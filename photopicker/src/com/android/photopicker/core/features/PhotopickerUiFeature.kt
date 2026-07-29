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

package com.android.photopicker.core.features

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.photopicker.core.banners.Banner
import com.android.photopicker.core.banners.BannerDefinition
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.banners.BannerInteractionState
import com.android.photopicker.core.banners.BannerLocation
import com.android.photopicker.core.banners.BannerState
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.navigation.Route
import com.android.photopicker.core.network.NetworkStatus
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService

/**
 * All Features that wish to add composables to the UI must implement this interface.
 *
 * It provides hooks for registering composables into the Feature framework's Location tree, and
 * calls it's composable hook during runtime.
 */
interface PhotopickerUiFeature : PhotopickerFeature {

    /**
     * A set of banners which this feature owns the implementation of.
     *
     * Features will only receive banner related callbacks for the banners in its ownedBanners
     * declaration.
     */
    val ownedBanners: Set<BannerDefinitions>
        get() = emptySet<BannerDefinitions>()

    /**
     * The set of [BannerDefinition]s that this feature implements.
     *
     * These definitions describe banners that support the new interaction model, including
     * configuration for auto-dismissal, persistence, and priority.
     *
     * Defaults to an empty set.
     */
    val ownedBannersDefinitions: Set<BannerDefinition>
        get() = emptySet<BannerDefinition>()

    /**
     * When computing the current banner state the [BannerManager] will call this method for each
     * [BannerDefinitions] in a feature's ownedBanners declaration.
     *
     * BannerManager will provide some additional inputs and then the feature must decide the
     * current priority of the [BannerDefinitions] given the current [BannerState] and
     * [PhotopickerConfiguration] as well as any additional data that needs to be fetched from the
     * [DataService].
     *
     * After all banner implementations have responded, the BannerManager will update the banner
     * state with the assigned display priority.
     *
     * While it's OK to fetch data from [DataService]; expensive or slow calls should try to be
     * avoided as much as possible, as slow responses may be skipped if it exceeds the configured
     * timeout for this call.
     *
     * A priority MUST be returned, but if the banner should never be shown under the current state,
     * then [Priority.DISABLED] should be returned.
     *
     * @param banner The unique BannerDefinition for the banner being requested.
     * @param bannerState the persisted BannerState, if it exists.
     * @param config The current [PhotopickerConfiguration]
     * @param dataService A dataService that can be used to fetch external data.
     * @param userMonitor UserMonitor for UserProfile access.
     * @param bannerLocation The [BannerLocation] where the banner will be displayed.
     */
    suspend fun getBannerPriority(
        banner: BannerDefinitions,
        bannerState: BannerState?,
        config: PhotopickerConfiguration,
        dataService: DataService,
        userMonitor: UserMonitor,
        networkStatus: NetworkStatus,
        bannerLocation: BannerLocation,
    ): Int {
        return Priority.DISABLED.priority
    }

    /**
     * Calculates the priority of a [BannerDefinition] to be shown at a specific [BannerLocation].
     *
     * This method is used by the [BannerManager] to determine which banner should be displayed when
     * multiple banners are available for the same location.
     *
     * @param bannerDefinition The definition of the banner being evaluated.
     * @param bannerInteractionState The current interaction state (e.g. dismissed status, shown
     *   count) retrieved from the database for this banner.
     * @param config The current [PhotopickerConfiguration].
     * @param dataService The [DataService] for querying media or provider data necessary to
     *   determine eligibility.
     * @param userMonitor The [UserMonitor] for checking user profile status.
     * @param location The [BannerLocation] where the banner is intended to be shown.
     * @return An integer representing the priority. Higher values take precedence. Return -1 if the
     *   banner should not be shown at this time.
     */
    suspend fun getBannerPriority(
        bannerDefinition: BannerDefinition,
        bannerInteractionState: BannerInteractionState?,
        config: PhotopickerConfiguration,
        dataService: DataService,
        userMonitor: UserMonitor,
        bannerLocation: BannerLocation,
    ): Int {
        return Priority.DISABLED.priority
    }

