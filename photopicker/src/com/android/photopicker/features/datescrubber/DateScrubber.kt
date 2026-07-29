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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.android.photopicker.R
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.obtainViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// Minimum drag distance (in dp) required before the cursor responds to vertical movement.
// Helps stabilize sensitivity and avoids jitter due to minor touch movements.
private val DRAG_SENSITIVITY_THRESHOLD = 0.2.dp

// A constant for the top offset of the date scrubber cursor in dp.
// Define maximum how far below the top edge the cursor is allowed to move
val DATE_SCRUBBER_TOP_OFFSET_MAX = 64.dp

// A constant for the bottom offset of the date scrubber cursor in dp.
// Define maximum how far above the bottom edge the cursor is allowed to move
val DATE_SCRUBBER_BOTTOM_OFFSET_MAX = 130.dp

// Minimum height in dp required to show the date scrubber.
private val MIN_PARENT_HEIGHT_FOR_DATE_SCRUBBER = 280.dp

/** A Composable to enable the date scrubber to support fast scrolling in the grid */
@Composable
fun DateScrubber(
    modifier: Modifier = Modifier,
    viewModel: DateScrubberViewModel = obtainViewModel(),
    params: LocationParams,
) {

    // If dateScrubberParameters is null, return early
    val dateScrubberParameters = params as? LocationParams.WithDateScrubber ?: return

    val gridState = dateScrubberParameters.gridState
    val parentHeight = dateScrubberParameters.parentHeight.value

    val density = LocalDensity.current
    val minParentHeightForDateScrubberPx =
        with(density) { MIN_PARENT_HEIGHT_FOR_DATE_SCRUBBER.toPx() }

    // If parentHeight is invalid or not enough, return early.
    if (parentHeight <= minParentHeightForDateScrubberPx) {
        Log.w(
            DateScrubberFeature.TAG,
            "Parent height ($parentHeight) is invalid or not enough to show the date scrubber, skipping DateScrubber composition.",
        )
        return
    }

    // Observing ViewModel state flows
    // Tracks visibility and dragging state of the cursor
    val cursorState by viewModel.cursorState.collectAsState()

    // Current Y offset of the cursor in sidebar
    val scrollOffset by viewModel.scrollOffset.collectAsState()

    // Date currently displayed next to the cursor
    val dateDisplayed by viewModel.dateDisplayed.collectAsState()

    /**
     * [maxScrollOffsetTop]: The highest (topmost) Y position the cursor can scroll to. Depends only
     * on the parent height.
     *
     * This value is always <=0
     *
     * The value is calculated once and remembered based on the current parentHeight to avoid
     * recomputing during recompositions unless parentHeight changes.
     */
    val maxScrollOffsetTop by
        remember(parentHeight) {
            val result =
                if (parentHeight > 0) {
                    // Get half of the parent height (center point of the layout)
                    val halfHeight = parentHeight / 2

                    // Define how far below the top edge the cursor is allowed
                    // to move (acts as a padding buffer)
                    val topOffset =
                        with(density) {
                            (parentHeight / 5).coerceAtMost(DATE_SCRUBBER_TOP_OFFSET_MAX.toPx())
                        }

                    // Calculate the top coordinate the cursor can move to:
                    // - Start from center (0), move upward to the top edge (-halfHeight), then add
                    // the top offset.
                    // - Since top values are negative in this coordinate system, we ensure the
                    // result doesn't exceed 0.
                    // Coordinate system: 0 is the vertical center; top is negative, bottom is
                    // positive
                    (-halfHeight + topOffset).coerceAtMost(0f)
                } else 0f

            mutableStateOf(result)
        }

    /**
     * [maxScrollOffsetBottom]: The lowest (bottommost) Y position the cursor can scroll to. Depends
     * only on the parent height.
     *
     * This value is always >=0
     *
     * The value is calculated once and remembered based on the current parentHeight to avoid
     * recomputing during recompositions unless parentHeight changes.
     */
    val maxScrollOffsetBottom by
        remember(parentHeight) {
            val result =
                if (parentHeight > 0) {
                    // Get half of the parent height (center point of the layout)
                    val halfHeight = parentHeight / 2

                    // Define how far above the bottom edge the cursor is allowed
                    // to move (acts as a padding buffer)
                    val bottomOffset = with(density) { DATE_SCRUBBER_BOTTOM_OFFSET_MAX.toPx() }

                    // Calculate the bottom coordinate the cursor can move to:
                    // - Start from center (0), move downward to the bottom edge (+halfHeight), then
                    // subtract the bottom offset.
                    // - Since bottom values are positive in this coordinate system, we ensure the
                    // result doesn't go below 0.
                    // Coordinate system: 0 is the vertical center; top is negative, bottom is
                    // positive
                    (halfHeight - bottomOffset).coerceAtLeast(0f)
                } else 0f

            mutableStateOf(result)
        }

    val isEmbedded =
        LocalPhotopickerConfiguration.current.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
    val isExpanded = LocalEmbeddedState.current?.isExpanded ?: false
    val isEmbeddedAndCollapsed = isEmbedded && !isExpanded

    var accumulatedDragAmount = 0f
    val coroutineScope = rememberCoroutineScope()

    /**
     * LaunchedEffect to control the visibility of the date scrubber cursor based on scroll state.
     *
     * It observes changes to [gridState.isScrollInProgress].
     * - When scrolling starts, the cursor becomes immediately visible and updates its position.
     * - When scrolling stops, a delay is introduced before hiding the cursor, giving the user a
     *   short grace period to interact further.
     */
    LaunchedEffect(gridState.isScrollInProgress) {
        // In embedded and collapsed mode, the cursor should not be visible.
        if (gridState.isScrollInProgress && !isEmbeddedAndCollapsed) {
            viewModel.onGridStartedScrolling(
                gridState.firstVisibleItemIndex,
                maxScrollOffsetTop,
                maxScrollOffsetBottom,
            )
        } else {
            viewModel.onGridStoppedScrolling()
        }
    }

    /**
     * LaunchedEffect to observe changes in [firstVisibleItemIndex] of the grid.
     *
     * Internally, it updates the scroll offset, which in turn updates the cursor position in the
     * sidebar.
     *
     * This is essentially responsible for moving the cursor in the sidebar when the user manually
     * scrolls the grid (i.e., via user gesture, not by dragging the date scrubber).
     */
    LaunchedEffect(gridState.firstVisibleItemIndex) {
        viewModel.onScrollPositionChanged(
            gridState.firstVisibleItemIndex,
            maxScrollOffsetTop,
            maxScrollOffsetBottom,
        )
    }

    /**
     * This Box hosts the draggable date scrubber cursor UI when the cursor is in visible state.
     * - It offsets the cursor vertically based on the current scroll position.
     * - Handles vertical drag gestures to update the cursor's position and fast-scroll the grid.
     * - Drag movements are only processed when the user drags enough, to avoid reacting to tiny
     *   unintentional movements.
     * - On drag, the corresponding date is shown and the grid is scrolled to the calculated target
     *   item index.
     * - If the scrubber is in embedded and collapsed mode, the cursor is hidden.
     */
    if (cursorState.isVisible && !isEmbeddedAndCollapsed) {
        Box(
            modifier =
                modifier
                    // Offset the cursor vertically based on the current scrollOffset.
                    // If scrollOffset is null, fallback to 0.
                    .offset { IntOffset(x = 0, y = scrollOffset?.roundToInt() ?: 0) }
                    // Handle vertical drag gestures for moving the date scrubber cursor.
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                accumulatedDragAmount += dragAmount
                                // Throttle sensitivity: Only trigger updates when drag is
                                // significant enough.
                                // It Improves drag stability by limiting reaction to small gestures
                                if (
                                    kotlin.math.abs(accumulatedDragAmount) >=
                                        DRAG_SENSITIVITY_THRESHOLD.toPx()
                                ) {
                                    // Compute and update scroll offset
                                    val dragSuccessful =
                                        viewModel.onDrag(
                                            accumulatedDragAmount,
                                            maxScrollOffsetTop,
                                            maxScrollOffsetBottom,
                                        )

                                    if (dragSuccessful) {
                                        accumulatedDragAmount = 0f

                                        // Mark gesture as consumed to prevent propagation
                                        change.consume()

                                        // Update displayed date
                                        viewModel.updateDateDisplayed(
                                            maxScrollOffsetTop,
                                            maxScrollOffsetBottom,
                                        )

                                        // Launch coroutine to scroll grid to the corresponding
                                        // targetItem
                                        coroutineScope.launch {
                                            val targetItem =
                                                viewModel.getTargetItemIndex(
                                                    maxScrollOffsetTop,
                                                    maxScrollOffsetBottom,
                                                )
                                            if (targetItem != null) {
                                                Log.d(
                                                    DateScrubberFeature.TAG,
                                                    "Scrolling grid to target item index: $targetItem.",
                                                )
                                                gridState.scrollToItem(targetItem)
                                            } else {
                                                Log.w(
                                                    DateScrubberFeature.TAG,
                                                    "Target item is null, cannot scroll grid.",
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            onDragEnd = { viewModel.onDragStopped() },
                            onDragStart = { viewModel.onDragStarted() },
                            onDragCancel = {
                                Log.d(
                                    DateScrubberFeature.TAG,
                                    "Drag canceled, scheduling hide cursor.",
                                )
                                viewModel.onDragStopped()
                            },
                        )
                    }
        ) {

            // Show the scrollable date scrubber cursor.
            ScrollableCursor(selectedDate = dateDisplayed)
        }
    }
}

/**
 * Composable that displays the date scrubber cursor in the side bar.
 *
 * The cursor contains:
 * - A rounded rectangular label showing the selected date (e.g., "July 2025") — only if available.
 * - A vertical cursor image to represent the drag handle.
 *
 * @param selectedDate The date string to be shown (e.g., "July 2025"). If null, the date label is
 *   not shown.
 * @param modifier Optional [Modifier] for layout customizations.
 */
@Composable
fun ScrollableCursor(selectedDate: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(77.dp), // cursor defines row height
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        // Show the date label if a date is available
        if (selectedDate != null) {
            Box(
                modifier =
                    Modifier.background(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = RoundedCornerShape(16.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val dateDescription =
                    stringResource(
                        R.string.photopicker_date_scrubber_current_date_desc,
                        selectedDate,
                    )

                Text(
                    text = selectedDate,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics {
                            contentDescription = dateDescription
                        },
                )
            }
        }

        // Cursor image
        Box(
            modifier = Modifier.size(width = 55.dp, height = 77.dp),
            contentAlignment = Alignment.Center,
        ) {
            val cursorDescription = stringResource(R.string.photopicker_date_scrubber_cursor_desc)

            Image(
                painter = painterResource(id = R.drawable.date_scrubber_cursor_background),
                contentDescription = cursorDescription,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.surfaceContainerLowest),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
            )
            Image(
                painter = painterResource(id = R.drawable.date_scrubber_cursor_arrows),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
            )
        }
    }
}
