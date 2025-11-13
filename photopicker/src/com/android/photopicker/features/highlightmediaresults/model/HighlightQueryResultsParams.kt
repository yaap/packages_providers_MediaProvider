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

/**
 * A data class that holds the highlight media information provided by an app. The two pieces of
 * required information will be provided by the app itself. These pieces will then be used to create
 * an object of this type for use across the different photopicker components.
 */
data class HighlightQueryResultsParams(
    // The type of highlight the app wants to use. They can open directly to the a media results
    // grid page or they can choose to show a highlight section on the top of the photo grid.
    // The media items in this highlight section could either be based on a text query of could be
    // from a pre-defined album if they chose highlight an album.
    // There's no other valid highlight type supported at the moment apart from what is declared in
    // this enum.
    val queryResultsHighlightType: QueryResultsHighlightType,
    // The query for which we show the highlighted results. This can either be of type Search
    // which will hold the search query or type Album which will hold the album to highlight.
    val queryResultsHighlightQuery: HighlightQuery,
)
