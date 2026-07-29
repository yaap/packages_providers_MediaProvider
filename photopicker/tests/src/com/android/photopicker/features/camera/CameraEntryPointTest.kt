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

import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.R
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerFlags
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.theme.PhotopickerTheme
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraEntryPointTest {

    @get:Rule val composeTestRule = createComposeRule()
    private val navController: TestNavHostController =
        TestNavHostController(InstrumentationRegistry.getInstrumentation().context)

    private val testConfig =
        PhotopickerConfiguration(
            runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
            action = MediaStore.ACTION_PICK_IMAGES,
            flags = PhotopickerFlags(POLAROID_ENABLED = true),
            sessionId = 1234,
        )

    @Test
    fun testCameraEntryPointButton_isDisplayed_whenCameraIsAvailable() = runTest {
        val resources = InstrumentationRegistry.getInstrumentation().context.resources
        val contentDescription = resources.getString(R.string.photopicker_open_camera_option)
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides testConfig,
                LocalNavController provides navController,
            ) {
                PhotopickerTheme(config = testConfig) {
                    CameraEntryPointButton(isCameraAvailable = true)
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription(contentDescription).assertIsDisplayed()
        composeTestRule.waitForIdle()
    }

    @Test
    fun testCameraEntryPointButton_isNotDisplayed_whenCameraIsNotAvailable() = runTest {
        val resources = InstrumentationRegistry.getInstrumentation().context.resources
        val contentDescription = resources.getString(R.string.photopicker_open_camera_option)
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides testConfig,
                LocalNavController provides navController,
            ) {
                PhotopickerTheme(config = testConfig) {
                    CameraEntryPointButton(isCameraAvailable = false)
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription(contentDescription).assertDoesNotExist()
        composeTestRule.waitForIdle()
    }
}
