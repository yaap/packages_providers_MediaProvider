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

package com.android.photopicker.extensions

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.geometry.Offset

/**
 * Gets the index of the item in the [LazyGridState] that is currently under the given hit point.
 *
 * @param hitPoint The [Offset] (x, y coordinates) of the touch/drag point relative to the grid's
 *   bounds.
 * @return The index of the item at the [hitPoint], or `null` if no item is found at that position
 *   (e.g., if the point is in a gap between items or outside the content area).
 */
fun LazyGridState.itemIndexAtPosition(hitPoint: Offset): Int? {
    return layoutInfo.visibleItemsInfo
        .firstOrNull { // Find the first visible item whose bounds contain the hitPoint.
            hitPoint.y.toInt() in it.offset.y..(it.offset.y + it.size.height) &&
                hitPoint.x.toInt() in it.offset.x..(it.offset.x + it.size.width)
        }
        ?.index // Return the index of the found item.
}

/**
 * Gets the index of the item that was hit by the drag, or the index of the last item if the drag
 * has moved past the last visible item in the grid.
 *
 * This is useful for ensuring that selection can extend to the very last item even if the drag
 * pointer technically moves slightly beyond its bounds due to auto-scrolling or fast gestures.
 *
 * @param hitPoint The [Offset] (x, y coordinates) where the drag hit the grid, relative to the
 *   grid's bounds.
 * @return The index of the item hit by the drag. If the drag is past the last item, it returns the
 *   index of the last item. Returns `null` if the hit point is not over any item and not past the
 *   last item (e.g., above the first item).
 */
fun LazyGridState.getItemPosition(hitPoint: Offset): Int? {
    return itemIndexAtPosition(hitPoint) // First, try to find an item directly at the hit point.
        // If no item is directly hit, check if the drag is past the last item.
        ?: if (isPastLastItem(hitPoint)) layoutInfo.totalItemsCount - 1 else null
}

/**
 * Determines if the drag gesture's current position is vertically beyond the last item in the grid.
 *
 * This helps in scenarios like auto-scrolling where the pointer might move beyond the rendered
 * bounds of the last item, ensuring that the selection logic can still correctly identify that the
 * user intends to select up to the end of the list.
 *
 * @param hitPoint The [Offset] (x, y coordinates) of the drag point relative to the grid's bounds.
 * @return `true` if the [hitPoint]'s y-coordinate is below the y-offset of the last item (and that
 *   last item is indeed the final item in the total list), `false` otherwise.
 */
private fun LazyGridState.isPastLastItem(hitPoint: Offset): Boolean {
    // Get the layout information for the last visible item.
    val lastItem =
        layoutInfo.visibleItemsInfo.lastOrNull()?.takeIf {
            // Ensure this visible item is actually the last item in the entire dataset.
            it.index == layoutInfo.totalItemsCount - 1
        } ?: return false // Return false if no visible items or last visible isn't overall last.

    // Determine if the hit point's y-coordinate is beyond the start of the last item.
    // This implies the drag has moved to or past the vertical position of the last item.
    return hitPoint.y > lastItem.offset.y
}