    /**
     * This is a factory method for providing an implementation of a [BannerDefinitions]. This
     * factory must always return a [Banner]. The [BannerManager] guarantees that this method will
     * only be called for any [BannerDefinition]s that are in the [ownedBanners] declaration.
     *
     * @param banner The [BannerDefinitions] that should be constructed.
     * @param dataService A dataService that can be used to fetch external data.
     * @param userMonitor UserMonitor for UserProfile access.
     * @param configuration The current [PhotopickerConfiguration].
     * @return A [Banner] implementation for the requested [BannerDefinitions]
     */
    suspend fun buildBanner(
        banner: BannerDefinitions,
        dataService: DataService,
        userMonitor: UserMonitor,
        configuration: PhotopickerConfiguration,
    ): Banner {
        throw IllegalArgumentException("Cannot build the requested banner: ${banner.id}")
    }

    /**
     * Factory method to build a [Banner] from a [BannerDefinition]. The [BannerManager] calls this
     * method to construct the banner that has been selected as the highest priority for a given
     * [BannerLocation]. The framework guarantees that this method will only be called for banners
     * that the feature has declared in its [ownedBannersDefinitions] set.
     *
     * This method is used when the PICKER_BANNER_REDESIGN_ENABLED flag is enabled.
     *
     * @param bannerDefinition The [BannerDefinition] to construct.
     * @param dataService A [DataService] that can be used to fetch external data required for the
     *   banner.
     * @param userMonitor A [UserMonitor] for accessing user profile information.
     * @return A [Banner] implementation for the requested [BannerDefinition].
     * @throws IllegalArgumentException if the feature cannot build the requested banner.
     */
    suspend fun buildBanner(
        bannerDefinition: BannerDefinition,
        dataService: DataService,
        userMonitor: UserMonitor,
    ): Banner {
        throw IllegalArgumentException("Cannot build the requested banner: ${bannerDefinition.id}")
    }

    /**
     * This is called during feature initialization. The FeatureManager will request a list of UI
     * [Location]s that this feature would like to receive compose calls for, and the priority with
     * which the feature should be considered for composing its UI.
     *
     * The default priority is [Priority.Default] but should be any non-negative Integer.
     *
     * In the case of a default priority, Features will have their compose functions called in the
     * order of registration in the [FeatureManager.KNOWN_FEATURE_REGISTRATIONS]
     *
     * This method does not have a default implementation, every [PhotopickerUiFeature] must
     * implement this themselves.
     */
    fun registerLocations(): List<Pair<Location, Int>>

    /**
     * The primary compose hook for rendering composables into the UI for a feature.
     *
     * The location being composed is passed as an argument, and it's expected that the feature
     * correctly maps the correct composable to the incoming location.
     *
     * It is strongly encouraged to use a when block to handle this top level compose call, and
     * correctly map the feature's composables to the location such as:
     * ```
     *
     * when(location) {
     *
     *  Location.THE_BEACH -> composeTheBeach()
     *  Location.OUTER_SPACE -> composeOuterSpace()
     *  else -> {}
     * }
     *
     * ```
     *
     * Also note the else block to handle all other locations that this UI feature does not care
     * about. It will not be called to compose for Locations not in its provided registry, but the
     * compiler will complain if all outcomes are not covered, so this is an easy way to prevent new
     * locations from requiring changes in all existing features.
     *
     * Warning: All code that executes inside of this method runs on the [MainThread] aka the UI
     * thread. Do not do expensive operations in this method, and follow all best practices for
     * normal @Composable functions. (Such as offloading background work via Coroutines.)
     */
    @Composable fun compose(location: Location, modifier: Modifier, params: LocationParams): Unit

    /**
     * This is called when the [NavHost] is composed for all enabled [PhotopickerUiFeature] to
     * assemble the navigable routes.
     *
     * @return A set of [Route] to add to the navigation graph, or an empty set if this feature
     *   exposes no routes.
     */
    fun registerNavigationRoutes(): Set<Route> = emptySet<Route>()
}
