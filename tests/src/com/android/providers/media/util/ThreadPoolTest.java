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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Since thread pools are static singletons, we shouldn't overload them with tasks in these tests
 * because they might affect other tests and cause flakes.
 */
@RunWith(AndroidJUnit4.class)
public class ThreadPoolTest {
    @Test
    public void testBackgroundThreadPoolExecutesTask() throws InterruptedException {
        Executor executor = BackgroundThreadPool.getExecutor();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger executedCount = new AtomicInteger(0);

        executor.execute(() -> {
            executedCount.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(1, executedCount.get());
    }

    @Test
    public void testBackgroundThreadPoolIsSingleton() {
        Executor executor1 = BackgroundThreadPool.getExecutor();
        Executor executor2 = BackgroundThreadPool.getExecutor();

        assertNotNull(executor1);
        assertNotNull(executor2);
        assertEquals(executor1, executor2);
    }

    @Test
    public void testForegroundThreadPoolExecutesTask() throws InterruptedException {
        Executor executor = ForegroundThreadPool.getExecutor();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger executedCount = new AtomicInteger(0);

        executor.execute(() -> {
            executedCount.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(1, executedCount.get());
    }

    @Test
    public void testForegroundThreadPoolIsSingleton() {
        Executor executor1 = ForegroundThreadPool.getExecutor();
        Executor executor2 = ForegroundThreadPool.getExecutor();

        assertNotNull(executor1);
        assertNotNull(executor2);
        assertEquals(executor1, executor2);
    }
}
