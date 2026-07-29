/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.photopicker.features.categorygrid

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.components.EmptyState
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.components.defaultBuildPersonMediaSetItem
import com.android.photopicker.core.components.mediaGrid
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.glide.Resolution
import com.android.photopicker.core.glide.loadMedia
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.data.model.CategoryType
import com.android.photopicker.data.model.Group
import com.android.photopicker.extensions.navigateToMediaSetContentGrid
import com.android.photopicker.features.categorygrid.categoryIcon.BadgeOverlay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The number of grid cells per row for Phone / narrow layouts */
private val CELLS_PER_ROW_FOR_PEOPLE_MEDIASET_GRID = 3
private val CELLS_PER_ROW_FOR_MEDIASET_GRID = 2
/** The number of grid cells per row for Tablet / expanded layouts */
private val CELLS_PER_ROW_EXPANDED_FOR_PEOPLE_MEDIASET_GRID = 4
private val CELLS_PER_ROW_EXPANDED_FOR_MEDIASET_GRID = 3
/** The amount of padding to use around each cell in the mediaset grid. */
private val MEASUREMENT_HORIZONTAL_CELL_SPACING_FOR_PEOPLE_MEDIASET_GRID = 1.dp
private val MEASUREMENT_HORIZONTAL_CELL_SPACING_MEDIASET_GRID = 16.dp
/** The radius to use for the corners of grid cells that are selected */
val MEASUREMENT_SELECTED_CORNER_RADIUS_FOR_MEDIASETS = 16.dp
/** Size of the spacer between the album icon and the album display label */
val MEASUREMENT_DEFAULT_MEDIASET_LABEL_SPACER_SIZE = 12.dp
/** The offset to apply to the selected icon to shift it over the corner of the image */
private val MEASUREMENT_BADGE_ICON_OFFSET = 8.dp
/** Additional padding between album items */
val MEASUREMENT_DEFAULT_MEDIASET_BOTTOM_PADDING = 16.dp

private fun getCellsPerRow(categoryType: CategoryType, isExpanded: Boolean): Int {
    // Assigns the pair of (normal, expanded) cell counts per row based on the category type.
    val cells =
        when (categoryType) {
            CategoryType.PEOPLE_AND_PETS -> {
                Pair(
                    CELLS_PER_ROW_FOR_PEOPLE_MEDIASET_GRID,
                    CELLS_PER_ROW_EXPANDED_FOR_PEOPLE_MEDIASET_GRID,
                )
            }
            else -> {
                Pair(CELLS_PER_ROW_FOR_MEDIASET_GRID, CELLS_PER_ROW_EXPANDED_FOR_MEDIASET_GRID)
            }
        }

    return if (isExpanded) cells.second else cells.first
}

private fun getGridCellPadding(categoryType: CategoryType): Dp {
    return when (categoryType) {
        CategoryType.PEOPLE_AND_PETS -> MEASUREMENT_HORIZONTAL_CELL_SPACING_FOR_PEOPLE_MEDIASET_GRID
        else -> MEASUREMENT_HORIZONTAL_CELL_SPACING_MEDIASET_GRID
    }
}

/**
 * Primary composable for drawing the main MediasetGrid on [PhotopickerDestinations.MEDIASET_GRID]
 *
 * @param viewModel - A viewModel override for the composable. Normally, this is fetched via hilt
 *   from the backstack entry by using obtainViewModel()
 */
