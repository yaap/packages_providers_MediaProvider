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

package com.android.signature.ui.util

import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import com.android.signature.ui.create.PathState
import com.android.signature.ui.create.SignatureStrokeStyle
import kotlin.math.max
import kotlin.math.min
import android.graphics.Canvas as AndroidCanvas

/**
 * Creates a [Path] from a list of points.
 *
 * @param points The list of points to create the path from.
 * @return A [Path] connecting the points, or an empty path if the list is empty.
 */
@VisibleForTesting
fun createPathFromPoints(points: List<Offset>): Path =
    Path().apply {
        if (points.isNotEmpty()) {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                lineTo(points[i].x, points[i].y)
            }
        }
    }

/**
 * Calculates the total bounding box for a list of paths.
 *
 * @param paths The list of paths to calculate the bounds for.
 * @return A [Rect] representing the combined bounds, or Rect.Zero if empty.
 */
@VisibleForTesting
fun calculateBounds(paths: List<PathState>): Rect =
    paths.fold(Rect.Zero) { acc, pathState ->
        val pathBounds = pathState.path.getBounds()
        if (acc == Rect.Zero) {
            pathBounds
        } else {
            Rect(
                left = min(acc.left, pathBounds.left),
                top = min(acc.top, pathBounds.top),
                right = max(acc.right, pathBounds.right),
                bottom = max(acc.bottom, pathBounds.bottom),
            )
        }
    }

/**
 * Data class to hold transformation parameters.
 */
@VisibleForTesting
data class TransformParams(
    val scale: Float = 1f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
)

/**
 * Calculates the scale and translation required to fit and center the given bounds within the canvas.
 *
 * @param bounds The bounding box of the content.
 * @param canvasSize The size of the canvas.
 * @param padding The padding to apply around the content.
 * @return [TransformParams] containing scale and translation.
 */
@VisibleForTesting
fun calculateTransform(
    bounds: Rect,
    canvasSize: Size,
    padding: Float,
): TransformParams {
    if (bounds == Rect.Zero || bounds.width <= 0 || bounds.height <= 0) {
        return TransformParams()
    }

    val availableWidth = canvasSize.width - padding * 2
    val availableHeight = canvasSize.height - padding * 2

    val scaleX = if (bounds.width > 0) availableWidth / bounds.width else 1f
    val scaleY = if (bounds.height > 0) availableHeight / bounds.height else 1f

    // Only scale down if larger, keep aspect ratio.
    var scale = min(scaleX, scaleY)

    // If scale > 1 (small signature), don't scale up
    if (scale > 1f) scale = 1f

    val scaledWidth = bounds.width * scale
    val scaledHeight = bounds.height * scale

    val translateX = (canvasSize.width - scaledWidth) / 2f - bounds.left * scale
    val translateY = (canvasSize.height - scaledHeight) / 2f - bounds.top * scale

    return TransformParams(scale, translateX, translateY)
}

/**
 * Helper function to convert a list of Compose Path objects into an Android Bitmap.
 *
 * @param paths The list of paths to draw.
 * @param size The size of the bitmap.
 * @param density The screen density.
 * @param layoutDirection The layout direction.
 * @param padding for drawn signature.
 * @return A [Bitmap] containing the drawn paths.
 */
@VisibleForTesting
fun createBitmapFromPaths(
    paths: List<PathState>,
    size: IntSize,
    density: Density,
    layoutDirection: LayoutDirection,
    padding: Float,
): Bitmap {
    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val androidCanvas = AndroidCanvas(bitmap)
    val composeCanvas = Canvas(androidCanvas)

    // Apply the same orientation bounds transform
    val bounds = calculateBounds(paths)
    val transform = calculateTransform(bounds, size.toSize(), padding)

    CanvasDrawScope().draw(density, layoutDirection, composeCanvas, size.toSize()) {
        withTransform({
            translate(transform.translateX, transform.translateY)
            scale(scaleX = transform.scale, scaleY = transform.scale, pivot = Offset.Zero)
        }) {
            paths.forEach { pathState ->
                drawPath(
                    path = pathState.path,
                    color = pathState.color,
                    style =
                        Stroke(
                            width = pathState.strokeWidth,
                            cap = SignatureStrokeStyle.cap,
                            join = SignatureStrokeStyle.join,
                        ),
                )
            }
        }
    }
    return bitmap
}
