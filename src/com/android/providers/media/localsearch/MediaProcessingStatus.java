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

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_NONE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.media.internal.flags.Flags;
import android.text.TextUtils;

import com.android.providers.media.DatabaseHelper;

import java.util.Collections;
import java.util.List;

/**
 * Manages the processing status of media files in the {@code MEDIA_PROCESSING_STATUS} table.
 * This class tracks the status of various processing types
 * (e.g., media labels, location, metadata) for each media file.
 */
public class MediaProcessingStatus {
    public static final String TAG = "MediaProcessingStatus";
    public static final String MEDIA_PROCESSING_STATUS_TABLE = "media_processing_status";
    // Column names for media_processing_status table
    public static final String FILE_ID_COLUMN = "file_id";
    public static final String MEDIA_TYPE = "media_type";
    public static final String GEN_MODIFIED = "generation_modified";

    /**
     * Label Status columns track the processing state or retry count for each processing type.
     * <p>
     * Values:
     * <ul>
     * <li>{@code 0}: Default state, not processed.</li>
     * <li>{@code > 0}: Number of failed attempts (incremented on failure). Max value:
     * {@link #RETRY_LIMIT}</li>
     * <li>{@code 999} ({@link #STATUS_COMPLETED}): Processing successfully completed or max
     * retries reached.</li>
     * </ul>
     */
    public static final String MEDIA_LABEL_STATUS = "is_media_label_processed";
    public static final String LOCATION_LABEL_STATUS = "is_location_label_processed";
    public static final String METADATA_LABEL_STATUS = "is_metadata_label_processed";

    /**
     * The maximum number of times processing will be attempted.
     */
    public static final int RETRY_LIMIT = 3;


    /**
     * The status value indicating that processing is finished. This indicates either a successful
     * processing attempt or that the {@link #RETRY_LIMIT} was reached.
     */
    public static final int STATUS_COMPLETED = 999;

    private static final Integer[] MEDIA_TYPES = new Integer[]{MEDIA_TYPE_AUDIO,
            MEDIA_TYPE_VIDEO, MEDIA_TYPE_IMAGE, MEDIA_TYPE_DOCUMENT, MEDIA_TYPE_NONE};

    private static int getCurrentRetryCount(SQLiteDatabase db, long mediaId,
            String processingStatusColumn) {
        final String selection = FILE_ID_COLUMN + "=?";
        final String[] selectionArgs = new String[]{String.valueOf(mediaId)};
        final String[] projection = new String[]{processingStatusColumn};

        try (Cursor c = db.query(MEDIA_PROCESSING_STATUS_TABLE, projection, selection,
                selectionArgs, /* groupBy */ null, /* having */ null, /* sortOrder */ null)) {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        }
        return 0;
    }

    private static int incrementRetryCount(SQLiteDatabase db, long mediaId,
            String processingStatusColumn) {
        return getCurrentRetryCount(db, mediaId, processingStatusColumn) + 1;
    }

