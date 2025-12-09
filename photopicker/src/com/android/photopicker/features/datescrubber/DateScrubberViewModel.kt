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
package com.android.photopicker.features.datescrubber

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.photopicker.features.datescrubber.data.DateScrubberDataService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for handling date scrubber functionality.
 *
 * This ViewModel manages:
 * - Date Scrubber Cursor States (Show the Cursor & Hide the Cursor)
 * - Cursor's ScrollOffset to correctly positioned the cursor in the Sidebar
 * - Date displayed when user is dragging the cursor
 * - Target Item displaying on the UI following the cursor current positioning
 *
 * @param scopeOverride An optional CoroutineScope to be used instead of the default viewModelScope.
 * @param dateScrubberDataService The service for fetching Items Per Month data .
 *
 * Note: The provided [DateScrubberDataService] currently supports Photo grid data only. At present,
 * the Date Scrubber is available only for scrolling within the Photo grid. However, the design is
 * extensible and may support other grids in the future once [DateScrubberDataService] is extended
 * to provide their respective grid data as well.
 */
@HiltViewModel
class DateScrubberViewModel
@Inject
constructor(
    scopeOverride: CoroutineScope?,
    private val dateScrubberDataService: DateScrubberDataService,
) : ViewModel() {
    companion object {
        val TAG: String = "PhotoPickerDateScrubberViewModel"
        const val DELAY_BEFORE_HIDING_CURSOR_MS: Long = 1000 // In ms
        const val DATE_SCRUBBER_CURSOR_DESCRIPTION = "Date Scrubber Cursor"

        // If total number of items in the grid are less than [MIN_ITEMS_TO_ENABLE_CURSOR], cursor
        // should not be visible.
        const val MIN_ITEMS_TO_ENABLE_CURSOR = 100
    }

    /** Check if a scope override was injected before using the default [viewModelScope] */
    private val scope: CoroutineScope = scopeOverride ?: this.viewModelScope

    /**
     * Holds a reference to the job responsible for hiding the cursor and display date after a
     * delay.
     */
    private var hideCursorJob: Job? = null

    /**
     * True if Grid Scroll currently is in Progress, (i.e., via user gesture, not via dragging the
     * date scrubber)
     */
    private var isGridScrollInProgress = false

    /**
     * Internal [MutableStateFlow] representing the current state of date scrubber cursor
     *
     * Internally[DateScrubberCursorState] manages two states:
     * 1) [DateScrubberCursorState.isDragging] indicates whether the cursor is currently being
     *    dragged by the user
     * 2) [DateScrubberCursorState.isVisible] indicates whether the cursor is currently visible on
     *    the UI.
     */
    private val _cursorState = MutableStateFlow(DateScrubberCursorState())

    /**
     * Publicly exposed [StateFlow] that allows the UI to observe the current cursor state, such as
     * visibility and dragging status, without modifying it directly.
     */
    val cursorState: StateFlow<DateScrubberCursorState> = _cursorState

    /**
     * Internal [MutableStateFlow] for tracking the current vertical scroll offset of the cursor.
     * This value helps position the cursor correctly within the sidebar based on user interaction.
     *
     * It is null only when the [DateScrubberViewModel] class is first created and [_scrollOffset]
     * is being initialized. As soon as the cursor needs to be visible, when the user scrolls the
     * grid for the first time, it will be assigned a corresponding non-null value and will never be
     * null afterwards. In other words, it is null only during initialization. Once the cursor
     * becomes visible, [_scrollOffset] will always hold a valid value whenever it is required.
     *
     * [_scrollOffset] is responsible for placing the cursor in the sidebar. Whenever any scrolling
     * occurs in the grid, the cursor must take its correct position. To achieve this,
     * [_scrollOffset] is updated frequently, resulting in high-frequency updates.
     */
    private val _scrollOffset = MutableStateFlow<Float?>(null)

    /**
     * Publicly exposed [StateFlow] that allows the UI to observe the current scroll offset, which
     * is used to align the visual position of the cursor during interaction.
     */
    val scrollOffset: StateFlow<Float?> = _scrollOffset

    /**
     * Internal [MutableStateFlow] for holding the date string
     * [Format: "MMMM yyyy" e.g.,"July 2025"] currently displayed while the user drags the cursor.
     *
     * Value = null Means, no date should be displayed with the cursor
     */
    private val _dateDisplayed = MutableStateFlow<String?>(null)

    /**
     * Publicly exposed [StateFlow] providing the currently displayed date string near the cursor
     */
    val dateDisplayed: StateFlow<String?> = _dateDisplayed

    /**
     * Called every time when user manually starts scrolling the grid (i.e., via user gesture, not
     * via dragging the date scrubber).
     *
     * Since the cursor should only start displaying on the UI when user starts scrolling the grid
     * manually, this is the first point of entry to display the date scrubber cursor in the
     * sidebar.
     *
     * When user manually scrolls the grid, only the cursor should be visible, not the date, so we
     * need to immediately hide the display date if it's still present with the cursor.
     *
     * Every time the cursor becomes visible, it is required to update the current scroll offset to
     * correctly position the cursor in the sidebar indicating the firstVisibleItemIndex in the grid
     * because:
     * - When parent height updates (either we rotate the device or in Embedded we are going
     *   expanded -> collapse -> expanded), the cursor should reset its positioning based on the new
     *   available scroll range length and should maintain new scroll offset.
     * - The ultimate goal is, the current item that cursor is indicating should always be present
     *   on the screen. Now in case of pinch-to-zoom if we zoom the grid or decrease number of items
     *   per row, it may happen that the item previously indicated by the cursor is currently out of
     *   screen and that's need to be updated.
     *
     * @param firstVisibleItemIndex First visible item index of the grid.
     * @param maxScrollOffsetTop The topmost position the cursor can travel to.
     * @param maxScrollOffsetBottom The bottommost position the cursor can travel to.
     */
    fun onGridStartedScrolling(
        firstVisibleItemIndex: Int,
        maxScrollOffsetTop: Float,
        maxScrollOffsetBottom: Float,
    ) {
        isGridScrollInProgress = true
        val totalItemsCount = dateScrubberDataService.getTotalItemsCount()
        // dateScrubberDataService.getTotalItemsCount() may return null if an exception occurs
        // while fetching the items-per-month data. In that case, the cursor should not be visible.
        if (totalItemsCount != null && totalItemsCount > MIN_ITEMS_TO_ENABLE_CURSOR) {
            hideCursorJob?.cancel()
            _dateDisplayed.value = null
            updateScrollOffset(firstVisibleItemIndex, maxScrollOffsetTop, maxScrollOffsetBottom)
            _cursorState.update { it.copy(isVisible = true) }
        } else {
            Log.w(
                TAG,
                "Scroll started but item count is lower than $MIN_ITEMS_TO_ENABLE_CURSOR or invalid ($totalItemsCount), not showing cursor.",
            )
        }
    }

    /**
     * Called when the grid stops scrolling (i.e., [gridState.isScrollInProgress] becomes false).
     */
    fun onGridStoppedScrolling() {
        isGridScrollInProgress = false
        scheduleHideCursorJob()
    }

    /**
     * Called every time the firstVisibleItemIndex of the grid changes to move the cursor when the
     * user manually scrolls the grid (i.e., via user gesture, not by dragging the date scrubber).
     *
     * firstVisibleItemIndex may change in two cases:
     * 1. When the user manually scrolls the grid.
     * 2. When the user drags the cursor.
     *
     * In this method, ScrollOffset will be updated only for the first case. For the second case,
     * ScrollOffset is updated via [onDrag].
     *
     * [DateScrubberCursorState.isDragging] will be false in the first case.
     *
     * @param firstVisibleItemIndex The index of the first visible item in the grid.
     * @param maxScrollOffsetTop The topmost position the cursor can travel to.
     * @param maxScrollOffsetBottom The bottommost position the cursor can travel to.
     */
    fun onScrollPositionChanged(
        firstVisibleItemIndex: Int,
        maxScrollOffsetTop: Float,
        maxScrollOffsetBottom: Float,
    ) {
        if (_cursorState.value.isVisible && !_cursorState.value.isDragging) {
            updateScrollOffset(firstVisibleItemIndex, maxScrollOffsetTop, maxScrollOffsetBottom)
        }
    }

    /**
     * Updates the current [_scrollOffset] of the cursor to maintain its position in the sidebar.
     *
     * @param firstVisibleItemIndex First visible item index of the grid.
     * @param maxScrollOffsetTop The topmost position the cursor can travel to.
     * @param maxScrollOffsetBottom The bottommost position the cursor can travel to.
     */
    private fun updateScrollOffset(
        firstVisibleItemIndex: Int,
        maxScrollOffsetTop: Float,
        maxScrollOffsetBottom: Float,
    ) {
        val scrollOffsetRatio = getScrollOffsetRatio(firstVisibleItemIndex)
        if (scrollOffsetRatio != null) {
            val totalScrollableRange = maxScrollOffsetBottom - maxScrollOffsetTop
            _scrollOffset.value = maxScrollOffsetTop + (scrollOffsetRatio * totalScrollableRange)
        } else {
            Log.w(TAG, "Failed to get scroll offset ratio, hiding cursor.")
            // In case some changes happen in the DB in the background and an exception occurs
            // while restoring the items-per-month data inside DateScrubberDataService or total
            // items count becomes zero then scrollOffsetRatio will be null and the cursor should be
            // hidden immediately.
            hideCursorImmediately()
        }
    }

    /**
     * Calculates the scroll offset ratio based on the given item position relative to the total
     * number of items in the grid. This ratio is used to determine the vertical positioning of the
     * date scrubber cursor within its scrollable range.
     *
     * If the total item count is unavailable (null), the function returns null, indicating that the
     * cursor cannot be positioned.
     *
     * The ratio is clamped between 0f and 1f to ensure the cursor remains within bounds.
     *
     * @param itemPosition The index of the current item (usually firstVisibleItemIndex).
     * @return The scroll offset ratio between 0f (top) and 1f (bottom), or null if unavailable.
     */
    private fun getScrollOffsetRatio(itemPosition: Int): Float? {
        val totalItems = dateScrubberDataService.getTotalItemsCount()
        if (totalItems == null || totalItems <= 0) return null

        return (itemPosition.toFloat() / totalItems.toFloat()).coerceIn(
            minimumValue = 0f,
            maximumValue = 1f,
        )
    }

    /**
     * Called when the user starts manually dragging the date scrubber cursor.
     * - Cancels any ongoing job that might hide the cursor to ensure it stays visible during the
     *   drag.
     * - Updates the cursor state to reflect that it is now being dragged and should be visible on
     *   the UI.
     */
    fun onDragStarted() {
        hideCursorJob?.cancel()
        _cursorState.update { it.copy(isDragging = true, isVisible = true) }
    }

    /**
     * Updates the [_scrollOffset] to reflect the current position of the date scrubber cursor while
     * it is being actively dragged by the user. This ensures the cursor's visual position on the
     * sidebar matches the user's interaction.
     *
     * @param accumulatedDragAmount The amount of drag in pixels since the last event.
     * @param maxScrollOffsetTop The upper limit (topmost point) of the scrollable range for the
     *   cursor.
     * @param maxScrollOffsetBottom The lower limit (bottommost point) of the scrollable range for
     *   the cursor.
     * @return `true` if the scroll offset was updated, `false` otherwise.
     */
    fun onDrag(
        accumulatedDragAmount: Float,
        maxScrollOffsetTop: Float,
        maxScrollOffsetBottom: Float,
    ): Boolean {
        val preScrollOffset = _scrollOffset.value
        if (preScrollOffset == null) {
            Log.w(TAG, "Drag attempted with null scroll offset. Drag ignored.")
            return false
        }
        _scrollOffset.value =
            preScrollOffset
                .plus(accumulatedDragAmount)
                .coerceIn(minimumValue = maxScrollOffsetTop, maximumValue = maxScrollOffsetBottom)
        return true
    }

    /**
     * Called when the user stops dragging the date scrubber cursor manually.
     * - The displayed date should be hidden after a [DELAY_BEFORE_HIDING_CURSOR_MS] delay.
     * - The cursor itself should also be hidden after the same [DELAY_BEFORE_HIDING_CURSOR_MS]
     *   delay.
     */
    fun onDragStopped() {
        _cursorState.update { it.copy(isDragging = false) }
        scheduleHideCursorJob()
    }

    /**
     * This function updates the [_dateDisplayed] based on the cursor's scroll position. This is
     * necessary so that the correct date corresponding to the current scroll position is shown
     * alongside the cursor.
     *
     * @param maxScrollOffsetTop The upper limit (topmost point) of the scrollable range for the
     *   cursor.
     * @param maxScrollOffsetBottom The lower limit (bottommost point) of the scrollable range for
     *   the cursor.
     */
    fun updateDateDisplayed(maxScrollOffsetTop: Float, maxScrollOffsetBottom: Float) {
        _dateDisplayed.value =
            getDateForTargetItem(getTargetItemIndex(maxScrollOffsetTop, maxScrollOffsetBottom))
    }

    /**
     * Returns the date string corresponding to the given [targetItemIndex].
     *
     * This function maps a target item index (from the grid) to its associated month using the list
     * of item counts per month.
     *
     * @param targetItemIndex The index of the target item in the grid.
     * @return A date string in the format "MMMM yyyy" (e.g., "July 2025") representing the month
     *   and year that contains the given item index, or null if unavailable.
     */
    private fun getDateForTargetItem(targetItemIndex: Int?): String? {
        val itemsPerMonthDataList = dateScrubberDataService.getItemsCountPerMonthList()
        if (targetItemIndex != null && itemsPerMonthDataList != null) {
            // Running sum to keep track of how many items we've iterated through
            var cumulativeItemCount = 0

            // Iterate over the Date-itemCount pairs and find the first month
            // where the target index is less than the running total count
            return itemsPerMonthDataList
                .firstOrNull { (_, value) ->
                    cumulativeItemCount += value
                    targetItemIndex < cumulativeItemCount
                }
                ?.first // Return the Date String (Format: "MMMM yyyy"), the first item in the Pair
        } else {
            Log.w(TAG, "Target item index or items per month data is null. Cannot get date")
            return null
        }
    }

    /**
     * Calculates the target item index in the grid based on the current [_scrollOffset] of the
     * cursor.
     *
     * This is used to fast scroll the grid using `scrollToItem()` up to the target item index and
     * to display the date corresponding to the target item while the user is dragging the date
     * scrubber cursor.
     *
     * @param maxScrollOffsetTop The topmost position the cursor can travel to.
     * @param maxScrollOffsetBottom The bottommost position the cursor can travel to.
     * @return The calculated target item index in the grid.
     *
     * Note:
     * - Currently, the target item index calculation ignores the space contributed by:
     *     - Month separators
     *     - Banners
     *     - The search bar (if enabled)
     *     - And assumes all media items are of uniform size.
     *
     * TODO(b/427149956): Incorporate month separators into target item index calculation.
     */
    fun getTargetItemIndex(maxScrollOffsetTop: Float, maxScrollOffsetBottom: Float): Int? {
        val totalScrollableRange = maxScrollOffsetBottom - maxScrollOffsetTop
        val totalItemsCount = dateScrubberDataService.getTotalItemsCount()
        val scrollOffset = _scrollOffset.value

        // Proceed only if scroll range and total item count are valid
        if (
            totalScrollableRange > 0 &&
                totalItemsCount != null &&
                totalItemsCount > 0 &&
                scrollOffset != null
        ) {
            val scrollOffsetRatio = (scrollOffset - maxScrollOffsetTop) / totalScrollableRange
            val targetIndex =
                (scrollOffsetRatio * totalItemsCount).toInt().coerceIn(0, totalItemsCount - 1)
            return targetIndex
        } else {
            Log.w(TAG, "Invalid state for calculating target item index. Hiding cursor.")
            // In case of invalid state, hide the cursor and return null
            hideCursorImmediately()
            return null
        }
    }

    /**
     * Schedules a job to hide the date scrubber cursor and its associated date display after a
     * [DELAY_BEFORE_HIDING_CURSOR_MS] delay.
     * - Cancels any previously scheduled hide job to prevent overlap.
     * - Waits for [DELAY_BEFORE_HIDING_CURSOR_MS]
     * - After the delay:
     *     1. Sets [isVisible] in [_cursorState] to false, which hides the cursor from the UI.
     *     2. Sets [_dateDisplayed] to null, which ensures no date text is shown with the cursor.
     */
    private fun scheduleHideCursorJob() {
        // Skip hide-cursor job when grid is scrolling ([gridState.isScrollInProgress] is true)
        // or cursor is dragging
        if (isGridScrollInProgress || _cursorState.value.isDragging) return

        hideCursorJob?.cancel() // cancel any pending job
        hideCursorJob =
            scope.launch {
                delay(DELAY_BEFORE_HIDING_CURSOR_MS)
                _cursorState.update { it.copy(isVisible = false) }
                _dateDisplayed.value = null
            }
    }

    /**
     * Immediately hides the cursor and associated date display, cancelling any pending hide jobs.
     */
    fun hideCursorImmediately() {
        Log.d(TAG, "Hiding cursor immediately.")
        hideCursorJob?.cancel()
        _cursorState.update { it.copy(isVisible = false, isDragging = false) }
        _dateDisplayed.value = null
    }
}
