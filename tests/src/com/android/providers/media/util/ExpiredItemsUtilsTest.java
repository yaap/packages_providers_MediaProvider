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

import static com.android.providers.media.scan.MediaScannerTest.stage;
import static com.android.providers.media.util.FileUtils.PREFIX_TRASHED;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.MediaStore;
import android.text.format.DateUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.R;
import com.android.providers.media.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class ExpiredItemsUtilsTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private File mDir;
    private ContentResolver mIsolatedContentResolver;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        // Deleting entries invokes Download#onMediaStoreDownloadsDeleted() which
                        // requires this permission.
                        Manifest.permission.WRITE_MEDIA_STORAGE,
                        // Adding this to use getUserHandles() api of UserManagerService which
                        // requires either MANAGE_USERS or CREATE_USERS. Since shell does not have
                        // MANAGER_USERS permissions, using CREATE_USERS in test. This works with
                        // MANAGE_USERS permission for MediaProvider module.
                        Manifest.permission.CREATE_USERS,
                        Manifest.permission.DUMP);

        resetIsolatedContext();
        File downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        mDir = new File(downloadsDir, "test_" + System.nanoTime());
        mDir.mkdirs();
        FileUtils.deleteContents(mDir);
        // Previous tests may have left stale files, do an idle run first to clean them up.
        MediaStore.runIdleMaintenance(mIsolatedContentResolver);
        MediaStore.waitForIdle(mIsolatedContentResolver);
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mDir);
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    /**
     * Verifies that a trashed item expired for 2 days is deleted after idle maintenance.
     */
    @Test
    public void testDeleteExpiredTrashedItem() throws IOException {
        final long expiredTwoDaysAgo =
                (System.currentTimeMillis() - (2 * DateUtils.DAY_IN_MILLIS)) / 1000;
        final Uri uri = createExpiredItem(PREFIX_TRASHED, expiredTwoDaysAgo, "item1");

        MediaStore.runIdleMaintenance(mIsolatedContentResolver);
        MediaStore.waitForIdle(mIsolatedContentResolver);

        try (Cursor cursor = mIsolatedContentResolver.query(uri, null, null, null)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    /**
     * Verifies that a pending item expired for 2 days is also deleted after idle maintenance.
     */
    @Test
    public void testDeleteExpiredPendingItem() throws IOException {
        final long expiredTwoDaysAgo =
                (System.currentTimeMillis() - (2 * DateUtils.DAY_IN_MILLIS)) / 1000;
        final Uri uri = createExpiredItem(FileUtils.PREFIX_PENDING, expiredTwoDaysAgo, "item1");

        MediaStore.runIdleMaintenance(mIsolatedContentResolver);
        MediaStore.waitForIdle(mIsolatedContentResolver);

        try (Cursor cursor = mIsolatedContentResolver.query(uri, null, null, null)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
    }

    /**
     * Confirms that a trashed item expired for 8 days has its expiration date extended, rather than
     * being deleted.
     */
    @Test
    public void testExtendExpiredTrashedItem() throws IOException {
        final long expiredEightDaysAgo =
                (System.currentTimeMillis() - (8 * DateUtils.DAY_IN_MILLIS)) / 1000;
        final Uri uri = createExpiredItem(PREFIX_TRASHED, expiredEightDaysAgo, "item2");

        MediaStore.runIdleMaintenance(mIsolatedContentResolver);
        MediaStore.waitForIdle(mIsolatedContentResolver);

        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = mIsolatedContentResolver.query(uri,
                new String[]{MediaStore.MediaColumns.DATE_EXPIRES},
                queryArgs, null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            long newDateExpires = cursor.getLong(0);
            assertThat(newDateExpires).isGreaterThan(expiredEightDaysAgo);
        }
    }

    /**
     * Confirms that a pending item expired for 8 days also has its expiration date extended.
     */
    @Test
    public void testExtendExpiredPendingItem() throws IOException {
        final long expiredEightDaysAgo =
                (System.currentTimeMillis() - (8 * DateUtils.DAY_IN_MILLIS)) / 1000;
        final Uri uri = createExpiredItem(FileUtils.PREFIX_PENDING, expiredEightDaysAgo, "item2");

        MediaStore.runIdleMaintenance(mIsolatedContentResolver);
        MediaStore.waitForIdle(mIsolatedContentResolver);

        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = mIsolatedContentResolver.query(uri,
                new String[]{MediaStore.MediaColumns.DATE_EXPIRES},
                queryArgs, null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            long newDateExpires = cursor.getLong(0);
            assertThat(newDateExpires).isGreaterThan(expiredEightDaysAgo);
        }
    }

    /**
     * Ensures that the cleanup process does not alter a trashed item that has not yet expired.
     */
    @Test
    public void testNonExpiredTrashedItem_isNotTouched() throws IOException {
        final long notExpired = (System.currentTimeMillis() + DateUtils.DAY_IN_MILLIS) / 1000;
        final Uri uri = createExpiredItem(PREFIX_TRASHED, notExpired, "item3");

        MediaStore.runIdleMaintenance(mIsolatedContentResolver);
        MediaStore.waitForIdle(mIsolatedContentResolver);

        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = mIsolatedContentResolver.query(uri,
                new String[]{MediaStore.MediaColumns.DATE_EXPIRES},
                queryArgs, null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            long dateExpires = cursor.getLong(0);
            assertThat(dateExpires).isEqualTo(notExpired);
        }
    }

    /**
     * Ensures that a pending item that has not yet expired is also correctly ignored by the cleanup
     * process.
     */
    @Test
    public void testNonExpiredPendingItem_isNotTouched() throws IOException {
        final long notExpired = (System.currentTimeMillis() + DateUtils.DAY_IN_MILLIS) / 1000;
        final Uri uri = createExpiredItem(FileUtils.PREFIX_PENDING, notExpired, "item3");

        MediaStore.runIdleMaintenance(mIsolatedContentResolver);
        MediaStore.waitForIdle(mIsolatedContentResolver);

        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = mIsolatedContentResolver.query(uri,
                new String[]{MediaStore.MediaColumns.DATE_EXPIRES},
                queryArgs, null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            long dateExpires = cursor.getLong(0);
            assertThat(dateExpires).isEqualTo(notExpired);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testDeleteExpiredTrashedFolder() throws IOException {
        final long expiredTwoDaysAgo =
                (System.currentTimeMillis() - (2 * DateUtils.DAY_IN_MILLIS)) / 1000;

        // Create the trashed folder structure directly with the .trash- prefix and expiry
        final String trashedFolder1Name = String.format(Locale.US, ".%s-%d-%s", PREFIX_TRASHED,
                expiredTwoDaysAgo, "Folder1");
        final File trashedFolder1 = new File(mDir, trashedFolder1Name);
        assertThat(trashedFolder1.mkdirs()).isTrue();

        final String trashedFolder2Name = String.format(Locale.US, ".%s-%d-%s", PREFIX_TRASHED,
                expiredTwoDaysAgo, "Folder2");
        final File trashedFolder2 = new File(trashedFolder1, trashedFolder2Name);
        assertThat(trashedFolder2.mkdirs()).isTrue();

        MediaStore.scanFile(mIsolatedContentResolver, trashedFolder1);
        MediaStore.scanFile(mIsolatedContentResolver, trashedFolder2);

        // Create the trashed files directly in their respective trashed folders
        final String trashedFile1Name = String.format(Locale.US, ".%s-%d-%s.jpg", PREFIX_TRASHED,
                expiredTwoDaysAgo, "File1");
        final File trashedFile1 = stage(R.raw.test_image,
                new File(trashedFolder1, trashedFile1Name));

        final String trashedFile2Name = String.format(Locale.US, ".%s-%d-%s.jpg", PREFIX_TRASHED,
                expiredTwoDaysAgo, "File2");
        final File trashedFile2 = stage(R.raw.test_image,
                new File(trashedFolder2, trashedFile2Name));

        final String trashedFile3Name = String.format(Locale.US, ".%s-%d-%s.jpg", PREFIX_TRASHED,
                expiredTwoDaysAgo, "File3");
        final File trashedFile3 = stage(R.raw.test_image,
                new File(trashedFolder2, trashedFile3Name));

        // Scan the files to make MediaStore aware of them
        final Uri uri1 = MediaStore.scanFile(mIsolatedContentResolver, trashedFile1);
        final Uri uri2 = MediaStore.scanFile(mIsolatedContentResolver, trashedFile2);
        final Uri uri3 = MediaStore.scanFile(mIsolatedContentResolver, trashedFile3);
        MediaStore.waitForIdle(mIsolatedContentResolver);

        // Run idle maintenance to trigger the deletion of expired items
        MediaStore.runIdleMaintenance(mIsolatedContentResolver);
        MediaStore.waitForIdle(mIsolatedContentResolver);

        // Verify that the items have been deleted from MediaStore
        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);

        try (Cursor cursor = mIsolatedContentResolver.query(uri1, null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
        try (Cursor cursor = mIsolatedContentResolver.query(uri2, null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }
        try (Cursor cursor = mIsolatedContentResolver.query(uri3, null, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(0);
        }

        // Verify that the top-level trashed folder has been deleted from the filesystem
        assertThat(trashedFolder1.exists()).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testExtendExpiredTrashedFolder() throws Exception {
        // Use an expiration of 8 days ago to trigger the extend logic.
        final long expiredEightDaysAgo =
                (System.currentTimeMillis() - (8 * DateUtils.DAY_IN_MILLIS)) / 1000;
        // Create nested trashed folder structure
        final String trashedFolder1Name = String.format(Locale.US, ".%s-%d-%s", PREFIX_TRASHED,
                expiredEightDaysAgo, "Folder1");
        final File trashedFolder1 = new File(mDir, trashedFolder1Name);
        assertThat(trashedFolder1.mkdirs()).isTrue();
        final String trashedFolder2Name = String.format(Locale.US, ".%s-%d-%s", PREFIX_TRASHED,
                expiredEightDaysAgo, "Folder2");
        final File trashedFolder2 = new File(trashedFolder1, trashedFolder2Name);
        assertThat(trashedFolder2.mkdirs()).isTrue();
        // Create trashed files in folders
        final String trashedFile1Name = String.format(Locale.US, ".%s-%d-%s.jpg", PREFIX_TRASHED,
                expiredEightDaysAgo, "File1");
        final File trashedFile1 = stage(R.raw.test_image,
                new File(trashedFolder1, trashedFile1Name));

        final String trashedFile2Name = String.format(Locale.US, ".%s-%d-%s.jpg", PREFIX_TRASHED,
                expiredEightDaysAgo, "File2");
        final File trashedFile2 = stage(R.raw.test_image,
                new File(trashedFolder2, trashedFile2Name));

        // Scan items to get original URIs and IDs
        MediaStore.scanFile(mIsolatedContentResolver, trashedFolder1);
        MediaStore.scanFile(mIsolatedContentResolver, trashedFolder2);
        final Uri fileUri1 = MediaStore.scanFile(mIsolatedContentResolver, trashedFile1);
        final Uri fileUri2 = MediaStore.scanFile(mIsolatedContentResolver, trashedFile2);
        final long originalFileId1 = ContentUris.parseId(fileUri1);
        final long originalFileId2 = ContentUris.parseId(fileUri2);

        // Run idle maintenance to trigger extension
        MediaStore.runIdleMaintenance(mIsolatedContentResolver);
        MediaStore.waitForIdle(mIsolatedContentResolver);

        // Verify physical filesystem changes
        final File[] files = mDir.listFiles();
        assertThat(files).hasLength(1);
        final File newFolder1 = files[0];
        assertThat(newFolder1.getName()).isNotEqualTo(trashedFolder1Name);

        // Verify MediaStore data consistency and URI stability
        final Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);

        // File 1 Path updated, Expiry updated, ID/URI remained same
        try (Cursor cursor = mIsolatedContentResolver.query(fileUri1,
                new String[]{MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_EXPIRES,
                        MediaStore.MediaColumns._ID},
                queryArgs, null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            final String newPath = cursor.getString(0);
            final long newExpiry = cursor.getLong(1);
            final long newId = cursor.getLong(2);

            assertThat(newPath).contains(newFolder1.getName());
            assertThat(newExpiry).isGreaterThan(expiredEightDaysAgo);
            assertThat(newId).isEqualTo(originalFileId1);
        }

        // File 2 Nested path and expiry consistency
        try (Cursor cursor = mIsolatedContentResolver.query(fileUri2,
                new String[]{MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_EXPIRES,
                        MediaStore.MediaColumns._ID},
                queryArgs, null)) {
            assertThat(cursor.moveToFirst()).isTrue();
            final String newPath = cursor.getString(0);
            final long newExpiry = cursor.getLong(1);
            final long newId = cursor.getLong(2);

            assertThat(newPath).contains(newFolder1.getName());
            assertThat(newExpiry).isGreaterThan(expiredEightDaysAgo);
            assertThat(newId).isEqualTo(originalFileId2);
        }
    }

    private void resetIsolatedContext() {
        if (mIsolatedContentResolver != null) {
            // This is necessary, we wait for all unfinished tasks to finish before we create a
            // new IsolatedContext.
            MediaStore.waitForIdle(mIsolatedContentResolver);
        }

        Context context = InstrumentationRegistry.getTargetContext();
        IsolatedContext isolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/
                false);
        mIsolatedContentResolver = isolatedContext.getContentResolver();
    }

    private Uri createExpiredItem(String prefix, long dateExpires, String displayName)
            throws IOException {
        final String fileName = String.format(Locale.US, ".%s-%d-%s.jpg", prefix, dateExpires,
                displayName);
        final File file = stage(R.raw.test_image, new File(mDir, fileName));
        final Uri uri = MediaStore.scanFile(mIsolatedContentResolver, file);
        MediaStore.waitForIdle(mIsolatedContentResolver);

        final String[] projection = new String[]{MediaStore.MediaColumns.DATE_EXPIRES};
        final Bundle queryArgs = new Bundle();
        if (prefix.equals(PREFIX_TRASHED)) {
            queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        } else if (prefix.equals(FileUtils.PREFIX_PENDING)) {
            queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        }

        try (Cursor cursor = mIsolatedContentResolver.query(uri, projection, queryArgs, null)) {
            assertThat(cursor.getCount()).isEqualTo(1);
        }
        return uri;
    }
}
