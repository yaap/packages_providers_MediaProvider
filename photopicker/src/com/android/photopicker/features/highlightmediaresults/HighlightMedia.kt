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

package com.android.photopicker.features.highlightmediaresults

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.photopicker.R
import com.android.photopicker.core.components.GridDragSelectDefaults
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.components.ScrollOrientation
import com.android.photopicker.core.components.defaultBuildMediaItem
import com.android.photopicker.core.components.defaultBuildSeparator
import com.android.photopicker.core.components.onGridDragSelect
import com.android.photopicker.core.components.rememberGridDragSelectState
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.data.model.Group
import com.android.photopicker.extensions.navigateToAlbumMediaGridForCategories
import com.android.photopicker.extensions.shimmerEffect
import com.android.photopicker.features.categorygrid.CategoryGridViewModel
import com.android.photopicker.features.highlightmediaresults.model.HighlightAlbum.Companion.getAlbumNameFromAlbum
import com.android.photopicker.features.highlightmediaresults.model.HighlightQuery
import com.android.photopicker.features.highlightmediaresults.model.HighlightQueryResultsParams
import com.android.photopicker.features.highlightmediaresults.model.QueryResultsHighlightType
import com.android.photopicker.features.search.SearchViewModel
import com.android.photopicker.util.LocalLocalizationHelper
import com.android.photopicker.util.applyWhen
import java.text.DateFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

val RECENTS_LABEL_PADDING = PaddingValues(top = 16.dp)
val HIGHLIGHT_TEXT_LABELS_PADDING = PaddingValues(bottom = 8.dp, start = 16.dp, end = 16.dp)
val MEASUREMENT_HIGHLIGHT_GRID_HEIGHT = 176.dp
val HIGHLIGHT_GRID_CONTENT_PADDING = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp)
val MEASUREMENT_HIGHLIGHT_GRID_CELL_ARRANGEMENT = 8.dp
const val HIGHLIGHT_GRID_CELL_COUNT = 10
const val HIGHLIGHT_GRID_ROW_COUNT = 1
const val HIGHLIGHT_SEARCH_DURATION_MS = 3000L

val HIGHLIGHT_QUERY_PLACEHOLDER_HEIGHT = 30.dp
val HIGHLIGHT_QUERY_PLACEHOLDER_WIDTH = 150.dp
val HIGHLIGHT_QUERY_PLACEHOLDER_CORNER = 28.dp

/**
 * A composable function which displays a media highlight section based on the given input highlight
 * params.
 *
 * @param params [LocationParams.WithLongClickAction] type params defining the long click behavior
 *   of highlight media items.
 * @param modifier The modifier to be applied to the composable if any
 */
