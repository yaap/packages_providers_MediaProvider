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

package com.android.photopicker.features.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.StateSelector
import com.android.photopicker.core.animations.standardDecelerate
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
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.glide.GlideLoadable
import com.android.photopicker.core.glide.Resolution
import com.android.photopicker.core.glide.loadMedia
import com.android.photopicker.core.hideWhenState
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.obtainViewModel
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.theme.LocalWindowSizeClass
import com.android.photopicker.data.model.Icon
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.SelectionDisabledReason
import com.android.photopicker.extensions.fadingEdge
import com.android.photopicker.extensions.navigateToPreviewMedia
import com.android.photopicker.extensions.transferScrollableTouchesToHostInEmbedded
import com.android.photopicker.features.preview.PreviewFeature
import com.android.photopicker.features.search.model.SearchSuggestion
import com.android.photopicker.features.search.model.SearchSuggestionType
import com.android.photopicker.features.search.model.UserSearchState
import com.android.photopicker.util.LocalLocalizationHelper
import com.android.photopicker.util.applyChoice
import com.android.photopicker.util.applyWhen
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MEASUREMENT_SEARCH_BAR_PADDING = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)

private val FETCH_SUGGESTION_DEBOUNCE_DELAY = 50L // in milliseconds

private val SUGGESTION_TITLE_ROW_PADDING =
    PaddingValues(start = 16.dp, end = 32.dp, top = 12.dp, bottom = 12.dp)
private val MEASUREMENT_LARGE_PADDING = 16.dp
private val MEASUREMENT_MEDIUM_PADDING = 8.dp
private val MEASUREMENT_SMALL_PADDING = 4.dp
private val MEASUREMENT_EXTRA_SMALL_PADDING = 2.dp

private val MEASUREMENT_SUGGESTION_ITEM_PADDING =
    PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)

private val CARD_CORNER_RADIUS_LARGE = 28.dp
private val CARD_CORNER_RADIUS_SMALL = 4.dp
private val TOP_SUGGESTION_CARD_SHAPE =
    RoundedCornerShape(
        topStart = CARD_CORNER_RADIUS_LARGE,
        topEnd = CARD_CORNER_RADIUS_LARGE,
        bottomStart = CARD_CORNER_RADIUS_SMALL,
        bottomEnd = CARD_CORNER_RADIUS_SMALL,
    )
private val BOTTOM_SUGGESTION_CARD_SHAPE =
    RoundedCornerShape(
        topStart = CARD_CORNER_RADIUS_SMALL,
        topEnd = CARD_CORNER_RADIUS_SMALL,
        bottomStart = CARD_CORNER_RADIUS_LARGE,
        bottomEnd = CARD_CORNER_RADIUS_LARGE,
    )
private val MIDDLE_SUGGESTION_CARD_SHAPE = RoundedCornerShape(CARD_CORNER_RADIUS_SMALL)
private val SINGLE_SUGGESTION_CARD_SHAPE = RoundedCornerShape(CARD_CORNER_RADIUS_LARGE)

private val MEASUREMENT_FACE_SUGGESTION_ICON = 48.dp
private val MEASUREMENT_FACE_RESULT_ICON = 32.dp
private val MEASUREMENT_OTHER_ICON = 40.dp

private val HIGHLIGHT_TOOLTIP_ELEVATION_MEASURE = 2.dp
private val TOOLTIP_CONTENT_HORIZONTAL_PADDING = 12.dp
private val TOOLTIP_CONTENT_VERTICAL_PADDING = 8.dp
private val TOOLTIP_ROUNDED_CORNERS_MEASURE = 20.dp
private val TOOLTIP_WIDTH = 250.dp
private val MEASUREMENT_RESULTS_BANNER_PADDING =
    PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)

private val MEASUREMENT_SUGGESTION_BANNER_PADDING =
    PaddingValues(start = 2.dp, end = 2.dp, top = 8.dp, bottom = 16.dp)

/** A composable function that displays a SearchBar. */
@Composable
fun Search(
    modifier: Modifier = Modifier,
    params: LocationParams,
    viewModel: SearchViewModel = obtainViewModel(isActivityScoped = true),
) {
    val userSearchStateInfo by viewModel.userSearchStateInfo.collectAsStateWithLifecycle()
    when {
        userSearchStateInfo.state == UserSearchState.ENABLED -> {
            SearchBarEnabled(params, viewModel, modifier)
        }
        else -> {
            SearchBarWithTooltip(modifier)
        }
    }
}

