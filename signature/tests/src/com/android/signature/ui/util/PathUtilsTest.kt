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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.signature.ui.create.PathState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PathUtilsTest {
    @Test
    fun createPathFromPoints_emptyList_returnsEmptyPath() {
        val path = createPathFromPoints(emptyList())
        assertTrue(path.isEmpty)
    }

    @Test
    fun createPathFromPoints_validPoints_returnsPath() {
        val points = listOf(Offset(0f, 0f), Offset(10f, 10f))
        val path = createPathFromPoints(points)
        assertFalse(path.isEmpty)
        // We can't easily verify path content without mocking or complex checks,
        // but isEmpty check confirms something was added.
    }

    @Test
    fun calculateBounds_emptyList_returnsRectZero() {
        val bounds = calculateBounds(emptyList())
        assertEquals(Rect.Zero, bounds)
    }

    @Test
    fun calculateBounds_multiplePaths_returnsCombinedBounds() {
        // Path 1 bounds should be Rect(0, 0, 10, 10)
        val path1 =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(10f, 10f)
            }

        // Path 2 bounds should be Rect(20, 20, 30, 40)
        val path2 =
            Path().apply {
                moveTo(20f, 20f)
                lineTo(30f, 40f)
            }

        val paths =
            listOf(
                PathState(path1, Color.Black, 5f),
                PathState(path2, Color.Black, 5f),
            )

        val combinedBounds = calculateBounds(paths)

        // Combined bounds should be Rect(0, 0, 30, 40)
        assertEquals(0f, combinedBounds.left, 0.001f)
        assertEquals(0f, combinedBounds.top, 0.001f)
        assertEquals(30f, combinedBounds.right, 0.001f)
        assertEquals(40f, combinedBounds.bottom, 0.001f)
    }

    @Test
    fun calculateTransform_emptyBounds_returnsIdentity() {
        val transform = calculateTransform(Rect.Zero, Size(100f, 100f), 10f)
        assertEquals(1f, transform.scale, 0.001f)
        assertEquals(0f, transform.translateX, 0.001f)
        assertEquals(0f, transform.translateY, 0.001f)
    }

    @Test
    fun calculateTransform_fitsWithinCanvas() {
        val bounds = Rect(0f, 0f, 200f, 200f)
        val canvasSize = Size(100f, 100f)
        val padding = 0f

        val transform = calculateTransform(bounds, canvasSize, padding)

        // Should scale down to 0.5 (200 -> 100)
        assertEquals(0.5f, transform.scale, 0.001f)
        // Should be centered (0 translation as it fits exactly)
        assertEquals(0f, transform.translateX, 0.001f)
        assertEquals(0f, transform.translateY, 0.001f)
    }

    @Test
    fun calculateTransform_centersSmallContent() {
        val bounds = Rect(0f, 0f, 50f, 50f)
        val canvasSize = Size(100f, 100f)
        val padding = 0f

        val transform = calculateTransform(bounds, canvasSize, padding)

        // Should NOT scale up (scale = 1f)
        assertEquals(1f, transform.scale, 0.001f)
        // Should translate to center: (100 - 50) / 2 = 25
        assertEquals(25f, transform.translateX, 0.001f)
        assertEquals(25f, transform.translateY, 0.001f)
    }

    @Test
    fun calculateTransform_appliesPadding() {
        val bounds = Rect(0f, 0f, 100f, 100f)
        val canvasSize = Size(100f, 100f)
        val padding = 10f

        val transform = calculateTransform(bounds, canvasSize, padding)

        // Available size: 80x80. Bounds: 100x100. Scale: 0.8
        assertEquals(0.8f, transform.scale, 0.001f)

        // Scaled size: 80x80.
        // Translate: (100 - 80) / 2 = 10
        assertEquals(10f, transform.translateX, 0.001f)
        assertEquals(10f, transform.translateY, 0.001f)
    }

    @Test
    fun createBitmapFromPaths_createsValidBitmap() {
        val path =
            Path().apply {
                moveTo(0f, 0f)
                lineTo(10f, 10f)
            }
        val pathState = PathState(path, Color.Black, 5f)
        val paths = listOf(pathState)
        val size = IntSize(100, 100)
        val density = Density(1f)
        val layoutDirection = LayoutDirection.Ltr

        val bitmap = createBitmapFromPaths(paths, size, density, layoutDirection, 0f)

        assertNotNull(bitmap)
        assertEquals(100, bitmap.width)
        assertEquals(100, bitmap.height)
    }

    @Test
    fun calculateTransform_landscapeBoundsToPortraitCanvas_scalesAndCenters() {
        // Wide bounds (e.g., drawn in landscape)
        val bounds = Rect(0f, 0f, 400f, 100f)

        // Tall canvas (e.g., rotated to portrait)
        val canvasSize = Size(200f, 600f)
        val padding = 0f

        val transform = calculateTransform(bounds, canvasSize, padding)

        // Scale must be based on the width to fit 400 into 200: Scale = 0.5
        assertEquals(0.5f, transform.scale, 0.001f)

        // Scaled height is 100 * 0.5 = 50.
        // It should be vertically centered: (600 - 50) / 2 = 275
        assertEquals(0f, transform.translateX, 0.001f)
        assertEquals(275f, transform.translateY, 0.001f)
    }

    @Test
    fun calculateTransform_portraitBoundsToLandscapeCanvas_scalesAndCenters() {
        // Tall bounds (e.g., drawn in portrait)
        val bounds = Rect(0f, 0f, 100f, 400f)

        // Wide canvas (e.g., rotated to landscape)
        val canvasSize = Size(600f, 200f)
        val padding = 0f

        val transform = calculateTransform(bounds, canvasSize, padding)

        // Scale must be based on the height to fit 400 into 200: Scale = 0.5
        assertEquals(0.5f, transform.scale, 0.001f)

        // Scaled width is 100 * 0.5 = 50.
        // It should be horizontally centered: (600 - 50) / 2 = 275
        assertEquals(275f, transform.translateX, 0.001f)
        assertEquals(0f, transform.translateY, 0.001f)
    }
}
