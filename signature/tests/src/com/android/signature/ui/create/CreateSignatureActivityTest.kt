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

import android.app.Activity
import android.content.Intent
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.signature.HiltTestActivity
import com.android.signature.data.SignatureDao
import com.android.signature.data.SignatureRepository
import com.android.signature.di.DatabaseModule
import com.android.signature.flags.Flags
import com.android.signature.test.TestUtils
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@HiltAndroidTest
@UninstallModules(DatabaseModule::class)
@RunWith(AndroidJUnit4::class)
class CreateSignatureActivityTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val setFlagsRule = SetFlagsRule()

    @get:Rule(order = 2)
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val signatureDao: SignatureDao =
        Mockito.mock(SignatureDao::class.java).apply {
            whenever(getAllSignatures()).thenReturn(flowOf(emptyList()))
            runBlocking {
                whenever(getSignatureCount()).thenReturn(0)
                whenever(insertSignature(any())).thenReturn(Unit)
            }
        }

    @BindValue
    @JvmField
    val repository: SignatureRepository = SignatureRepository(signatureDao)

    @Before
    fun setup() {
        hiltRule.inject()
        TestUtils.wakeUpDevice()
        disableAnimations()
    }

    @After
    fun tearDown() {
        enableAnimations()
    }

    private fun disableAnimations() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.executeShellCommand("settings put global window_animation_scale 0")
        uiAutomation.executeShellCommand("settings put global transition_animation_scale 0")
        uiAutomation.executeShellCommand("settings put global animator_duration_scale 0")
    }

    private fun enableAnimations() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.executeShellCommand("settings put global window_animation_scale 1")
        uiAutomation.executeShellCommand("settings put global transition_animation_scale 1")
        uiAutomation.executeShellCommand("settings put global animator_duration_scale 1")
    }

    private fun setWindowFlags(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.setShowWhenLocked(true)
        activity.setTurnScreenOn(true)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun createSignatureActivity_flagEnabled_launchesAndDisplaysScreen() {
        ActivityScenario.launch(CreateSignatureActivity::class.java).use { scenario ->
            scenario.onActivity { setWindowFlags(it) }
            composeTestRule.onNodeWithText("Add signature").assertIsDisplayed()
            composeTestRule.onNodeWithText("Draw").assertIsDisplayed()
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun createSignatureActivity_flagDisabled_finishesActivity() {
        ActivityScenario.launch(CreateSignatureActivity::class.java).use { scenario ->
            // Activity should have finished itself in onCreate
            assertTrue(
                scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED || scenario.result.resultCode == Activity.RESULT_CANCELED,
            )
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun createSignatureActivity_save_finishesActivityWithResult() {
        // Launch HiltTestActivity as the host
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                setWindowFlags(activity)
                // Launch CreateSignatureActivity for result
                val intent = Intent(context, CreateSignatureActivity::class.java)
                activity.launchActivityForResult(intent)
            }

            // Switch to Type tab
            composeTestRule.onNodeWithText("Type").performClick()

            // Enter text
            composeTestRule.onNodeWithText("Enter name").performTextInput("Test Signature")

            // Wait for list
            composeTestRule.waitForIdle()

            // Select font (click the first one) - this triggers save
            val textNodes = composeTestRule.onAllNodes(hasText("Test Signature"))
            if (textNodes.fetchSemanticsNodes().size > 1) {
                textNodes[1].performClick()
            }

            // Wait for result in HiltTestActivity
            var lastResult: androidx.activity.result.ActivityResult? = null
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                scenario.onActivity { lastResult = it.lastResult }
                lastResult != null
            }

            val result = lastResult!!
            assertEquals(Activity.RESULT_OK, result.resultCode)

            val resultData = result.data
            assertTrue(resultData?.hasExtra(CreateSignatureActivity.EXTRA_SIGNATURE_ID) == true)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun createSignatureActivity_cancel_finishesActivityWithCanceled() {
        // Launch HiltTestActivity as the host
        ActivityScenario.launch(HiltTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                setWindowFlags(activity)
                // Launch CreateSignatureActivity for result
                val intent = Intent(context, CreateSignatureActivity::class.java)
                activity.launchActivityForResult(intent)
            }

            // Click Cancel (in Draw tab)
            composeTestRule.onNodeWithText("Cancel").performClick()

            // Wait for result in HiltTestActivity
            var lastResult: androidx.activity.result.ActivityResult? = null
            composeTestRule.waitUntil(timeoutMillis = 5000) {
                scenario.onActivity { lastResult = it.lastResult }
                lastResult != null
            }

            val result = lastResult!!
            assertEquals(Activity.RESULT_CANCELED, result.resultCode)
        }
    }
}
