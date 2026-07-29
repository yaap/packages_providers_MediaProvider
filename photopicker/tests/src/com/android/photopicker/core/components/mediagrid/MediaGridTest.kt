/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.core.components

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.SurfaceControlViewHost
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.testing.TestNavHostController
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.embedded.EmbeddedState
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.SelectionImpl
import com.android.photopicker.core.theme.PhotopickerTheme
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.paging.FakeInMemoryAlbumPagingSource
import com.android.photopicker.data.paging.FakeInMemoryMediaPagingSource
import com.android.photopicker.extensions.insertMonthSeparators
import com.android.photopicker.extensions.toMediaGridItemFromAlbum
import com.android.photopicker.extensions.toMediaGridItemFromMedia
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.util.test.MockContentProviderWrapper
import com.android.photopicker.util.test.whenever
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * Unit tests for the [MediaGrid] composables.
 *
 * Since [MediaGrid]'s default implementation uses Glide to load images, the [ApplicationModule] is
 * uninstalled and this test mocks out Glide's dependencies to always return a test image.
 *
 * The data in this test suite is provided by [FakeInMemoryPagingSource] to isolate device state and
 * avoid creating test images on the device itself. Metadata is generated in the paging source, and
 * all images are backed by a test resource png that is provided by the content resolver mock.
 */
