/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static com.android.providers.media.localsearch.MediaProcessingStatus.MEDIA_PROCESSING_STATUS_TABLE;
import static com.android.providers.media.localsearch.MediaProcessingStatus.STATUS_COMPLETED;
import static com.android.providers.media.localsearch.ProcessingHelper.LAST_GEN_MODIFIED_WITH_LOCATION_LABEL;
import static com.android.providers.media.localsearch.ProcessingHelper.LAST_GEN_MODIFIED_WITH_METADATA_LABEL;
import static com.android.providers.media.localsearch.ProcessingUtils.isDefaultSearchMediaServiceSupported;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.location.Geocoder;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;

import androidx.appsearch.app.GenericDocument;
import androidx.appsearch.app.SearchSpec;
import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.IsolatedContext;
import com.android.providers.media.appsearch.AppSearchDbManager;
import com.android.providers.media.appsearch.MediaItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
public class ProcessingHelperTest {

    // Mountain View, CA coordinates
    private static final double TEST_LAT = 37.422;
    private static final double TEST_LONG = -122.084;
    private static final int METADATA_INSERTION_BATCH_SIZE = 50;

    private IsolatedContext mIsolatedContext;
    private DatabaseHelper mDatabaseHelper;
    private ProcessingHelper mProcessingHelper;
    private AppSearchDbManager mAppSearchDbManager;

    // Executor that runs immediately on the current thread
    private final Executor mDirectExecutor = Runnable::run;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        mIsolatedContext = new IsolatedContext(context, "test", /*asFuseThread*/ false);

        // Media processing is only required if we are using default search service.
        // Devices on which default search service is disabled or some other service
        // is used should not run these tests
        assumeTrue(isDefaultSearchMediaServiceSupported(mIsolatedContext));

        mDatabaseHelper = mIsolatedContext.getExternalDatabase();

        try {
            mProcessingHelper = new ProcessingHelper(mIsolatedContext, mDatabaseHelper,
                    mDirectExecutor);
            mAppSearchDbManager = mProcessingHelper.mAppSearchDbManager;
            assumeNotNull(mAppSearchDbManager);
        } catch (UnsupportedOperationException e) {
            // Required appSearch features are not supported.
            assumeNoException("AppSearch features are not supported on this device", e);
        }

        mProcessingHelper.mPrefs.edit().clear().apply();

