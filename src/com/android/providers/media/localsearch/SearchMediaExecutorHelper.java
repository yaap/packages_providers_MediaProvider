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

package com.android.providers.media.localsearch;

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_NONE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
import static android.provider.SearchMediaService.EXTRA_MEDIA_TYPE_FILTER;
import static android.provider.SearchMediaService.EXTRA_NEXT_PAGE_TOKEN;
import static android.provider.SearchMediaService.EXTRA_SEARCH_RESULTS_PAGE_SIZE;
import static android.provider.SearchMediaService.EXTRA_SEARCH_RESULTS_SORT_ORDER;
import static android.provider.SearchMediaService.EXTRA_SORT_BY_RELEVANCE;
import static android.provider.SearchMediaService.EXTRA_SORT_BY_TIME;

import static androidx.appsearch.app.SearchSpec.EMBEDDING_SEARCH_METRIC_TYPE_COSINE;
import static androidx.appsearch.app.SearchSpec.ORDER_DESCENDING;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appsearch.app.EmbeddingVector;
import androidx.appsearch.app.SearchSpec;

import com.android.providers.media.appsearch.AppSearchDbManager;
import com.android.providers.media.appsearch.MediaItem;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class to assist {@link DefaultSearchMediaService} in building {@link SearchSpec}
 * and query string for getting search results from Appsearch db.
 */
public final class SearchMediaExecutorHelper {
    static final int DEFAULT_PAGE_SIZE = 100;
    static final int BUFFER_SIZE = 1;
    /** The default upper bound for score. */
    static final double MAX_SCORE = Double.MAX_VALUE;
    static final String SEPARATOR = "_";

    /** The default upper bound for date taken in milliseconds. */
    private static final long MAX_DATE_TAKEN_MILLIS = Long.MAX_VALUE;
    private static final double SEMANTIC_SEARCH_THRESHOLD = 0.7;

    private static final String OR_CONNECTOR = " OR ";
    private static final String AND_CONNECTOR = " AND ";
    private static final String SEMANTIC_SEARCH_FUNC_START =
            "semanticSearch(getEmbeddingParameter(0), ";
    private static final String SEMANTIC_SEARCH_FUNC_END = ", 1.0)";

    private static final String EXPR_SEMANTIC_SCORE =
            "maxOrDefault(this.matchedSemanticScores(getEmbeddingParameter(0)),0.0)";
    private static final String EXPR_RELEVANCE_SCORE = "this.relevanceScore()";
    private static final double RELEVANCE_SCORE_WEIGHT = 0.4;
    private static final double SEMANTIC_SCORE_WEIGHT = 0.8;
    private static final String EXPR_RANKING_RELEVANCE = RELEVANCE_SCORE_WEIGHT + " * "
            + EXPR_RELEVANCE_SCORE + " + " + SEMANTIC_SCORE_WEIGHT + " * " + EXPR_SEMANTIC_SCORE;
    private static final String EXPR_RANKING_TIME = "(maxOrDefault(getScorableProperty(\""
            + MediaItem.SCHEMA_TYPE + "\", \"" + MediaItem.PROPERTY_DATE_TAKEN + "\"), 0.0))";
    private static final String IMAGE = "image";
    private static final String VIDEO = "video";
    private static final String AUDIO = "audio";
    private static final String DOCUMENT = "document";

    private SearchMediaExecutorHelper() {}

    @NonNull
    static SearchSpec createAppSearchSpec(@NonNull Bundle searchParams,
            @NonNull Optional<EmbeddingVector> queryEmbedding) {
        SearchSpec.Builder searchSpecBuilder = new SearchSpec.Builder();
        addDefaultSearchFilters(searchSpecBuilder);
        addPageSizeFilters(searchSpecBuilder, searchParams);
        if (queryEmbedding.isPresent()) {
            addQueryEmbedding(searchSpecBuilder, queryEmbedding.get());
        }
        addSortingFilters(searchSpecBuilder, searchParams, queryEmbedding);
        return searchSpecBuilder.build();
    }

    /**
     * Builds the query string for getting search results from Appsearch db.
     *
     * <p>Sample queries:
     *
     * <ul>
     *   <li>EXTRA_SORT_BY_RELEVANCE:
     *       <pre>{@code
     *(cat OR semanticSearch(getEmbeddingParameter(0), 0.7, 1.0))
     *AND (mediaType == 1 OR mediaType == 2) AND (dirty == 0)
     *       }</pre>
     *   <li>EXTRA_SORT_BY_TIME:
     *       <pre>{@code
     *(cat OR semanticSearch(getEmbeddingParameter(0), 0.7, 1.0)) AND
     *(dateTaken <= 10129) AND  (mediaType == 1 OR mediaType == 2) AND
     *(dirty == 0)
     *       }</pre>
     * </ul>
     */
    @NonNull
    static String createAppSearchQueryString(@NonNull String searchText,
            @NonNull Bundle searchParams, Optional<EmbeddingVector> embeddingVector) {
        StringBuilder builder = new StringBuilder();
        builder.append("(");
        builder.append(searchText);

        if (embeddingVector.isPresent()) {
            builder.append(OR_CONNECTOR);
            builder.append(SEMANTIC_SEARCH_FUNC_START)
                    .append(SEMANTIC_SEARCH_THRESHOLD)
                    .append(SEMANTIC_SEARCH_FUNC_END);
        }

        builder.append(")");

        String sortOrder = searchParams.getString(EXTRA_SEARCH_RESULTS_SORT_ORDER,
                EXTRA_SORT_BY_RELEVANCE);

        if (EXTRA_SORT_BY_TIME.equalsIgnoreCase(sortOrder)) {
            addTimeQueryFilter(builder, searchParams);
        }

        addMediaTypeFilter(builder, searchParams);
        addDirtyFilter(builder);
        return builder.toString();
    }

