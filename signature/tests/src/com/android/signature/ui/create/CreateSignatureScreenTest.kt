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

package com.android.signature.ui.create

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.signature.HiltTestActivity
import com.android.signature.data.SignatureDao
import com.android.signature.data.SignatureRepository
import com.android.signature.test.TestUtils
import com.android.signature.ui.SignatureViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CreateSignatureScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setup() {
        hiltRule.inject()
        TestUtils.wakeUpDevice()
        try {
            composeTestRule.activityRule.scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.setShowWhenLocked(true)
                it.setTurnScreenOn(true)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun createSignatureScreen_displaysTabs() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CreateSignatureScreen(viewModel = viewModel, onSignatureCreated = {}, onCancel = {})
            }
        }

        composeTestRule.onNodeWithText("Draw").assertIsDisplayed()
        composeTestRule.onNodeWithText("Type").assertIsDisplayed()
        composeTestRule.onNodeWithText("Upload").assertIsDisplayed()
    }

    @Test
    fun createSignatureScreen_switchesTabs() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CreateSignatureScreen(viewModel = viewModel, onSignatureCreated = {}, onCancel = {})
            }
        }

        // Default is Draw tab
        composeTestRule.onNodeWithTag("DrawingCanvas").assertIsDisplayed()

        // Switch to Type tab
        composeTestRule.onNodeWithText("Type").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Enter name").assertIsDisplayed()

        // Switch to Upload tab
        composeTestRule.onNodeWithText("Upload").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Select Image").assertIsDisplayed()
    }

    @Test
    fun createSignatureScreen_cancel_triggersCallback() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)
        var cancelClicked = false

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CreateSignatureScreen(
                    viewModel = viewModel,
                    onSignatureCreated = {},
                    onCancel = { cancelClicked = true },
                )
            }
        }

        // Cancel button is in Draw tab (default)
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue(cancelClicked)
    }

    @Test
    fun createSignatureScreen_saveError_handlesException() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        // Mock insert to throw exception
        runBlocking {
            whenever(signatureDao.insertSignature(any())).thenThrow(RuntimeException("Save failed"))
        }
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CreateSignatureScreen(viewModel = viewModel, onSignatureCreated = {}, onCancel = {})
            }
        }

        // Draw something
        composeTestRule.onNodeWithTag("DrawingCanvas").performTouchInput {
            swipeRight()
        }

        // Click Add
        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.waitForIdle()

        // If no crash, exception was caught.
    }

    @Test
    fun createSignatureScreen_typeTab_saveError_handlesException() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        runBlocking {
            whenever(signatureDao.insertSignature(any())).thenThrow(RuntimeException("Save failed"))
        }
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CreateSignatureScreen(viewModel = viewModel, onSignatureCreated = {}, onCancel = {})
            }
        }

        // Switch to Type tab
        composeTestRule.onNodeWithText("Type").performClick()

        // Enter text
        composeTestRule.onNodeWithText("Enter name").performTextInput("Test")
        composeTestRule.waitForIdle()

        // Click item to save
        val textNodes = composeTestRule.onAllNodes(hasText("Test"))
        if (textNodes.fetchSemanticsNodes().size > 1) {
            textNodes[1].performClick()
        }
        composeTestRule.waitForIdle()

        // If no crash, exception was caught.
    }

    @Test
    fun createSignatureScreen_uploadTab_saveError_handlesException() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        runBlocking {
            whenever(signatureDao.insertSignature(any())).thenThrow(RuntimeException("Save failed"))
        }
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        // Create dummy image
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "test_image.png")
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = Uri.fromFile(file)
        val testRegistry = FakeActivityResultRegistry(uri)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides
                        object :
                            ActivityResultRegistryOwner {
                            override val activityResultRegistry = testRegistry
                        },
                ) {
                    CreateSignatureScreen(
                        viewModel = viewModel,
                        onSignatureCreated = {},
                        onCancel = {},
                    )
                }
            }
        }

        // Switch to Upload tab
        composeTestRule.onNodeWithText("Upload").performClick()

        // Select Image
        composeTestRule.onNodeWithText("Select Image").performClick()
        composeTestRule.waitForIdle()

        // Click Add
        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.waitForIdle()

        // If no crash, exception was caught.
    }
}
