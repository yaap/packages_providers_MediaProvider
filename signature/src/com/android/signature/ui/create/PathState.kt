/*
 * Copyright 2026 The Android Open Source Project
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

package com.android.signature.ui.create

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import java.util.Objects

/**
 * Class representing the state of a drawn path on the canvas.
 *
 * @property path The [Path] object representing the drawing.
 * @property color The color of the path.
 * @property strokeWidth The width of the stroke.
 * @property points The list of points that make up the path, used for serialization.
 */
class PathState(
    val path: Path,
    val color: Color,
    val strokeWidth: Float,
    val points: List<Offset> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PathState

        if (path != other.path) return false
        if (color != other.color) return false
        if (strokeWidth != other.strokeWidth) return false
        if (points != other.points) return false

        return true
    }

    override fun hashCode(): Int {
        return Objects.hash(path, color, strokeWidth, points)
    }

    override fun toString(): String {
        return "PathState(path=$path, color=$color, strokeWidth=$strokeWidth, points=$points)"
    }
}
