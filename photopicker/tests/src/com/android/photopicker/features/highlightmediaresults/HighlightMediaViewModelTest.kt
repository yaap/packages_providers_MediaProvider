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

package com.android.photopicker.features.highlightmediaresults

import android.os.Build
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.filters.SmallTest
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalCoroutinesApi::class)
class HighlightMediaViewModelTest {

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
    )
    fun testInitialState_showHighlightSection_isTrue() = runTest {
        val viewModel =
            HighlightMediaViewModel(
                this.backgroundScope,
                StandardTestDispatcher(this.testScheduler),
                TestDataServiceImpl(),
            )
        assertThat(viewModel.showHighlightSection.value).isTrue()
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
    )
    fun testOnProviderChange_resetsShowHighlightSection_toTrue() = runTest {
        val testDataService = TestDataServiceImpl()
        testDataService.setAvailableProviders(
            listOf(
                Provider(
                    authority = "new_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 1,
                    displayName = "New Provider",
                )
            )
        )

        val viewModel =
            HighlightMediaViewModel(
                this.backgroundScope,
                StandardTestDispatcher(this.testScheduler),
                testDataService,
            )

        // Set to false initially
        viewModel.setShowHighlightSection(false)
        advanceUntilIdle()
        assertThat(viewModel.showHighlightSection.value).isFalse()

        // Trigger a provider change
        testDataService.setAvailableProviders(emptyList())
        advanceTimeBy(500)
        advanceUntilIdle()

        // Verify the state is reset to true
        assertThat(viewModel.showHighlightSection.value).isTrue()
    }
}
