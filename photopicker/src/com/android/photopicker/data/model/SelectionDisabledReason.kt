/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.photopicker.data.model

import android.content.res.Resources
import com.android.photopicker.R
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.util.LocalizationHelper
import com.android.photopicker.util.SizeUnit

/**
 * Enum defining the reasons why a media item might be disabled for selection in the Photo Picker.
 *
 * These reasons map to the constraints defined in
 * [android.widget.photopicker.PhotoPickerSelectionParams].
 */
enum class SelectionDisabledReason {
    /** The media item's size exceeds the maximum allowed size in bytes. */
    EXCEEDS_MAX_SIZE,

    /** The video's duration exceeds the maximum allowed duration in seconds. */
    EXCEEDS_MAX_DURATION,

    /** The video's duration is below the minimum required duration in seconds. */
    FALLS_BELOW_MIN_DURATION,

    /** The media item's resolution exceeds the maximum allowed resolution in pixels. */
    EXCEEDS_MAX_RESOLUTION,

    /** The media item's resolution is below the minimum required resolution in pixels. */
    FALLS_BELOW_MIN_RESOLUTION,

    /** The media item's MIME type is not in the list of allowed MIME types. */
    MIME_TYPE_NOT_ALLOWED;

    /**
     * Returns a localized error message for this [SelectionDisabledReason].
     *
     * This method uses the current [PhotopickerConfiguration] to retrieve the application name and
     * the specific constraint values (size, video duration, resolution) to format the error
     * message.
     *
     * @param configuration The current photopicker configuration.
     * @param localizationHelper The helper for localization.
     * @param resources The resources used to fetch strings.
     * @return A localized and formatted error message string.
     */
    fun getDisabledMessage(
        configuration: PhotopickerConfiguration,
        localizationHelper: LocalizationHelper,
        resources: Resources,
    ): String {
        val selectionParams = configuration.selectionParams
        checkNotNull(selectionParams) {
            "SelectionParams cannot be null when disabled reason is non-null."
        }
        val appName =
            configuration.callingPackageLabel
                ?: resources.getString(R.string.photopicker_selection_param_generic_app_label)

        return when (this) {
            EXCEEDS_MAX_SIZE -> {
                val maxSizeInBytes = selectionParams.maxMediaItemSizeInBytes
                val (sizeUnit, formattedValue) = localizationHelper.getFormattedSize(maxSizeInBytes)
                val stringResourceId = fileSizeToResourceIdMap.getValue(sizeUnit)
                resources.getString(stringResourceId, appName, formattedValue)
            }
            EXCEEDS_MAX_DURATION -> {
                checkNotNull(selectionParams.maxVideoDuration) {
                    "Max video duration param cannot be null when disabled reason is EXCEEDS_MAX_DURATION."
                }
                val maxDuration = selectionParams.maxVideoDuration!!.toSeconds()
                resources.getString(
                    R.string.photopicker_selection_max_video_duration_error,
                    appName,
                    localizationHelper.getLocalizedCount(maxDuration),
                )
            }
            FALLS_BELOW_MIN_DURATION -> {
                checkNotNull(selectionParams.minVideoDuration) {
                    "Min video duration param cannot be null when disabled reason is FALLS_BELOW_MIN_DURATION."
                }
                val minDuration = selectionParams.minVideoDuration!!.toSeconds()
                resources.getString(
                    R.string.photopicker_selection_min_video_duration_error,
                    appName,
                    localizationHelper.getLocalizedCount(minDuration),
                )
            }
            EXCEEDS_MAX_RESOLUTION -> {
                resources.getString(
                    R.string.photopicker_selection_max_media_item_resolution_error,
                    appName,
                )
            }
            FALLS_BELOW_MIN_RESOLUTION -> {
                resources.getString(
                    R.string.photopicker_selection_min_media_item_resolution_error,
                    appName,
                )
            }
            MIME_TYPE_NOT_ALLOWED -> {
                resources.getString(
                    R.string.photopicker_selection_unsupported_mime_type_error,
                    appName,
                )
            }
        }
    }

    companion object {
        /**
         * Returns a localized error message if the total selection size exceeds the maximum allowed
         * batch size.
         *
         * @param configuration The current photopicker configuration.
         * @param localizationHelper The helper for localization.
         * @param resources The resources used to fetch strings.
         * @return A localized and formatted error message string, or null if no limit is set.
         */
        fun getSelectionBatchSizeLimitExceededMessage(
            configuration: PhotopickerConfiguration,
            localizationHelper: LocalizationHelper,
            resources: Resources,
        ): String? {
            val selectionParams = configuration.selectionParams ?: return null
            val maxBatchSize = selectionParams.maxSelectionBatchSizeInBytes
            if (maxBatchSize == -1L) return null

            val appName =
                configuration.callingPackageLabel
                    ?: resources.getString(R.string.photopicker_selection_param_generic_app_label)

            val (sizeUnit, formattedValue) = localizationHelper.getFormattedSize(maxBatchSize)
            val stringResourceId = batchSizeToResourceIdMap.getValue(sizeUnit)
            return resources.getString(stringResourceId, appName, formattedValue)
        }

        /**
         * Returns the [SelectionDisabledReason] corresponding to the given name, or null if the
         * name is not recognized.
         */
        fun fromName(name: String?): SelectionDisabledReason? {
            return entries.find { it.name == name }
        }

        /**
         * A mapping of [SizeUnit] to string resources specifically for individual media item size
         * errors.
         */
        private val fileSizeToResourceIdMap =
            mapOf<SizeUnit, Int>(
                SizeUnit.KB to R.string.photopicker_selection_max_media_item_size_error_kb,
                SizeUnit.MB to R.string.photopicker_selection_max_media_item_size_error_mb,
                SizeUnit.GB to R.string.photopicker_selection_max_media_item_size_error_gb,
            )

        /**
         * A mapping of [SizeUnit] to string resources specifically for selection batch size errors.
         */
        private val batchSizeToResourceIdMap =
            mapOf<SizeUnit, Int>(
                SizeUnit.KB to R.string.photopicker_selection_max_selection_batch_size_error_kb,
                SizeUnit.MB to R.string.photopicker_selection_max_selection_batch_size_error_mb,
                SizeUnit.GB to R.string.photopicker_selection_max_selection_batch_size_error_gb,
            )
    }
}
