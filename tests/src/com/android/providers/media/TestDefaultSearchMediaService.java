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

import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.providers.media.localsearch.DefaultSearchMediaService;
import com.android.providers.media.localsearch.SearchMediaExecutor;

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class TestDefaultSearchMediaService extends DefaultSearchMediaService {
    private static final String TAG = TestDefaultSearchMediaService.class.getSimpleName();

    @Override
    public void onCreate() {
        SearchMediaExecutor searchMediaExecutor = null;
        try {
            searchMediaExecutor = new TestSearchMediaExecutor(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize SearchMediaServiceExecutor", e);
        }
        setSearchMediaExecutor(searchMediaExecutor);
    }

    @Override
    public boolean onCheckSemanticSearchSupport() {
        return true;
    }
}
