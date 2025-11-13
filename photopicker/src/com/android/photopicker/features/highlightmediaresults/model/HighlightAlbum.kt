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

import android.content.Context
import android.provider.MediaStore
import com.android.photopicker.R

/**
 * An enum class representing the possible media albums that can be highlighted by an app in
 * [MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_QUERY_RESULTS] along with their static album ids.
 */
enum class HighlightAlbum(val albumId: String) {
    // Highlight the Favorites album
    HIGHLIGHT_ALBUM_FAVORITES("Favorites"),
    // Highlight the Camera album
    HIGHLIGHT_ALBUM_CAMERA("Camera"),
    // Highlight the Screenshots album
    HIGHLIGHT_ALBUM_SCREENSHOTS("Screenshots"),
    // Highlight the Videos album
    HIGHLIGHT_ALBUM_VIDEOS("Videos"),
    // Highlight the Downloads album
    HIGHLIGHT_ALBUM_DOWNLOADS("Downloads"),
    UNSET_HIGHLIGHT_ALBUM("UnsetAlbum");

    companion object {
        fun toHighlightAlbum(id: String): HighlightAlbum {
            return when (id) {
                MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_FAVORITES -> HIGHLIGHT_ALBUM_FAVORITES
                MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_CAMERA -> HIGHLIGHT_ALBUM_CAMERA
                MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_SCREENSHOTS -> HIGHLIGHT_ALBUM_SCREENSHOTS
                MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_VIDEOS -> HIGHLIGHT_ALBUM_VIDEOS
                MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_DOWNLOADS -> HIGHLIGHT_ALBUM_DOWNLOADS
                else -> UNSET_HIGHLIGHT_ALBUM
            }
        }

        fun getAlbumNameFromAlbum(context: Context, album: HighlightAlbum): String {
            return when (album) {
                HIGHLIGHT_ALBUM_FAVORITES ->
                    context.getString(R.string.photopicker_hsr_favorites_album_label)
                HIGHLIGHT_ALBUM_SCREENSHOTS ->
                    context.getString(R.string.photopicker_hsr_screenshots_album_label)
                HIGHLIGHT_ALBUM_CAMERA ->
                    context.getString(R.string.photopicker_hsr_camera_album_label)
                HIGHLIGHT_ALBUM_DOWNLOADS ->
                    context.getString(R.string.photopicker_hsr_downloads_album_label)
                HIGHLIGHT_ALBUM_VIDEOS ->
                    context.getString(R.string.photopicker_hsr_videos_album_label)
                else -> throw IllegalArgumentException("Unsupported album id received")
            }
        }
    }
}
