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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.android.photopicker.core.components.rememberMediaGridState
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.SelectionDisabledReason
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val RECENTS_LABEL_PADDING = PaddingValues(top = 16.dp)
val HIGHLIGHT_TEXT_LABELS_PADDING = PaddingValues(bottom = 8.dp, start = 16.dp, end = 16.dp)
val MEASUREMENT_HIGHLIGHT_GRID_HEIGHT = 176.dp
val HIGHLIGHT_GRID_CONTENT_PADDING = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp)
val MEASUREMENT_HIGHLIGHT_GRID_CELL_ARRANGEMENT = 8.dp
const val HIGHLIGHT_GRID_CELL_COUNT = 10
const val HIGHLIGHT_GRID_ROW_COUNT = 1
const val HIGHLIGHT_SEARCH_DURATION_MS = 8000L
val HIGHLIGHT_QUERY_PLACEHOLDER_HEIGHT = 30.dp
val HIGHLIGHT_QUERY_PLACEHOLDER_WIDTH = 150.dp
val HIGHLIGHT_QUERY_PLACEHOLDER_CORNER = 28.dp
val HIGHLIGHT_TOOLTIP_ELEVATION_MEASURE = 2.dp
val TOOLTIP_CONTENT_HORIZONTAL_PADDING = 12.dp
val TOOLTIP_CONTENT_VERTICAL_PADDING = 8.dp
val TOOLTIP_ROUNDED_CORNERS_MEASURE = 20.dp
val TOOLTIP_WIDTH = 250.dp
val TOOLTIP_ANCHOR_SPACING_EMBEDDED = 14.dp
val TOOLTIP_PLACEMENT_CORRECTION_OFFSET = 12.dp
val MEASUREMENT_ZERO = 0.dp

/**
 * A composable function which displays a media highlight section based on the given input highlight
 * params.
 *
 * @param params [LocationParams.WithLongClickAction] type params defining the long click behavior
 *   of highlight media items.
 * @param modifier The modifier to be applied to the composable if any
 * @param highlightMediaViewModel - A viewModel override for the composable. Normally, this is
 *   fetched via hilt from the backstack entry by using obtainViewModel()
 */
