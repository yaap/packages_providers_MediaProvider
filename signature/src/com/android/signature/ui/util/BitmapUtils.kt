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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.VisibleForTesting

private const val ERROR_EMPTY_TEXT = "Text cannot be empty"

/**
 * Helper function to create a Bitmap from text with a specific typeface.
 *
 * @param text The text to draw.
 * @param typeface The typeface to use.
 * @param textSize The size of the text.
 * @return A [Bitmap] containing the drawn text.
 * @throws IllegalArgumentException if the text is empty.
 */
@VisibleForTesting
fun createBitmapFromText(
    text: String,
    typeface: Typeface,
    textSize: Float,
): Bitmap {
    require(text.isNotEmpty()) { ERROR_EMPTY_TEXT }
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            color = Color.BLACK
            this.typeface = typeface
            textAlign = Paint.Align.LEFT
        }
    val baseline = -paint.ascent() // ascent() is negative
    val width = (paint.measureText(text) + 0.5f).toInt().coerceAtLeast(1)
    val height = (baseline + paint.descent() + 0.5f).toInt().coerceAtLeast(1)
    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(image)
    canvas.drawText(text, 0f, baseline, paint)
    return image
}

/**
 * Scales down a bitmap if its width or height exceeds the maximum dimension.
 *
 * @param bitmap The bitmap to scale down.
 * @param maxDimension The maximum allowed dimension (width or height).
 * @return A new scaled [Bitmap] or the original [Bitmap] if it's already within the limits.
 */
@VisibleForTesting
fun scaleDownBitmap(
    bitmap: Bitmap,
    maxDimension: Float,
): Bitmap {
    val maxDim = maxOf(bitmap.width, bitmap.height)
    if (maxDim <= maxDimension) {
        return bitmap
    }
    val scale = maxDimension / maxDim
    val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}
