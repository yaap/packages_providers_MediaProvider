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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** Default values for [Modifier.onGridDragSelect]. */
public object GridDragSelectDefaults {

    /** Default value for [HapticFeedback]. */
    public val hapticsFeedback: HapticFeedback
        @Composable get() = LocalHapticFeedback.current

    /** Default value to determine when to start auto-scrolling. */
    public val autoScrollThreshold: Float
        @Composable get() = with(LocalDensity.current) { DEFAULT_THRESHOLD_DP.dp.toPx() }

    private const val DEFAULT_THRESHOLD_DP = 40
}