    /**
     * Inserts a new row into the media processing status table.
     * <p>
     * This method is called after the initial metadata extraction is complete.
     * It initializes the row with the {@code METADATA_LABEL_STATUS} set to
     * {@link #STATUS_COMPLETED}, while other processing statuses (Location, Media Label)
     * remain at their default (unprocessed) state.
     */
    public static void insertMetadataProcessedRowInStatusTable(SQLiteDatabase db, long mediaId,
            long mediaType,
            long genModified) {
        final ContentValues values = new ContentValues();
        values.put(FILE_ID_COLUMN, mediaId);
        values.put(MEDIA_TYPE, mediaType);
        values.put(GEN_MODIFIED, genModified);
        values.put(METADATA_LABEL_STATUS, STATUS_COMPLETED);

        db.insertWithOnConflict(MEDIA_PROCESSING_STATUS_TABLE, /* nullColumnHack */ null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * Updates the processing status for location labels for a specific media item.
     * <p>
     * If {@code isSuccess} is true, the status is marked as {@link #STATUS_COMPLETED}.
     * If {@code isSuccess} is false, the current failure count is incremented.
     * If the failure count reaches {@link #RETRY_LIMIT}, the status is marked as
     * {@link #STATUS_COMPLETED}
     *
     * @return {@code true} if a row was updated, {@code false} otherwise.
     */
    public static boolean updateLocationLabelStatus(SQLiteDatabase db, long mediaId,
            boolean isSuccess) {
        return updateRowInStatusTable(db, mediaId, LOCATION_LABEL_STATUS, isSuccess);
    }

    /**
     * Updates the processing status for media labels and embeddings for a specific media item.
     * <p>
     * If {@code isSuccess} is true, the status is marked as {@link #STATUS_COMPLETED}.
     * If {@code isSuccess} is false, the current failure count is incremented.
     * If the failure count reaches {@link #RETRY_LIMIT}, the status is marked as
     * {@link #STATUS_COMPLETED} to prevent infinite retries.
     *
     * @return {@code true} if a row was updated, {@code false} otherwise.
     */
    public static boolean updateMediaLabelStatus(SQLiteDatabase db, long mediaId,
            boolean isSuccess) {
        return updateRowInStatusTable(db, mediaId, MEDIA_LABEL_STATUS, isSuccess);
    }

    private static boolean updateRowInStatusTable(SQLiteDatabase db, long mediaId,
            String statusColumn, boolean isSuccess) {
        int status = isSuccess ? STATUS_COMPLETED : incrementRetryCount(db, mediaId,
                statusColumn);

        final String selection = FILE_ID_COLUMN + "=?";
        final String[] selectionArgs = new String[]{String.valueOf(mediaId)};

        final ContentValues values = new ContentValues();
        values.put(statusColumn, status);

        int rowsUpdated = db.update(MEDIA_PROCESSING_STATUS_TABLE, values, selection,
                selectionArgs);

        return (rowsUpdated == 1);
    }

    /**
     * Updates the processing status for labels as completed for a list of media items.
     *
     * @param db           SQLite external.db database instance
     * @param fileIds      List of media item file IDs.
     * @param statusColumn Column name for the processing status.
     * @return number of rows affected with the update
     */
    public static int bulkUpdateLabelStatusAsSuccess(SQLiteDatabase db, List<Long> fileIds,
            String statusColumn) {
        final ContentValues values = new ContentValues();
        values.put(statusColumn, STATUS_COMPLETED);

        String whereClauseForUpdate =
                FILE_ID_COLUMN + " IN (" + TextUtils.join(",", fileIds) + ")";
        return db.update(MEDIA_PROCESSING_STATUS_TABLE, values, whereClauseForUpdate,
                null);
    }

    /**
     * Delete row from media_processing_status table for mediaId if file updated/deleted in the SQL
     * files table
     *
     * @param helper  DatabaseHelper instance
     * @param mediaId File id to be deleted
     */
    public static void deleteMediaIdFromStatusTable(DatabaseHelper helper, long mediaId) {
        if (Flags.enableMediaProcessing()) {
            String selection = FILE_ID_COLUMN + " = ?";
            helper.runWithTransaction((db) -> {
                db.delete(MEDIA_PROCESSING_STATUS_TABLE, selection,
                        new String[]{String.valueOf(mediaId)});
                return null;
            });
        }
    }

    /**
     * Delete rows from media_processing_status table for the given list of mediaIds.
     *
     * @param helper   DatabaseHelper instance
     * @param mediaIds List of file ids to be deleted
     */
    public static void deleteMediaIdsFromStatusTable(DatabaseHelper helper, List<Long> mediaIds) {
        if (Flags.enableMediaProcessing() && !mediaIds.isEmpty()) {
            helper.runWithTransaction((db) -> {
                String placeholders = TextUtils.join(",",
                        Collections.nCopies(mediaIds.size(), "?"));

                String selection = FILE_ID_COLUMN + " IN (" + placeholders + ")";

                String[] selectionArgs = mediaIds.stream()
                        .map(String::valueOf)
                        .toArray(String[]::new);

                db.delete(MEDIA_PROCESSING_STATUS_TABLE, selection, selectionArgs);
                return null;
            });
        }
    }
}
