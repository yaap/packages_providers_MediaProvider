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

package com.android.photopicker.core.features

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.State
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.preparemedia.PrepareMediaResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/**
 * Parameter interface for passing additional parameters to a [Location]'s implementer via
 * [FeatureManager#composeLocation].
 *
 * By default all Locations receive the None parameter, but this interface can be extended and then
 * location code can cast to the expected type with a pattern such as:
 * ```
 * val clickAction = params as? LocationParams.WithClickAction
 * clickAction?.onClick()
 * ```
 *
 * Or narrow the type using a `when` block. These interfaces can be combined into custom types to
 * ensure compile time type-checking of parameter types. `Any` should not be used to pass
 * parameters.
 */
sealed interface LocationParams {

    /** The default parameters, which represents no additional parameters provided. */
    object None : LocationParams

    /**
     * A generic click handler parameter. Including this as a parameter doesn't attach the click
     * handler to anything, the implementer must call this method in response to the click action.
     */
    fun interface WithClickAction : LocationParams {
        fun onClick()
    }

    /**
     * Parameter passed to Location.NAVIGATION_BAR_NAV_BUTTON to indicate if icon should to be shown
     * in the navigation bar button.
     */
    fun interface WithNavButtonIcon : LocationParams {
        fun showButtonIcon(): Boolean
    }

    /** Requirements for attaching a [MediaPreparer] to the compose UI. */
    interface WithMediaPreparer : LocationParams {

        // Method which can be called to obtain a deferred for the currently requested prepare
        // operation.
        fun obtainDeferred(): CompletableDeferred<PrepareMediaResult>

        // Flow to trigger the start of media prepares.
        val prepareMedia: Flow<Set<Media>>
    }

    /** Requirements for attaching the Date Scrubber to the compose UI. */
    interface WithDateScrubber : LocationParams {
        // Height of the UI container (as State), used to define
        // the scrollable range for the date scrubber cursor
        val parentHeight: State<Float>

        // Grid state for the grid that supports fast scrolling through the date scrubber
        val gridState: LazyGridState
    }

    /**
     * Parameter passed to [Location.NAVIGATION_BAR] for passing through click handlers.
     *
     * @property onSearchBarClicked A callback to be invoked when the search bar is clicked.
     * @property onCloseButtonClicked A callback to be invoked when the close button is clicked.
     */
    interface WithNavigationBar : LocationParams {
        fun onSearchBarClicked()

        fun onCloseButtonClicked()
    }
}
