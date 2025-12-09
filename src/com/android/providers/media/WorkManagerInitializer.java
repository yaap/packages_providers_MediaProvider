/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Configuration;
import androidx.work.WorkManager;

import com.android.providers.media.flags.Flags;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class WorkManagerInitializer {
    private static final String TAG = "WorkManagerInitializer";
    // Thread pool size should be at least equal to the number of unique work requests in
    // {@link PickerSyncManager} to ensure that any request type is not blocked on other request
    // types. It is advisable to use unique work requests because in case the number of queued
    // requests grows, they should not block other work requests.
    private static final int WORK_MANAGER_THREAD_POOL_SIZE = 15;

    private static final int POOL_SIZE = 30;
    private static final int QUEUE_SIZE = 50;
    private static final long KEEP_ALIVE_TIME_SECONDS = 60;
    @Nullable
    private static volatile Executor sWorkManagerExecutor;

    /**
     * Initialize the {@link WorkManager} if it is not initialized already.
     *
     * @return a {@link WorkManager} object that can be used to run work requests.
     */
    @NonNull
    public static WorkManager getWorkManager(Context mContext) {
        if (!WorkManager.isInitialized()) {
            Log.i(TAG, "Work manager not initialised. Attempting to initialise.");
            WorkManager.initialize(mContext, getWorkManagerConfiguration());
        }
        return WorkManager.getInstance(mContext);
    }

    @NonNull
    private static Configuration getWorkManagerConfiguration() {
        ensureWorkManagerExecutor();
        return new Configuration.Builder()
                .setMinimumLoggingLevel(Log.INFO)
                .setExecutor(sWorkManagerExecutor)
                .build();
    }

    private static void ensureWorkManagerExecutor() {
        if (sWorkManagerExecutor == null) {
            synchronized (WorkManagerInitializer.class) {
                if (sWorkManagerExecutor == null) {
                    if (Flags.enableCustomWorkManagerThreadPool()) {
                        Log.i(TAG, "Using custom thread executor for WorkManager.");
                        sWorkManagerExecutor = getCustomThreadPoolExecutor();
                    } else {
                        sWorkManagerExecutor = Executors
                                .newFixedThreadPool(WORK_MANAGER_THREAD_POOL_SIZE);
                    }
                }
            }
        }
    }

    /**
     * This thread pool can spawn a fixed number of threads, defined by {@code POOL_SIZE}. These
     * threads are destroyed if the threads are idle for more than {@code KEEP_ALIVE_TIME_SECONDS}.
     * <p>
     * This is different from previously used fixed thread pool which retained all threads
     * indefinitely, even when they are idle. This resulted in unnecessary resource retention.
     * This executor can scale based on demand and release resources when threads are no longer
     * needed.
     *
     * @return a {@link Executor} optimized for scalability
     */
    private static Executor getCustomThreadPoolExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(/* corePoolSize */ POOL_SIZE,
                /* maxPoolSize */ POOL_SIZE, KEEP_ALIVE_TIME_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_SIZE), new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return  executor;
    }
}
