/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.photopicker.features.data.paging

import android.content.ContentResolver
import android.content.Intent
import android.os.Build
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.MediaStore
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.TestMediaProvider
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.data.paging.MediaPagingSource
import com.android.photopicker.features.datescrubber.DateScrubberFeature
import com.android.photopicker.tests.HiltTestActivity
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@HiltAndroidTest
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class MediaPagingSourceTest {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) var setFlagsRule = SetFlagsRule()
    @get:Rule(order = 3)
    val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    private val testSessionId = generatePickerSessionId()
    private val testContentProvider: TestMediaProvider = TestMediaProvider()
    private val contentResolver: ContentResolver = ContentResolver.wrap(testContentProvider)
    private val availableProviders: List<Provider> =
        listOf(
            Provider(
                authority = "local_authority",
                mediaSource = MediaSource.LOCAL,
                uid = 1,
                displayName = "Local Provider",
            )
        )

    @Mock private lateinit var mockMediaProviderClient: MediaProviderClient

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        hiltRule.inject()
    }

    @Test
    fun testLoad() = runTest {
        val testPhotopickerConfiguration =
            PhotopickerConfiguration(
                action = MediaStore.ACTION_PICK_IMAGES,
                intent = Intent(MediaStore.ACTION_PICK_IMAGES),
                sessionId = testSessionId,
            )
        val featureManager =
            FeatureManager(
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                this.backgroundScope,
                TestPrefetchDataService(),
            )
        val events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                featureManager,
            )

        val isDateScrubberEnabled = featureManager.isFeatureEnabled(DateScrubberFeature::class.java)
        val pageSize: Int = 10
        val mediaPagingSource =
            MediaPagingSource(
                contentResolver = contentResolver,
                availableProviders = availableProviders,
                mediaProviderClient = mockMediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                testPhotopickerConfiguration,
                isDateScrubberEnabled,
                events,
                pageSize,
            )

        val pageKey: MediaPageKey = MediaPageKey()
        val params =
            LoadParams.Append<MediaPageKey>(
                key = pageKey,
                loadSize = pageSize,
                placeholdersEnabled = false,
            )

        mediaPagingSource.load(params)
        advanceTimeBy(100)

        verify(mockMediaProviderClient, times(1))
            .fetchMedia(
                pageKey,
                pageSize,
                pageSize,
                contentResolver,
                availableProviders,
                testPhotopickerConfiguration,
                shouldEnableItemsBeforeCount = true,
                shouldEnableItemsAfterCount = isDateScrubberEnabled,
            )
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_DATESCRUBBER)
    fun testGetRefreshKey_whenFlagEnabled() = runTest {
        val testPhotopickerConfiguration =
            PhotopickerConfiguration(
                action = MediaStore.ACTION_PICK_IMAGES,
                intent = Intent(MediaStore.ACTION_PICK_IMAGES),
                sessionId = testSessionId,
            )
        val featureManager =
            FeatureManager(
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                this.backgroundScope,
                TestPrefetchDataService(),
            )
        val events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                featureManager,
            )

        val pageSize: Int = 10
        val isDateScrubberEnabled = featureManager.isFeatureEnabled(DateScrubberFeature::class.java)
        val mediaPageKeyCacheInterval = 100
        val mockMediaPageKeyCache =
            listOf(
                MediaPageKey(pickerId = 105L, dateTakenMillis = 1759165800000L),
                MediaPageKey(pickerId = 104L, dateTakenMillis = 1759165700000L),
                MediaPageKey(pickerId = 103L, dateTakenMillis = 1759165700000L),
                MediaPageKey(pickerId = 102L, dateTakenMillis = 1759165600000L),
                MediaPageKey(pickerId = 101L, dateTakenMillis = 1759165500000L),
            )
        val mediaPagingSource =
            MediaPagingSource(
                contentResolver = contentResolver,
                availableProviders = availableProviders,
                mediaProviderClient = mockMediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                testPhotopickerConfiguration,
                isDateScrubberEnabled,
                events,
                pageSize,
                mediaPageKeyCacheInterval = mediaPageKeyCacheInterval,
                mediaPageKeyCache = mockMediaPageKeyCache,
            )

        val anchorPosition = 175
        val validRefreshPosition = anchorPosition - anchorPosition % mediaPageKeyCacheInterval
        val expectedIndexInCache = validRefreshPosition / mediaPageKeyCacheInterval
        val pagingState = createFakePagingState(anchorPosition = 175, pageSize = pageSize)
        assertThat(mediaPagingSource.getRefreshKey(pagingState))
            .isEqualTo(mockMediaPageKeyCache[expectedIndexInCache])

        advanceTimeBy(100)

        // Verify that jumping is enabled in the PagingSource
        assertThat(mediaPagingSource.jumpingSupported).isEqualTo(true)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_DATESCRUBBER)
    fun testGetRefreshKey_whenFlagDisabled() = runTest {
        val testPhotopickerConfiguration =
            PhotopickerConfiguration(
                action = MediaStore.ACTION_PICK_IMAGES,
                intent = Intent(MediaStore.ACTION_PICK_IMAGES),
                sessionId = testSessionId,
            )
        val featureManager =
            FeatureManager(
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                this.backgroundScope,
                TestPrefetchDataService(),
            )
        val events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                featureManager,
            )

        val pageSize: Int = 10
        val isDateScrubberEnabled = featureManager.isFeatureEnabled(DateScrubberFeature::class.java)
        val mediaPageKeyCacheInterval = 100
        val mockMediaPageKeyCache =
            listOf(
                MediaPageKey(pickerId = 105L, dateTakenMillis = 1759165800000L),
                MediaPageKey(pickerId = 104L, dateTakenMillis = 1759165700000L),
                MediaPageKey(pickerId = 103L, dateTakenMillis = 1759165700000L),
                MediaPageKey(pickerId = 102L, dateTakenMillis = 1759165600000L),
                MediaPageKey(pickerId = 101L, dateTakenMillis = 1759165500000L),
            )
        val mediaPagingSource =
            MediaPagingSource(
                contentResolver = contentResolver,
                availableProviders = availableProviders,
                mediaProviderClient = mockMediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                testPhotopickerConfiguration,
                isDateScrubberEnabled,
                events,
                pageSize,
                mediaPageKeyCacheInterval = mediaPageKeyCacheInterval,
                mediaPageKeyCache = mockMediaPageKeyCache,
            )

        val anchorPosition = 175
        val validRefreshPosition = anchorPosition - anchorPosition % mediaPageKeyCacheInterval
        val expectedIndexInCache = validRefreshPosition / mediaPageKeyCacheInterval
        val pagingState = createFakePagingState(anchorPosition = 175, pageSize = pageSize)

        // Since date scrubber flag is disabled ,getRefreshKey Should return null
        assertThat(mediaPagingSource.getRefreshKey(pagingState)).isEqualTo(null)

        advanceTimeBy(100)

        // Verify that jumping is enabled in the PagingSource
        assertThat(mediaPagingSource.jumpingSupported).isEqualTo(false)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_DATESCRUBBER)
    fun testGetRefreshKey_returnsNull_whenMediaPageKeyCacheIsEmpty() = runTest {
        val testPhotopickerConfiguration =
            PhotopickerConfiguration(
                action = MediaStore.ACTION_PICK_IMAGES,
                intent = Intent(MediaStore.ACTION_PICK_IMAGES),
                sessionId = testSessionId,
            )
        val featureManager =
            FeatureManager(
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                this.backgroundScope,
                TestPrefetchDataService(),
            )
        val events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                featureManager,
            )

        val pageSize: Int = 10
        val isDateScrubberEnabled = featureManager.isFeatureEnabled(DateScrubberFeature::class.java)
        val mediaPageKeyCacheInterval = 100
        val mediaPagingSource =
            MediaPagingSource(
                contentResolver = contentResolver,
                availableProviders = availableProviders,
                mediaProviderClient = mockMediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                testPhotopickerConfiguration,
                isDateScrubberEnabled,
                events,
                pageSize,
                mediaPageKeyCacheInterval = mediaPageKeyCacheInterval,
            )

        val pagingState = createFakePagingState(anchorPosition = 175, pageSize = pageSize)
        assertThat(mediaPagingSource.getRefreshKey(pagingState)).isEqualTo(null)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_DATESCRUBBER)
    fun testGetRefreshKey_returnsNull_whenAnchorPositionIsBeyondCachedKeys() = runTest {
        val testPhotopickerConfiguration =
            PhotopickerConfiguration(
                action = MediaStore.ACTION_PICK_IMAGES,
                intent = Intent(MediaStore.ACTION_PICK_IMAGES),
                sessionId = testSessionId,
            )
        val featureManager =
            FeatureManager(
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                this.backgroundScope,
                TestPrefetchDataService(),
            )
        val events =
            Events(
                scope = this.backgroundScope,
                provideTestConfigurationFlow(this.backgroundScope, testPhotopickerConfiguration),
                featureManager,
            )

        val pageSize: Int = 10
        val isDateScrubberEnabled = featureManager.isFeatureEnabled(DateScrubberFeature::class.java)
        val mediaPageKeyCacheInterval = 100
        val mockMediaPageKeyCache =
            listOf(
                MediaPageKey(pickerId = 105L, dateTakenMillis = 1759165800000L),
                MediaPageKey(pickerId = 104L, dateTakenMillis = 1759165700000L),
                MediaPageKey(pickerId = 103L, dateTakenMillis = 1759165700000L),
                MediaPageKey(pickerId = 102L, dateTakenMillis = 1759165600000L),
                MediaPageKey(pickerId = 101L, dateTakenMillis = 1759165500000L),
            )
        val mediaPagingSource =
            MediaPagingSource(
                contentResolver = contentResolver,
                availableProviders = availableProviders,
                mediaProviderClient = mockMediaProviderClient,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                testPhotopickerConfiguration,
                isDateScrubberEnabled,
                events,
                pageSize,
                mediaPageKeyCacheInterval = mediaPageKeyCacheInterval,
                mediaPageKeyCache = mockMediaPageKeyCache,
            )

        val anchorPosition = 555
        val pagingState =
            createFakePagingState(anchorPosition = anchorPosition, pageSize = pageSize)

        // Required index in cache is 5, that is not available, getRefreshKey should return null
        assertThat(mediaPagingSource.getRefreshKey(pagingState)).isEqualTo(null)
    }

    /**
     * Creates a fake [PagingState] instance for testing purposes.
     *
     * This function manually constructs a [PagingState] to simulate the state of the Paging
     * library, allowing tests to precisely control properties like the [anchorPosition] and
     * [pageSize] without relying on a real data source.
     *
     * @param anchorPosition The index of the item that is currently in the viewport.
     * @param pageSize The page size of the PagingConfig.
     * @return A mock [PagingState] instance.
     */
    fun createFakePagingState(
        anchorPosition: Int,
        pageSize: Int,
    ): PagingState<MediaPageKey, Media> {
        return PagingState(
            pages =
                listOf(
                    PagingSource.LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
                ),
            anchorPosition = anchorPosition,
            config = PagingConfig(pageSize),
            leadingPlaceholderCount = 0,
        )
    }
}