/**
 * This composable displays an enabled search bar that allows users to enter search queries.
 *
 * @param params A [LocationParams] relevant to the search functionality.
 * @param viewModel The `SearchViewModel` providing the search logic and state.
 * @param modifier The modifier to be applied to the composable.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SearchBarEnabled(params: LocationParams, viewModel: SearchViewModel, modifier: Modifier) {
    val focused by viewModel.searchBarFocusedState.collectAsStateWithLifecycle()
    val searchTerm by viewModel.searchBarTextState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val suggestionLists by viewModel.searchSuggestions.collectAsStateWithLifecycle()
    val searchableProviders by viewModel.searchableProviders.collectAsStateWithLifecycle()
    val providerToIconMap by viewModel.providerToIconMap.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val events = LocalEvents.current
    val configuration = LocalPhotopickerConfiguration.current

    SearchBar(
        inputField = {
            SearchInputContent(
                viewModel = viewModel,
                focused = focused,
                searchTerm = searchTerm,
                onFocused = {
                    if (it) {
                        val clickAction = params as? LocationParams.WithClickAction
                        clickAction?.onClick()
                        scope.launch {
                            events.dispatch(
                                Event.LogPhotopickerUIEvent(
                                    FeatureToken.SEARCH.token,
                                    configuration.sessionId,
                                    configuration.callingPackageUid ?: -1,
                                    Telemetry.UiEvent.ENTER_PICKER_SEARCH,
                                )
                            )
                        }
                    }
                    viewModel.setSearchBarFocusedState(it)
                },
                onSearchQueryChanged = {
                    viewModel.setSearchBarText(it)
                    viewModel.clearSearch()
                },
                searchState = searchState,
                modifier,
            )
        },
        expanded = focused,
        onExpandedChange = { viewModel.setSearchBarFocusedState(it) },
        colors =
            SearchBarDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                dividerColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            if (focused) {
                Modifier.fillMaxWidth()
            } else {
                modifier.padding(MEASUREMENT_SEARCH_BAR_PADDING)
            },
        content = {
            when (searchState) {
                is SearchState.Active -> {
                    val searchResults =
                        remember(searchState) {
                            try {
                                viewModel.getSearchResults()
                            } catch (e: IllegalStateException) {
                                // If search state is inactive fetching search results would throw
                                // an IllegalStateException but this is not expected to ever happen
                                // as search results will be called only for active search states.
                                Log.e(
                                    SearchFeature.TAG,
                                    " Cannot show search results in inactive search state",
                                    e,
                                )
                                flow { PagingData.from(emptyList()) }
                            }
                        }
                    ResultMediaGrid(searchResults)
                }

                SearchState.Inactive -> {
                    if (suggestionLists.totalSuggestions > 0) {
                        val focusManager = LocalFocusManager.current
                        val cloudProvider =
                            searchableProviders.singleOrNull()?.takeIf {
                                it.mediaSource == MediaSource.REMOTE
                            }

                        Suggestions(
                            searchSuggestions = suggestionLists,
                            isZeroSearchState = searchTerm.isEmpty(),
                            onSuggestionClick = { suggestion ->
                                focusManager.clearFocus()
                                viewModel.setSearchBarText(suggestion.displayText ?: "")
                                viewModel.performSearch(suggestion = suggestion)
                            },
                            onDeleteSuggestion = { suggestion ->
                                viewModel.removeSearchHistory(suggestion)
                            },
                            modifier = modifier,
                            cloudProviderIcon = providerToIconMap.getOrDefault(cloudProvider, null),
                            cloudProviderName = cloudProvider?.displayName,
                        )
                    }
                }
            }
        },
    )
    LaunchedEffect(key1 = searchTerm) { // Trigger when searchTerm changes
        delay(FETCH_SUGGESTION_DEBOUNCE_DELAY)
        viewModel.fetchSuggestions(searchTerm)
    }
}

/**
 * This composable displays a disabled search bar with a tooltip that appears when the user clicks
 * over it.
 *
 * @param modifier The modifier to be applied to the composable.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SearchBarWithTooltip(modifier: Modifier) {
    val tooltipState = rememberTooltipState()
    val scope = rememberCoroutineScope()
    // Applying here the passed modifier to Box allowing the caller of the SearchBarWithTooltip
    // function to control the appearance and layout of the entire search bar and tooltip unit so
    // that the tooltip with disabled search bar can be modified to fill width of the box.
    Box(modifier = modifier) {
        TooltipBox(
            positionProvider =
                remember {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset {
                            return IntOffset(
                                x =
                                    anchorBounds.left +
                                        (anchorBounds.width - popupContentSize.width) / 2,
                                y = anchorBounds.bottom - popupContentSize.height,
                            )
                        }
                    }
                },
            tooltip = {
                PlainTooltip {
                    Text(text = stringResource(R.string.photopicker_search_disabled_hint))
                }
            },
            state = tooltipState,
        ) {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = "",
                        enabled = false,
                        placeholder = { SearchBarPlaceHolder(false) },
                        colors = TextFieldDefaults.colors(MaterialTheme.colorScheme.surface),
                        onQueryChange = {},
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        leadingIcon = { SearchBarIcon(false, {}, {}, searchDisabled = true) },
                        modifier = Modifier.clickable { scope.launch { tooltipState.show() } },
                    )
                },
                expanded = false,
                onExpandedChange = {},
                colors =
                    SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        dividerColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                modifier = Modifier.fillMaxWidth().padding(MEASUREMENT_SEARCH_BAR_PADDING),
                content = {},
            )
        }
    }
}

/**
 * Renders the appropriate search input field based on the current search state.
 *
 * This composable function determines which type of search input to display depending on whether
 * the user has selected a "FACE" type suggestion or is performing a regular text search.
 *
 * @param viewModel The `SearchViewModel` providing the search logic and state.
 * @param focused A boolean value indicating if the search input field is currently focused.
 * @param searchTerm Current text entered in search input field.
 * @param onFocused A callback function to be invoked when the focus state of the search field
 *   changes.
 * @param onSearchQueryChanged A callback function to be invoked when the search query text changes.
 * @param searchState The current state of the search, determining the input field type.
 * @param modifier A Modifier that can be applied to the SearchInputContent composable.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SearchInputContent(
    viewModel: SearchViewModel,
    focused: Boolean,
    searchTerm: String,
    onFocused: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    searchState: SearchState,
    modifier: Modifier,
) {
    // BackHandler to intercept the system back button press when focused
    BackHandler(enabled = focused) {
        onFocused(false)
        onSearchQueryChanged("")
    }
    when (
        focused &&
            searchState is SearchState.Active.SuggestionSearch &&
            searchState.suggestion.type == SearchSuggestionType.FACE
    ) {
        true -> {
            ShowSearchInputWithCustomIcon(searchState.suggestion, onFocused, onSearchQueryChanged)
        }
        else -> {
            SearchInput(
                searchQuery = searchTerm,
                focused = focused,
                onSearchQueryChanged = { onSearchQueryChanged(it) },
                onFocused = {
                    onFocused(it)
                    if (it) {
                        viewModel.clearSearch()
                    }
                },
                onSearch = { viewModel.performSearch(query = searchTerm) },
                modifier = modifier,
            )
        }
    }
}

/**
 * A composable function that displays a search input field within a SearchBar.
 *
 * This component provides a text field for entering search queries It also handles focus state and
 * provides callbacks for search query changes and focus changes.
 *
 * @param searchQuery The current text entered in search bar input field.
 * @param focused A boolean value indicating whether the search input field is currently focused.
 * @param onSearchQueryChanged A callback function that is invoked when the search query text
 *   changes. This function receives the updated search query as a parameter.
 * @param onFocused A callback function that is invoked when the focus state of the search field
 *   changes. This function receives a boolean value indicating the new focus state.
 * @param onSearch A callback function to be invoked when a text is searched.
 * @param modifier A Modifier that can be applied to the SearchInput composable to customize its
 *   appearance and behavior.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SearchInput(
    searchQuery: String,
    focused: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onFocused: (Boolean) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    SearchBarDefaults.InputField(
        query = searchQuery,
        placeholder = { SearchBarPlaceHolder(focused) },
        colors =
            TextFieldDefaults.colors(
                unfocusedContainerColor =
                    if (focused) {
                        MaterialTheme.colorScheme.surfaceContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        onQueryChange = onSearchQueryChanged,
        onSearch = {
            onFocused(true)
            focusManager.clearFocus()
            onSearch()
        },
        expanded = focused,
        onExpandedChange = onFocused,
        leadingIcon = { SearchBarIcon(focused, onFocused, onSearchQueryChanged) },
        trailingIcon = SearchBarTrailingIcon(focused, searchQuery, onSearchQueryChanged),
        modifier = modifier.focusRequester(focusRequester),
    )
    RequestFocusOnResume(focusRequester = focusRequester, focused)
}

/**
 * A composable function that displays the trailing icon in a SearchBar.
 *
 * The icon changes based on the search query and focus state. It shows a voice input icon when the
 * query is empty and a clear icon when there is text. These icons are only shown when the search
 * bar is focused and in an inactive search state.
 *
 * This returns a composable lambda containing the icon if it should be visible, and `null`
 * otherwise. This `null` return is critical, as it signals to the parent composable not to reserve
 * any layout space for the icon.
 *
 * @param focused A boolean value indicating whether the search bar is in focus.
 * @param searchQuery The current search query string.
 * @param onSearchQueryChanged A callback function to update the search query.
 * @param viewModel The `SearchViewModel` providing the search logic and state.
 */
