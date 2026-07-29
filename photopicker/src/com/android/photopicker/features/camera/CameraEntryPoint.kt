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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.photopicker.R
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.theme.CustomAccentColorScheme
import com.android.photopicker.extensions.navigateToCamera

/**
 * A stateful composable that handles the logic for displaying the camera entry point.
 *
 * @param viewModel Camera View Model that provides and holds camera related data
 * @param modifier The modifier to be applied to the layout.
 */
@Composable
fun CameraEntryPoint(
    viewModel: CameraViewModel = obtainViewModel(isActivityScoped = true),
    modifier: Modifier = Modifier,
) {
    val isCameraAvailable by viewModel.isCameraAvailable.collectAsStateWithLifecycle()
    CameraEntryPointButton(isCameraAvailable = isCameraAvailable, modifier = modifier)
}

/**
 * A stateless composable that displays the camera entry point UI.
 *
 * @param isCameraAvailable Whether the camera is available on the device and current active
 *   profile.
 * @param modifier The modifier to be applied to the layout.
 */
@Composable
fun CameraEntryPointButton(isCameraAvailable: Boolean, modifier: Modifier = Modifier) {
    if (isCameraAvailable) {
        val configuration = LocalPhotopickerConfiguration.current
        val isVideoOnly = configuration.hasOnlyVideoMimeTypes()
        val cameraContentDescription = stringResource(R.string.photopicker_open_camera_option)
        val aspectRatio = configuration.getAspectRatioForMediaItemGrids().ratio
        val navController = LocalNavController.current

        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        CustomAccentColorScheme.current.getAccentColorIfDefinedOrElse(
                            MaterialTheme.colorScheme.primary
                        )
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = cameraContentDescription
                    }
                    .clickable { navController.navigateToCamera() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isVideoOnly) Icons.Filled.Videocam else Icons.Filled.AddAPhoto,
                contentDescription = null,
                tint =
                    CustomAccentColorScheme.current.getTextColorForAccentComponentsIfDefinedOrElse(
                        MaterialTheme.colorScheme.onPrimary
                    ),
            )
        }
    }
}
