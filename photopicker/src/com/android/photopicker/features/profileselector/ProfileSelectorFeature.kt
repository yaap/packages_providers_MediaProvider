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

package com.android.photopicker.features.profileselector

import android.content.Context
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import com.android.photopicker.core.events.Event
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
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.VectorIcon
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking

/** Feature class for the Photopicker's Profile Selector button. */
class ProfileSelectorFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerProfileSelectorFeature"

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ): Boolean {

            // Profile switching is not permitted in permission mode.
            if (MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP.equals(config.action)) {
                return false
            }

            return true
        }

        override fun build(featureManager: FeatureManager) = ProfileSelectorFeature()
    }

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.PROFILE_SELECTOR, Priority.HIGH.priority))
    }

    override val token = FeatureToken.PROFILE_SELECTOR.token

    private val ownedBannersByLocation: Map<BannerLocation, Set<BannerDefinitions>> =
        mapOf(BannerLocation.PHOTO_GRID_BANNER to setOf(BannerDefinitions.SWITCH_PROFILE))

    override val ownedBanners: Set<BannerDefinitions> =
        ownedBannersByLocation.values.flatten().toSet()

    private val ownedBannersDefinitionByLocation: Map<BannerLocation, Set<BannerDefinition>> =
        mapOf(BannerLocation.PHOTO_GRID_BANNER to setOf(BannerDefinition.SWITCH_PROFILE))

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
        return calculateBannerPriority(
            isValidLocation =
                ownedBannersDefinitionByLocation[bannerLocation]?.contains(bannerDefinition) ==
                    true,
            isDismissed = bannerInteractionState?.isDismissed == true,
            userMonitor = userMonitor,
            defaultPriority = bannerDefinition.priority.priority,
        )
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
        return calculateBannerPriority(
            isValidLocation = ownedBannersByLocation[bannerLocation]?.contains(banner) == true,
            isDismissed = bannerState?.dismissed == true,
            userMonitor = userMonitor,
            defaultPriority = Priority.HIGH.priority,
        )
    }

    override suspend fun buildBanner(
        banner: BannerDefinitions,
        dataService: DataService,
        userMonitor: UserMonitor,
        configuration: PhotopickerConfiguration,
    ): Banner {
        return when (banner) {
            BannerDefinitions.SWITCH_PROFILE -> {
                createSwitchProfileBanner(userMonitor)
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
            BannerDefinition.SWITCH_PROFILE -> {
                createSwitchProfileBanner(userMonitor)
            }
            else ->
                throw IllegalArgumentException(
                    "$TAG cannot build the requested banner: $bannerDefinition"
                )
        }
    }

    /** Events consumed by the ProfileSelector */
    override val eventsConsumed = setOf<RegisteredEventClass>()

    /** Events produced by the ProfileSelector */
    override val eventsProduced =
        setOf<RegisteredEventClass>(Event.LogPhotopickerUIEvent::class.java)

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
        when (location) {
            Location.PROFILE_SELECTOR -> ProfileSelector(modifier)
            else -> {}
        }
    }

    private fun calculateBannerPriority(
        isValidLocation: Boolean,
        isDismissed: Boolean,
        userMonitor: UserMonitor,
        defaultPriority: Int,
    ): Int {
        if (isDismissed || !isValidLocation) {
            return Priority.DISABLED.priority
        }

        return when (userMonitor.launchingProfile.profileType) {
            UserProfile.ProfileType.PRIMARY -> Priority.DISABLED.priority
            else -> defaultPriority
        }
    }

    private fun createSwitchProfileBanner(userMonitor: UserMonitor): Banner {
        val userStatus = userMonitor.userStatus.value
        val currentProfile = userStatus.activeUserProfile
        val targetProfile =
            userStatus.allProfiles.find { it.profileType == UserProfile.ProfileType.PRIMARY }

        // Check if the primary profile exists first
        checkNotNull(targetProfile) {
            "Could not build switch profile banner, no primary profile found."
        }

        // check that we aren't already on the primary profile
        check(currentProfile.identifier != targetProfile.identifier) {
            "Could not build switch profile banner, current and target profiles were the same."
        }

        return object : Banner {
            override val declaration = BannerDefinitions.SWITCH_PROFILE
            override val bannerDefinition = BannerDefinition.SWITCH_PROFILE

            @Composable
            override fun buildTitle(): String {
                val config = LocalPhotopickerConfiguration.current
                return if (config.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                    val targetProfileLabel =
                        targetProfile.label ?: getLabelForProfile(targetProfile)
                    stringResource(
                        R.string.photopicker_profile_switch_banner_title,
                        targetProfileLabel,
                    )
                } else {
                    ""
                }
            }

            @Composable
            override fun buildMessage(): String {
                val config = LocalPhotopickerConfiguration.current
                val currentProfileLabel = currentProfile.label ?: getLabelForProfile(currentProfile)

                return if (config.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                    val messageResId =
                        when (currentProfile.profileType) {
                            UserProfile.ProfileType.MANAGED ->
                                R.string.photopicker_work_profile_switch_banner_message
                            else -> R.string.photopicker_private_profile_switch_banner_message
                        }
                    stringResource(messageResId, currentProfileLabel)
                } else {
                    val targetLabel = targetProfile.label ?: getLabelForProfile(targetProfile)
                    stringResource(
                        R.string.photopicker_profile_switch_banner_message,
                        currentProfileLabel,
                        targetLabel,
                    )
                }
            }

            @Composable override fun getIcon() = VectorIcon(getIconForProfile(currentProfile))

            @Composable
            override fun actionLabel(): String? {
                return stringResource(R.string.photopicker_profile_banner_switch_button_label)
            }

            override fun onAction(context: Context) {
                val personalProfile =
                    userMonitor.userStatus.value.allProfiles.find {
                        it.profileType == UserProfile.ProfileType.PRIMARY
                    }
                personalProfile?.let {
                    runBlocking { userMonitor.requestSwitchActiveUserProfile(it, context) }
                }
            }
        }
    }
}
