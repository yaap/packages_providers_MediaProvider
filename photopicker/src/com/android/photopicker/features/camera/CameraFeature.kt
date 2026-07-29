/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.photopicker.features.camera

import android.content.Intent
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.features.Priority
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.navigation.Route
import kotlinx.coroutines.Deferred

/** Feature class for the Camera feature. */
class CameraFeature : PhotopickerUiFeature {
    companion object Registration : FeatureRegistration {
        override val TAG: String = "PhotopickerCameraFeature"

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ): Boolean {
            val isRuntimeEnvEligible = config.runtimeEnv == PhotopickerRuntimeEnv.ACTIVITY
            if (!isRuntimeEnvEligible) return false

            val isIntentActionEligible =
                config.action == MediaStore.ACTION_PICK_IMAGES ||
                    config.action == Intent.ACTION_GET_CONTENT
            if (!isIntentActionEligible) return false

            val isFeatureFlagEnabled = config.flags.POLAROID_ENABLED
            if (!isFeatureFlagEnabled) return false

            // TODO(b/487298902): Add API check
            return true
        }

        override fun build(featureManager: FeatureManager) = CameraFeature()
    }

    override val token = FeatureToken.CAMERA.token

    override val eventsConsumed = setOf<RegisteredEventClass>()

    override val eventsProduced = setOf<RegisteredEventClass>()

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.CAMERA_ENTRY_POINT, Priority.MEDIUM.priority))
    }

    override fun registerNavigationRoutes(): Set<Route> {
        return setOf(
            object : Route {
                override val route = PhotopickerDestinations.CAMERA.route
                override val initialRoutePriority = Priority.DISABLED.priority
                override val arguments = emptyList<NamedNavArgument>()
                override val deepLinks = emptyList<NavDeepLink>()
                override val isDialog = true
                override val dialogProperties =
                    DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                        // It is recommended to use [decorFitsSystemWindows] set to `false` when
                        // [usePlatformDefaultWidth] is false to support using the entire screen and
                        // avoiding UI glitches on some devices when the IME animates in.
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    )
                override val enterTransition = null
                override val exitTransition = null
                override val popEnterTransition = null
                override val popExitTransition = null

                @Composable
                override fun composable(navBackStackEntry: NavBackStackEntry?) {
                    Camera()
                }
            }
        )
    }

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
        when (location) {
            Location.CAMERA_ENTRY_POINT -> {
                CameraEntryPoint()
            }
            else -> {}
        }
    }
}