@Composable
private fun SearchBarTrailingIcon(
    focused: Boolean,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    viewModel: SearchViewModel = obtainViewModel(),
): (@Composable () -> Unit)? {
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()

    return if (focused && searchState is SearchState.Inactive) {
        {
            when (searchQuery.isEmpty()) {
                true -> {
                    val isEmbedded =
                        LocalPhotopickerConfiguration.current.runtimeEnv ==
                            PhotopickerRuntimeEnv.EMBEDDED
                    if (!isEmbedded) {
                        VoiceSearchIcon { spokenText ->
                            onSearchQueryChanged(spokenText)
                            viewModel.performSearch(query = spokenText)
                        }
                    }
                }
                false -> {
                    ClearSearchQueryIcon { onSearchQueryChanged("") }
                }
            }
        }
    } else {
        null
    }
}

/**
 * A composable that displays a microphone icon to initiate voice search.
 *
 * It handles launching the speech recognizer intent and returns the result via a callback.
 *
 * @param onResult A callback that provides the recognized spoken text.
 */
@Composable
private fun VoiceSearchIcon(onResult: (String) -> Unit) {
    val speechRecognizerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK) {
                val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val confidenceScores =
                    result.data?.getFloatArrayExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES)
                var spokenText: String? = null
                if (!results.isNullOrEmpty()) {
                    if (confidenceScores != null && confidenceScores.size == results.size) {
                        var highestConfidence = -1.0f
                        var bestResultIndex = 0
                        for (i in confidenceScores.indices) {
                            if (confidenceScores[i] > highestConfidence) {
                                highestConfidence = confidenceScores[i]
                                bestResultIndex = i
                            }
                        }
                        spokenText = results[bestResultIndex]
                    } else {
                        // Fallback to the first result if confidence scores are not available
                        spokenText = results[0]
                    }
                }
                if (!spokenText.isNullOrEmpty()) {
                    onResult(spokenText)
                }
            }
        }

    IconButton(
        onClick = {
            val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                }
            try {
                speechRecognizerLauncher.launch(intent)
            } catch (e: Exception) {
                Log.w(SearchFeature.TAG, "Failed to launch speech recognition activity.", e)
            }
        }
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription =
                stringResource(R.string.photopicker_search_voice_search_button_description),
        )
    }
}

/**
 * A composable that displays a close (X) icon to clear the current search query.
 *
 * @param onClear A callback function to be invoked when the icon is clicked.
 */
@Composable
private fun ClearSearchQueryIcon(onClear: () -> Unit) {
    IconButton(onClick = onClear) {
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.photopicker_search_clear_text),
        )
    }
}

@Composable
private fun RequestFocusOnResume(
    focusRequester: FocusRequester,
    focused: Boolean,
    viewModel: SearchViewModel = obtainViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        when (focused && searchState is SearchState.Inactive) {
            true ->
                lifecycleOwner.repeatOnLifecycle(state = Lifecycle.State.RESUMED) {
                    focusRequester.requestFocus()
                }
            false -> {}
        }
    }
}

/**
 * Composable that shows an Icon and Title if present in Search Results page on selecting Face type
 * from suggestions list.
 */

