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

package com.android.providers.media.util;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import androidx.annotation.NonNull;
import androidx.work.WorkManager;


import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A singleton utility class that provides a pre-configured {@link ThreadPoolExecutor}
 * for executing background tasks. This ensures that background operations do not block the
 * main thread or binder thread, maintaining application responsiveness and performance.
 *
 * This thread pool should be used for relatively quick tasks that don't complicated scheduling
 * constraints or logic. For longer running tasks or tasks that require more complex scheduling
 * and retry logic, please use {@link WorkManager}.
 *
 * The thread pool is initialized lazily using the Initialization-on-demand holder
 * design pattern, ensuring thread-safe and efficient singleton creation, without the overhead
 * of locking mechanisms.
 *
 * Threads within this pool are given {@link android.os.Process#THREAD_PRIORITY_BACKGROUND}
 * to minimize their impact on foreground performance.
 */
public class BackgroundThreadPool {
    /**
     * The number of core threads in the pool.
     * These threads will be terminated after the keep alive time has passed when threads
     * are idle. This is done because `allowCoreThreadTimeOut` is set to true, because we are
     * generally ok with the additional latency of bringing up a new thread when the
     * total pool size < core pool size in case of background tasks.
     */
    private static final int CORE_POOL_SIZE = 2;

    /**
     * The maximum number of threads allowed in the pool.
     * When the work queue is full, new tasks will cause new threads to be created up to this limit.
     * This should very rarely be required.
     */
    private static final int MAX_POOL_SIZE = 5;

    /**
     * The maximum time that idle worker threads will wait for new tasks before terminating.
     * This applies to all threads in the pool - both core and non-core threads as
     * `allowCoreThreadTimeOut` is set to true.
     */
    private static final int KEEP_ALIVE_TIME_IN_SECONDS = 60;

    /**
     * The maximum number of tasks that can be queued up when all core threads are busy.
     *
     * We should keep the tasks in this queue very high, but not unbounded to avoid OOM in
     * exceptional situations.
     */
    private static final int MAX_QUEUE_SIZE = 100;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private BackgroundThreadPool() {}

    /**
     * Returns the singleton instance of the {@link Executor} for background tasks.
     *
     * Please ensure your task that runs on this executor handles {@link RejectedExecutionException}
     * because if the queue is full and all threads are busy, the executor will throw a
     * {@link RejectedExecutionException}
     *
     * @return An {@link Executor} instance configured for background operations.
     */
    public static Executor getExecutor() {
        return SingletonHolder.sExecutor;
    }

    /**
     * Inner static class to hold the singleton instance of the {@link ThreadPoolExecutor}.
     * This leverages the Initialization-on-demand holder idiom for lazy and thread-safe
     * initialization of the executor. The `EXECUTOR` instance is only created when
     * `getExecutor()` is first called.
     */
    private static class SingletonHolder {
        @NonNull
        private static ThreadPoolExecutor sExecutor = initExecutor();

        /**
         * Initializes and configures the {@link ThreadPoolExecutor}.
         * This method is called only once when the `SingletonHolder` class is first loaded.
         *
         * @return A fully configured {@link ThreadPoolExecutor} instance.
         */
        @NonNull
        private static ThreadPoolExecutor initExecutor() {
            // Create a custom thread factory with the thread priority set to background.
            final ThreadFactory threadFactory =
                    ThreadFactoryUtil.getThreadFactory(
                            /* namePrefix */ "background-pool-",
                            THREAD_PRIORITY_BACKGROUND);

            sExecutor = new ThreadPoolExecutor(
                    CORE_POOL_SIZE,
                    MAX_POOL_SIZE,
                    KEEP_ALIVE_TIME_IN_SECONDS,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(MAX_QUEUE_SIZE),
                    threadFactory,
                    new ThreadPoolExecutor.AbortPolicy()
            );

            sExecutor.allowCoreThreadTimeOut(true);

            return sExecutor;
        }
    }
}
