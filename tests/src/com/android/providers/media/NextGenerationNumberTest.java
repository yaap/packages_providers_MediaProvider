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

import static android.provider.MediaStore.MediaColumns.GENERATION_MODIFIED;
import static android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY;

import static com.android.providers.media.DatabaseBackupAndRecovery.LATEST_LEVEL_DB_VERSION;
import static com.android.providers.media.MediaProvider.BACKED_UP_DATA_IN_LEVEL_DB;
import static com.android.providers.media.MediaProvider.BACKED_UP_FILE_PATH;
import static com.android.providers.media.MediaProvider.BACKED_UP_LEVELDB_VERSION;
import static com.android.providers.media.MediaProvider.VOLUME_NAME;
import static com.android.providers.media.scan.MediaScannerTest.stage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.MediaStore;
import android.support.test.uiautomator.UiDevice;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.flags.Flags;
import com.android.providers.media.stableuris.dao.BackupIdRow;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled({Flags.FLAG_ENABLE_NEXT_GENERATION_NUMBER,
        Flags.FLAG_ENABLE_GENERATION_NUMBER_RECOVERY})
public class NextGenerationNumberTest {
    private Context mContext;
    private UiDevice mUiDevice;
    private File mDownloadsDir;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mDownloadsDir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
        FileUtils.deleteContents(mDownloadsDir);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.WRITE_MEDIA_STORAGE);
    }

    @Test
    public void testRecoveryWhenFileEntryExistsInFilesTable() throws Exception {
        List<File> files = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                File f = new File(mDownloadsDir, "testFile_" + i + System.currentTimeMillis()
                        + ".jpg");
                stageNewFile(R.raw.test_image, f);
                files.add(f);
            }

            MediaStore.scanVolume(mContext.getContentResolver(), VOLUME_EXTERNAL_PRIMARY);

            Map<String, Long> pathVsGenModified = new HashMap<>();
            for (File f : files) {
                String filePath = f.getAbsolutePath();
                try (Cursor c = query(filePath)) {
                    c.moveToFirst();
                    pathVsGenModified.put(filePath,
                            c.getLong(c.getColumnIndex(GENERATION_MODIFIED)));
                }
            }

            // try to recover data. No new entries will be inserted as the entries would already
            // exist in files table with same generation modified as stored in level db.
            Bundle extras = new Bundle();
            extras.putString(VOLUME_NAME, VOLUME_EXTERNAL_PRIMARY);
            mContext.getContentResolver().call(MediaStore.AUTHORITY,
                    MediaStore.RECOVER_DATA_CALL, /* arg */ null, extras);

            for (int i = 0; i < 3; i++) {
                File f = files.get(i);
                String filePath = f.getAbsolutePath();
                try (Cursor c = query(filePath)) {
                    c.moveToFirst();
                    long generationModifiedAfterRecovery =
                            c.getLong(c.getColumnIndex(GENERATION_MODIFIED));
                    long generationModifiedBeforeRecovery = pathVsGenModified.get(filePath);
                    assertEquals(generationModifiedBeforeRecovery, generationModifiedAfterRecovery);
                }
            }

        } finally {
            for (File f : files) {
                f.delete();
            }
        }
    }

    @Test
    public void testRecoveryWhenFileEntryDoesNotExistsInFilesTable() throws Exception {
        List<File> files = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                File f = new File(mDownloadsDir, "testFile_" + i + System.currentTimeMillis()
                        + ".jpg");
                stageNewFile(R.raw.test_image, f);
                files.add(f);
            }

            MediaStore.scanVolume(mContext.getContentResolver(), VOLUME_EXTERNAL_PRIMARY);

            Map<String, Long> pathVsGenModified = new HashMap<>();
            for (File f : files) {
                String filePath = f.getAbsolutePath();
                try (Cursor c = query(filePath)) {
                    c.moveToFirst();
                    pathVsGenModified.put(filePath,
                            c.getLong(c.getColumnIndex(GENERATION_MODIFIED)));
                }
            }

            // clear media provider. This will remove entries in db and also trigger recovery.
            // New entries will be created in files table
            mUiDevice.executeShellCommand("pm clear --user " + mContext.getUserId() + " "
                    + getMediaProviderPackageName());
            SystemClock.sleep(20_000); // wait for media provider to come up

            for (int i = 0; i < 3; i++) {
                File f = files.get(i);
                String filePath = f.getAbsolutePath();
                try (Cursor c = query(filePath)) {
                    c.moveToFirst();
                    long generationModifiedAfterRecovery =
                            c.getLong(c.getColumnIndex(GENERATION_MODIFIED));
                    long generationModifiedBeforeRecovery = pathVsGenModified.get(filePath);
                    assertTrue(
                            generationModifiedBeforeRecovery < generationModifiedAfterRecovery);
                }
            }
        } finally {
            for (File f : files) {
                f.delete();
            }
        }
    }

    @Test
    public void testLevelDbVersion() throws Exception {
        List<File> files = new ArrayList<>();
        try {
            File testFile = new File(mDownloadsDir, "testFile_" + System.currentTimeMillis()
                    + ".jpg");
            stageNewFile(R.raw.test_image, testFile);
            files.add(testFile);
            String filePath = testFile.getAbsolutePath();
            long generationModifiedInFilesTable;
            try (Cursor c = query(filePath)) {
                c.moveToFirst();
                generationModifiedInFilesTable = c.getLong(c.getColumnIndex(GENERATION_MODIFIED));
            }

            Bundle extras = new Bundle();
            extras.putString(VOLUME_NAME, VOLUME_EXTERNAL_PRIMARY);
            extras.putString(BACKED_UP_FILE_PATH, filePath);
            mContext.getContentResolver().call(MediaStore.AUTHORITY,
                    MediaStore.RESET_LEVEL_DB_AT_DEFAULT_VERSION_CALL, /* arg */ null, extras);

            Bundle result = mContext.getContentResolver().call(MediaStore.AUTHORITY,
                    MediaStore.ENSURE_LEVEL_DB_AT_LATEST_VERSION_CALL, /* arg */ null, extras);
            BackupIdRow backupIdRow =
                    (BackupIdRow) result.getSerializable(BACKED_UP_DATA_IN_LEVEL_DB);
            long generationModifiedInLevelDb = backupIdRow.getGenerationModified();
            long levelDbVersion = result.getLong(BACKED_UP_LEVELDB_VERSION);

            assertEquals(generationModifiedInLevelDb, generationModifiedInFilesTable);
            assertEquals(LATEST_LEVEL_DB_VERSION, levelDbVersion);
        } finally {
            for (File f : files) {
                f.delete();
            }
        }
    }

    private static String getMediaProviderPackageName() {
        final Instrumentation inst = androidx.test.InstrumentationRegistry.getInstrumentation();
        final PackageManager packageManager = inst.getContext().getPackageManager();
        final ProviderInfo providerInfo = packageManager.resolveContentProvider(
                MediaStore.AUTHORITY, PackageManager.MATCH_ALL);
        return providerInfo.packageName;
    }

    private Cursor query(String path) {
        Uri uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String selection = MediaStore.Files.FileColumns.DATA + " LIKE ?";
        String[] selectionArgs = new String[]{path};
        return mContext.getContentResolver().query(uri, null, selection, selectionArgs, null);
    }

    private void stageNewFile(int resId, File file) throws IOException {
        file.createNewFile();
        stage(resId, file);
    }
}
