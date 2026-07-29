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

import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.util.FileUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link MediaStore} notifications.
 */
@RunWith(AndroidJUnit4.class)
public class MediaStoreNotifyTest {

    private File mDir;
    private ContentResolver mContentResolver;

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG);
        mContentResolver = InstrumentationRegistry.getContext().getContentResolver();

        File downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);

        mDir = new File(downloadsDir, "test_" + System.nanoTime());
        mDir.mkdirs();
        FileUtils.deleteContents(mDir);
        MediaStore.scanFile(mContentResolver, mDir);
    }

    @After
    public void tearDown() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().adoptShellPermissionIdentity();
        if (mDir != null) {
            FileUtils.deleteContents(mDir);
            mDir.delete();
        }
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    /**
     * Tests that an INSERT notification is received when a new file is added and scanned.
     */
    @Test
    public void testNotifyInsertOnFile() throws Exception {
        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        final TestContentObserver observer = TestContentObserver.create(
                filesUri, ContentResolver.NOTIFY_INSERT, /* expectedCount */ 1);
        try {
            final File file = new File(mDir, "test-" + System.nanoTime() + ".txt");
            assertTrue(file.createNewFile());
            MediaStore.scanFile(mContentResolver, file);
            // Wait for both notifications to be received.
            observer.waitForChange();
            assertReceivedUris(observer.getReceivedUris(), file);
        } finally {
            observer.unregister();
        }
    }

    /**
     * Tests that an UPDATE notification is received when an existing file is renamed and scanned.
     */
    @Test
    public void testNotifyUpdateOnFile() throws Exception {
        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        final File file = new File(mDir, "test-" + System.nanoTime() + ".txt");
        assertTrue(file.createNewFile());
        MediaStore.scanFile(mContentResolver, file);
        final TestContentObserver observer = TestContentObserver.create(
                filesUri, ContentResolver.NOTIFY_UPDATE, /* expectedCount */ 1);
        try {
            // Rename the file on disk.
            final File newFile = new File(mDir, "renamed-" + System.nanoTime() + ".txt");
            assertTrue(file.renameTo(newFile));
            // Wait for the update notification to be received.
            observer.waitForChange();
            // Verify that we received the correct notification for the original Uri.
            assertReceivedUris(observer.getReceivedUris(), newFile);
        } finally {
            observer.unregister();
        }
    }

    /**
     * Tests that a DELETE notification is received when an existing file is deleted and scanned.
     */
    @Test
    public void testNotifyDeleteOnFile() throws Exception {
        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        final File file = new File(mDir, "test-" + System.nanoTime() + ".txt");
        assertTrue(file.createNewFile());
        Uri uri = MediaStore.scanFile(mContentResolver, file);
        final TestContentObserver observer = TestContentObserver.create(
                filesUri, ContentResolver.NOTIFY_DELETE, /* expectedCount */ 1);
        try {
            assertTrue(file.delete());
            // Wait for the update notification to be received.
            observer.waitForChange();
            // Verify that we received the correct notification for the original Uri.
            assertReceivedUris(observer.getReceivedUris(), uri);
        } finally {
            observer.unregister();
        }
    }

    /**
     * Tests that an INSERT notification is received when a new folder is created and scanned.
     */
    @Test
    public void testNotifyInsertOnFolder() {
        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        final TestContentObserver observer = TestContentObserver.create(
                filesUri, ContentResolver.NOTIFY_INSERT, /* expectedCount */ 1);
        try {
            final File folder = new File(mDir, "test-" + System.nanoTime());
            assertTrue(folder.mkdirs());
            final Uri scannedFolderUri = MediaStore.scanFile(mContentResolver, folder);
            // Wait for both notifications to be received.
            observer.waitForChange();
            assertReceivedUris(observer.getReceivedUris(), scannedFolderUri);
        } finally {
            observer.unregister();
        }
    }

    /**
     * Tests that a folder rename triggers a DELETE notification for the old folder Uri
     * and an INSERT notification for the new folder Uri.
     */
    @Test
    public void testNotifyInsertAndDeleteOnFolder() {
        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        final TestContentObserver insertObserver = TestContentObserver.create(
                filesUri, ContentResolver.NOTIFY_INSERT, /* expectedCount */ 1);
        final TestContentObserver deleteObserver = TestContentObserver.create(
                filesUri, ContentResolver.NOTIFY_DELETE, /* expectedCount */ 1);
        final File folder = new File(mDir, "test-" + System.nanoTime());
        assertTrue(folder.mkdirs());
        Uri scannedFolderUri = MediaStore.scanFile(mContentResolver, folder);
        try {
            // Rename the folder on disk.
            final File newFolder = new File(mDir, "renamed-" + System.nanoTime());
            assertTrue(folder.renameTo(newFolder));
            MediaStore.scanFile(mContentResolver,  newFolder);
            // Wait for the update notification to be received.
            insertObserver.waitForChange();
            deleteObserver.waitForChange();


            // Verify that we received the correct notification for the original Uri.
            assertReceivedUris(insertObserver.getReceivedUris(), newFolder);
            assertReceivedUris(deleteObserver.getReceivedUris(), scannedFolderUri);
        } finally {
            insertObserver.unregister();
            deleteObserver.unregister();
        }
    }

    /**
     * Tests that a DELETE notification is received when an existing folder is deleted and scanned.
     */
    @Test
    public void testNotifyDeleteOnFolder() {
        final Uri filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        final File folder = new File(mDir, "test-" + System.nanoTime());
        assertTrue(folder.mkdirs());
        Uri scannedfolderUri = MediaStore.scanFile(mContentResolver, folder);
        final TestContentObserver observer = TestContentObserver.create(
                filesUri, ContentResolver.NOTIFY_DELETE, /* expectedCount */ 1);
        try {
            deleteFile(folder);
            // Wait for the update notification to be received.
            observer.waitForChange();
            // Verify that we received the correct notification for the original Uri.
            assertReceivedUris(observer.getReceivedUris(), scannedfolderUri);
        } finally {
            observer.unregister();
        }
    }

    /**
     * Asserts that at least one of the received URIs points to the given physical file path.
     *
     * @param uris The list of {@link Uri}s received by the content observer.
     * @param file The expected physical {@link File}.
     */
    private void assertReceivedUris(List<Uri> uris, File file) {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().adoptShellPermissionIdentity();
        boolean fileFound = false;
        for (Uri uri : uris) {
            try (Cursor c = mContentResolver.query(uri,
                    new String[]{MediaStore.Files.FileColumns.DATA}, null, null, null)) {
                if (c.moveToFirst()) {
                    final String path = c.getString(0);
                    if (path.equals(file.getAbsolutePath())) {
                        fileFound = true;
                        break;
                    }
                }
            }
        }
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();

        assertTrue("Notification for file was not received ", fileFound);
    }

    /**
     * Asserts that at least one of the received URIs has the same row ID as the expected URI.
     *
     * @param receivedUris The list of {@link Uri}s received by the content observer.
     * @param uri          The expected {@link Uri} containing the row ID.
     */
    private void assertReceivedUris(List<Uri> receivedUris, Uri uri) {
        boolean fileFound = false;
        long expectedId = ContentUris.parseId(uri);
        for (Uri receivedUri : receivedUris) {
            if (ContentUris.parseId(receivedUri) == expectedId) {
                fileFound = true;
                break;
            }
        }

        assertTrue("Notification for file with id " + expectedId + " was not received.", fileFound);

    }

    /**
     * Deletes a file and ensures MediaStore is notified by scanning the file path afterward.
     *
     * @param file The {@link File} to delete.
     */
    private void deleteFile(File file) {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().adoptShellPermissionIdentity();

        file.delete();
        MediaStore.scanFile(mContentResolver, file);

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    /**
     * Observer that will wait for a specific number of change events to be delivered.
     */
    public static class TestContentObserver extends ContentObserver {
        private static final String TAG = "TestContentObserver";
        private final int mFlags;
        private final CountDownLatch mLatch;
        private final List<Uri> mReceivedUris = new ArrayList<>();

        private TestContentObserver(int flags, int expectedCount) {
            super(null);
            mFlags = flags;
            mLatch = new CountDownLatch(expectedCount);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int flags) {
            Log.v(TAG, String.format("onChange(%b, %s, %d)", selfChange, uri.toString(), flags));

            if ((flags & mFlags) == mFlags) {
                synchronized (mReceivedUris) {
                    mReceivedUris.add(uri);
                }
                mLatch.countDown();
            }
        }

        /** Creates and registers a new {@link TestContentObserver}. */
        public static TestContentObserver create(Uri uri, int flags, int expectedCount) {
            final TestContentObserver obs = new TestContentObserver(flags, expectedCount);
            InstrumentationRegistry.getContext().getContentResolver()
                    .registerContentObserver(uri, true, obs);
            return obs;
        }

        /** Waits for the expected number of notifications. */
        public void waitForChange() {
            try {
                assertTrue("Did not receive all expected notifications.",
                        mLatch.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        /** Returns the list of received notification URIs. */
        public List<Uri> getReceivedUris() {
            synchronized (mReceivedUris) {
                return new ArrayList<>(mReceivedUris);
            }
        }

        /** Unregisters the content observer. */
        public void unregister() {
            InstrumentationRegistry.getContext().getContentResolver()
                    .unregisterContentObserver(this);
        }
    }

}
