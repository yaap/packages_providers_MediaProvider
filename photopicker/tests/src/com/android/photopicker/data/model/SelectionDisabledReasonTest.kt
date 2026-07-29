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

import android.widget.photopicker.PhotoPickerSelectionParams
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.R
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.util.LocalizationHelper
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.Locale
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SelectionDisabledReasonTest {

    companion object {
        private val DURATION_60S = Duration.ofSeconds(60)
        private val DURATION_10S = Duration.ofSeconds(10)
        private const val RESOLUTION_1000PX = 1000L
        private const val RESOLUTION_100PX = 100L
        private const val SIZE_512KB = 512 * 1024L
        private const val SIZE_5MB = 5 * 1024 * 1024L
        private const val SIZE_1_556MB = (1.556 * 1024 * 1024).toLong()
        private const val DECIMAL_1_56 = 1.56
        private const val SIZE_2GB = 2 * 1024 * 1024 * 1024L
    }

    private val localizationHelper = LocalizationHelper(Locale.US)
    private val appName = "Test App"
    private val testableContext = InstrumentationRegistry.getInstrumentation().context
    private val resources = testableContext.resources

    @Test
    fun testExceedsMaxDuration() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder().setMaxVideoDuration(DURATION_60S).build()
        val config = createConfig(selectionParams, appName)
        val reason = SelectionDisabledReason.EXCEEDS_MAX_DURATION

        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_max_video_duration_error,
                appName,
                localizationHelper.getLocalizedCount(DURATION_60S.toSeconds()),
            )

        val message = reason.getDisabledMessage(config, localizationHelper, resources)

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testFallsBelowMinDuration() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder().setMinVideoDuration(DURATION_10S).build()
        val config = createConfig(selectionParams, appName)
        val reason = SelectionDisabledReason.FALLS_BELOW_MIN_DURATION

        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_min_video_duration_error,
                appName,
                localizationHelper.getLocalizedCount(DURATION_10S.toSeconds()),
            )

        val message = reason.getDisabledMessage(config, localizationHelper, resources)

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testExceedsMaxResolution() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMaxMediaItemResolutionInPixels(RESOLUTION_1000PX)
                .build()
        val config = createConfig(selectionParams, appName)
        val reason = SelectionDisabledReason.EXCEEDS_MAX_RESOLUTION

        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_max_media_item_resolution_error,
                appName,
            )

        val message = reason.getDisabledMessage(config, localizationHelper, resources)

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testFallsBelowMinResolution() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMinMediaItemResolutionInPixels(RESOLUTION_100PX)
                .build()
        val config = createConfig(selectionParams, appName)
        val reason = SelectionDisabledReason.FALLS_BELOW_MIN_RESOLUTION

        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_min_media_item_resolution_error,
                appName,
            )

        val message = reason.getDisabledMessage(config, localizationHelper, resources)

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testMimeTypeNotAllowed() {
        val selectionParams = PhotoPickerSelectionParams.Builder().build()
        val config = createConfig(selectionParams, appName)
        val reason = SelectionDisabledReason.MIME_TYPE_NOT_ALLOWED

        val expectedMessage =
            resources.getString(R.string.photopicker_selection_unsupported_mime_type_error, appName)

        val message = reason.getDisabledMessage(config, localizationHelper, resources)

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testExceedsMaxSizeKb() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder().setMaxMediaItemSizeInBytes(SIZE_512KB).build()
        val config = createConfig(selectionParams, appName)
        val reason = SelectionDisabledReason.EXCEEDS_MAX_SIZE

        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_max_media_item_size_error_kb,
                appName,
                localizationHelper.getLocalizedCount(512.0),
            )

        val message = reason.getDisabledMessage(config, localizationHelper, resources)

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testExceedsMaxSizeMb() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder().setMaxMediaItemSizeInBytes(SIZE_5MB).build()
        val config = createConfig(selectionParams, appName)
        val reason = SelectionDisabledReason.EXCEEDS_MAX_SIZE

        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_max_media_item_size_error_mb,
                appName,
                localizationHelper.getLocalizedCount(5.0),
            )

        val message = reason.getDisabledMessage(config, localizationHelper, resources)

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testExceedsMaxSizeGb() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder().setMaxMediaItemSizeInBytes(SIZE_2GB).build()
        val config = createConfig(selectionParams, appName)
        val reason = SelectionDisabledReason.EXCEEDS_MAX_SIZE

        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_max_media_item_size_error_gb,
                appName,
                localizationHelper.getLocalizedCount(2.0),
            )

        val message = reason.getDisabledMessage(config, localizationHelper, resources)

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testExceedsMaxSizeWithDecimalUpToTwoPlaces() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder().setMaxMediaItemSizeInBytes(SIZE_1_556MB).build()
        val config = createConfig(selectionParams, appName)
        val reason = SelectionDisabledReason.EXCEEDS_MAX_SIZE

        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_max_media_item_size_error_mb,
                appName,
                localizationHelper.getLocalizedCount(DECIMAL_1_56),
            )

        val message = reason.getDisabledMessage(config, localizationHelper, resources)

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testFallbackAppName() {
        val selectionParams = PhotoPickerSelectionParams.Builder().build()
        val config = createConfig(selectionParams, null)
        val reason = SelectionDisabledReason.MIME_TYPE_NOT_ALLOWED

        val genericAppName =
            resources.getString(R.string.photopicker_selection_param_generic_app_label)
        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_unsupported_mime_type_error,
                genericAppName,
            )

        val message = reason.getDisabledMessage(config, localizationHelper, resources)

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testNullSelectionParamsThrows() {
        val config = createConfig(null, appName)
        val reason = SelectionDisabledReason.MIME_TYPE_NOT_ALLOWED

        assertThrows(IllegalStateException::class.java) {
            reason.getDisabledMessage(config, localizationHelper, resources)
        }
    }

    @Test
    fun testSelectionBatchSizeLimitExceededKb() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder().setMaxSelectionBatchSizeInBytes(SIZE_512KB).build()
        val config = createConfig(selectionParams, appName)

        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_max_selection_batch_size_error_kb,
                appName,
                localizationHelper.getLocalizedCount(512.0),
            )

        val message =
            SelectionDisabledReason.getSelectionBatchSizeLimitExceededMessage(
                config,
                localizationHelper,
                resources,
            )

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testSelectionBatchSizeLimitExceededMb() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder().setMaxSelectionBatchSizeInBytes(SIZE_5MB).build()
        val config = createConfig(selectionParams, appName)

        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_max_selection_batch_size_error_mb,
                appName,
                localizationHelper.getLocalizedCount(5.0),
            )

        val message =
            SelectionDisabledReason.getSelectionBatchSizeLimitExceededMessage(
                config,
                localizationHelper,
                resources,
            )

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testSelectionBatchSizeLimitExceededGb() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder().setMaxSelectionBatchSizeInBytes(SIZE_2GB).build()
        val config = createConfig(selectionParams, appName)

        val expectedMessage =
            resources.getString(
                R.string.photopicker_selection_max_selection_batch_size_error_gb,
                appName,
                localizationHelper.getLocalizedCount(2.0),
            )

        val message =
            SelectionDisabledReason.getSelectionBatchSizeLimitExceededMessage(
                config,
                localizationHelper,
                resources,
            )

        assertThat(message).isEqualTo(expectedMessage)
    }

    @Test
    fun testNoSelectionBatchSizeLimitReturnsNull() {
        val selectionParams = PhotoPickerSelectionParams.Builder().build()
        val config = createConfig(selectionParams, appName)

        val message =
            SelectionDisabledReason.getSelectionBatchSizeLimitExceededMessage(
                config,
                localizationHelper,
                resources,
            )

        assertThat(message).isNull()
    }

    @Test
    fun testSelectionBatchSizeMessageReturnsNullForNullSelectionParams() {
        val config = createConfig(null, appName)

        val message =
            SelectionDisabledReason.getSelectionBatchSizeLimitExceededMessage(
                config,
                localizationHelper,
                resources,
            )

        assertThat(message).isNull()
    }

    private fun createConfig(
        selectionParams: PhotoPickerSelectionParams?,
        label: String?,
    ): PhotopickerConfiguration {
        return PhotopickerConfiguration(
            action = "",
            sessionId = 0,
            selectionParams = selectionParams,
            callingPackageLabel = label,
        )
    }
}
