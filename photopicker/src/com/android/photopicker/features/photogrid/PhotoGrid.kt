/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.features.photogrid

import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.StateSelector
import com.android.photopicker.core.animations.standardDecelerate
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.components.AnimatedBanner
import com.android.photopicker.core.components.EmptyState
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.components.getCellsPerRow
import com.android.photopicker.core.components.mediaGrid
import com.android.photopicker.core.components.rememberMediaGridState
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.events.Telemetry
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.hideWhenState
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.SelectionDisabledReason
import com.android.photopicker.extensions.navigateToAlbumGrid
import com.android.photopicker.extensions.navigateToCategoryGrid
import com.android.photopicker.extensions.navigateToPhotoGrid
import com.android.photopicker.extensions.navigateToPreviewMedia
import com.android.photopicker.features.albumgrid.AlbumGridFeature
import com.android.photopicker.features.camera.CameraFeature
import com.android.photopicker.features.camera.CameraViewModel
import com.android.photopicker.features.categorygrid.CategoryGridFeature
import com.android.photopicker.features.navigationbar.NavigationBarButton
import com.android.photopicker.features.preview.PreviewFeature
import com.android.photopicker.util.LocalLocalizationHelper
import kotlinx.coroutines.launch

private val MEASUREMENT_BANNER_PADDING =
    PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp)

// This is the number of rows we should include in the recents section at the top of the Photo Grid.
// The recents section does not contain any separators.
private val RECENTS_ROW_COUNT = 3

/**
 * Primary composable for drawing the main PhotoGrid on [PhotopickerDestinations.PHOTO_GRID]
 *
 * @param viewModel - A viewModel override for the composable. Normally, this is fetched via hilt
 *   from the backstack entry by using obtainViewModel()
 * @param cameraViewModel - Camera view model that holds camera state.
 */