/**
 * Composable that displays a search input field with a custom icon and text.
 *
 * This composable function renders a search input field in a row that includes:
 * - A leading icon
 * - A custom icon representing a search suggestion.
 * - Text displaying the suggestion's display text.
 *
 * @param suggestion The `SearchSuggestion` object to be displayed.
 * @param onFocused A callback function to be invoked when the focus state of the search field
 *   changes.
 * @param onSearchQueryChanged A callback function to be invoked when the search query text changes.
 */
@Composable
fun ShowSearchInputWithCustomIcon(
    suggestion: SearchSuggestion,
    onFocused: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
) {
    Row(
        modifier = Modifier.padding(MEASUREMENT_SMALL_PADDING).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBarIcon(
            true,
            onFocused = onFocused,
            onSearchQueryChanged = { onSearchQueryChanged("") },
        )
        SuggestionIcon(
            suggestion,
            modifier = Modifier.clip(CircleShape).size(MEASUREMENT_FACE_RESULT_ICON),
        )
        Text(
            text = suggestion.displayText ?: "",
            modifier = Modifier.padding(start = MEASUREMENT_MEDIUM_PADDING),
        )
    }
}

/**
 * A composable function that displays the leading icon in a SearchBar. The icon changes based on
 * the focused state of the SearchBar.
 *
 * @param focused A boolean value indicating whether search input field of search bar is currently
 *   focused.
 * @param onFocused A callback function that is invoked when the focus state of the search field
 *   changes.
 *     * This function receives a boolean value indicating the new focus state.
 *
 * @param onSearchQueryChanged A callback function that is invoked when the search query text
 *   changes.
 *     * This function receives the updated search query as a parameter.
 *
 * @param searchDisabled A boolean value indicating whether the search bar is disabled.
 */
@Composable
private fun SearchBarIcon(
    focused: Boolean,
    onFocused: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    searchDisabled: Boolean = false,
) {
    if (focused) {
        IconButton(
            onClick = {
                onFocused(false)
                onSearchQueryChanged("")
            }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.photopicker_back_option),
            )
        }
    } else {
        val description =
            when (searchDisabled) {
                true -> {
                    stringResource(R.string.photopicker_search_disabled_hint)
                }
                else -> {
                    stringResource(R.string.photopicker_search_placeholder_text)
                }
            }
        Icon(imageVector = Icons.Outlined.Search, contentDescription = description)
    }
}

/**
 * A composable function that displays a placeholder text in the search bar.
 *
 * The placeholder text changes depending on the search provider and whether the search bar is
 * focused. The text includes the name of the cloud provider if it is the only available search
 * provider. When local search is available (either alone or with a cloud provider) and the search
 * bar is in a focused state, it also considers the allowed MIME types from the
 * `LocalPhotopickerConfiguration` to display a more specific placeholder.
 *
 * @param focused Whether the search bar currently has focus.
 * @param viewModel The search view model that provides the list of searchable providers.
 */
@Composable
private fun SearchBarPlaceHolder(focused: Boolean, viewModel: SearchViewModel = obtainViewModel()) {
    val searchableProviders by viewModel.searchableProviders.collectAsStateWithLifecycle()
    // If cloud provider is the only search provider
    val isSearchProvidedByCloudProvider =
        searchableProviders.size == 1 && searchableProviders[0].mediaSource == MediaSource.REMOTE
    val placeholderText =
        if (focused) {
            when {
                isSearchProvidedByCloudProvider ->
                    stringResource(
                        R.string.photopicker_search_with_provider_placeholder_text,
                        searchableProviders[0].displayName,
                    )
                LocalPhotopickerConfiguration.current.hasOnlyVideoMimeTypes() ->
                    stringResource(R.string.photopicker_search_videos_placeholder_text)
                else -> stringResource(R.string.photopicker_search_photos_placeholder_text)
            }
        } else {
            when {
                isSearchProvidedByCloudProvider ->
                    stringResource(
                        R.string.photopicker_search_provider_placeholder_text,
                        searchableProviders[0].displayName,
                    )
                else -> stringResource(R.string.photopicker_search_placeholder_text)
            }
        }

    // State to track if the text is overflowing.
    var isOverflowing by remember { mutableStateOf(false) }

    Text(
        text = placeholderText,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
        modifier =
            Modifier.fillMaxWidth()
                .applyWhen(
                    condition = isOverflowing,
                    block = { fadingEdge(color = MaterialTheme.colorScheme.surfaceContainer) },
                ),
        // The onTextLayout callback gets called after layout and provides the layout result.
        onTextLayout = { textLayoutResult -> isOverflowing = textLayoutResult.didOverflowWidth },
    )
}

/**
 * This composable displays an empty state screen when there are no results.
 *
 * @param modifier Modifier used to adjust the layout or styling of the composable.
 */
@Composable
fun EmptySearchResult(
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = obtainViewModel(),
) {
    val localConfig = LocalConfiguration.current
    val emptyStatePadding = remember(localConfig) { (localConfig.screenHeightDp * .20).dp }

    val searchableProviders by viewModel.searchableProviders.collectAsStateWithLifecycle()
    val isCloudOnly =
        searchableProviders.size == 1 && searchableProviders[0].mediaSource == MediaSource.REMOTE
    val bodyText =
        if (isCloudOnly) {
            stringResource(
                R.string.photopicker_search_result_empty_state_message_when_local_search_disabled
            )
        } else {
            stringResource(
                R.string.photopicker_search_result_empty_state_message_when_local_search_enabled
            )
        }

    EmptyState(
        modifier = modifier.fillMaxWidth().padding(top = emptyStatePadding),
        icon = Icons.Outlined.HideImage,
        title = stringResource(R.string.photopicker_search_result_empty_state_title),
        body = bodyText,
    )
}

