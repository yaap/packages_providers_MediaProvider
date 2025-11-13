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
 * Creates and remembers a [GridDragSelectState] instance.
 *
 * This composable handles the state management for drag selection within a lazy grid. It integrates
 * with the provided [LazyGridState] and [Selection] controller.
 *
 * @param lazyGridState The state object of the `LazyVerticalGrid` or `LazyHorizontalGrid`. Defaults
 *   to a new remembered state.
 * @param selection The selection controller responsible for managing selected items. Defaults to
 *   the ambient [LocalSelection] controller.
 * @return A remembered [GridDragSelectState] instance, keyed to [lazyGridState] and [selection].
 */
@Composable
public fun rememberGridDragSelectState(
    lazyGridState: LazyGridState = rememberLazyGridState(),
    selection: Selection<Media> = LocalSelection.current,
): GridDragSelectState {
    return remember(lazyGridState, selection) {
        GridDragSelectState(
            gridState = lazyGridState,
            selection = selection,
            dragState = DragState.create(),
        )
    }
}

/**
 * Holds the state for drag-to-select functionality within a composable.
 *
 * This state class manages the drag gesture lifecycle (start, update, stop) and holds the relevant
 * state for the current Drag gesture.
 *
 * Use [rememberGridDragSelectState] to create and remember an instance of this class within your
 * composable.
 *
 * @property gridState The state object of the `LazyVerticalGrid` or `LazyHorizontalGrid` being
 *   used.
 * @property selection The selection controller responsible for managing selected items.
 * @property dragState The internal state representing the current drag operation (initial and
 *   current index).
 */
class GridDragSelectState(
    val gridState: LazyGridState,
    val selection: Selection<Media>,
    var dragState: DragState,
) {

    /** The current auto-scroll speed, typically non-zero when dragging near grid edges. */
    val autoScrollSpeed = mutableStateOf(0f)
    /** The dominant direction of the current drag (e.g., Up, Down, Left, Right). */
    val direction = mutableStateOf(DragDirection.UNSET)

    /**
     * Executes the provided [block] within the [DragSelectScope] if a drag operation
     * (`dragState.isDragging`) is currently active.
     *
     * This function is intended to be called repeatedly while the user's pointer is moving during a
     * drag gesture (e.g., within a `pointerInput` modifier's drag event handler).
     *
     * The [block] receives a [DragSelectScope] receiver, providing access to the current drag state
     * ([dragState]), grid state ([gridState]), selection controller ([selection]), and allows
     * updating the drag position via [DragSelectScope.updateDrag].
     *
     * @param block The lambda to execute within the [GridDragSelectState].
     */
    fun whenDragging(block: GridDragSelectState.() -> Unit) {
        if (dragState.isDragging) {
            block(this)
        }
    }

    /**
     * Updates the current item index being dragged over. This modifies the [dragState]'s `current`
     * value. This method is typically called from within the [whenDragging] block via
     * [DragSelectScope.updateDrag].
     *
     * @param current The index of the item currently under the drag pointer.
     */
    fun updateDrag(current: Int) {
        dragState = dragState.copy(current = current)
    }

    /**
     * Starts a new drag operation by setting the [dragState] with the initial and current index.
     * Executes the provided [block] immediately after initializing the drag state.
     *
     * @param index The initial index where the drag gesture started (e.g., the item first pressed).
     * @param block A suspend lambda to execute immediately after starting the drag. This is
     *   typically used for applying the initial selection change based on the starting item (e.g.,
     *   selecting the first item if it wasn't selected).
     */
    fun startDrag(index: Int, block: GridDragSelectState.() -> Unit) {
        // Initialize drag state: initial and current index are the same at the start.
        dragState = DragState(initial = index, current = index)
        // Execute the provided block, e.g., for initial selection.
        block()
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