@Composable
fun PhotoGrid(
    viewModel: PhotoGridViewModel = obtainViewModel(),
    cameraViewModel: CameraViewModel = obtainViewModel(),
) {
    val navController = LocalNavController.current
    val featureManager = LocalFeatureManager.current
    val isPreviewEnabled = remember { featureManager.isFeatureEnabled(PreviewFeature::class.java) }
    val layoutDirection = LocalLayoutDirection.current

    val selection by LocalSelection.current.flow.collectAsStateWithLifecycle()

    /* Use the expanded layout any time the Width is Medium or larger. */
    val isExpandedScreen: Boolean =
        when (LocalWindowSizeClass.current.widthSizeClass) {
            WindowWidthSizeClass.Medium -> true
            WindowWidthSizeClass.Expanded -> true
            else -> false
        }

    val cellsPerRow = remember(isExpandedScreen) { getCellsPerRow(isExpandedScreen) }
    val itemsFlow =
        remember(cellsPerRow) {
            viewModel.getData(/* recentsCellCount */ (cellsPerRow * RECENTS_ROW_COUNT))
        }
    val items = itemsFlow.collectAsLazyPagingItems()

    val configuration = LocalPhotopickerConfiguration.current
    val selectionLimit = configuration.selectionLimit
    val localizationHelper = LocalLocalizationHelper.current
    val selectionLimitExceededMessage =
        stringResource(
            R.string.photopicker_selection_limit_exceeded_snackbar,
            localizationHelper.getLocalizedCount(selectionLimit),
        )
    val resources = LocalContext.current.resources
    val selectionBatchSizeLimitExceededMessage =
        SelectionDisabledReason.getSelectionBatchSizeLimitExceededMessage(
            configuration,
            localizationHelper,
            resources,
        )
    val events = LocalEvents.current
    val scope = rememberCoroutineScope()

    // Modifier applied when photo grid to album grid navigation is disabled
    val baseModifier = Modifier.fillMaxSize()
    // Modifier applied when photo grid to album grid navigation is enabled
    val modifierWithNavigation =
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { _, dragAmount ->
                    val adjustedDragAmount =
                        if (layoutDirection == LayoutDirection.Rtl) -dragAmount else dragAmount
                    if (adjustedDragAmount < 0) {
                        // Negative adjusted drag amount indicates navigate to album/category grid
                        if (featureManager.isFeatureEnabled(AlbumGridFeature::class.java)) {
                            // Dispatch UI event to indicate switching to albums tab
                            scope.launch {
                                events.dispatch(
                                    Event.LogPhotopickerUIEvent(
                                        FeatureToken.ALBUM_GRID.token,
                                        configuration.sessionId,
                                        configuration.callingPackageUid ?: -1,
                                        Telemetry.UiEvent.SWITCH_PICKER_TAB,
                                    )
                                )
                            }
                            navController.navigateToAlbumGrid()
                        } else if (
                            featureManager.isFeatureEnabled(CategoryGridFeature::class.java)
                        ) {
                            // Dispatch UI event to indicate switching to collections tab
                            scope.launch {
                                events.dispatch(
                                    Event.LogPhotopickerUIEvent(
                                        FeatureToken.CATEGORY_GRID.token,
                                        configuration.sessionId,
                                        configuration.callingPackageUid ?: -1,
                                        Telemetry.UiEvent.SWITCH_PICKER_TAB,
                                    )
                                )
                            }
                            navController.navigateToCategoryGrid()
                        }
                    }
                }
            )
        }

    val isEmbedded =
        LocalPhotopickerConfiguration.current.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
    val isExpanded = LocalEmbeddedState.current?.isExpanded ?: false
    val isEmbeddedAndCollapsed = isEmbedded && !isExpanded
    val host = LocalEmbeddedState.current?.host
    val photoGridBoxHeight = remember { mutableStateOf(0f) }
    val photosGridDescription = stringResource(R.string.photopicker_media_grid_content_description)

    Box(
        modifier =
            when (isEmbeddedAndCollapsed) {
                    true -> baseModifier
                    false -> modifierWithNavigation
                }
                .onGloballyPositioned { layoutCoordinates ->
                    val newHeight = layoutCoordinates.size.height.toFloat()
                    if (photoGridBoxHeight.value != newHeight) {
                        photoGridBoxHeight.value = newHeight
                    }
                }
                .semantics { contentDescription = photosGridDescription }
    ) {
        val isEmptyAndNoMorePages =
            items.itemCount == 0 &&
                items.loadState.source.append is LoadState.NotLoading &&
                items.loadState.source.append.endOfPaginationReached
        val isNotEmpty = items.itemCount > 0

        when {
            isEmptyAndNoMorePages -> {
                val localConfig = LocalConfiguration.current
                val emptyStatePadding =
                    remember(localConfig) { (localConfig.screenHeightDp * .20).dp }
                val isVideoOnlyMimeType =
                    LocalPhotopickerConfiguration.current.hasOnlyVideoMimeTypes()

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
                    icon = Icons.Outlined.Image,
                    title =
                        when {
                            isVideoOnlyMimeType ->
                                stringResource(R.string.photopicker_videos_empty_state_title)
                            else -> stringResource(R.string.photopicker_photos_empty_state_title)
                        },
                    body = stringResource(R.string.photopicker_photos_empty_state_body),
                )
            }
            // Only show the grid when there is at least one item in the page set.
            isNotEmpty -> {

                // When the PhotoGrid is ready to show, also collect the latest banner
                // data from [BannerManager] so it can be placed inside of the mediaGrid's
                // scroll container.
                val currentBanner by viewModel.getBannerFlow().collectAsStateWithLifecycle()

                // Embedded selector for the Grid banner section..
                // Extract this out because the below grid implementations differ based on flags,
                // but both use the same selector.
                val bannerContentSelector =
                    object : StateSelector.AnimatedVisibilityInEmbedded {
                        override val visible = LocalEmbeddedState.current?.isExpanded ?: false
                        override val enter =
                            expandVertically(animationSpec = standardDecelerate(300))
                        override val exit =
                            shrinkVertically(animationSpec = standardDecelerate(150))
                    }

                val highlightContentSelector =
                    object : StateSelector.AnimatedVisibilityInEmbedded {
                        override val visible = LocalEmbeddedState.current?.isExpanded ?: false
                        override val enter =
                            expandVertically(animationSpec = standardDecelerate(300))
                        override val exit =
                            shrinkVertically(animationSpec = standardDecelerate(150))
                    }

                // Listen to whether camera is currently enabled or not
                val isCameraAvailable =
                    if (featureManager.isFeatureEnabled(CameraFeature::class.java)) {
                        cameraViewModel.isCameraAvailable.collectAsStateWithLifecycle()
                    } else {
                        null
                    }

                // Click handler for the Grid. Extract this out because the below grid
                // implementations differ based on flags, but both use the same click handler.
                val onItemClick = { item: MediaGridItem ->
                    if (item is MediaGridItem.MediaItem) {
                        val disabledReasonMessage =
                            item.media.disabledReason?.getDisabledMessage(
                                configuration,
                                localizationHelper,
                                resources,
                            )
                        viewModel.handleGridItemSelection(
                            item = item.media,
                            selectionLimitExceededMessage = selectionLimitExceededMessage,
                            selectionBatchSizeLimitExceededMessage =
                                selectionBatchSizeLimitExceededMessage,
                            disabledReasonMessage,
                        )
                        // Log user's interaction with picker's main grid(photo grid)
                        scope.launch {
                            events.dispatch(
                                Event.LogPhotopickerUIEvent(
                                    FeatureToken.PHOTO_GRID.token,
                                    configuration.sessionId,
                                    configuration.callingPackageUid ?: -1,
                                    Telemetry.UiEvent.PICKER_MAIN_GRID_INTERACTION,
                                )
                            )
                        }
                    }
                }

                // Preview Handler for previewing a item. Used either for pinch at max zoom, or long
                // press when media grid gestures are disabled.
                val onPreviewItem = { item: MediaGridItem ->
                    if (isPreviewEnabled) {
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
                        if (item is MediaGridItem.MediaItem) {
                            // Log entry into the photopicker preview mode
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
                }
                val state = rememberMediaGridState()
                val aspectRatio = configuration.getAspectRatioForMediaItemGrids().ratio
                mediaGrid(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    items = items,
                    isExpandedScreen = isExpandedScreen,
                    selection = selection,
                    aspectRatio = aspectRatio,
                    dragSelectionEnabled = configuration.selectionLimit > 1,
                    /* index offset for banner and highlight content */
                    dragSelectIndexOffset = 2,
                    bannerContent = {
                        hideWhenState(selector = bannerContentSelector) {
                            AnimatedBanner(
                                currentBanner,
                                modifier = Modifier.padding(MEASUREMENT_BANNER_PADDING),
                                onDismiss = { banner ->
                                    // Coerce the type back to [BannerDefinitions]
                                    // so that it can be dismissed.
                                    if (configuration.flags.PICKER_BANNER_REDESIGN_ENABLED) {
                                        val bannerDefinition = banner.bannerDefinition
                                        viewModel.markBannerDefinitionAsDismissed(bannerDefinition)
                                    } else {
                                        val declaration = banner.declaration
                                        if (declaration is BannerDefinitions) {
                                            viewModel.markBannerAsDismissed(declaration)
                                        }
                                    }
                                },
                            )
                        }
                    },
                    highlightMediaContent = {
                        hideWhenState(selector = highlightContentSelector) {
                            featureManager.composeLocation(
                                Location.HIGHLIGHT_MEDIA_CAROUSEL,
                                maxSlots = 1,
                            )
                        }
                    },
                    cameraEntryPointContent =
                        if (isCameraAvailable?.value == true) {
                            {
                                featureManager.composeLocation(
                                    Location.CAMERA_ENTRY_POINT,
                                    maxSlots = 1,
                                )
                            }
                        } else {
                            // We need to return null if camera button should not be shown to avoid
                            // lazy grid reserving the first square in media grid for the camera
                            // button.
                            null
                        },
                    pinchToZoomEnabled = true,
                    onZoomAtMaxZoom = onPreviewItem,
                    onItemClick = onItemClick,
                    initialColumns = cellsPerRow,
                    selectionTransform = {
                        Media.withSelectable(
                            item = it,
                            selectionSource = Telemetry.MediaLocation.MAIN_GRID,
                            album = null,
                        )
                    },
                    arePlaceholdersEnabled = viewModel.ARE_PLACEHOLDERS_ENABLED,
                )
                PhotoGridDateScrubber(featureManager, photoGridBoxHeight, state.gridState)
                LaunchedEffect(Unit) {
                    // Log loading of photos in the photo grid
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.PHOTO_GRID.token,
                            configuration.sessionId,
                            configuration.callingPackageUid ?: -1,
                            Telemetry.UiEvent.UI_LOADED_PHOTOS,
                        )
                    )
                }
            }
        }
    }
}

