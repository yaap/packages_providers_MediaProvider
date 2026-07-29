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

package com.android.photopicker.features.privacyexplainer

import android.provider.MediaStore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.android.photopicker.R
import com.android.photopicker.core.banners.Banner
import com.android.photopicker.core.banners.BannerDefinition
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.banners.BannerInteractionState
import com.android.photopicker.core.banners.BannerLocation
import com.android.photopicker.core.banners.BannerState
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.features.Priority
import com.android.photopicker.core.network.NetworkStatus
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Icon
import kotlinx.coroutines.Deferred

/** Feature class for the Photopicker's Privacy explainer. */
class PrivacyExplainerFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerPrivacyExplainerFeature"

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ) = true

        override fun build(featureManager: FeatureManager) = PrivacyExplainerFeature()
    }

    override fun registerLocations(): List<Pair<Location, Int>> = emptyList()

    override val token = FeatureToken.PRIVACY_EXPLAINER.token

    private val ownedBannersByLocation: Map<BannerLocation, Set<BannerDefinitions>> =
        mapOf(BannerLocation.PHOTO_GRID_BANNER to setOf(BannerDefinitions.PRIVACY_EXPLAINER))

    override val ownedBanners: Set<BannerDefinitions> =
        ownedBannersByLocation.values.flatten().toSet()

    private val ownedBannersDefinitionByLocation: Map<BannerLocation, Set<BannerDefinition>> =
        mapOf(
            BannerLocation.PHOTO_GRID_BANNER to
                setOf(
                    BannerDefinition.PRIVACY_EXPLAINER,
                    BannerDefinition.PRIVACY_EXPLAINER_LIMITED_ACCESS,
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

        val isValidForLocation =
            ownedBannersDefinitionByLocation[bannerLocation]?.contains(bannerDefinition) ?: false
        if (bannerInteractionState?.isDismissed == true || !isValidForLocation) {
            return Priority.DISABLED.priority
        }
        // Determine which banner is valid for the current Action mode
        val isPermissionMode = config.action == MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP
        val isValidForMode =
            when (bannerDefinition) {
                BannerDefinition.PRIVACY_EXPLAINER_LIMITED_ACCESS -> isPermissionMode
                BannerDefinition.PRIVACY_EXPLAINER -> !isPermissionMode
                else -> false
            }

        return if (isValidForMode) bannerDefinition.priority.priority
        else Priority.DISABLED.priority
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

        return when (banner) {
            BannerDefinitions.PRIVACY_EXPLAINER -> {
                val isValidForLocation =
                    ownedBannersByLocation[bannerLocation]?.contains(banner) ?: false
                if (bannerState?.dismissed == true || !isValidForLocation) {
                    Priority.DISABLED.priority
                } else {
                    Priority.HIGH.priority
                }
            }
            else ->
                throw IllegalArgumentException("$TAG cannot build the requested banner: $banner")
        }
    }

    override suspend fun buildBanner(
        bannerDefinition: BannerDefinition,
        dataService: DataService,
        userMonitor: UserMonitor,
    ): Banner {
        return when (bannerDefinition) {
            BannerDefinition.PRIVACY_EXPLAINER_LIMITED_ACCESS,
            BannerDefinition.PRIVACY_EXPLAINER -> PrivacyExplainerBanner(bannerDefinition)
            else ->
                throw IllegalArgumentException(
                    "$TAG cannot build the requested banner: $bannerDefinition"
                )
        }
    }

    override suspend fun buildBanner(
        banner: BannerDefinitions,
        dataService: DataService,
        userMonitor: UserMonitor,
        configuration: PhotopickerConfiguration,
    ): Banner {
        return when (banner) {
            BannerDefinitions.PRIVACY_EXPLAINER ->
                when (configuration.action) {
                    MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP ->
                        PrivacyExplainerBanner(BannerDefinition.PRIVACY_EXPLAINER_LIMITED_ACCESS)
                    else -> PrivacyExplainerBanner(BannerDefinition.PRIVACY_EXPLAINER)
                }
            else ->
                throw IllegalArgumentException("$TAG cannot build the requested banner: $banner")
        }
    }

    override val eventsConsumed = setOf<RegisteredEventClass>()
    override val eventsProduced = setOf<RegisteredEventClass>()

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {}

    /**
     * A private [Banner] implementation for the privacy explainer banners.
     *
     * This class is responsible for providing the content (title, message, and icon) for both the
     * standard (`PRIVACY_EXPLAINER`) and limited-access (`PRIVACY_EXPLAINER_LIMITED_ACCESS`)
     * privacy explainer banners.
     *
     * @param bannerDefinition The specific [BannerDefinition] this instance represents, which
     *   controls its behavior and identity.
     */
    private class PrivacyExplainerBanner(override val bannerDefinition: BannerDefinition) : Banner {
        override val declaration = BannerDefinitions.PRIVACY_EXPLAINER

        @Composable override fun buildTitle(): String = ""

        @Composable
        override fun buildMessage(): String {
            val config = LocalPhotopickerConfiguration.current
            val genericAppName =
                stringResource(R.string.photopicker_privacy_explainer_generic_app_name)
            val callingAppName = config.callingPackageLabel ?: genericAppName

            val messageResId =
                when (bannerDefinition) {
                    BannerDefinition.PRIVACY_EXPLAINER_LIMITED_ACCESS -> {
                        if (config.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                            R.string.photopicker_privacy_explainer_limited_access_permission_mode
                        } else {
                            R.string.photopicker_privacy_explainer_permission_mode
                        }
                    }
                    else -> {
                        if (config.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                            R.string.photopicker_privacy_explainer_message
                        } else {
                            R.string.photopicker_privacy_explainer
                        }
                    }
                }
            return stringResource(messageResId, callingAppName)
        }

        @Composable
        override fun getIcon() =
            Icon(ImageVector.vectorResource(R.drawable.android_security_privacy))
    }
}
