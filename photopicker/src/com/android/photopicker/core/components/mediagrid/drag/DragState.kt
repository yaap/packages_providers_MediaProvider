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

/** Represents the index-direction of a drag gesture. */
enum class DragDirection {
    NEGATIVE,
    POSITIVE,
    UNSET,
}

/** Represents the possible orientations for scrolling. */
enum class ScrollOrientation {
    HORIZONTAL,
    VERTICAL,
}

/**
 * Represents the state of a drag gesture within the media grid.
 *
 * Tracks the starting position and current position of a drag event. Useful for determining drag
 * direction and calculating offsets.
 *
 * @property initial The initial index where the drag started. Defaults to [None] if no drag is in
 *   progress.
 * @property current The current index of the item being dragged over. Defaults to [None] if no drag
 *   is in progress.
 */
data class DragState(val initial: Int, val current: Int) {

    /**
     * Returns `true` if a drag operation is currently in progress (i.e., initial and current
     * positions are set).
     */
    val isDragging: Boolean
        get() = initial != None && current != None

    companion object {

        /** Constant representing an unset or invalid drag position index. */
        const val None = -1

        /**
         * Factory method to create a new [DragState] instance.
         *
         * @param initial The initial drag position index, defaults to [None].
         * @param current The current drag position index, defaults to [None].
         * @return A new [DragState] instance.
         */
        fun create(initial: Int = None, current: Int = None): DragState =
            DragState(initial, current)
    }
}
