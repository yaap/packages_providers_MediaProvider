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

package com.android.providers.media.metrics;

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;

import static com.android.providers.media.metrics.DeviceStorageStateMetricsCollector.OTHER_MEDIA_TYPES;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.spy;

import android.Manifest;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Configuration;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.TestWorkerBuilder;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.IsolatedContext;
import com.android.providers.media.MediaProvider;
import com.android.providers.media.WorkManagerInitializer;
import com.android.providers.media.util.FileUtils;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

@RunWith(MockitoJUnitRunner.class)
public class DeviceStorageStateMetricsCollectorTest {
    static final long WORK_INTERVAL_MILLIS = 7 * 24 * 60 * 60 * 1000;
    private static final String PERIODIC_WORK_NAME = "TestLogDeviceStorageStateMetrics";
    private final String mOwnerPkgName = "com.android.dummypackage";
    private Context mContext;
    private IsolatedContext mIsolatedContext;
    private MediaProvider mMediaProvider;
    private Optional<DatabaseHelper> mExternalDb;
    private DeviceStorageStateMetricsCollector mMetricsCollector;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.LOG_COMPAT_CHANGE,
                Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                Manifest.permission.READ_DEVICE_CONFIG);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mIsolatedContext = new IsolatedContext(mContext, "metricsCollector", false);

        try (ContentProviderClient cpc =
                     mIsolatedContext.getContentResolver().acquireContentProviderClient(
                MediaStore.AUTHORITY)) {
            mMediaProvider = (MediaProvider) cpc.getLocalContentProvider();
            mExternalDb = mMediaProvider.getDatabaseHelper(DatabaseHelper.EXTERNAL_DATABASE_NAME);
            assumeTrue(mExternalDb.isPresent());
        }

        mMetricsCollector = spy(
                TestWorkerBuilder.from(mContext, DeviceStorageStateMetricsCollector.class,
                        Executors.newSingleThreadExecutor()).build());

        WorkManagerTestInitHelper.initializeTestWorkManager(mContext,
                new Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(
                                new SynchronousExecutor()) // Crucial for immediate execution
                        .build());
    }

    @After
    public void tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void testEnqueueWork() {
        PeriodicWorkRequest testWorkRequest =
                DeviceStorageStateMetricsCollector.createPeriodicWorkRequest();

        WorkManager workManager = WorkManagerInitializer.getWorkManager(mContext);
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                testWorkRequest);

        try {
            WorkInfo scheduledWorkInfo = workManager.getWorkInfoById(testWorkRequest.getId()).get();
            assumeTrue(scheduledWorkInfo != null);
            assertThat(scheduledWorkInfo.getConstraints().requiresDeviceIdle()).isTrue();
            assertThat(scheduledWorkInfo.getPeriodicityInfo().getRepeatIntervalMillis()).isEqualTo(
                    WORK_INTERVAL_MILLIS);
        } catch (Exception e) {
            Assert.fail("Test fails with exception : " + e.getMessage());
        }
    }

    private List<File> insertTestImageFiles() {
        List<File> testFiles = new ArrayList<>();
        final File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        final Uri imageUri = MediaStore.Images.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        final int size = 100 * 1000; //100kb

        for (int i = 0; i < 10; i++) {
            final File testImage = new File(dir, "test_" + System.nanoTime() + ".jpeg");
            final String displayName = FileUtils.extractDisplayName(testImage.getAbsolutePath());
            ContentValues values = new ContentValues();

            values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Images.Media.SIZE, size);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, mOwnerPkgName);

            mIsolatedContext.getContentResolver().insert(imageUri, values);
            testFiles.add(testImage);
        }

        try (Cursor c = mIsolatedContext.getContentResolver().query(imageUri, null, null, null,
                null)) {
            assertThat(c.getCount()).isEqualTo(10);
        }

        return testFiles;
    }

    private String getSelectionString(int mediaType) {
        String selectionString = switch (mediaType) {
            case MEDIA_TYPE_IMAGE, MEDIA_TYPE_AUDIO, MEDIA_TYPE_VIDEO ->
                    "media_type = " + mediaType;
            case MEDIA_TYPE_DOCUMENT ->
                    "(media_type = " + mediaType + " OR primary_directory = 'Documents')";
            case OTHER_MEDIA_TYPES ->
                    "media_type NOT IN (1, 2, 3, 6) AND (primary_directory IS NULL OR "
                            + "primary_directory != 'Documents')";
            default -> throw new IllegalArgumentException("Invalid media type : " + mediaType);
        };

        return selectionString.concat(" AND (is_trashed = 0 OR is_pending = 0)");
    }

    private Pair<Integer, Integer> queryStorageStateForMediaType(int mediaType) {
        String[] projection = new String[]{"COUNT(*) AS num_files",
                "SUM(_size)/1000000 AS files_storage_size_mb"};

        try (Cursor c = mExternalDb.get().runWithoutTransaction((db) -> {
            return db.query("files", projection, getSelectionString(mediaType), null, null, null,
                    null);
        })) {
            if (c.getCount() == 0) {
                return new Pair<>(0, 0);
            }

            c.moveToFirst();
            return new Pair<>(c.getInt(0), c.getInt(1));
        }
    }

    private void assertDeviceStorageStateLogs() {
        String[] projection = new String[]{"SUM(_size)/1000000 AS device_storage_size_mb",
                "COUNT(*) AS num_files_shared_storage",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Documents/%\" THEN 1 ELSE 0 END) "
                        + "AS num_default_documents_dir",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Download/%\" THEN 1 ELSE 0 END) "
                        + "AS num_default_download_dir",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Android/media/%\" THEN 1 ELSE 0 "
                        + "END) AS num_android_media_dir",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%\" THEN _size ELSE 0 END)/1000000 "
                        + "AS files_shared_storage_size_mb",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Documents/%\" THEN _size ELSE 0 "
                        + "END)/1000000 AS default_documents_dir_storage_size_mb",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Download/%\" THEN _size ELSE 0 "
                        + "END)/1000000 AS default_download_dir_storage_size_mb",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Android/media/%\" THEN _size ELSE"
                        + " 0 END)/1000000 AS android_media_dir_storage_size_mb"};

        String selection = "is_trashed = 0 OR is_pending = 0";

        try (Cursor expectedValues = mExternalDb.get().runWithoutTransaction((db) -> {
            return db.query("files", projection, selection, null, null, null, null);
        })) {

            assumeTrue(expectedValues.getCount() > 0);

            Pair<Integer, Integer> expectedImagesCountAndSize = queryStorageStateForMediaType(
                    MEDIA_TYPE_IMAGE);
            Pair<Integer, Integer> expectedVideosCountAndSize = queryStorageStateForMediaType(
                    MEDIA_TYPE_VIDEO);
            Pair<Integer, Integer> expectedAudioCountAndSize = queryStorageStateForMediaType(
                    MEDIA_TYPE_AUDIO);
            Pair<Integer, Integer> expectedDocumentCountAndSize = queryStorageStateForMediaType(
                    MEDIA_TYPE_DOCUMENT);
            Pair<Integer, Integer> expectedOtherMediaTypesCountAndSize =
                    queryStorageStateForMediaType(OTHER_MEDIA_TYPES);

            assumeTrue(expectedValues.moveToFirst());

            Mockito.verify(mMetricsCollector).logDeviceStorageStateReported(
                            /* device_storage_size_mb */ expectedValues.getInt(0),
                            /* num_files_in_shared_storage */ expectedValues.getInt(1),
                            /* num_images */ expectedImagesCountAndSize.first,
                            /* num_videos */ expectedVideosCountAndSize.first,
                            /* num_audio */ expectedAudioCountAndSize.first,
                            /* num_documents */ expectedDocumentCountAndSize.first,
                            /* num_other_media */ expectedOtherMediaTypesCountAndSize.first,
                            /* num_in_default_documents */ expectedValues.getInt(2),
                            /* num_in_default_downloads */ expectedValues.getInt(3),
                            /* num_in_android_media */ expectedValues.getInt(4),
                            /* files_shared_storage_size_mb */ expectedValues.getInt(5),
                            /* images_storage_size_mb */ expectedImagesCountAndSize.second,
                            /* videos_storage_size_mb */ expectedVideosCountAndSize.second,
                            /* audio_storage_size_mb */ expectedAudioCountAndSize.second,
                            /* documents_storage_size_mb */ expectedDocumentCountAndSize.second,
                            /* other_media_storage_size_mb */
                            expectedOtherMediaTypesCountAndSize.second,
                            /* default_downloads_storage_size_mb */ expectedValues.getInt(6),
                            /* default_documents_storage_size_mb */ expectedValues.getInt(7),
                            /* android_media_storage_size_mb */ expectedValues.getInt(8));
        }
    }

    @Test
    public void testDoWork() {
        // Insert 10 files of media type IMAGE and file path /storage/emulated
        // of size 100kb each
        List<File> testFiles = insertTestImageFiles();

        try {
            mMetricsCollector.setMediaProvider(mMediaProvider);
            assertThat(mMetricsCollector.doWork()).isEqualTo(ListenableWorker.Result.success());
            assertDeviceStorageStateLogs();
        } catch (Exception e) {
            Assert.fail("Test fails with exception : " + e.getMessage());
        } finally {
            //delete test files
            for (File testFile : testFiles) {
                testFile.delete();
            }
        }
    }
}
