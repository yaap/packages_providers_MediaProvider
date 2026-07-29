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

import static com.android.providers.media.scan.MediaScannerTest.stage;
import static com.android.providers.media.scan.ModernMediaScannerTest.executeShellCommand;
import static com.android.providers.media.util.FileUtils.DIRECTORY_TRASH_STORAGE;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.flags.Flags;
import com.android.providers.media.util.FileUtils;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class MediaStoreTrashedTest {

    private static final int MAX_FILENAME_BYTES = FileUtils.MAX_FILENAME_BYTES;

    private static IsolatedContext sIsolatedContext;
    private static ContentResolver sIsolatedResolver;
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private File mTestDir;
    private File mTrashedDir;

    private static void resetIsolatedContext() {
        if (sIsolatedResolver != null) {
            // This is necessary, we wait for all unfinished tasks to finish before we create a
            // new IsolatedContext.
            MediaStore.waitForIdle(sIsolatedResolver);
        }

        Context context = InstrumentationRegistry.getTargetContext();
        sIsolatedContext = new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        sIsolatedResolver = sIsolatedContext.getContentResolver();
    }

    @Before
    public void setUp() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.MANAGE_EXTERNAL_STORAGE);
        resetIsolatedContext();

        final File downloadDir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        mTestDir = new File(downloadDir, "test-" + System.nanoTime());
        mTestDir.mkdirs();

        mTrashedDir = new File(Environment.getExternalStorageDirectory(), DIRECTORY_TRASH_STORAGE);

        MediaStore.scanFile(sIsolatedResolver, mTestDir);
    }

    @After
    public void tearDown() {
        if (mTestDir != null) {
            FileUtils.deleteContents(mTestDir);
            mTestDir.delete();
            if (mTrashedDir != null) {
                final File downloadDir = Environment
                        .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File testFolderUnderTrash = new File(mTrashedDir,
                        downloadDir.getName() + File.separator + mTestDir.getName());
                FileUtils.deleteContents(testFolderUnderTrash);
                testFolderUnderTrash.delete();
            }
        }
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    /**
     * Verifies that when a file is trashed, its file path ({@link MediaColumns#DATA})
     * points to a location within the volume's trash directory, and when restored,
     * it points back to its original file path.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testTrashFile_movesToTrashStorageDirectory() throws Exception {
        final String originalName = "image-" + System.nanoTime() + ".jpg";
        File file = stage(R.raw.lg_g4_iso_800_jpg, new File(mTestDir, originalName));
        final Uri uri = insertToMediaStore(file);
        FileTestData fileTestData = getFileTestData(uri);

        // Trash the file
        String trashedPath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            trashedPath = MediaStore.trashFile(sIsolatedResolver, file.getPath());
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }

        // Verify that the file path has been updated to the trash directory
        assertTrashStateInTrashLocation(trashedPath, fileTestData);

        // Verify the file system reflects the move
        File trashedFile = new File(trashedPath);
        assertTrue("Trashed file should exist on disk", trashedFile.exists());
        assertFalse("Original file should not exist on disk", file.exists());

        // Restore the file to its original location
        String restoredPath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            restoredPath = MediaStore.restoreFileFromTrash(sIsolatedResolver, trashedPath, null);
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }

        // Verify the file is restored to its original path
        assertEquals("Restored path should match original path", file.getPath(), restoredPath);
        assertRestoreStateFromTrashLocation(fileTestData, restoredPath);

        // Verify the file system reflects the restore
        assertTrue("Restored file should exist at original path", file.exists());
        assertFalse("Trashed file should no longer exist", trashedFile.exists());
    }

    /**
     * Verifies that when a trashed item is restored, its parent directory within the trash
     * is also deleted if it becomes empty.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testRestoreTrashedFile_deletesEmptyParentInTrash() throws Exception {
        // Setup a file in a unique subdirectory to ensure it's the only one
        final File subDir = new File(mTestDir, "testDir");
        subDir.mkdirs();
        final String originalName = "image_to_restore" + System.nanoTime() + ".jpg";
        File file = stage(R.raw.lg_g4_iso_800_jpg, new File(subDir, originalName));
        MediaStore.scanFile(sIsolatedResolver, file);

        // Trash the file
        String trashedPath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            trashedPath = MediaStore.trashFile(sIsolatedResolver, file.getPath());
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }

        // Verify its parent directory exists in the trash storage
        File trashedFile = new File(trashedPath);
        File trashedParentDir = trashedFile.getParentFile();
        assertTrue("Parent directory in trash should exist after trashing",
                trashedParentDir.exists());

        // Restore the file (it's the last one in its trashed parent dir)
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            MediaStore.restoreFileFromTrash(sIsolatedResolver, trashedPath, null);
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }

        // Verify the parent directory in trash has been cleaned up
        assertFalse("Parent directory in trash should be deleted after restoring last item",
                trashedParentDir.exists());
        assertTrue("Restored file should exist at original location", file.exists());
    }

    /**
     * Verifies that after a trashed item in a subdirectory is permanently deleted, its parent
     * directories that become empty are also removed.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testDeleteTrashedFile_deletesEmptyParentInTrash() throws Exception {
        // Setup a file in a unique subdirectory to ensure it's the only one
        final File subDir = new File(mTestDir, "deleteTestDir");
        subDir.mkdirs();
        final String originalName = "image_to_delete.jpg";
        File file = stage(R.raw.lg_g4_iso_800_jpg, new File(subDir, originalName));
        MediaStore.scanFile(sIsolatedResolver, file);

        // Trash the file
        String trashedPath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            trashedPath = MediaStore.trashFile(sIsolatedResolver, file.getPath());
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }

        // Get the trashed item for deletion and verify parent exists
        File trashedFile = new File(trashedPath);
        File trashedParentDir = trashedFile.getParentFile();
        assertTrue("Parent directory in trash should exist after trashing",
                trashedParentDir.exists());

        trashedFile.delete();
        MediaStore.scanFile(sIsolatedResolver, trashedFile);

        // Wait for the background jobs to be completed
        MediaStore.waitForIdle(sIsolatedResolver);

        // Verify the parent directory in trash has been cleaned up
        assertFalse("Parent directory in trash should be deleted after deleting the trashed item",
                trashedParentDir.exists());
        assertFalse("Trashed file should not exist after deletion", trashedFile.exists());
        assertFalse("Original file should also not exist", file.exists());
    }

    /**
     * Verifies that when a file is trashed, its metadata is preserved.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testTrashFile_preservesMetadata() throws Exception {
        final String originalFileName = "image.jpg";
        final File file = stage(R.raw.lg_g4_iso_800_jpg, new File(mTestDir, originalFileName));
        final Uri fileUri = insertToMediaStore(file);
        final FileTestData fileTestData = getFileTestData(fileUri);

        // Perform the trash action.
        String trashedFilePath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            trashedFilePath = MediaStore.trashFile(sIsolatedResolver, file.getPath());
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }
        // Trigger a full scan to ensure that background operations (like metadata extraction)
        // are completed and the database reflects the final, persisted state of the trashed
        // item before we verify its metadata.
        MediaStore.waitForIdle(sIsolatedResolver);
        MediaStore.scanVolume(sIsolatedResolver, MediaStore.VOLUME_EXTERNAL_PRIMARY);

        // Verify the trashed file state.
        assertTrue("Trashed path should be in trash directory",
                trashedFilePath.startsWith(mTrashedDir.getPath()));
        assertFalse("Original file should not exist", file.exists());
        final File trashedFile = new File(trashedFilePath);
        assertTrue("Trashed file should exist", trashedFile.exists());
        assertTrashStateInTrashLocation(trashedFilePath, fileTestData);
    }

    /**
     * Verifies that when a file is restored, its metadata is preserved.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testRestoreFile_preservesMetadata() throws Exception {
        final String originalFileName = "image" + System.nanoTime() + ".jpg";
        final File file = stage(R.raw.lg_g4_iso_800_jpg, new File(mTestDir, originalFileName));
        final Uri fileUri = insertToMediaStore(file);
        final FileTestData fileTestData = getFileTestData(fileUri);

        String restoredFilePath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            String trashedFilePath = MediaStore.trashFile(sIsolatedResolver, file.getPath());
            restoredFilePath = MediaStore.restoreFileFromTrash(sIsolatedResolver,
                    trashedFilePath, null);
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }
        // Trigger a full scan to ensure that background operations (like metadata extraction)
        // are completed and the database reflects the final, persisted state of the trashed
        // item before we verify its metadata.
        MediaStore.waitForIdle(sIsolatedResolver);
        MediaStore.scanVolume(sIsolatedResolver, MediaStore.VOLUME_EXTERNAL_PRIMARY);

        // Verify the restored state.
        assertEquals("Restored path should match original file path", file.getPath(),
                restoredFilePath);
        assertTrue("Restored file should exist", file.exists());
        // Verify the file is restored and preserve the selected metadata.
        assertRestoreStateFromTrashLocation(fileTestData, restoredFilePath);
    }

    /**
     * Verifies that when a folder is trashed, its children's metadata is preserved.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testTrashFolder_preservesMetadata() throws Exception {
        final File folder = new File(mTestDir, "TestFolder");
        assertTrue("Failed to create test folder", folder.mkdirs());
        final String fileName = "image-" + System.nanoTime() + ".jpg";
        final File file = stage(R.raw.lg_g4_iso_800_jpg, new File(folder, fileName));
        final Uri fileUri = insertToMediaStore(file);
        final FileTestData fileTestData = getFileTestData(fileUri);

        String trashedFolderPath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            trashedFolderPath = MediaStore.trashFile(sIsolatedResolver, folder.getPath());
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }
        // Trigger a full scan to ensure that background operations (like metadata extraction)
        // are completed and the database reflects the final, persisted state of the trashed
        // item before we verify its metadata.
        MediaStore.waitForIdle(sIsolatedResolver);
        MediaStore.scanVolume(sIsolatedResolver, MediaStore.VOLUME_EXTERNAL_PRIMARY);

        // Verify the trashed folder state.
        assertTrue("Trashed path should be in trash directory",
                trashedFolderPath.startsWith(mTrashedDir.getPath()));
        assertFalse("Original folder should not exist", folder.exists());
        final File trashedFolder = new File(trashedFolderPath);
        assertTrue("Trashed folder should exist", trashedFolder.exists());

        final FileTestData trashedFileTestData = getFileTestData(fileUri);
        // Verify the file is trashed and preserve the selected metadata.
        assertTrashStateInTrashLocation(trashedFileTestData.mFilePath, fileTestData);
    }

    /**
     * Verifies that when a folder is restored, its metadata is preserved.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testRestoreFolder_preservesMetadata() throws Exception {
        final File folder = new File(mTestDir, "TestFolder");
        assertTrue("Failed to create test folder", folder.mkdirs());
        final String originalFileName = "my_image.jpg";
        final File file = stage(R.raw.lg_g4_iso_800_jpg, new File(folder, originalFileName));
        final Uri fileUri = insertToMediaStore(file);
        final FileTestData fileTestData = getFileTestData(fileUri);

        String restoredFolderPath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            String trashedFolderPath = MediaStore.trashFile(sIsolatedResolver, folder.getPath());
            restoredFolderPath = MediaStore.restoreFileFromTrash(sIsolatedResolver,
                    trashedFolderPath, null);
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }
        // Trigger a full scan to ensure that background operations (like metadata extraction)
        // are completed and the database reflects the final, persisted state of the trashed
        // item before we verify its metadata.
        MediaStore.waitForIdle(sIsolatedResolver);
        MediaStore.scanVolume(sIsolatedResolver, MediaStore.VOLUME_EXTERNAL_PRIMARY);

        // Verify the restored state.
        assertEquals("Restored path should match original folder path", folder.getPath(),
                restoredFolderPath);
        assertTrue("Restored folder should exist", folder.exists());
        // Verify the file is restored and preserve the selected metadata.
        String restoredFilePath = getFileTestData(fileUri).mFilePath;
        assertRestoreStateFromTrashLocation(fileTestData, restoredFilePath);
        assertTrue("Restored file should exist on disk", file.exists());
    }

    /**
     * Verifies that when a directory with a legacy trashed file is trashed, the legacy file is
     * moved to its corresponding location in the trash, and the now-empty directory is trashed
     * separately.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testTrashFolderWithLegacyTrashedFile_movesFileSeparately() throws Exception {
        // Create a directory with a legacy trashed file inside.
        final File folderToTrash = new File(mTestDir, "Hello");
        assertTrue("Test folder should be created", folderToTrash.mkdirs());
        final File legacyTrashedFile = new File(folderToTrash, ".trashed-12345-image.jpg");
        final File file = stage(R.raw.lg_g4_iso_800_jpg, legacyTrashedFile);
        final Uri fileUri = insertToMediaStore(file);
        assertNotNull("Uri should not be null after insert", fileUri);

        // Trash the directory.
        String trashedFolderPath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            trashedFolderPath = MediaStore.trashFile(sIsolatedResolver, folderToTrash.getPath());
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }
        // Trigger a full scan to ensure that background operations (like metadata extraction)
        // are completed and the database reflects the final, persisted state of the trashed
        // item before we verify its metadata.
        MediaStore.waitForIdle(sIsolatedResolver);
        MediaStore.scanVolume(sIsolatedResolver, MediaStore.VOLUME_EXTERNAL_PRIMARY);

        // Verify original items are gone.
        assertFalse("Original folder should not exist after being trashed", folderToTrash.exists());
        assertFalse("Legacy trashed file should not exist in its original location",
                legacyTrashedFile.exists());

        // Verify the folder was moved to the trash and is now empty.
        File trashedFolder = new File(trashedFolderPath);
        assertTrue("Trashed folder should exist in the trash directory", trashedFolder.exists());
        assertTrue("Trashed folder path should be in the trash storage directory",
                trashedFolderPath.startsWith(mTrashedDir.getPath()));
        assertEquals("Trashed folder should now be empty", 0, trashedFolder.list().length);

        // Verify the legacy file was moved separately, preserving its directory structure.
        final String relativePath = folderToTrash.getPath().substring(
                Environment.getExternalStorageDirectory().getPath().length());
        final File expectedLegacyFileParentInTrash = new File(mTrashedDir, relativePath);
        final File expectedLegacyFileInTrash = new File(expectedLegacyFileParentInTrash,
                legacyTrashedFile.getName());

        assertTrue("Parent directory for legacy file should be created in trash",
                expectedLegacyFileParentInTrash.exists());
        assertTrue("Legacy trashed file should exist separately in trash",
                expectedLegacyFileInTrash.exists());

        // Verify MediaStore records for both items.
        try (Cursor c = getCursorByPath(trashedFolderPath)) {
            assertNotNull(c);
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("Folder should be marked as trashed", 1, c.getInt(c.getColumnIndexOrThrow(
                    MediaColumns.IS_TRASHED)));
        }

        try (Cursor c = getCursorByPath(expectedLegacyFileInTrash.getPath())) {
            assertNotNull(c);
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("Legacy file should be marked as trashed", 1,
                    c.getInt(c.getColumnIndexOrThrow(
                            MediaColumns.IS_TRASHED)));
            assertEquals("Legacy file path should be updated", expectedLegacyFileInTrash.getPath(),
                    c.getString(c.getColumnIndexOrThrow(MediaColumns.DATA)));
        }
    }

    /**
     * Verifies that when a file is trashed using the update or createTrashRequest APIs, its file
     * path ({@link MediaColumns#DATA}) points to a location within the volume's trash directory,
     * and when restored, it points back to its original file path.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testTrash_movesToTrashStorageDirectory() throws Exception {
        final String originalName = "image" + System.nanoTime() + ".jpg";
        File file = stage(R.raw.lg_g4_iso_800_jpg, new File(mTestDir, originalName));
        final Uri uri = insertToMediaStore(file);
        final FileTestData fileTestData = getFileTestData(uri);

        final Bundle extras = new Bundle();
        extras.putBoolean(MediaStore.QUERY_ARG_ALLOW_MOVEMENT, true);
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.IS_TRASHED, 1);

        // Trash the file
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            sIsolatedResolver.update(uri, values, extras);
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }


        // Verify trashed state
        FileTestData trashedFileData = getFileTestData(uri);
        assertTrashStateInTrashLocation(trashedFileData.mFilePath, fileTestData);

        // Restore the file
        values.clear();
        values.put(MediaColumns.IS_TRASHED, 0);
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            sIsolatedResolver.update(uri, values, extras);
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }

        // Verify restored state
        assertInPlaceRestoreState(uri, file);
    }

    /**
     * Verifies legacy trash behavior, file is renamed with a ".trashed-" prefix in its
     * original directory, followed by a restore using the update API.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testTrash_renamesWithLegacyPrefix() throws Exception {
        // Legacy trash behavior will work only on target sdk version Baklava and before
        Assume.assumeTrue(sIsolatedContext.getApplicationInfo().targetSdkVersion
                <= Build.VERSION_CODES.BAKLAVA);

        final String originalName = "image.jpg";
        File file = stage(R.raw.lg_g4_iso_800_jpg, new File(mTestDir, originalName));
        final Uri uri = MediaStore.scanFile(sIsolatedResolver, file);

        final Bundle extras = new Bundle();
        extras.putBoolean(MediaStore.QUERY_ARG_ALLOW_MOVEMENT, true);
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.IS_TRASHED, 1);

        // Trash the file
        sIsolatedResolver.update(uri, values, extras);

        // Verify trashed state
        assertInPlaceTrashState(file, uri);

        // Restore the file
        values.clear();
        values.put(MediaColumns.IS_TRASHED, 0);
        sIsolatedResolver.update(uri, values, extras);

        // Verify restored state
        assertInPlaceRestoreState(uri, file);
    }

    /**
     * Verifies legacy trash (in-place rename) combined with the new
     * {@link MediaStore#restoreFileFromTrash} API for restoration.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testLegacyTrashAndNewRestore() throws Exception {
        // Legacy trash behavior will work only on target sdk version Baklava and before
        Assume.assumeTrue(sIsolatedContext.getApplicationInfo().targetSdkVersion
                <= Build.VERSION_CODES.BAKLAVA);

        final String originalName = "image.jpg";
        File file = stage(R.raw.lg_g4_iso_800_jpg, new File(mTestDir, originalName));
        final Uri uri = insertToMediaStore(file);
        final FileTestData fileTestData = getFileTestData(uri);

        final Bundle extras = new Bundle();
        extras.putBoolean(MediaStore.QUERY_ARG_ALLOW_MOVEMENT, true);
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.IS_TRASHED, 1);

        // Trash the file
        sIsolatedResolver.update(uri, values, extras);

        // Verify trashed state
        assertInPlaceTrashState(file, uri);

        File trashedFile = new File(getFileTestData(uri).mFilePath);
        // Restore the file to its original location
        String restoredPath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            restoredPath = MediaStore.restoreFileFromTrash(sIsolatedResolver,
                    trashedFile.getAbsolutePath(), null);
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }

        // Verify the file is restored to its original path
        assertEquals("Restored path should match original path", file.getPath(), restoredPath);
        assertRestoreStateFromTrashLocation(fileTestData, restoredPath);

        // Verify the file system reflects the restore
        assertTrue("Restored file should exist at original path", file.exists());
        assertFalse("Trashed file should no longer exist", trashedFile.exists());
    }

    /**
     * Verifies new trash behavior (file moved to dedicated trash directory) followed by
     * restoration using the update API.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testNewTrashAndLegacyRestore() throws Exception {
        // Legacy trash behavior will work only on target sdk version Baklava and before
        Assume.assumeTrue(sIsolatedContext.getApplicationInfo().targetSdkVersion
                <= Build.VERSION_CODES.BAKLAVA);
        final String originalName = "image.jpg";
        File file = stage(R.raw.lg_g4_iso_800_jpg, new File(mTestDir, originalName));
        final Uri uri = insertToMediaStore(file);
        final FileTestData fileTestData = getFileTestData(uri);

        // Trash the file
        String trashedPath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            trashedPath = MediaStore.trashFile(sIsolatedResolver, file.getPath());
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }

        // Verify that the file path has been updated to the trash directory
        assertTrashStateInTrashLocation(trashedPath, fileTestData);

        final Bundle extras = new Bundle();
        extras.putBoolean(MediaStore.QUERY_ARG_ALLOW_MOVEMENT, true);
        final ContentValues values = new ContentValues();
        values.put(MediaColumns.IS_TRASHED, 0);

        // Restore the file
        sIsolatedResolver.update(uri, values, extras);

        // Verify restored state
        assertInPlaceRestoreState(uri, file);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testTrashTopLevelDefaultDirectory_fails() throws Exception {
        final File dcim = createTopLevelDir(Environment.DIRECTORY_DCIM);

        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            MediaStore.trashFile(sIsolatedResolver, dcim.getPath());
            fail("Trashing a default directory should have failed");
        } catch (Exception e) {
            // expected
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testTrashTopLevelDefaultDirectory_caseInsensitive_fails() throws Exception {
        final File downloads = createTopLevelDir(Environment.DIRECTORY_DOWNLOADS.toLowerCase(
                Locale.ROOT));

        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            MediaStore.trashFile(sIsolatedResolver, downloads.getPath());
            fail("Trashing a default directory should have failed");
        } catch (Exception e) {
            // expected
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
            downloads.delete();
        }
    }

    @Test
    @Ignore("b/472707437")
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testTrashTopLevelDirectory_success() throws Exception {
        final File topLevelFolder = createTopLevelDir(mTestDir.getName());

        try {
            String trashedPath;
            try {
                sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
                trashedPath = MediaStore.trashFile(sIsolatedResolver, topLevelFolder.getPath());
            } finally {
                sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
            }

            assertTrue(FileUtils.isTrashedFileInTrashDirectory(trashedPath));

            String restoredPath;
            try {
                sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
                restoredPath = MediaStore.restoreFileFromTrash(sIsolatedResolver,
                        trashedPath, /* targetPath */ null);
            } finally {
                sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
            }

            assertEquals(topLevelFolder.getPath(), restoredPath);
        } finally {
            deleteTopLevelDir(topLevelFolder);
        }
    }

    /**
     * Verifies that when a file with an extremely long name is trashed, the resulting
     * trashed file path is trimmed to stay within the filesystem's limits
     * (MAX_FILENAME_BYTES bytes).
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    public void testTrashFile_trimsLongFileName() throws Exception {
        // Create a filename that is already at the MAX_FILENAME_BYTES-byte limit
        final String longName = createExtremeFileName("extremely-long-name-", ".jpg");
        File file = stage(R.raw.lg_g4_iso_800_jpg, new File(mTestDir, longName));
        final Uri uri = insertToMediaStore(file);

        // Trash the file. The trashing process adds a ".trashed-timestamp-" prefix (~20 bytes).
        // The resulting name would exceed MAX_FILENAME_BYTE bytes and would fail
        // without trimming logic.
        String trashedPath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            trashedPath = MediaStore.trashFile(sIsolatedResolver, file.getPath());
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }

        // Verify the trashed filename is within the MAX_FILENAME_BYTES-byte limit
        String trashedFileName = new File(trashedPath).getName();
        assertTrue("Trashed filename length (" + trashedFileName.getBytes().length
                        + " bytes) should be within MAX_FILENAME_BYTES bytes",
                trashedFileName.getBytes().length <= MAX_FILENAME_BYTES);

        // Verify the file system reflects the move to the trimmed path
        File trashedFile = new File(trashedPath);
        assertTrue("Trashed file should exist on disk at trimmed path", trashedFile.exists());
        assertFalse("Original file should no longer exist", file.exists());

        // Verify we can still restore it
        String restoredPath;
        try {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(true);
            restoredPath = MediaStore.restoreFileFromTrash(sIsolatedResolver, trashedPath, null);
        } finally {
            sIsolatedContext.setByPassTargetSdkCheckForTrash(false);
        }

        // Verify the restored file exists (Note: the name may remain trimmed if it was
        // lossily shortened during the trash operation)
        assertTrue("Restored file should exist on disk", new File(restoredPath).exists());
    }

    /**
     * Helper to create a string of exactly MAX_FILENAME_BYTES bytes.
     */
    private static String createExtremeFileName(String prefix, String extension) {
        StringBuilder str = new StringBuilder(prefix);
        // MAX_FILENAME_BYTES is the limit defined in FileUtils
        for (int i = 0; i < (MAX_FILENAME_BYTES - prefix.length() - extension.length()); i++) {
            str.append(i % 10);
        }
        return str.append(extension).toString();
    }

    /**
     * Queries for a media item by its file path, including trashed items.
     *
     * @param path The file path to query for.
     * @return A cursor containing the query results.
     */
    private Cursor getCursorByPath(String path) {
        Bundle queryArgs = new Bundle();
        String[] selectionArgs = new String[]{path};
        queryArgs.putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaColumns.DATA + " = ?");
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);

        return sIsolatedResolver
                .query(
                        MediaStore.Files.EXTERNAL_CONTENT_URI,
                        null,
                        queryArgs,
                        null);
    }

    /**
     * Inserts a file into the MediaStore and returns its URI.
     *
     * @param file The file to insert.
     * @return The URI of the newly inserted item.
     */
    private Uri insertToMediaStore(File file) {
        final Uri imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
        values.put(MediaStore.MediaColumns.DATA, file.getAbsolutePath());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, "com.android.providers.media.test");
        values.put(MediaStore.MediaColumns.IS_FAVORITE, 1);
        return sIsolatedResolver.insert(imageUri, values);
    }

    private FileTestData getFileTestData(Uri uri) {
        Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor c = sIsolatedResolver.query(uri, null, queryArgs, null)) {
            assertNotNull(c);
            assertEquals("Should have one entry for the new file", 1, c.getCount());
            assertTrue(c.moveToFirst());
            long originalFileId = c.getLong(c.getColumnIndexOrThrow(MediaColumns._ID));
            String originalOwnerPackageName = c.getString(
                    c.getColumnIndexOrThrow(MediaColumns.OWNER_PACKAGE_NAME));
            int originalIsFavorite = c.getInt(c.getColumnIndexOrThrow(MediaColumns.IS_FAVORITE));
            String originalFilePath = c.getString(c.getColumnIndexOrThrow(MediaColumns.DATA));
            return new FileTestData(originalFileId, originalFilePath,
                    originalOwnerPackageName, originalIsFavorite);
        }
    }

    private void assertTrashStateInTrashLocation(String trashedPath, FileTestData fileTestData) {
        try (Cursor c = getCursorByPath(trashedPath)) {
            assertNotNull(c);
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow(MediaColumns.IS_TRASHED)));
            assertEquals(trashedPath, c.getString(c.getColumnIndexOrThrow(MediaColumns.DATA)));
            assertEquals(fileTestData.mIsFavorite,
                    c.getInt(c.getColumnIndexOrThrow(MediaColumns.IS_FAVORITE)));
            assertEquals(fileTestData.mOwnerPackageName,
                    c.getString(c.getColumnIndexOrThrow(MediaColumns.OWNER_PACKAGE_NAME)));
            assertEquals(fileTestData.mId, c.getLong(c.getColumnIndexOrThrow(MediaColumns._ID)));
            assertTrue(FileUtils.isTrashedFileInTrashDirectory(trashedPath));
        }
    }

    private void assertInPlaceTrashState(File file, Uri fileUri) {
        Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);

        try (Cursor c = sIsolatedResolver.query(fileUri, null, queryArgs, null)) {
            assertTrue(c.moveToFirst());
            assertEquals(1,
                    c.getInt(c.getColumnIndexOrThrow(MediaColumns.IS_TRASHED)));
            assertEquals(file.getName(),
                    c.getString(c.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)));

            String data = c.getString(c.getColumnIndexOrThrow(MediaColumns.DATA));
            File trashedFile = new File(data);

            // Trashed file should be in the same parent directory
            assertEquals(file.getParent(), trashedFile.getParent());
            // Trashed file name should be prefixed and contain the original name
            assertTrue("File name should be prefixed with .trashed-",
                    trashedFile.getName().startsWith(".trashed-"));
            assertTrue("File name should contain original name",
                    trashedFile.getName().endsWith(file.getName()));
        }
    }

    private void assertRestoreStateFromTrashLocation(FileTestData fileTestData,
            String restoredPath) {
        try (Cursor c = getCursorByPath(restoredPath)) {
            assertNotNull(c);
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow(MediaColumns.IS_TRASHED)));
            assertEquals(fileTestData.mFilePath,
                    c.getString(c.getColumnIndexOrThrow(MediaColumns.DATA)));
            assertEquals(fileTestData.mIsFavorite,
                    c.getInt(c.getColumnIndexOrThrow(MediaColumns.IS_FAVORITE)));
            assertEquals(fileTestData.mOwnerPackageName,
                    c.getString(c.getColumnIndexOrThrow(MediaColumns.OWNER_PACKAGE_NAME)));
            assertEquals(fileTestData.mId, c.getLong(c.getColumnIndexOrThrow(MediaColumns._ID)));
        }
    }

    private void assertInPlaceRestoreState(Uri uri, File file) {
        try (Cursor c = sIsolatedResolver.query(uri, null, null, null)) {
            assertTrue(c.moveToFirst());
            assertEquals(0,
                    c.getInt(c.getColumnIndexOrThrow(MediaColumns.IS_TRASHED)));
            assertEquals(file.getName(),
                    c.getString(c.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)));
            assertEquals(file.getAbsolutePath(),
                    c.getString(c.getColumnIndexOrThrow(MediaColumns.DATA)));
        }
    }

    private File createTopLevelDir(String topLevelDirName) throws IOException {
        // Top Level directory is not allowed by MediaProvider, so the directory is created via
        // shell command.

        File topLevelDir = new File(Environment.getExternalStorageDirectory(), topLevelDirName);

        final String createTopLevelDirCommand =
                "mkdir -p " + topLevelDir.getAbsolutePath();

        executeShellCommand(createTopLevelDirCommand);

        // Force the mock MediaProvider to scan.
        final Uri uri = MediaStore.scanFile(sIsolatedResolver, topLevelDir);
        assertWithMessage("Uri obtained by scanning file " + topLevelDir)
                .that(uri)
                .isNotNull();

        return topLevelDir;
    }

    private void deleteTopLevelDir(File topLevelDir) throws IOException {
        final String removeTopLevelDirCommand =
                "rm -rf " + topLevelDir.getPath();
        executeShellCommand(removeTopLevelDirCommand);
    }

    static class FileTestData {
        public long mId;
        public String mFilePath;
        public String mOwnerPackageName;
        public int mIsFavorite;

        FileTestData(long id, String filePath, String ownerPackageName,
                int isFavorite) {
            this.mId = id;
            this.mFilePath = filePath;
            this.mOwnerPackageName = ownerPackageName;
            this.mIsFavorite = isFavorite;
        }

    }
}
