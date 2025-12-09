/*
 *  * Copyright (C) 2025 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.utils.HandlerExecutor;

import java.util.concurrent.Executor;

/**
 * Provides shared background threads for the MediaProvider.
 *
 * <p>Maintains a general-purpose thread and a dedicated thread for database operations.
 * Access is provided via static {@link Handler} and {@link Executor} methods.
 */
public class MediaBackgroundThread {
    private static final String MEDIA_BACKGROUND_THREAD = "MediaBackgroundThread";
    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static HandlerThread sInstance;
    @GuardedBy("sLock")
    private static Handler sHandler;
    @GuardedBy("sLock")
    private static Executor sExecutor;

    private static final String MEDIA_DB_OPS_BACKGROUND_THREAD = "MediaDbOpsBackgroundThread";
    private static final Object sDbOpsLock = new Object();
    @GuardedBy("sDbOpsLock")
    private static HandlerThread sDbOpsInstance;
    @GuardedBy("sDbOpsLock")
    private static Handler sDbOpsHandler;
    @GuardedBy("sDbOpsLock")
    private static Executor sDbOpsExecutor;

    private MediaBackgroundThread() { }

    @GuardedBy("sLock")
    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new HandlerThread(MEDIA_BACKGROUND_THREAD,
                    Process.THREAD_PRIORITY_BACKGROUND);
            sInstance.start();
            sHandler = new Handler(sInstance.getLooper());
            sExecutor = new HandlerExecutor(sHandler);
        }
    }

    @GuardedBy("sDbOpsLock")
    private static void ensureDbOpsThreadLocked() {
        if (sDbOpsInstance == null) {
            sDbOpsInstance = new HandlerThread(MEDIA_DB_OPS_BACKGROUND_THREAD,
                    Process.THREAD_PRIORITY_BACKGROUND);
            sDbOpsInstance.start();
            sDbOpsHandler = new Handler(sDbOpsInstance.getLooper());
            sDbOpsExecutor = new HandlerExecutor(sDbOpsHandler);
        }
    }


    /**
     * Returns the Handler for general background tasks.
     * This should not be used for DB operations.
     */
    @NonNull
    public static Handler getHandler() {
        synchronized (sLock) {
            ensureThreadLocked();
            return sHandler;
        }
    }

    /**
     * Returns the Executor for general background tasks.
     * This should not be used for DB operations.
     */
    @NonNull
    public static Executor getExecutor() {
        synchronized (sLock) {
            ensureThreadLocked();
            return sExecutor;
        }
    }


    /**
     * Returns the Handler for database background tasks.
     * This should only be used for DB operations.
     */
    @NonNull
    public static Handler getDbOpsHandler() {
        synchronized (sDbOpsLock) {
            ensureDbOpsThreadLocked();
            return sDbOpsHandler;
        }
    }

    /**
     * Returns the Executor for database background tasks.
     * This should only be used for DB operations.
     */
    @NonNull
    public static Executor getDbOpsExecutor() {
        synchronized (sDbOpsLock) {
            ensureDbOpsThreadLocked();
            return sDbOpsExecutor;
        }
    }
}