@Composable
fun HighlightMedia(params: LocationParams = LocationParams.None, modifier: Modifier = Modifier) {
    val highlightParams: HighlightQueryResultsParams =
        LocalPhotopickerConfiguration.current.highlightQueryResultsParams
    val highlightQuery: HighlightQuery = highlightParams.queryResultsHighlightQuery
    var showHighlightSection by rememberSaveable { mutableStateOf(true) }

    if (!checkHighlightParamsValidity(highlightParams)) {
        return
    }
    val longClickActionParams = params as? LocationParams.WithLongClickAction
    val onItemLongClick: (item: MediaGridItem) -> Unit = { item ->
        longClickActionParams?.onLongClick(item)
    }

    val selectionLimit = LocalPhotopickerConfiguration.current.selectionLimit
    val selectionLimitExceededMessage =
        stringResource(R.string.photopicker_selection_limit_exceeded_snackbar, selectionLimit)
    AnimatedVisibility(
        visible = showHighlightSection,
        exit = fadeOut(animationSpec = tween(durationMillis = 300, easing = LinearEasing)),
    ) {
        Column {
            Box(modifier = modifier.animateContentSize()) {
                when (highlightQuery) {
                    is HighlightQuery.Search -> {
                        val viewModel: SearchViewModel = obtainViewModel(isActivityScoped = true)
                        var searchQuery by rememberSaveable {
                            mutableStateOf(highlightQuery.searchQuery)
                        }
                        val pagingItems =
                            remember(searchQuery) {
                                getSearchHighlightMediaItems(searchQuery, viewModel)
                            }

                        val highlightText =
                            stringResource(R.string.photopicker_hsr_suggestions_for_text) +
                                " " +
                                highlightQuery.searchQuery
                        HighlightSectionContent(
                            highlightQuery = highlightText,
                            highlightMediaItems = pagingItems.collectAsLazyPagingItems(),
                            onItemLongClick = onItemLongClick,
                            onClick = {
                                setSearchParametersForHighlightMedia(
                                    highlightQuery.searchQuery,
                                    viewModel,
                                )
                            },
                            modifier = modifier,
                            dispatcher = viewModel.backgroundDispatcher,
                            onGridItemSelection = { highlightMediaItem ->
                                viewModel.handleGridItemSelection(
                                    item = highlightMediaItem.media,
                                    selectionLimitExceededMessage = selectionLimitExceededMessage,
                                )
                            },
                            onEmptyResults = { showHighlightSection = false },
                        )
                    }

                    is HighlightQuery.Album -> {
                        val navController = LocalNavController.current
                        val viewModel: CategoryGridViewModel =
                            obtainViewModel(isActivityScoped = true)
                        val context = LocalContext.current

                        // Create the album object to fetch the album media. Fetching the album
                        // media requires only the base album data: album id and its authority.
                        val highlightBaseAlbum =
                            Group.BaseAlbum(
                                id = highlightQuery.album.albumId,
                                authority = viewModel.getLocalAlbumAuthority(),
                                displayName = getAlbumNameFromAlbum(context, highlightQuery.album),
                            )

                        var albumName by rememberSaveable {
                            mutableStateOf(highlightBaseAlbum.displayName)
                        }
                        val pagingItems =
                            remember(albumName) {
                                viewModel.getHighlightAlbumMedia(highlightBaseAlbum)
                            }

                        HighlightSectionContent(
                            highlightQuery = highlightBaseAlbum.displayName,
                            highlightMediaItems = pagingItems.collectAsLazyPagingItems(),
                            onItemLongClick = onItemLongClick,
                            onClick = {
                                navController.navigateToAlbumMediaGridForCategories(
                                    album = highlightBaseAlbum
                                )
                            },
                            modifier = modifier,
                            dispatcher = viewModel.backgroundDispatcher,
                            onGridItemSelection = { highlightMediaItem ->
                                viewModel.handleAlbumMediaGridItemSelection(
                                    highlightMediaItem.media,
                                    selectionLimitExceededMessage,
                                    highlightBaseAlbum,
                                )
                            },
                            onEmptyResults = { showHighlightSection = false },
                        )
                    }
                }
            }
            // Display the "Recents" label below the highlight grid
            RecentsLabel()
        }
    }
}

/**
 * The highlight composable to be rendered in case an app wants to highlight media based on a search
 * query or an album.
 *
 * @param highlightQuery The input highlight text query to highlight
 * @param highlightMediaItems The items to be displayed in the grid as [LazyPagingItems]
 * @param onItemLongClick Callback triggered when a media item is long-pressed.
 * @param onClick Defines the onClick behaviour of the See All button.
 * @param modifier The modifier to be applied if any
 * @param dispatcher Background Coroutine dispatcher.
 * @param onGridItemSelection Defines click action on the item.
 * @param onEmptyResults A callback function to be invoked when there are no results to show.
 */
