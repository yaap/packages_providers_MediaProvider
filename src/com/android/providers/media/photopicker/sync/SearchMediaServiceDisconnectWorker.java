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

package com.android.providers.media.photopicker.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.photopicker.PhotoPickerLocalSearchManager;

/**
 * Worker to disconnect the SearchMediaService after a period of inactivity and when the device is
 * idle.
 */
public class SearchMediaServiceDisconnectWorker extends Worker {
    private static final String TAG = "SearchServiceDisconnectWorker";

    public SearchMediaServiceDisconnectWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.v(TAG, "Executing SearchServiceDisconnectWorker to disconnect service.");

        try {
            PhotoPickerLocalSearchManager.getInstance(getApplicationContext()).stop();
        } catch (Exception e) {
            Log.e(TAG, "Failed to disconnect SearchMediaService.", e);
            return Result.failure();
        }

        return Result.success();
    }
}
