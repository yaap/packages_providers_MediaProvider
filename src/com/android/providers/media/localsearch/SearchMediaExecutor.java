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

import static android.provider.SearchMediaService.EXTRA_NEXT_PAGE_TOKEN;
import static android.provider.SearchMediaService.EXTRA_SEARCH_RESULTS_PAGE_SIZE;
import static android.provider.SearchMediaService.EXTRA_SEARCH_RESULTS_SORT_ORDER;
import static android.provider.SearchMediaService.EXTRA_SORT_BY_RELEVANCE;
import static android.provider.SearchMediaService.EXTRA_SORT_BY_TIME;

import static com.android.providers.media.localsearch.SearchMediaExecutorHelper.BUFFER_SIZE;
import static com.android.providers.media.localsearch.SearchMediaExecutorHelper.DEFAULT_PAGE_SIZE;
import static com.android.providers.media.localsearch.SearchMediaExecutorHelper.MAX_SCORE;
import static com.android.providers.media.localsearch.SearchMediaExecutorHelper.SEPARATOR;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.os.Trace;
import android.provider.SearchMediaException;
import android.provider.SearchMediaResult;
import android.provider.SearchMediaResultPage;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.appsearch.app.EmbeddingVector;
import androidx.appsearch.app.GenericDocument;
import androidx.appsearch.app.SearchResult;
import androidx.appsearch.app.SearchResults;
import androidx.appsearch.app.SearchSpec;