@Composable
fun HighlightSectionContent(
    highlightQuery: String,
    highlightMediaItems: LazyPagingItems<MediaGridItem>,
    onItemLongClick: (item: MediaGridItem) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dispatcher: CoroutineDispatcher,
    onGridItemSelection: (item: MediaGridItem.MediaItem) -> Unit,
    onEmptyResults: () -> Unit,
) {
    var highlightSectionState by rememberSaveable { mutableStateOf(HighlightSectionState.LOADING) }

    AnimatedVisibility(
        visible = highlightSectionState == HighlightSectionState.LOADING,
        exit = fadeOut(animationSpec = tween(durationMillis = 100, easing = LinearEasing)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HighlightQueryPlaceholder()
            HighlightMediaPlaceholder()
        }
    }

    AnimatedVisibility(
        visible = highlightSectionState == HighlightSectionState.TIMEOUT,
        enter =
            fadeIn(
                animationSpec =
                    tween(durationMillis = 200, delayMillis = 400, easing = LinearEasing)
            ),
    ) {
        SuggestionsChip(
            highlightText = highlightQuery,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    AnimatedVisibility(
        visible = highlightSectionState == HighlightSectionState.RESULTS_AVAILABLE,
        enter = fadeIn(animationSpec = tween(durationMillis = 200)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Show the highlight query and the "See All" button
            HighlightQueryAndSeeAllButton(highlightText = highlightQuery, onClick = onClick)

            // Show the horizontal highlight grid
            HighlightMediaGrid(
                highlightItems = highlightMediaItems,
                onItemLongClick = onItemLongClick,
                onGridItemSelection = onGridItemSelection,
            )
        }
    }

    LaunchedEffect(highlightMediaItems.loadState.refresh) {
        if (highlightSectionState == HighlightSectionState.LOADING) {
            val refreshLoadState = highlightMediaItems.loadState.refresh
            val itemsCount = highlightMediaItems.itemCount
            withContext(dispatcher) {
                if (
                    itemsCount == 0 &&
                        refreshLoadState is LoadState.Loading &&
                        highlightSectionState == HighlightSectionState.LOADING
                ) {
                    delay(HIGHLIGHT_SEARCH_DURATION_MS)
                }
                when {
                    itemsCount == 0 &&
                        (refreshLoadState is LoadState.Loading ||
                            refreshLoadState is LoadState.Error) -> {
                        highlightSectionState = HighlightSectionState.TIMEOUT
                    }

                    itemsCount == 0 && refreshLoadState is LoadState.NotLoading -> {
                        highlightSectionState = HighlightSectionState.EMPTY
                        onEmptyResults()
                    }

                    else -> {
                        highlightSectionState = HighlightSectionState.RESULTS_AVAILABLE
                    }
                }
            }
        }
    }
}

/** Displays the "Recents" label below the Highlight grid */
@Composable
private fun RecentsLabel() {
    Row(modifier = Modifier.padding(RECENTS_LABEL_PADDING)) {
        defaultBuildSeparator(
            MediaGridItem.SeparatorItem(
                label = stringResource(R.string.photopicker_hsr_recents_label)
            )
        )
    }
}

/**
 * Displays the highlight text which could either be the input search text query or the album name
 * along with the "See All" button in a horizontal row.
 *
 * @param highlightText The highlight text - could be the search text or the album name
 * @param onClick Defines the onClick behaviour of the See All button.
 */
@Composable
private fun HighlightQueryAndSeeAllButton(highlightText: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(HIGHLIGHT_TEXT_LABELS_PADDING),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = highlightText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = onClick) {
            Text(
                text = stringResource(R.string.photopicker_hsr_see_all_button_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Displays a horizontally scrollable highlight media grid containing items based on the given
 * highlight query. The number of items in the grid is either [HIGHLIGHT_GRID_CELL_COUNT] or all
 * items based on the query, whichever is lower. The items in the grid are selectable.
 *
 * @param highlightItems The items to be displayed in the grid as [LazyPagingItems]
 * @param onItemLongClick Defines long click action on the item.
 * @param onGridItemSelection Defines click action on the item.
 */
@Composable
private fun HighlightMediaGrid(
    highlightItems: LazyPagingItems<MediaGridItem>,
    onItemLongClick: (item: MediaGridItem) -> Unit,
    onGridItemSelection: (item: MediaGridItem.MediaItem) -> Unit,
) {
    val state = rememberGridDragSelectState()
    val selection by LocalSelection.current.flow.collectAsStateWithLifecycle()
    val description = stringResource(R.string.photopicker_hsr_media_text)

    val dateFormat =
        LocalLocalizationHelper.current.getLocalizedDateTimeFormatter(
            dateStyle = DateFormat.MEDIUM,
            timeStyle = DateFormat.SHORT,
        )
    LazyHorizontalGrid(
        rows = GridCells.Fixed(HIGHLIGHT_GRID_ROW_COUNT),
        modifier =
            Modifier.fillMaxWidth()
                .height(MEASUREMENT_HIGHLIGHT_GRID_HEIGHT)
                .semantics { contentDescription = description }
                .applyWhen(
                    LocalPhotopickerConfiguration.current.flags.MEDIA_GRID_TOUCH_FEATURES_ENABLED,
                    {
                        onGridDragSelect(
                            config = LocalPhotopickerConfiguration.current,
                            items = highlightItems,
                            state = state,
                            windowRect = null,
                            indexOffset = 0,
                            autoScrollThreshold = GridDragSelectDefaults.autoScrollThreshold,
                            autoScrollOrientation = ScrollOrientation.HORIZONTAL,
                            hapticFeedback = GridDragSelectDefaults.hapticsFeedback,
                            selectionTransform = { it },
                        )
                    },
                ),
        state = state.gridState,
        contentPadding = HIGHLIGHT_GRID_CONTENT_PADDING,
        horizontalArrangement = Arrangement.spacedBy(MEASUREMENT_HIGHLIGHT_GRID_CELL_ARRANGEMENT),
    ) {
        items(
            count = minOf(highlightItems.itemCount, HIGHLIGHT_GRID_CELL_COUNT),
            key = { index -> MediaGridItem.keyFactory(highlightItems.peek(index), index) },
        ) { index ->
            val highlightMediaItem: MediaGridItem? = highlightItems.get(index)
            if (highlightMediaItem != null && highlightMediaItem is MediaGridItem.MediaItem) {
                defaultBuildMediaItem(
                    highlightMediaItem,
                    isHighlightMediaItem = true,
                    isSelected = selection.contains(highlightMediaItem.media),
                    selectedPosition = selection.indexOf(highlightMediaItem.media),
                    onClick = { onGridItemSelection(highlightMediaItem) },
                    onLongPress = { onItemLongClick(highlightMediaItem) },
                    dragSelectionEnabled = true,
                    dateFormat = dateFormat,
                    focusItem = null,
                )
            }
        }
    }
}

/** Displays suggestion chip with a clickable search query in case of search timeout. */
@Composable
private fun SuggestionsChip(
    highlightText: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(modifier = modifier) {
        Column {
            Row(
                modifier = Modifier.padding(HIGHLIGHT_TEXT_LABELS_PADDING),
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.width(20.dp).height(20.dp),
                )
                Text(
                    text = stringResource(R.string.photopicker_search_suggestions_text),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(PaddingValues(start = 16.dp)),
                )
            }

            AssistChip(
                onClick = onClick,
                label = { Text(highlightText) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription =
                            stringResource(R.string.photopicker_search_placeholder_text),
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
                modifier = Modifier.padding(HIGHLIGHT_GRID_CONTENT_PADDING),
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
}

/**
 * Displays a shimmering placeholder for a highlight query.
 *
 * This composable creates a `Box` with a fixed height and width, styled to resemble a search or
 * query input field in a loading state.
 */
@Composable
private fun HighlightQueryPlaceholder() {
    val description = stringResource(R.string.photopicker_hsr_query_placeholder_text)
    Box(
        modifier =
            Modifier.height(HIGHLIGHT_QUERY_PLACEHOLDER_HEIGHT)
                .width(HIGHLIGHT_QUERY_PLACEHOLDER_WIDTH)
                .padding(HIGHLIGHT_TEXT_LABELS_PADDING)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(HIGHLIGHT_QUERY_PLACEHOLDER_CORNER),
                )
                .clip(RoundedCornerShape(HIGHLIGHT_QUERY_PLACEHOLDER_CORNER))
                .shimmerEffect()
                .semantics { contentDescription = description }
    )
}

/**
 * Displays a horizontal grid of media items with a shimmering animation. Each item in the grid is
 * represented by a `Box` with a shimmering effect, indicating a loading state.
 */
@Composable
private fun HighlightMediaPlaceholder() {
    val description = stringResource(R.string.photopicker_hsr_media_placeholder_text)
    LazyHorizontalGrid(
        rows = GridCells.Fixed(HIGHLIGHT_GRID_ROW_COUNT),
        modifier =
            Modifier.fillMaxWidth().height(MEASUREMENT_HIGHLIGHT_GRID_HEIGHT).semantics {
                contentDescription = description
            },
        contentPadding = HIGHLIGHT_TEXT_LABELS_PADDING,
        horizontalArrangement = Arrangement.spacedBy(MEASUREMENT_HIGHLIGHT_GRID_CELL_ARRANGEMENT),
    ) {
        items(HIGHLIGHT_GRID_CELL_COUNT) { index ->
            Box(
                modifier =
                    Modifier.size(MEASUREMENT_HIGHLIGHT_GRID_HEIGHT)
                        .clip(RoundedCornerShape(HIGHLIGHT_QUERY_PLACEHOLDER_CORNER))
                        .shimmerEffect()
            )
        }
    }
}

/** Returns a flow containing the media items based on the given input search query. */
private fun getSearchHighlightMediaItems(
    highlightQuery: String,
    viewModel: SearchViewModel,
): Flow<PagingData<MediaGridItem>> {
    return viewModel.getHighlightSearchResults(searchQuery = highlightQuery)
}

/** Sets the search params to show all the search results when the See All button is clicked */
private fun setSearchParametersForHighlightMedia(
    highlightQuery: String,
    viewModel: SearchViewModel,
) {
    viewModel.performSearch(query = highlightQuery)
    viewModel.setSearchBarFocusedState(true)
    viewModel.setSearchBarText(text = highlightQuery)
}

/**
 * Checks the validity of the input highlight params to make a final call on whether the highlight
 * section will be displayed or not. In case of empty highlight query or if the highlight type is
 * not [QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION], we simply return.
 */
private fun checkHighlightParamsValidity(highlightParams: HighlightQueryResultsParams): Boolean {
    val highlightType = highlightParams.queryResultsHighlightType
    // HighlightMedia carousel should only be shown for HIGHLIGHT_MEDIA_SECTION highlight type
    val validHighlightType = highlightType == QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION
    // An empty search query and UNSET album type won't show any results
    val validQuery =
        when (highlightParams.queryResultsHighlightQuery) {
            is HighlightQuery.Search -> {
                highlightParams.queryResultsHighlightQuery.searchQuery.isNotEmpty()
            }
            else -> true
        }
    return validHighlightType && validQuery
}

/** Represents the different UI states for the Highlight section. */
enum class HighlightSectionState {
    LOADING,
    TIMEOUT,
    RESULTS_AVAILABLE,
    EMPTY,
}
