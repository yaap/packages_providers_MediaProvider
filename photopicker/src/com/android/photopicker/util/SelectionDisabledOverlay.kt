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

package com.android.photopicker.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The alpha value for the gradient overlay for disabled media items */
const val MEASUREMENT_DISABLED_GRADIENT_ALPHA = 0.1f

/** The default size for the error icon used for disabled media items */
private val MEASUREMENT_DISABLED_ICON_SIZE = 18.dp

/** The default padding for the error icon used for disabled media items */
private val MEASUREMENT_DISABLED_ICON_PADDING = 8.dp

/**
 * Displays an overlay of an error icon with a scrim for media items that are disabled.
 *
 * @param modifier The [Modifier] to be applied to the overlay
 * @param iconSize The size of the error icon
 * @param iconPadding The padding for the error icon
 * @param contentDescription The accessibility text for the icon
 */
@Composable
fun SelectionDisabledOverlay(
    modifier: Modifier = Modifier,
    iconSize: Dp = MEASUREMENT_DISABLED_ICON_SIZE,
    iconPadding: Dp = MEASUREMENT_DISABLED_ICON_PADDING,
    contentDescription: String? = null,
) {
    Box(modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(iconPadding).size(iconSize),
        )
    }
}