@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class MediaGridTest {
    /** Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createComposeRule()
    @get:Rule(order = 2) val glideRule = GlideTestRule()

    private val START_DESTINATION_TEXT = "Start Destination"
    private val PREVIEW_SCREEN_TEXT_PREFIX = "Preview Screen for "

    /**
     * MediaGrid uses Glide for loading images, so we have to mock out the dependencies for Glide
     * Replace the injected ContentResolver binding in [ApplicationModule] with this test value.
     */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for ActivityModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope

    /* Overrides for the ConcurrencyModule */
    @BindValue @Main val mainDispatcher: CoroutineDispatcher = testDispatcher
    @BindValue @Background val backgroundDispatcher: CoroutineDispatcher = testDispatcher

    @Mock lateinit var mockContentProvider: ContentProvider

    @Mock lateinit var mockSurfaceControlViewHost: SurfaceControlViewHost

    /**
     * A [EmbeddedState] having a mocked [SurfaceControlViewHost] instance that can be used for
     * testing in collapsed mode
     */
    private lateinit var testEmbeddedStateWithHostInCollapsedState: EmbeddedState

    /**
     * A [EmbeddedState] having a mocked [SurfaceControlViewHost] instance that can be used for
     * testing in Expanded state
     */
    private lateinit var testEmbeddedStateWithHostInExpandedState: EmbeddedState

    lateinit var pager: Pager<MediaPageKey, Media>
    lateinit var flow: Flow<PagingData<MediaGridItem>>

    private val MEDIA_GRID_TEST_TAG = "media_grid"
    private val BANNER_CONTENT_TEST_TAG = "banner_content"
    private val CUSTOM_ITEM_TEST_TAG = "custom_item"
    private val CUSTOM_ITEM_SEPARATOR_TAG = "custom_separator"
    private val CUSTOM_PLACEHOLDER_TAG = "custom_placeholder"

    private val CUSTOM_ITEM_FACTORY_TEXT = "custom item factory"
    private val CUSTOM_ITEM_SEPARATOR_TEXT = "custom item separator"
    private val CUSTOM_PLACEHOLDER_TEXT = "custom placeholder"

    private val FIRST_SEPARATOR_LABEL = "First"
    private val SECOND_SEPARATOR_LABEL = "Second"

    private val MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING = "taken on"

    /* A small MediaGridItem list that includes two Separators with three MediaItems in between */
    private val dataWithSeparators =
        buildList<MediaGridItem>() {
            add(MediaGridItem.SeparatorItem(FIRST_SEPARATOR_LABEL))
            for (i in 1..3) {
                add(
                    MediaGridItem.MediaItem(
                        media =
                            Media.Image(
                                mediaId = "$i",
                                pickerId = i.toLong(),
                                authority = "a",
                                mediaSource = MediaSource.LOCAL,
                                mediaUri =
                                    Uri.EMPTY.buildUpon()
                                        .apply {
                                            scheme("content")
                                            authority("media")
                                            path("picker")
                                            path("a")
                                            path("$i")
                                        }
                                        .build(),
                                glideLoadableUri =
                                    Uri.EMPTY.buildUpon()
                                        .apply {
                                            scheme("content")
                                            authority("a")
                                            path("$i")
                                        }
                                        .build(),
                                dateTakenMillisLong =
                                    LocalDateTime.now()
                                        .minus(i.toLong(), ChronoUnit.DAYS)
                                        .toEpochSecond(ZoneOffset.UTC) * 1000,
                                sizeInBytes = 1000L,
                                mimeType = "image/png",
                                standardMimeTypeExtension = 1,
                                width = 512,
                                height = 512,
                            )
                    )
                )
            }
            add(MediaGridItem.SeparatorItem(SECOND_SEPARATOR_LABEL))
        }

    private val pinchToZoomTestData =
        buildList<MediaGridItem>() {
            // Separator takes up a full row
            add(MediaGridItem.SeparatorItem("Pinch Separator"))
            // Media items
            for (i in 1..6) { // Enough items to test column changes
                add(
                    MediaGridItem.MediaItem(
                        media =
                            Media.Image(
                                mediaId = "pinch_media_$i",
                                pickerId = (i + 200).toLong(), // Unique pickerId
                                authority = "pinch_authority",
                                mediaSource = MediaSource.LOCAL,
                                mediaUri =
                                    Uri.EMPTY.buildUpon()
                                        .apply {
                                            scheme("content")
                                            authority("media")
                                            path("picker")
                                            path("pinch_authority")
                                            path("pinch_media_$i")
                                        }
                                        .build(),
                                glideLoadableUri =
                                    Uri.EMPTY.buildUpon()
                                        .apply {
                                            scheme("content")
                                            authority("pinch_authority")
                                            path("pinch_media_$i")
                                        }
                                        .build(),
                                dateTakenMillisLong =
                                    LocalDateTime.now()
                                        .minus(
                                            (i + 10).toLong(),
                                            ChronoUnit.DAYS,
                                        ) // Ensure different dates
                                        .toEpochSecond(ZoneOffset.UTC) * 1000,
                                sizeInBytes = 100L * i,
                                mimeType = "image/png",
                                standardMimeTypeExtension = 1,
                                width = 512,
                                height = 512,
                            )
                    )
                )
            }
        }

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        // Stub out the content resolver for Glide
        provider = MockContentProviderWrapper(mockContentProvider)
        contentResolver = ContentResolver.wrap(provider)

        // Return a resource png so that glide actually has something to load
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any(), any())) {
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getResources()
                .openRawResourceFd(R.drawable.android)
        }

        initEmbeddedStates()

        // Normally this would be created in the view model that owns the paged data.
        pager =
            Pager(PagingConfig(pageSize = 50, maxSize = 500)) {
                FakeInMemoryMediaPagingSource(nextPageSize = 50)
            }

        // Keep the flow processing out of the composable as that drastically cuts down on the
        // flakiness of individual test runs.
        flow = pager.flow.toMediaGridItemFromMedia().insertMonthSeparators()
    }

    private fun getTestableContext(): Context {
        return InstrumentationRegistry.getInstrumentation().getContext()
    }

    /** Initialize [EmbeddedState] instances */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun initEmbeddedStates() {
        if (SdkLevel.isAtLeastU()) {
            @Suppress("DEPRECATION")
            (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
            testEmbeddedStateWithHostInCollapsedState =
                EmbeddedState(isExpanded = false, host = mockSurfaceControlViewHost)
            testEmbeddedStateWithHostInExpandedState =
                EmbeddedState(isExpanded = true, host = mockSurfaceControlViewHost)
        }
    }

    /**
     * Test wrapper around the mediaGrid which sets up the required collections, and applies a test
     * tag before rendering the mediaGrid.
     */
    @Composable
    private fun grid(
        selection: SelectionImpl<Media>,
        onItemClick: (MediaGridItem) -> Unit,
        bannerContent: (@Composable () -> Unit)? = null,
    ) {
        val items = flow.collectAsLazyPagingItems()
        val selected by selection.flow.collectAsStateWithLifecycle()

        CompositionLocalProvider(
            LocalNavController provides TestNavHostController(getTestableContext()),
            LocalSelection provides selection,
        ) {
            mediaGrid(
                items = items,
                selection = selected,
                onItemClick = onItemClick,
                bannerContent = bannerContent,
                modifier = Modifier.testTag(MEDIA_GRID_TEST_TAG),
            )
        }
    }

    /** Test wrapper for MediaGrid with pinch-to-zoom enabled. */
    @Composable
    private fun pinchToZoomGrid(
        testItems: List<MediaGridItem>,
        selection: SelectionImpl<Media>,
        initialColumns: Int,
        minColumns: Int = 2,
        maxColumns: Int = 5,
        onItemClick: (MediaGridItem) -> Unit = {},
        onZoomAtMaxZoom: (MediaGridItem) -> Unit = {},
    ) {
        CompositionLocalProvider(
            LocalNavController provides TestNavHostController(getTestableContext()),
            LocalSelection provides selection,
        ) {
            val itemsFlow = flowOf(PagingData.from(testItems))
            // Use testDispatcher for consistency if advanced paging features were used,
            // though PagingData.from is simple.
            val lazyPagingItems = itemsFlow.collectAsLazyPagingItems()
            val selected by selection.flow.collectAsStateWithLifecycle()
            val state = rememberMediaGridState()

            // Provide a fixed size Box for predictable gesture coordinates and grid layout.
            Box(modifier = Modifier.size(300.dp, 500.dp)) {
                mediaGrid(
                    state = state,
                    items = lazyPagingItems,
                    selection = selected,
                    onItemClick = onItemClick,
                    initialColumns = initialColumns,
                    pinchToZoomEnabled = true,
                    pinchToZoomMinColumns = minColumns,
                    pinchToZoomMaxColumns = maxColumns,
                    onZoomAtMaxZoom = onZoomAtMaxZoom,
                    modifier = Modifier.testTag(MEDIA_GRID_TEST_TAG).fillMaxSize(),
                    // Reduce default padding to ensure more items are visible for testing layout
                    // changes.
                    contentPadding = PaddingValues(0.dp),
                    contentItemFactory = { item, _, onClick, _ ->
                        when (item) {
                            is MediaGridItem.MediaItem -> {
                                Box(
                                    modifier =
                                        Modifier.fillMaxSize().semantics(mergeDescendants = true) {
                                            contentDescription = "${item.media.mediaId}"
                                        }
                                ) {
                                    Text("${item.media.mediaId}")
                                }
                            }
                            else -> {}
                        }
                    },
                )
            }
        }
    }

    /**
     * A custom content item factory that renders the same text string for each item in the grid.
     */
    @Composable
    private fun customContentItemFactory(item: MediaGridItem, onClick: ((MediaGridItem) -> Unit)?) {
        Box(
            modifier =
                // .clickable also merges the semantics of its descendants
                Modifier.testTag(CUSTOM_ITEM_TEST_TAG).clickable {
                    if (item is MediaGridItem.MediaItem) {
                        onClick?.invoke(item)
                    }
                }
        ) {
            Text(CUSTOM_ITEM_FACTORY_TEXT)
        }
    }

    /**
     * A custom content placeholder factory that renders the same text string for each placeholder.
     */
    @Composable
    private fun customContentPlaceholderFactory() {
        Box(
            modifier =
                // Merge the semantics into the parent node to make it easy to assert and select
                // these nodes in the tree.
                Modifier.semantics(mergeDescendants = true) {}.testTag(CUSTOM_PLACEHOLDER_TAG)
        ) {
            Text(CUSTOM_PLACEHOLDER_TEXT)
        }
    }

    /** A custom content separator factory that renders the same text string for each separator. */
    @Composable
    private fun customContentSeparatorFactory() {
        Box(
            modifier =
                // Merge the semantics into the parent node to make it easy to asset and select
                // these nodes in the tree.
                Modifier.semantics(mergeDescendants = true) {}.testTag(CUSTOM_ITEM_SEPARATOR_TAG)
        ) {
            Text(CUSTOM_ITEM_SEPARATOR_TEXT)
        }
    }

    @Test
    fun testMediaGrid_pinchToZoom_invokesCallbackWhenMinColumnsReached() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        val callbackInvoked = CompletableDeferred<Boolean>()

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalSelection provides selection,
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    },
            ) {
                val itemsFlow = flowOf(PagingData.from(pinchToZoomTestData))
                val lazyPagingItems = itemsFlow.collectAsLazyPagingItems()
                val selected by selection.flow.collectAsStateWithLifecycle()
                val state = rememberMediaGridState()

                Box(modifier = Modifier.size(300.dp, 500.dp)) { // Fixed size for predictable layout
                    mediaGrid(
                        state = state,
                        items = lazyPagingItems,
                        selection = selected,
                        onItemClick = {},
                        initialColumns = 2, // Start at min columns
                        pinchToZoomEnabled = true,
                        pinchToZoomMinColumns = 2, // Min columns to trigger callback
                        pinchToZoomMaxColumns = 5,
                        onZoomAtMaxZoom = { callbackInvoked.complete(true) },
                        modifier = Modifier.testTag(MEDIA_GRID_TEST_TAG).fillMaxSize(),
                        contentPadding = PaddingValues(0.dp), // Minimal padding
                        contentItemFactory = { item, _, _, _ ->
                            when (item) {
                                is MediaGridItem.MediaItem -> {
                                    Box(Modifier.fillMaxSize()) { Text(item.media.mediaId) }
                                }
                                else -> {}
                            }
                        },
                    )
                }
            }
        }

        advanceTimeBy(500)
        composeTestRule.waitForIdle()

        // The first item in pinchToZoomTestData is a separator, so media starts at index 1.
        val targetMediaItem = pinchToZoomTestData[1] as MediaGridItem.MediaItem
        val targetNodeText = targetMediaItem.media.mediaId

        val itemNode = composeTestRule.onNode(hasText(targetNodeText), useUnmergedTree = true)
        itemNode.assertExists()
        itemNode.assertIsDisplayed()

        // Perform pinch-out (zoom in) gesture
        val itemBounds = itemNode.fetchSemanticsNode().boundsInRoot
        val itemCenter = itemBounds.center

        val pointer1Initial = Offset(10f, 0f) // Pointers start close
        val pointer2Initial = Offset(15f, 0f)
        val pointer1Final = Offset(50f, 0f) // Pointers end further apart
        val pointer2Final = Offset(65f, 50f)

        itemNode.performTouchInput {
            pinch(
                center + pointer1Initial,
                center + pointer2Initial,
                center + pointer1Final,
                center + pointer2Final,
                durationMillis = 200L,
            )
        }

        composeTestRule.waitForIdle()

        val wasInvoked = callbackInvoked.await()

        assertWithMessage("Callback should have been invoked at max zoom").that(wasInvoked).isTrue()
    }

    /** Ensures the MediaGrid loads media with the correct semantic information */
    @Test
    fun testMediaGridDisplaysMedia() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        mediaGrid.assertIsDisplayed()
    }

    /**
     * Validates that [insertMonthSeparators] correctly inserts a separator by applying the local
     * time zone offset.
     */
    @Test
    fun testInsertMonthSeparators() = runTest {
        // 1. Item 1 (The "before" item)
        // This UTC timestamp is 2024-03-15T12:00:00Z
        val item1Timestamp = 1710504000000L
        val item1 =
            Media.Image(
                mediaId = "1",
                pickerId = 1,
                authority = "a",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("1")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("1")
                        }
                        .build(),
                dateTakenMillisLong = item1Timestamp,
                sizeInBytes = 1000L,
                mimeType = "image/png",
                standardMimeTypeExtension = 1,
                width = 512,
                height = 512,
            )

        // 2. Item 2 (The "after" item)
        // This UTC timestamp is 30 days before: 2024-02-14T12:00:00Z
        val item2Timestamp = 1707912000000L
        val item2 =
            Media.Image(
                mediaId = "2",
                pickerId = 2,
                authority = "a",
                mediaSource = MediaSource.LOCAL,
                mediaUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("media")
                            path("picker")
                            path("a")
                            path("2")
                        }
                        .build(),
                glideLoadableUri =
                    Uri.EMPTY.buildUpon()
                        .apply {
                            scheme("content")
                            authority("a")
                            path("2")
                        }
                        .build(),
                dateTakenMillisLong = item2Timestamp,
                sizeInBytes = 1000L,
                mimeType = "image/png",
                standardMimeTypeExtension = 1,
                width = 512,
                height = 512,
            )

        val testPager =
            Pager(PagingConfig(pageSize = 10)) {
                FakeInMemoryMediaPagingSource(dataList = listOf(item1, item2), nextPageSize = 10)
            }

        val testFlow = testPager.flow.toMediaGridItemFromMedia().insertMonthSeparators()

        lateinit var lazyPagingItems: LazyPagingItems<MediaGridItem>

        composeTestRule.setContent { lazyPagingItems = testFlow.collectAsLazyPagingItems() }

        // Wait for Paging to load the data and Compose to finish rendering
        composeTestRule.waitForIdle()

        // Manually build the list of items currently loaded by Paging
        val loadedItems = buildList {
            for (i in 0 until lazyPagingItems.itemCount) {
                lazyPagingItems[i]?.let { add(it) }
            }
        }

        assertWithMessage("Loaded items should contain 3 elements")
            .that(loadedItems.size)
            .isEqualTo(3)

        assertThat(loadedItems[0]).isInstanceOf(MediaGridItem.MediaItem::class.java)
        assertThat(loadedItems[1]).isInstanceOf(MediaGridItem.SeparatorItem::class.java)
        assertThat(loadedItems[2]).isInstanceOf(MediaGridItem.MediaItem::class.java)

        // Calculate the expected label
        val localZoneId = ZoneId.systemDefault()
        val afterInstant = Instant.ofEpochMilli(item2Timestamp)
        val afterLocalDateTime = LocalDateTime.ofInstant(afterInstant, localZoneId)

        val expectedLabel = afterLocalDateTime.format(DateTimeFormatter.ofPattern("MMMM yyyy"))

        val separator = loadedItems[1] as MediaGridItem.SeparatorItem
        assertWithMessage("Separator label should match")
            .that(separator.label)
            .isEqualTo(expectedLabel)
    }

    /** Ensures the MediaGrid shows any banner content that is provided. */
    @Test
    fun testMediaGridDisplaysBannerContent() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                ) {
                    grid(
                        selection = selection,
                        onItemClick = {},
                        bannerContent = {
                            Text(
                                text = "bannerContent",
                                modifier = Modifier.testTag(BANNER_CONTENT_TEST_TAG),
                            )
                        },
                    )
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(BANNER_CONTENT_TEST_TAG))
        mediaGrid.assertIsDisplayed()
    }

    /** Ensures the AlbumGrid loads media with the correct semantic information */
    @Test
    fun testAlbumGridDisplaysMedia() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        // Modify the pager and flow to get data from the FakeInMemoryAlbumPagingSource.

        // Normally this would be created in the view model that owns the paged data.
        val pagerForAlbums: Pager<MediaPageKey, Group.Album> =
            Pager(PagingConfig(pageSize = 50, maxSize = 500)) { FakeInMemoryAlbumPagingSource() }

        // Keep the flow processing out of the composable as that drastically cuts down on the
        // flakiness of individual test runs.
        flow = pagerForAlbums.flow.toMediaGridItemFromAlbum()

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        mediaGrid.assertIsDisplayed()
    }

    /**
     * Ensures the MediaGrid continues to load media as the grid is scrolled. This further ensures
     * the grid, paging and glide integrations are correctly setup.
     */
    @Test
    fun testMediaGridScroll() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        // Scroll the grid down by swiping up.
        mediaGrid.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        // Scroll the grid down by swiping up.
        mediaGrid.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        // Scroll the grid down by swiping up.
        mediaGrid.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        mediaGrid.assertIsDisplayed()
    }

    /** Ensures that items have the correct semantic information before and after selection */
    @Test
    fun testMediaGridClickItemSingleSelect() {
        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration =
                        provideTestConfigurationFlow(
                            scope = backgroundScope,
                            defaultConfiguration =
                                TestPhotopickerConfiguration.build {
                                    action("")
                                    selectionLimit(1)
                                },
                        ),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("")
                            selectionLimit(1)
                        }
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                action("")
                                selectionLimit(1)
                            },
                    ) {
                        grid(
                            /* selection= */ selection,
                            /* onItemClick= */ { item ->
                                launch {
                                    if (item is MediaGridItem.MediaItem)
                                        selection.toggle(item.media)
                                }
                            },
                        )
                    }
                }
            }

            composeTestRule
                .onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
                .onChildren()
                // Remove the separators
                .filter(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)
        }
    }

    /** Ensures that items have the correct semantic information before and after selection */
    @Test
    fun testMediaGridClickItemMultiSelect() {
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()
        val selectedString = resources.getString(R.string.photopicker_item_selected)

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration =
                        provideTestConfigurationFlow(
                            scope = backgroundScope,
                            defaultConfiguration =
                                TestPhotopickerConfiguration.build {
                                    action("")
                                    selectionLimit(50)
                                },
                        ),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("")
                            selectionLimit(50)
                        }
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                action("")
                                selectionLimit(50)
                            },
                    ) {
                        grid(
                            /* selection= */ selection,
                            /* onItemClick= */ { item ->
                                launch {
                                    if (item is MediaGridItem.MediaItem)
                                        selection.toggle(item.media)
                                }
                            },
                        )
                    }
                }
            }

            composeTestRule
                .onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
                .onChildren()
                // Remove the separators
                .filter(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)

            // Ensure the selected semantics got applied to the selected node.
            composeTestRule.waitUntilAtLeastOneExists(hasContentDescription(selectedString))
        }
    }

    @Test
    fun testMediaGridSelectionChangesContentDescription() {
        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration =
                        provideTestConfigurationFlow(
                            scope = backgroundScope,
                            defaultConfiguration =
                                TestPhotopickerConfiguration.build {
                                    action("")
                                    selectionLimit(50) // multi-select
                                },
                        ),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("")
                            selectionLimit(50)
                        }
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                action("")
                                selectionLimit(50)
                            },
                    ) {
                        grid(
                            /* selection= */ selection,
                            /* onItemClick= */ { item ->
                                launch {
                                    if (item is MediaGridItem.MediaItem)
                                        selection.toggle(item.media)
                                }
                            },
                        )
                    }
                }
            }

            // Find an item to click on.
            val itemToSelect =
                composeTestRule
                    .onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
                    .onChildren()
                    .filter(
                        hasContentDescription(
                            MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING, // "taken on"
                            substring = true,
                        )
                    )
                    .onFirst()

            itemToSelect.assertExists()

            // Verify nothing is selected initially.
            composeTestRule
                .onAllNodes(hasContentDescription("Selected", substring = true))
                .assertCountEquals(0)

            // Click to select the item.
            itemToSelect.performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Verify one item is now selected.
            composeTestRule
                .onAllNodes(hasContentDescription("Selected", substring = true))
                .assertCountEquals(1)
        }
    }

    /** Ensures that items have the correct semantic information before and after selection */
    @Test
    fun testMediaGridClickItemOrderedSelection() {
        val photopickerConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                selectionLimit(2)
                pickImagesInOrder(true)
            }

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration =
                        provideTestConfigurationFlow(
                            scope = backgroundScope,
                            defaultConfiguration = photopickerConfiguration,
                        ),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides photopickerConfiguration
                ) {
                    PhotopickerTheme(isDarkTheme = false, config = photopickerConfiguration) {
                        grid(
                            /* selection= */ selection,
                            /* onItemClick= */ { item ->
                                launch {
                                    if (item is MediaGridItem.MediaItem)
                                        selection.toggle(item.media)
                                }
                            },
                        )
                    }
                }
            }

            composeTestRule
                .onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
                .onChildren()
                // Remove the separators
                .filter(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)

            // Ensure the ordered selected semantics got applied to the selected node.
            composeTestRule.waitUntilAtLeastOneExists(hasText("1"))
        }
    }

    /** Ensures that Separators are correctly inserted into the MediaGrid. */
    @Test
    fun testMediaGridSeparator() {
        // Provide a custom PagingData that puts Separators in specific positions to reduce
        // test flakiness of having to scroll to find a separator.
        val customData = PagingData.from(dataWithSeparators)
        val dataFlow = flowOf(customData)

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                    LocalNavController provides TestNavHostController(getTestableContext()),
                    LocalSelection provides selection,
                ) {
                    val items = dataFlow.collectAsLazyPagingItems()
                    val selected by selection.flow.collectAsStateWithLifecycle()
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                action("TEST_ACTION")
                                intent(Intent("TEST_ACTION"))
                            },
                    ) {
                        mediaGrid(items = items, selection = selected, onItemClick = {})
                    }
                }
            }

            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .assertCountEquals(3)
            composeTestRule.onNode(hasText(FIRST_SEPARATOR_LABEL)).assertIsDisplayed()
            composeTestRule.onNode(hasText(SECOND_SEPARATOR_LABEL)).assertIsDisplayed()
        }
    }

    /** Ensures that the grid uses a custom content item factory when it is provided */
    @Test
    fun testMediaGridCustomContentItemFactory() {
        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        }
                ) {
                    CompositionLocalProvider(
                        LocalNavController provides TestNavHostController(getTestableContext()),
                        LocalSelection provides selection,
                    ) {
                        val items = flow.collectAsLazyPagingItems()
                        val selected by selection.flow.collectAsStateWithLifecycle()
                        mediaGrid(
                            items = items,
                            selection = selected,
                            onItemClick = {},
                            contentItemFactory = { item, _, onClick, _ ->
                                customContentItemFactory(item, onClick)
                            },
                        )
                    }
                }
            }

            composeTestRule
                .onAllNodes(hasTestTag(CUSTOM_ITEM_TEST_TAG))
                .assertAll(hasText(CUSTOM_ITEM_FACTORY_TEXT))
        }
    }

    /** Ensures that the grid uses a custom content item factory when it is provided */
    @Test
    fun testMediaGridCustomContentSeparatorFactory() {
        // Provide a custom PagingData that puts Separators in specific positions to reduce
        // test flakiness of having to scroll to find a separator.
        val customData = PagingData.from(dataWithSeparators)
        val dataFlow = flowOf(customData)

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                    LocalNavController provides TestNavHostController(getTestableContext()),
                    LocalSelection provides selection,
                ) {
                    val items = dataFlow.collectAsLazyPagingItems()
                    val selected by selection.flow.collectAsStateWithLifecycle()
                    mediaGrid(
                        items = items,
                        selection = selected,
                        onItemClick = {},
                        contentSeparatorFactory = { _ -> customContentSeparatorFactory() },
                    )
                }
            }

            composeTestRule
                .onAllNodes(hasTestTag(CUSTOM_ITEM_SEPARATOR_TAG))
                .assertAll(hasText(CUSTOM_ITEM_SEPARATOR_TEXT))
        }
    }

    /**
     * Ensures that the grid uses a custom content placeholder factory when it is provided and
     * placeholders are enabled
     */
    @Test
    fun testMediaGridCustomContentPlaceholderFactory_enablePlaceholders() {
        val placeholderGridDataSize = 15

        // Creates a data flow that simulates a grid with a known size
        // but without any actual content just to render placeholders.
        pager =
            Pager(PagingConfig(pageSize = 50, maxSize = 500)) {
                FakeInMemoryMediaPagingSource(
                    dataSize = placeholderGridDataSize,
                    isPlaceholderGrid = true,
                    nextPageSize = 50,
                )
            }
        flow = pager.flow.toMediaGridItemFromMedia().insertMonthSeparators()

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                    LocalNavController provides TestNavHostController(getTestableContext()),
                    LocalSelection provides selection,
                ) {
                    val items = flow.collectAsLazyPagingItems()
                    val selected by selection.flow.collectAsStateWithLifecycle()
                    mediaGrid(
                        items = items,
                        selection = selected,
                        onItemClick = {},
                        contentPlaceholderFactory = { customContentPlaceholderFactory() },
                        arePlaceholdersEnabled = true,
                    )
                }
            }

            composeTestRule.waitForIdle()

            val nodes = composeTestRule.onAllNodes(hasTestTag(CUSTOM_PLACEHOLDER_TAG))

            // Check if at-least one placeholder visible on the screen
            assertThat(nodes.fetchSemanticsNodes().isNotEmpty()).isEqualTo(true)
            nodes.assertAll(hasText(CUSTOM_PLACEHOLDER_TEXT))
        }
    }

    /**
     * Ensures that the grid doesn't uses a custom content placeholder factory when it is provided
     * and placeholders are disabled
     */
    @Test
    fun testMediaGridCustomContentPlaceholderFactory_disablePlaceholders() {
        val placeholderGridDataSize = 15

        // Creates a data flow that simulates a grid with a known size
        // but without any actual content just to render placeholders.
        pager =
            Pager(PagingConfig(pageSize = 50, maxSize = 500)) {
                FakeInMemoryMediaPagingSource(
                    dataSize = placeholderGridDataSize,
                    isPlaceholderGrid = true,
                    nextPageSize = 50,
                )
            }
        flow = pager.flow.toMediaGridItemFromMedia().insertMonthSeparators()

        runTest {
            val selection =
                SelectionImpl<Media>(
                    scope = backgroundScope,
                    configuration = provideTestConfigurationFlow(scope = backgroundScope),
                    preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action("TEST_ACTION")
                            intent(Intent("TEST_ACTION"))
                        },
                    LocalNavController provides TestNavHostController(getTestableContext()),
                    LocalSelection provides selection,
                ) {
                    val items = flow.collectAsLazyPagingItems()
                    val selected by selection.flow.collectAsStateWithLifecycle()
                    mediaGrid(
                        items = items,
                        selection = selected,
                        onItemClick = {},
                        contentPlaceholderFactory = { customContentPlaceholderFactory() },
                        arePlaceholdersEnabled = false,
                    )
                }
            }

            composeTestRule.waitForIdle()

            val nodes = composeTestRule.onAllNodes(hasTestTag(CUSTOM_PLACEHOLDER_TAG))

            // Check no placeholder visible on the screen
            assertThat(nodes.fetchSemanticsNodes().isEmpty()).isEqualTo(true)
        }
    }

    /** Ensures that touches are transferring for embedded when swipe up in collapsed mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreTransferringToHostInEmbedded_CollapsedMode_SwipeUp() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInCollapsedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
    }

    /** Ensures that touches are transferring for embedded when swipe down in collapsed mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreTransferringToHostInEmbedded_CollapsedMode_SwipeDown() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInCollapsedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
    }

    /** Ensures that clicks are not transferring for embedded in collapsed mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreNotTransferringToHostInEmbedded_CollapsedMode_Click() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInCollapsedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { click() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is not invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
    }

    /** Ensures that touches are not transferring for embedded when swipe up in Expanded mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreNotTransferringToHostInEmbedded_ExpandedMode_SwipeUP() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is not invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
    }

    /** Ensures that touches are transferring for embedded when swipe down in Expanded mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreTransferringToHostInEmbedded_ExpandedMode_SwipeDown() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
    }

    /** Ensures that clicks are not transferring for embedded in Expanded mode */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testTouchesAreNotTransferringToHostInEmbedded_ExpandedMode_Click() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration = provideTestConfigurationFlow(scope = backgroundScope),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState,
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                        },
                ) {
                    grid(/* selection= */ selection, /* onItemClick= */ {})
                }
            }
        }

        val mediaGrid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))

        mediaGrid.performTouchInput { click() }
        composeTestRule.waitForIdle()
        mediaGrid.assertIsDisplayed()
        // Verify whether the method to transfer touch events is not invoked during testing
        @Suppress("DEPRECATION")
        verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
    }

    @Test
    fun testMediaGrid_pinchToZoom_zoomIn_reducesColumns() = runTest {
        val config =
            TestPhotopickerConfiguration.build {
                action("TEST_ACTION")
                intent(Intent("TEST_ACTION"))
            }
        val initialColumns = 3
        val minColumns = 2
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        defaultConfiguration = config,
                        scope = backgroundScope,
                    ),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalPhotopickerConfiguration provides config) {
                PhotopickerTheme(isDarkTheme = false, config = config) {
                    pinchToZoomGrid(
                        testItems = pinchToZoomTestData,
                        selection = selection,
                        initialColumns = initialColumns,
                        minColumns = minColumns,
                        maxColumns = initialColumns + 1, // ensure max is > initial
                    )
                }
            }
        }
        advanceTimeBy(500) // Allow time for collectors
        composeTestRule.waitForIdle() // Wait for recomposition.

        // pinchToZoomTestData has a separator at index 0.
        // Media items start from index 1 of pinchToZoomTestData.
        // Item "pinch_media_1" (MediaGridItem index 1)
        // Item "pinch_media_2" (MediaGridItem index 2)
        // Item "pinch_media_3" (MediaGridItem index 3)
        // Item "pinch_media_4" (MediaGridItem index 4)

        // With 3 columns:
        // Row 0 (Grid): Separator (spans 3 columns)
        // Row 1 (Grid): pinch_media_1, pinch_media_2, pinch_media_3
        // Row 2 (Grid): pinch_media_4, pinch_media_5, pinch_media_6
        // So, pinch_media_1 and pinch_media_4 should be in different rows.
        assertWithMessage(
                "Initially, item pinch_media_1 and pinch_media_4 should be in different rows"
            )
            .that(areItemsInSameRow("pinch_media_1", "pinch_media_4"))
            .isFalse()
        assertWithMessage(
                "Initially, item pinch_media_1 and pinch_media_2 should be in the same row"
            )
            .that(areItemsInSameRow("pinch_media_1", "pinch_media_2"))
            .isTrue()

        // Perform pinch-out (zoom in) gesture to reduce columns to minColumns (2)
        composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG)).performTouchInput {
            val start0 = center + Offset(-50f, 0f)
            val end0 = center + Offset(-100f, 0f)
            val start1 = center + Offset(50f, 0f)
            val end1 = center + Offset(100f, 0f)
            val duration = 300L

            // First finger down
            down(pointerId = 0, position = start0)
            // Second finger down
            down(pointerId = 1, position = start1)

            // Move fingers over half the duration
            // advanceEventTime is critical for gestures to be processed correctly.
            advanceEventTime(duration / 2)
            moveTo(pointerId = 0, position = end0)
            moveTo(pointerId = 1, position = end1)

            // Hold for the second half of the duration
            advanceEventTime(duration / 2)

            // Lift fingers
            up(pointerId = 0)
            up(pointerId = 1)
        }
        advanceTimeBy(1000) // Allow time for recomposition and animation
        composeTestRule.waitForIdle()

        // With 2 columns:
        // Row 0 (Grid): Separator (spans 2 columns)
        // Row 1 (Grid): pinch_media_1, pinch_media_2
        // Row 2 (Grid): pinch_media_3, pinch_media_4
        // Row 3 (Grid): pinch_media_5, pinch_media_6
        // Now, pinch_media_1 and pinch_media_3 should be in different rows.
        // pinch_media_1 and pinch_media_2 should still be in the same row.
        // pinch_media_2 and pinch_media_3 should be in different rows.

        assertWithMessage(
                "After zoom in, item pinch_media_1 and pinch_media_3 should be in different rows"
            )
            .that(areItemsInSameRow("pinch_media_1", "pinch_media_3"))
            .isFalse()
        assertWithMessage(
                "After zoom in, item pinch_media_1 and pinch_media_2 should be in the same row"
            )
            .that(areItemsInSameRow("pinch_media_1", "pinch_media_2"))
            .isTrue()
        assertWithMessage(
                "After zoom in, item pinch_media_2 and pinch_media_3 should be in different rows"
            )
            .that(areItemsInSameRow("pinch_media_2", "pinch_media_3"))
            .isFalse()
    }

    @Test
    fun testMediaGrid_pinchToZoom_zoomOut_increasesColumns() = runTest {
        val config =
            TestPhotopickerConfiguration.build {
                action("TEST_ACTION")
                intent(Intent("TEST_ACTION"))
            }
        val initialColumns = 3
        val maxColumns = 4
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        defaultConfiguration = config,
                        scope = backgroundScope,
                    ),
                preSelectedMedia = TestDataServiceImpl().preSelectionMediaData,
            )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalPhotopickerConfiguration provides config) {
                PhotopickerTheme(isDarkTheme = false, config = config) {
                    pinchToZoomGrid(
                        testItems = pinchToZoomTestData,
                        selection = selection,
                        initialColumns = initialColumns,
                        minColumns = initialColumns - 1, // ensure min is < initial
                        maxColumns = maxColumns,
                    )
                }
            }
        }

        advanceTimeBy(500) // Allow time for recomposition and animation
        composeTestRule.waitForIdle()

        // With 3 columns:
        // Row 0 (Grid): Separator
        // Row 1 (Grid): pinch_media_1, pinch_media_2, pinch_media_3
        // Row 2 (Grid): pinch_media_4, pinch_media_5, pinch_media_6
        // pinch_media_3 and pinch_media_4 are in different rows.
        assertWithMessage(
                "Initially, item pinch_media_3 and pinch_media_4 should be in different rows"
            )
            .that(areItemsInSameRow("pinch_media_3", "pinch_media_4"))
            .isFalse()
        assertWithMessage(
                "Initially, items pinch_media_1, pinch_media_2, pinch_media_3 should be in the same row"
            )
            .that(
                areItemsInSameRow("pinch_media_1", "pinch_media_2") &&
                    areItemsInSameRow("pinch_media_2", "pinch_media_3")
            )
            .isTrue()

        // Perform pinch-in (zoom out) gesture to increase columns to maxColumns (4)
        composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG)).performTouchInput {
            pinch(
                start0 = center + Offset(-100f, 0f), // Start points for two fingers
                end0 = center + Offset(-50f, 0f), // End points after moving inwards
                start1 = center + Offset(100f, 0f),
                end1 = center + Offset(50f, 0f),
                durationMillis = 300L,
            )
        }
        advanceTimeBy(500) // Allow time for recomposition and animation
        composeTestRule.waitForIdle()

        // With 4 columns:
        // Row 0 (Grid): Separator
        // Row 1 (Grid): pinch_media_1, pinch_media_2, pinch_media_3, pinch_media_4
        // Row 2 (Grid): pinch_media_5, pinch_media_6
        // Now, pinch_media_3 and pinch_media_4 should be in the SAME row.
        assertWithMessage(
                "After zoom out, item pinch_media_3 and pinch_media_4 should be in the same row"
            )
            .that(areItemsInSameRow("pinch_media_3", "pinch_media_4"))
            .isTrue()
        assertWithMessage(
                "After zoom out, item pinch_media_4 and pinch_media_5 should be in different rows"
            )
            .that(areItemsInSameRow("pinch_media_4", "pinch_media_5"))
            .isFalse()
    }

    private fun SemanticsNodeInteraction.getTop(): Float {
        return fetchSemanticsNode().boundsInRoot.top
    }

    // Helper to check if two items are visually in the same row.
    // Items are identified by their content description (mediaId part).
    private fun areItemsInSameRow(
        item1IdSubstring: String,
        item2IdSubstring: String,
        allowSmallDelta: Boolean = true,
    ): Boolean {
        val item1Node =
            composeTestRule.onNode(
                hasContentDescription(item1IdSubstring, substring = true)
                // useUnmergedTree = true,
            )
        val item2Node =
            composeTestRule.onNode(
                hasContentDescription(item2IdSubstring, substring = true)
                // useUnmergedTree = true,
            )
        item1Node.assertExists()
        item2Node.assertExists()
        val top1 = item1Node.getTop()
        val top2 = item2Node.getTop()
        // Using a small delta for float comparison can be useful if exact pixel alignment isn't
        // guaranteed.
        return if (allowSmallDelta) kotlin.math.abs(top1 - top2) < 5.0f else top1 == top2
    }
}
