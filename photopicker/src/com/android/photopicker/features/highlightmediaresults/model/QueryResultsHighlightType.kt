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

import android.provider.MediaStore

/**
 * This represents the possible HighlightSearch types available for an app to use in
 * [MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_QUERY_RESULTS].
 *
 * HighlightSearchType is the way an app chooses to highlight search results.
 */
enum class QueryResultsHighlightType {
    // Displays a highlight section above the photo grid.
    HIGHLIGHT_MEDIA_SECTION,
    // Opens to the search page directly.
    HIGHLIGHT_MEDIA_RESULTS,
    UNSET_HIGHLIGHT_TYPE;

    companion object {
        fun toQueryResultsHighlightType(type: Int): QueryResultsHighlightType {
            return when (type) {
                MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED -> HIGHLIGHT_MEDIA_SECTION
                MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED -> HIGHLIGHT_MEDIA_RESULTS
                else -> UNSET_HIGHLIGHT_TYPE
            }
        }
    }
}