@Composable
fun MediaSetGrid(
    flow: StateFlow<Group.Category?>,
    viewModel: CategoryGridViewModel = obtainViewModel(),
) {
    val categoryState by flow.collectAsStateWithLifecycle(initialValue = null)
    val category = categoryState
    when (category) {
        null -> {}
        else -> {
            val items = remember(category) { viewModel.getMediaSets(category) }
            val navController = LocalNavController.current
            val scope = rememberCoroutineScope()
            val events = LocalEvents.current
            val configuration = LocalPhotopickerConfiguration.current

            val isEmbedded =
                LocalPhotopickerConfiguration.current.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
            val host = LocalEmbeddedState.current?.host
            // Use the expanded layout any time the Width is Medium or larger.
            val isExpandedScreen: Boolean =
                when (LocalWindowSizeClass.current.widthSizeClass) {
                    WindowWidthSizeClass.Medium -> true
                    WindowWidthSizeClass.Expanded -> true
                    else -> false
                }
            val mediaSetItems = items.collectAsLazyPagingItems()

            val cellsPerRow = getCellsPerRow(category.categoryType, isExpandedScreen)
            val gridCellPadding = getGridCellPadding(category.categoryType)
            val contentPadding = PaddingValues(gridCellPadding)
            val badgeIconModifier = Modifier.padding(MEASUREMENT_BADGE_ICON_OFFSET)

            Column(modifier = Modifier.fillMaxSize()) {
                val isEmptyAndNoMorePages =
                    mediaSetItems.itemCount == 0 &&
                        mediaSetItems.loadState.source.append is LoadState.NotLoading &&
                        mediaSetItems.loadState.source.append.endOfPaginationReached
                when {
                    isEmptyAndNoMorePages -> {
                        val localConfig = LocalConfiguration.current
                        val emptyStatePadding =
                            remember(localConfig) { (localConfig.screenHeightDp * .20).dp }
                        val isVideoOnlyMimeType =
                            LocalPhotopickerConfiguration.current.hasOnlyVideoMimeTypes()
                        val (title, body, icon) =
                            getEmptyStateContentForMediaset(
                                category.categoryType,
                                isVideoOnlyMimeType,
                            )
                        EmptyState(
                            modifier =
                                if (SdkLevel.isAtLeastU() && isEmbedded && host != null) {
                                    // In embedded no need to give extra top padding to make empty
                                    // state title and body clearly visible in collapse mode (small
                                    // view)
                                    Modifier.fillMaxWidth()
                                } else {
                                    // Provide 20% of screen height as empty space above
                                    Modifier.fillMaxWidth().padding(top = emptyStatePadding)
                                },
                            icon = icon,
                            title = title,
                            body = body,
                        )
                        LaunchedEffect(Unit) {
                            events.dispatch(
                                Event.LogPhotopickerUIEvent(
                                    FeatureToken.CATEGORY_GRID.token,
                                    configuration.sessionId,
                                    configuration.callingPackageUid ?: -1,
                                    Telemetry.UiEvent.UI_LOADED_EMPTY_STATE,
                                )
                            )
                        }
                    }

                    else -> {
                        // Invoke the composable for MediasetGrid. OnClick uses the navController to
                        // navigate to the mediaset content for the mediaset that is selected by the
                        // user.
                        mediaGrid(
                            modifier = Modifier.fillMaxSize(),
                            items = mediaSetItems,
                            onItemClick = { item ->
                                if (item is MediaGridItem.PersonMediaSetItem) {
                                    scope.launch {
                                        events.dispatch(
                                            Event.LogPhotopickerUIEvent(
                                                FeatureToken.CATEGORY_GRID.token,
                                                configuration.sessionId,
                                                configuration.callingPackageUid ?: -1,
                                                Telemetry.UiEvent.CATEGORY_PEOPLEPET_OPEN,
                                            )
                                        )
                                    }
                                    navController.navigateToMediaSetContentGrid(
                                        mediaSet = item.mediaSet
                                    )
                                } else if (item is MediaGridItem.MediaSetItem) {
                                    scope.launch {
                                        events.dispatch(
                                            Event.LogPhotopickerUIEvent(
                                                FeatureToken.CATEGORY_GRID.token,
                                                configuration.sessionId,
                                                configuration.callingPackageUid ?: -1,
                                                Telemetry.UiEvent.PICKER_CATEGORIES_INTERACTION,
                                            )
                                        )
                                    }
                                    navController.navigateToMediaSetContentGrid(
                                        mediaSet = item.mediaSet
                                    )
                                }
                            },
                            isExpandedScreen = isExpandedScreen,
                            initialColumns = cellsPerRow,
                            selection = emptySet(),
                            gridCellPadding = gridCellPadding,
                            contentPadding = contentPadding,
                            contentItemFactory = { item, isSelected, onClick, dateFormat ->
                                when (item) {
                                    is MediaGridItem.MediaSetItem ->
                                        mediaSetContentFactory(item, onClick, badgeIconModifier)
                                    is MediaGridItem.PersonMediaSetItem ->
                                        defaultBuildPersonMediaSetItem(item, onClick)
                                    else -> {}
                                }
                            },
                        )
                        LaunchedEffect(Unit) {
                            // Dispatch UI event to log loading of category contents
                            events.dispatch(
                                Event.LogPhotopickerUIEvent(
                                    FeatureToken.CATEGORY_GRID.token,
                                    configuration.sessionId,
                                    configuration.callingPackageUid ?: -1,
                                    Telemetry.UiEvent.UI_LOADED_MEDIA_SETS,
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Return a generic content for the empty state.
 *
 * @return a [Triple] that contains the [Title, Body, Icon] for the empty state.
 */
@Composable
private fun getEmptyStateContentForMediaset(
    categoryType: CategoryType,
    isVideoOnlyMime: Boolean,
): Triple<String, String, ImageVector> {
    return if (categoryType == CategoryType.PEOPLE_AND_PETS) {
        Triple(
            stringResource(R.string.photopicker_people_category_empty_state_title),
            stringResource(R.string.photopicker_people_category_empty_state_body),
            Icons.Outlined.Group,
        )
    } else {
        Triple(
            when {
                isVideoOnlyMime -> stringResource(R.string.photopicker_videos_empty_state_title)
                else -> stringResource(R.string.photopicker_photos_empty_state_title)
            },
            stringResource(R.string.photopicker_photos_empty_state_body),
            Icons.Outlined.Group,
        )
    }
}

@VisibleForTesting
@Composable
fun mediaSetContentFactory(
    item: MediaGridItem.MediaSetItem,
    onClick: ((item: MediaGridItem) -> Unit)?,
    badgeIconModifier: Modifier = Modifier,
) {
    Column(
        // Apply semantics for the click handlers
        Modifier.semantics(mergeDescendants = true) {
                onClick(
                    action = {
                        onClick?.invoke(item)
                        /* eventHandled= */ true
                    }
                )
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick?.invoke(item) }) }
            .padding(bottom = MEASUREMENT_DEFAULT_MEDIASET_BOTTOM_PADDING)
    ) {
        with(item.mediaSet) {
            Box {
                val imageModifier =
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(MEASUREMENT_SELECTED_CORNER_RADIUS_FOR_MEDIASETS))
                        .aspectRatio(1f)

                loadMedia(media = icon, resolution = Resolution.THUMBNAIL, modifier = imageModifier)

                // We do not need to badge media sets in "cloud albums as category" collection
                if (CategoryType.USER_ALBUMS.key != item.mediaSet.parentCategoryType) {
                    badge?.let {
                        BadgeOverlay(
                            icon = it,
                            modifier = badgeIconModifier.align(Alignment.BottomEnd),
                        )
                    }
                }
            }
            Spacer(Modifier.size(MEASUREMENT_DEFAULT_MEDIASET_LABEL_SPACER_SIZE))
            // Media set title shown on the media set grid.
            Text(
                text = displayName ?: "",
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
