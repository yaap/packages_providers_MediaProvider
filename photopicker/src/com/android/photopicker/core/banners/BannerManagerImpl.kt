/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.photopicker.core.banners

import android.os.UserHandle
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.database.DatabaseManager
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.network.NetworkMonitor
import com.android.photopicker.core.network.NetworkStatus
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.extensions.pmap
import com.android.photopicker.features.cloudmedia.CloudMediaFeature
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A production implementation of [BannerManager] */
class BannerManagerImpl(
    private val scope: CoroutineScope,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val configurationManager: ConfigurationManager,
    private val databaseManager: DatabaseManager,
    private val featureManager: FeatureManager,
    private val dataService: DataService,
    private val userMonitor: UserMonitor,
    private val networkMonitor: NetworkMonitor,
    private val processOwnerHandle: UserHandle,
) : BannerManager {

    companion object {
        val TAG = "PhotopickerBannerManager"
        const val GET_BANNER_PRIORITY_TIMEOUT_MS = 1000L
        private const val SYSTEM_PACKAGE_NAME = "system"
        private const val SYSTEM_APP_ID = 0
        private const val INVALID_APP_ID = -1
    }

    private val _bannerFlowBylocation =
        ConcurrentHashMap<BannerLocation, MutableStateFlow<Banner?>>()

    private val cachedBannerStatesMutex = Mutex()

    private var cachedBannerStates = mutableSetOf<BannerInteractionState>()

    private val bannersShownInSession: MutableSet<BannerDefinition> = mutableSetOf()

    private val networkStatus: StateFlow<NetworkStatus> = networkMonitor.networkStatus

    /**
     * Returns a [StateFlow] for the given [bannerLocation]. If a flow for the location does not yet
     * exist, it is created and stored. Subsequent calls for the same location return the existing
     * flow.
     */
    override fun getBannerFlow(bannerLocation: BannerLocation): StateFlow<Banner?> {
        return _bannerFlowBylocation.getOrPut(bannerLocation) { MutableStateFlow(null) }
    }

    /**
     * Keeps track of any banners with [DismissStrategy.SESSION] that were dismissed during the
     * current Photopicker session.
     */
    private val bannersDismissedInSession: MutableSet<BannerDeclaration> = mutableSetOf()

    init {
        scope.launch { loadInitialBannerStates() }
        // Observe Profile switches and always force banner refresh when the
        // user changes the active profile.
        scope.launch {
            userMonitor.userStatus
                .drop(1)
                .map { it.activeUserProfile }
                .distinctUntilChanged()
                .collect { refreshBanners() }
        }
        if (
            featureManager.isFeatureEnabled(CloudMediaFeature::class.java) &&
                configurationManager.configuration.value.flags.PICKER_OFFLINE_BANNERS_ENABLED
        ) {
            scope.launch {
                networkStatus
                    .drop(1)
                    .distinctUntilChanged()
                    .filter { it != NetworkStatus.Unknown }
                    .collect {
                        val currentCloudProvider: Provider? =
                            dataService.availableProviders.value.firstOrNull {
                                it.mediaSource == MediaSource.REMOTE
                            }
                        if (currentCloudProvider != null) {
                            refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
                            refreshBanner(BannerLocation.SEARCH_GRID_BANNER)
                        }
                    }
            }
        }
    }

    /** Upserts a banner interaction state in the cache in a thread-safe manner. */
    private suspend fun upsertCachedBannerState(newState: BannerInteractionState) {
        cachedBannerStatesMutex.withLock {
            cachedBannerStates.removeIf { it.bannerId == newState.bannerId }
            cachedBannerStates.add(newState)
        }
    }

    /** Finds a banner interaction state in the cache in a thread-safe manner. */
    private suspend fun findCachedBannerState(
        bannerDefinition: BannerDefinition
    ): BannerInteractionState? {
        return cachedBannerStatesMutex.withLock {
            cachedBannerStates.firstOrNull { it.bannerId == bannerDefinition }
        }
    }

    /**
     * Loads the initial state of banner interactions from the database into the local cache.
     *
     * This is run on the background dispatcher to avoid blocking the main thread.
     */
    private suspend fun loadInitialBannerStates() {
        if (configurationManager.configuration.value.flags.PICKER_BANNER_REDESIGN_ENABLED) {
            scope.launch(backgroundDispatcher) {
                try {
                    val callingPackageUid =
                        configurationManager.configuration.value.callingPackageUid ?: SYSTEM_APP_ID
                    val callingPackage =
                        configurationManager.configuration.value.callingPackage
                            ?: SYSTEM_PACKAGE_NAME
                    val states =
                        databaseManager
                            .acquireDao(BannerInteractionStateDao::class.java)
                            .getBannerInteractionStates(callingPackageUid, callingPackage)
                    cachedBannerStatesMutex.withLock {
                        cachedBannerStates.clear()
                        cachedBannerStates.addAll(states ?: emptyList())
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to load banner states from database", ex)
                }
            }
        }
    }

    /**
     * Attempt to show the requested banner for the given [BannerLocation].
     *
     * Unless a specific banner is needed, it is better to use [refreshBanners] to allow the banner
     * with the highest priority to be shown.
     */
    override suspend fun showBanner(banner: BannerDeclaration, bannerLocation: BannerLocation) {
        try {
            val newBanner = generateBanner(banner)
            _bannerFlowBylocation.getOrPut(bannerLocation) { MutableStateFlow(newBanner) }.value =
                newBanner
        } catch (ex: Exception) {
            // Avoid a crash if the banner cannot be generated
            // Instead do nothing and return.
            Log.e(TAG, "Could not show banner: ${banner.id} at $bannerLocation", ex)
            return
        }
    }

    /**
     * Attempt to show the requested [BannerDefinition] for the given [BannerLocation].
     *
     * Unless a specific banner is needed, it is better to use [refreshBanners] to allow the banner
     * with the highest priority to be shown.
     */
    @VisibleForTesting
    override suspend fun showBanner(
        bannerDefinition: BannerDefinition,
        bannerLocation: BannerLocation,
    ) {
        try {
            val newBanner = generateBannerFromDefinition(bannerDefinition)
            _bannerFlowBylocation.getOrPut(bannerLocation) { MutableStateFlow(newBanner) }.value =
                newBanner
        } catch (ex: Exception) {
            // Avoid a crash if the banner cannot be generated
            // Instead do nothing and return.
            Log.e(TAG, "Could not show banner: ${bannerDefinition.id} at $bannerLocation", ex)
            return
        }
    }

    /** Hides all banners for all known Banner locations. (But does not mark them as dismissed) */
    override fun hideBanners() {
        _bannerFlowBylocation.values.forEach { mutableFlow -> mutableFlow.value = null }
    }

    /**
     * Determines the target application package name for a given banner.
     *
     * @param bannerDefinition The banner definition.
     * @return The calling package name if the banner is dismissible per UID, otherwise "system".
     */
    private fun getTargetAppName(bannerDefinition: BannerDefinition): String? {
        return if (bannerDefinition.dismissiblePer == BannerDismissStrategy.PER_UID) {
            configurationManager.configuration.value.callingPackage ?: null
        } else {
            SYSTEM_PACKAGE_NAME
        }
    }

    /**
     * Determines the target application UID for a given banner.
     *
     * @param bannerDefinition The banner definition.
     * @return The calling package UID if the banner is dismissible per UID, otherwise the system
     *   UID (0).
     */
    private fun getTargetAppId(bannerDefinition: BannerDefinition): Int {
        return if (bannerDefinition.dismissiblePer == BannerDismissStrategy.PER_UID) {
            configurationManager.configuration.value.callingPackageUid ?: INVALID_APP_ID
        } else {
            SYSTEM_APP_ID
        }
    }

    /**
     * Persists the provided [BannerInteractionState] to the database.
     *
     * @param state The state to save.
     */
    private suspend fun saveBannerInteractionState(state: BannerInteractionState) {
        withContext(backgroundDispatcher) {
            try {
                databaseManager
                    .acquireDao(BannerInteractionStateDao::class.java)
                    .setBannerInteractionState(state)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to save banner state to database", ex)
            }
        }
    }

    /**
     * Marks a [BannerDefinition] as dismissed by the user.
     *
     * Updates the local cache and persists the dismissal state to the database. Triggers a banner
     * refresh to update the UI.
     *
     * @param bannerDefinition The banner definition to mark as dismissed.
     */
    override suspend fun markBannerAsManuallyDismissed(bannerDefinition: BannerDefinition) {
        if (!bannerDefinition.manualDismissible) return

        val targetAppId = getTargetAppId(bannerDefinition)
        val targetAppName = getTargetAppName(bannerDefinition)
        if (targetAppName == null || targetAppId == INVALID_APP_ID) {
            // If there is no calling app info set in configuration then
            // dismissible state for the banner can't actually be updated.
            Log.w(
                TAG,
                "Cannot mark ${bannerDefinition.id} as dismissed for UID," +
                    " no UID/callingPackage present in configuration.",
            )
            return
        }

        val currentState = findCachedBannerState(bannerDefinition)
        if (currentState?.isDismissed == true) {
            // Return early if the banner is already dismissed in the cache.
            refreshBanners()
            return
        }

        val newState =
            currentState?.copy(isDismissed = true)
                ?: BannerInteractionState(
                    bannerId = bannerDefinition,
                    appUid = targetAppId,
                    packageName = targetAppName,
                    isDismissed = true,
                    shownCount = 1, // Initial show count on manual dismiss
                )

        upsertCachedBannerState(newState)
        saveBannerInteractionState(newState)
        refreshBanners()
    }

    /**
     * Attempt to mark the banner as dismissed in current context. This triggers a refresh for all
     * active banner locations to ensure the UI is updated consistently.
     */
    override suspend fun markBannerAsDismissed(banner: BannerDeclaration) {

        if (banner.dismissable) {

            // For SESSION dismissableStrategy banners, rather than writing state to the database,
            // add the banner to the dismissed set that for this Photopicker session.
            if (banner.dismissableStrategy == DismissStrategy.SESSION) {
                bannersDismissedInSession.add(banner)
                refreshBanners()
                return
            }

            // For all other strategies, update the Database state for the current banner.
            setBannerState(
                BannerState(
                    bannerId = banner.id,
                    uid =
                        when (banner.dismissableStrategy) {
                            DismissStrategy.PER_UID ->
                                configurationManager.configuration.value.callingPackageUid
                                    ?: run {
                                        // If there is no Uid set in the configuration, this
                                        // dismiss-able state can't actually be updated.
                                        Log.w(
                                            TAG,
                                            "Cannot mark ${banner.id} as dismissed for UID," +
                                                " no UID present in configuration.",
                                        )
                                        return@markBannerAsDismissed
                                    }
                            DismissStrategy.ONCE -> 0
                            DismissStrategy.SESSION -> {
                                return@markBannerAsDismissed
                            }
                            DismissStrategy.NONE -> {
                                Log.w(TAG, "Cannot mark non-dismissable banner as dismissed.")
                                return@markBannerAsDismissed
                            }
                        },
                    dismissed = true,
                )
            )
            refreshBanners()
        }
    }

    /** Retrieve the requested banner state from the database */
    override suspend fun getBannerState(banner: BannerDeclaration): BannerState? {

        // No need to check the database if the banner cannot be dismissed.
        if (banner.dismissableStrategy == DismissStrategy.NONE) {
            return null
        }

        // For SESSION dismissal, rely on the dismissed map. If the Banner is
        // present in the already-dismissed set, mark it as dismissed.
        if (banner.dismissableStrategy == DismissStrategy.SESSION) {
            return BannerState(
                bannerId = banner.id,
                uid = configurationManager.configuration.value.callingPackageUid ?: 0,
                dismissed = bannersDismissedInSession.contains(banner),
            )
        }

        return withContext(backgroundDispatcher) {
            try {
                databaseManager
                    .acquireDao(BannerStateDao::class.java)
                    .getBannerState(
                        bannerId = banner.id,
                        uid =
                            when (banner.dismissableStrategy) {
                                DismissStrategy.PER_UID ->
                                    checkNotNull(
                                        configurationManager.configuration.value.callingPackageUid
                                    ) {
                                        "No callingPackageUid"
                                    }
                                DismissStrategy.ONCE -> 0
                                else -> 0
                            },
                    )
            } catch (ex: IllegalStateException) {
                Log.w(
                    TAG,
                    "Attempted to retrieve a PER_UID banner state and no uid was present in the" +
                        " configuration.",
                    ex,
                )
                null
            }
        }
    }

    /** Persist the banner state to the database */
    override suspend fun setBannerState(bannerState: BannerState) {
        withContext(backgroundDispatcher) {
            databaseManager.acquireDao(BannerStateDao::class.java).setBannerState(bannerState)
        }
    }

    /**
     * Refreshes banners for all possible [BannerLocation]s. For each location, this method
     * initialize/re-evaluates the banner state for that location, and displays the one with the
     * highest priority.
     */
    override suspend fun refreshBanners() {

        Log.d(TAG, "Refresh of banners was requested.")

        // [BannerState] is not accessible cross-profile, so any time the [activeUserProfile]
        // is not the Process owner's profile, banners need to be hidden to avoid showing
        // banner content that is not relevant to the active profile.
        if (
            userMonitor.userStatus.value.activeUserProfile.handle.identifier !=
                processOwnerHandle.getIdentifier()
        ) {
            Log.d(
                TAG,
                "User profile has been changed and is no longer owner, banners will be cleared for all locations.",
            )
            hideBanners()
            return
        }
        val locationsToRefresh = BannerLocation.values().toList()
        locationsToRefresh.forEach { location ->
            if (configurationManager.configuration.value.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                refreshBannerInternalAutoDismissable(location)
            } else {
                refreshBannerInternal(location)
            }
        }
    }

    /**
     * Refreshes the banner for a specific [BannerLocation]. It fetches all available banners from
     * enabled features, determines their priority and displays the one with the highest priority.
     */
    override suspend fun refreshBanner(bannerLocation: BannerLocation) {
        Log.d(TAG, "Refresh of banners was requested for location: $bannerLocation.")

        // [BannerState] is not accessible cross-profile, so any time the [activeUserProfile]
        // is not the Process owner's profile, banners need to be hidden to avoid showing
        // banner content that is not relevant to the active profile.
        if (
            userMonitor.userStatus.value.activeUserProfile.handle.identifier !=
                processOwnerHandle.getIdentifier()
        ) {
            Log.d(
                TAG,
                "User profile has been changed and is no longer owner, banners will be cleared.",
            )
            hideBanners()
            return
        }
        if (configurationManager.configuration.value.flags.PICKER_BANNER_REDESIGN_ENABLED) {
            refreshBannerInternalAutoDismissable(bannerLocation)
        } else {
            refreshBannerInternal(bannerLocation)
        }
    }

    /**
     * Evaluates available banners for a given [BannerLocation], selects the one with the highest
     * priority, and updates the corresponding banner flow.
     *
     * This function performs the core logic for banner refreshes. It gathers all banners from
     * enabled UI features, calculates their priority for the specified location, and displays the
     * top-priority banner. If no suitable banner is found, the existing banner for that location is
     * cleared.
     */
    private suspend fun refreshBannerInternal(bannerLocation: BannerLocation) {
        // Force this work to the background
        withContext(backgroundDispatcher) {
            val currentNetworkStatus =
                if (configurationManager.configuration.value.flags.PICKER_OFFLINE_BANNERS_ENABLED) {
                    networkStatus.value
                } else {
                    NetworkStatus.Unknown
                }

            // Always ensure providers before requesting a banner refresh, banners depend on
            // having accurate provider information to generate the correct banners.
            dataService.ensureProviders()

            // Acquire all possible active banners and their relative priority from
            // the enabled ui features.
            val allAvailableBanners: MutableList<Pair<BannerDeclaration, Int>> =
                featureManager.enabledUiFeatures
                    // FlatMap from List<List<Pair<PhotopickerUiFeature,BannerDeclaration>>> to a
                    // single Iterable list so the work can be run in parallel in the next step.
                    .flatMap { feature -> feature.ownedBanners.map { Pair(feature, it) } }
                    // Use [pmap] to launch these in parallel, so each banner checked
                    // does not accumulate the total time of this call.
                    .pmap { (feature, banner) ->
                        val priority =
                            try {
                                // Calls to acquire the banner's priority. This call has a time
                                // limit as the feature may be requesting external data that may
                                // take too long to respond. Calls that exceed the time limit will
                                // be assigned a -1 priority.
                                withTimeout(GET_BANNER_PRIORITY_TIMEOUT_MS) {
                                    feature.getBannerPriority(
                                        banner,
                                        getBannerState(banner),
                                        configurationManager.configuration.value,
                                        dataService,
                                        userMonitor,
                                        currentNetworkStatus,
                                        bannerLocation,
                                    )
                                }
                            } catch (_: TimeoutCancellationException) {
                                Log.v(
                                    TAG,
                                    "getBannerPriority timed out for ${banner.id} at $bannerLocation",
                                )
                                // In the event of a timeout, return a negative number so
                                // that the banner will be skipped
                                -1
                            }

                        Pair(banner, priority)
                    }
                    .filter { it.second >= 0 } // Skip any banners with a priority below zero
                    .toMutableList()

            // Finally, sort by the reported priorities.
            // This is a stable sort that will order elements by Priority first, feature
            // banner registration order second, and overall feature registration order third.
            allAvailableBanners.sortByDescending { it.second }

            val highestPriorityBanner = allAvailableBanners.firstOrNull()
            val newBanner =
                try {
                    highestPriorityBanner?.let { generateBanner(it.first) }
                } catch (ex: Exception) {
                    Log.e(
                        TAG,
                        "Could not generate banner: ${highestPriorityBanner?.first?.id} for $bannerLocation",
                        ex,
                    )
                    null
                }

            if (newBanner != null) {
                Log.d(
                    TAG,
                    "Banner refresh completed for $bannerLocation, ${newBanner.declaration.id} will be shown",
                )
            } else {
                Log.d(TAG, "Banner refresh completed for $bannerLocation, no banner was selected.")
            }
            _bannerFlowBylocation.getOrPut(bannerLocation) { MutableStateFlow(newBanner) }.value =
                newBanner
        }
    }

    /**
     * Internal implementation for refreshing banners when the flag
     * {Flags.PICKER_BANNER_REDESIGN_ENABLED} is enabled.
     *
     * Calculates priorities for available banners, handles auto-dismissal logic (recording views),
     * and updates the banner flow for the specified location.
     *
     * @param bannerLocation The location to refresh.
     */
    private suspend fun refreshBannerInternalAutoDismissable(bannerLocation: BannerLocation) {
        withContext(backgroundDispatcher) {
            // Always ensure providers before requesting a banner refresh, banners depend on
            // having accurate provider information to generate the correct banners.
            dataService.ensureProviders()

            val allAvailableBanners: MutableList<Pair<BannerDefinition, Int>> =
                featureManager.enabledUiFeatures
                    .flatMap { feature ->
                        feature.ownedBannersDefinitions.map { Pair(feature, it) }
                    }
                    .pmap { (feature, bannerDefinition) ->
                        val priority =
                            try {
                                withTimeout(GET_BANNER_PRIORITY_TIMEOUT_MS) {
                                    feature.getBannerPriority(
                                        bannerDefinition,
                                        findCachedBannerState(bannerDefinition),
                                        configurationManager.configuration.value,
                                        dataService,
                                        userMonitor,
                                        bannerLocation,
                                    )
                                }
                            } catch (e: TimeoutCancellationException) {
                                -1
                            }
                        Pair(bannerDefinition, priority)
                    }
                    .filter { it.second >= 0 }
                    .toMutableList()

            allAvailableBanners.sortByDescending { it.second }

            val highestPriorityBannerDefinition = allAvailableBanners.firstOrNull()?.first
            val newBanner =
                highestPriorityBannerDefinition?.let {
                    try {
                        generateBannerFromDefinition(it)
                    } catch (ex: Exception) {
                        Log.e(
                            TAG,
                            "Could not generate banner from table: ${it.name} for $bannerLocation",
                            ex,
                        )
                        null
                    }
                }

            if (newBanner != null) {
                val bannerDef = newBanner.bannerDefinition

                // Only record the banner as shown if it hasn't been dismissed yet.
                // We use [bannersShownInSession] to avoid incrementing the show
                // count multiple times if the banner is refreshed within the
                // same session.
                if (findCachedBannerState(bannerDef)?.isDismissed != true) {
                    if (!bannersShownInSession.contains(bannerDef)) {
                        bannersShownInSession.add(bannerDef)
                        recordBannerShown(bannerDef)
                    }
                }
                Log.d(
                    TAG,
                    "AutoDismiss Banner refresh for $bannerLocation: ${bannerDef.name} will be shown",
                )
                _bannerFlowBylocation
                    .getOrPut(bannerLocation) { MutableStateFlow(newBanner) }
                    .value = newBanner
            } else {
                Log.d(TAG, "AutoDismiss Banner refresh for $bannerLocation: No banner selected.")
                _bannerFlowBylocation.getOrPut(bannerLocation) { MutableStateFlow(null) }.value =
                    null
            }
        }
    }

    /**
     * Locates the [PhotopickerUiFeature] responsible for building the [BannerDeclaration] and calls
     * the factory builder.
     *
     * @param [BannerDeclaration] to acquire an implementation for.
     * @return a [Banner] implementation for the provided [BannerDeclaration]
     */
    private suspend fun generateBanner(banner: BannerDeclaration): Banner {
        val feature: PhotopickerUiFeature? =
            featureManager.enabledUiFeatures
                .filter { it.ownedBanners.contains(banner) }
                .firstOrNull()
        checkNotNull(feature) { "Could not find an enabled builder for $banner" }
        return feature.buildBanner(
            checkNotNull(banner as? BannerDefinitions) {
                "Could not cast declaration to valid banner definition"
            },
            dataService,
            userMonitor,
            configurationManager.configuration.value,
        )
    }

    /**
     * Records that a banner has been shown to the user.
     *
     * Increments the show count for the banner. If the show count reaches the maximum allowed, the
     * banner is marked as dismissed.
     *
     * @param bannerDefinition The definition of the banner that was shown.
     */
    private suspend fun recordBannerShown(bannerDefinition: BannerDefinition) {
        if (!bannerDefinition.autoDismissible || bannerDefinition.maxShowCount == null) return

        val targetAppId = getTargetAppId(bannerDefinition)
        val targetAppName = getTargetAppName(bannerDefinition)
        if (targetAppName == null || targetAppId == INVALID_APP_ID) {
            // If there is no calling app info set in configuration then
            // dismissible state for the banner can't actually be updated.
            Log.w(
                TAG,
                "Cannot mark ${bannerDefinition} as shown UID," +
                    " no UID present in configuration.",
            )
            return
        }
        val maxShow = bannerDefinition.maxShowCount

        val currentState = findCachedBannerState(bannerDefinition)
        val currentCount = currentState?.shownCount ?: 0
        val newCount = currentCount + 1
        val isDismissed = newCount >= maxShow

        val newState =
            BannerInteractionState(
                bannerId = bannerDefinition,
                appUid = targetAppId,
                packageName = targetAppName,
                isDismissed = isDismissed,
                shownCount = newCount,
            )

        upsertCachedBannerState(newState)
        saveBannerInteractionState(newState)

        // Refresh banners if this update caused the banner to be dismissed
        if (newState.isDismissed) {
            refreshBanners()
        }
    }

    /**
     * Generates a [Banner] instance from a [BannerDefinition].
     *
     * Finds the feature that owns the banner definition and delegates the creation to it.
     *
     * @param bannerDefinition The definition of the banner to create.
     * @return The created [Banner] instance.
     * @throws IllegalStateException if no feature is found for the banner definition.
     */
    private suspend fun generateBannerFromDefinition(bannerDefinition: BannerDefinition): Banner {
        val feature: PhotopickerUiFeature? =
            featureManager.enabledUiFeatures
                .filter { it.ownedBannersDefinitions.contains(bannerDefinition) }
                .firstOrNull()
        checkNotNull(feature) { "Could not find an enabled builder for $bannerDefinition" }

        return feature.buildBanner(
            checkNotNull(bannerDefinition as? BannerDefinition) {
                "Could not cast declaration to valid banner definition"
            },
            dataService,
            userMonitor,
        )
    }
}
