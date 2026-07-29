/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.providers.media.MediaDocumentsProvider.AUTHORITY;
import static com.android.providers.media.flags.Flags.FLAG_ENABLE_MIME_TYPE_UPDATE_ON_RENAME;
import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;
import static com.android.providers.media.scan.MediaScannerTest.stage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.flags.Flags;
import com.android.providers.media.scan.MediaScanner;
import com.android.providers.media.scan.ModernMediaScanner;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.MimeUtils;

import com.google.common.base.Objects;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Arrays;

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
@RunWith(AndroidJUnit4.class)
public class MediaDocumentsProviderTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private File mDownloadsDir;

    private static class TestFile {
        int resId;
        String fileName;

        TestFile(int resId, String fileName) {
            this.resId = resId;
            this.fileName = fileName;
        }
    }

    private TestFile[] mTestFiles = {
            new TestFile(R.raw.test_audio, "audio.mp3"),
            new TestFile(R.raw.test_video, "video.mp4"),
            new TestFile(R.raw.test_image, "image.jpg"),
            new TestFile(R.raw.test_m3u, "playlist.m3u"),
            new TestFile(R.raw.test_srt, "subtitle.srt"),
            new TestFile(R.raw.test_txt, "document.txt"),
            new TestFile(R.raw.test_bin, "random.bin"),
    };

    @Before
    public void setUp() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                        Manifest.permission.INTERACT_ACROSS_USERS);
        mDownloadsDir = new File(Environment.getExternalStorageDirectory(),
                Environment.DIRECTORY_DOWNLOADS);
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_MEDIA_DOCUMENTS_PROVIDER_ALLFILES_ROOT)
    public void testFilesRootDoesNotExist() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern",
                /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        assertPathExistence(resolver, false, "root", MediaDocumentsProvider.TYPE_FILES_ROOT);
        assertPathExistence(resolver, false, "root", MediaDocumentsProvider.TYPE_FILES_ROOT,
                "search");
        assertPathExistence(resolver, false, "root", MediaDocumentsProvider.TYPE_FILES_ROOT,
                "recent");
        assertPathExistence(resolver, false, "document", MediaDocumentsProvider.TYPE_FILES_ROOT);
        assertPathExistence(resolver, false, "document", MediaDocumentsProvider.TYPE_FILES_ROOT,
                "children");
    }

    @Test
    public void testSimple() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern",
                /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Give ourselves some basic media to work with
        stageTestMedia(isolatedContext);

        assertProbe(resolver, "root");
        for (String root : new String[] {
                MediaDocumentsProvider.TYPE_AUDIO_ROOT,
                MediaDocumentsProvider.TYPE_VIDEOS_ROOT,
                MediaDocumentsProvider.TYPE_IMAGES_ROOT,
                MediaDocumentsProvider.TYPE_DOCUMENTS_ROOT,
        }) {
            assertProbe(resolver, "root", root, "search");

            assertProbe(resolver, "document", root);
            assertProbe(resolver, "document", root, "children");
        }

        for (String recent : new String[] {
                MediaDocumentsProvider.TYPE_VIDEOS_ROOT,
                MediaDocumentsProvider.TYPE_IMAGES_ROOT,
                MediaDocumentsProvider.TYPE_DOCUMENTS_ROOT,
        }) {
            assertProbe(resolver, "root", recent, "recent");
        }

        for (String dir : new String[] {
                MediaDocumentsProvider.TYPE_VIDEOS_BUCKET,
                MediaDocumentsProvider.TYPE_IMAGES_BUCKET,
                MediaDocumentsProvider.TYPE_DOCUMENTS_BUCKET,
        }) {
            assertProbe(resolver, "document", dir, "children");
        }

        for (String item : new String[] {
                MediaDocumentsProvider.TYPE_ARTIST,
                MediaDocumentsProvider.TYPE_ALBUM,
                MediaDocumentsProvider.TYPE_VIDEOS_BUCKET,
                MediaDocumentsProvider.TYPE_IMAGES_BUCKET,
                MediaDocumentsProvider.TYPE_DOCUMENTS_BUCKET,

                MediaDocumentsProvider.TYPE_AUDIO,
                MediaDocumentsProvider.TYPE_VIDEO,
                MediaDocumentsProvider.TYPE_IMAGE,
                MediaDocumentsProvider.TYPE_DOCUMENT,
        }) {
            assertProbe(resolver, "document", item);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_DOCUMENTS_PROVIDER_ALLFILES_ROOT)
    public void testSimpleFilesRoot() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern",
                /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Give ourselves some basic media to work with
        stageTestMedia(isolatedContext);

        // Files root should support search.
        assertProbe(resolver, "root", MediaDocumentsProvider.TYPE_FILES_ROOT, "search");

        // Files root should support recent documents.
        assertProbe(resolver, "root", MediaDocumentsProvider.TYPE_FILES_ROOT, "recent");

        // Files root should support individual documents.
        assertProbe(resolver, "document", MediaDocumentsProvider.TYPE_FILE);

        // Files root should *not* support querying for its children.
        assertPathExistence(resolver, false, "document", MediaDocumentsProvider.TYPE_FILES_ROOT,
                "children");
    }

    @Test
    public void testOpenFile() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern",
                /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Give ourselves some basic media to work with
        stageTestMedia(isolatedContext);

        for (String item : new String[] {
                MediaDocumentsProvider.TYPE_ARTIST,
                MediaDocumentsProvider.TYPE_ALBUM,
                MediaDocumentsProvider.TYPE_VIDEOS_BUCKET,
                MediaDocumentsProvider.TYPE_IMAGES_BUCKET,
                MediaDocumentsProvider.TYPE_DOCUMENTS_BUCKET,
                MediaDocumentsProvider.TYPE_AUDIO,
                MediaDocumentsProvider.TYPE_VIDEO,
                MediaDocumentsProvider.TYPE_IMAGE,
                MediaDocumentsProvider.TYPE_DOCUMENT,
        }) {
            assertOpenFile(resolver, item);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_DOCUMENTS_PROVIDER_ALLFILES_ROOT)
    public void testOpenFileFromFilesRoot() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern",
                /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Give ourselves some basic media to work with
        stageTestMedia(isolatedContext);

        assertOpenFile(resolver, MediaDocumentsProvider.TYPE_FILE);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_DOCUMENTS_PROVIDER_ALLFILES_ROOT)
    public void testRenameInFilesRoot() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern",
                /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Give ourselves some basic media to work with
        final File stageDir = stageTestMedia(isolatedContext);

        final Uri recentsUri = DocumentsContract.buildRecentDocumentsUri(AUTHORITY,
                MediaDocumentsProvider.TYPE_FILES_ROOT);
        try (Cursor c = resolver.query(recentsUri, null, null, null)) {
            // Rename the first file.
            assertTrue(c.moveToNext());

            final String docId = c.getString(c.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
            final String displayName = c.getString(
                    c.getColumnIndex(Document.COLUMN_DISPLAY_NAME));
            final String newName = "test_" + displayName;

            final File currentFile = new File(stageDir, displayName);
            assertTrue(currentFile.exists());
            final File renamedFile = new File(stageDir, newName);
            assertFalse(renamedFile.exists());

            final Uri fileUri = DocumentsContract.buildDocumentUri(AUTHORITY, docId);
            assertNotNull(DocumentsContract.renameDocument(resolver, fileUri, newName));

            assertFalse(currentFile.exists());
            assertTrue(renamedFile.exists());
        }
    }

    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_ENABLE_MEDIA_DOCUMENTS_PROVIDER_ALLFILES_ROOT,
        FLAG_ENABLE_MIME_TYPE_UPDATE_ON_RENAME
    })
    public void testRenameInFilesRootWithMimeTypeUpdateEnabled() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext =
                new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Give ourselves some basic media to work with
        final File stageDir = stageTestMedia(isolatedContext);

        final Uri recentsUri =
                DocumentsContract.buildRecentDocumentsUri(
                        AUTHORITY, MediaDocumentsProvider.TYPE_FILES_ROOT);
        try (Cursor c = resolver.query(recentsUri, null, null, null)) {
            // Find and rename document.txt replacing its mime type.
            String docId = null;
            String displayName = null;

            while (c.moveToNext()) {
                displayName = c.getString(c.getColumnIndex(Document.COLUMN_DISPLAY_NAME));
                if (displayName.equals("document.txt")) {
                    docId = c.getString(c.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
                    break;
                }
            }
            assertNotNull("Could not find document.txt to rename", docId);

            final String newName = "document.ipynb";
            final File currentFile = new File(stageDir, displayName);
            assertTrue(currentFile.exists());
            final File renamedFile = new File(stageDir, newName);
            assertFalse(renamedFile.exists());

            final Uri fileUri = DocumentsContract.buildDocumentUri(AUTHORITY, docId);
            assertNotNull(DocumentsContract.renameDocument(resolver, fileUri, newName));
            assertFalse("Original file should no longer exist", currentFile.exists());
            assertTrue("Renamed file should exist", renamedFile.exists());
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_DOCUMENTS_PROVIDER_ALLFILES_ROOT)
    @RequiresFlagsDisabled(FLAG_ENABLE_MIME_TYPE_UPDATE_ON_RENAME)
    public void testRenameInFilesRootWithMimeTypeUpdateDisabled() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext =
                new IsolatedContext(context, "modern", /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Give ourselves some basic media to work with
        final File stageDir = stageTestMedia(isolatedContext);

        final Uri recentsUri =
                DocumentsContract.buildRecentDocumentsUri(
                        AUTHORITY, MediaDocumentsProvider.TYPE_FILES_ROOT);
        try (Cursor c = resolver.query(recentsUri, null, null, null)) {
            // Find and rename document.txt replacing its mime type.
            String docId = null;
            String displayName = null;

            while (c.moveToNext()) {
                displayName = c.getString(c.getColumnIndex(Document.COLUMN_DISPLAY_NAME));
                if (displayName.equals("document.txt")) {
                    docId = c.getString(c.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
                    break;
                }
            }
            assertNotNull("Could not find document.txt to rename", docId);

            final String newName = "document.ipynb";
            final File currentFile = new File(stageDir, displayName);
            assertTrue(currentFile.exists());
            final File renamedFile = new File(stageDir, newName);
            assertFalse(renamedFile.exists());

            // with the flag disabled the renamed file will be forced to keep existing extension
            final File forcedFile = new File(stageDir, newName + ".txt");
            assertFalse(renamedFile.exists());

            final Uri fileUri = DocumentsContract.buildDocumentUri(AUTHORITY, docId);
            assertNotNull(DocumentsContract.renameDocument(resolver, fileUri, newName));
            assertFalse("Original file should no longer exist", currentFile.exists());
            assertFalse("Renamed file should not exist", renamedFile.exists());
            assertTrue("Forced file should exist", forcedFile.exists());
        }
    }

    /**
     * Walk the recent items published by the Files root and confirm it returns all file types,
     * and that they publish the appropriate flags.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_DOCUMENTS_PROVIDER_ALLFILES_ROOT)
    public void testTraverseFilesRoot() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern",
                /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Give ourselves some basic media to work with
        stageTestMedia(isolatedContext);

        final Uri recents = DocumentsContract.buildRecentDocumentsUri(AUTHORITY,
                MediaDocumentsProvider.TYPE_FILES_ROOT);
        try (Cursor c = resolver.query(recents, null, null, null)) {
            assertEquals(c.getCount(), mTestFiles.length);
            while (c.moveToNext()) {
                final String docId = c.getString(c.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
                final String displayName = c.getString(
                        c.getColumnIndex(Document.COLUMN_DISPLAY_NAME));

                final int flags = c.getInt(c.getColumnIndex(Document.COLUMN_FLAGS));
                final int minimumFlags =
                        Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_WRITE
                                | Document.FLAG_SUPPORTS_DELETE;
                assertEquals(displayName + " does not publish correct flags", minimumFlags,
                        flags & minimumFlags);

                final String mimeType = c.getString(c.getColumnIndex(Document.COLUMN_MIME_TYPE));
                boolean fileShouldSupportThumbnail = mimeType.startsWith("image/")
                        || mimeType.startsWith("video/");
                if (fileShouldSupportThumbnail) {
                    assertEquals(displayName + " should support thumbnails but doesn't",
                            Document.FLAG_SUPPORTS_THUMBNAIL,
                            (flags & Document.FLAG_SUPPORTS_THUMBNAIL));
                } else {
                    assertEquals(displayName + " should not support thumbnails but does", 0,
                            (flags & Document.FLAG_SUPPORTS_THUMBNAIL));
                }

                final Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, docId);
                resolver.openInputStream(uri);
            }
        }
    }

    /**
     * Recursively walk every item published by provider and confirm we can
     * query it, open it, and obtain metadata for it.
     */
    @Test
    public void testTraverse() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final Context isolatedContext = new IsolatedContext(context, "modern",
                /*asFuseThread*/ false);
        final ContentResolver resolver = isolatedContext.getContentResolver();

        // Give ourselves some basic media to work with
        stageTestMedia(isolatedContext);

        final Uri roots = DocumentsContract.buildRootsUri(AUTHORITY);
        try (Cursor c = resolver.query(roots, null, null, null)) {
            while (c.moveToNext()) {
                final String docId = c.getString(c.getColumnIndex(Root.COLUMN_DOCUMENT_ID));

                // The "Files" Root doesn't support querying for children.
                if (MediaDocumentsProvider.TYPE_FILES_ROOT.equals(docId)) {
                    continue;
                }

                final Uri children = DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId);
                doTraversal(resolver, children);
            }
        }
    }

    /**
     * Recursively walk all children documents at the given location.
     */
    public void doTraversal(ContentResolver resolver, Uri child) throws Exception {
        try (Cursor c = resolver.query(child, null, null, null)) {
            while (c.moveToNext()) {
                final String docId = c.getString(c.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
                final String mimeType = c.getString(c.getColumnIndex(Document.COLUMN_MIME_TYPE));

                final Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, docId);
                final Uri grandchild = DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId);

                if (Objects.equal(Document.MIME_TYPE_DIR, mimeType)) {
                    doTraversal(resolver, grandchild);
                } else {
                    // Verify we can open
                    try (InputStream in = resolver.openInputStream(uri)) {
                    }

                    // Verify we can fetch metadata for common types
                    final int mediaType = MimeUtils.resolveMediaType(mimeType);
                    switch (mediaType) {
                        case FileColumns.MEDIA_TYPE_AUDIO:
                        case FileColumns.MEDIA_TYPE_VIDEO:
                        case FileColumns.MEDIA_TYPE_IMAGE:
                            assertNotNull(DocumentsContract.getDocumentMetadata(resolver, uri));
                    }
                }
            }
        }
    }

    @Test
    public void testBuildSearchSelection() {
        final String displayName = "foo";
        final String[] mimeTypes = new String[]{"text/csv", "video/*", "image/png", "audio/*"};
        final long lastModifiedAfter = 1000 * 1000;
        final long fileSizeOver = 1000 * 1000;
        final String columnDisplayName = "display";
        final String columnMimeType = "mimeType";
        final String columnLastModified = "lastModified";
        final String columnFileSize = "fileSize";
        final String resultSelection =
                "display LIKE ? AND lastModified > 1000 AND fileSize > 1000000 AND (mimeType LIKE"
                        + " ? OR mimeType LIKE ? OR mimeType IN (?,?))";

        final Pair<String, String[]> selectionPair = MediaDocumentsProvider.buildSearchSelection(
                displayName, mimeTypes, lastModifiedAfter, fileSizeOver, columnDisplayName,
                columnMimeType, columnLastModified, columnFileSize);

        assertEquals(resultSelection, selectionPair.first);
        assertEquals(5, selectionPair.second.length);
        assertEquals("%" + displayName + "%", selectionPair.second[0]);
        assertMimeType(mimeTypes[1], selectionPair.second[1]);
        assertMimeType(mimeTypes[3], selectionPair.second[2]);
        assertMimeType(mimeTypes[0], selectionPair.second[3]);
        assertMimeType(mimeTypes[2], selectionPair.second[4]);
    }

    @Test
    public void testAddDocumentSelection() {
        final String selection = "";
        final String[] selectionArgs = new String[]{};
        final String resultSelection = "media_type=?";

        final Pair<String, String[]> selectionPair = MediaDocumentsProvider.addDocumentSelection(
                selection, selectionArgs);

        assertEquals(resultSelection, selectionPair.first);
        assertEquals(1, selectionPair.second.length);
        assertEquals(MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT,
                Integer.parseInt(selectionPair.second[0]));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_TRASH_AND_RESTORE_BY_FILE_PATH_API)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    public void testTrashDocument() throws Exception {
        MediaDocumentsProvider provider = new MediaDocumentsProvider();
        final IsolatedContext isolatedContext = new IsolatedContext(
                InstrumentationRegistry.getTargetContext(), "modern",
                /*asFuseThread*/ false);
        final ProviderInfo info = isolatedContext.getPackageManager()
                .resolveContentProvider(MediaDocumentsProvider.AUTHORITY,
                        PackageManager.GET_META_DATA);
        provider.attachInfo(isolatedContext, info);

        final ContentResolver resolver = isolatedContext.getContentResolver();

        final File dir = new File(mDownloadsDir, "test_" + System.nanoTime());
        dir.mkdirs();
        FileUtils.deleteContents(dir);
        File firstImageFile = new File(dir, "image-1-" + System.nanoTime() + ".jpg");
        File secondImageFile = new File(dir, "image-2-" + System.nanoTime() + ".jpg");
        stage(R.raw.test_image, firstImageFile);
        stage(R.raw.test_image, secondImageFile);

        final MediaScanner scanner = new ModernMediaScanner(isolatedContext, new TestConfigStore());
        scanner.scanDirectory(dir, REASON_UNKNOWN);

        Uri uri = DocumentsContract.buildChildDocumentsUri(AUTHORITY,
                MediaDocumentsProvider.TYPE_IMAGES_ROOT);

        String dirDocId;
        try (Cursor dirCursor = traverseAndFindItem(resolver, uri, dir.getName())) {
            // dir should exists
            assertNotNull(dirCursor);

            dirDocId = dirCursor.getString(dirCursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
        }

        final Uri childrenDocumentUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY,
                dirDocId);

        String imageDocId;
        try (Cursor imageCursor = traverseAndFindItem(resolver, childrenDocumentUri,
                firstImageFile.getName())) {
            // image should exist
            assertNotNull(imageCursor);

            imageDocId = imageCursor.getString(
                    imageCursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
        }

        try {
            isolatedContext.setByPassTargetSdkCheckForTrash(true);
            isolatedContext.setByPassManageExternalStorageCheckForTrash(true);
            // call trashed document
            provider.trashDocument(imageDocId);
        } finally {
            isolatedContext.setByPassTargetSdkCheckForTrash(false);
            isolatedContext.setByPassManageExternalStorageCheckForTrash(false);
        }

        try (Cursor trashedFirstImageCursor = traverseAndFindItem(resolver, childrenDocumentUri,
                firstImageFile.getName())) {
            // after trash this image file shouldn't exist
            assertNull(trashedFirstImageCursor);
        }
    }

    /**
     * Recursively walks all children documents at the given location to find a target document by
     * its display name.
     * This method traverses through directories and their sub-documents until the target is found
     * or all reachable documents have been checked.
     *
     * @param resolver          The {@link ContentResolver} used to query document providers.
     * @param child             The {@link Uri} of the current document or directory whose children
     *                          are to be traversed.
     * @param targetDisplayName The display name of the document to find.
     * @return A {@link Cursor} positioned at the row of the found document if it exists, or
     * {@code null} if the document is not found within the traversed path.
     * @throws Exception if an error occurs during the document query or traversal.
     */
    private Cursor traverseAndFindItem(ContentResolver resolver, Uri child,
            String targetDisplayName) throws Exception {
        try (Cursor c = resolver.query(child, null, null, null)) {
            while (c != null && c.moveToNext()) {
                final String docId = c.getString(c.getColumnIndex(Document.COLUMN_DOCUMENT_ID));
                final String mimeType = c.getString(c.getColumnIndex(Document.COLUMN_MIME_TYPE));
                final String displayName = c.getString(
                        c.getColumnIndex(Document.COLUMN_DISPLAY_NAME));

                if (displayName.equals(targetDisplayName)) {
                    return c;
                }

                final Uri grandchild = DocumentsContract.buildChildDocumentsUri(AUTHORITY, docId);

                if (Objects.equal(Document.MIME_TYPE_DIR, mimeType)) {
                    doTraversal(resolver, grandchild);
                }
            }
        }
        return null;
    }

    private static void assertProbe(ContentResolver resolver, String... paths) {
        assertPathExistence(resolver, true, paths);
    }

    private static void assertPathExistence(ContentResolver resolver, boolean pathShouldExist,
            String... paths) {
        final Uri.Builder probe = Uri.parse("content://" + MediaDocumentsProvider.AUTHORITY)
                .buildUpon();
        for (String path : paths) {
            probe.appendPath(path);
        }
        try (Cursor c = resolver.query(probe.build(), null, Bundle.EMPTY, null)) {
            if (pathShouldExist) {
                assertNotNull(Arrays.toString(paths), c);
            } else {
                assertNull(Arrays.toString(paths), c);
            }
        } catch (UnsupportedOperationException e) {
            assertFalse(pathShouldExist);
        }
    }

    private static void assertMimeType(String expected, String actual) {
        if (expected.endsWith("/*")) {
            assertEquals(expected.substring(0, expected.length() - 1) + "%", actual);
        } else {
            assertEquals(expected, actual);
        }
    }

    private File stageTestMedia(Context context) throws Exception {
        final File dir = new File(context.getExternalMediaDirs()[0], "test_" + System.nanoTime());
        dir.mkdirs();
        FileUtils.deleteContents(dir);

        for (final TestFile testFile : mTestFiles) {
            stage(testFile.resId, new File(dir, testFile.fileName));
        }

        final MediaScanner scanner = new ModernMediaScanner(context, new TestConfigStore());
        scanner.scanDirectory(dir, REASON_UNKNOWN);

        return dir;
    }

    private void assertOpenFile(ContentResolver resolver, String item)
            throws FileNotFoundException {
        final Uri.Builder probe = Uri.parse("content://" + MediaDocumentsProvider.AUTHORITY)
                .buildUpon().appendPath(MediaDocumentsProvider.TYPE_DOCUMENT).appendPath(item);
        try (Cursor c = resolver.query(probe.build(), null, Bundle.EMPTY, null)) {
            while (c.moveToNext()) {
                final Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY,
                        getDocIdForIdent(item, c.getLong(0)));
                assertNotNull(resolver.openFile(uri, "r", null));
                assertNotNull(resolver.openFile(uri, "rw", null));
            }
        }
    }

    private static String getDocIdForIdent(String type, long id) {
        return type + ":" + id;
    }
}
