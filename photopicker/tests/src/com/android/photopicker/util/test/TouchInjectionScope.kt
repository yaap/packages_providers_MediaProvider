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

package com.android.photopicker.util.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import kotlin.math.abs

/**
 * Applies drag action of an amount of [totalOffset]. Note, we drag in small increments of 10f, or
 * otherwise won't be detected by `detectDragGesturesAfterLongPress`.
 */
fun TouchInjectionScope.dragInIncrements(totalOffset: Float, vertical: Boolean = true) {
    var currentOffset = 0f
    val positiveOffset = totalOffset > 0
    while (abs(currentOffset) < abs(totalOffset)) {
        val increment = if (positiveOffset) 10f else -10f
        currentOffset += increment
        moveBy(
            delta =
                Offset(x = if (vertical) 0f else increment, y = if (vertical) increment else 0f),
            delayMillis = 10L,
        )
    }
}
