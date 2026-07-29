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

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
import static android.provider.MediaStore.Images.ImageColumns.LATITUDE;
import static android.provider.MediaStore.Images.ImageColumns.LONGITUDE;

import static com.android.providers.media.MediaProvider.MEDIAPROVIDER_PREFS;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.provider.MediaStore.Files;
import android.provider.MediaStore.Files.FileColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.exifinterface.media.ExifInterface;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.flags.Flags;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;

/**
 * Utility class for handling location metadata of media files
 */
public class LocationMetadataUtils {
    private static final String TAG = "LocationMetadataUtils";

    private static final String LOCATION_METADATA_UPDATED_SHARED_PREFERENCE =
            "location_metadata_updated";

    private static final String LAST_ROW_UPDATED_WITH_LOCATION = "last_row_updated_with_location";

    private static final String MAX_ROW_ID_BEFORE_LOCATION_BACKFILL =
            "max_row_id_before_location_backfill";

    private static final int LOCATION_METADATA_UPDATE_BATCH_SIZE = 100;

    /**
     * Backfill LATITUDE/LONGITUDE columns for db rows with no location metadata
     *
     * @param signal idle maintenance cancellation signal
     */
    public static void updateLocationMetadata(Context context, DatabaseHelper externalDatabase,
            @NonNull CancellationSignal signal) {
        if (!Flags.indexMediaLatitudeLongitude()) {
            return;
        }

        SharedPreferences preferences = context.getSharedPreferences(MEDIAPROVIDER_PREFS,
                Context.MODE_PRIVATE);

        if (preferences.getBoolean(LOCATION_METADATA_UPDATED_SHARED_PREFERENCE, false)) {
            // Location metadata updated for device.
            return;
        }

        long maxRowIdBeforeLocationBackfill = getMaxRowIdBeforeLocationBackfill(externalDatabase,
                preferences);
        long lastRowUpdatedWithLocation = preferences.getLong(LAST_ROW_UPDATED_WITH_LOCATION, 0);

        try {
            while (lastRowUpdatedWithLocation <= maxRowIdBeforeLocationBackfill) {
                if (signal.isCanceled()) {
                    Log.w(TAG, "Received cancellation signal while backfilling location metadata");
                    break;
                }

                final long currentUpdatedRow = lastRowUpdatedWithLocation;
                lastRowUpdatedWithLocation = externalDatabase.runWithTransaction((db) -> {
                    return updateLocationMetadataColumns(db, currentUpdatedRow,
                            maxRowIdBeforeLocationBackfill, signal);
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error while backfilling location metadata", e);
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putLong(LAST_ROW_UPDATED_WITH_LOCATION, lastRowUpdatedWithLocation);
            if (lastRowUpdatedWithLocation > maxRowIdBeforeLocationBackfill) {
                editor.putBoolean(LOCATION_METADATA_UPDATED_SHARED_PREFERENCE, true);
            }
            editor.apply();
        }
    }

    /**
     * Returns latest row ID which has location metadata after LAT/LONG columns are enabled
     *
     * @param preferences SharedPreference
     * @return row id
     */
    private static long getMaxRowIdBeforeLocationBackfill(DatabaseHelper externalDatabase,
            SharedPreferences preferences) {
        long maxRowIdBeforeLocationBackfill = preferences.getLong(
                MAX_ROW_ID_BEFORE_LOCATION_BACKFILL, -1);
        if (maxRowIdBeforeLocationBackfill != -1) {
            return maxRowIdBeforeLocationBackfill;
        }

        long latestRowInDb = externalDatabase.runWithTransaction((db) -> {
            String orderByClause = FileColumns._ID + " DESC ";
            try (Cursor c = db.query(Files.TABLE, new String[]{FileColumns._ID}, null, null, null,
                    null, orderByClause, /* limit */ "1")) {
                c.moveToFirst();
                return c.getLong(0);
            }
        });

        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(MAX_ROW_ID_BEFORE_LOCATION_BACKFILL, latestRowInDb);
        editor.apply();

        return latestRowInDb;
    }

    /**
     * Query row id and filepath for 100 rows where row id is greater than lastUpdatedRow, media
     * type is image or video, and the db row for that has NULL location metadata, in the order of
     * increasing row ids.
     * @param lastUpdatedRow Last row updated with location metadata at idle scan
     * @return query results
     */
    private static Cursor queryForNullLocationMetadataColumns(SQLiteDatabase db,
            long lastUpdatedRow) {
        final String[] projection = new String[]{FileColumns._ID, FileColumns.DATA};
        final String selection = getSelectionString(lastUpdatedRow);
        final String orderByClause = FileColumns._ID + " ASC";

        return db.query(/* distinct */ true, Files.TABLE, projection, selection,
                /* selectionArgs */ null, /* groupBy */ null, /* having */ null, orderByClause,
                String.valueOf(LOCATION_METADATA_UPDATE_BATCH_SIZE));
    }

    @NonNull
    private static String getSelectionString(long lastUpdatedRow) {
        String selectRowsAfter = FileColumns._ID + " > " + lastUpdatedRow;
        String selectImages = FileColumns.MEDIA_TYPE + "=" + MEDIA_TYPE_IMAGE;
        String selectVideos = FileColumns.MEDIA_TYPE + "=" + MEDIA_TYPE_VIDEO;
        String selectLocationMetadataIsNull = LATITUDE + " IS NULL AND " + LONGITUDE + " IS NULL";

        return selectRowsAfter + " AND (" + selectImages + " OR " + selectVideos + ") AND ("
                + selectLocationMetadataIsNull + ")";
    }

    /**
     * Update location metadata columns for files existing in MediaProvider db after
     * latitude/longitude columns update at scanItem is enabled.
     * @return generationModified number for the last updated row
     */
    @VisibleForTesting
    static long updateLocationMetadataColumns(SQLiteDatabase db, long lastUpdatedRow,
            long maxRowIdBeforeLocationBackfill, @NonNull CancellationSignal signal) {
        ArrayList<FileInfo> filesToUpdate = new ArrayList<>();
        try (Cursor c = queryForNullLocationMetadataColumns(db, lastUpdatedRow)) {
            while (c.moveToNext()) {
                long fileId = c.getLong(c.getColumnIndexOrThrow(FileColumns._ID));
                String data = c.getString(c.getColumnIndexOrThrow(FileColumns.DATA));

                filesToUpdate.add(new FileInfo(fileId, data));
            }
        }

        if (filesToUpdate.isEmpty()) {
            // All subsequent rows have updated location metadata
            return maxRowIdBeforeLocationBackfill + 1;
        }

        Log.d(TAG, "Incrementally updating " + filesToUpdate.size()
                + " files with null location metadata");
        int evaluatedRows = 0;
        for (FileInfo fileToUpdate : filesToUpdate) {
            File file = new File(fileToUpdate.mFilepath);
            try (FileInputStream is = new FileInputStream(file)) {
                final ExifInterface exif = new ExifInterface(is);
                float[] locationCoordinates = new float[2];
                if (exif.getLatLong(locationCoordinates)) {
                    ContentValues values = new ContentValues();
                    values.put(LATITUDE, locationCoordinates[0]);
                    values.put(LONGITUDE, locationCoordinates[1]);

                    String selection = FileColumns._ID + "=" + fileToUpdate.mId;
                    db.update(Files.TABLE, values, selection, null);
                }
                evaluatedRows++;
            } catch (Exception e) {
                Log.e(TAG, "Couldn't update location metadata for " + fileToUpdate.mId, e);
            } finally {
                lastUpdatedRow = fileToUpdate.mId;
            }
        }

        Log.d(TAG, "Scanned " + evaluatedRows + " files. Expected : " + filesToUpdate.size());

        return lastUpdatedRow;
    }

    private static class FileInfo {
        final long mId;
        final String mFilepath;

        FileInfo(long id, String filepath) {
            this.mId = id;
            this.mFilepath = filepath;
        }
    }
}
