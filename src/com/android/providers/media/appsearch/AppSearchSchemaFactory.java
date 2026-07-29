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

package com.android.providers.media.appsearch;

import static androidx.appsearch.app.AppSearchSchema.EmbeddingPropertyConfig.INDEXING_TYPE_SIMILARITY;
import static androidx.appsearch.app.AppSearchSchema.LongPropertyConfig.INDEXING_TYPE_RANGE;
import static androidx.appsearch.app.AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL;
import static androidx.appsearch.app.AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED;
import static androidx.appsearch.app.AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED;
import static androidx.appsearch.app.AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS;
import static androidx.appsearch.app.AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN;

import androidx.appsearch.app.AppSearchSchema;
import androidx.appsearch.app.SetSchemaRequest;

/**
 * Factory for creating the AppSearch schema for MediaProvider programmatically.
 */
public final class AppSearchSchemaFactory {

    private AppSearchSchemaFactory() {}

    /**
     * Builds the {@link SetSchemaRequest} for all media documents.
     */
    public static SetSchemaRequest buildSchema() {
        return new SetSchemaRequest.Builder()
                .addSchemas(
                        new AppSearchSchema.Builder(MediaItem.SCHEMA_TYPE)
                                .addProperty(new AppSearchSchema.LongPropertyConfig.Builder(
                                        MediaItem.PROPERTY_FILE_ID)
                                        .setCardinality(CARDINALITY_REQUIRED)
                                        .setIndexingType(INDEXING_TYPE_RANGE)
                                        .setScoringEnabled(true)
                                        .build())
                                .addProperty(new AppSearchSchema.LongPropertyConfig.Builder(
                                        MediaItem.PROPERTY_DATE_TAKEN)
                                        .setCardinality(CARDINALITY_REQUIRED)
                                        .setIndexingType(INDEXING_TYPE_RANGE)
                                        .setScoringEnabled(true)
                                        .build())
                                .addProperty(new AppSearchSchema.LongPropertyConfig.Builder(
                                        MediaItem.PROPERTY_MEDIA_TYPE)
                                        .setCardinality(CARDINALITY_REQUIRED)
                                        .setIndexingType(INDEXING_TYPE_RANGE)
                                        .build())
                                .addProperty(new AppSearchSchema.LongPropertyConfig.Builder(
                                        MediaItem.PROPERTY_DIRTY)
                                        .setCardinality(CARDINALITY_REQUIRED)
                                        .setIndexingType(INDEXING_TYPE_RANGE)
                                        .build())
                                .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                                        MediaItem.PROPERTY_METADATA_EXTRACTED)
                                        .setCardinality(CARDINALITY_OPTIONAL)
                                        .setIndexingType(INDEXING_TYPE_EXACT_TERMS)
                                        .setTokenizerType(TOKENIZER_TYPE_PLAIN)
                                        .build())
                                .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                                        MediaItem.PROPERTY_LOCATION_EXTRACTED)
                                        .setCardinality(CARDINALITY_OPTIONAL)
                                        .setIndexingType(
                                                INDEXING_TYPE_EXACT_TERMS)
                                        .setTokenizerType(TOKENIZER_TYPE_PLAIN)
                                        .build())
                                .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                                        MediaItem.PROPERTY_LABELS_EXTRACTED)
                                        .setCardinality(CARDINALITY_OPTIONAL)
                                        .setIndexingType(
                                                INDEXING_TYPE_EXACT_TERMS)
                                        .setTokenizerType(TOKENIZER_TYPE_PLAIN)
                                        .build())
                                .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                                        MediaItem.PROPERTY_VOLUME_NAME)
                                        .setCardinality(CARDINALITY_REQUIRED)
                                        .setIndexingType(
                                                INDEXING_TYPE_EXACT_TERMS)
                                        .setTokenizerType(TOKENIZER_TYPE_PLAIN)
                                        .build())
                                .addProperty(new AppSearchSchema.EmbeddingPropertyConfig.Builder(
                                        MediaItem.PROPERTY_EMBEDDINGS)
                                        .setCardinality(CARDINALITY_REPEATED)
                                        .setIndexingType(INDEXING_TYPE_SIMILARITY)
                                        .build())
                                .build())
                .build();
    }
}
