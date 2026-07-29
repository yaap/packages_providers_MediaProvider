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

import android.os.Build;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.mediaprocessingservice.EmbeddingVector;
import android.provider.mediaprocessingservice.EmbeddingVectorSerializer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import com.android.providers.media.flags.Flags;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_PROCESSING_SERVICE)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
public class EmbeddingVectorSerializerTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    public void testSerializeDeserialize_emptyList() throws IOException {
        List<EmbeddingVector> original = Collections.emptyList();
        byte[] serialized = EmbeddingVectorSerializer.serializeList(original);
        assertThat(serialized).isNotNull();
        assertThat(serialized).isEmpty();
        List<EmbeddingVector> deserialized = EmbeddingVectorSerializer.deserializeList(serialized);
        assertThat(deserialized).isEmpty();
    }

    @Test
    public void testSerializeDeserialize_singleVector() throws IOException {
        float[] values = new float[]{1.0f, 2.5f, -0.1f};
        EmbeddingVector vec = new EmbeddingVector(values, "model_sig_123");
        List<EmbeddingVector> original = Collections.singletonList(vec);

        byte[] serialized = EmbeddingVectorSerializer.serializeList(original);
        assertThat(serialized).isNotNull();
        List<EmbeddingVector> deserialized = EmbeddingVectorSerializer.deserializeList(serialized);

        assertThat(deserialized).hasSize(1);
        assertEmbeddingVectorEquals(deserialized.get(0), original.get(0));
    }

    @Test
    public void testSerializeDeserialize_multipleVectors() throws IOException {
        EmbeddingVector vec1 = new EmbeddingVector(new float[]{0.1f, 0.2f}, "model_A");
        EmbeddingVector vec2 = new EmbeddingVector(new float[]{-1f}, "model_B");
        EmbeddingVector vec3 = new EmbeddingVector(new float[0], "model_C_empty");
        List<EmbeddingVector> original = Arrays.asList(vec1, vec2, vec3);

        byte[] serialized = EmbeddingVectorSerializer.serializeList(original);
        assertThat(serialized).isNotNull();
        List<EmbeddingVector> deserialized = EmbeddingVectorSerializer.deserializeList(serialized);

        assertThat(deserialized).hasSize(3);
        assertEmbeddingVectorEquals(deserialized.get(0), original.get(0));
        assertEmbeddingVectorEquals(deserialized.get(1), original.get(1));
        assertEmbeddingVectorEquals(deserialized.get(2), original.get(2));
    }

    @Test
    public void testSerializeDeserialize_specialCharsSignature() throws IOException {
        EmbeddingVector vec = new EmbeddingVector(new float[]{1f}, "model_with_UNICODE_CHAR_éÜ中");
        List<EmbeddingVector> original = Collections.singletonList(vec);

        byte[] serialized = EmbeddingVectorSerializer.serializeList(original);
        assertThat(serialized).isNotNull();
        List<EmbeddingVector> deserialized = EmbeddingVectorSerializer.deserializeList(serialized);

        assertThat(deserialized).hasSize(1);
        assertEmbeddingVectorEquals(deserialized.get(0), original.get(0));
    }

    @Test
    public void testDeserialize_invalidData() {
        byte[] invalidData = new byte[]{1, 2, 3}; // Not a valid format
        assertThrows(IOException.class,
                () -> EmbeddingVectorSerializer.deserializeList(invalidData));
    }

    private void assertEmbeddingVectorEquals(EmbeddingVector actual, EmbeddingVector expected) {
        assertThat(actual.getModelSignature()).isEqualTo(expected.getModelSignature());
        assertThat(actual.getValues()).isEqualTo(expected.getValues());
    }
}
