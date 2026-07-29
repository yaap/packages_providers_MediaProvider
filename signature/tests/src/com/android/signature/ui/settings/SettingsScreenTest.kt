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

package com.android.signature.ui.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.signature.HiltTestActivity
import com.android.signature.R
import com.android.signature.data.Signature
import com.android.signature.data.SignatureDao
import com.android.signature.data.SignatureRepository
import com.android.signature.test.TestUtils
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val signatureDao: SignatureDao =
        Mockito.mock(SignatureDao::class.java).apply {
            whenever(getAllSignatures()).thenReturn(flowOf(emptyList()))
        }

    private val repository: SignatureRepository = SignatureRepository(signatureDao)

    @Before
    fun setup() {
        hiltRule.inject()
        TestUtils.wakeUpDevice()
    }

    private fun launchActivityAndSetContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        ActivityScenario.launch(HiltTestActivity::class.java).onActivity { activity ->
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.setContent(content = content)
        }
    }

    @Test
    fun settingsScreen_emptyList_displaysPlaceholder() {
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SettingsViewModel(repository, eventLogger)

        launchActivityAndSetContent {
            SettingsScreen(viewModel = viewModel, onNavigateUp = {})
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.no_signatures_saved))
            .assertIsDisplayed()
    }

    @Test
    fun settingsScreen_populatedList_displaysSignatures() {
        val signature1 = Signature(id = "1", type = Signature.TYPE_TYPED, textData = "Sig 1")
        val signature2 = Signature(id = "2", type = Signature.TYPE_TYPED, textData = "Sig 2")
        whenever(signatureDao.getAllSignatures()).thenReturn(
            flowOf(listOf(signature1, signature2)),
        )
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SettingsViewModel(repository, eventLogger)

        launchActivityAndSetContent {
            SettingsScreen(viewModel = viewModel, onNavigateUp = {})
        }

        composeTestRule.onNodeWithText("Sig 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sig 2").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_drawnSignature_displaysImage() {
        // Create dummy bitmap data
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val imageData = stream.toByteArray()

        val signature = Signature(id = "1", type = Signature.TYPE_DRAWN, imageData = imageData)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(listOf(signature)))
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SettingsViewModel(repository, eventLogger)

        launchActivityAndSetContent {
            SettingsScreen(viewModel = viewModel, onNavigateUp = {})
        }

        // Verify image content description is displayed
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.drawn_signature_content_description),
            ).assertIsDisplayed()
    }

    @Test
    fun settingsScreen_deleteSignature_showsDialog() {
        val signature = Signature(id = "1", type = Signature.TYPE_TYPED, textData = "Sig 1")
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(listOf(signature)))
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SettingsViewModel(repository, eventLogger)

        launchActivityAndSetContent {
            SettingsScreen(viewModel = viewModel, onNavigateUp = {})
        }

        // Click delete icon
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.delete_signature_content_description),
            ).performClick()

        // Verify dialog appears
        composeTestRule
            .onNodeWithText(context.getString(R.string.delete_signature_title))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.delete_signature_message))
            .assertIsDisplayed()
    }

    @Test
    fun settingsScreen_confirmDelete_deletesSignature() {
        val signature = Signature(id = "1", type = Signature.TYPE_TYPED, textData = "Sig 1")
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(listOf(signature)))
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SettingsViewModel(repository, eventLogger)

        launchActivityAndSetContent {
            SettingsScreen(viewModel = viewModel, onNavigateUp = {})
        }

        // Click delete icon
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.delete_signature_content_description),
            ).performClick()

        // Click Delete in dialog
        composeTestRule.onNodeWithText(context.getString(R.string.delete_action)).performClick()

        // Verify delete called
        runBlocking {
            verify(signatureDao).deleteSignature(signature)
        }

        // Verify dialog dismissed
        composeTestRule
            .onNodeWithText(context.getString(R.string.delete_signature_title))
            .assertIsNotDisplayed()
    }

    @Test
    fun settingsScreen_dismissDelete_hidesDialog() {
        val signature = Signature(id = "1", type = Signature.TYPE_TYPED, textData = "Sig 1")
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(listOf(signature)))
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SettingsViewModel(repository, eventLogger)

        launchActivityAndSetContent {
            SettingsScreen(viewModel = viewModel, onNavigateUp = {})
        }

        // Click delete icon
        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.delete_signature_content_description),
            ).performClick()

        // Click Cancel in dialog
        composeTestRule.onNodeWithText(context.getString(R.string.cancel_action)).performClick()

        // Verify delete NOT called
        runBlocking {
            verify(signatureDao, Mockito.never()).deleteSignature(signature)
        }

        // Verify dialog dismissed
        composeTestRule
            .onNodeWithText(context.getString(R.string.delete_signature_title))
            .assertIsNotDisplayed()
    }

    @Test
    fun settingsScreen_backNavigation_triggersCallback() {
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SettingsViewModel(repository, eventLogger)
        var backClicked = false

        launchActivityAndSetContent {
            SettingsScreen(viewModel = viewModel, onNavigateUp = { backClicked = true })
        }

        composeTestRule
            .onNodeWithContentDescription(
                context.getString(R.string.back_content_description),
            ).performClick()
        assert(backClicked)
    }

    @Test
    fun settingsScreen_scrollableList() {
        // Create enough signatures to overflow the screen
        val signatures =
            List(20) { i ->
                Signature(id = "$i", type = Signature.TYPE_TYPED, textData = "Signature $i")
            }
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(signatures))
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SettingsViewModel(repository, eventLogger)

        launchActivityAndSetContent {
            SettingsScreen(viewModel = viewModel, onNavigateUp = {})
        }

        // Verify first item is displayed
        composeTestRule.onNodeWithText("Signature 0").assertIsDisplayed()

        // Scroll to the last item using the list's test tag
        composeTestRule.onNodeWithTag("SettingsList").performScrollToNode(hasText("Signature 19"))

        // Verify last item is displayed
        composeTestRule.onNodeWithText("Signature 19").assertIsDisplayed()
    }
}
