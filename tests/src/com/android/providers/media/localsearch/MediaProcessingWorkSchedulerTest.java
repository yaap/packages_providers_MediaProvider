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

package com.android.providers.media.localsearch;

import static com.android.providers.media.localsearch.MediaProcessingWorkScheduler.PERIODIC_WORK_NAME;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.media.internal.flags.Flags;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.TestWorkerBuilder;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.R;
import com.android.providers.media.WorkManagerInitializer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_PROCESSING)
public class MediaProcessingWorkSchedulerTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final String TAG = "ProcessingSchedulerTest";

    private Context mContext;
    private IsolatedContext mIsolatedContext;
    private WorkManager mWorkManager;

    private MediaProcessingWorkScheduler mScheduler;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mIsolatedContext = new IsolatedContext(mContext, "test", false);
        mScheduler = TestWorkerBuilder.from(mIsolatedContext,
                MediaProcessingWorkScheduler.class).build();

        // Initialize WorkManager for testing
        WorkManagerTestInitHelper.initializeTestWorkManager(mIsolatedContext,
                new Configuration.Builder()
                        .setExecutor(new SynchronousExecutor())
                        .build());
        mWorkManager = WorkManagerInitializer.getWorkManager(mIsolatedContext);
    }

    @After
    public void tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase();
    }


    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.CINNAMON_BUN)
    public void testEnqueueWork() throws Exception {
        MediaProcessingWorkScheduler.enqueueWork(mIsolatedContext);

        List<WorkInfo> workInfos = mWorkManager.getWorkInfosForUniqueWork(PERIODIC_WORK_NAME).get();
        assertThat(workInfos).hasSize(1);
        WorkInfo testWork = workInfos.get(0);
        assertThat(testWork).isNotNull();

        long expectedJobIntervalMillis = (long) 6 * 60 * 60 * 1000; // default - 6 hours

        try {
            int defaultJobIntervalHours = mIsolatedContext.getResources().getInteger(
                    R.integer.config_default_media_processing_job_interval_hours);
            expectedJobIntervalMillis = (long) defaultJobIntervalHours * 60 * 60 * 1000;
        } catch (Resources.NotFoundException e) {
            Log.d(TAG, "Overlayable config for media processing job interval not found. Using "
                    + "default value - 6 hours");
        }

        assertThat(testWork.getPeriodicityInfo().getRepeatIntervalMillis()).isEqualTo(
                expectedJobIntervalMillis);
    }
}
