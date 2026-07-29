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

package com.android.providers.media.localsearch;

import static com.android.providers.media.DatabaseHelper.EXTERNAL_DATABASE_NAME;
import static com.android.providers.media.localsearch.ProcessingConstants.WORKER_LOCK;
import static com.android.providers.media.localsearch.ProcessingUtils.isMediaProcessingRequired;
import static com.android.providers.media.localsearch.ProcessingHelper.isNetworkAvailable;

import android.content.ContentProviderClient;
import android.content.Context;
import android.os.Trace;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.MediaProvider;
import com.android.providers.media.WorkManagerInitializer;
import com.android.providers.media.appsearch.AppSearchDbManager;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class MediaProcessingRecoveryScheduler extends Worker {
    private static final String TAG = "ProcessingRecoveryJob";
    static final String PERIODIC_WORK_NAME = "MediaProcessingRecoveryJob";
    static final int WORK_INTERVAL_DAYS = 7;
    private Optional<ProcessingHelper> mProcessingHelper;
    private final Context mContext;

    public MediaProcessingRecoveryScheduler(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        mContext = context;
    }

    /**
     * Enqueue unique periodic work to retry failed media processing tasks for local search.
     */
    public static void enqueueWork(Context context) {
        if (isMediaProcessingRequired(context)) {
            WorkManagerInitializer.getWorkManager(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                    createPeriodicWorkRequest());
        }
    }

    private static PeriodicWorkRequest createPeriodicWorkRequest() {
        return new PeriodicWorkRequest.Builder(MediaProcessingRecoveryScheduler.class,
                WORK_INTERVAL_DAYS, TimeUnit.DAYS)
                .setConstraints(new Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresDeviceIdle(true)
                        .build())
                .build();
    }

    @NonNull
    @Override
    public Result doWork() {
        Trace.beginSection("MediaProcessing.doRecoveryWork");
        try {
            return doRecoveryWork();
        } finally {
            Trace.endSection();
        }
    }

    @SuppressWarnings("NewApi")
    private Result doRecoveryWork() {
        Log.d(TAG, "Starting media processing recovery job");

        synchronized (WORKER_LOCK) {
            DatabaseHelper externalDb;
            try (ContentProviderClient cpc = mContext.getContentResolver()
                    .acquireContentProviderClient(MediaStore.AUTHORITY)) {
                MediaProvider provider = (MediaProvider) cpc.getLocalContentProvider();
                if (provider == null) {
                    Log.e(TAG, "Failed to get MediaProvider instance");
                    return Result.failure();
                }

                Optional<DatabaseHelper> dbHelper =
                        provider.getDatabaseHelper(EXTERNAL_DATABASE_NAME);
                if (dbHelper.isEmpty()) {
                    Log.e(TAG, "Failed to get DatabaseHelper instance");
                    return Result.failure();
                }
                externalDb = dbHelper.get();
            }

            try (ProcessingHelper helper = new ProcessingHelper(mContext, externalDb)) {
                mProcessingHelper = Optional.of(helper);

                if (mProcessingHelper.isEmpty()) {
                    Log.v(TAG, "Failed to initialize ProcessingHelper instance");
                    return Result.failure();
                }

                ProcessingHelper processingHelper = mProcessingHelper.get();
                if (processingHelper.getProcessingRequestedPerMediaType().isEmpty()) {
                    Log.v(TAG, "No processing config available or Service unreachable. "
                            + "Skip job run.");
                    return Result.success();
                }

                processingHelper.deleteStaleRowsFromAppSearch();

                processingHelper.enforceAppSearchDocumentLimit(
                        AppSearchDbManager.MAX_DOCUMENT_COUNT);

                if (isNetworkAvailable(mContext)) {
                    processingHelper.runRetryLocationLabels();
                } else {
                    Log.v(TAG, "No network connection. Skip location label processing");
                }

                // TODO(b/428140364) : Add retry for default media label processing

                return Result.success();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to initialize ProcessingHelper instance", e);

                return Result.failure();
            } catch (Exception e) {
                Log.e(TAG, "Failed to complete all requested processing in this job run", e);
                return Result.failure();
            } finally {
                mProcessingHelper = Optional.empty();
            }
        }
    }

    @Override
    public void onStopped() {
        Log.v(TAG, "MediaProcessingRecoveryScheduler stopped.");

        if (mProcessingHelper.isPresent()) {
            mProcessingHelper.get().cancelOutstandingWork();
        }
    }
}
