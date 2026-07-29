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

package com.android.providers.media;

import static com.android.providers.media.DefaultSearchMediaServiceTest.EMBEDDING_DIMENSION;
import static com.android.providers.media.DefaultSearchMediaServiceTest.MODEL_SIG;

import android.content.Context;

import androidx.appsearch.app.EmbeddingVector;

import com.android.providers.media.localsearch.SearchMediaExecutor;

import java.util.Optional;

public class TestSearchMediaExecutor extends SearchMediaExecutor {
    public TestSearchMediaExecutor(Context context) {
        super(context);
    }

    @Override
    public Optional<EmbeddingVector> getEmbeddingForSearchText(String text) {
        float[] vector = new float[EMBEDDING_DIMENSION];
        if (text.contains("cat")) {
            vector[0] = 1.0f; vector[1] = 0.0f;
        } else if (text.contains("dog")) {
            vector[0] = 0.0f; vector[1] = 1.0f;
        } else if (text.contains("house")) {
            vector[0] = 0.8f; vector[1] = 0.1f;
        } else { // Unrelated
            vector[0] = -1.0f; vector[1] = -1.0f;
        }

        return Optional.of(new EmbeddingVector(vector, MODEL_SIG));
    }
}
