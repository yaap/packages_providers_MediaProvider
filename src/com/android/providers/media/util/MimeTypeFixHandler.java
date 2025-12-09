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
import android.database.sqlite.SQLiteDatabase;
import android.drm.DrmManagerClient;
import android.drm.DrmSupportInfo;
import android.provider.MediaStore;
import android.util.ArraySet;
import android.util.Log;

import com.android.providers.media.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Utility class for handling MIME type mappings.
 */
public final class MimeTypeFixHandler {

    private static final String TAG = "MimeTypeFixHandler";
    private static final Map<String, String> sExtToMimeType = new HashMap<>();
    private static final Map<String, String> sMimeTypeToExt = new HashMap<>();

    private static final Map<String, String> sCorruptedExtToMimeType = new HashMap<>();
    private static final Map<String, String> sCorruptedMimeTypeToExt = new HashMap<>();

    private static DrmManagerClient sDrmClient = null;

    /**
     * Set of MIME types that should be considered to be DRM, meaning we need to
     * consult {@link DrmManagerClient} to obtain the actual MIME type.
     */
    private static final Set<String> sDrmMimeTypes = new ArraySet<>();

    /**
     * Loads MIME type mappings from the classpath resource if not already loaded.
     * <p>
     * This method initializes both the standard and corrupted MIME type maps.
     * </p>
     */
    public static void loadMimeTypes(Context context) {
        if (context == null) {
            return;
        }

        if (sExtToMimeType.isEmpty()) {
            parseTypes(context, R.raw.mime_types, sExtToMimeType, sMimeTypeToExt);
            // this will add or override the extension to mime type mapping
            parseTypes(context, R.raw.android_mime_types, sExtToMimeType, sMimeTypeToExt);
            Log.v(TAG, "MIME types loaded");
        }
        if (sCorruptedExtToMimeType.isEmpty()) {
            parseTypes(context, R.raw.corrupted_mime_types, sCorruptedExtToMimeType,
                    sCorruptedMimeTypeToExt);
            Log.v(TAG, "Corrupted MIME types loaded");
        }

        initDrmMimeTypes(context);
    }

    /**
     * Initializes the {@link DrmManagerClient} and populates the set of DRM MIME types
     * by querying available DRM support information.
     *
     * @param context The context used to resolve resources.
     */
    private static void initDrmMimeTypes(Context context) {
        sDrmClient = new DrmManagerClient(context);

        // Dynamically collect the set of MIME types that should be considered
        // to be DRM, as this can vary between devices
        for (DrmSupportInfo info : sDrmClient.getAvailableDrmSupportInfo()) {
            Iterator<String> mimeTypes = info.getMimeTypeIterator();
            while (mimeTypes.hasNext()) {
                sDrmMimeTypes.add(mimeTypes.next());
            }
        }
    }

