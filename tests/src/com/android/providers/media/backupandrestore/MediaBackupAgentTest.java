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

package com.android.providers.media.backupandrestore;

import static com.android.providers.media.backupandrestore.BackupAndRestoreTestUtils.deSerialiseValueString;
import static com.android.providers.media.backupandrestore.BackupAndRestoreTestUtils.getSharedPreferenceValue;
import static com.android.providers.media.backupandrestore.BackupAndRestoreUtils.isBackupAndRestoreSupported;
import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;
import static com.android.providers.media.scan.MediaScannerTest.stage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.R;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.leveldb.LevelDBEntry;
import com.android.providers.media.leveldb.LevelDBInstance;
import com.android.providers.media.leveldb.LevelDBManager;
import com.android.providers.media.leveldb.LevelDBResult;
import com.android.providers.media.scan.ModernMediaScanner;
import com.android.providers.media.util.FileUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
@EnableFlags({Flags.FLAG_ENABLE_BACKUP_AND_RESTORE})
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.BAKLAVA)
public class MediaBackupAgentTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final String KEY_VALUE_SEPARATOR = "=";
    private static final String FIELD_SEPARATOR = ":::";
    private static final String EXTERNAL_DB_NAME = "external.db";
    private static final int DUMMY_COLUMN_ID = 100;
    private static final String DUMMY_COLUMN_VALUE = "dummy_value";

    private Context mIsolatedContext;

    private ContentResolver mIsolatedResolver;

    private ModernMediaScanner mModern;

    private File mDownloadsDir;

    private File mRestoreDir;
    private File mBackupDir;

    private String mLevelDbPath;
    private MediaBackupAgent mMediaBackupAgent;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.DUMP,
                        Manifest.permission.CREATE_USERS,
                        Manifest.permission.READ_DEVICE_CONFIG);

        mIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        mIsolatedResolver = mIsolatedContext.getContentResolver();
        mModern = new ModernMediaScanner(mIsolatedContext, new TestConfigStore());
        mRestoreDir = new File(mIsolatedContext.getFilesDir(), "restore");
        mBackupDir = new File(mIsolatedContext.getFilesDir(), "backup");
        mDownloadsDir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
        mLevelDbPath =
                mIsolatedContext.getFilesDir().getAbsolutePath() + "/backup/external_primary";
        FileUtils.deleteContents(mDownloadsDir);

        mMediaBackupAgent = new MediaBackupAgent();
        mMediaBackupAgent.attach(mIsolatedContext);
        LevelDBManager.delete(mLevelDbPath);
    }

    @After
    public void tearDown() {
        LevelDBManager.delete(mLevelDbPath);
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testCompleteFlow() throws Exception {
        assumeTrue(isBackupAndRestoreSupported(mIsolatedContext));

        //create new test file & stage it
        File file = createTestFileAndStageIt();

        try {
            String path = file.getAbsolutePath();
            // scan directory to have entry in files table
            scanFileAndCreateBackup(path);
            assertTrue(mBackupDir.exists());

            assertLevelDbExistsAndHasLatestValues(path);

            // run the backup agent. This will copy over backup directory to restore directory and
            // set shared preference.
            mMediaBackupAgent.onRestoreFinished();
            assertTrue(getSharedPreferenceValue(mIsolatedContext));
            assertTrue(mRestoreDir.exists());
            assertFalse(mBackupDir.exists());

            //delete existing external db database having old values
            mIsolatedContext.deleteDatabase(EXTERNAL_DB_NAME);

            // run media scan, this will populate db and read value from backup
            doRestorationAndAssertFileRestored(path);

            // on idle maintenance, clean up is called. It should delete restore directory and set
            // shared preference to false
            MediaStore.runIdleMaintenance(mIsolatedResolver);
            assertFalse(getSharedPreferenceValue(mIsolatedContext));
            assertFalse(mRestoreDir.exists());
        } finally {
            file.delete();
        }
    }

    @Test
    public void testBackupWhenSourceDeviceHasHigherLevelDbVersion() throws Exception {
        assumeTrue(isBackupAndRestoreSupported(mIsolatedContext));

        //create new test file & stage it
        File file = createTestFileAndStageIt();

        try {
            String path = file.getAbsolutePath();
            scanFileAndCreateBackup(path);

            //assume the higher version has a new columns that the has been saved in leveldb
            LevelDBInstance levelDBInstance = LevelDBManager.getInstance(mLevelDbPath);
            LevelDBResult levelDBResult = levelDBInstance.query(path);
            String value = getSerialisedValueWithOneColumnMore(levelDBResult);
            levelDBInstance.insert(new LevelDBEntry(path, value));

            // run the backup agent. This will copy over backup directory to restore directory and
            // set shared preference.
            mMediaBackupAgent.onRestoreFinished();

            //delete existing external db database having old values
            mIsolatedContext.deleteDatabase(EXTERNAL_DB_NAME);

            // One less field (due to lower level db version of source device) should have no impact
            // on restoration process. The extra backed up fields should be ignored.
            doRestorationAndAssertFileRestored(path);

            // cleanup restore directory
            MediaStore.runIdleMaintenance(mIsolatedResolver);
        } finally {
            file.delete();
        }
    }

    @Test
    public void testBackupWhenSourceDeviceHasLowerLevelDbVersion() throws Exception {
        assumeTrue(isBackupAndRestoreSupported(mIsolatedContext));

        //create new test file & stage it
        File file = createTestFileAndStageIt();

        try {
            String path = file.getAbsolutePath();
            scanFileAndCreateBackup(path);

            //assume the higher version has a new columns that the has been saved in leveldb
            LevelDBInstance levelDBInstance = LevelDBManager.getInstance(mLevelDbPath);
            LevelDBResult levelDBResult = levelDBInstance.query(path);
            String value = getSerialisedValueWithOneColumnLess(levelDBResult);
            levelDBInstance.insert(new LevelDBEntry(path, value));

            // run the backup agent. This will copy over backup directory to restore directory and
            // set shared preference.
            mMediaBackupAgent.onRestoreFinished();

            //delete existing external db database having old values
            mIsolatedContext.deleteDatabase(EXTERNAL_DB_NAME);

            // The less fields (due to lower level db version of source device) should have no
            // impact on restoration process.
            doRestorationAndAssertFileRestored(path);

            // cleanup restore directory
            MediaStore.runIdleMaintenance(mIsolatedResolver);
        } finally {
            file.delete();
        }
    }

    private void doRestorationAndAssertFileRestored(String path) {
        // run media scan, this will populate db and read value from backup
        mModern.scanDirectory(mDownloadsDir, REASON_UNKNOWN);

        // we verify that the metadata is read from backup (and not extracted from file) by checking
        // is_favorite value.
        assertEquals(1, queryFavoritesValue(path));
    }

    private void scanFileAndCreateBackup(String path) {
        // scan directory to have entry in files table
        mModern.scanDirectory(mDownloadsDir, REASON_UNKNOWN);

        // set is_favorite value to 1. We will check this value later after restoration.
        updateFavoritesValue(path, 1);

        // run idle maintenance, this will save file's metadata in leveldb with is_favorite = 1
        MediaStore.runIdleMaintenance(mIsolatedResolver);
    }

    @NonNull
    private File createTestFileAndStageIt() throws IOException {
        File file = new File(mDownloadsDir, "testImage_"
                + SystemClock.elapsedRealtimeNanos() + ".jpg");
        file.createNewFile();
        stage(R.raw.test_image, file);
        return file;
    }

    @NonNull
    private static String getSerialisedValueWithOneColumnMore(LevelDBResult levelDBResult) {
        String serializedString = levelDBResult.getValue();
        return serializedString
                + DUMMY_COLUMN_ID
                + KEY_VALUE_SEPARATOR
                + DUMMY_COLUMN_VALUE
                + FIELD_SEPARATOR;
    }

    @NonNull
    private static String getSerialisedValueWithOneColumnLess(LevelDBResult levelDBResult) {
        String serializedString = levelDBResult.getValue();
        int lastSeparatorIndex = serializedString.lastIndexOf(FIELD_SEPARATOR);
        return (lastSeparatorIndex != -1) ? serializedString.substring(0, lastSeparatorIndex)
                : serializedString;
    }

    private void assertLevelDbExistsAndHasLatestValues(String path) {
        // check that entry is created in level db for the file
        LevelDBInstance levelDBInstance = LevelDBManager.getInstance(mLevelDbPath);
        assertNotNull(levelDBInstance);

        // check that entry created in level db has latest value(is_favorite = 1)
        LevelDBResult levelDBResult = levelDBInstance.query(path);
        assertNotNull(levelDBResult);
        Map<String, String> actualResultMap = deSerialiseValueString(levelDBResult.getValue());
        assertEquals(1,
                Integer.parseInt(actualResultMap.get(MediaStore.MediaColumns.IS_FAVORITE)));
    }

    private void updateFavoritesValue(String path, int value) {
        Uri uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String selection = MediaStore.Files.FileColumns.DATA + " LIKE ?";
        String[] selectionArgs = new String[]{path};

        ContentValues values = new ContentValues();
        values.put(MediaStore.Files.FileColumns.IS_FAVORITE, value);
        values.put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis());

        mIsolatedResolver.update(uri, values, selection, selectionArgs);
    }

    private int queryFavoritesValue(String path) {
        Uri uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String selection = MediaStore.Files.FileColumns.DATA + " LIKE ?";
        String[] selectionArgs = new String[]{path};

        Cursor cursor = mIsolatedResolver.query(uri, null, selection, selectionArgs, null);
        cursor.moveToFirst();
        return cursor.getInt(cursor.getColumnIndex(MediaStore.Files.FileColumns.IS_FAVORITE));
    }
}