/**
 * Composable function that shows suggestion in the search view.
 *
 * @param searchSuggestions A `SearchSuggestions` object containing the different types of
 *   suggestions to be displayed.
 * @param isZeroSearchState A boolean value indicating if the search query is empty.
 * @param modifier A Modifier that can be applied to the suggestions list.
 * @param cloudProviderIcon [Icon] of the cloud provider providing the suggestions
 * @param cloudProviderName Name of the cloud provider providing the suggestions
 * @param onSuggestionClick A callback function to be invoked when a suggestion is clicked.
 * @param onDeleteSuggestion A callback function to be invoked when a history suggestion is
 *   long-pressed.
 */
@Composable
private fun Suggestions(
    searchSuggestions: SearchSuggestions,
    isZeroSearchState: Boolean,
    modifier: Modifier,
    cloudProviderIcon: Icon?,
    cloudProviderName: String?,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    onDeleteSuggestion: (SearchSuggestion) -> Unit,
    viewModel: SearchViewModel = obtainViewModel(isActivityScoped = true),
) {
    val isEmbedded =
        LocalPhotopickerConfiguration.current.runtimeEnv == PhotopickerRuntimeEnv.EMBEDDED
    val host = LocalEmbeddedState.current?.host
    val isExpanded = rememberUpdatedState(LocalEmbeddedState.current?.isExpanded ?: false)
    val events = LocalEvents.current
    val configuration = LocalPhotopickerConfiguration.current

    val currentBanner by viewModel.getBanners().collectAsStateWithLifecycle()
    val bannerContentSelector =
        object : StateSelector.AnimatedVisibilityInEmbedded {
            override val visible = LocalEmbeddedState.current?.isExpanded ?: false
            override val enter = expandVertically(animationSpec = standardDecelerate(300))
            override val exit = shrinkVertically(animationSpec = standardDecelerate(150))
        }

    val historySuggestions = searchSuggestions.history
    val faceSuggestions = searchSuggestions.face
    val otherSuggestions = searchSuggestions.other

    val state = rememberLazyListState()

    Box(modifier = modifier.padding(MEASUREMENT_LARGE_PADDING)) {
        LazyColumn(
            modifier =
                if (SdkLevel.isAtLeastU() && isEmbedded && host != null) {
                    modifier.transferScrollableTouchesToHostInEmbedded(state, isExpanded, host)
                } else {
                    modifier
                },
            state = state,
        ) {
            if (configuration.flags.PICKER_OFFLINE_BANNERS_ENABLED && currentBanner != null) {
                item {
                    hideWhenState(selector = bannerContentSelector) {
                        AnimatedBanner(
                            currentBanner,
                            Modifier.padding(MEASUREMENT_SUGGESTION_BANNER_PADDING),
                        )
                    }
                }
            } else {
                item { Spacer(modifier = Modifier.height(MEASUREMENT_MEDIUM_PADDING)) }
            }
            items(historySuggestions.take(SearchViewModel.HISTORY_SUGGESTION_MAX_LIMIT)) {
                suggestion ->
                val size =
                    minOf(historySuggestions.size, SearchViewModel.HISTORY_SUGGESTION_MAX_LIMIT)
                SuggestionCard(
                    suggestion,
                    historySuggestions.indexOf(suggestion),
                    size,
                    faceSuggestions.size,
                    otherSuggestions.size,
                    onSuggestionClick,
                    onDeleteSuggestion = onDeleteSuggestion,
                )
            }
            if (faceSuggestions.isNotEmpty() || otherSuggestions.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.padding(SUGGESTION_TITLE_ROW_PADDING),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        cloudProviderIcon?.let {
                            loadMedia(
                                media = cloudProviderIcon as GlideLoadable,
                                resolution = Resolution.THUMBNAIL,
                                modifier = Modifier.size(24.dp).clip(CircleShape),
                                contentDescription = cloudProviderName,
                            )
                        }
                        Spacer(
                            modifier =
                                // Using applyChoice directly on Modifier causes compile time error
                                Modifier.semantics {}
                                    .applyChoice(
                                        condition = cloudProviderIcon != null,
                                        trueBlock = { width(MEASUREMENT_MEDIUM_PADDING) },
                                        // If the icon is absent, padding from left should add up to
                                        // 32.dp
                                        falseBlock = { width(MEASUREMENT_LARGE_PADDING) },
                                    )
                        )
                        Text(text = stringResource(R.string.photopicker_search_suggestions_text))
                    }
                }
            }
            if (faceSuggestions.size > 0) {
                item {
                    ShowFaceSuggestions(faceSuggestions, onSuggestionClick, otherSuggestions.size)
                }
            }
            items(otherSuggestions.take(SearchViewModel.ALL_SUGGESTION_MAX_LIMIT)) { suggestion ->
                val size = minOf(otherSuggestions.size, SearchViewModel.ALL_SUGGESTION_MAX_LIMIT)
                SuggestionCard(
                    suggestion,
                    otherSuggestions.indexOf(suggestion),
                    size,
                    faceSuggestions.size,
                    otherSuggestions.size,
                    onSuggestionClick,
                    isZeroSearchState,
                    onDeleteSuggestion,
                )
            }
        }
        LaunchedEffect(Unit) {
            events.dispatch(
                Event.LogPhotopickerUIEvent(
                    FeatureToken.SEARCH.token,
                    configuration.sessionId,
                    configuration.callingPackageUid ?: -1,
                    Telemetry.UiEvent.UI_LOADED_SEARCH_SUGGESTIONS,
                )
            )
        }
    }
}

