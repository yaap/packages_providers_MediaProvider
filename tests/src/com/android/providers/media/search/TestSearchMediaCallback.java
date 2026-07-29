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

import android.os.RemoteException;
import android.provider.ISearchMediaCallback;
import android.provider.SearchMediaException;
import android.provider.SearchMediaResultPage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestSearchMediaCallback extends ISearchMediaCallback.Stub {

    private SearchMediaResultPage mSearchMediaResultPage = null;
    private SearchMediaException mSearchMediaException = null;
    private final CountDownLatch mLatch = new CountDownLatch(1);

    @Override
    public void onSearchResultsSuccess(SearchMediaResultPage searchMediaResultPage)
            throws RemoteException {
        mSearchMediaResultPage = searchMediaResultPage;
        mLatch.countDown();
    }

    @Override
    public void onSearchResultsFailure(SearchMediaException searchMediaException) {
        mSearchMediaException = searchMediaException;
        mLatch.countDown();
    }

    /**
     * Wait for search to complete, maximum for given time
     */
    public void await(int time, TimeUnit unit) throws InterruptedException {
        mLatch.await(time, unit);
    }

    public SearchMediaResultPage getSearchMediaResultPage() {
        return mSearchMediaResultPage;
    }

    public SearchMediaException getSearchMediaException() {
        return mSearchMediaException;
    }
}
