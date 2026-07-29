/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.photopicker.features.datescrubber

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.data.TestDateScrubberDataServiceImpl
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DateScrubberViewModelTest {
    val maxScrollOffsetTop: Float = -777.0f
    val maxScrollOffsetBottom: Float = 723.0f
    val totalScrollableRange = maxScrollOffsetBottom - maxScrollOffsetTop

    var DATA_SIZE = 150
    private val fixedCurrentDateTime =
        LocalDateTime.of(2025, 8, 26, 12, 0) // August 26, 2025, 12:00 PM

    /**
     * This list is designed with enough variation in dates to thoroughly test the date scrubber
     * feature, ensuring it can handle different months and years.
     *
     * For DATA_SIZE = 150, and assuming [fixedCurrentDateTime] is a reference for the current time,
     * here is what the list would be for the UTC time zone:
     * [(August 2025, 8), (July 2025, 10), (June 2025, 10), (May 2025, 11), (April 2025, 10), (March 2025, 10), (February 2025, 9), (January 2025, 11), (December 2024, 10), (November 2024, 10), (October 2024, 10), (September 2024, 10), (August 2024, 11), (July 2024, 10), (June 2024, 10)]
     */
    val DATA: List<Media>
        get() {
            return buildList() {
                for (i in 1..DATA_SIZE) {
                    add(
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
                                fixedCurrentDateTime
                                    .minus(i.toLong() * 3, ChronoUnit.DAYS)
                                    .toEpochSecond(ZoneOffset.UTC) * 1000,
                            sizeInBytes = 1000L,
                            mimeType = "image/png",
                            standardMimeTypeExtension = 1,
                            width = 512,
                            height = 512,
                        )
                    )
                }
            }
        }

    val testDateScrubberDataService = TestDateScrubberDataServiceImpl()

    @Test
    fun testCursorBehavior_onGridScrollStart() {
        runTest {
            // Prepares the test data to simulate a populated media grid.
            testDateScrubberDataService.mediaList = DATA
            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Asserts the initial state of the cursor before any scrolling.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)

            // Index of the first item visible on the screen.
            val firstVisibleItemIndex = DATA_SIZE / 2

            // Cursor should exactly be in the middle of the side scrollable range
            val expectedScrollOffset = maxScrollOffsetTop + totalScrollableRange / 2

            // Calls the method that is triggered when the grid begins to scroll.
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Verifies the state of the cursor after the scroll starts.
            // The cursor should be visible
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffset)
        }
    }

    @Test
    fun testCursorHidden_whenGridHasNullItemsCount() {
        runTest {
            // Initializes the ViewModel without providing any data, simulating a null item count.
            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Asserts the initial state: the cursor should be hidden by default.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)

            val firstVisibleItemIndex = 0

            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Asserts that the cursor remains hidden because the conditions for its visibility
            // (valid enough items) are not met.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)
        }
    }

    @Test
    fun testCursorHidden_whenItemCountBelowThreshold() {
        runTest {
            // Set the DATA_SIZE to be just below the minimum required for the cursor to appear.
            DATA_SIZE = DateScrubberViewModel.MIN_ITEMS_TO_ENABLE_CURSOR - 1
            // Populate the data service with the specified number of items.
            testDateScrubberDataService.mediaList = DATA

            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Assert the initial state: the cursor should be hidden by default.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)

            val firstVisibleItemIndex = 0

            // Call the method that is triggered when the grid starts scrolling.
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Assert that the cursor remains hidden, confirming the feature is disabled when the
            // item count is below threshold.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)
        }
    }

    @Test
    fun testCursorHidesAfterDelay_whenGridScrollEnds() {
        runTest {
            testDateScrubberDataService.mediaList = DATA
            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Initial state assertions: the cursor should not be visible.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)

            val firstVisibleItemIndex = 50

            // Simulate the grid starting to scroll.
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Assert that the cursor is now visible after scrolling begins.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)

            // Simulate the grid stopping its scroll. This schedules the hide action with a delay.
            viewModel.onGridStoppedScrolling()

            // Advance time to the exact moment the delay should end.
            advanceTimeBy(DateScrubberViewModel.DELAY_BEFORE_HIDING_CURSOR_MS)

            // Assert that the cursor is still visible, confirming the hide action has not occurred
            // yet.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)

            // Advance time by one more millisecond to trigger the hide action.
            advanceTimeBy(1)

            // The cursor should now be hidden.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
        }
    }

    @Test
    fun testCursorRemainsVisible_whenGridStopsScrollingDuringActiveDrag() {
        runTest {
            testDateScrubberDataService.mediaList = DATA
            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Initial state assertions: the cursor should not be visible.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)

            val firstVisibleItemIndex = 50

            // Simulate the grid starting to scroll.
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Assert that the cursor is now visible after scrolling begins.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)

            // Simulate the user starting a drag gesture before the grid stops scrolling.
            viewModel.onDragStarted()
            advanceTimeBy(100)

            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(true)

            // Simulate the grid stopping its scroll.
            // Since drag is in progress it should not schedule the hide cursor job
            viewModel.onGridStoppedScrolling()

            // Advance time to the exact moment the delay should end.
            advanceTimeBy(DateScrubberViewModel.DELAY_BEFORE_HIDING_CURSOR_MS + 1)

            // Assert that the cursor is still visible, confirming the hide action has not occurred
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
        }
    }

    @Test
    fun testCursorPositionUpdatesOnManualScroll() {
        runTest {
            testDateScrubberDataService.mediaList = DATA
            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Assert the initial state: the cursor should be hidden before any action.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)

            // Define the initial visible item index.
            var firstVisibleItemIndex = 0
            var expectedScrollOffset = maxScrollOffsetTop

            // Simulate the start of a grid scroll.
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Assert the cursor is visible and its offset is calculated for the starting position.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffset)

            // Simulate the grid scrolling to a new position.
            firstVisibleItemIndex = DATA_SIZE / 2

            // Cursor should exactly be in the middle of the side scrollable range
            expectedScrollOffset = maxScrollOffsetTop + totalScrollableRange / 2

            viewModel.onScrollPositionChanged(
                firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Assert the cursor's visibility and that its offset has updated to the new position.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffset)

            // Simulate a further scroll till end
            firstVisibleItemIndex = DATA_SIZE
            expectedScrollOffset = maxScrollOffsetBottom
            viewModel.onScrollPositionChanged(
                firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Assert the cursor remains visible and its offset has again updated.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffset)
        }
    }

    @Test
    fun testCursorHidesImmediately_whenItemDataBecomesNullDuringScroll() {
        runTest {
            testDateScrubberDataService.mediaList = DATA
            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Assert the initial state before scrolling starts.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)

            // Simulate the start of a scroll
            val firstVisibleItemIndex1 = 0
            val expectedScrollOffset1 = maxScrollOffsetTop
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = firstVisibleItemIndex1,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            // Advance time and verify the cursor is now visible.
            advanceTimeBy(100)
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffset1)

            // Simulate a manual scroll to a new position.
            val firstVisibleItemIndex2 = DATA_SIZE / 2

            // Cursor should exactly be in the middle of the side scrollable range
            val expectedScrollOffset2 = maxScrollOffsetTop + totalScrollableRange / 2

            viewModel.onScrollPositionChanged(
                firstVisibleItemIndex2,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            // Advance time and verify the cursor is still visible at the new position.
            advanceTimeBy(100)
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffset2)

            // Simulate the data becoming null while a scroll is in progress.
            val firstVisibleItemIndex3 = 100
            testDateScrubberDataService.mediaList = null
            viewModel.onScrollPositionChanged(
                firstVisibleItemIndex3,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Assert that the cursor has been hidden immediately because the data is no longer
            // available.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            // The scroll offset should not have changed from the last valid position.
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffset2)
        }
    }

    @Test
    fun testScrollOffsetUnaffectedByOnScrollPositionChanged_whenUserDragsCursor() {
        runTest {
            testDateScrubberDataService.mediaList = DATA
            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Assert initial state: the cursor should be hidden before any interaction.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)

            // Define a starting position for the grid.
            val preFirstVisibleItemIndex = 0

            val preExpectedScrollOffset = maxScrollOffsetTop

            // Simulate the grid starting to scroll from a specific position.
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = preFirstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            // Advance time and verify the cursor is now visible and at the correct position.
            advanceTimeBy(100)
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(preExpectedScrollOffset)

            // Simulate the user starting to drag the date scrubber cursor.
            viewModel.onDragStarted()
            val nextFirstVisibleItemIndex = 50
            advanceTimeBy(100)

            // verify the cursor's state has changed to "dragging".
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(true)

            // While dragging, simulate the grid's first visible item changing.
            viewModel.onScrollPositionChanged(
                nextFirstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(true)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            // While user dragging the cursor,  scroll offset should not be updated by
            // [DateScrubberViewModel.onScrollPositionChanged].
            // During drag ScrollOffset should be updated only by
            // [DateScrubberViewModel.updateScrollOffsetAndDateDisplayed]
            assertThat(viewModel.scrollOffset.value).isEqualTo(preExpectedScrollOffset)
        }
    }

    @Test
    fun testCursorStateUpdatesOnDragStarted() {
        runTest {
            testDateScrubberDataService.mediaList = DATA
            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Assert initial state: cursor is not visible and not dragging.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)

            val firstVisibleItemIndex = 0

            val expectedScrollOffset = maxScrollOffsetTop

            // Simulate scrolling to trigger cursor visibility.
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Assert state after scrolling starts: cursor is now visible.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffset)

            // Simulate the start of a drag gesture.
            viewModel.onDragStarted()

            advanceTimeBy(100)

            // Assert state after dragging starts: cursor remains visible and is now dragging.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(true)
        }
    }

    /**
     * Verifies that cursor state, date, and scroll offset are updated correctly when the user drags
     * the cursor.
     */
    @Test
    fun testCursorStateAndDataOnDrag() {
        runTest {
            testDateScrubberDataService.mediaList = DATA
            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Assert initial state: cursor not visible or dragging.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)

            val firstVisibleItemIndex = 0
            val expectedInitialScrollOffset = maxScrollOffsetTop

            // Simulate scrolling to show the cursor.
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Assert state after scrolling starts: cursor is now visible, but not dragging.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedInitialScrollOffset)

            // Simulate start of drag gesture.
            viewModel.onDragStarted()
            advanceTimeBy(100)

            // Assert state after dragging starts: cursor is now visible and in a dragging state.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(true)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedInitialScrollOffset)

            // Simulate dragging the cursor to the middle of the scrollable range
            val expectedScrollOffsetForMiddle = maxScrollOffsetTop + totalScrollableRange / 2
            val requiredAccumulatedDragAmount =
                expectedScrollOffsetForMiddle - expectedInitialScrollOffset
            val expectedCurrentDisplayDate =
                "January 2025" // Date of the 75th Item (Middle of Dataset where totalItems = 150)
            var isDragSuccessful =
                viewModel.onDrag(
                    requiredAccumulatedDragAmount,
                    maxScrollOffsetTop,
                    maxScrollOffsetBottom,
                )

            advanceTimeBy(100)

            assertThat(isDragSuccessful).isEqualTo(true)

            if (isDragSuccessful) {
                viewModel.updateDateDisplayed(maxScrollOffsetTop, maxScrollOffsetBottom)
            }

            advanceTimeBy(100)

            // Assert state after dragging: cursor remains visible and in a dragging state. The
            // displayed date should now be updated to reflect the new scroll position.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(true)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(expectedCurrentDisplayDate)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffsetForMiddle)

            // Assert that the target item index is correct.
            var targetItem = viewModel.getTargetItemIndex(maxScrollOffsetTop, maxScrollOffsetBottom)
            advanceTimeBy(100)
            val expectedTargetItem =
                75 // Index of the 75th Item (Middle of the Dataset where the totalItems = 150)
            assertThat(targetItem).isEqualTo(expectedTargetItem)

            // Simulate dragging the cursor to end position
            val requiredScrollOffsetForEnd = maxScrollOffsetBottom
            val requiredAccumulatedDragAmountForEnd =
                maxScrollOffsetBottom - expectedScrollOffsetForMiddle
            val expectedEndDisplayDate = "June 2024"
            isDragSuccessful =
                viewModel.onDrag(
                    requiredAccumulatedDragAmountForEnd,
                    maxScrollOffsetTop,
                    maxScrollOffsetBottom,
                )

            advanceTimeBy(100)

            assertThat(isDragSuccessful).isEqualTo(true)

            if (isDragSuccessful) {
                viewModel.updateDateDisplayed(maxScrollOffsetTop, maxScrollOffsetBottom)
            }

            advanceTimeBy(100)

            // Assert state after dragging: cursor remains visible and in a dragging state. The
            // displayed date should now be updated to reflect the new scroll position.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(true)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(expectedEndDisplayDate)
            assertThat(viewModel.scrollOffset.value).isEqualTo(requiredScrollOffsetForEnd)

            // Assert that the target item index is correct.
            targetItem = viewModel.getTargetItemIndex(maxScrollOffsetTop, maxScrollOffsetBottom)
            advanceTimeBy(100)
            val expectedEndTargetItem = DATA_SIZE - 1
            assertThat(targetItem).isEqualTo(expectedEndTargetItem)
        }
    }

    /**
     * Verifies that the date scrubber cursor state and data are correctly updated when the user
     * stops dragging and the cursor eventually disappears.
     *
     * This test simulates the entire lifecycle of a user drag: from the initial scroll to show the
     * cursor, to the dragging action itself, and finally, to the delay before the cursor
     * automatically hides. It asserts that the ViewModel's state is correct at each step.
     */
    @Test
    fun testCursorStateAndDataUpdatesOnDragStop() {
        runTest {
            testDateScrubberDataService.mediaList = DATA
            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Assert initial state: cursor not visible or dragging.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)

            val firstVisibleItemIndex = 0
            val expectedInitialScrollOffset = maxScrollOffsetTop

            // Simulate scrolling to show the cursor.
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Assert state after scrolling starts: cursor is now visible, but not dragging.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedInitialScrollOffset)

            // Simulate the grid stopping its scroll to set
            // dateScrubberViewModel.isGridScrollInProgress = false
            viewModel.onGridStoppedScrolling()
            advanceTimeBy(100)

            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)

            // Simulate start of drag gesture.
            viewModel.onDragStarted()
            advanceTimeBy(100)

            // Assert state after dragging starts: cursor is now visible and in a dragging state.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(true)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedInitialScrollOffset)

            // Simulate dragging the cursor to the middle of the scrollable range
            val expectedScrollOffsetForMiddle = maxScrollOffsetTop + totalScrollableRange / 2
            val requiredAccumulatedDragAmount =
                expectedScrollOffsetForMiddle - expectedInitialScrollOffset
            val expectedCurrentDisplayDate =
                "January 2025" // Date of the 75th Item (Middle of Dataset where totalItems = 150)
            val isDragSuccessful =
                viewModel.onDrag(
                    requiredAccumulatedDragAmount,
                    maxScrollOffsetTop,
                    maxScrollOffsetBottom,
                )

            advanceTimeBy(100)

            assertThat(isDragSuccessful).isEqualTo(true)

            if (isDragSuccessful) {
                viewModel.updateDateDisplayed(maxScrollOffsetTop, maxScrollOffsetBottom)
            }

            advanceTimeBy(100)

            // Assert state after dragging: cursor remains visible and in a dragging state. The
            // displayed date should now be updated to reflect the new scroll position.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(true)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(expectedCurrentDisplayDate)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffsetForMiddle)

            // Assert that the target item index is correct.
            val targetItem = viewModel.getTargetItemIndex(maxScrollOffsetTop, maxScrollOffsetBottom)
            advanceTimeBy(100)
            val expectedTargetItem =
                75 // Index of the 75th Item (Middle of the Dataset where the totalItems = 150)
            assertThat(targetItem).isEqualTo(expectedTargetItem)

            // Simulate the cursor drag ending.
            viewModel.onDragStopped()

            advanceTimeBy(100)

            // Assert state immediately after dragging stops: cursor is still visible for the delay
            // period but in non dragging state.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(expectedCurrentDisplayDate)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffsetForMiddle)

            // Advance time just before the cursor hiding delay expires.
            advanceTimeBy(DateScrubberViewModel.DELAY_BEFORE_HIDING_CURSOR_MS - 100)
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(expectedCurrentDisplayDate)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffsetForMiddle)

            // Advance time past the cursor hiding delay.
            advanceTimeBy(1)

            // Assert state after delay: cursor and dates are now hidden
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedScrollOffsetForMiddle)
        }
    }

    @Test
    fun testCursorHidesImmediately_whenItemDataBecomesNullDuringCursorDrag() {
        runTest {
            testDateScrubberDataService.mediaList = DATA
            val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

            advanceTimeBy(100)

            // Assert initial state: cursor not visible or dragging.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(null)

            val firstVisibleItemIndex = 0
            val expectedInitialScrollOffset = maxScrollOffsetTop

            // Simulate scrolling to show the cursor.
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )

            advanceTimeBy(100)

            // Assert state after scrolling starts: cursor is now visible, but not dragging.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedInitialScrollOffset)

            // Simulate start of drag gesture.
            viewModel.onDragStarted()
            advanceTimeBy(100)

            // Assert state after dragging starts: cursor is now visible and in a dragging state.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(true)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(true)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedInitialScrollOffset)

            // Simulate dragging the cursor to new position
            val accumulatedDragAmount = -expectedInitialScrollOffset

            // Simulate the data becoming null during a drag.
            testDateScrubberDataService.mediaList = null

            val expectedSecondScrollOffset = expectedInitialScrollOffset + accumulatedDragAmount

            val isDragSuccessful =
                viewModel.onDrag(accumulatedDragAmount, maxScrollOffsetTop, maxScrollOffsetBottom)

            advanceTimeBy(100)

            assertThat(isDragSuccessful).isEqualTo(true)

            if (isDragSuccessful) {
                viewModel.updateDateDisplayed(maxScrollOffsetTop, maxScrollOffsetBottom)
            }

            advanceTimeBy(100)

            // Assert state after data becomes null: cursor is immediately hidden, and all
            // related state variables are reset to null.
            assertThat(viewModel.cursorState.value.isVisible).isEqualTo(false)
            assertThat(viewModel.cursorState.value.isDragging).isEqualTo(false)
            assertThat(viewModel.dateDisplayed.value).isEqualTo(null)
            assertThat(viewModel.scrollOffset.value).isEqualTo(expectedSecondScrollOffset)

            // Assert that the target item is also correctly null.
            val targetItem = viewModel.getTargetItemIndex(maxScrollOffsetTop, maxScrollOffsetBottom)
            advanceTimeBy(100)
            assertThat(targetItem).isEqualTo(null)
        }
    }
}