    private static void addTimeQueryFilter(StringBuilder builder, Bundle searchParams) {
        String pageToken = searchParams.getString(EXTRA_NEXT_PAGE_TOKEN, null);

        long maxDateTaken = MAX_DATE_TAKEN_MILLIS;
        if (pageToken != null) {
            try {
                maxDateTaken = Long.parseLong(pageToken);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid page token format: " + pageToken, e);
            }
        }

        String queryExpression = "(" + MediaItem.PROPERTY_DATE_TAKEN + " <= " + maxDateTaken + ")";

        builder.append(AND_CONNECTOR);
        builder.append(queryExpression);
    }

    private static void addDefaultSearchFilters(SearchSpec.Builder searchSpecBuilder) {
        searchSpecBuilder.addFilterNamespaces(AppSearchDbManager.NAMESPACE);
        searchSpecBuilder.setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY);
        searchSpecBuilder.addFilterSchemas(MediaItem.SCHEMA_TYPE);
        searchSpecBuilder.setListFilterQueryLanguageEnabled(true);
        searchSpecBuilder.setNumericSearchEnabled(true);
        searchSpecBuilder.setScorablePropertyRankingEnabled(true);
    }

    private static void addQueryEmbedding(SearchSpec.Builder searchSpecBuilder,
            @NonNull EmbeddingVector queryEmbedding) {
        searchSpecBuilder.addEmbeddingParameters(queryEmbedding);
        searchSpecBuilder.setDefaultEmbeddingSearchMetricType(EMBEDDING_SEARCH_METRIC_TYPE_COSINE);
    }

    private static void addSortingFilters(SearchSpec.Builder searchSpecBuilder,
            Bundle searchParams, Optional<EmbeddingVector> queryEmbedding) {
        String sortOrder = searchParams.getString(EXTRA_SEARCH_RESULTS_SORT_ORDER,
                EXTRA_SORT_BY_RELEVANCE);
        if (EXTRA_SORT_BY_TIME.equalsIgnoreCase(sortOrder)) {
            searchSpecBuilder.setRankingStrategy(EXPR_RANKING_TIME);
        } else {
            if (queryEmbedding.isPresent()) {
                searchSpecBuilder.setRankingStrategy(EXPR_RANKING_RELEVANCE);
            } else {
                searchSpecBuilder.setRankingStrategy(EXPR_RELEVANCE_SCORE);
            }
        }
        searchSpecBuilder.setOrder(ORDER_DESCENDING);
    }

    private static void addMediaTypeFilter(StringBuilder builder, Bundle searchParams) {
        String[] mediaTypes = searchParams.getStringArray(EXTRA_MEDIA_TYPE_FILTER);

        if (mediaTypes != null && mediaTypes.length > 0) {
            Set<Integer> allowedMediaTypes = getAllowedMediaTypes(mediaTypes);
            String mediaTypeQuery = allowedMediaTypes.stream()
                    .map(type -> MediaItem.PROPERTY_MEDIA_TYPE + " == " + type)
                    .collect(Collectors.joining(OR_CONNECTOR));

            builder.append(AND_CONNECTOR).append("(").append(mediaTypeQuery).append(")");
        }
    }

    @NonNull
    private static Set<Integer> getAllowedMediaTypes(String[] mediaTypes) {
        Set<Integer> allowedMediaTypes = new HashSet<>();
        for (String mediaType : mediaTypes) {
            switch (mediaType.toLowerCase(Locale.ROOT)) {
                case IMAGE -> allowedMediaTypes.add(MEDIA_TYPE_IMAGE);
                case VIDEO -> allowedMediaTypes.add(MEDIA_TYPE_VIDEO);
                case AUDIO -> allowedMediaTypes.add(MEDIA_TYPE_AUDIO);
                case DOCUMENT -> allowedMediaTypes.add(MEDIA_TYPE_DOCUMENT);
                default -> allowedMediaTypes.add(MEDIA_TYPE_NONE);
            }
        }
        return allowedMediaTypes;
    }

    private static void addDirtyFilter(StringBuilder builder) {
        builder.append(AND_CONNECTOR).append("(").append(MediaItem.PROPERTY_DIRTY).append(" == 0)");
    }

    private static void addPageSizeFilters(SearchSpec.Builder searchSpecBuilder,
            Bundle searchParams) {
        long pageSize = searchParams.getLong(EXTRA_SEARCH_RESULTS_PAGE_SIZE, DEFAULT_PAGE_SIZE);
        int resultsCount = (int) (pageSize + BUFFER_SIZE);
        searchSpecBuilder.setResultCountPerPage(resultsCount);
    }
}
