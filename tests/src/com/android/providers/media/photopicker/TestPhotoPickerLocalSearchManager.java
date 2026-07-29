/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.providers.media.photopicker;

import android.content.Context;
import android.util.Log;

import androidx.work.OneTimeWorkRequest;

import com.android.providers.media.photopicker.sync.SearchMediaServiceDisconnectWorker;

import java.util.concurrent.TimeUnit;

public class TestPhotoPickerLocalSearchManager extends PhotoPickerLocalSearchManager {
    private static final String TAG = "TestLocalSearchManager";
    public static final long DISCONNECT_SEARCH_SERVICE_DELAY_SECONDS = 3;

    public TestPhotoPickerLocalSearchManager(Context context) {
        super(context);
    }

    @Override
    public OneTimeWorkRequest getSearchMediaServiceDisconnectWorkRequest() {
        Log.v(TAG, "Scheduling SearchMediaService disconnect in "
                + DISCONNECT_SEARCH_SERVICE_DELAY_SECONDS + " seconds.");
        return new OneTimeWorkRequest.Builder(SearchMediaServiceDisconnectWorker.class)
                .setInitialDelay(DISCONNECT_SEARCH_SERVICE_DELAY_SECONDS, TimeUnit.SECONDS)
                .build();
    }
}
