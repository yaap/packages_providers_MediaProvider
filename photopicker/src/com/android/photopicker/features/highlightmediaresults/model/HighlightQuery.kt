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

package com.android.photopicker.features.highlightmediaresults.model

/** Defines the type of highlight query */
sealed interface HighlightQuery {
    /**
     * HighlightQuery of type Search which holds the input searchQuery used to highlight media
     * results
     */
    data class Search(val searchQuery: String) : HighlightQuery

    /** HighlightQuery of type Album which holds the album to highlight */
    data class Album(val album: HighlightAlbum) : HighlightQuery
}
