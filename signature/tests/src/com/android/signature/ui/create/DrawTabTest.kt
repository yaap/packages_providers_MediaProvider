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

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.signature.HiltTestActivity
import com.android.signature.data.Signature
import com.android.signature.data.SignatureDao
import com.android.signature.data.SignatureRepository
import com.android.signature.test.TestUtils
import com.android.signature.ui.SignatureViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DrawTabTest {
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
    fun drawTab_initialState_showsPlaceholderAndDisabledAdd() {
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

        // Verify placeholder text is displayed
        composeTestRule.onNodeWithText("Draw your signature in this area").assertIsDisplayed()
        // Verify X and Line are displayed (X is text)
        composeTestRule.onNodeWithText("X").assertIsDisplayed()

        // Verify Add button is disabled
        composeTestRule.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test
    fun drawTab_drawing_hidesPlaceholderAndEnablesAdd() {
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

        // Perform drawing
        composeTestRule.onNodeWithTag("DrawingCanvas").performTouchInput {
            // Drag to simulate drawing
            swipeRight()
        }
        composeTestRule.waitForIdle()

        // Verify placeholder text is hidden (does not exist or not displayed)
        // Since we use if (drawingPaths.isEmpty()), it should be removed from composition.
        composeTestRule.onNodeWithText("Draw your signature in this area").assertIsNotDisplayed()

        // Verify X is still displayed (as per requirement)
        composeTestRule.onNodeWithText("X").assertIsDisplayed()

        // Verify Add button is enabled
        composeTestRule.onNodeWithText("Add").assertIsEnabled()
    }

    @Test
    fun createSignatureScreen_drawTab_savesSignature() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        runBlocking {
            whenever(signatureDao.getSignatureCount()).thenReturn(0)
        }
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CreateSignatureScreen(viewModel = viewModel, onSignatureCreated = {}, onCancel = {})
            }
        }

        // Ensure we are on Draw tab (default)
        composeTestRule.onNodeWithText("Draw").assertIsDisplayed()

        // Perform drawing on the canvas
        composeTestRule.onNodeWithTag("DrawingCanvas").performTouchInput {
            swipeRight()
        }

        // Click Add
        composeTestRule.onNodeWithText("Add").performClick()

        composeTestRule.waitForIdle()

        // Verify signature is saved
        runBlocking {
            val captor = argumentCaptor<Signature>()
            verify(signatureDao).insertSignature(captor.capture())
            val capturedSignature = captor.firstValue
            Assert.assertEquals(Signature.TYPE_DRAWN, capturedSignature.type)
            // We can't easily assert the image data content, but we can assert it's not null
            assert(capturedSignature.imageData != null)
        }
    }

    @Test
    fun drawTab_cancel_triggersCallback() {
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

        composeTestRule.onNodeWithText("Cancel").performClick()
        Assert.assertTrue(cancelClicked)
    }

    @Test
    fun drawTab_canvasResize_handlesTransformRecalculationWithoutCrashing() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        // Use a mutable state to simulate screen rotation/resizing
        val canvasSizeModifier = mutableStateOf(Modifier.size(200.dp, 400.dp))

        composeTestRule.setContent {
            Box(modifier = canvasSizeModifier.value) {
                CreateSignatureScreen(viewModel = viewModel, onSignatureCreated = {}, onCancel = {})
            }
        }

        // Draw a line while in "Portrait" (200x400)
        composeTestRule.onNodeWithTag("DrawingCanvas").performTouchInput {
            swipeRight()
        }
        composeTestRule.waitForIdle()

        // Verify the stroke is registered and "Add" is enabled
        composeTestRule.onNodeWithText("Add").assertIsEnabled()

        // Simulate device rotation to "Landscape" (400x200)
        // This will trigger the `onSizeChanged` and `LaunchedEffect(currentSize)`
        // inside `DrawingCanvas` to scale/translate the existing path.
        canvasSizeModifier.value = Modifier.size(400.dp, 200.dp)

        composeTestRule.waitForIdle()

        // Verify the app didn't crash and the state remains valid (Add button still enabled)
        // The transformation logic will have recalculated without wiping the drawing.
        composeTestRule.onNodeWithText("Add").assertIsEnabled()
    }
}
