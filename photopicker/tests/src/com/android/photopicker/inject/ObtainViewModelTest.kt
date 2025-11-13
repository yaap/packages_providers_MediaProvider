/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.photopicker.inject

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.tests.HiltTestActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ObtainViewModelTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 0)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)

    class TestViewModel : ViewModel()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun obtainViewModelForNonActivityScoped() {

        lateinit var testActivity: HiltTestActivity
        composeTestRule.activity.let { activity -> testActivity = activity }

        val testViewModelStoreOwner =
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = ViewModelStore()
            }

        var firstObtainedViewModel: TestViewModel? = null
        var secondObtainedViewModel: TestViewModel? = null

        composeTestRule.setContent {
            val configuration =
                TestPhotopickerConfiguration.build { runtimeEnv(PhotopickerRuntimeEnv.ACTIVITY) }
            // Different view model store owners will return different view models unless
            // same view models are requested in the function call
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides configuration,
                LocalContext provides testActivity,
                LocalViewModelStoreOwner provides testViewModelStoreOwner,
                LocalLifecycleOwner provides testActivity,
                LocalSavedStateRegistryOwner provides testActivity,
            ) {
                firstObtainedViewModel = obtainViewModel()
            }

            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides configuration,
                LocalContext provides testActivity,
                LocalViewModelStoreOwner provides testActivity,
                LocalLifecycleOwner provides testActivity,
                LocalSavedStateRegistryOwner provides testActivity,
            ) {
                secondObtainedViewModel = obtainViewModel()
            }
        }
        assertNotNull(firstObtainedViewModel)
        assertNotNull(secondObtainedViewModel)

        assertTrue(firstObtainedViewModel != secondObtainedViewModel)
    }

    @Test
    fun obtainViewModelForActivityScoped() {

        lateinit var testActivity: HiltTestActivity
        composeTestRule.activity.let { activity -> testActivity = activity }

        val testViewModelStoreOwner =
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = ViewModelStore()
            }

        var firstObtainedViewModel: TestViewModel? = null
        var secondObtainedViewModel: TestViewModel? = null

        composeTestRule.setContent {
            val configuration =
                TestPhotopickerConfiguration.build { runtimeEnv(PhotopickerRuntimeEnv.ACTIVITY) }
            // Different view model store owners will return same view models when requested
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides configuration,
                LocalContext provides testActivity,
                LocalViewModelStoreOwner provides testViewModelStoreOwner,
                LocalLifecycleOwner provides testActivity,
                LocalSavedStateRegistryOwner provides testActivity,
            ) {
                firstObtainedViewModel = obtainViewModel(isActivityScoped = true)
            }

            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides configuration,
                LocalContext provides testActivity,
                LocalViewModelStoreOwner provides testActivity,
                LocalLifecycleOwner provides testActivity,
                LocalSavedStateRegistryOwner provides testActivity,
            ) {
                secondObtainedViewModel = obtainViewModel(isActivityScoped = true)
            }
        }
        assertNotNull(firstObtainedViewModel)
        assertNotNull(secondObtainedViewModel)

        assertTrue(firstObtainedViewModel == secondObtainedViewModel)
    }
}