        // Clean up any previous test data in AppSearch
        deleteAllAppSearchDocuments();
    }

    @After
    public void tearDown() throws Exception {
        if (!isDefaultSearchMediaServiceSupported(mIsolatedContext)) {
            return;
        }

        if (mAppSearchDbManager != null) {
            deleteAllAppSearchDocuments();
            mAppSearchDbManager.disconnect();
        }

        try {
            mDatabaseHelper.runWithTransaction((db) -> {
                db.delete(MEDIA_PROCESSING_STATUS_TABLE, null, null);
                db.delete(MediaStore.Files.TABLE, null, null);
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to clean up database in tearDown", e);
        }
    }

    @Test
    public void testProcessMetadataLabels() throws Exception {
        // Insert a file into SQLite
        long genModified = 100L;
        String fileName = "party_photo.jpg";
        long fileId = insertFile(FileColumns.MEDIA_TYPE_IMAGE, fileName,
                "/storage/emulated/0/DCIM/" + fileName, genModified, null, null);
        mProcessingHelper.runProcessMetadataLabels();

        // Verify SharedPreferences updated
        long lastGen = mProcessingHelper.mPrefs.getLong(LAST_GEN_MODIFIED_WITH_METADATA_LABEL, -1);
        assertEquals(genModified, lastGen);

        // Query AppSearch to verify the document exists and has labels
        List<GenericDocument> results = mAppSearchDbManager.getDocumentsByFileIds(List.of(fileId));
        assertThat(results).hasSize(1);

        String extractedLabel = results.get(0).getPropertyString(
                MediaItem.PROPERTY_METADATA_EXTRACTED);
        assertThat(extractedLabel).contains("party_photo");
        assertThat(extractedLabel).contains("jpg");
    }

    @Test
    public void testProcessLocationLabels() throws Exception {
        // This test requires a working Geocoder.
        assumeTrue(Geocoder.isPresent());


        // 1. Insert file with valid Lat/Long (Mountain View)
        long genModified = 200L;
        long fileId = insertFile(FileColumns.MEDIA_TYPE_IMAGE, "geo_pic.jpg", "/DCIM/geo_pic.jpg",
                genModified, TEST_LAT, TEST_LONG);

        // 2. Pre-populate MediaProcessingStatus table (Metadata processing does this)
        // This is necessary because processLocationLabels() queries this table to find candidates.
        mDatabaseHelper.runWithTransaction((db) -> {
            ContentValues statusRow = new ContentValues();
            statusRow.put(MediaProcessingStatus.FILE_ID_COLUMN, fileId);
            statusRow.put(MediaProcessingStatus.MEDIA_TYPE, FileColumns.MEDIA_TYPE_IMAGE);
            statusRow.put(MediaProcessingStatus.GEN_MODIFIED, genModified);
            statusRow.put(MediaProcessingStatus.METADATA_LABEL_STATUS, 1); // 1 = Completed
            statusRow.put(MediaProcessingStatus.LOCATION_LABEL_STATUS, 0); // 0 = Pending
            return db.insert(MEDIA_PROCESSING_STATUS_TABLE, null, statusRow);
        });

        assumeNotNull(mProcessingHelper.mLocationResolver);
        mProcessingHelper.runProcessLocationLabels();

        // Verify SharedPreferences updated
        long lastGen = mProcessingHelper.mPrefs.getLong(LAST_GEN_MODIFIED_WITH_LOCATION_LABEL, -1);
        assertEquals(genModified, lastGen);

        // Verify AppSearch has location data
        List<GenericDocument> results = mAppSearchDbManager.getDocumentsByFileIds(List.of(fileId));

        // If the geocoder failed (network/setup), the doc might not exist or have null location.
        mDatabaseHelper.runWithoutTransaction((db) -> {
            if (!results.isEmpty()) {
                GenericDocument doc = results.get(0);
                String location = doc.getPropertyString(MediaItem.PROPERTY_LOCATION_EXTRACTED);

                // If Geocoder worked, we expect "United States" or "Mountain View"
                // If it failed/timed out silently, this might be null.
                if (location != null) {
                    assertThat(location).contains("United States");
                }

                // Verify Status Table is marked as processed (status > 0)
                try (Cursor c = db.query("media_processing_status",
                        new String[]{"location_label_status"}, "file_id=?",
                        new String[]{String.valueOf(fileId)}, null, null, null)) {
                    assertThat(c.moveToFirst()).isTrue();
                    assertThat(c.getInt(0)).isNotEqualTo(0); // Should not be pending
                }
            }
            return null;
        });
    }

    @Test
    public void testRetryLocationLabels() throws Exception {
        assumeTrue(Geocoder.isPresent());

        // 1. Insert a file with valid Lat/Long
        long fileGenModified = 100L;
        long fileId = insertFile(FileColumns.MEDIA_TYPE_IMAGE, "retry_geo_pic.jpg",
                "/DCIM/retry_geo_pic.jpg", fileGenModified, TEST_LAT, TEST_LONG);

        // 2. Set up a PREVIOUS FAILURE for this file in the status table.
        mDatabaseHelper.runWithTransaction((db) -> {
            ContentValues statusRow = new ContentValues();
            statusRow.put(MediaProcessingStatus.FILE_ID_COLUMN, fileId);
            statusRow.put(MediaProcessingStatus.MEDIA_TYPE, FileColumns.MEDIA_TYPE_IMAGE);
            statusRow.put(MediaProcessingStatus.GEN_MODIFIED, fileGenModified);
            statusRow.put(MediaProcessingStatus.METADATA_LABEL_STATUS, STATUS_COMPLETED);
            // Status is 1 (Failed once), NOT 0 (Pending) and NOT 999 (Completed)
            statusRow.put(MediaProcessingStatus.LOCATION_LABEL_STATUS, 1);
            return db.insert(MEDIA_PROCESSING_STATUS_TABLE, null, statusRow);
        });

        // 3. Set the last processed generation to be HIGHER than the file.
        // This simulates that the main job has moved past this file, making it eligible for retry.
        long lastProcessedGenModifiedForLocation = fileGenModified + 10;
        mProcessingHelper.mPrefs.edit()
                .putLong(LAST_GEN_MODIFIED_WITH_LOCATION_LABEL, lastProcessedGenModifiedForLocation)
                .apply();

        // 4. Trigger the retry logic
        assumeNotNull(mProcessingHelper.mLocationResolver);
        mProcessingHelper.runRetryLocationLabels();

        // 5. Verify the SQLite Status Table was updated.
        mDatabaseHelper.runWithoutTransaction((db) -> {
            try (Cursor c = db.query(MEDIA_PROCESSING_STATUS_TABLE,
                    new String[]{MediaProcessingStatus.LOCATION_LABEL_STATUS},
                    MediaProcessingStatus.FILE_ID_COLUMN + "=?",
                    new String[]{String.valueOf(fileId)}, /*groupBy*/ null,
                    /*having*/ null, /*orderBy*/ null)) {

                assertThat(c.moveToFirst()).isTrue();
                int status = c.getInt(0);

                // Check is that the processing status is NOT 1 anymore (it was touched).
                assertThat(status).isNotEqualTo(1);
            }
            return null;
        });
    }

    @Test
    public void testRetryLocationLabels_skipsFilesNewerThanLastProcessed() throws Exception {
        // 1. File is NEWER (Gen = 300) than the lastProcessedGenModified (Gen = 200)
        long fileGen = 300L;
        long lastProcessedGenModified = 200L;

        long fileId = insertFile(FileColumns.MEDIA_TYPE_IMAGE, "new_pic.jpg",
                "/DCIM/new_pic.jpg", fileGen, TEST_LAT, TEST_LONG);

        // Insert new unprocessed file
        mDatabaseHelper.runWithTransaction((db) -> {
            ContentValues statusRow = new ContentValues();
            statusRow.put(MediaProcessingStatus.FILE_ID_COLUMN, fileId);
            statusRow.put(MediaProcessingStatus.MEDIA_TYPE, FileColumns.MEDIA_TYPE_IMAGE);
            statusRow.put(MediaProcessingStatus.GEN_MODIFIED, fileGen);
            statusRow.put(MediaProcessingStatus.METADATA_LABEL_STATUS, STATUS_COMPLETED);
            statusRow.put(MediaProcessingStatus.LOCATION_LABEL_STATUS, 0); // Unprocessed
            return db.insert(MEDIA_PROCESSING_STATUS_TABLE, null, statusRow);
        });

        // Set lastProcessedGenModified lower than file gen
        mProcessingHelper.mPrefs.edit()
                .putLong(LAST_GEN_MODIFIED_WITH_LOCATION_LABEL, lastProcessedGenModified)
                .apply();

        mProcessingHelper.runRetryLocationLabels();

        // 3. Status should STILL be 0 (Untouched)
        mDatabaseHelper.runWithoutTransaction((db) -> {
            try (Cursor c = db.query(MEDIA_PROCESSING_STATUS_TABLE,
                    new String[]{MediaProcessingStatus.LOCATION_LABEL_STATUS},
                    MediaProcessingStatus.FILE_ID_COLUMN + "=?",
                    new String[]{String.valueOf(fileId)}, null, null, null)) {
                assertThat(c.moveToFirst()).isTrue();
                assertThat(c.getInt(0)).isEqualTo(0); // Unchanged
            }
            return null;
        });
    }

    @Test
    public void testDeleteStaleRowsFromAppSearch() throws Exception {
        // 1. Insert two files into SQLite and AppSearch
        long genModified = 100L;

        long fileIdToKeep = insertFile(FileColumns.MEDIA_TYPE_IMAGE, "keep.jpg",
                "/storage/emulated/0/DCIM/keep.jpg", genModified, null, null);
        long fileIdToDelete = insertFile(FileColumns.MEDIA_TYPE_IMAGE, "delete.jpg",
                "/storage/emulated/0/DCIM/delete.jpg", genModified, null, null);

        // Put these files into AppSearch
        mProcessingHelper.processMetadataLabels();

        // Verify both exist in AppSearch initially
        List<GenericDocument> initialDocs = mAppSearchDbManager
                .getDocumentsByFileIds(List.of(fileIdToKeep, fileIdToDelete));
        assertThat(initialDocs).hasSize(2);

        // 2. Remove one file from the SQLite files table
        mDatabaseHelper.runWithTransaction((db) -> {
            return db.delete(MediaStore.Files.TABLE, FileColumns._ID + "=?",
                    new String[]{String.valueOf(fileIdToDelete)});
        });

        // 3. Run the cleanup method
        mProcessingHelper.deleteStaleRowsFromAppSearch();

        // 4. Verify results
        List<GenericDocument> finalDocs = mAppSearchDbManager
                .getDocumentsByFileIds(List.of(fileIdToKeep, fileIdToDelete));

        // We expect only the 'keep' file to remain
        assertThat(finalDocs).hasSize(1);
        assertThat(finalDocs.get(0).getId()).isEqualTo(String.valueOf(fileIdToKeep));
    }

    @Test
    public void testEnforceDocumentLimit_deletesOldestDocuments() throws Exception {
        int totalDocs = 1500;
        int limit = 500;
        int expectedDocsToDelete = totalDocs - limit;

        List<Long> expectedIds = new ArrayList<>();
        for (int i = 1; i <= totalDocs; i++) {
            long genModified = i;
            long fileId = insertFile(FileColumns.MEDIA_TYPE_IMAGE, "img" + i + ".jpg",
                    "/storage/emulated/0/DCIM/img" + i + ".jpg", genModified,
                    /* lat */ null, /* lon */ null);
            if (i > expectedDocsToDelete) {
                expectedIds.add(fileId);
            }

            if (i % METADATA_INSERTION_BATCH_SIZE == 0) {
                mProcessingHelper.processMetadataLabels();
            }
        }

        // Verify initial state
        List<Long> allIdsBefore = new ArrayList<>(mAppSearchDbManager.getAllFileIds());
        assertThat(allIdsBefore).hasSize(totalDocs);

        mProcessingHelper.enforceAppSearchDocumentLimit(limit);

        List<Long> allRemainingIdsInAppsearchDb = new ArrayList<>(
                mAppSearchDbManager.getAllFileIds());

        int remainingCount = allRemainingIdsInAppsearchDb.size();

        assertEquals(limit, remainingCount);
        assertThat(allRemainingIdsInAppsearchDb).containsExactlyElementsIn(expectedIds);

        List<Long> allRemainingIdsInProcessingTable = new ArrayList<>();
        mDatabaseHelper.runWithoutTransaction((db) -> {
            try (Cursor c = db.query(MEDIA_PROCESSING_STATUS_TABLE,
                    new String[]{MediaProcessingStatus.FILE_ID_COLUMN},
                    /* selection */ null, /* selectionArg */ null,
                    /* groupBy */ null, /* having */ null, /* orderBy */ null)) {
                while (c.moveToNext()) {
                    allRemainingIdsInProcessingTable.add(c.getLong(0));
                }
            }
            return null;
        });

        assertEquals(limit, remainingCount);
        assertThat(allRemainingIdsInProcessingTable).containsExactlyElementsIn(expectedIds);
    }


    /**
     * Helper to insert a file into the real (isolated) SQLute DV
     */
    private long insertFile(int mediaType, String displayName, String data, long genModified,
            Double lat, Double lon) {
        return mDatabaseHelper.runWithTransaction((db) -> {
            ContentValues values = new ContentValues();
            values.put(FileColumns.MEDIA_TYPE, mediaType);
            values.put(FileColumns.DISPLAY_NAME, displayName);
            values.put(FileColumns.DATA, data);
            values.put(FileColumns.RELATIVE_PATH, "DCIM/");
            values.put(FileColumns.GENERATION_MODIFIED, genModified);
            values.put(FileColumns.DATE_TAKEN, System.currentTimeMillis());
            values.put(FileColumns.MIME_TYPE, "image/jpeg");
            values.put(FileColumns.VOLUME_NAME, "external_primary");
            values.put(FileColumns.IS_PENDING, 0);
            values.put(FileColumns.IS_TRASHED, 0);

            if (lat != null && lon != null) {
                values.put(MediaStore.Images.ImageColumns.LATITUDE, lat);
                values.put(MediaStore.Images.ImageColumns.LONGITUDE, lon);
            }

            return db.insert(MediaStore.Files.TABLE, null, values);
        });
    }

    private void deleteAllAppSearchDocuments() throws Exception {
        SearchSpec searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(AppSearchDbManager.NAMESPACE).build();

        mAppSearchDbManager.deleteDocuments(/* query */ "", searchSpec);
    }
}
