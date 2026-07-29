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

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.model.Media

/**
 * Creates and remembers a [MediaGridState] for a media grid.
 *
 * This function manages the state of the media grid, including its scroll position, selection, and
 * drag-to-select gestures.
 *
 * @param lazyGridState The [LazyGridState] for the underlying grid composable.
 * @param selection The [Selection] model to use for tracking selected items.
 * @return A remembered [MediaGridState] instance.
 */
@Composable
public fun rememberMediaGridState(
    lazyGridState: LazyGridState = rememberLazyGridState(),
    selection: Selection<Media> = LocalSelection.current,
): MediaGridState {
    return remember(lazyGridState, selection) {
        MediaGridState(
            gridState = lazyGridState,
            selection = selection,
            dragState = DragState.create(),
        )
    }
}

/**
 * A state holder for the media grid, which wraps the [LazyGridState] for the underlying grid
 * composable and manages drag-to-select operations.
 *
 * @param gridState The state of the underlying [LazyGridState].
 * @param selection The selection for the current session.
 * @param dragState The state of the drag-to-select operation.
 */
class MediaGridState(
    val gridState: LazyGridState,
    val selection: Selection<Media>,
    var dragState: DragState,
) {

    /** The current auto-scroll speed, typically non-zero when dragging near grid edges. */
    val autoScrollSpeed = mutableStateOf(0f)
    /** The dominant direction of the current drag (e.g., Up, Down, Left, Right). */
    val direction = mutableStateOf(DragDirection.UNSET)

    /**
     * Executes the provided [block] within the [MediaGridState] if a drag operation
     * (`dragState.isDragging`) is currently active.
     *
     * This function is intended to be called repeatedly while the user's pointer is moving during a
     * drag gesture (e.g., within a `pointerInput` modifier's drag event handler).
     *
     * The [block] receives a [MediaGridState] receiver, providing access to the current drag state
     * ([dragState]), grid state ([gridState]), selection controller ([selection]), and allows
     * updating the drag position via [updateDrag].
     *
     * @param block The lambda to execute within the [MediaGridState].
     */
    fun whenDragging(block: MediaGridState.() -> Unit) {
        if (dragState.isDragging) {
            block(this)
        }
    }

    /**
     * Updates the current item index being dragged over. This modifies the [dragState]'s `current`
     * value. This method is typically called from within the [whenDragging] block.
     *
     * @param current The index of the item currently under the drag pointer.
     */
    fun updateDrag(current: Int) {
        dragState = dragState.copy(current = current)
    }

    /**
     * Starts a new drag operation by setting the [dragState] with the initial and current index.
     *
     * @param index The initial index where the drag gesture started (e.g., the item first pressed).
     */
    fun startDrag(index: Int) {
        // Initialize drag state: initial and current index are the same at the start.
        dragState = DragState(initial = index, current = index)
    }

    /**
     * Stops the current drag operation. Resets the [dragState] to indicate no active drag by
     * setting `initial = DragState.None`, and resets the [autoScrollSpeed] to 0f.
     */
    fun stopDrag() {
        // Reset drag state to indicate no drag is in progress.
        dragState = DragState.create()
        // Reset auto-scroll speed.
        autoScrollSpeed.value = 0f
    }
}
