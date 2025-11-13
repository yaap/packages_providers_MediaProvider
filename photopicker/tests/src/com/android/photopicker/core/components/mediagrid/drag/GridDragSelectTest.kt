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

package com.android.photopicker.core.components

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.map
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.selection.SelectionImpl
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.paging.FakeInMemoryMediaPagingSource
import com.android.photopicker.util.test.dragInIncrements
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class GridDragSelectTest {

    @get:Rule val composeTestRule = createComposeRule()
    private val MEDIA_GRID_TEST_TAG = "media_grid"
    private val MEDIA_GRID_ITEM_TEST_TAG = "media_grid_item"
    private val MULTI_SELECT_CONFIG =
        TestPhotopickerConfiguration.build {
            action("TEST_ACTION")
            intent(Intent("TEST_ACTION"))
            selectionLimit(50)
        }

    lateinit var pager: Pager<MediaPageKey, Media>
    lateinit var flow: Flow<PagingData<MediaGridItem>>

    @Before
    fun setup() {
        pager =
            Pager(PagingConfig(pageSize = 50, maxSize = 500)) { FakeInMemoryMediaPagingSource() }
        flow = pager.flow.toMediaGridItemFromMedia()
    }

    /**
     * A Composable function that displays a vertical grid of media items with drag-to-select
     * functionality.
     *
     * @param state The state object that manages the grid's selection and scroll position.
     * @param config The configuration for the Photopicker. Defaults to the current local
     *   configuration.
     * @param enableAutoScroll Whether to enable auto-scrolling when dragging near the edges of the
     *   grid.
     * @param autoScrollThreshold The threshold (in dp) from the edge of the grid at which
     *   auto-scrolling starts.
     * @param autoScrollOrientation The orientation for auto-scrolling, defaults to vertical.
     * @param hapticFeedback The haptic feedback instance to use for selection events.
     * @param selectionTransform A function to transform the selected [Media] item.
     */
    @Composable
    private fun verticalGrid(
        state: GridDragSelectState,
        config: PhotopickerConfiguration = LocalPhotopickerConfiguration.current,
        enableAutoScroll: Boolean = true,
        autoScrollThreshold: Float = GridDragSelectDefaults.autoScrollThreshold,
        autoScrollOrientation: ScrollOrientation = ScrollOrientation.VERTICAL,
        hapticFeedback: HapticFeedback? = null,
        selectionTransform: (Media) -> Media = { it },
    ) {
        val items = flow.collectAsLazyPagingItems()

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = state.gridState,
            modifier =
                Modifier.fillMaxSize()
                    .onGridDragSelect(
                        config = config,
                        items = items,
                        state = state,
                        indexOffset = 0,
                        enableAutoScroll = enableAutoScroll,
                        autoScrollThreshold = autoScrollThreshold,
                        autoScrollOrientation = autoScrollOrientation,
                        hapticFeedback = hapticFeedback,
                        selectionTransform = selectionTransform,
                    )
                    .testTag(MEDIA_GRID_TEST_TAG),
        ) {
            items(count = items.itemCount) { index ->
                Box(Modifier.aspectRatio(1f).fillMaxSize().testTag(MEDIA_GRID_ITEM_TEST_TAG)) {
                    val item = items.get(index)
                    when (item) {
                        is MediaGridItem.MediaItem -> Text("${item.media.pickerId}")
                        else -> Text("other item")
                    }
                }
            }
        }
    }

    /**
     * A Composable function that displays a horizontal grid of media items with drag-to-select
     * functionality.
     *
     * @param state The state object that manages the grid's selection and scroll position.
     * @param config The configuration for the Photopicker. Defaults to the current local
     *   configuration.
     * @param enableAutoScroll Whether to enable auto-scrolling when dragging near the edges of the
     *   grid.
     * @param autoScrollThreshold The threshold (in dp) from the edge of the grid at which
     *   auto-scrolling starts.
     * @param autoScrollOrientation The orientation for auto-scrolling, defaults to horizontal.
     * @param hapticFeedback The haptic feedback instance to use for selection events.
     * @param selectionTransform A function to transform the selected [Media] item.
     */
    @Composable
    private fun horizontalGrid(
        state: GridDragSelectState,
        config: PhotopickerConfiguration = LocalPhotopickerConfiguration.current,
        enableAutoScroll: Boolean = true,
        autoScrollThreshold: Float = GridDragSelectDefaults.autoScrollThreshold,
        autoScrollOrientation: ScrollOrientation = ScrollOrientation.HORIZONTAL,
        hapticFeedback: HapticFeedback? = null,
        selectionTransform: (Media) -> Media = { it },
    ) {
        val items = flow.collectAsLazyPagingItems()

        LazyHorizontalGrid(
            rows = GridCells.Fixed(1),
            state = state.gridState,
            modifier =
                Modifier.height(150.dp)
                    .onGridDragSelect(
                        config = config,
                        items = items,
                        state = state,
                        indexOffset = 0,
                        enableAutoScroll = enableAutoScroll,
                        autoScrollThreshold = autoScrollThreshold,
                        autoScrollOrientation = autoScrollOrientation,
                        hapticFeedback = null,
                        selectionTransform = selectionTransform,
                    )
                    .testTag(MEDIA_GRID_TEST_TAG),
        ) {
            items(count = items.itemCount) { index ->
                Box(Modifier.aspectRatio(1f).fillMaxSize().testTag(MEDIA_GRID_ITEM_TEST_TAG)) {
                    val item = items.get(index)
                    when (item) {
                        is MediaGridItem.MediaItem -> Text("${item.media.pickerId}")
                        else -> Text("other item")
                    }
                }
            }
        }
    }

    @Test
    fun testDragToSelectVerticalGridSelectTopRow() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration = MULTI_SELECT_CONFIG,
                    ),
                preSelectedMedia = MutableStateFlow(emptyList()),
            )

        lateinit var state: GridDragSelectState

        composeTestRule.setContent {
            state = rememberGridDragSelectState(selection = selection)
            CompositionLocalProvider(LocalPhotopickerConfiguration provides MULTI_SELECT_CONFIG) {
                verticalGrid(state = state)
            }
        }

        assertWithMessage("Expected items to be visible")
            .that(state.gridState.layoutInfo.visibleItemsInfo)
            .isNotEmpty()

        val grid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        with(grid) {
            assertIsDisplayed()
            performTouchInput {
                down(topLeft)
                // Wait for the long press to register to enable drag-to-select
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                moveTo(topRight)
                // Wait for the scroll to finish.
                advanceEventTime(1000)
                up()
            }
        }

        composeTestRule.waitForIdle()
        assertWithMessage("expected items in selection").that(selection.size()).isEqualTo(3)
    }

    @Test
    fun testDragToSelectVerticalGridAutoScroll() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration = MULTI_SELECT_CONFIG,
                    ),
                preSelectedMedia = MutableStateFlow(emptyList()),
            )

        lateinit var state: GridDragSelectState

        composeTestRule.setContent {
            state = rememberGridDragSelectState(selection = selection)
            CompositionLocalProvider(LocalPhotopickerConfiguration provides MULTI_SELECT_CONFIG) {
                verticalGrid(state = state)
            }
        }

        assertWithMessage("Expected items to be visible")
            .that(state.gridState.layoutInfo.visibleItemsInfo)
            .isNotEmpty()

        val initialVisibleIndex = state.gridState.firstVisibleItemIndex

        val grid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        with(grid) {
            assertIsDisplayed()
            performTouchInput {
                down(center)
                // Wait for the long press to register to enable drag-to-select
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                dragInIncrements(totalOffset = getBoundsInRoot().bottom.toPx(), vertical = true)
                // Wait for the scroll to finish.
                advanceEventTime(1000)
                up()
            }
        }

        composeTestRule.waitForIdle()
        assertWithMessage("Expected firstVisibleItemIndex to have changed")
            .that(state.gridState.firstVisibleItemIndex)
            .isNotEqualTo(initialVisibleIndex)
    }

    @Test
    fun testDragToSelectVerticalGridAutoScrollDisabled() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration = MULTI_SELECT_CONFIG,
                    ),
                preSelectedMedia = MutableStateFlow(emptyList()),
            )

        lateinit var state: GridDragSelectState

        composeTestRule.setContent {
            state = rememberGridDragSelectState(selection = selection)
            CompositionLocalProvider(LocalPhotopickerConfiguration provides MULTI_SELECT_CONFIG) {
                verticalGrid(state = state, enableAutoScroll = false)
            }
        }

        assertWithMessage("Expected items to be visible")
            .that(state.gridState.layoutInfo.visibleItemsInfo)
            .isNotEmpty()

        val initialVisibleIndex = state.gridState.firstVisibleItemIndex

        val grid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        with(grid) {
            assertIsDisplayed()
            performTouchInput {
                down(center)
                // Wait for the long press to register to enable drag-to-select
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                dragInIncrements(totalOffset = getBoundsInRoot().bottom.toPx(), vertical = true)
                // Wait for the scroll to finish.
                advanceEventTime(1000)
                up()
            }
        }

        composeTestRule.waitForIdle()
        assertWithMessage("Expected firstVisibleItemIndex not to have changed")
            .that(state.gridState.firstVisibleItemIndex)
            .isEqualTo(initialVisibleIndex)
    }

    @Test
    fun testDragToSelectVerticalGridMovingPointerBackwardsRemovesItems() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration = MULTI_SELECT_CONFIG,
                    ),
                preSelectedMedia = MutableStateFlow(emptyList()),
            )

        lateinit var state: GridDragSelectState

        composeTestRule.setContent {
            state = rememberGridDragSelectState(selection = selection)
            CompositionLocalProvider(LocalPhotopickerConfiguration provides MULTI_SELECT_CONFIG) {
                // Disable auto-scrolling to make the items landing in the selection more
                // predictable.
                verticalGrid(state = state, enableAutoScroll = false)
            }
        }

        assertWithMessage("Expected items to be visible")
            .that(state.gridState.layoutInfo.visibleItemsInfo)
            .isNotEmpty()

        val collector = mutableListOf<Set<Media>>()
        backgroundScope.launch { selection.flow.toList(collector) }
        val grid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        with(grid) {
            assertIsDisplayed()
            performTouchInput {
                down(topLeft)
                // Wait for the long press to register to enable drag-to-select
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                moveTo(topRight)
            }

            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            assertWithMessage("Expected selection to have changed")
                .that(collector.size)
                .isGreaterThan(1)

            assertWithMessage("Expected swiped over items to have been added")
                .that(collector.any { it.size > 1 })
                .isTrue()

            performTouchInput {
                moveTo(topLeft)
                up()
            }

            assertWithMessage("Expected selection to contain only the initial item")
                .that(selection.size())
                .isEqualTo(1)
        }
    }

    @Test
    fun testDragToSelectHorizontalGrid() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration = MULTI_SELECT_CONFIG,
                    ),
                preSelectedMedia = MutableStateFlow(emptyList()),
            )

        lateinit var state: GridDragSelectState

        composeTestRule.setContent {
            state = rememberGridDragSelectState(selection = selection)
            CompositionLocalProvider(LocalPhotopickerConfiguration provides MULTI_SELECT_CONFIG) {
                horizontalGrid(state = state)
            }
        }

        assertWithMessage("Expected items to be visible")
            .that(state.gridState.layoutInfo.visibleItemsInfo)
            .isNotEmpty()

        val grid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        with(grid) {
            assertIsDisplayed()
            performTouchInput {
                down(topLeft)
                // Wait for the long press to register to enable drag-to-select
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                dragInIncrements(totalOffset = getBoundsInRoot().right.toPx(), vertical = false)
                // Wait for the scroll to finish.
                advanceEventTime(1000)
                up()
            }
        }

        composeTestRule.waitForIdle()
        // Given differing screen dimensions the amount of grid cells will be different, so just
        // check that the initial cell and at least one more were selected.
        assertWithMessage("expected items in selection").that(selection.size()).isGreaterThan(1)
    }

    @Test
    fun testDragToSelectHorizontalGridAutoScroll() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration = MULTI_SELECT_CONFIG,
                    ),
                preSelectedMedia = MutableStateFlow(emptyList()),
            )

        lateinit var state: GridDragSelectState

        composeTestRule.setContent {
            state = rememberGridDragSelectState(selection = selection)
            CompositionLocalProvider(LocalPhotopickerConfiguration provides MULTI_SELECT_CONFIG) {
                horizontalGrid(state = state)
            }
        }

        assertWithMessage("Expected items to be visible")
            .that(state.gridState.layoutInfo.visibleItemsInfo)
            .isNotEmpty()

        val initialVisibleIndex = state.gridState.firstVisibleItemIndex

        val grid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        with(grid) {
            assertIsDisplayed()
            performTouchInput {
                down(center)
                // Wait for the long press to register to enable drag-to-select
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                dragInIncrements(totalOffset = getBoundsInRoot().right.toPx(), vertical = false)
                // Wait for the scroll to finish.
                advanceEventTime(1000)
                up()
            }
        }
        composeTestRule.waitForIdle()
        assertWithMessage("Expected firstVisibleItemIndex to have changed")
            .that(state.gridState.firstVisibleItemIndex)
            .isNotEqualTo(initialVisibleIndex)
    }

    @Test
    fun testDragToSelectHorizontalGridAutoScrollDisabled() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration = MULTI_SELECT_CONFIG,
                    ),
                preSelectedMedia = MutableStateFlow(emptyList()),
            )

        lateinit var state: GridDragSelectState

        composeTestRule.setContent {
            state = rememberGridDragSelectState(selection = selection)
            CompositionLocalProvider(LocalPhotopickerConfiguration provides MULTI_SELECT_CONFIG) {
                horizontalGrid(state = state, enableAutoScroll = false)
            }
        }

        assertWithMessage("Expected items to be visible")
            .that(state.gridState.layoutInfo.visibleItemsInfo)
            .isNotEmpty()

        val initialVisibleIndex = state.gridState.firstVisibleItemIndex

        val grid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        with(grid) {
            assertIsDisplayed()
            performTouchInput {
                down(center)
                // Wait for the long press to register to enable drag-to-select
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                dragInIncrements(totalOffset = getBoundsInRoot().right.toPx(), vertical = false)
                // Wait for the scroll to finish.
                advanceEventTime(1000)
                up()
            }
        }

        composeTestRule.waitForIdle()
        assertWithMessage("Expected firstVisibleItemIndex not to have changed")
            .that(state.gridState.firstVisibleItemIndex)
            .isEqualTo(initialVisibleIndex)
    }

    @Test
    fun testSelectionTransformIsApplied() = runTest {
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration = MULTI_SELECT_CONFIG,
                    ),
                preSelectedMedia = MutableStateFlow(emptyList()),
            )

        lateinit var state: GridDragSelectState
        composeTestRule.setContent {
            state = rememberGridDragSelectState(selection = selection)
            CompositionLocalProvider(LocalPhotopickerConfiguration provides MULTI_SELECT_CONFIG) {
                verticalGrid(
                    state = state,
                    selectionTransform = {
                        Media.withSelectable(
                            item = it,
                            selectionSource = Telemetry.MediaLocation.MAIN_GRID,
                            album = null,
                        )
                    },
                )
            }
        }
        composeTestRule.waitForIdle()

        val grid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        with(grid) {
            assertIsDisplayed()
            performTouchInput {
                down(topLeft)
                // Wait for the long press to register to enable drag-to-select
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                dragInIncrements(totalOffset = getBoundsInRoot().right.toPx(), vertical = false)
                // Wait for the scroll to finish.
                advanceEventTime(1000)
                up()
            }
        }
        composeTestRule.waitForIdle()

        val snapshot = selection.snapshot()
        assertWithMessage("Expected selection to not be empty.").that(snapshot).isNotEmpty()
        snapshot.forEach {
            assertWithMessage("Expected transform to be applied")
                .that(it.selectionSource)
                .isEqualTo(Telemetry.MediaLocation.MAIN_GRID)
        }
    }

    @Test
    fun testDragCancellationStopsDragState() = runTest {
        // Simulating a true "cancel" event mid-drag via performTouchInput is complex.
        // This test verifies state.stopDrag() has the desired effect.
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration = MULTI_SELECT_CONFIG,
                    ),
                preSelectedMedia = MutableStateFlow(emptyList()),
            )

        lateinit var state: GridDragSelectState
        composeTestRule.setContent {
            state = rememberGridDragSelectState(selection = selection)
            CompositionLocalProvider(LocalPhotopickerConfiguration provides MULTI_SELECT_CONFIG) {
                verticalGrid(state = state)
            }
        }
        composeTestRule.waitForIdle()

        val grid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        with(grid) {
            assertIsDisplayed()
            performTouchInput {
                down(topLeft)
                // Wait for the long press to register to enable drag-to-select
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                moveBy(Offset(5f, 5f))
            }
        }
        composeTestRule.waitForIdle()

        // At this point, state.isDragging should be true.
        assertWithMessage("Should be dragging").that(state.dragState.isDragging).isTrue()

        // Now, explicitly call stopDrag as if a cancellation occurred.
        state.stopDrag() // This is what onDragCancel calls
        composeTestRule.waitForIdle()

        assertWithMessage("DragCancel should have reset dragging")
            .that(state.dragState.isDragging)
            .isFalse()
        assertWithMessage("DragCancel should have reset dragState")
            .that(state.dragState.initial)
            .isEqualTo(-1) // Check for reset state
    }

    @Test
    fun testHapticFeedbackIsInvoked() = runTest {
        val mockHapticFeedback: HapticFeedback = mock(HapticFeedback::class.java)
        val selection =
            SelectionImpl<Media>(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration = MULTI_SELECT_CONFIG,
                    ),
                preSelectedMedia = MutableStateFlow(emptyList()),
            )

        lateinit var state: GridDragSelectState
        composeTestRule.setContent {
            state = rememberGridDragSelectState(selection = selection)
            CompositionLocalProvider(LocalPhotopickerConfiguration provides MULTI_SELECT_CONFIG) {
                verticalGrid(state = state, hapticFeedback = mockHapticFeedback)
            }
        }
        composeTestRule.waitForIdle()

        val grid = composeTestRule.onNode(hasTestTag(MEDIA_GRID_TEST_TAG))
        with(grid) {
            assertIsDisplayed()
            performTouchInput {
                down(topLeft)
                // Wait for the long press to register to enable drag-to-select
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                moveBy(Offset(5f, 5f))
                up()
            }
        }
        composeTestRule.waitForIdle() // Wait for coroutines
        verify(mockHapticFeedback, times(1)).performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/**
 * An extension function to prepare a flow of [PagingData<Media>] to be provided to the [MediaGrid]
 * composable, by wrapping all of the [Media] objects in a [MediaGridItem.MediaItem].
 *
 * @return A [PagingData<MediaGridItem>] that can be processed further, or provided to the
 *   [MediaGrid].
 */
private fun Flow<PagingData<Media>>.toMediaGridItemFromMedia(): Flow<PagingData<MediaGridItem>> {
    return this.map { pagingData -> pagingData.map { MediaGridItem.MediaItem(it) } }
}
