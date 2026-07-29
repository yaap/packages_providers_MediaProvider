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

package com.android.photopicker.features.cloudmedia

import android.content.Intent
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.photopicker.R
import com.android.photopicker.core.banners.Banner
import com.android.photopicker.core.banners.BannerDefinition
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.banners.BannerInteractionState
import com.android.photopicker.core.banners.BannerLocation
import com.android.photopicker.core.banners.BannerState
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.features.Priority
import com.android.photopicker.core.navigation.Route
import com.android.photopicker.core.network.NetworkStatus
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Icon
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.overflowmenu.OverflowMenuItem
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * Feature class for the Photopicker's cloud media implementation.
 *
 * This feature adds the Cloud media preloader for preloading off-device content before the
 * Photopicker session ends.
 */
class CloudMediaFeature : PhotopickerUiFeature {
    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerCloudMediaFeature"

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ): Boolean {

            // Cloud media is not available in permission mode.
            if (config.action == MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP) return false

            return config.flags.CLOUD_MEDIA_ENABLED &&
                config.flags.CLOUD_ALLOWED_PROVIDERS.isNotEmpty()
        }

        override fun build(featureManager: FeatureManager) = CloudMediaFeature()
    }

    override val token = FeatureToken.CLOUD_MEDIA.token

    /**
     * A map that defines which banners are available for each [BannerLocation] for this feature.
     * This allows for a generic check to ensure a banner is only evaluated for its intended
     * location.
     */
    private val ownedBannersByLocation: Map<BannerLocation, Set<BannerDefinitions>> =
        mapOf(
            BannerLocation.PHOTO_GRID_BANNER to
                setOf(
                    BannerDefinitions.DEVICE_NETWORK_UNAVAILABLE,
                    BannerDefinitions.CLOUD_CHOOSE_ACCOUNT,
                    BannerDefinitions.CLOUD_CHOOSE_PROVIDER,
                    BannerDefinitions.CLOUD_MEDIA_AVAILABLE,
                ),
            BannerLocation.SEARCH_GRID_BANNER to
                setOf(BannerDefinitions.CLOUD_SEARCH_RESULTS_OFFLINE),
        )

    override val ownedBanners: Set<BannerDefinitions> =
        ownedBannersByLocation.values.flatten().toSet()

    private val ownedBannersDefinitionByLocation: Map<BannerLocation, Set<BannerDefinition>> =
        mapOf(
            BannerLocation.PHOTO_GRID_BANNER to
                setOf(
                    BannerDefinition.CLOUD_CHOOSE_ACCOUNT,
                    BannerDefinition.CLOUD_CHOOSE_PROVIDER,
                    BannerDefinition.CLOUD_MEDIA_AVAILABLE,
                )
        )

    override val ownedBannersDefinitions: Set<BannerDefinition> =
        ownedBannersDefinitionByLocation.values.flatten().toSet()

    override suspend fun getBannerPriority(
        bannerDefinition: BannerDefinition,
        bannerInteractionState: BannerInteractionState?,
        config: PhotopickerConfiguration,
        dataService: DataService,
        userMonitor: UserMonitor,
        bannerLocation: BannerLocation,
    ): Int {
        val isEmbedded = config.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
        val isValidForLocation =
            ownedBannersDefinitionByLocation[bannerLocation]?.contains(bannerDefinition) ?: false
        if (bannerInteractionState?.isDismissed == true || !isValidForLocation) {
            return Priority.DISABLED.priority
        }
        // Attempt to find a [REMOTE] provider in the available list of providers.
        val currentCloudProvider: Provider? =
            dataService.availableProviders.value.firstOrNull {
                it.mediaSource == MediaSource.REMOTE
            }

        // If one is found, fetch the collectionInfo for that provider.
        val collectionInfo: CollectionInfo? =
            currentCloudProvider?.let { dataService.getCollectionInfo(it) }

        return when (bannerDefinition) {
            BannerDefinition.CLOUD_CHOOSE_PROVIDER -> {
                return when {
                    // Don't show in Embedded, as it is not supported.
                    // TODO(b/489945007): To be enabled in Embedded picker.
                    isEmbedded -> Priority.DISABLED.priority

                    // If there is no current provider, but a list of allowed providers exists
                    currentCloudProvider == null &&
                        dataService.getAllAllowedProviders().isNotEmpty() ->
                        bannerDefinition.priority.priority

                    // There's a cloud provider set, so don't show
                    else -> Priority.DISABLED.priority
                }
            }
            BannerDefinition.CLOUD_CHOOSE_ACCOUNT -> {
                collectionInfo?.let {
                    when {
                        // Don't show in Embedded, as the banner starts an activity which can cause
                        // a crash.
                        // TODO(b/489945007): To be enabled in Embedded.
                        isEmbedded -> Priority.DISABLED.priority

                        // If there is no current cloud provider account
                        it.accountName == null -> bannerDefinition.priority.priority

                        // There's a cloud provider account set, so don't show
                        else -> Priority.DISABLED.priority
                    }
                } ?: Priority.DISABLED.priority
            }
            BannerDefinition.CLOUD_MEDIA_AVAILABLE -> {
                collectionInfo?.let {
                    if (it.accountName != null && it.collectionId != null) {
                        bannerDefinition.priority.priority
                    } else {
                        Priority.DISABLED.priority
                    }
                } ?: Priority.DISABLED.priority
            }
            else ->
                throw IllegalArgumentException(
                    "$TAG cannot build the requested banner: $bannerDefinition"
                )
        }
    }

    override suspend fun getBannerPriority(
        banner: BannerDefinitions,
        bannerState: BannerState?,
        config: PhotopickerConfiguration,
        dataService: DataService,
        userMonitor: UserMonitor,
        networkStatus: NetworkStatus,
        bannerLocation: BannerLocation,
    ): Int {

        val isEmbedded = config.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED

        // Banners owned by [CloudMediaFeature] have been previously dismissed or are not valid
        // for the current location should be disabled immediately.
        val isValidForLocation = ownedBannersByLocation[bannerLocation]?.contains(banner) ?: false
        if (bannerState?.dismissed == true || !isValidForLocation) {
            return Priority.DISABLED.priority
        }

        // Attempt to find a [REMOTE] provider in the available list of providers.
        val currentCloudProvider: Provider? =
            dataService.availableProviders.value.firstOrNull {
                it.mediaSource == MediaSource.REMOTE
            }

        // If one is found, fetch the collectionInfo for that provider.
        val collectionInfo: CollectionInfo? =
            currentCloudProvider?.let { dataService.getCollectionInfo(it) }

        val areOfflineBannersEnabled =
            config.flags.PICKER_OFFLINE_BANNERS_ENABLED && currentCloudProvider != null

        return when (banner) {
            BannerDefinitions.CLOUD_SEARCH_RESULTS_OFFLINE -> {
                if (areOfflineBannersEnabled && networkStatus == NetworkStatus.Unavailable) {
                    return Priority.HIGHEST.priority
                } else {
                    return Priority.DISABLED.priority
                }
            }
            BannerDefinitions.DEVICE_NETWORK_UNAVAILABLE -> {
                if (areOfflineBannersEnabled && networkStatus == NetworkStatus.Unavailable) {
                    return Priority.HIGHEST.priority
                } else {
                    return Priority.DISABLED.priority
                }
            }
            BannerDefinitions.CLOUD_CHOOSE_PROVIDER -> {
                return when {
                    // Don't show in Embedded, as the banner starts an activity which can cause a
                    // crash.
                    isEmbedded -> Priority.DISABLED.priority

                    // If there is no current provider, but a list of allowed providers exists
                    currentCloudProvider == null &&
                        dataService.getAllAllowedProviders().isNotEmpty() ->
                        Priority.MEDIUM.priority

                    // There's a cloud provider set, so don't show
                    else -> Priority.DISABLED.priority
                }
            }
            BannerDefinitions.CLOUD_CHOOSE_ACCOUNT -> {
                collectionInfo?.let {
                    when {
                        // Don't show in Embedded, as the banner starts an activity which can cause
                        // a crash.
                        isEmbedded -> Priority.DISABLED.priority

                        // If there is no current cloud provider account
                        it.accountName == null -> Priority.MEDIUM.priority

                        // There's a cloud provider account set, so don't show
                        else -> Priority.DISABLED.priority
                    }
                } ?: Priority.DISABLED.priority
            }
            BannerDefinitions.CLOUD_MEDIA_AVAILABLE -> {
                collectionInfo?.let {
                    if (it.accountName != null && it.collectionId != null) {
                        Priority.MEDIUM.priority
                    } else {
                        Priority.DISABLED.priority
                    }
                } ?: Priority.DISABLED.priority
            }
            else ->
                throw IllegalArgumentException("$TAG cannot build the requested banner: $banner")
        }
    }

    override suspend fun buildBanner(
        banner: BannerDefinitions,
        dataService: DataService,
        userMonitor: UserMonitor,
        configuration: PhotopickerConfiguration,
    ): Banner {

        val cloudProvider: Provider? =
            dataService.availableProviders.value.firstOrNull {
                it.mediaSource == MediaSource.REMOTE
            }

        val collectionInfo: CollectionInfo? =
            cloudProvider?.let { dataService.getCollectionInfo(it) }

        val providerIcon: Icon? =
            cloudProvider?.let { dataService.providerToIconMap.value.getOrDefault(it, null) }

        return when (banner) {
            BannerDefinitions.CLOUD_CHOOSE_PROVIDER -> cloudChooseProviderBanner
            BannerDefinitions.CLOUD_CHOOSE_ACCOUNT ->
                buildCloudChooseAccountBanner(
                    cloudProvider =
                        checkNotNull(cloudProvider) { "cloudProvider was null during buildBanner" },
                    collectionInfo =
                        checkNotNull(collectionInfo) {
                            "collectionInfo was null during buildBanner"
                        },
                    providerIcon = providerIcon,
                )
            BannerDefinitions.CLOUD_MEDIA_AVAILABLE ->
                buildCloudMediaAvailableBanner(
                    cloudProvider =
                        checkNotNull(cloudProvider) { "cloudProvider was null during buildBanner" },
                    collectionInfo =
                        checkNotNull(collectionInfo) {
                            "collectionInfo was null during buildBanner"
                        },
                    providerIcon = providerIcon,
                )
            BannerDefinitions.CLOUD_SEARCH_RESULTS_OFFLINE ->
                buildSearchResultsOfflineBanner(
                    cloudProvider =
                        checkNotNull(cloudProvider) { "cloudProvider was null during buildBanner" }
                )
            BannerDefinitions.DEVICE_NETWORK_UNAVAILABLE ->
                buildNoNetworkAvailableBanner(
                    cloudProvider =
                        checkNotNull(cloudProvider) { "cloudProvider was null during buildBanner" },
                    isEmbedded = configuration.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED,
                )
            else ->
                throw IllegalArgumentException("$TAG cannot build the requested banner: $banner")
        }
    }

    /** Events consumed by Cloud Media */
    override val eventsConsumed = setOf<RegisteredEventClass>()

    /** Events produced by the Cloud Media */
    override val eventsProduced =
        setOf<RegisteredEventClass>(
            Event.LogPhotopickerMenuInteraction::class.java,
            Event.LogPhotopickerUIEvent::class.java,
        )

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(
            // Medium priority for OVERFLOW_MENU_ITEMS so that [BrowseFeature] can
            // have the top spot if it's enabled.
            Pair(Location.OVERFLOW_MENU_ITEMS, Priority.MEDIUM.priority)
        )
    }

    override fun registerNavigationRoutes(): Set<Route> {
        return setOf()
    }

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
        val events = LocalEvents.current
        val scope = rememberCoroutineScope()
        val configuration = LocalPhotopickerConfiguration.current
        when (location) {
            Location.OVERFLOW_MENU_ITEMS -> {
                val context = LocalContext.current
                val clickAction = params as? LocationParams.WithClickAction
                OverflowMenuItem(
                    label = stringResource(R.string.photopicker_overflow_cloud_media_app),
                    onClick = {
                        clickAction?.onClick()
                        context.startActivity(Intent(MediaStore.ACTION_PICK_IMAGES_SETTINGS))
                        // Dispatch event to log user's interactiuon with the cloud settings menu
                        // item in the photopicker
                        scope.launch {
                            events.dispatch(
                                Event.LogPhotopickerMenuInteraction(
                                    token,
                                    configuration.sessionId,
                                    configuration.callingPackageUid ?: -1,
                                    Telemetry.MenuItemSelected.CLOUD_SETTINGS,
                                )
                            )
                        }
                    },
                )
            }
            else -> {}
        }
    }

    override suspend fun buildBanner(
        bannerDefinition: BannerDefinition,
        dataService: DataService,
        userMonitor: UserMonitor,
    ): Banner {

        val cloudProvider: Provider? =
            dataService.availableProviders.value.firstOrNull {
                it.mediaSource == MediaSource.REMOTE
            }

        val collectionInfo: CollectionInfo? =
            cloudProvider?.let { dataService.getCollectionInfo(it) }

        val providerIcon: Icon? =
            cloudProvider?.let { dataService.providerToIconMap.value.getOrDefault(it, null) }

        return when (bannerDefinition) {
            BannerDefinition.CLOUD_CHOOSE_PROVIDER -> cloudChooseProviderBanner
            BannerDefinition.CLOUD_CHOOSE_ACCOUNT ->
                buildCloudChooseAccountBanner(
                    cloudProvider =
                        checkNotNull(cloudProvider) { "cloudProvider was null during buildBanner" },
                    collectionInfo =
                        checkNotNull(collectionInfo) {
                            "collectionInfo was null during buildBanner"
                        },
                    providerIcon = providerIcon,
                )
            BannerDefinition.CLOUD_MEDIA_AVAILABLE ->
                buildCloudMediaAvailableBanner(
                    cloudProvider =
                        checkNotNull(cloudProvider) { "cloudProvider was null during buildBanner" },
                    collectionInfo =
                        checkNotNull(collectionInfo) {
                            "collectionInfo was null during buildBanner"
                        },
                    providerIcon = providerIcon,
                )
            else ->
                throw IllegalArgumentException(
                    "$TAG cannot build the requested banner: $bannerDefinition"
                )
        }
    }
}
