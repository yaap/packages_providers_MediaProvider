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

package com.android.photopicker.features.categorygrid.categoryIcon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.android.photopicker.core.glide.Resolution
import com.android.photopicker.core.glide.loadMedia
import com.android.photopicker.data.model.GlideIcon
import com.android.photopicker.data.model.Icon
import com.android.photopicker.data.model.VectorIcon
import com.android.photopicker.data.model.VectorIconBadge

private val MEASUREMENT_OVERLAY_BADGE_CONTAINER = 40.dp
private val MEASUREMENT_BADGE_VECTOR_ICON_SIZE = 24.dp

/**
 * A composable that displays a badge icon overlay typically over a media grid item.
 *
 * The badge can be either a [GlideIcon], which is loaded via [loadMedia], or a [VectorIcon], which
 * is displayed using the [Icon] composable. The positioning of the badge is controlled by the
 * incoming [modifier].
 *
 * @param icon The [Icon] to display.
 * @param modifier The modifier to be applied to the badge container. This should handle
 *   positioning.
 * @param contentDescription The content description for the badge icon.
 */
@Composable
fun BadgeOverlay(icon: Icon, modifier: Modifier = Modifier, contentDescription: String? = null) {
    // The modifier passed in from the caller will position it.
    val badgeModifier = modifier.size(MEASUREMENT_OVERLAY_BADGE_CONTAINER)
    when (icon) {
        is GlideIcon ->
            loadMedia(
                media = icon,
                resolution = Resolution.THUMBNAIL,
                modifier = badgeModifier,
                contentDescription = contentDescription,
            )

        is VectorIcon ->
            VectorIconBadge(
                icon = icon,
                boxModifier =
                    badgeModifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                iconModifier = Modifier.size(MEASUREMENT_BADGE_VECTOR_ICON_SIZE),
            )
    }
}
