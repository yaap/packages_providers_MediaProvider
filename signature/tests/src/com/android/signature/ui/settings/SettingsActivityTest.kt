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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.whenever

@HiltAndroidTest
@UninstallModules(DatabaseModule::class)
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val setFlagsRule = SetFlagsRule()

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<SettingsActivity>()

    // Stub the mock immediately to avoid NPE during Activity launch
    private val signatureDao: SignatureDao =
        Mockito.mock(SignatureDao::class.java).apply {
            whenever(getAllSignatures()).thenReturn(flowOf(emptyList()))
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

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun settingsActivity_flagEnabled_launchesAndDisplaysScreen() {
        composeTestRule.onNodeWithText("Manage Signatures").assertIsDisplayed()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun settingsActivity_flagDisabled_finishesActivity() {
        // Activity should finish itself in onCreate
        composeTestRule.waitForIdle()
        val scenario = composeTestRule.activityRule.scenario
        // State might become DESTROYED very quickly
        assertTrue(
            scenario.state == Lifecycle.State.DESTROYED || composeTestRule.activity.isFinishing,
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_SIGNATURE)
    fun settingsActivity_backNavigation_finishesActivity() {
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()

        assertTrue(composeTestRule.activity.isFinishing || composeTestRule.activity.isDestroyed)
    }
}
