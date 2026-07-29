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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.obtainViewModel

/**
 * Root composable for camera capture screen. This includes camera view finder, camera settings, and
 * camera action buttons like capture and record etc.
 *
 * @param viewModel CameraViewModel which is scoped to the Photopicker session.
 */
@Composable
fun Camera(
    // Scope the view model explicitly to the activity (Photopicker session) and not the
    // navigation graph entry.
    viewModel: CameraViewModel = obtainViewModel(isActivityScoped = true)
) {
    val navController = LocalNavController.current

    // Fill the screen and wrap content in a surface with Black background.
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        // TODO(b/414668505)
    }
}
