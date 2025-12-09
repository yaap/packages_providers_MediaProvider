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

import static android.os.Process.THREAD_PRIORITY_FOREGROUND;

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
 * for executing foreground tasks. This gives flexibility over adding timeouts to foreground tasks
 * when decorated with {@link CompleteableFuture} in order to maintaining application performance.
 *
 * This thread pool should be used for relatively quick tasks that don't complicated scheduling
 * constraints or logic. For longer running tasks or tasks that require more complex scheduling
 * and retry logic, please use {@link WorkManager}.
 *
 * The thread pool is initialized lazily using the Initialization-on-demand holder
 * design pattern, ensuring thread-safe and efficient singleton creation, without the overhead
 * of locking mechanisms.
 *
 * Threads within this pool are given {@link android.os.Process#THREAD_PRIORITY_FOREGROUND}
 * to minimize their impact on foreground performance.
 */
public class ForegroundThreadPool {
    /**
     * The number of core threads in the pool.
     * Once created, these threads will be be kept alive even if idle. Please note that if the
     * number of threads in the thread pool < core threads, even if one or more threads are idle,
     * a new one will be created to run the task.
     */
    private static final int CORE_POOL_SIZE = 2;

    /**
     * The maximum number of threads allowed in the pool.
     * When the work queue is full, new tasks will cause new threads to be created up to this limit.
     * This should very rarely be required.
     */
    private static final int MAX_POOL_SIZE = 20;

    /**
     * The maximum time that idle worker threads will wait for new tasks before terminating.
     * This does not apply to core threads in the pool.
     */
    private static final int KEEP_ALIVE_TIME_IN_SECONDS = 60;

    /**
     * The maximum number of tasks that can be queued up when all core threads are busy.
     *
     * We should keep the size of this queue very low, so that new threads can get created if the
     * workload becomes much higher than expected. The queue is only present to help prevent
     * resource exhaustion when a burst of tasks flow in but are quick enough to not require
     * additional threads.
     */
    private static final int MAX_QUEUE_SIZE = 5;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ForegroundThreadPool() {}

    /**
     * Returns the singleton instance of the {@link Executor} for foreground tasks.
     *
     * Please ensure your task that runs on this executor handles {@link RejectedExecutionException}
     * because if all threads are busy, the executor will throw a {@link RejectedExecutionException}
     *
     * @return An {@link Executor} instance configured for foreground operations.
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
        private static Executor sExecutor = initExecutor();

        /**
         * Initializes and configures the {@link ThreadPoolExecutor}.
         * This method is called only once when the `SingletonHolder` class is first loaded.
         *
         * @return A fully configured {@link ThreadPoolExecutor} instance.
         */
        @NonNull
        private static Executor initExecutor() {
            // Create a custom thread factory with the thread priority set to foreground.
            final ThreadFactory threadFactory =
                    ThreadFactoryUtil.getThreadFactory(
                            /* namePrefix */ "foreground-pool-",
                            THREAD_PRIORITY_FOREGROUND);

            sExecutor = new ThreadPoolExecutor(
                    CORE_POOL_SIZE,
                    MAX_POOL_SIZE,
                    KEEP_ALIVE_TIME_IN_SECONDS,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(MAX_QUEUE_SIZE),
                    threadFactory,
                    new ThreadPoolExecutor.AbortPolicy()
            );

            return sExecutor;
        }
    }
}
