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

import static com.android.providers.media.localsearch.ProcessingUtils.isDefaultSearchMediaServiceSupported;

import android.annotation.NonNull;
import android.os.Build;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.provider.SearchMediaException;
import android.provider.SearchMediaResultPage;
import android.provider.SearchMediaService;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

/**
 * Default implementation of {@link SearchMediaService} that performs local media search using
 * AppSearch.
 *
 * <p>All search operations are performed asynchronously on a dedicated background thread.
 *
 * <p>Usage of this class is only supported on devices running Android T (API 33) or higher and
 * when the {@code enable_media_search} flag is enabled.
 */
public class DefaultSearchMediaService extends SearchMediaService {
    private static final String TAG = DefaultSearchMediaService.class.getSimpleName();
    private SearchMediaExecutor mSearchMediaExecutor;

    @Override
    public void onCreate() {
        super.onCreate();

        if (!isDefaultSearchMediaServiceSupported(this)) {
            Log.e(TAG, "DefaultSearchMediaService is not supported.");
            return;
        }

        try {
            mSearchMediaExecutor = new SearchMediaExecutor(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SearchMediaServiceExecutor", e);
        }
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void onSearchMedia(@NonNull String searchText, @NonNull String searchId,
            @NonNull Bundle searchParams,
            @NonNull OutcomeReceiver<SearchMediaResultPage, SearchMediaException> receiver) {
        if (!isDefaultSearchMediaServiceSupported(this)) {
            throw new UnsupportedOperationException("DefaultSearchMediaService is not supported.");
        }

        if (mSearchMediaExecutor == null) {
            Log.e(TAG, "SearchMediaExecutor is not initialized.");
            receiver.onError(new SearchMediaException(searchId,
                    "SearchMediaExecutor is not initialized.",
                    SearchMediaException.ERROR_UNKNOWN,
                    /* retryable */ true));
            return;
        }

        mSearchMediaExecutor.searchMedia(searchText, searchId, searchParams, receiver);
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void onCancelSearch(@NonNull String searchId) {
        if (!isDefaultSearchMediaServiceSupported(this)) {
            throw new UnsupportedOperationException("DefaultSearchMediaService is not supported.");
        }

        if (mSearchMediaExecutor == null) {
            return;
        }

        mSearchMediaExecutor.cancelSearch(searchId);
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public boolean onCheckSemanticSearchSupport() {
        if (!isDefaultSearchMediaServiceSupported(this)) {
            throw new UnsupportedOperationException("DefaultSearchMediaService is not supported.");
        }

        // currently semantic search is not supported.
        return false;
    }

    @Override
    public void onDestroy() {
        if (!isDefaultSearchMediaServiceSupported(this)) {
            Log.e(TAG, "DefaultSearchMediaService is not supported.");
            return;
        }

        if (mSearchMediaExecutor != null) {
            mSearchMediaExecutor.disconnect();
        }
    }

    /**
     * To be used for testing only
     */
    @VisibleForTesting
    protected void setSearchMediaExecutor(SearchMediaExecutor searchMediaExecutor) {
        mSearchMediaExecutor = searchMediaExecutor;
    }
}
