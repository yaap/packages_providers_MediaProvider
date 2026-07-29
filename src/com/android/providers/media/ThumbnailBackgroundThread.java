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

package com.android.providers.media;

import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated thread pool for high-priority thumbnail generation in MediaProvider.
 */
public class ThumbnailBackgroundThread {

    // Minimum threads kept alive, even if idle.
    private static final int CORE_THREAD_COUNT = 4;
    // Maximum threads allowed when the queue is saturated.
    private static final int MAX_THREAD_COUNT = 8;
    // Maximum tasks waiting in the queue before scaling up or rejecting.
    private static final int MAX_QUEUE_SIZE = 5000;
    // Idle time extra threads wait before being terminated.
    private static final long KEEP_ALIVE_TIME_SECONDS = 30L;
    private static final String THREAD_NAME = "MediaProvider-ForegroundThumb";

    private static final ExecutorService sExecutor = new ThreadPoolExecutor(
            CORE_THREAD_COUNT,
            MAX_THREAD_COUNT,
            KEEP_ALIVE_TIME_SECONDS, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUE_SIZE),
            r -> {
                // Set to foreground priority so thumbnails load quickly for the user
                return new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
                    r.run();
                }, THREAD_NAME);
            },
            // Explicitly throw RejectedExecutionException if the request limit is hit
            new ThreadPoolExecutor.AbortPolicy()
    );

    private ThumbnailBackgroundThread() {
    }

    /**
     * Returns the singleton bounded executor for thumbnail tasks.
     */
    public static ExecutorService getExecutor() {
        return sExecutor;
    }
}
