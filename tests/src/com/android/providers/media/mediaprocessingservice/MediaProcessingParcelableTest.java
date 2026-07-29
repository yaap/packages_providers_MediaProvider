/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.providers.media.mediaprocessingservice;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.MediaStore;
import android.provider.mediaprocessingservice.EmbeddingVector;
import android.provider.mediaprocessingservice.ErrorMessage;
import android.provider.mediaprocessingservice.MediaProcessingRequest;
import android.provider.mediaprocessingservice.MediaProcessingResponse;
import android.provider.mediaprocessingservice.QueryProcessingResponse;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import com.android.providers.media.flags.Flags;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_PROCESSING_SERVICE)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
public class MediaProcessingParcelableTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private <T extends android.os.Parcelable> T parcelAndUnparcel(T obj,
            android.os.Parcelable.Creator<T> creator) {
        Parcel parcel = Parcel.obtain();
        try {
            obj.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testEmbeddingVector_parceling() {
        float[] values = new float[]{1.0f, 2.5f, -0.1f};
        EmbeddingVector original = new EmbeddingVector(values, "model_sig_123");
        EmbeddingVector unparceled = parcelAndUnparcel(original, EmbeddingVector.CREATOR);

        assertThat(unparceled.getValues()).isEqualTo(original.getValues());
        assertThat(unparceled.getModelSignature()).isEqualTo(original.getModelSignature());
    }

    @Test
    public void testErrorMessage_parceling() {
        ErrorMessage original = new ErrorMessage(ErrorMessage.ErrorCode.ERROR_UNKNOWN, "Not Found",
                false);
        ErrorMessage unparceled = parcelAndUnparcel(original, ErrorMessage.CREATOR);

        assertThat(unparceled.getErrorCode()).isEqualTo(original.getErrorCode());
        assertThat(unparceled.getMessage()).isEqualTo(original.getMessage());
        assertThat(unparceled.isRetryable()).isEqualTo(original.isRetryable());
    }

    @Test
    public void testMediaProcessingRequest_parceling() {
        Uri uri = Uri.parse("content://media/external/images/media/101");
        long time = System.currentTimeMillis();
        MediaProcessingRequest original = new MediaProcessingRequest(uri,
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, time);
        MediaProcessingRequest unparceled = parcelAndUnparcel(original,
                MediaProcessingRequest.CREATOR);

        assertThat(unparceled.getUri()).isEqualTo(original.getUri());
        assertThat(unparceled.getMediaType()).isEqualTo(original.getMediaType());
        assertThat(unparceled.getProcessingGenerationNumber()).isEqualTo(
                original.getProcessingGenerationNumber());
    }

    @Test
    public void testMediaProcessingRequest_invalidUriAuthority() {
        Uri uri = Uri.parse("content://other_authority/123");
        assertThrows(IllegalArgumentException.class, () -> {
            new MediaProcessingRequest(uri, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, 0L);
        });
    }

    @Test
    public void testMediaProcessingResponse_parceling_withEmbeddings() {
        Uri uri = Uri.parse("content://media/external/video/media/202");
        long requestTime = System.currentTimeMillis();
        String labels = "cat,dog,tree";
        float[] values = new float[]{0.1f, 0.2f};
        EmbeddingVector vector = new EmbeddingVector(values, "vec_model");
        List<EmbeddingVector> vectors = Collections.singletonList(vector);

        MediaProcessingResponse original = MediaProcessingResponse.builder(uri,
                requestTime).setExtractedLabels(labels).setEmbeddingVectorList(vectors).build();
        MediaProcessingResponse unparceled = parcelAndUnparcel(original,
                MediaProcessingResponse.CREATOR);

        assertThat(unparceled.getUri()).isEqualTo(original.getUri());
        assertThat(unparceled.getProcessingGenerationNumber()).isEqualTo(
                original.getProcessingGenerationNumber());
        assertThat(unparceled.getExtractedLabels()).isEqualTo(original.getExtractedLabels());

        List<EmbeddingVector> unparceledVectors = unparceled.getEmbeddingVectorList();
        assertThat(unparceledVectors).hasSize(vectors.size());
        assertThat(unparceledVectors.get(0).getModelSignature()).isEqualTo(
                vector.getModelSignature());
        assertThat(unparceledVectors.get(0).getValues()).isEqualTo(vector.getValues());
    }

    @Test
    public void testMediaProcessingResponse_parceling_nullEmbeddings() {
        Uri uri = Uri.parse("content://media/external/images/media/303");
        long requestTime = System.currentTimeMillis();
        MediaProcessingResponse original = MediaProcessingResponse.builder(uri,
                requestTime).setExtractedLabels("labels_only").build();
        MediaProcessingResponse unparceled = parcelAndUnparcel(original,
                MediaProcessingResponse.CREATOR);

        assertThat(unparceled.getUri()).isEqualTo(original.getUri());
        assertThat(unparceled.getProcessingGenerationNumber()).isEqualTo(
                original.getProcessingGenerationNumber());
        assertThat(unparceled.getExtractedLabels()).isEqualTo("labels_only");
        assertThat(unparceled.getEmbeddingVectorList()).isEmpty();
    }

    @Test
    public void testMediaProcessingResponse_invalidUriAuthority() {
        Uri uri = Uri.parse("content://other_authority/123");
        assertThrows(IllegalArgumentException.class, () -> {
            MediaProcessingResponse.builder(uri, 0);
        });
    }

    @Test
    public void testQueryProcessingResponse_parceling_withEmbedding() {
        String query = "test query";
        float[] values = new float[]{0.1f, 0.2f};
        EmbeddingVector vector = new EmbeddingVector(values, "query_model");
        QueryProcessingResponse original = new QueryProcessingResponse(query, vector);
        QueryProcessingResponse unparceled = parcelAndUnparcel(original,
                QueryProcessingResponse.CREATOR);

        assertThat(unparceled.getSearchQuery()).isEqualTo(original.getSearchQuery());
        assertThat(unparceled.getEmbeddingVector()).isNotNull();
        assertThat(unparceled.getEmbeddingVector().getModelSignature()).isEqualTo(
                vector.getModelSignature());
        assertThat(unparceled.getEmbeddingVector().getValues()).isEqualTo(vector.getValues());
    }

    @Test
    public void testQueryProcessingResponse_parceling_nullEmbedding() {
        String query = "another query";
        QueryProcessingResponse original = new QueryProcessingResponse(query, null);
        QueryProcessingResponse unparceled = parcelAndUnparcel(original,
                QueryProcessingResponse.CREATOR);

        assertThat(unparceled.getSearchQuery()).isEqualTo(original.getSearchQuery());
        assertThat(unparceled.getEmbeddingVector()).isNull();
    }
}