/**
 * Composable that displays a suggestion card within a search suggestion list.
 *
 * @param suggestion The search suggestion data to display in the card.
 * @param index The index of this suggestion within the list of suggestions.
 * @param size The total number of suggestions in the list.
 * @param faceTypeCount The number of suggestions of type FACE in the list.
 * @param otherTypeCount The number of suggestions of other type in the list.
 * @param onSuggestionClick Callback function to be invoked when the suggestion card is clicked. It
 *   receives the clicked [SearchSuggestion] as a parameter.
 * @param isZeroSearchState A boolean flag indicating whether the search is in a "zero state" (e.g.,
 *   no search term entered yet). Defaults to `false`.
 * @param onDeleteSuggestion Callback function to be invoked when a history suggestion card is
 *   long-pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionCard(
    suggestion: SearchSuggestion,
    index: Int,
    size: Int,
    faceTypeCount: Int,
    otherTypeCount: Int,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    isZeroSearchState: Boolean = false,
    onDeleteSuggestion: (SearchSuggestion) -> Unit,
) {
    val config = LocalPhotopickerConfiguration.current
    val cardShape =
        getCardShape(index, size, suggestion.type, faceTypeCount, otherTypeCount, isZeroSearchState)
    if (
        config.flags.PICKER_DELETE_HISTORY_SUGGESTION &&
            suggestion.type == SearchSuggestionType.HISTORY
    ) {
        DeletableSuggestionCard(
            suggestion = suggestion,
            shape = cardShape,
            onSuggestionClick = onSuggestionClick,
            onDeleteSuggestion = onDeleteSuggestion,
        )
    } else {
        Card(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(MEASUREMENT_EXTRA_SMALL_PADDING)
                    .clickable(onClick = { onSuggestionClick(suggestion) }),
            shape = cardShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            SuggestionItem(suggestion)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletableSuggestionCard(
    suggestion: SearchSuggestion,
    shape: Shape,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    onDeleteSuggestion: (SearchSuggestion) -> Unit,
) {

    val config = LocalPhotopickerConfiguration.current
    if (!config.flags.PICKER_DELETE_HISTORY_SUGGESTION) {
        return
    }
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Position provider to place the tooltip below the anchor, aligned to the left.
    val positionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // A small spacing between the anchor and the tooltip.
                val spacing = with(density) { 8.dp.toPx() }.toInt()

                // Position the tooltip below the anchor card.
                val y = anchorBounds.top - with(density) { 32.dp.toPx() }.toInt()

                // Align the left edge of the tooltip with the left edge of the anchor.
                val x = anchorBounds.left + spacing

                return IntOffset(x, y)
            }
        }
    }
    TooltipBox(
        positionProvider = positionProvider,
        tooltip = {
            PlainTooltip(
                // This adds the caret(the small arrow pointing to the anchor button)
                // to the tooltip.
                caretShape = TooltipDefaults.caretShape(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(TOOLTIP_ROUNDED_CORNERS_MEASURE),
                modifier = Modifier.width(TOOLTIP_WIDTH).height(48.dp),
                tonalElevation = HIGHLIGHT_TOOLTIP_ELEVATION_MEASURE,
                shadowElevation = HIGHLIGHT_TOOLTIP_ELEVATION_MEASURE,
            ) {
                val deleteDescription =
                    stringResource(R.string.photopicker_history_suggestion_delete_text)
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.semantics(mergeDescendants = true) {
                                contentDescription = deleteDescription
                            }
                            .clickable {
                                onDeleteSuggestion(suggestion)
                                scope.launch { tooltipState.dismiss() }
                            },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.photopicker_history_suggestion_delete_text),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.clearAndSetSemantics {}
                                .padding(
                                    horizontal = TOOLTIP_CONTENT_HORIZONTAL_PADDING,
                                    vertical = TOOLTIP_CONTENT_VERTICAL_PADDING,
                                ),
                        fontSize = 14.sp,
                    )
                }
            }
        },
        state = tooltipState,
    ) {
        Card(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(MEASUREMENT_EXTRA_SMALL_PADDING)
                    .combinedClickable(
                        onClick = { onSuggestionClick(suggestion) },
                        onLongClick = { scope.launch { tooltipState.show() } },
                        onLongClickLabel =
                            stringResource(R.string.photopicker_history_suggestion_delete_text),
                    ),
            shape = shape,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            SuggestionItem(suggestion)
        }
    }
}

/**
 * Composable that displays the actual suggestion item within a suggestion card
 *
 * @param suggestion The search suggestion item to display.
 */
@Composable
fun SuggestionItem(suggestion: SearchSuggestion) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(MEASUREMENT_SUGGESTION_ITEM_PADDING),
    ) {
        if (suggestion.type == SearchSuggestionType.FACE) {
            SuggestionIcon(suggestion, Modifier.size(MEASUREMENT_OTHER_ICON).clip(CircleShape))
        } else {
            Box(
                modifier =
                    Modifier.background(MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(6.dp)
            ) {
                Icon(
                    imageVector = getImageVector(suggestion.type),
                    contentDescription = suggestion.displayText ?: "",
                )
            }
        }
        val text = suggestion.displayText ?: ""
        Text(text = text, modifier = Modifier.padding(start = MEASUREMENT_LARGE_PADDING).weight(1f))
        if (
            suggestion.type != SearchSuggestionType.FACE &&
                suggestion.type != SearchSuggestionType.HISTORY &&
                suggestion.icon != null
        ) {
            SuggestionIcon(suggestion, Modifier.size(MEASUREMENT_OTHER_ICON).clip(CircleShape))
        }
    }
}

/**
 * Composable for showing Face suggestion type in the list of search suggestions.
 *
 * @param list The list of `SearchSuggestion` objects of type FACE to be displayed.
 * @param onSuggestionClick A callback function to be invoked when a suggestion is clicked.
 * @param otherTypeCount The number of suggestions of other type in search suggestions list.
 */
