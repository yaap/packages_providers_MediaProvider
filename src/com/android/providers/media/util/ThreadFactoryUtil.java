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

import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A utility class for creating customized {@link ThreadFactory} instances.
 *
 * This class provides a convenient way to generate thread factories that
 * produce threads with specific naming conventions and Android process priorities.
 * This factory will commonly used with {@link java.util.concurrent.ThreadPoolExecutor}
 * to manage thread pools effectively, making debugging and performance analysis easier.
 */
public class ThreadFactoryUtil {
    private static final String TAG = "ThreadFactoryUtil";

    // Private constructor to prevent direct instantiation of this utility class.
    private ThreadFactoryUtil() {}

    /**
     * Returns a new {@link ThreadFactory} that creates threads with a specified
     * name prefix and Android process priority.
     *
     * By default, threads created by this factory are set to be non-daemon threads,
     * ensuring they do not prevent the Java Virtual Machine (or Android application process)
     * from exiting while tasks are still running.
     *
     * @param namePrefix The prefix to use for naming the threads created by this factory.
     * Must not be null. Example: "my-background-worker-".
     * @param threadPriority The Android process priority for the thread from {@link Process}.
     * Example: {@link android.os.Process#THREAD_PRIORITY_BACKGROUND}.
     * @return A new {@link ThreadFactory} instance configured with the specified naming
     * and priority.
     * @throws NullPointerException if {@code namePrefix} is null.
     */
    public static ThreadFactory getThreadFactory(
            @NonNull String namePrefix,
            int threadPriority) {
        Objects.requireNonNull(namePrefix);

        return new ThreadFactory() {
            private final AtomicInteger mThreadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                final String threadName = namePrefix + mThreadNumber.getAndIncrement();

                final Runnable wrappedRunnable = () -> {
                    try {
                        Process.setThreadPriority(threadPriority);
                    } catch (IllegalArgumentException | SecurityException e) {
                        Log.e(TAG, "Could not update thread priority", e);
                    }

                    runnable.run();
                };

                final Thread thread = new Thread(wrappedRunnable, threadName);

                Log.d(TAG, "Created thread " + threadName);
                return thread;
            }
        };
    }
}
