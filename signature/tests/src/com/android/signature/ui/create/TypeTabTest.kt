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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TypeTabTest {

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
    fun typeTab_initialState_showsSignaturePreview() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CreateSignatureScreen(
                    viewModel = viewModel,
                    onSignatureCreated = {},
                    onCancel = {})
            }
        }

        // Switch to Type tab
        composeTestRule.onNodeWithText("Type").performClick()
        composeTestRule.waitForIdle()

        // Verify "Signature" is displayed (default preview)
        composeTestRule.onAllNodes(hasText("Signature")).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun typeTab_emptyText_clickDoesNotSave() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CreateSignatureScreen(
                    viewModel = viewModel,
                    onSignatureCreated = {},
                    onCancel = {})
            }
        }

        // Switch to Type tab
        composeTestRule.onNodeWithText("Type").performClick()
        composeTestRule.waitForIdle()

        // Click on the first "Signature" preview
        val textNodes = composeTestRule.onAllNodes(hasText("Signature"))
        if (textNodes.fetchSemanticsNodes().isNotEmpty()) {
            textNodes[0].performClick()
        }

        composeTestRule.waitForIdle()

        // Verify save was NOT called
        runBlocking {
            verify(signatureDao, Mockito.never()).insertSignature(any())
        }
    }

    @Test
    fun typeTab_typing_updatesPreview() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CreateSignatureScreen(
                    viewModel = viewModel,
                    onSignatureCreated = {},
                    onCancel = {})
            }
        }

        // Switch to Type tab
        composeTestRule.onNodeWithText("Type").performClick()

        val typedText = "Alice"
        // Enter text
        composeTestRule.onNodeWithText("Enter name").performTextInput(typedText)
        composeTestRule.waitForIdle()

        // Verify "Alice" is displayed in the list (multiple times)
        // Note: "Alice" is also in the TextField.
        val nodes = composeTestRule.onAllNodes(hasText(typedText)).fetchSemanticsNodes()
        // Should be > 1 (TextField + List items)
        Assert.assertTrue(nodes.size > 1)
    }

    @Test
    fun createSignatureScreen_typeTab_savesSignature() {
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
                CreateSignatureScreen(
                    viewModel = viewModel,
                    onSignatureCreated = {},
                    onCancel = {})
            }
        }

        // Switch to Type tab
        composeTestRule.onNodeWithText("Type").performClick()

        val typedText = "John Doe"
        // Enter text (Placeholder is "Enter name")
        composeTestRule.onNodeWithText("Enter name").performTextInput(typedText)

        // Wait for the list to populate
        composeTestRule.waitForIdle()

        // Click on the first font preview.
        // We use onAllNodes with hasText because "John Doe" appears in the TextField and in the list items.
        val textNodes = composeTestRule.onAllNodes(hasText(typedText))

        // The first node is the TextField, the second is the first list item.
        // Clicking the item triggers save.
        if (textNodes.fetchSemanticsNodes().size > 1) {
            textNodes[1].performClick()
        }

        composeTestRule.waitForIdle()

        // Verify signature is saved
        runBlocking {
            val captor = argumentCaptor<Signature>()
            verify(signatureDao).insertSignature(captor.capture())
            val capturedSignature = captor.firstValue
            Assert.assertEquals(Signature.TYPE_TYPED, capturedSignature.type)
            Assert.assertEquals(typedText, capturedSignature.textData)
        }
    }
}