/** The date scrubber for the main photo grid. Composable for [Location.DATE_SCRUBBER] */
@Composable
fun BoxScope.PhotoGridDateScrubber(
    featureManager: FeatureManager,
    parentHeight: State<Float>,
    gridState: LazyGridState,
) {
    featureManager.composeLocation(
        Location.DATE_SCRUBBER,
        maxSlots = 1,
        modifier = Modifier.align(Alignment.CenterEnd),
        params =
            object : LocationParams.WithDateScrubber {
                override val parentHeight = parentHeight
                override val gridState = gridState
            },
    )
}

/**
 * The navigation button for the main photo grid. Composable for
 * [Location.NAVIGATION_BAR_NAV_BUTTON]
 */
@Composable
fun PhotoGridNavButton(
    modifier: Modifier,
    params: LocationParams,
    iconModifier: Modifier = Modifier,
) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val events = LocalEvents.current
    val configuration = LocalPhotopickerConfiguration.current
    val isVideoOnlyMimeType = LocalPhotopickerConfiguration.current.hasOnlyVideoMimeTypes()
    val buttonText =
        if (isVideoOnlyMimeType) stringResource(R.string.photopicker_videos_nav_button_label)
        else stringResource(R.string.photopicker_photos_nav_button_label)
    val showButtonIcon = params as? LocationParams.WithNavButtonIcon
    val selectActionLabel = stringResource(R.string.photopicker_select_action_description)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isCurrentRouteSelected = currentRoute == PhotopickerDestinations.PHOTO_GRID.route

    val onTabClick: () -> Unit = {
        // Log switching tab to the photos tab
        scope.launch {
            events.dispatch(
                Event.LogPhotopickerUIEvent(
                    FeatureToken.PHOTO_GRID.token,
                    configuration.sessionId,
                    configuration.callingPackageUid ?: -1,
                    Telemetry.UiEvent.SWITCH_PICKER_TAB,
                )
            )
        }
        navController.navigateToPhotoGrid()
    }

    NavigationBarButton(
        onClick = onTabClick,
        modifier =
            modifier.clearAndSetSemantics {
                role = Role.Tab
                selected = isCurrentRouteSelected
                collectionItemInfo =
                    CollectionItemInfo(rowIndex = 0, rowSpan = 1, columnIndex = 0, columnSpan = 1)
                contentDescription = buttonText
                onClick(
                    // Providing a custom label here changes the TalkBack usage hint.
                    // This makes TalkBack announce "Double tap to Select" instead of the default
                    // "Double tap to Activate".
                    label = selectActionLabel,
                    action = {
                        onTabClick()
                        true
                    },
                )
            },
        isCurrentRouteSelected = isCurrentRouteSelected,
    ) {
        when (showButtonIcon?.showButtonIcon()) {
            true -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector =
                            if (isVideoOnlyMimeType) Icons.Outlined.PlayCircle
                            else Icons.Outlined.Image,
                        // Set this to null to prevent double announcement
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).then(iconModifier),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = buttonText,
                        maxLines = 1, // Limit the text to a single line
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            else ->
                Text(
                    text = buttonText,
                    maxLines = 1, // Limit the text to a single line
                    overflow = TextOverflow.Ellipsis,
                )
        }
    }
}