    /**
     * Parses the specified mime types file and populates the provided mapping with file extension
     * to MIME type entries.
     *
     * @param resource the mime.type resource
     * @param mapping  the map to populate with file extension (key) to MIME type (value) mappings
     */
    private static void parseTypes(Context context, int resource, Map<String, String> extToMimeType,
            Map<String, String> mimeTypeToExt) {
        try (InputStream inputStream = context.getResources().openRawResource(resource)) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Strip comments and normalize whitespace
                    line = line.replaceAll("#.*$", "").trim().replaceAll("\\s+", " ");
                    // Skip empty lines or lines without a space (i.e., no extension mapping)
                    if (line.isEmpty() || !line.contains(" ")) {
                        continue;
                    }
                    String[] tokens = line.split(" ");
                    if (tokens.length < 2) {
                        continue;
                    }
                    String mimeType = tokens[0].toLowerCase(Locale.ROOT);
                    String firstExt = tokens[1].toLowerCase(Locale.ROOT);
                    if (firstExt.startsWith("?")) {
                        firstExt = firstExt.substring(1);
                        if (firstExt.isEmpty()) {
                            continue;
                        }
                    }

                    // ?mime ext1 ?ext2 ext3
                    if (mimeType.toLowerCase(Locale.ROOT).startsWith("?")) {
                        mimeType = mimeType.substring(1); // Remove the "?"
                        if (mimeType.isEmpty()) {
                            continue;
                        }
                        mimeTypeToExt.putIfAbsent(mimeType, firstExt);
                    } else {
                        mimeTypeToExt.put(mimeType, firstExt);
                    }

                    for (int i = 1; i < tokens.length; i++) {
                        String extension = tokens[i].toLowerCase(Locale.ROOT);
                        boolean putIfAbsent = extension.startsWith("?");
                        if (putIfAbsent) {
                            extension = extension.substring(1); // Remove the "?"
                            extToMimeType.putIfAbsent(extension, mimeType);
                        } else {
                            extToMimeType.put(extension, mimeType);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Exception raised while parsing mime.types", e);
        }
    }

    /**
     * Returns the MIME type for the given file extension from our internal mappings.
     *
     * @param extension The file extension to look up.
     * @return The associated MIME type from the primary mapping if available, or
     * {@link android.content.ClipDescription#MIMETYPE_UNKNOWN} if the extension is marked
     * as corrupted
     * Returns {@link Optional#empty()}  if not found in either mapping.
     */
    static Optional<String> getMimeType(String extension) {
        String lowerExt = extension.toLowerCase(Locale.ROOT);
        if (sExtToMimeType.containsKey(lowerExt)) {
            return Optional.of(sExtToMimeType.get(lowerExt));
        }

        if (sCorruptedExtToMimeType.containsKey(lowerExt)) {
            return Optional.of(android.content.ClipDescription.MIMETYPE_UNKNOWN);
        }

        return Optional.empty();
    }

    /**
     * Gets file extension from MIME type.
     *
     * @param mimeType The MIME type.
     * @return Optional file extension, or empty.
     */
    static Optional<String> getExtFromMimeType(String mimeType) {
        if (mimeType == null) {
            return Optional.empty();
        }

        mimeType = mimeType.toLowerCase(Locale.ROOT);
        return Optional.ofNullable(sMimeTypeToExt.get(mimeType));
    }

    /**
     * Checks if a MIME type is corrupted.
     *
     * @param mimeType The MIME type.
     * @return {@code true} if corrupted, {@code false} otherwise.
     */
    static boolean isCorruptedMimeType(String mimeType) {
        if (sMimeTypeToExt.containsKey(mimeType)) {
            return false;
        }

        return sCorruptedMimeTypeToExt.containsKey(mimeType);
    }


    /**
     * Scans the database for files with unsupported or mismatched MIME types and updates them.
     *
     * @param db The SQLiteDatabase to update.
     * @return true if all intended updates were successfully applied (or if there were no files),
     * false otherwise.
     */
    public static boolean updateUnsupportedMimeTypes(SQLiteDatabase db) {
        List<FileMimeTypeUpdate> filesToUpdate = new ArrayList<>();
        List<FileMimeTypeUpdate> videoMp4Files = new ArrayList<>();
        String[] projections = new String[]{MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DISPLAY_NAME
        };
        try (Cursor cursor = db.query(MediaStore.Files.TABLE, projections,
                null, null, null, null, null)) {

            while (cursor != null && cursor.moveToNext()) {
                long fileId = cursor.getLong(cursor.getColumnIndexOrThrow(
                        MediaStore.Files.FileColumns._ID));
                String data = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Files.FileColumns.DATA));
                String currentMimeType = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE));
                String displayName = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Files.FileColumns.DISPLAY_NAME));

                String extension = FileUtils.extractFileExtension(data);
                if (extension == null) {
                    continue;
                }
                String newMimeType = MimeUtils.resolveMimeType(new File(displayName));

                boolean isDrm = false;
                if (sDrmClient != null) {
                    isDrm = sDrmMimeTypes.contains(newMimeType);
                    if (isDrm) {
                        newMimeType = sDrmClient.getOriginalMimeType(data);
                    }
                }

                if (!newMimeType.equalsIgnoreCase(currentMimeType)) {
                    filesToUpdate.add(new FileMimeTypeUpdate(fileId, data, newMimeType, isDrm));
                }
                if (newMimeType.equalsIgnoreCase("video/mp4")) {
                    videoMp4Files.add(new FileMimeTypeUpdate(fileId, data, newMimeType, isDrm));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch files for MIME type check", e);
            return false;
        }

        Log.v(TAG, "Identified " + filesToUpdate.size() + " files with incorrect MIME types.");
        int updatedRows = 0;
        for (FileMimeTypeUpdate fileUpdate : filesToUpdate) {
            try {
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, fileUpdate.mNewMimeType);

                int mediaType = getMediaType(fileUpdate.mNewMimeType, fileUpdate.mFilePath);
                contentValues.put(MediaStore.Files.FileColumns.MEDIA_TYPE, mediaType);
                contentValues.put(MediaStore.Files.FileColumns.IS_DRM, fileUpdate.mIsDrm ? 1 : 0);

                String whereClause = MediaStore.Files.FileColumns._ID + " = ?";
                String[] whereArgs = new String[]{String.valueOf(fileUpdate.mFileId)};
                updatedRows += db.update(MediaStore.Files.TABLE, contentValues, whereClause,
                        whereArgs);
            } catch (Exception e) {
                Log.e(TAG, "Error updating file with id: " + fileUpdate.mFileId, e);
            }
        }

        // Refine MIME type and media type for files initially identified as "video/mp4".
        // This handles cases where an MP4 file might actually be an audio file (e.g., M4A).
        fixMp4MimeType(videoMp4Files, db);

        Log.v(TAG, "Updated MIME type and Media type for " + updatedRows + " rows");
        return updatedRows == filesToUpdate.size();
    }

    /**
     * Gets the MediaStore media type for a file, returning {@code MEDIA_TYPE_NONE}
     * for hidden files or files identified as album art
     *
     * @param mimeType The file's MIME type
     * @param path     The file's absolute path
     * @return The {@code MediaStore.Files.FileColumns.MEDIA_TYPE_*} constant, or
     * {@code MEDIA_TYPE_NONE} if hidden or album art
     */
    private static int getMediaType(String mimeType, String path) {
        File file = new File(path);
        // Return MEDIA_TYPE_NONE for hidden files or if any of its parents is hidden
        if (FileUtils.shouldFileBeHidden(file)) {
            return MediaStore.Files.FileColumns.MEDIA_TYPE_NONE;
        }

        int mediaType = MimeUtils.resolveMediaType(mimeType);

        // Exclude images identified as album art
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                && FileUtils.isFileAlbumArt(file)) {
            mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_NONE;
        }
        return mediaType;
    }

    /**
     * Fixes the MIME type and media type for files initially identified as "video/mp4"
     * by attempting to refine their MIME type (e.g., to "audio/m4a" if applicable).
     *
     * @param videoMp4Files A list of {@link FileMimeTypeUpdate} objects for files with
     *                      "video/mp4" MIME type.
     * @param db            The SQLiteDatabase to update.
     */
    private static void fixMp4MimeType(List<FileMimeTypeUpdate> videoMp4Files, SQLiteDatabase db) {
        for (FileMimeTypeUpdate videoMp4File : videoMp4Files) {
            try {
                String refinedMimeType = MimeUtils.updateM4aMimeType(
                        new File(videoMp4File.mFilePath),
                        videoMp4File.mNewMimeType);
                // if mp4 ext mimetype is same, then ignore updates
                if (refinedMimeType.equalsIgnoreCase(videoMp4File.mNewMimeType)) {
                    continue;
                }

                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, refinedMimeType);

                int mediaType = getMediaType(refinedMimeType, videoMp4File.mFilePath);
                contentValues.put(MediaStore.Files.FileColumns.MEDIA_TYPE, mediaType);
                contentValues.put(MediaStore.Files.FileColumns.IS_DRM, videoMp4File.mIsDrm ? 1 : 0);

                String whereClause = MediaStore.Files.FileColumns._ID + " = ?";
                String[] whereArgs = new String[]{String.valueOf(videoMp4File.mFileId)};
                db.update(MediaStore.Files.TABLE, contentValues, whereClause,
                        whereArgs);
            } catch (Exception e) {
                Log.e(TAG, "Error updating file with id: " + videoMp4File.mFileId, e);
            }
        }
    }

    private static class FileMimeTypeUpdate {
        final long mFileId;
        final String mFilePath;
        final String mNewMimeType;
        final boolean mIsDrm;

        FileMimeTypeUpdate(long fileId, String filePath, String newMimeType, boolean isDrm) {
            this.mFileId = fileId;
            this.mFilePath = filePath;
            this.mNewMimeType = newMimeType;
            this.mIsDrm = isDrm;
        }
    }

}
