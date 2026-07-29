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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.components.EmptyState
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.components.mediaGrid
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.SelectionDisabledReason
import com.android.photopicker.extensions.navigateToPreviewMedia
import com.android.photopicker.features.preview.PreviewFeature
import com.android.photopicker.util.LocalLocalizationHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Primary composable for drawing the Mediaset content grid on
 * [PhotopickerDestinations.MEDIASET_CONTENT_GRID]
 *
 * @param viewModel - A viewModel override for the composable. Normally, this is fetched via hilt
 *   from the backstack entry by using obtainViewModel()
 * @param flow - stateflow holding the mediaset for which the media needs to be presented.
 */
@Composable
fun MediaSetContentGrid(
    flow: StateFlow<Group.MediaSet?>,
    viewModel: CategoryGridViewModel = obtainViewModel(),
) {
    val mediasetState by flow.collectAsStateWithLifecycle(initialValue = null)
    val mediaset = mediasetState
    Column(modifier = Modifier.fillMaxSize()) {
        when (mediaset) {
            null -> {}
            else -> {
                val mediasetItems = remember(mediaset) { viewModel.getMediaSetContent(mediaset) }
                MediasetContentGrid(mediasetItems = mediasetItems)
            }
        }
    }
}

/** Initialises all the states and media source required to load media for the input [mediaset]. */
@Composable
private fun MediasetContentGrid(
    mediasetItems: Flow<PagingData<MediaGridItem>>,
    viewModel: CategoryGridViewModel = obtainViewModel(),
) {
    val featureManager = LocalFeatureManager.current
    val isPreviewEnabled = remember { featureManager.isFeatureEnabled(PreviewFeature::class.java) }
    val navController = LocalNavController.current
    val items = mediasetItems.collectAsLazyPagingItems()
    // Collect the selection to notify the mediaGrid of selection changes.
    val selection by LocalSelection.current.flow.collectAsStateWithLifecycle()
    val configuration = LocalPhotopickerConfiguration.current
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
            LocalPhotopickerConfiguration.current,
            localizationHelper,
            resources,
        )
    // Use the expanded layout any time the Width is Medium or larger.
    val isExpandedScreen: Boolean =
        when (LocalWindowSizeClass.current.widthSizeClass) {
            WindowWidthSizeClass.Medium -> true
            WindowWidthSizeClass.Expanded -> true
            else -> false
        }
    val isEmbedded = configuration.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
    val host = LocalEmbeddedState.current?.host
    val scope = rememberCoroutineScope()
    val events = LocalEvents.current

    // Container encapsulating the mediaset title followed by its content in the form of a
    // grid, the content also includes date and month separators.
    Column(modifier = Modifier.fillMaxSize()) {
        val isEmptyAndNoMorePages =
            items.itemCount == 0 &&
                items.loadState.source.append is LoadState.NotLoading &&
                items.loadState.source.append.endOfPaginationReached

        // State to track the loading and empty states
        var resultsState by remember { mutableStateOf(ResultsState.LOADING_WITHOUT_INDICATOR) }
        if (isEmptyAndNoMorePages) {
            resultsState = ResultsState.EMPTY
        }

        LaunchedEffect(items.loadState.refresh) {
            if (
                items.itemCount == 0 &&
                    items.loadState.refresh is LoadState.Loading &&
                    resultsState == ResultsState.LOADING_WITHOUT_INDICATOR
            ) {

                // Switch to the background thread.
                withContext(viewModel.backgroundDispatcher) {
                    // Wait 1 second to display the loading indicator.
                    delay(1000)
                    if (
                        items.itemCount == 0 &&
                            resultsState == ResultsState.LOADING_WITHOUT_INDICATOR
                    ) {
                        resultsState = ResultsState.LOADING_WITH_INDICATOR

                        // Wait 10 seconds before giving up and displaying the no results page.
                        delay(10000)
                        if (
                            items.itemCount == 0 &&
                                resultsState == ResultsState.LOADING_WITH_INDICATOR
                        ) {
                            resultsState = ResultsState.EMPTY
                        }
                    }
                }
            } else if (resultsState != ResultsState.EMPTY) {
                resultsState = ResultsState.MEDIA_SETS_CONTENT_GRID
            }
        }

        when (resultsState) {
            ResultsState.EMPTY -> {
                val localConfig = LocalConfiguration.current
                val emptyStatePadding =
                    remember(localConfig) { (localConfig.screenHeightDp * .20).dp }
                val isVideoOnlyMimeType = configuration.hasOnlyVideoMimeTypes()
                val (title, body, icon) = getEmptyStateContentForMediaset(isVideoOnlyMimeType)
                EmptyState(
                    modifier =
                        if (SdkLevel.isAtLeastU() && isEmbedded && host != null) {
                            // In embedded no need to give extra top padding to make empty
                            // state title and body clearly visible in collapse mode (small view)
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
            ResultsState.LOADING_WITH_INDICATOR -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    val progressIndicatorDescription =
                        stringResource(R.string.photopicker_loading_media_items_description)
                    CircularProgressIndicator(
                        modifier =
                            Modifier.align(Alignment.Center).semantics {
                                contentDescription = progressIndicatorDescription
                            }
                    )
                }
            }
            ResultsState.MEDIA_SETS_CONTENT_GRID -> {

                val onItemPreview = { item: MediaGridItem ->
                    // If the [PreviewFeature] is enabled, launch the preview route.
                    if (isPreviewEnabled && item is MediaGridItem.MediaItem) {
                        // Dispatch UI event to log long pressing the media item
                        scope.launch {
                            events.dispatch(
                                Event.LogPhotopickerUIEvent(
                                    FeatureToken.PREVIEW.token,
                                    configuration.sessionId,
                                    configuration.callingPackageUid ?: -1,
                                    Telemetry.UiEvent.PICKER_LONG_SELECT_MEDIA_ITEM,
                                )
                            )
                        }
                        // Dispatch UI event to log entry into preview mode
                        scope.launch {
                            events.dispatch(
                                Event.LogPhotopickerUIEvent(
                                    FeatureToken.PREVIEW.token,
                                    configuration.sessionId,
                                    configuration.callingPackageUid ?: -1,
                                    Telemetry.UiEvent.ENTER_PICKER_PREVIEW_MODE,
                                )
                            )
                        }
                        navController.navigateToPreviewMedia(item.media)
                    }
                }

                val aspectRatio = configuration.getAspectRatioForMediaItemGrids().ratio
                mediaGrid(
                    modifier = Modifier.fillMaxSize(),
                    items = items,
                    isExpandedScreen = isExpandedScreen,
                    selection = selection,
                    aspectRatio = aspectRatio,
                    dragSelectionEnabled = configuration.selectionLimit > 1,
                    dragSelectIndexOffset = 0, // by default, which is suitable here.
                    pinchToZoomEnabled = true,
                    onZoomAtMaxZoom = onItemPreview,
                    onItemClick = { item ->
                        if (item is MediaGridItem.MediaItem) {
                            val disabledReasonMessage =
                                item.media.disabledReason?.getDisabledMessage(
                                    configuration,
                                    localizationHelper,
                                    resources,
                                )
                            viewModel.handleMediaSetItemSelection(
                                item.media,
                                selectionLimitExceededMessage,
                                disabledReasonMessage,
                                selectionBatchSizeLimitExceededMessage,
                            )
                        }
                    },
                    selectionTransform = { mediaItem: Media ->
                        Media.withSelectable(
                            item = mediaItem,
                            selectionSource = Telemetry.MediaLocation.CATEGORY,
                            album = null, // MediaSet is not an album
                        )
                    },
                )
                LaunchedEffect(Unit) {
                    // Dispatch UI event to log loading of media set contents
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.CATEGORY_GRID.token,
                            configuration.sessionId,
                            configuration.callingPackageUid ?: -1,
                            Telemetry.UiEvent.UI_LOADED_MEDIA_SETS_CONTENTS,
                        )
                    )
                }
            }
            else -> {}
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
    isVideoOnlyMime: Boolean
): Triple<String, String, ImageVector> {
    return Triple(
        when {
            isVideoOnlyMime -> stringResource(R.string.photopicker_videos_empty_state_title)
            else -> stringResource(R.string.photopicker_photos_empty_state_title)
        },
        stringResource(R.string.photopicker_photos_empty_state_body),
        Icons.Outlined.Image,
    )
}

/** Represents the different UI states for the media sets results data. */
enum class ResultsState {
    LOADING_WITHOUT_INDICATOR,
    LOADING_WITH_INDICATOR,
    EMPTY,
    MEDIA_SETS_CONTENT_GRID,
}
