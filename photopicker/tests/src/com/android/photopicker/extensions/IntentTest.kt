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

package com.android.photopicker.extensions

import android.content.Intent
import android.platform.test.annotations.RequiresFlagsEnabled
import android.provider.MediaStore
import androidx.core.os.bundleOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.IllegalIntentExtraException
import com.android.photopicker.features.highlightmediaresults.model.HighlightAlbum
import com.android.photopicker.features.highlightmediaresults.model.HighlightQuery
import com.android.photopicker.features.highlightmediaresults.model.HighlightQueryResultsParams
import com.android.photopicker.features.highlightmediaresults.model.QueryResultsHighlightType
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [Intent] extension functions */
@SmallTest
@RunWith(AndroidJUnit4::class)
class IntentTest {

    @Test
    fun testGetSelectionLimitFromIntentActionPickImages() {

        val intent =
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
            }

        /* Use a different default that what's in the intent */
        val limit = intent.getPhotopickerSelectionLimitOrDefault(default = 25)

        assertThat(limit).isEqualTo(50)
    }

    @Test
    fun testGetSelectionLimitFromIntentActionPickImagesDefault() {

        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        /* Use a different default that what's in the intent */
        val limit = intent.getPhotopickerSelectionLimitOrDefault(default = 25)

        assertThat(limit).isEqualTo(25)
    }

    @Test
    fun testGetSelectionLimitFromIntentGetContentDefault() {

        val intent = Intent(Intent.ACTION_GET_CONTENT)
        /* Use a different default that what's in the intent */
        val limit = intent.getPhotopickerSelectionLimitOrDefault(default = 25)

        assertThat(limit).isEqualTo(25)
    }

    @Test
    fun testGetSelectionLimitFromIntentGetContent() {

        val intent =
            Intent(Intent.ACTION_GET_CONTENT).apply { putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        /* Use a different default that what's in the intent */
        val limit = intent.getPhotopickerSelectionLimitOrDefault(default = 25)

        assertThat(limit).isEqualTo(MediaStore.getPickImagesMaxLimit())
    }

    @Test
    fun testGetMimeTypeFromIntentActionPickImages() {
        val mimeTypes: List<String> = mutableListOf("image/*", "video/mp4", "image/gif")
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isEqualTo(mimeTypes)
    }

    @Test
    fun testGetMimeTypeFromIntentActionPickImagesWithWildcards() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply { setType("*/*") }

        val mimeTypes: List<String> = mutableListOf("*/*")
        val intent2 = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent2.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        val expectedMimeTypes = arrayListOf("image/*", "video/*")
        assertThat(intent.getPhotopickerMimeTypes()).isEqualTo(expectedMimeTypes)
        assertThat(intent2.getPhotopickerMimeTypes()).isEqualTo(expectedMimeTypes)
    }

    @Test
    fun testGetInvalidMimeTypeFromIntentActionPickImages() {
        val mimeTypes: List<String> = mutableListOf("image/*", "application/binary", "image/gif")
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        assertThrows(IllegalIntentExtraException::class.java) { intent.getPhotopickerMimeTypes() }
    }

    @Test
    fun testGetMimeTypeFromIntentActionGetContent() {
        val mimeTypes: List<String> = mutableListOf("image/*", "video/mp4", "image/gif")
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isEqualTo(mimeTypes)
    }

    @Test
    fun testGetInvalidMimeTypeFromIntentActionGetContent() {
        val mimeTypes: List<String> = mutableListOf("image/*", "application/binary", "image/gif")
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isNull()
    }

    @Test
    fun testGetTypeFromIntent() {
        val mimeType: String = "image/gif"
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.setType(mimeType)

        val resultMimeTypeFilter = intent.getPhotopickerMimeTypes()
        assertThat(resultMimeTypeFilter).isEqualTo(mutableListOf(mimeType))
    }

    @Test
    fun testGetInvalidTypeFromIntent() {
        val mimeType: String = "application/binary"
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.setType(mimeType)

        assertThrows(IllegalIntentExtraException::class.java) { intent.getPhotopickerMimeTypes() }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testGetSearchHighlightMediaQueryInfoFromIntent() {
        val searchQuery = "dog"
        val highlightQueryResultsParams =
            HighlightQueryResultsParams(
                queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                queryResultsHighlightQuery = HighlightQuery.Search(searchQuery = searchQuery),
            )
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(
            MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS,
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                    MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY to searchQuery,
            ),
        )

        val retrievedHighlightQueryParams = intent.getHighlightQueryResultsParams()

        assertThat(retrievedHighlightQueryParams.queryResultsHighlightType)
            .isEqualTo(highlightQueryResultsParams.queryResultsHighlightType)
        assertThat(retrievedHighlightQueryParams.queryResultsHighlightQuery)
            .isEqualTo(highlightQueryResultsParams.queryResultsHighlightQuery)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testGetAlbumHighlightMediaQueryInfoFromIntent() {
        val highlightQueryResultsParams =
            HighlightQueryResultsParams(
                queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                queryResultsHighlightQuery =
                    HighlightQuery.Album(HighlightAlbum.HIGHLIGHT_ALBUM_FAVORITES),
            )
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(
            MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM,
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                    MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID to
                    MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_FAVORITES,
            ),
        )

        val retrievedHighlightQueryParams = intent.getHighlightQueryResultsParams()

        assertThat(retrievedHighlightQueryParams.queryResultsHighlightType)
            .isEqualTo(highlightQueryResultsParams.queryResultsHighlightType)
        assertThat(retrievedHighlightQueryParams.queryResultsHighlightQuery)
            .isEqualTo(highlightQueryResultsParams.queryResultsHighlightQuery)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testGetHighlightQueryMediaInfoFromIntentDefault() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        intent.putExtra(
            MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS,
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                    MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY to "",
            ),
        )

        val retrievedHsrInfo = intent.getHighlightQueryResultsParams()

        assertThat(retrievedHsrInfo.queryResultsHighlightType)
            .isEqualTo(QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION)
        assertThat(retrievedHsrInfo.queryResultsHighlightQuery)
            .isEqualTo(HighlightQuery.Search(searchQuery = ""))
    }
}