@Composable
fun HighlightMedia(
    params: LocationParams = LocationParams.None,
    modifier: Modifier = Modifier,
    highlightMediaViewModel: HighlightMediaViewModel = obtainViewModel(isActivityScoped = true),
) {
    val configuration = LocalPhotopickerConfiguration.current
    val highlightParams: HighlightQueryResultsParams = configuration.highlightQueryResultsParams
    val highlightQuery: HighlightQuery = highlightParams.queryResultsHighlightQuery
    val showHighlightSection by
        highlightMediaViewModel.showHighlightSection.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val events = LocalEvents.current

    if (!checkHighlightParamsValidity(highlightParams)) {
        return
    }

    val selectionLimit = configuration.selectionLimit
    val localizationHelper = LocalLocalizationHelper.current
    val resources = LocalContext.current.resources
    val selectionLimitExceededMessage =
        stringResource(
            R.string.photopicker_selection_limit_exceeded_snackbar,
            localizationHelper.getLocalizedCount(selectionLimit),
        )
    val selectionBatchSizeLimitExceededMessage =
        SelectionDisabledReason.getSelectionBatchSizeLimitExceededMessage(
            configuration,
            localizationHelper,
            resources,
        )
    AnimatedVisibility(
        visible = showHighlightSection,
        exit = fadeOut(animationSpec = tween(durationMillis = 300, easing = LinearEasing)),
    ) {
        Column() {
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

                        HighlightSectionContent(
                            // Pass in the search query as a param to ensure correct translations
                            highlightQuery =
                                stringResource(
                                    R.string.photopicker_hsr_suggestions_for_label,
                                    highlightQuery.searchQuery,
                                ),
                            isSearchHighlight = true,
                            highlightMediaItems = pagingItems.collectAsLazyPagingItems(),
                            onClick = {
                                setSearchParametersForHighlightMedia(
                                    highlightQuery.searchQuery,
                                    viewModel,
                                )
                            },
                            modifier = modifier,
                            dispatcher = viewModel.backgroundDispatcher,
                            onGridItemSelection = { highlightMediaItem ->
                                val disabledReasonMessage =
                                    highlightMediaItem.media.disabledReason?.getDisabledMessage(
                                        configuration,
                                        localizationHelper,
                                        resources,
                                    )
                                viewModel.handleGridItemSelection(
                                    item = highlightMediaItem.media,
                                    selectionLimitExceededMessage = selectionLimitExceededMessage,
                                    disabledReasonMessage = disabledReasonMessage,
                                    selectionBatchSizeLimitExceededMessage =
                                        selectionBatchSizeLimitExceededMessage,
                                    selectionSource = Telemetry.MediaLocation.HIGHLIGHT_MEDIA_GRID,
                                )
                                scope.launch {
                                    events.dispatch(
                                        Event.LogPhotopickerUIEvent(
                                            FeatureToken.HIGHLIGHT_MEDIA_RESULTS.token,
                                            configuration.sessionId,
                                            configuration.callingPackageUid ?: -1,
                                            Telemetry.UiEvent.PICKER_SELECT_HSR_RESULT,
                                        )
                                    )
                                }
                            },
                            onEmptyResults = {
                                highlightMediaViewModel.setShowHighlightSection(false)
                            },
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
                            isSearchHighlight = false,
                            highlightMediaItems = pagingItems.collectAsLazyPagingItems(),
                            onClick = {
                                navController.navigateToAlbumMediaGridForCategories(
                                    album = highlightBaseAlbum
                                )
                            },
                            modifier = modifier,
                            dispatcher = viewModel.backgroundDispatcher,
                            onGridItemSelection = { highlightMediaItem ->
                                val disabledReasonMessage =
                                    highlightMediaItem.media.disabledReason?.getDisabledMessage(
                                        configuration,
                                        localizationHelper,
                                        resources,
                                    )
                                viewModel.handleAlbumMediaGridItemSelection(
                                    highlightMediaItem.media,
                                    selectionLimitExceededMessage,
                                    highlightBaseAlbum,
                                    disabledReasonMessage,
                                    selectionBatchSizeLimitExceededMessage,
                                    Telemetry.MediaLocation.HIGHLIGHT_MEDIA_GRID,
                                )
                                scope.launch {
                                    events.dispatch(
                                        Event.LogPhotopickerUIEvent(
                                            FeatureToken.HIGHLIGHT_MEDIA_RESULTS.token,
                                            configuration.sessionId,
                                            configuration.callingPackageUid ?: -1,
                                            Telemetry.UiEvent.PICKER_SELECT_HSR_RESULT,
                                        )
                                    )
                                }
                            },
                            onEmptyResults = {
                                highlightMediaViewModel.setShowHighlightSection(false)
                            },
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
 * @param isSearchHighlight A boolean indicating if the highlight is of type search or not in which
 *   case it is an album highlight.
 * @param highlightMediaItems The items to be displayed in the grid as [LazyPagingItems]
 * @param onClick Defines the onClick behaviour of the See All button.
 * @param modifier The modifier to be applied if any
 * @param dispatcher Background Coroutine dispatcher.
 * @param onGridItemSelection Defines click action on the item.
 * @param onEmptyResults A callback function to be invoked when there are no results to show.
 */
@Composable
fun HighlightSectionContent(
    highlightQuery: String,
    isSearchHighlight: Boolean,
    highlightMediaItems: LazyPagingItems<MediaGridItem>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dispatcher: CoroutineDispatcher,
    onGridItemSelection: (item: MediaGridItem.MediaItem) -> Unit,
    onEmptyResults: () -> Unit,
) {
    var highlightSectionState by rememberSaveable { mutableStateOf(HighlightSectionState.LOADING) }
    val events = LocalEvents.current
    val configuration = LocalPhotopickerConfiguration.current

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
            HighlightQueryAndSeeAllButton(
                highlightText = highlightQuery,
                onClick = onClick,
                isSearchHighlight = isSearchHighlight,
            )

            // Show the horizontal highlight grid
            HighlightMediaGrid(
                highlightItems = highlightMediaItems,
                onGridItemSelection = onGridItemSelection,
            )
        }
    }

    LaunchedEffect(highlightMediaItems.loadState.refresh) {
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
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.HIGHLIGHT_MEDIA_RESULTS.token,
                            configuration.sessionId,
                            configuration.callingPackageUid ?: -1,
                            Telemetry.UiEvent.UI_LOADED_HSR_TIMEOUT,
                        )
                    )
                }
                itemsCount == 0 && refreshLoadState is LoadState.NotLoading -> {
                    highlightSectionState = HighlightSectionState.EMPTY
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.HIGHLIGHT_MEDIA_RESULTS.token,
                            configuration.sessionId,
                            configuration.callingPackageUid ?: -1,
                            Telemetry.UiEvent.UI_LOADED_EMPTY_STATE,
                        )
                    )
                    onEmptyResults()
                }
                else -> {
                    highlightSectionState = HighlightSectionState.RESULTS_AVAILABLE
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.HIGHLIGHT_MEDIA_RESULTS.token,
                            configuration.sessionId,
                            configuration.callingPackageUid ?: -1,
                            Telemetry.UiEvent.UI_LOADED_HSR_RESULTS,
                        )
                    )
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
 * along with the "See All" button in a horizontal row. In case of search highlight, an info icon
 * button precedes the search highlight text which is an anchor for the tooltip describing the
 * source of the highlight text to the user. This is not shown in case of album highlight which are
 * typically predefined.
 *
 * @param highlightText The highlight text - could be the search text or the album name
 * @param onClick Defines the onClick behaviour of the See All button.
 * @param isSearchHighlight A boolean indicating if the highlight is of type search or not in which
 *   case it is an album highlight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighlightQueryAndSeeAllButton(
    highlightText: String,
    onClick: () -> Unit,
    isSearchHighlight: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(HIGHLIGHT_TEXT_LABELS_PADDING),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val scope = rememberCoroutineScope()
        val events = LocalEvents.current
        val configuration = LocalPhotopickerConfiguration.current
        // We make the tooltip persistent to start with so as to control its behavior later
        // when the info icon is clicked.
        val tooltipState = rememberTooltipState(isPersistent = true)

        // Display the tooltip in case of search highlight only
        if (isSearchHighlight) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isEmbedded =
                    LocalPhotopickerConfiguration.current.runtimeEnv ==
                        PhotopickerRuntimeEnv.EMBEDDED
                val spacingBetweenTooltipAndAnchor =
                    if (isEmbedded) TOOLTIP_ANCHOR_SPACING_EMBEDDED else MEASUREMENT_ZERO
                val spacingBetweenTooltipAndAnchorInPx =
                    with(LocalDensity.current) { spacingBetweenTooltipAndAnchor.roundToPx() }
                val toolTipCorrectionOffsetMeasure =
                    if (isEmbedded) TOOLTIP_PLACEMENT_CORRECTION_OFFSET else MEASUREMENT_ZERO
                val toolTipCorrectionOffsetMeasureInPx =
                    with(LocalDensity.current) { toolTipCorrectionOffsetMeasure.roundToPx() }
                TooltipBox(
                    // This provider positions the tooltip preferring to place it above the anchor.
                    // The implementation is exactly similar to the default implementation with
                    // some adjustments made to set the tooltip and its caret properly for
                    // embedded photopicker. For embedded picker:
                    // The tooltip and its caret is slightly misplaced.
                    // We use custom offset values for both the coordinates to render it correctly
                    // so that it behaves similar to the regular photopicker. We only need the
                    // x offset in case x goes outside the window bounds which happens to be the
                    // case in embedded picker.
                    // We adjust the tooltip-anchor spacing coordinate to make sure the tooltip
                    // caret is always rendered above the anchor and not adjacent to it.
                    positionProvider =
                        remember {
                            object : PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: IntRect,
                                    windowSize: IntSize,
                                    layoutDirection: LayoutDirection,
                                    popupContentSize: IntSize,
                                ): IntOffset {

                                    // Tooltip prefers to be center aligned horizontally.
                                    var x =
                                        anchorBounds.left +
                                            (anchorBounds.width - popupContentSize.width) / 2

                                    if (x < 0) {
                                        // Make tooltip start aligned if colliding with the
                                        // left side of the screen.
                                        x = anchorBounds.left - toolTipCorrectionOffsetMeasureInPx
                                    } else if (x + popupContentSize.width > windowSize.width) {
                                        // Make tooltip end aligned if colliding with the
                                        // right side of the screen
                                        x = anchorBounds.right - popupContentSize.width
                                    }

                                    // Tooltip prefers to be above the anchor,
                                    // but if this causes the tooltip to overlap with the anchor
                                    // then we place it below the anchor
                                    var y =
                                        anchorBounds.top -
                                            popupContentSize.height -
                                            spacingBetweenTooltipAndAnchorInPx
                                    if (y < 0) {
                                        y = anchorBounds.bottom + spacingBetweenTooltipAndAnchorInPx
                                    }
                                    return IntOffset(x, y)
                                }
                            }
                        },
                    tooltip = {
                        PlainTooltip(
                            // This adds the caret(the small arrow pointing to the anchor button)
                            // to the tooltip.
                            caretShape = TooltipDefaults.caretShape(),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(TOOLTIP_ROUNDED_CORNERS_MEASURE),
                            modifier = Modifier.width(TOOLTIP_WIDTH),
                            tonalElevation = HIGHLIGHT_TOOLTIP_ELEVATION_MEASURE,
                            shadowElevation = HIGHLIGHT_TOOLTIP_ELEVATION_MEASURE,
                        ) {
                            val callingApplicationLabel: String =
                                configuration.callingPackageLabel
                                    ?: stringResource(R.string.photopicker_hsr_generic_app_label)
                            Text(
                                modifier =
                                    Modifier.padding(
                                        horizontal = TOOLTIP_CONTENT_HORIZONTAL_PADDING,
                                        vertical = TOOLTIP_CONTENT_VERTICAL_PADDING,
                                    ),
                                text =
                                    stringResource(
                                        R.string.photopicker_hsr_tooltip_text,
                                        callingApplicationLabel,
                                    ),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    },
                    state = tooltipState,
                ) {
                    // This is the anchor for the tooltip.
                    IconButton(
                        onClick = {
                            // The tooltip's show() and dismiss() have to be called in two separate
                            // coroutines to prevent a deadlock. Tooltip's show() suspends
                            // waiting for dismiss() to be called which never will trigger
                            // if they are in the same coroutine.
                            scope.launch { tooltipState.show() }
                            scope.launch {
                                // Show the tooltip for 5 seconds
                                delay(5000L)
                                tooltipState.dismiss()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription =
                                stringResource(R.string.photopicker_hsr_tooltip_icon_description),
                        )
                    }
                }
                // Show the highlight text
                HighlightText(highlightText = highlightText)
            }
        } else {
            // Album highlight: just show the highlight text
            HighlightText(highlightText = highlightText)
        }

        TextButton(
            onClick = {
                onClick()
                scope.launch {
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.HIGHLIGHT_MEDIA_RESULTS.token,
                            configuration.sessionId,
                            configuration.callingPackageUid ?: -1,
                            Telemetry.UiEvent.PICKER_SELECT_HSR_SEE_ALL,
                        )
                    )
                }
            }
        ) {
            Text(
                text = stringResource(R.string.photopicker_hsr_see_all_button_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Displays the highlight text in both album and search highlight */
@Composable
private fun HighlightText(highlightText: String) {
    Text(
        text = highlightText,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * Displays a horizontally scrollable highlight media grid containing items based on the given
 * highlight query. The number of items in the grid is either [HIGHLIGHT_GRID_CELL_COUNT] or all
 * items based on the query, whichever is lower. The items in the grid are selectable.
 *
 * @param highlightItems The items to be displayed in the grid as [LazyPagingItems]
 * @param onGridItemSelection Defines click action on the item.
 */
@Composable
private fun HighlightMediaGrid(
    highlightItems: LazyPagingItems<MediaGridItem>,
    onGridItemSelection: (item: MediaGridItem.MediaItem) -> Unit,
) {
    val state = rememberMediaGridState()
    val selection by LocalSelection.current.flow.collectAsStateWithLifecycle()
    val description = stringResource(R.string.photopicker_hsr_media_text)
    val configuration = LocalPhotopickerConfiguration.current
    val dragSelectionEnabled = configuration.selectionLimit > 1

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
                    dragSelectionEnabled,
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
                    dragSelectionEnabled = dragSelectionEnabled,
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
    val scope = rememberCoroutineScope()
    val events = LocalEvents.current
    val configuration = LocalPhotopickerConfiguration.current
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
                onClick = {
                    onClick()
                    scope.launch {
                        events.dispatch(
                            Event.LogPhotopickerUIEvent(
                                FeatureToken.HIGHLIGHT_MEDIA_RESULTS.token,
                                configuration.sessionId,
                                configuration.callingPackageUid ?: -1,
                                Telemetry.UiEvent.PICKER_SELECT_HSR_SUGGESTION_CHIP,
                            )
                        )
                    }
                },
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
