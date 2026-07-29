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

package com.android.providers.media.search;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.provider.SearchMediaException;
import android.provider.SearchMediaResult;
import android.provider.SearchMediaResultPage;
import android.provider.SearchMediaService;

import java.util.ArrayList;
import java.util.List;

public class TestSearchMediaService extends SearchMediaService {

    public static final String SHOULD_THROW_ERROR = "should_throw_error";
    public static final String DUMMY_SEARCH_RESULT_SIZE = "dummy_search_result_size";
    public static final int DEFAULT_ERROR_CODE = SearchMediaException.ERROR_UNKNOWN;
    public static final String DEFAULT_ERROR_MESSAGE = "Failed to get search results";

    @Override
    public void onSearchMedia(@NonNull String searchText, @NonNull String searchId,
            @NonNull Bundle searchParams, @NonNull OutcomeReceiver<SearchMediaResultPage,
                    SearchMediaException> outcomeReceiver) {
        boolean shouldThrowError =  searchParams.getBoolean(SHOULD_THROW_ERROR, false);
        if (shouldThrowError) {
            SearchMediaException searchMediaException = new SearchMediaException(
                    searchId, DEFAULT_ERROR_MESSAGE, DEFAULT_ERROR_CODE, /* retryable */ false);
            outcomeReceiver.onError(searchMediaException);
        } else {
            Bundle extras = new Bundle();
            extras.putBundle(EXTRA_NEXT_PAGE_TOKEN, new Bundle());
            SearchMediaResultPage searchMediaResultPage = new SearchMediaResultPage(searchId,
                    getSearchResults(searchParams), extras);
            outcomeReceiver.onResult(searchMediaResultPage);
        }
    }

    @Override
    public void onCancelSearch(@NonNull String searchId) {

    }

    @Override
    public boolean onCheckSemanticSearchSupport() {
        return true;
    }

    static List<SearchMediaResult> getSearchResults(Bundle searchParams) {
        long numResults = searchParams.getLong(DUMMY_SEARCH_RESULT_SIZE, 100L);
        List<SearchMediaResult> searchMediaResults = new ArrayList<>();
        for (int i = 0; i < numResults; i++) {
            SearchMediaResult searchMediaResult = new SearchMediaResult(
                    /* id */ i, /* dateTaken */ 100000L,
                    /* score */ 0.9 - 0.001 * i, /* mediaType */ 1);
            searchMediaResults.add(searchMediaResult);
        }
        return searchMediaResults;
    }
}
