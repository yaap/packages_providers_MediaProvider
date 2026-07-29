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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.providers.media.flags.Flags;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A utility class for handling expired items in the MediaProvider.
 */
public final class ExpiredItemsUtils {
    private static final String TAG = "ExpiredItemsUtils";

    private ExpiredItemsUtils() {
        // Utility class
    }

    /**
     * Deletes expired items from the database.
     *
     * @param context      The context.
     * @param db           The SQLite database.
     * @param signal       A signal to cancel the operation in progress.
     * @param deletionHost A host to handle the deletion of files.
     * @return The number of items that were deleted.
     */
    public static int deleteExpiredItems(@NonNull Context context, @NonNull SQLiteDatabase db,
            @NonNull CancellationSignal signal, @NonNull ExpiredDeletionHost deletionHost) {
        final long expiredOneWeek =
                ((System.currentTimeMillis() - DateUtils.WEEK_IN_MILLIS) / 1000);
        final long now = (System.currentTimeMillis() / 1000);

        String dateSelection = FileColumns.DATE_EXPIRES + " > " + expiredOneWeek + " AND "
                + FileColumns.DATE_EXPIRES + " < " + now;
        String selection = buildFileSelection(context, dateSelection);

        final List<FileRow> filesToDelete = new ArrayList<>();
        final List<FileRow> dirsToDelete = new ArrayList<>();
        try (Cursor c = db.query(true, MediaStore.Files.TABLE, FileRow.PROJECTIONS, selection,
                null, null, null, null, null, signal)) {
            while (c.moveToNext()) {
                final FileRow item = new FileRow(c);
                if (item.mIsDirectory) {
                    dirsToDelete.add(item);
                } else {
                    filesToDelete.add(item);
                }
            }
        }

        int deleteCount = 0;
        for (FileRow file : filesToDelete) {
            deleteCount += deletionHost.deleteFile(file.mVolumeName, file.mId);
        }

        if (Flags.enableTrashAndRestoreByFilePathApi()) {
            // Sort directories by path length in descending order to delete nested directories
            // first.
            dirsToDelete.sort(
                    (d1, d2) -> Integer.compare(d2.mOriginalPath.length(),
                            d1.mOriginalPath.length()));

            for (FileRow dir : dirsToDelete) {
                deleteCount += deletionHost.deleteFile(dir.mVolumeName, dir.mId);
            }
        }

        return deleteCount;
    }

    /**
     * Extends the expiration date of expired items.
     *
     * @param context       The context.
     * @param db            The SQLite database.
     * @param signal        A signal to cancel the operation in progress.
     * @param extensionHost A host to handle the extension of expired items.
     * @return The number of items whose expiration dates were extended.
     */
    public static int extendExpiredItems(@NonNull Context context, @NonNull SQLiteDatabase db,
            @NonNull CancellationSignal signal, @NonNull ExpiredExtensionHost extensionHost) {
        final long expiredOneWeek =
                ((System.currentTimeMillis() - DateUtils.WEEK_IN_MILLIS) / 1000);
        final long now = (System.currentTimeMillis() / 1000);
        final long expiredTime = now + (FileUtils.DEFAULT_DURATION_EXTENDED / 1000);
        String dateSelection = FileColumns.DATE_EXPIRES + " <= " + expiredOneWeek;
        String selection = buildFileSelection(context, dateSelection);

        final List<FileRow> allItemsToExtend = new ArrayList<>();
        // Sort by path (ASC) to ensure that parent directories are processed before their
        // children.
        try (Cursor c = db.query(true, MediaStore.Files.TABLE, FileRow.PROJECTIONS, selection,
                null, null, null, FileColumns.DATA + " ASC", null, signal)) {
            while (c.moveToNext()) {
                FileRow fileRow = new FileRow(c);
                // Only include directories if the trash flag is enabled
                if (Flags.enableTrashAndRestoreByFilePathApi() || !fileRow.mIsDirectory) {
                    allItemsToExtend.add(fileRow);
                }
            }
        }

        // A map to store the count of items within each directory. This is used to calculate the
        // total number of extended items when a directory's expiration is extended.
        final HashMap<Long, Integer> directoryItemCount = new HashMap<>();

        // The final list of items to process. For directories, we only process the
        // top-level one and the extension logic will handle nested items.
        final List<FileRow> itemsToExtend =
                filterItemsToExtend(allItemsToExtend, directoryItemCount);

        int extendCount = 0;
        int index = 0;
        for (FileRow item : itemsToExtend) {
            // This is a sanity check. If the flag is off, we should not have any directories to
            // extend.
            if (!Flags.enableTrashAndRestoreByFilePathApi() && item.mIsDirectory) {
                continue;
            }
            if (extendExpiredItem(db, item, expiredTime, expiredTime + index, extensionHost)) {
                if (item.mIsDirectory) {
                    // When a directory is extended, all its children are also considered extended.
                    extendCount += 1 + directoryItemCount.getOrDefault(item.mId, 0);
                } else {
                    extendCount++;
                }
            }
            index++;
        }

        return extendCount;
    }