import com.android.providers.media.appsearch.AppSearchDbManager;
import com.android.providers.media.appsearch.MediaItem;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SearchMediaExecutor {
    private static final String TAG = SearchMediaExecutor.class.getSimpleName();

    public static final int MAX_ALLOWED_SEARCH_TEXT_LENGTH = 100;
    private final Handler mHandler = SearchMediaServiceBackgroundThread.getHandler();
    private final Map<String, CancellationSignal> mCancellationSignalMap =
            new ConcurrentHashMap<>();

    private AppSearchDbManager mAppSearchDbManager;
    private Optional<RestrictedQueryChecker> mRestrictedQueryChecker;

    public SearchMediaExecutor(Context context) {
        final long start = System.currentTimeMillis();
        try {
            mRestrictedQueryChecker = Optional.of(new RestrictedQueryChecker(context));
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize RestrictedQueryChecker", e);
            mRestrictedQueryChecker = Optional.empty();
        }
        try {
            mAppSearchDbManager = new AppSearchDbManager(context);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AppSearchDbManager", e);
        }
        Log.d(TAG, "SearchMediaExecutor took " + (System.currentTimeMillis() - start)
                + "ms to initialise");
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    void searchMedia(@NonNull String searchText, @NonNull String searchId,
            @NonNull Bundle searchParams,
            @NonNull OutcomeReceiver<SearchMediaResultPage, SearchMediaException> receiver) {
        Trace.beginAsyncSection("SearchMediaExecutor.onSearchMedia", searchId.hashCode());

        if (mAppSearchDbManager == null) {
            receiver.onError(new SearchMediaException(searchId, "AppSearchDbManager is not "
                    + "initialized, cannot perform search. Please try again later.",
                    SearchMediaException.ERROR_UNKNOWN, /* retryable */ true));
            Trace.endAsyncSection("SearchMediaExecutor.onSearchMedia", searchId.hashCode());
            return;
        }

        String uniqueSearchId = getUniqueSearchIdPerUid(searchId);
        if (mCancellationSignalMap.containsKey(uniqueSearchId)) {
            mCancellationSignalMap.get(uniqueSearchId).cancel();
        }
        mCancellationSignalMap.put(uniqueSearchId, new CancellationSignal());

        mHandler.post(() -> {
            final long start = System.currentTimeMillis();
            if (isCancelled(uniqueSearchId)) {
                Log.i(TAG, "Search was cancelled for searchId: " + searchId);
                Trace.endAsyncSection("SearchMediaExecutor.onSearchMedia", searchId.hashCode());
                return;
            }

            if (searchText.length() > MAX_ALLOWED_SEARCH_TEXT_LENGTH) {
                receiver.onError(new SearchMediaException(searchId, "Please limit search text to "
                        + MAX_ALLOWED_SEARCH_TEXT_LENGTH + " characters or less.",
                        SearchMediaException.ERROR_INVALID_ARGUMENTS, /* retryable */ false));
                Trace.endAsyncSection("SearchMediaExecutor.onSearchMedia", searchId.hashCode());
                return;
            }

            if (mRestrictedQueryChecker.isEmpty()) {
                Log.d(TAG, "Query checker failed to load, returning empty results");
                receiver.onResult(new SearchMediaResultPage(searchId,
                        /* searchResults */ new ArrayList<>(), /* extras */ Bundle.EMPTY));
                Trace.endAsyncSection("SearchMediaExecutor.onSearchMedia", searchId.hashCode());
                return;
            }

            if (mRestrictedQueryChecker.get().isQueryRestricted(searchText)) {
                Log.d(TAG, "Query is restricted, returning empty results");
                receiver.onResult(new SearchMediaResultPage(searchId,
                        /* searchResults */ new ArrayList<>(), /* extras */ Bundle.EMPTY));
                Trace.endAsyncSection("SearchMediaExecutor.onSearchMedia", searchId.hashCode());
                return;
            }

            List<SearchMediaResult> searchMediaResults;
            try {
                searchMediaResults = performSearch(searchText, searchParams);
            } catch (Exception e) {
                Log.e(TAG, "Failed to perform search", e);
                try {
                    receiver.onError(new SearchMediaException(
                            searchId,
                            e.getMessage(),
                            SearchMediaException.ERROR_IO,
                            /* retryable */ true
                    ));
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to send error.", ex);
                } finally {
                    mCancellationSignalMap.remove(uniqueSearchId);
                }
                Trace.endAsyncSection("SearchMediaExecutor.onSearchMedia", searchId.hashCode());
                return;
            }

            Bundle extras = new Bundle();
            addPageToken(extras, searchParams, searchMediaResults);

            List<SearchMediaResult> searchResultsPage = limitResultsToPageSize(searchMediaResults,
                    searchParams);
            SearchMediaResultPage searchMediaResultPage = new SearchMediaResultPage(searchId,
                    searchResultsPage, extras);

            Log.d(TAG, "Search results count: " + searchResultsPage.size()
                    + ". Next page token: " + extras.getString(EXTRA_NEXT_PAGE_TOKEN));

            try {
                receiver.onResult(searchMediaResultPage);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to send search results.", ex);
            } finally {
                mCancellationSignalMap.remove(uniqueSearchId);
            }
            Log.d(TAG, "searchMedia took " + (System.currentTimeMillis() - start) + "ms");
            Trace.endAsyncSection("SearchMediaExecutor.onSearchMedia", searchId.hashCode());
        });
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    void cancelSearch(@NonNull String searchId) {
        Trace.beginSection("SearchMediaExecutor.cancelSearch");

        final long start = System.currentTimeMillis();
        Log.d(TAG, "cancelling search request with searchId: " + searchId);

        try {
            CancellationSignal signal = mCancellationSignalMap.remove(
                    getUniqueSearchIdPerUid(searchId));
            if (signal != null) {
                signal.cancel();
            }
        } finally {
            Log.d(TAG, "cancelSearch took " + (System.currentTimeMillis() - start) + "ms");
            Trace.endSection();
        }
    }

    void disconnect() {
        final long start = System.currentTimeMillis();
        if (mAppSearchDbManager != null) {
            mAppSearchDbManager.disconnect();
        }
        mCancellationSignalMap.clear();
        Log.d(TAG, "disconnect() took " + (System.currentTimeMillis() - start) + "ms");
    }

    private String getUniqueSearchIdPerUid(String searchId) {
        return searchId + SEPARATOR + Binder.getCallingUid();
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private List<SearchMediaResult> performSearch(String searchText, Bundle searchParams) throws
            Exception {
        Optional<EmbeddingVector> embedding = getEmbeddingForSearchText(searchText);
        String queryString = SearchMediaExecutorHelper.createAppSearchQueryString(searchText,
                searchParams, embedding);
        SearchSpec searchSpec = SearchMediaExecutorHelper.createAppSearchSpec(searchParams,
                embedding);

        List<SearchResult> results;
        try (SearchResults searchResults =
                     mAppSearchDbManager.searchDocuments(queryString, searchSpec)) {
            results = retrieveSearchResults(searchResults, searchParams);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to get search results from Appsearch DB", ex);
            throw ex;
        }

        return convertToSearchMediaResults(results);
    }

    private static List<SearchResult> retrieveSearchResults(SearchResults searchResults,
            Bundle searchParams) throws Exception {
        String sortOrder = searchParams.getString(EXTRA_SEARCH_RESULTS_SORT_ORDER,
                EXTRA_SORT_BY_RELEVANCE);
        List<SearchResult> results = new ArrayList<>();

        if (EXTRA_SORT_BY_TIME.equalsIgnoreCase(sortOrder)) {
            List<SearchResult> page = searchResults.getNextPageAsync().get();
            if (page != null) {
                results.addAll(page);
            }
        } else {
            String pageToken = searchParams.getString(EXTRA_NEXT_PAGE_TOKEN);
            double maxScore = MAX_SCORE;
            if (pageToken != null) {
                try {
                    maxScore = Double.parseDouble(pageToken);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid page token: " + pageToken, e);
                }
            }
            long requestedPageSize = searchParams.getLong(EXTRA_SEARCH_RESULTS_PAGE_SIZE,
                    DEFAULT_PAGE_SIZE) + BUFFER_SIZE;

            // There can be a edge case where score of multiple search results is same and
            // these search results are split into multiple pages. In this case, the next page may
            // have duplicate search results. We are not handling this edge case as the score of
            // search result being same is rare and handling this edge case scenario would require
            // additional sorting on search results which will make the search query relatively
            // slower.
            List<SearchResult> page = searchResults.getNextPageAsync().get();
            while (page != null && !page.isEmpty() && results.size() < requestedPageSize) {
                for (SearchResult result : page) {
                    if (result.getRankingSignal() <= maxScore) {
                        results.add(result);
                    }
                }
                page = searchResults.getNextPageAsync().get();
            }
        }
        return results;
    }

    private static List<SearchMediaResult> convertToSearchMediaResults(List<SearchResult> results) {
        List<SearchMediaResult> searchMediaResults = new ArrayList<>();
        for (SearchResult result : results) {
            GenericDocument genericDocument = result.getGenericDocument();
            long fileId = genericDocument.getPropertyLong(MediaItem.PROPERTY_FILE_ID);
            long dateTaken = genericDocument.getPropertyLong(MediaItem.PROPERTY_DATE_TAKEN);
            double score = result.getRankingSignal();
            long mediaType = genericDocument.getPropertyLong(MediaItem.PROPERTY_MEDIA_TYPE);
            searchMediaResults.add(new SearchMediaResult(fileId, dateTaken, score, mediaType));
        }
        return searchMediaResults;
    }

    private List<SearchMediaResult> limitResultsToPageSize(
            List<SearchMediaResult> searchMediaResults, Bundle searchParams) {
        long pageSize = searchParams.getLong(EXTRA_SEARCH_RESULTS_PAGE_SIZE, DEFAULT_PAGE_SIZE);
        if (searchMediaResults.size() > pageSize) {
            return searchMediaResults.subList(0, (int) pageSize);
        }
        return searchMediaResults;
    }

    private void addPageToken(Bundle extras, Bundle searchParams,
            List<SearchMediaResult> searchMediaResults) {
        long pageSize = searchParams.getLong(EXTRA_SEARCH_RESULTS_PAGE_SIZE, DEFAULT_PAGE_SIZE);

        if (searchMediaResults.size() > pageSize) {
            SearchMediaResult extraResult = searchMediaResults.get((int) pageSize);
            String sortOrder = searchParams.getString(EXTRA_SEARCH_RESULTS_SORT_ORDER,
                    EXTRA_SORT_BY_RELEVANCE);
            String token;
            if (sortOrder.equalsIgnoreCase(EXTRA_SORT_BY_TIME)) {
                token = String.valueOf(extraResult.getDateTaken());
            } else {
                token = String.valueOf(extraResult.getScore());
            }
            extras.putString(EXTRA_NEXT_PAGE_TOKEN, token);
        }
    }

    private boolean isCancelled(String searchId) {
        if (mCancellationSignalMap.containsKey(searchId)) {
            return mCancellationSignalMap.get(searchId).isCanceled();
        }

        return true;
    }

    @VisibleForTesting
    protected Optional<EmbeddingVector> getEmbeddingForSearchText(String searchText) {
        try {
            Trace.beginSection("SearchMediaExecutor.getEmbeddingForSearchText");
            // TODO: this EmbeddingVector should be retrieved from {@link MediaProcessingService},
            //  support for which will be added in future.
            return Optional.empty();
        } finally {
            Trace.endSection();
        }
    }
}