@Composable
fun ShowFaceSuggestions(
    list: List<SearchSuggestion>,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    otherTypeCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(MEASUREMENT_EXTRA_SMALL_PADDING),
        shape =
            getCardShape(
                0,
                list.size,
                SearchSuggestionType.FACE,
                faceTypeCount = list.size,
                otherTypeCount = otherTypeCount,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(MEASUREMENT_LARGE_PADDING).fillMaxWidth(),
            horizontalArrangement =
                when {
                    list.size == SearchViewModel.FACE_SUGGESTION_MAX_LIMIT ->
                        Arrangement.SpaceAround
                    else -> Arrangement.spacedBy(8.dp)
                },
        ) {
            list.take(SearchViewModel.FACE_SUGGESTION_MAX_LIMIT).forEach { suggestion ->
                SuggestionIcon(
                    suggestion,
                    modifier =
                        Modifier.size(MEASUREMENT_FACE_SUGGESTION_ICON)
                            .clip(CircleShape)
                            .clickable { onSuggestionClick(suggestion) },
                )
            }
        }
    }
}

/**
 * Composable that displays an icon for a search suggestion
 *
 * Tries to load an image from the `iconUri` provided in the SearchSuggestion` object. If the image
 * loading is successful displays the image as a bitmap else falls back to displaying default vector
 * icon.
 *
 * @param suggestion The `SearchSuggestion` object containing the icon URI and suggestion type.
 * @param modifier Modifiers to be applied to the Icon composable.
 */
@Composable
fun SuggestionIcon(suggestion: SearchSuggestion, modifier: Modifier) {
    val imageDescription = suggestion.displayText ?: ""
    when {
        suggestion.icon != null -> {
            loadMedia(
                media = suggestion.icon,
                resolution = Resolution.THUMBNAIL,
                modifier = modifier.background(MaterialTheme.colorScheme.surface),
            )
        }
        else -> {
            Icon(
                imageVector = getImageVector(suggestion.type),
                contentDescription = imageDescription,
                modifier = modifier,
            )
        }
    }
}

