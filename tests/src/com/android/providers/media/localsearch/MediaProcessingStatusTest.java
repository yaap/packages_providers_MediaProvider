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

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;

import static com.android.providers.media.localsearch.MediaProcessingStatus.STATUS_COMPLETED;
import static com.android.providers.media.localsearch.MediaProcessingStatus.deleteMediaIdFromStatusTable;
import static com.android.providers.media.localsearch.MediaProcessingStatus.insertMetadataProcessedRowInStatusTable;
import static com.android.providers.media.localsearch.MediaProcessingStatus.updateLocationLabelStatus;

import static com.google.common.truth.Truth.assertThat;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.media.internal.flags.Flags;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.IsolatedContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MediaProcessingStatusTest {
    private static final String TAG = "MediaProcessingStatusTest";
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private IsolatedContext mIsolatedContext;
    private DatabaseHelper mHelper;

    @Before
    public void setUp() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.LOG_COMPAT_CHANGE,
                Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                Manifest.permission.READ_DEVICE_CONFIG, Manifest.permission.INTERACT_ACROSS_USERS);

        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mIsolatedContext = new IsolatedContext(context, "test", /*asFuseThread*/ false);
        mHelper = mIsolatedContext.getExternalDatabase();
    }

    @After
    public void tearDown() {
        //Clean up MediaProcessingStatus table
        try {
            mHelper.runWithTransaction(
                    (db) -> db.delete(MediaProcessingStatus.MEDIA_PROCESSING_STATUS_TABLE, null,
                            null));
        } catch (Exception e) {
            throw new RuntimeException("Failed to clean up database in tearDown", e);
        } finally {
            mHelper.close();
        }
    }

    private void assertLabelProcessingStatus(Cursor c, int mediaLabelStatus,
            int locationLabelStatus, int metadataLabelStatus) {
        assertThat(c.getInt(
                c.getColumnIndexOrThrow(MediaProcessingStatus.METADATA_LABEL_STATUS))).isEqualTo(
                metadataLabelStatus);
        assertThat(c.getInt(
                c.getColumnIndexOrThrow(MediaProcessingStatus.LOCATION_LABEL_STATUS))).isEqualTo(
                locationLabelStatus);
        assertThat(c.getInt(
                c.getColumnIndexOrThrow(MediaProcessingStatus.MEDIA_LABEL_STATUS))).isEqualTo(
                mediaLabelStatus);
    }

    @Test
    public void testInsertMetadataProcessedRowInStatusTable() {
        mHelper.runWithTransaction((db) -> {
            insertMetadataProcessedRowInStatusTable(db, /* fileId */ 1L,
                    /* mediaType */ MEDIA_TYPE_IMAGE, /* generationModified */ 100L);

            try (Cursor c = queryForMediaId(db, 1L)) {
                assertThat(c.getCount()).isEqualTo(1);
                c.moveToFirst();
                assertThat(c.getLong(c.getColumnIndexOrThrow(MediaProcessingStatus.FILE_ID_COLUMN)))
                        .isEqualTo(1L);
                assertThat(c.getInt(c.getColumnIndexOrThrow(MediaProcessingStatus.MEDIA_TYPE)))
                        .isEqualTo(MEDIA_TYPE_IMAGE);
                assertThat(c.getLong(c.getColumnIndexOrThrow(MediaProcessingStatus.GEN_MODIFIED)))
                        .isEqualTo(100L);
                assertLabelProcessingStatus(c,
                        /* mediaLabelStatus */ 0,
                        /* locationLabelStatus */ 0,
                        /* metadataLabelStatus */ STATUS_COMPLETED);
            }
            return null;
        });
    }

    @Test
    public void testUpdateLocationLabelStatus_success() {
        mHelper.runWithTransaction((db) -> {
            insertMetadataProcessedRowInStatusTable(db, /* fileId */ 2L,
                    /* mediaType */ MEDIA_TYPE_IMAGE, /* generationModified */ 101L);
            boolean updated = updateLocationLabelStatus(db, /* fileId */ 2L, /* isSuccess */ true);

            assertThat(updated).isTrue();
            try (Cursor c = queryForMediaId(db, 2L)) {
                assertThat(c.getCount()).isEqualTo(1);
                c.moveToFirst();
                assertLabelProcessingStatus(c,
                        /* mediaLabelStatus */ 0,
                        /* locationLabelStatus */ STATUS_COMPLETED,
                        /* metadataLabelStatus */ STATUS_COMPLETED);
            }
            return null;
        });
    }

    @Test
    public void testUpdateLocationLabelStatus_processingFailed() {
        mHelper.runWithTransaction((db) -> {
            insertMetadataProcessedRowInStatusTable(db, /* fileId */ 3L,
                    /* mediaType */ MEDIA_TYPE_IMAGE, /* generationModified */ 102L);

            // Fail 1
            updateLocationLabelStatus(db, /* fileId */ 3L, /* isSuccess */ false);
            try (Cursor c = queryForMediaId(db, 3L)) {
                assertThat(c.getCount()).isEqualTo(1);
                c.moveToFirst();
                assertLabelProcessingStatus(c,
                        /* mediaLabelStatus */ 0,
                        /* locationLabelStatus */ 1,
                        /* metadataLabelStatus */ STATUS_COMPLETED);
            }

            // Fail 2
            updateLocationLabelStatus(db, /* fileId */ 3L, /* isSuccess */ false);
            try (Cursor c = queryForMediaId(db, 3L)) {
                assertThat(c.getCount()).isEqualTo(1);
                c.moveToFirst();
                assertLabelProcessingStatus(c,
                        /* mediaLabelStatus */ 0,
                        /* locationLabelStatus */ 2,
                        /* metadataLabelStatus */ STATUS_COMPLETED);
            }

            return null;
        });
    }

    @Test
    public void testUpdateLocationLabelStatus_nonExistentRow() {
        mHelper.runWithTransaction((db) -> {
            // No row inserted for fileId 999. Update should fail
            assertThat(updateLocationLabelStatus(db, /* fileId */ 999L, /* isSuccess */
                    true)).isFalse();

            try (Cursor c = queryForMediaId(db, 999L)) {
                assertThat(c.getCount()).isEqualTo(0);
            }
            return null;
        });
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_PROCESSING)
    public void testDeleteMediaIdFromStatusTable() {
        mHelper.runWithTransaction((db) -> {
            insertMetadataProcessedRowInStatusTable(db, /* fileId */ 5L,
                    /* mediaType */ FileColumns.MEDIA_TYPE_VIDEO, /* generationModified */ 104L);
            try (Cursor c = queryForMediaId(db, 5L)) {
                assertThat(c.getCount()).isEqualTo(1);
            }
            return null;
        });

        deleteMediaIdFromStatusTable(mHelper, /* fileId */ 5L);

        mHelper.runWithoutTransaction((db) -> {
            try (Cursor c = queryForMediaId(db, 5L)) {
                assertThat(c.getCount()).isEqualTo(0);
            }
            return null;
        });
    }

    private Cursor queryForMediaId(SQLiteDatabase db, long mediaId) {
        return db.query(MediaProcessingStatus.MEDIA_PROCESSING_STATUS_TABLE,
                /* projection */ null, /* selection */ MediaProcessingStatus.FILE_ID_COLUMN + "=?",
                /* selectionArgs */ new String[]{String.valueOf(mediaId)},
                /* groupBy */ null, /* having */ null, /* orderBy */ null);
    }
}