    /**
     * Builds the full SQL selection clause for querying pending or trashed files
     * on external volumes using a specific date-based filter.
     */
    private static String buildFileSelection(@NonNull Context context,
            @NonNull String dateSelection) {
        return dateSelection
                + " AND (" + MediaColumns.IS_PENDING + "=1 OR " + MediaColumns.IS_TRASHED + "=1)"
                + " AND " + MediaColumns.VOLUME_NAME + " in " + DatabaseUtils.bindList(
                MediaStore.getExternalVolumeNames(context).toArray());
    }

    /**
     * Extends the expiration date of an expired item.
     */
    private static boolean extendExpiredItem(@NonNull SQLiteDatabase db,
            @NonNull FileRow fileRow, long newExpiredTime, long adjustedExpiredTime,
            @NonNull ExpiredExtensionHost host) {
        final long fileId = fileRow.mId;
        final String originalPath = fileRow.mOriginalPath;
        String newPath = FileUtils.getAbsoluteExtendedPath(originalPath, newExpiredTime);
        if (newPath == null) {
            Log.e(TAG, "Couldn't compute path for " + originalPath + " and expired time "
                    + newExpiredTime);
            return false;
        }

        long updatedExpiredTime = newExpiredTime;
        // If the intended new path already exists, try to generate a unique path by using the
        // adjusted expiration time. This helps avoid filename collisions.
        if (new File(newPath).exists()) {
            newPath = FileUtils.getAbsoluteExtendedPath(originalPath, adjustedExpiredTime);
            updatedExpiredTime = adjustedExpiredTime;

            // If the path with the adjusted time also exists, we cannot generate a unique filename,
            // so the operation must fail.
            if (new File(newPath).exists()) {
                final String errorMessage =
                        "Failed to extend expired item from " + originalPath + " to "
                                + newPath + " because the destination file already exists.";
                Log.e(TAG, errorMessage);
                return false;
            }
        }

        // In case of directory, rename the directory, all its nested items, and invalidate the
        // cache.
        if (fileRow.mIsDirectory) {
            return host.renameDirectoryAndInvalidateCache(originalPath, newPath);
        }

        try {
            if (updateDatabaseForExpiredItem(db, newPath, fileId, updatedExpiredTime)) {
                return host.renameFileAndInvalidateCache(originalPath, newPath);
            }
            return false;
        } catch (SQLiteConstraintException e) {
            final String errorMessage =
                    "Update database _data from " + originalPath + " to " + newPath + " failed.";
            Log.d(TAG, errorMessage, e);
            return false;
        }
    }