/** Composable for drawing the search results Grid */
@Composable
private fun ResultMediaGrid(
    resultItems: Flow<PagingData<MediaGridItem>>,
    viewModel: SearchViewModel = obtainViewModel(),
) {
    val navController = LocalNavController.current
    val selectionLimit = LocalPhotopickerConfiguration.current.selectionLimit
    val featureManager = LocalFeatureManager.current
    val isPreviewEnabled = remember { featureManager.isFeatureEnabled(PreviewFeature::class.java) }
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
    val items = resultItems.collectAsLazyPagingItems()
    val scope = rememberCoroutineScope()
    val events = LocalEvents.current
    val configuration = LocalPhotopickerConfiguration.current
    val searchGridDescription =
        stringResource(R.string.photopicker_search_results_grid_content_description)

    // Collect the selection to notify the mediaGrid of selection changes.
    val selection by LocalSelection.current.flow.collectAsStateWithLifecycle()

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
                disabledReasonMessage = disabledReasonMessage,
                selectionBatchSizeLimitExceededMessage = selectionBatchSizeLimitExceededMessage,
            )
            scope.launch {
                events.dispatch(
                    Event.LogPhotopickerUIEvent(
                        FeatureToken.SEARCH.token,
                        configuration.sessionId,
                        configuration.callingPackageUid ?: -1,
                        Telemetry.UiEvent.SELECT_SEARCH_RESULT,
                    )
                )
            }
        }
    }

    val onPreviewItem = { item: MediaGridItem ->
        if (isPreviewEnabled) {
            scope.launch {
                events.dispatch(
                    Event.LogPhotopickerUIEvent(
                        FeatureToken.SEARCH.token,
                        configuration.sessionId,
                        configuration.callingPackageUid ?: -1,
                        Telemetry.UiEvent.PICKER_LONG_SELECT_MEDIA_ITEM,
                    )
                )
            }
            if (item is MediaGridItem.MediaItem) {
                scope.launch {
                    events.dispatch(
                        Event.LogPhotopickerUIEvent(
                            FeatureToken.SEARCH.token,
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

    // Use the expanded layout any time the Width is Medium or larger.
    val isExpandedScreen: Boolean =
        when (LocalWindowSizeClass.current.widthSizeClass) {
            WindowWidthSizeClass.Medium,
            WindowWidthSizeClass.Expanded -> true
            else -> false
        }

    val cellsPerRow = remember(isExpandedScreen) { getCellsPerRow(isExpandedScreen) }
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
            withContext(viewModel.backgroundDispatcher) {
                delay(1000)
                if (items.itemCount == 0) {
                    resultsState = ResultsState.LOADING_WITH_INDICATOR
                    delay(10000)
                    if (resultsState == ResultsState.LOADING_WITH_INDICATOR) {
                        scope.launch {
                            events.dispatch(
                                Event.LogPhotopickerUIEvent(
                                    FeatureToken.SEARCH.token,
                                    configuration.sessionId,
                                    configuration.callingPackageUid ?: -1,
                                    Telemetry.UiEvent.UI_LOADED_SEARCH_RESULT_TIMEOUT,
                                )
                            )
                        }
                        resultsState = ResultsState.EMPTY
                    }
                }
            }
        } else if (resultsState != ResultsState.EMPTY) {
            resultsState = ResultsState.RESULTS_GRID
        }
    }
    when (resultsState) {
        ResultsState.EMPTY -> {
            EmptySearchResult()
            LaunchedEffect(Unit) {
                events.dispatch(
                    Event.LogPhotopickerUIEvent(
                        FeatureToken.SEARCH.token,
                        configuration.sessionId,
                        configuration.callingPackageUid ?: -1,
                        Telemetry.UiEvent.UI_LOADED_EMPTY_STATE,
                    )
                )
            }
        }
        ResultsState.LOADING_WITH_INDICATOR -> {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
        ResultsState.RESULTS_GRID -> {
            Box(
                modifier =
                    Modifier.fillMaxSize().semantics { contentDescription = searchGridDescription }
            ) {
                val state = rememberMediaGridState()
                val currentBanner by viewModel.getBanners().collectAsStateWithLifecycle()
                val aspectRatio = configuration.getAspectRatioForMediaItemGrids().ratio
                val bannerContentSelector =
                    object : StateSelector.AnimatedVisibilityInEmbedded {
                        override val visible = LocalEmbeddedState.current?.isExpanded ?: false
                        override val enter =
                            expandVertically(animationSpec = standardDecelerate(300))
                        override val exit =
                            shrinkVertically(animationSpec = standardDecelerate(150))
                    }
                mediaGrid(
                    state = state,
                    items = items,
                    isExpandedScreen = isExpandedScreen,
                    selection = selection,
                    aspectRatio = aspectRatio,
                    bannerContent = {
                        if (configuration.flags.PICKER_OFFLINE_BANNERS_ENABLED) {
                            hideWhenState(selector = bannerContentSelector) {
                                AnimatedBanner(
                                    currentBanner,
                                    Modifier.padding(MEASUREMENT_RESULTS_BANNER_PADDING),
                                )
                            }
                        }
                    },
                    dragSelectionEnabled = configuration.selectionLimit > 1,
                    /* index offset for banner */
                    dragSelectIndexOffset = 1,
                    pinchToZoomEnabled = true,
                    onZoomAtMaxZoom = onPreviewItem,
                    onItemClick = onItemClick,
                    initialColumns = cellsPerRow,
                    selectionTransform = {
                        Media.withSelectable(
                            item = it,
                            selectionSource = Telemetry.MediaLocation.SEARCH_GRID,
                            album = null,
                        )
                    },
                )
            }

            LaunchedEffect(Unit) {
                // Dispatch UI event to log loading of search result contents
                events.dispatch(
                    Event.LogPhotopickerUIEvent(
                        FeatureToken.SEARCH.token,
                        configuration.sessionId,
                        configuration.callingPackageUid ?: -1,
                        Telemetry.UiEvent.UI_LOADED_SEARCH_RESULTS,
                    )
                )
            }
        }
        else -> {}
    }
}

/**
 * Returns Icon based on the suggestion type.
 *
 * @param suggestionType The type of search suggestion
 */
private fun getImageVector(suggestionType: SearchSuggestionType): ImageVector {
    return when (suggestionType) {
        SearchSuggestionType.HISTORY -> {
            Icons.Outlined.History
        }
        SearchSuggestionType.FAVORITES_ALBUM -> {
            Icons.Outlined.StarBorder
        }
        SearchSuggestionType.LOCATION -> {
            Icons.Outlined.LocationOn
        }
        SearchSuggestionType.DATE -> {
            Icons.Filled.Today
        }
        SearchSuggestionType.VIDEOS_ALBUM -> {
            Icons.Outlined.PlayCircle
        }
        SearchSuggestionType.ALBUM -> {
            Icons.Outlined.PhotoAlbum
        }
        SearchSuggestionType.TEXT -> {
            Icons.Outlined.Search
        }
        SearchSuggestionType.SCREENSHOTS_ALBUM -> {
            Icons.Outlined.Smartphone
        }
        else -> {
            Icons.Outlined.Search
        }
    }
}

/**
 * Determines the shape of a card based on its index, size, and suggestion type.
 *
 * This function calculates the appropriate `Shape` for a card in a list, considering:
 * - **index:** The position of the card in the list.
 * - **size:** The total number of cards in the list.
 * - **type:** The type of suggestion the card represents.
 * - **isZeroState:** Indicates whether the search is in a "zero state"
 *
 * @param index The index of the card in the list.
 * @param size The total number of cards in the list.
 * @param type The type of suggestion.
 * @param faceTypeCount The number of suggestions of type FACE in the list.
 * @param otherTypeCount The number of suggestions of other type in the suggestions list.
 * @param isZeroState A boolean flag to indicate whether the search is in a "zero state"
 * @return The calculated `Shape` for the card.
 */
@Composable
private fun getCardShape(
    index: Int,
    size: Int,
    type: SearchSuggestionType,
    faceTypeCount: Int,
    otherTypeCount: Int,
    isZeroState: Boolean = true,
): Shape {
    return when {
        type == SearchSuggestionType.FACE && isZeroState -> {
            if (otherTypeCount > 0) {
                TOP_SUGGESTION_CARD_SHAPE
            } else {
                SINGLE_SUGGESTION_CARD_SHAPE
            }
        }
        type == SearchSuggestionType.HISTORY -> getRoundedCornerShape(index, size)
        else -> {
            if (faceTypeCount == 0) {
                getRoundedCornerShape(index, size)
            } else {
                when (index) {
                    size - 1 -> BOTTOM_SUGGESTION_CARD_SHAPE
                    else -> MIDDLE_SUGGESTION_CARD_SHAPE
                }
            }
        }
    }
}

/**
 * Helper function to calculate the rounded corner shape based on index and size.
 *
 * @param index The position of the item in the list.
 * @param size The total number of items in the list.
 * @return A `Shape` object with the appropriate rounded corners.
 */
private fun getRoundedCornerShape(index: Int, size: Int): Shape {
    return when {
        index == 0 && size == 1 -> SINGLE_SUGGESTION_CARD_SHAPE
        index == 0 -> TOP_SUGGESTION_CARD_SHAPE
        index == size - 1 -> BOTTOM_SUGGESTION_CARD_SHAPE
        else -> MIDDLE_SUGGESTION_CARD_SHAPE
    }
}

/** Represents the different UI states for the search results data. */
enum class ResultsState {
    LOADING_WITHOUT_INDICATOR,
    LOADING_WITH_INDICATOR,
    EMPTY,
    RESULTS_GRID,
}
