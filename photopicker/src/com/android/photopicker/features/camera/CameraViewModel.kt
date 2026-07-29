/*
 * Copyright 2026 The Android Open Source Project
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

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.photopicker.core.user.UserMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** ViewModel for the Camera feature. */
@HiltViewModel
class CameraViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    @ApplicationContext private val appContext: Context,
    private val userMonitor: UserMonitor,
) : ViewModel() {

    companion object {
        const val TAG = "CameraViewModel"
    }

    private val scope: CoroutineScope = scopeOverride ?: this.viewModelScope

    private val isLaunchingProfileCurrentlyActive: StateFlow<Boolean> =
        userMonitor.userStatus
            .map { it.activeUserProfile.identifier == userMonitor.launchingProfile.identifier }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    userMonitor.userStatus.value.activeUserProfile.identifier ==
                        userMonitor.launchingProfile.identifier,
            )

    /**
     * Lazily check if the device has a front or a rear camera, which is required to show the camera
     * entrypoint. Unstable camera types like external camera attached to the devices will not
     * enable the feature.
     */
    private val isDeviceCameraAvailable: Boolean by lazy {
        val packageManager = appContext.packageManager
        (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT))
    }

    /**
     * Lazily check if the device is eligible for the feature. This check is deliberately not added
     * in `CameraFeature.isEligible` to avoid IPC calls blocking the Photopicker app startup.
     */
    private val isDeviceEligible: Boolean by lazy {
        val packageManager = appContext.packageManager
        val excludedFeatures =
            listOf(
                PackageManager.FEATURE_PC,
                PackageManager.FEATURE_EMBEDDED,
                PackageManager.FEATURE_LEANBACK,
                PackageManager.FEATURE_AUTOMOTIVE,
                PackageManager.FEATURE_WATCH,
            )
        excludedFeatures.none { packageManager.hasSystemFeature(it) }
    }

    /**
     * This state flow has the value true only if all the following conditions are met:
     * 1. The profile is eligible to show the camera entrypoint
     * 2. The device has an eligible camera to show the entrypoint
     * 3. The device is eligible to show the entrypoint
     */
    val isCameraAvailable: StateFlow<Boolean> =
        isLaunchingProfileCurrentlyActive
            .map { isLaunchingProfileCurrentlyActive ->
                when {
                    !isLaunchingProfileCurrentlyActive -> {
                        Log.d(
                            TAG,
                            "Hide camera entry point because the launching profile is not currently active",
                        )
                        return@map false
                    }
                    !isDeviceCameraAvailable -> {
                        Log.d(
                            TAG,
                            "Hide camera entry point because the device does not have an eligible camera",
                        )
                        return@map false
                    }
                    !isDeviceEligible -> {
                        Log.d(
                            TAG,
                            "Hide camera entry point because the device is not eligible for the feature",
                        )
                        return@map false
                    }
                    else -> {
                        Log.d(TAG, "Profile is eligible to show camera")
                        return@map true
                    }
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    isLaunchingProfileCurrentlyActive.value &&
                        isDeviceCameraAvailable &&
                        isDeviceEligible,
            )
}
