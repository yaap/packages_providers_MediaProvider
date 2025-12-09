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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.photopicker.core.Background
import com.android.photopicker.data.DataService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for [HighlightMedia] composable.
 *
 * This ViewModel manages the highlight section visibility state.
 *
 * @param scopeOverride An optional CoroutineScope to be used instead of the default viewModelScope.
 * @param backgroundDispatcher A CoroutineDispatcher for running background tasks.
 * @param dataService The service for monitoring available providers list.
 */
@HiltViewModel
class HighlightMediaViewModel
@Inject
constructor(
    private val scopeOverride: CoroutineScope?,
    @Background val backgroundDispatcher: CoroutineDispatcher,
    private val dataService: DataService,
) : ViewModel() {
    // Check if a scope override was injected before using the default [viewModelScope]
    private val scope: CoroutineScope = scopeOverride ?: this.viewModelScope

    /** Represents the visibility state of the highlight section. */
    private val _showHighlightSection = MutableStateFlow(true)
    val showHighlightSection: StateFlow<Boolean> = _showHighlightSection

    init {
        scope.launch(backgroundDispatcher) {
            // When available providers change, reset the highlight section visibility to true.
            dataService.availableProviders.collect { setShowHighlightSection(true) }
        }
    }

    /**
     * Sets the visibility of the highlight section.
     *
     * @param show The new visibility state.
     */
    fun setShowHighlightSection(show: Boolean) {
        _showHighlightSection.value = show
    }
}
