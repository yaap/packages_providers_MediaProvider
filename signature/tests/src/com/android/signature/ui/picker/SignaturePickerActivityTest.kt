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

package com.android.signature.ui.picker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.signature.HiltTestActivity
import com.android.signature.R
import com.android.signature.data.Signature
import com.android.signature.data.SignatureDao
import com.android.signature.data.SignatureRepository
import com.android.signature.di.DatabaseModule
import com.android.signature.flags.Flags
import com.android.signature.test.TestUtils
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.whenever

@HiltAndroidTest
@UninstallModules(DatabaseModule::class)
@RunWith(AndroidJUnit4::class)
class SignaturePickerActivityTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Rule to override flag values WITHIN the test process
    @get:Rule(order = 1)
    val setFlagsRule = SetFlagsRule()

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Use MutableStateFlow to allow updating data after activity launch
    private val signaturesFlow = MutableStateFlow<List<Signature>>(emptyList())

    private val signatureDao: SignatureDao = Mockito.mock(SignatureDao::class.java).apply {
        whenever(getAllSignatures()).thenReturn(signaturesFlow)
    }

    @BindValue
    @JvmField
    val repository: SignatureRepository = SignatureRepository(signatureDao)

    @Before
    fun setup() {
        hiltRule.inject()
        TestUtils.wakeUpDevice()
        try {
            composeTestRule.activityRule.scenario.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.setShowWhenLocked(true)
                it.setTurnScreenOn(true)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun launchPickerActivity() {
        val intent = Intent(context, SignaturePickerActivity::class.java)
        // Start for result to ensure callingPackage is set
        composeTestRule.activity.launchActivityForResult(intent)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun signaturePickerActivity_flagEnabled_launchesAndDisplaysScreen() {
        launchPickerActivity()
        composeTestRule.onNodeWithText(context.getString(R.string.picker_title)).assertIsDisplayed()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun signaturePickerActivity_flagDisabled_finishesImmediately() {
        launchPickerActivity()
        composeTestRule.waitForIdle()

        // Assert that the HiltTestActivity received a CANCELED result
        var lastResult: androidx.activity.result.ActivityResult? = null
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            lastResult = composeTestRule.activity.lastResult
            lastResult != null
        }
        assertEquals(Activity.RESULT_CANCELED, lastResult?.resultCode)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun signaturePickerActivity_addNew_launchesCreateSignatureActivity() {
        launchPickerActivity()

        composeTestRule.onNodeWithText(context.getString(R.string.picker_add_new)).performClick()

        // Wait for the current activity to lose focus, indicating another activity has started
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            !composeTestRule.activity.hasWindowFocus()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun signaturePickerActivity_selectSignature_finishesWithResult() {
        val signature = Signature(id = "1", type = Signature.TYPE_TYPED, textData = "Sig 1")

        launchPickerActivity()

        // Update the flow to emit the signature
        signaturesFlow.update { listOf(signature) }

        composeTestRule.onNodeWithText("Sig 1").performClick()
        composeTestRule.waitForIdle()

        // Wait for the result to be delivered to HiltTestActivity
        var lastResult: androidx.activity.result.ActivityResult? = null
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            lastResult = composeTestRule.activity.lastResult
            lastResult != null
        }

        // Check result
        val result = lastResult!!
        assertEquals(Activity.RESULT_OK, result.resultCode)

        val expectedBaseUri = Uri.parse("content://com.android.signature.provider/signatures/1")
        val expectedUri =
            expectedBaseUri.buildUpon()
                .appendQueryParameter("type", Signature.TYPE_TYPED.toString())
                .appendQueryParameter("text", "Sig 1")
                .build()

        assertEquals(expectedUri, result.data?.data)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun signaturePickerActivity_dismiss_finishesWithCanceled() {
        launchPickerActivity()
        composeTestRule.waitForIdle()

        // Simulate back press to dismiss bottom sheet
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()

        // Wait for result
        var lastResult: androidx.activity.result.ActivityResult? = null
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            lastResult = composeTestRule.activity.lastResult
            lastResult != null
        }

        assertEquals(Activity.RESULT_CANCELED, lastResult?.resultCode)
    }
}