    /**
     * Updates the database with the new path and expiration time for an item.
     */
    private static boolean updateDatabaseForExpiredItem(@NonNull SQLiteDatabase db,
            @NonNull String path, long id, long expiredTime) {
        final String table = MediaStore.Files.TABLE;
        final String whereClause = MediaColumns._ID + "=?";
        final String[] whereArgs = new String[]{String.valueOf(id)};
        final ContentValues values = new ContentValues();
        values.put(FileColumns.DATA, path);
        values.put(FileColumns.DATE_EXPIRES, expiredTime);
        final int count = db.update(table, values, whereClause, whereArgs);
        return count == 1;
    }

    /**
     * Filters the list of all items to extend, returning only the items that need to be processed.
     * For directories, only the top-level directory is returned, and its children are counted in
     * the directoryItemCount map.
     */
    private static List<FileRow> filterItemsToExtend(
            List<FileRow> allItemsToExtend, Map<Long, Integer> directoryItemCount) {
        if (!Flags.enableTrashAndRestoreByFilePathApi()) {
            return allItemsToExtend;
        }

        final List<FileRow> itemsToProcess = new ArrayList<>();
        String lastProcessedDirectoryPath = null;
        long lastProcessedDirId = -1;

        for (FileRow item : allItemsToExtend) {
            if (lastProcessedDirectoryPath != null && item.mOriginalPath.startsWith(
                    lastProcessedDirectoryPath + "/")) {
                // This item is inside a directory we are already processing.
                directoryItemCount.put(
                        lastProcessedDirId,
                        directoryItemCount.getOrDefault(lastProcessedDirId, 0) + 1);
                continue;
            }

            itemsToProcess.add(item);

            if (item.mIsDirectory) {
                lastProcessedDirectoryPath = item.mOriginalPath;
                lastProcessedDirId = item.mId;
            } else {
                lastProcessedDirectoryPath = null;
                lastProcessedDirId = -1;
            }
        }
        return itemsToProcess;
    }

    /**
     * Defines the contract for the host component that handles file deletions.
     */
    public interface ExpiredDeletionHost {
        /**
         * Permanently deletes the file associated with the given ID.
         *
         * @param volumeName The name of the volume where the file resides.
         * @param id         The ID of the file to delete.
         * @return The number of files deleted, which is 1 on success and 0 on failure.
         */
        int deleteFile(String volumeName, long id);
    }

    /**
     * Defines the contract for the host component that handles file extensions.
     */
    public interface ExpiredExtensionHost {
        /**
         * Renames a file from an original path to a new path and invalidates any related caches.
         *
         * @param originalPath The current path of the file to rename.
         * @param newPath      The destination path for the file.
         * @return {@code true} if the rename was successful, {@code false} otherwise.
         */
        boolean renameFileAndInvalidateCache(String originalPath, String newPath);

        /**
         * Renames a directory from an original path to a new path and invalidates any related
         * caches.
         *
         * @param originalPath The current path of the directory to rename.
         * @param newPath      The destination path for the directory.
         * @return {@code true} if the rename was successful, {@code false} otherwise.
         */
        boolean renameDirectoryAndInvalidateCache(String originalPath, String newPath);
    }


    static final class FileRow {
        public static final String[] PROJECTIONS =
                new String[]{FileColumns._ID, FileColumns.VOLUME_NAME,
                        FileColumns.DATE_EXPIRES, FileColumns.DATA};
        final long mId;
        final String mVolumeName;
        final long mDateExpires;
        final String mOriginalPath;
        final boolean mIsDirectory;

        FileRow(Cursor c) {
            this.mId = c.getLong(c.getColumnIndexOrThrow(FileColumns._ID));
            this.mVolumeName = c.getString(c.getColumnIndexOrThrow(FileColumns.VOLUME_NAME));
            this.mDateExpires = c.getLong(c.getColumnIndexOrThrow(FileColumns.DATE_EXPIRES));
            this.mOriginalPath = c.getString(c.getColumnIndexOrThrow(FileColumns.DATA));
            this.mIsDirectory = new File(mOriginalPath).isDirectory();
        }
    }
}
