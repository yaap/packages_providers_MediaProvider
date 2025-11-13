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

package com.android.providers.media;

import static android.content.Intent.ACTION_LOCALE_CHANGED;
import static android.content.Intent.ACTION_MEDIA_MOUNTED;
import static android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE;
import static android.content.Intent.ACTION_PACKAGE_DATA_CLEARED;
import static android.content.Intent.ACTION_PACKAGE_FULLY_REMOVED;

import static com.android.providers.media.MediaProvider.BROADCAST_INTENT;
import static com.android.providers.media.MediaProvider.CANCEL_WORK_AFTER_ENQUEUEING;
import static com.android.providers.media.MediaProvider.IS_SCAN_VOLUME_CALL;
import static com.android.providers.media.MediaProvider.REMOVE_VOL_BEFORE_ENQUEUEING;
import static com.android.providers.media.MediaProvider.VOLUME_NAME;
import static com.android.providers.media.MediaProvider.WAIT_FOR_SCAN_COMPLETION;
import static com.android.providers.media.MediaProvider.WORK_INFO_STATE;
import static com.android.providers.media.scan.MediaScannerTest.stage;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkInfo;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
@RequiresFlagsEnabled(com.android.providers.media.flags.Flags.FLAG_ENABLE_MEDIA_SERVICE_V2)
public class MediaServiceV2Test {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private Context mContext;
    private File mDownloadsDir;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mDownloadsDir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.WRITE_MEDIA_STORAGE);
    }

    @Test
    public void testScanVolume() throws Exception {
        File testFile = new File(mDownloadsDir,
                "testImage_" + SystemClock.elapsedRealtimeNanos() + ".jpg");
        stageNewFile(R.raw.test_image, testFile);

        try {
            Bundle extras = new Bundle();
            extras.putString(VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY);
            extras.putBoolean(IS_SCAN_VOLUME_CALL, true);

            Bundle result = mContext.getContentResolver().call(MediaStore.AUTHORITY,
                    MediaStore.MEDIA_SERVICE_V2_CALL, /* arg */ null, extras);

            assertThat(result.getString(WORK_INFO_STATE))
                    .isEqualTo(WorkInfo.State.SUCCEEDED.toString());
            assertTrue(isFileScanned(testFile));
        } finally {
            testFile.delete();
        }
    }

    @Test
    public void testIsStopped() throws Exception {
        List<File> files = new ArrayList<>();

        // create 500 files and it will act as a long running work.
        // We will cancel the work before it gets completed.
        for (int i = 0; i < 500; i++) {
            File testFile = new File(mDownloadsDir,
                    i + "_" + SystemClock.elapsedRealtimeNanos() + ".jpg");
            stageNewFile(R.raw.test_image, testFile);
            files.add(testFile);
        }

        try {
            Bundle extras = new Bundle();
            extras.putString(VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY);
            extras.putBoolean(IS_SCAN_VOLUME_CALL, true);
            extras.putBoolean(WAIT_FOR_SCAN_COMPLETION, false);
            extras.putBoolean(CANCEL_WORK_AFTER_ENQUEUEING, true);

            Bundle result = mContext.getContentResolver().call(MediaStore.AUTHORITY,
                    MediaStore.MEDIA_SERVICE_V2_CALL, /* arg */ null, extras);

            assertThat(result.getString(WORK_INFO_STATE))
                    .isEqualTo(WorkInfo.State.CANCELLED.toString());
            assertAllFilesNotScanned(files);
        } finally {
            for (File file : files) {
                file.delete();
            }
        }
    }

    @Test
    public void testPackageDataCleared() {
        // add an entry to files table with a random package that we will orphan for given uid.
        String fileName = "a1_" + System.nanoTime() + ".jpeg";
        Uri uri = addDummyContentValuesForFile(fileName);

        try {
            verifyPackageRemovalOrphansFile(ACTION_PACKAGE_DATA_CLEARED, fileName);
        } finally {
            mContext.getContentResolver().delete(uri, /* extras */ null);
        }
    }


    @Test
    public void testPackageFullyRemoved() {
        // add an entry to files table with a random package that we will orphan for given uid.
        String fileName = "a2_" + System.nanoTime() + ".jpeg";
        Uri uri = addDummyContentValuesForFile(fileName);

        try {
            verifyPackageRemovalOrphansFile(ACTION_PACKAGE_FULLY_REMOVED, fileName);
        } finally {
            mContext.getContentResolver().delete(uri, /* extras */ null);
        }
    }

    private void verifyPackageRemovalOrphansFile(String action, String fileName) {
        //create intent for ACTION_PACKAGE_FULLY_REMOVED or ACTION_PACKAGE_DATA_CLEARED
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(action);
        broadcastIntent.setData(
                Uri.fromParts("content", mContext.getPackageName(), /*fragment*/ null));
        broadcastIntent.putExtra(Intent.EXTRA_UID, UserHandle.myUserId());

        // create work for action
        Bundle extras = new Bundle();
        extras.putParcelable(BROADCAST_INTENT, broadcastIntent);
        Bundle result = mContext.getContentResolver().call(MediaStore.AUTHORITY,
                MediaStore.MEDIA_SERVICE_V2_CALL, /* arg */ null, extras);

        // assert work finished successfully and entry for dummy package is removed.
        assertThat(result.getString(WORK_INFO_STATE))
                .isEqualTo(WorkInfo.State.SUCCEEDED.toString());
        assertThat(getFileOwnerPackageName(fileName)).isNull();
    }

    @Test
    public void testMediaMountedWhenVolumeAlreadyAttached() throws Exception {
        File testFile = new File(mDownloadsDir,
                "c1_" + SystemClock.elapsedRealtimeNanos() + ".jpg");
        stageNewFile(R.raw.test_image, testFile);

        try {
            Bundle result = getResultForMountMedia(/* removeVolumeBeforeEnqueueing */ false);

            assertThat(result.getString(WORK_INFO_STATE))
                    .isEqualTo(WorkInfo.State.SUCCEEDED.toString());
            // scan volume is not called if the volume is already attached.
            // So we do not expect file to be scanned.
            assertThat(isFileScanned(testFile)).isFalse();
        } finally {
            testFile.delete();
        }
    }

    @Test
    public void testMediaMountedWhenVolumeNotAttached() throws Exception {
        File testFile = new File(mDownloadsDir,
                "c2_" + SystemClock.elapsedRealtimeNanos() + ".jpg");
        stageNewFile(R.raw.test_image, testFile);

        try {
            Bundle result = getResultForMountMedia(/* removeMediaVolumeBeforeEnqueueing */ true);

            assertThat(result.getString(WORK_INFO_STATE))
                    .isEqualTo(WorkInfo.State.SUCCEEDED.toString());
            assertThat(isFileScanned(testFile)).isTrue();
        } finally {
            testFile.delete();
        }
    }

    @Nullable
    private Bundle getResultForMountMedia(boolean removeMediaVolumeBeforeEnqueueing) {
        StorageVolume vol = getExternalPrimaryStorageVolume();
        Assert.assertNotNull(vol);

        //create intent for ACTION_MEDIA_MOUNTED.
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(ACTION_MEDIA_MOUNTED);
        broadcastIntent.putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, vol);

        // create work for ACTION_MEDIA_MOUNTED
        Bundle extras = new Bundle();
        extras.putBoolean(REMOVE_VOL_BEFORE_ENQUEUEING, removeMediaVolumeBeforeEnqueueing);
        extras.putString(VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY);
        extras.putParcelable(BROADCAST_INTENT, broadcastIntent);
        Bundle result = mContext.getContentResolver().call(MediaStore.AUTHORITY,
                MediaStore.MEDIA_SERVICE_V2_CALL, /* arg */ null, extras);
        return result;
    }

    private StorageVolume getExternalPrimaryStorageVolume() {
        List<StorageVolume> volumes =
                mContext.getSystemService(StorageManager.class).getStorageVolumes();
        for (StorageVolume vol : volumes) {
            if (MediaStore.VOLUME_EXTERNAL_PRIMARY
                    .equalsIgnoreCase(vol.getMediaStoreVolumeName())) {
                return vol;
            }
        }
        return null;
    }

    @Test
    public void testOnLocalChanged() {
        String fileName = "f_" + System.nanoTime() + ".jpeg";
        Uri uri = addDummyContentValuesForFile(fileName);

        try {
            //create intent for ACTION_LOCALE_CHANGED
            Intent broadcastIntent = new Intent();
            broadcastIntent.setAction(ACTION_LOCALE_CHANGED);

            // create work for ACTION_LOCALE_CHANGED
            Bundle extras = new Bundle();
            extras.putParcelable(BROADCAST_INTENT, broadcastIntent);
            Bundle result = mContext.getContentResolver().call(MediaStore.AUTHORITY,
                    MediaStore.MEDIA_SERVICE_V2_CALL, /* arg */ null, extras);

            // assert work finished successfully and file is scanned.
            assertThat(result.getString(WORK_INFO_STATE))
                    .isEqualTo(WorkInfo.State.SUCCEEDED.toString());

        } finally {
            mContext.getContentResolver().delete(uri, /* extras */ null);
        }
    }

    @Test
    public void testScanFile() throws Exception {
        // add an entry to files table with a random package that we will orphan for given uid.
        File testFile = new File(mDownloadsDir,
                "b_" + SystemClock.elapsedRealtimeNanos() + ".jpg");
        stageNewFile(R.raw.test_image, testFile);

        try {
            //create intent for ACTION_MEDIA_SCANNER_SCAN_FILE
            Intent broadcastIntent = new Intent();
            broadcastIntent.setAction(ACTION_MEDIA_SCANNER_SCAN_FILE);
            broadcastIntent.setData(Uri.fromFile(testFile));

            // create work for ACTION_MEDIA_SCANNER_SCAN_FILE
            Bundle extras = new Bundle();
            extras.putParcelable(BROADCAST_INTENT, broadcastIntent);
            Bundle result = mContext.getContentResolver().call(MediaStore.AUTHORITY,
                    MediaStore.MEDIA_SERVICE_V2_CALL, /* arg */ null, extras);

            // assert work finished successfully and file is scanned.
            assertThat(result.getString(WORK_INFO_STATE))
                    .isEqualTo(WorkInfo.State.SUCCEEDED.toString());
            assertTrue(isFileScanned(testFile));
        } finally {
            testFile.delete();
        }
    }

    private Uri addDummyContentValuesForFile(String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");

        Uri uri = mContext.getContentResolver()
                .insert(MediaStore.Files.EXTERNAL_CONTENT_URI, values);
        assertThat(getFileOwnerPackageName(fileName)).isEqualTo(mContext.getPackageName());
        return uri;
    }

    private String getFileOwnerPackageName(String fileName) {
        Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String selection = MediaStore.Files.FileColumns.DISPLAY_NAME + "=?";
        String[] args = new String[]{fileName};
        try (Cursor cursor = mContext.getContentResolver().query(
                filesUri,
                new String[]{MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME},
                selection,
                args,
                /*sortOrder*/ null)) {
            cursor.moveToFirst();
            return cursor.getString(0);
        }
    }

    private boolean isFileScanned(File file) {
        Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String selection = MediaStore.Files.FileColumns.DISPLAY_NAME + " = ?";
        String[] selectionArgs = new String[] { file.getName() };

        try (Cursor cursor = mContext.getContentResolver().query(
                filesUri,
                new String[] { MediaStore.Files.FileColumns.DATE_TAKEN },
                selection,
                selectionArgs,
                null)) {
            // DATE_TAKEN is populated when file is scanned.
            return cursor != null && cursor.moveToFirst() && cursor.getLong(0) > 0;
        }
    }

    private void assertAllFilesNotScanned(List<File> files) {
        for (File file : files) {
            if (!isFileScanned(file)) {
                return;
            }
        }
        fail("Did not expect all files to be scanned.");
    }

    private void stageNewFile(int resId, File file) throws IOException {
        file.createNewFile();
        stage(resId, file);
    }
}
