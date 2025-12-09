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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.android.photopicker.core.glide.ParcelableGlideLoadable
import com.android.photopicker.core.glide.Resolution
import com.android.photopicker.core.glide.loadMedia
import com.android.photopicker.data.model.CategoryType
import com.android.photopicker.data.model.Icon
import kotlin.collections.chunked
import kotlin.collections.forEachIndexed
import kotlin.collections.lastIndex

/** The radius to use for the corners of grid cells that are selected */
private val MEASUREMENT_SELECTED_CORNER_RADIUS_FOR_ALBUMS = 16.dp
/** The offset to apply to the selected icon to shift it over the corner of the image */
private val MEASUREMENT_BADGE_ICON_OFFSET = 8.dp

/**
 * Composable for creating a grid of icons, used to represent a category.
 *
 * This composable arranges a list of [ParcelableGlideLoadable] icons into a grid. The shape of
 * individual thumbnail in the grid (e.g., circular or rounded rectangle) is determined by the
 * [categoryType]. An optional [badgeIcon] can be overlaid on the grid.
 *
 * @param icons The list of icons to display in the grid.
 * @param modifier The modifier to be applied to the whole component.
 * @param categoryType The category type for which the icon grid is being created.
 * @param badgeIcon An optional icon to be displayed as a badge on top of the grid.
 * @param maxIcon The maximum number of icons to display. The list is padded to this size.
 * @param iconPerRow The number of icons to display in each row of the grid.
 */
@Composable
fun IconGrid(
    icons: List<ParcelableGlideLoadable>,
    modifier: Modifier,
    categoryType: CategoryType,
    badgeIcon: Icon?,
    badgeIconModifier: Modifier = Modifier,
    maxIcon: Int = 4,
    iconPerRow: Int = 2,
) {
    // The root is a Box to allow layering the badge on top.
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Pad the list to ensure we required icons per row
                val paddedIcons = (icons + List(maxIcon) { null }).take(maxIcon)
                val iconsInRow = paddedIcons.chunked(iconPerRow)

                val clipShape =
                    when (categoryType) {
                        CategoryType.PEOPLE_AND_PETS,
                        CategoryType.APP_FOLDERS -> CircleShape
                        else -> RoundedCornerShape(MEASUREMENT_SELECTED_CORNER_RADIUS_FOR_ALBUMS)
                    }

                val iconGridModifier =
                    Modifier.fillMaxSize()
                        .clip(clipShape)
                        .background(MaterialTheme.colorScheme.surface)

                iconsInRow.forEachIndexed { rowIndex, rowItem ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowItem.forEachIndexed { colIndex, icon ->
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f)) {
                                if (icons.isNotEmpty() && icon is ParcelableGlideLoadable) {
                                    CategoryIcon(icon, iconGridModifier)
                                } else {
                                    if (
                                        icons.isEmpty() &&
                                            !(rowIndex == iconsInRow.lastIndex &&
                                                colIndex == rowItem.lastIndex)
                                    ) {
                                        CategoryIconPlaceholder(iconGridModifier)
                                    } else {
                                        CategoryIconPlaceholder(iconGridModifier, false)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        badgeIcon?.let {
            BadgeOverlay(
                icon = it,
                // This modifier aligns the badge inside the parent Box.
                modifier = badgeIconModifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * A composable that renders a placeholder for an icon in the [IconGrid].
 *
 * This is used to ensure the grid structure is maintained even if there are not enough icons to
 * fill the grid. The placeholder is only shown if [showPlaceholder] is true.
 *
 * @param modifier The modifier to be applied to the placeholder.
 * @param showPlaceholder Whether to display the placeholder. Defaults to true.
 */
@Composable
fun CategoryIconPlaceholder(modifier: Modifier, showPlaceholder: Boolean = true) {
    Box(
        modifier =
            when (showPlaceholder) {
                true -> modifier
                false -> Modifier
            }
    )
}

/**
 * A composable that displays a single icon within the [IconGrid].
 *
 * This uses [loadMedia] to load and display the provided [icon].
 *
 * @param icon The [ParcelableGlideLoadable] icon to display.
 * @param modifier The modifier to be applied to the icon.
 */
@Composable
fun CategoryIcon(icon: ParcelableGlideLoadable, modifier: Modifier) {
    loadMedia(media = icon, resolution = Resolution.THUMBNAIL, modifier = modifier)
}
