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

package com.android.photopicker.data.model

import android.net.Uri
import android.os.Parcel
import android.widget.photopicker.PhotoPickerSelectionParams
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [Media] data models */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaTest {

    private val SIZE_BYTES_DEFAULT = 1000L
    private val WIDTH_DEFAULT = 1000
    private val HEIGHT_DEFAULT = 1000
    private val RESOLUTION_PIXELS_DEFAULT = WIDTH_DEFAULT.toLong() * HEIGHT_DEFAULT.toLong()
    private val DURATION_DEFAULT = Duration.ofSeconds(30)

    /** Write to parcel as a [Media.Image], read back as a [Media.Image] */
    @Test
    fun testMediaImageIsParcelable() {

        val testImage = createImage()

        val parcel = Parcel.obtain()
        testImage.writeToParcel(parcel, /* flags= */ 0)
        parcel.setDataPosition(0)

        // Unmarshall the parcel and compare the result to the original to ensure they are the same.
        val resultImage = Media.Image.createFromParcel(parcel)
        assertWithMessage("Image was different when parcelled")
            .that(resultImage)
            .isEqualTo(testImage)

        parcel.recycle()
    }

    /** Write to parcel as a [Media.Video], read back as a [Media.Video] */
    @Test
    fun testMediaVideoIsParcelable() {

        val testVideo = createVideo()

        val parcel = Parcel.obtain()
        testVideo.writeToParcel(parcel, /* flags= */ 0)
        parcel.setDataPosition(0)

        // Unmarshall the parcel and compare the result to the original to ensure they are the same.
        val resultVideo = Media.Video.createFromParcel(parcel)
        assertWithMessage("Video was different when parcelled")
            .that(resultVideo)
            .isEqualTo(testVideo)

        parcel.recycle()
    }

    @Test
    fun testImageHashCodeIsPredictable() {

        val testImage = createImage()

        val testImage2 = createImage()

        assertWithMessage("Different hashCode received for same input")
            .that(testImage.hashCode())
            .isEqualTo(testImage2.hashCode())
    }

    @Test
    fun testImageEquals() {

        val testImage = createImage()

        val testImage2 =
            createImage(
                pickerId = 987654321L // intentionally different as this field is ignored by equals
            )

        assertWithMessage("Expected images to be equal").that(testImage).isEqualTo(testImage2)
    }

    @Test
    fun testImageNotEquals() {

        val testImage =
            createImage(
                mediaId = "image_id_2" // intentionally different id
            )

        val testImage2 =
            createImage(
                pickerId = 987654321L // intentionally different as this field is ignored by equals
            )

        assertWithMessage("Expected images to not be equal")
            .that(testImage)
            .isNotEqualTo(testImage2)
    }

    @Test
    fun testVideoHashCodeIsPredictable() {

        val testVideo = createVideo()

        val testVideo2 =
            createVideo(
                // Hashcode should not change with different timestamps
                dateTakenMillisLong = 123456789L
            )

        assertWithMessage("Different hashCode received for same input")
            .that(testVideo.hashCode())
            .isEqualTo(testVideo2.hashCode())
    }

    @Test
    fun testVideoEquals() {

        val testVideo = createVideo()

        val testVideo2 =
            createVideo(
                pickerId = 987654321L // intentionally different as this field is ignored by equals
            )

        assertWithMessage("Expected videos to be equal").that(testVideo).isEqualTo(testVideo2)
    }

    @Test
    fun testVideoNotEquals() {

        val testVideo =
            createVideo(
                mediaId = "video_id_12345" // intentionally different id
            )

        val testVideo2 =
            createVideo(
                pickerId = 987654321L // intentionally different as this field is ignored by equals
            )

        assertWithMessage("Expected videos to not be equal")
            .that(testVideo)
            .isNotEqualTo(testVideo2)
    }

    @Test
    fun testVideo_withAllSelectionParamsSatisfied_setsNullDisableReason() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMaxMediaItemSizeInBytes(SIZE_BYTES_DEFAULT * 2)
                .setMaxMediaItemResolutionInPixels(RESOLUTION_PIXELS_DEFAULT * 2)
                .setMinMediaItemResolutionInPixels(RESOLUTION_PIXELS_DEFAULT / 2)
                .setMaxVideoDuration(DURATION_DEFAULT.multipliedBy(2))
                .setMinVideoDuration(DURATION_DEFAULT.dividedBy(2))
                .setMimeTypes(listOf("video/*"))
                .build()

        val video = createVideo(mimeType = "video/mp4", selectionParams = selectionParams)

        assertThat(video.disabledReason).isNull()
    }

    @Test
    fun testImage_mediaExceedsMaxSize_setsDisableReason() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMaxMediaItemSizeInBytes(SIZE_BYTES_DEFAULT / 2)
                .build()

        val image = createImage(selectionParams = selectionParams)

        assertThat(image.disabledReason).isEqualTo(SelectionDisabledReason.EXCEEDS_MAX_SIZE)
    }

    @Test
    fun testVideo_mediaExceedsMaxDuration_setsDisableReason() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMaxVideoDuration(DURATION_DEFAULT.dividedBy(2))
                .build()

        val video = createVideo(selectionParams = selectionParams)

        assertThat(video.disabledReason).isEqualTo(SelectionDisabledReason.EXCEEDS_MAX_DURATION)
    }

    @Test
    fun testVideo_mediaFallsBelowMinDuration_setsDisableReason() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMinVideoDuration(DURATION_DEFAULT.multipliedBy(2))
                .build()

        val video = createVideo(selectionParams = selectionParams)

        assertThat(video.disabledReason).isEqualTo(SelectionDisabledReason.FALLS_BELOW_MIN_DURATION)
    }

    @Test
    fun testMedia_mediaExceedsMaxResolution_setsDisableReason() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMaxMediaItemResolutionInPixels(RESOLUTION_PIXELS_DEFAULT / 2)
                .build()

        val image = createImage(selectionParams = selectionParams)

        assertThat(image.disabledReason).isEqualTo(SelectionDisabledReason.EXCEEDS_MAX_RESOLUTION)
    }

    @Test
    fun testMedia_mediaFallsBelowMinResolution_setsDisableReason() {
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMinMediaItemResolutionInPixels(RESOLUTION_PIXELS_DEFAULT * 2)
                .build()

        val image = createImage(selectionParams = selectionParams)

        assertThat(image.disabledReason)
            .isEqualTo(SelectionDisabledReason.FALLS_BELOW_MIN_RESOLUTION)
    }

    @Test
    fun testMedia_mediaMimeTypeNotAllowed_setsDisableReason() {
        val allowedMimeType = "image/png"
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMimeTypes(listOf(allowedMimeType)) // only png is allowed
                .build()

        val image =
            createImage(
                mimeType = "image/jpeg", // jpeg not allowed
                selectionParams = selectionParams,
            )

        assertThat(image.disabledReason).isEqualTo(SelectionDisabledReason.MIME_TYPE_NOT_ALLOWED)
    }

    private fun createImage(
        mediaId: String = "image_id",
        pickerId: Long = 123456789L,
        authority: String = "authority",
        mediaSource: MediaSource = MediaSource.LOCAL,
        mediaUri: Uri =
            Uri.EMPTY.buildUpon()
                .apply {
                    scheme("content")
                    authority("media")
                    path("picker")
                    path("a")
                    path(mediaId)
                }
                .build(),
        glideLoadableUri: Uri =
            Uri.EMPTY.buildUpon()
                .apply {
                    scheme("content")
                    authority("a")
                    path(mediaId)
                }
                .build(),
        dateTakenMillisLong: Long = 987654321L,
        sizeInBytes: Long = SIZE_BYTES_DEFAULT,
        mimeType: String = "image/png",
        standardMimeTypeExtension: Int = 1,
        width: Int = WIDTH_DEFAULT,
        height: Int = HEIGHT_DEFAULT,
        selectionParams: PhotoPickerSelectionParams? = null,
    ): Media.Image {
        return Media.Image(
            mediaId = mediaId,
            pickerId = pickerId,
            authority = authority,
            mediaSource = mediaSource,
            mediaUri = mediaUri,
            glideLoadableUri = glideLoadableUri,
            dateTakenMillisLong = dateTakenMillisLong,
            sizeInBytes = sizeInBytes,
            mimeType = mimeType,
            standardMimeTypeExtension = standardMimeTypeExtension,
            width = width,
            height = height,
            selectionParams = selectionParams,
        )
    }

    private fun createVideo(
        mediaId: String = "video_id",
        pickerId: Long = 123456789L,
        authority: String = "authority",
        mediaSource: MediaSource = MediaSource.LOCAL,
        mediaUri: Uri =
            Uri.EMPTY.buildUpon()
                .apply {
                    scheme("content")
                    authority("media")
                    path("picker")
                    path("a")
                    path(mediaId)
                }
                .build(),
        glideLoadableUri: Uri =
            Uri.EMPTY.buildUpon()
                .apply {
                    scheme("content")
                    authority("a")
                    path(mediaId)
                }
                .build(),
        dateTakenMillisLong: Long = 987654321L,
        sizeInBytes: Long = SIZE_BYTES_DEFAULT,
        mimeType: String = "video/mp4",
        standardMimeTypeExtension: Int = 1,
        duration: Int = DURATION_DEFAULT.toMillis().toInt(),
        width: Int = WIDTH_DEFAULT,
        height: Int = HEIGHT_DEFAULT,
        selectionParams: PhotoPickerSelectionParams? = null,
    ): Media.Video {
        return Media.Video(
            mediaId = mediaId,
            pickerId = pickerId,
            authority = authority,
            mediaSource = mediaSource,
            mediaUri = mediaUri,
            glideLoadableUri = glideLoadableUri,
            dateTakenMillisLong = dateTakenMillisLong,
            sizeInBytes = sizeInBytes,
            mimeType = mimeType,
            standardMimeTypeExtension = standardMimeTypeExtension,
            duration = duration,
            width = width,
            height = height,
            selectionParams = selectionParams,
        )
    }
}
