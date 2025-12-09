/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker.data;

import static android.content.ContentResolver.EXTRA_HONORED_ARGS;
import static android.provider.CloudMediaProviderContract.AlbumColumns;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS;
import static android.provider.CloudMediaProviderContract.EXTRA_ALBUM_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_PAGE_SIZE;
import static android.provider.CloudMediaProviderContract.EXTRA_PAGE_TOKEN;
import static android.provider.CloudMediaProviderContract.EXTRA_SORT_ORDER;
import static android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS;
import static android.provider.CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS;
import static android.provider.CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_SD_CARD;
import static android.provider.CloudMediaProviderContract.MediaCategoryColumns;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo;
import static android.provider.CloudMediaProviderContract.MediaSetColumns;

import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.INT_DEFAULT;
import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.LONG_DEFAULT;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorLong;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;
import static com.android.providers.media.util.DatabaseUtils.bindList;
import static com.android.providers.media.util.DatabaseUtils.replaceMatchAnyChar;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteQueryBuilder;
import android.os.Bundle;
import android.os.Environment;
import android.os.UserHandle;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.ConfigStore;
import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.R;
import com.android.providers.media.VolumeCache;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.util.MimeUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * This is a facade that hides the complexities of executing some SQL statements on the external db.
 * It does not do any caller permission checks and is only intended for internal use within the
 * MediaProvider for the Photo Picker.
 */
public class ExternalDbFacade {
    private static final String TAG = "ExternalDbFacade";
    @VisibleForTesting
    static final String TABLE_FILES = "files";

    @VisibleForTesting
    static final String TABLE_DELETED_MEDIA = "deleted_media";
    @VisibleForTesting
    static final String COLUMN_OLD_ID = "old_id";
    private static final String COLUMN_OLD_ID_AS_ID = COLUMN_OLD_ID + " AS " +
            CloudMediaProviderContract.MediaColumns.ID;
    private static final String COLUMN_GENERATION_MODIFIED = MediaColumns.GENERATION_MODIFIED;
    private static final String COLUMN_DATE_TAKEN_MILLIS = "COALESCE(" + MediaColumns.DATE_TAKEN
            + "," + MediaColumns.DATE_MODIFIED + "* 1000)";
    private static final String COLUMN_ROW_NUMBER = "row_number";
    private static final String TABLE_SUBQUERY = "subquery_table";

    private static final String[] PROJECTION_MEDIA_COLUMNS = new String[]{
            MediaColumns._ID + " AS " + CloudMediaProviderContract.MediaColumns.ID,
            COLUMN_DATE_TAKEN_MILLIS + " AS "
                    + CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
            MediaColumns.GENERATION_MODIFIED + " AS "
                    + CloudMediaProviderContract.MediaColumns.SYNC_GENERATION,
            MediaColumns.SIZE + " AS " + CloudMediaProviderContract.MediaColumns.SIZE_BYTES,
            MediaColumns.MIME_TYPE + " AS " + CloudMediaProviderContract.MediaColumns.MIME_TYPE,
            FileColumns._SPECIAL_FORMAT + " AS "
                    + CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
            MediaColumns.DURATION + " AS "
                    + CloudMediaProviderContract.MediaColumns.DURATION_MILLIS,
            MediaColumns.IS_FAVORITE + " AS " + CloudMediaProviderContract.MediaColumns.IS_FAVORITE,
            MediaColumns.WIDTH + " AS " + CloudMediaProviderContract.MediaColumns.WIDTH,
            MediaColumns.HEIGHT + " AS " + CloudMediaProviderContract.MediaColumns.HEIGHT,
            MediaColumns.ORIENTATION + " AS " + CloudMediaProviderContract.MediaColumns.ORIENTATION,
            MediaColumns.OWNER_PACKAGE_NAME + " AS "
                    + CloudMediaProviderContract.MediaColumns.OWNER_PACKAGE_NAME,
            FileColumns._USER_ID + " AS " + CloudMediaProviderContract.MediaColumns.USER_ID,
    };
    private static final String[] PROJECTION_MEDIA_INFO = new String[]{
            "MAX(" + MediaColumns.GENERATION_MODIFIED + ") AS "
                    + MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION
    };
    private static final String[] PROJECTION_ALBUM_DB = new String[]{
            "COUNT(" + MediaColumns._ID + ") AS "
                    + AlbumColumns.MEDIA_COUNT,
            "MAX(" + COLUMN_DATE_TAKEN_MILLIS + ") AS "
                    + AlbumColumns.DATE_TAKEN_MILLIS,
            MediaColumns._ID + " AS " + AlbumColumns.MEDIA_COVER_ID,
    };

    /**
     * Projection array defining the columns required to represent the downloads media set.
     */
    private static final String[] PROJECTION_DOWNLOADS_FOLDER = new String[]{
            MediaColumns._ID + " AS " + MediaSetColumns.MEDIA_COVER_ID,
            COLUMN_DATE_TAKEN_MILLIS + " AS " + AlbumColumns.DATE_TAKEN_MILLIS,
    };

    /**
     * Projection array defining the common columns required to represent a media set
     * This is {@link MediaSetColumns#ALL_PROJECTION} + {@link AlbumColumns#DATE_TAKEN_MILLIS}
     *
     * {@link AlbumColumns#DATE_TAKEN_MILLIS} is required for setting the starting point for the
     * next page for paginated calls.
     */
    private static final String[] PROJECTION_MEDIA_SET = new String[] {
            MediaSetColumns.ID,
            MediaSetColumns.DISPLAY_NAME,
            MediaSetColumns.MEDIA_COUNT,
            MediaSetColumns.MEDIA_COVER_ID,
            AlbumColumns.DATE_TAKEN_MILLIS,
    };

    /**
     * Projection array defining the columns required to represent a device folder media set.
     */
    private static final String[] PROJECTION_DEVICE_MEDIA_SET = new String[]{
            String.format(Locale.ROOT,
                    "'%s:'||%s AS %s",
                    MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                    MediaColumns.BUCKET_ID,
                    MediaSetColumns.ID),
            String.format(Locale.ROOT,
                    "%s AS %s",
                    MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaSetColumns.DISPLAY_NAME),
            MediaSetColumns.MEDIA_COUNT,
            String.format(Locale.ROOT,
                    "%s AS %s",
                    MediaColumns._ID,
                    MediaSetColumns.MEDIA_COVER_ID),
            AlbumColumns.DATE_TAKEN_MILLIS};

    /**
     * Projection array defining the columns required to represent a sd card folder media set.
     */
    private static final String[] PROJECTION_SD_CARD_MEDIA_SET = new String[]{
            String.format(Locale.ROOT,
                    "'%s:'||%s AS %s",
                    MEDIA_CATEGORY_TYPE_SD_CARD,
                    MediaColumns.BUCKET_ID,
                    MediaSetColumns.ID),
            String.format(Locale.ROOT,
                    "%s AS %s",
                    MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaSetColumns.DISPLAY_NAME),
            MediaSetColumns.MEDIA_COUNT,
            String.format(Locale.ROOT,
                    "%s AS %s",
                    MediaColumns._ID,
                    MediaSetColumns.MEDIA_COVER_ID),
            AlbumColumns.DATE_TAKEN_MILLIS};

    /**
     * Projection array defining the columns required to represent an app-specific media set.
     */
    private static final String[] PROJECTION_APPS_MEDIA_SET = new String[]{
            String.format(Locale.ROOT,
                    "'%s:'||%s AS %s",
                    MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                    MediaColumns.OWNER_PACKAGE_NAME,
                    MediaSetColumns.ID),
            MediaColumns.OWNER_PACKAGE_NAME,
            MediaSetColumns.MEDIA_COUNT,
            String.format(Locale.ROOT,
                    "%s AS %s",
                    MediaColumns._ID,
                    MediaSetColumns.MEDIA_COVER_ID),
            AlbumColumns.DATE_TAKEN_MILLIS};

    /**
     * Projection array for the inner subquery used when querying device media sets (folders).
     * Calculates row numbers and media counts by partitioning over bucket_id.
     */
    private static final String[] PROJECTION_DEVICE_MEDIA_SET_SUBQUERY = new String[]{
            MediaColumns.BUCKET_ID,
            MediaColumns.BUCKET_DISPLAY_NAME,
            MediaColumns._ID,
            String.format(Locale.ROOT,
                    "%s AS %s",
                    COLUMN_DATE_TAKEN_MILLIS,
                    AlbumColumns.DATE_TAKEN_MILLIS),
            String.format(Locale.ROOT,
                    "ROW_NUMBER() OVER(PARTITION BY %s ORDER BY %s DESC, %s DESC) AS %s",
                    MediaColumns.BUCKET_ID,
                    COLUMN_DATE_TAKEN_MILLIS,
                    MediaColumns._ID,
                    COLUMN_ROW_NUMBER),
            String.format(Locale.ROOT,
                    "COUNT(*) OVER (PARTITION BY %s) AS %s",
                    MediaColumns.BUCKET_ID,
                    MediaSetColumns.MEDIA_COUNT)};

    /**
     * Projection string for the inner subquery used when querying app-specific media sets.
     * Calculates row numbers and media counts by partitioning over owner_package_name.
     */
    private static final String[] PROJECTION_APPS_MEDIA_SET_SUBQUERY = new String[]{
            MediaColumns.OWNER_PACKAGE_NAME,
            MediaColumns._ID,
            String.format(Locale.ROOT,
                    "%s AS %s",
                    COLUMN_DATE_TAKEN_MILLIS,
                    AlbumColumns.DATE_TAKEN_MILLIS),
            String.format(Locale.ROOT,
                    "ROW_NUMBER() OVER(PARTITION BY %s ORDER BY %s DESC, %s DESC) AS %s",
                    MediaColumns.OWNER_PACKAGE_NAME,
                    COLUMN_DATE_TAKEN_MILLIS,
                    MediaColumns._ID,
                    COLUMN_ROW_NUMBER),
            String.format(Locale.ROOT,
                    "COUNT(*) OVER (PARTITION BY %s) AS %s",
                    MediaColumns.OWNER_PACKAGE_NAME,
                    MediaSetColumns.MEDIA_COUNT)};

    private static final String WHERE_IMAGE_TYPE = FileColumns.MEDIA_TYPE + " = "
            + FileColumns.MEDIA_TYPE_IMAGE;
    private static final String WHERE_VIDEO_TYPE = FileColumns.MEDIA_TYPE + " = "
            + FileColumns.MEDIA_TYPE_VIDEO;
    private static final String WHERE_MEDIA_TYPE = WHERE_IMAGE_TYPE + " OR " + WHERE_VIDEO_TYPE;
    private static final String WHERE_IS_DOWNLOAD = MediaColumns.IS_DOWNLOAD + " = 1";
    private static final String WHERE_NOT_TRASHED = MediaColumns.IS_TRASHED + " = 0";
    private static final String WHERE_NOT_PENDING = MediaColumns.IS_PENDING + " = 0";
    private static final String WHERE_GREATER_GENERATION =
            MediaColumns.GENERATION_MODIFIED + " > ?";
    private static final String WHERE_RELATIVE_PATH = MediaColumns.RELATIVE_PATH
            + " LIKE ?";

    private static final String WHERE_DATE_TAKEN_MILLIS_BEFORE =
            String.format("(%s < CAST(? AS INT) OR (%s = CAST(? AS INT) AND %s < CAST(? AS INT)))",
                    CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
                    CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
                    MediaColumns._ID);


    /* Include any directory named exactly {@link Environment.DIRECTORY_SCREENSHOTS}
     * and its child directories. */
    private static final String WHERE_RELATIVE_PATH_IS_SCREENSHOT_DIR =
            MediaColumns.RELATIVE_PATH
                    + " LIKE '%/"
                    + Environment.DIRECTORY_SCREENSHOTS
                    + "/%' OR "
                    + MediaColumns.RELATIVE_PATH
                    + " LIKE '"
                    + Environment.DIRECTORY_SCREENSHOTS
                    + "/%'";

    private static final String WHERE_VOLUME_IN_PREFIX =
            MediaColumns.VOLUME_NAME + " IN %s";

    public static final String RELATIVE_PATH_CAMERA = Environment.DIRECTORY_DCIM + "/Camera/%";

    public static final String RELATIVE_PATH_DOWNLOAD = Environment.DIRECTORY_DOWNLOADS + "/";

    private static final String WHERE_MIME_TYPE = MediaColumns.MIME_TYPE + " LIKE ? ";

    private static final String WHERE_RELATIVE_PATH_NOT =
            MediaColumns.RELATIVE_PATH + " NOT LIKE ?";

    private static final String WHERE_OWNER_PACKAGE_NAME_IS_NOT_NULL =
            MediaColumns.OWNER_PACKAGE_NAME + " IS NOT NULL";

    private static final String WHERE_BUCKET_ID_NOT_NULL = MediaColumns.BUCKET_ID + " IS NOT NULL";

    private static final String WHERE_RELATIVE_PATH_IS_NOT_SCREENSHOT_DIR =
            "NOT ( " + WHERE_RELATIVE_PATH_IS_SCREENSHOT_DIR + " )";

    private static final String WHERE_IS_NOT_DOWNLOAD = MediaColumns.IS_DOWNLOAD + " IS NOT 1";

    private static final String WHERE_RELATIVE_PATH_IS_DOWNLOAD = String.format(
            Locale.ROOT,
            "%s IS '%s'",
            MediaColumns.RELATIVE_PATH,
            RELATIVE_PATH_DOWNLOAD);

    private static final String WHERE_RELATIVE_PATH_IS_NOT_DOWNLOAD = String.format(
            Locale.ROOT,
            "NOT(%s)",
            WHERE_RELATIVE_PATH_IS_DOWNLOAD);

    private static final String WHERE_ROW_NUMBER_IS_ONE = String.format(
            Locale.ROOT,
            "%s.%s = 1",
            TABLE_SUBQUERY, COLUMN_ROW_NUMBER);

    private static final String WHERE_BUCKET_ID_IS = MediaColumns.BUCKET_ID + " IS ?";
    private static final String WHERE_OWNER_PACKAGE_NAME_IS =
            MediaColumns.OWNER_PACKAGE_NAME + " IS ?";

    // Include all the media items that are either downloaded
    // or are moved/copied to the "Download/" folder
    private static final String WHERE_IS_DOWNLOAD_MEDIA_SET = String.format(
            Locale.ROOT,
            "%s OR %s",
            WHERE_IS_DOWNLOAD,
            WHERE_RELATIVE_PATH_IS_DOWNLOAD);

    @VisibleForTesting
    static String[] LOCAL_ALBUM_IDS = {
            ALBUM_ID_CAMERA,
            ALBUM_ID_SCREENSHOTS,
            ALBUM_ID_DOWNLOADS
    };

    /**
     * Local album ids that are displayed along with other collections in the "Collections" tab in
     * the picker ui, when the feature flag
     * {@link Flags#FLAG_ENABLE_LOCAL_MEDIA_PROVIDER_CAPABILITIES} is enabled
     */
    private static final String[] COLLECTION_TAB_LOCAL_ALBUM_IDS = {
            ALBUM_ID_CAMERA,
            ALBUM_ID_SCREENSHOTS
    };

    private final Context mContext;
    private final DatabaseHelper mDatabaseHelper;
    private final VolumeCache mVolumeCache;

    public ExternalDbFacade(Context context, DatabaseHelper databaseHelper,
            VolumeCache volumeCache) {
        mContext = context;
        mDatabaseHelper = databaseHelper;
        mVolumeCache = volumeCache;
    }

    /**
     * Returns {@code true} if the PhotoPicker should be notified of this change, {@code false}
     * otherwise
     */
    public boolean onFileInserted(int mediaType, boolean isPending) {
        if (!mDatabaseHelper.isExternal()) {
            return false;
        }

        return !isPending && MimeUtils.isImageOrVideoMediaType(mediaType);
    }

    /**
     * Adds or removes media to the deleted_media tables
     *
     * Returns {@code true} if the PhotoPicker should be notified of this change, {@code false}
     * otherwise
     */
    public boolean onFileUpdated(long oldId, int oldMediaType, int newMediaType,
            boolean oldIsTrashed, boolean newIsTrashed, boolean oldIsPending,
            boolean newIsPending, boolean oldIsFavorite, boolean newIsFavorite,
            int oldSpecialFormat, int newSpecialFormat) {
        if (!mDatabaseHelper.isExternal()) {
            return false;
        }

        final boolean oldIsMedia = MimeUtils.isImageOrVideoMediaType(oldMediaType);
        final boolean newIsMedia = MimeUtils.isImageOrVideoMediaType(newMediaType);

        final boolean oldIsVisible = !oldIsTrashed && !oldIsPending;
        final boolean newIsVisible = !newIsTrashed && !newIsPending;

        final boolean oldIsVisibleMedia = oldIsVisible && oldIsMedia;
        final boolean newIsVisibleMedia = newIsVisible && newIsMedia;

        if (!oldIsVisibleMedia && newIsVisibleMedia) {
            // Was not visible media and is now visible media
            removeDeletedMedia(oldId);
            return true;
        } else if (oldIsVisibleMedia && !newIsVisibleMedia) {
            // Was visible media and is now not visible media
            addDeletedMedia(oldId);
            return true;
        }

        if (newIsVisibleMedia) {
            return (oldIsFavorite != newIsFavorite) || (oldSpecialFormat != newSpecialFormat);
        }


        // Do nothing, not an interesting change
        return false;
    }

    /**
     * Adds or removes media to the deleted_media tables
     *
     * Returns {@code true} if the PhotoPicker should be notified of this change, {@code false}
     * otherwise
     */
    public boolean onFileDeleted(long id, int mediaType) {
        if (!mDatabaseHelper.isExternal()) {
            return false;
        }
        if (!MimeUtils.isImageOrVideoMediaType(mediaType)) {
            return false;
        }

        addDeletedMedia(id);
        return true;
    }

    /**
     * Adds media with row id {@code oldId} to the deleted_media table. Returns {@code true} if
     * if it was successfully added, {@code false} otherwise.
     */
    @VisibleForTesting
    boolean addDeletedMedia(long oldId) {
        return mDatabaseHelper.runWithTransaction((db) -> {
            SQLiteQueryBuilder qb = createDeletedMediaQueryBuilder();

            ContentValues cv = new ContentValues();
            cv.put(COLUMN_OLD_ID, oldId);
            cv.put(COLUMN_GENERATION_MODIFIED, DatabaseHelper.getGeneration(db));

            try {
                return qb.insert(db, cv) > 0;
            } catch (SQLiteConstraintException e) {
                String select = COLUMN_OLD_ID + " = ?";
                String[] selectionArgs = new String[]{String.valueOf(oldId)};

                return qb.update(db, cv, select, selectionArgs) > 0;
            }
        });
    }

    /**
     * Removes media with row id {@code oldId} from the deleted_media table. Returns {@code true} if
     * it was successfully removed, {@code false} otherwise.
     */
    @VisibleForTesting
    boolean removeDeletedMedia(long oldId) {
        return mDatabaseHelper.runWithTransaction(db -> {
            SQLiteQueryBuilder qb = createDeletedMediaQueryBuilder();

            return qb.delete(db, COLUMN_OLD_ID + " = ?", new String[]{String.valueOf(oldId)}) > 0;
        });
    }

    /**
     * Returns all items from the deleted_media table.
     */
    public Cursor queryDeletedMedia(long generation) {
        final Cursor cursor = mDatabaseHelper.runWithTransaction(db -> {
            SQLiteQueryBuilder qb = createDeletedMediaQueryBuilder();
            String[] projection = new String[]{COLUMN_OLD_ID_AS_ID};
            String select = COLUMN_GENERATION_MODIFIED + " > ?";
            String[] selectionArgs = new String[]{String.valueOf(generation)};

            return qb.query(db, projection, select, selectionArgs,  /* groupBy */ null,
                    /* having */ null, /* orderBy */ null);
        });

        cursor.setExtras(getCursorExtras(generation, /* albumId */ null, /* pageSize */ -1,
                /* pageToken */ null));
        return cursor;
    }

    /**
     * Returns all items from the files table where {@link MediaColumns#GENERATION_MODIFIED}
     * is greater than {@code generation}.
     */
    public Cursor queryMedia(long generation, String albumId, String[] mimeTypes,
            int pageSize, String pageToken, int sortOrder) {
        final List<String> selectionArgs = new ArrayList<>();
        final String orderBy = getOrderByClause();

        Log.d(TAG, "Token received for queryMedia = " + pageToken);

        final Cursor cursor = mDatabaseHelper.runWithTransaction(db -> {
            SQLiteQueryBuilder qb = createMediaQueryBuilder();
            qb.appendWhereStandalone(WHERE_GREATER_GENERATION);
            selectionArgs.add(String.valueOf(generation));

            if (pageToken != null) {
                String[] lastMedia = parsePageToken(pageToken);
                if (lastMedia != null) {
                    qb.appendWhereStandalone(getDateTakenWhereClause());
                    addSelectionArgsForWhereClause(lastMedia, selectionArgs);
                }
            }

            selectionArgs.addAll(appendWhere(qb, albumId, mimeTypes));

            return qb.query(db, PROJECTION_MEDIA_COLUMNS, /* select */ null,
                    selectionArgs.toArray(new String[selectionArgs.size()]), /* groupBy */ null,
                    /* having */ null, orderBy, String.valueOf(pageSize));
        });

        String nextPageToken = null;
        if (cursor.getCount() > 0 && pageSize != INT_DEFAULT) {
            nextPageToken = setPageToken(cursor);

        }
        cursor.setExtras(getCursorExtras(generation, albumId, pageSize, nextPageToken));
        return cursor;
    }

    private static void addSelectionArgsForWhereClause(String[] lastMedia,
            List<String> selectionArgs) {
        selectionArgs.add(lastMedia[0]);
        selectionArgs.add(lastMedia[0]);
        selectionArgs.add(lastMedia[1]);
    }

    private static String[] parsePageToken(String pageToken) {
        String[] lastMedia = pageToken.split("\\|");

        if (lastMedia.length != 2) {
            Log.w(TAG, "Error parsing token in queryMedia.");
            return null;
        }
        return lastMedia;
    }

    private static String getDateTakenWhereClause() {
        return CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS + " IS NOT NULL AND "
                + WHERE_DATE_TAKEN_MILLIS_BEFORE;
    }

    private static String getOrderByClause() {
        return CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS + " DESC,"
                + CloudMediaProviderContract.MediaColumns.ID + " DESC";
    }

    private String setPageToken(Cursor mediaList) {
        String token = null;
        if (mediaList.moveToLast()) {
            String timeTakenMillis = getCursorString(mediaList,
                    CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS);
            String lastItemRowId = getCursorString(mediaList,
                    CloudMediaProviderContract.MediaColumns.ID);
            token = timeTakenMillis + "|" + lastItemRowId;
            mediaList.moveToFirst();
        }
        return token;
    }

    private Bundle getCursorExtras(long generation, String albumId, int pageSize,
            String pageToken) {
        final Bundle bundle = new Bundle();
        final ArrayList<String> honoredArgs = new ArrayList<>();

        if (generation > LONG_DEFAULT) {
            honoredArgs.add(EXTRA_SYNC_GENERATION);
        }
        if (!TextUtils.isEmpty(albumId)) {
            honoredArgs.add(EXTRA_ALBUM_ID);
        }

        if (pageSize > INT_DEFAULT) {
            honoredArgs.add(EXTRA_PAGE_SIZE);
        }

        if (pageToken != null) {
            honoredArgs.add(EXTRA_PAGE_TOKEN);
        }

        bundle.putString(EXTRA_MEDIA_COLLECTION_ID, getMediaCollectionId());
        if (pageToken != null) {
            bundle.putString(EXTRA_PAGE_TOKEN, pageToken);
        }
        bundle.putStringArrayList(EXTRA_HONORED_ARGS, honoredArgs);

        return bundle;
    }

    /**
     * Returns the total count and max {@link MediaColumns#GENERATION_MODIFIED} value
     * of the media items in the files table greater than {@code generation}.
     */
    private Cursor getMediaCollectionInfoCursor(long generation) {
        final String[] selectionArgs = new String[]{String.valueOf(generation)};
        final String[] projection = new String[]{
                MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION
        };

        return mDatabaseHelper.runWithTransaction(db -> {
            SQLiteQueryBuilder qbMedia = createMediaQueryBuilder();
            qbMedia.appendWhereStandalone(WHERE_GREATER_GENERATION);
            SQLiteQueryBuilder qbDeletedMedia = createDeletedMediaQueryBuilder();
            qbDeletedMedia.appendWhereStandalone(WHERE_GREATER_GENERATION);

            try (Cursor mediaCursor = query(qbMedia, db, PROJECTION_MEDIA_INFO, selectionArgs);
                    Cursor deletedMediaCursor =
                            query(qbDeletedMedia, db, PROJECTION_MEDIA_INFO, selectionArgs)) {
                final int mediaGenerationIndex = mediaCursor.getColumnIndexOrThrow(
                        MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);
                final int deletedMediaGenerationIndex =
                        deletedMediaCursor.getColumnIndexOrThrow(
                                MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);

                long mediaGeneration = 0;
                if (mediaCursor.moveToFirst()) {
                    mediaGeneration = mediaCursor.getLong(mediaGenerationIndex);
                }

                long deletedMediaGeneration = 0;
                if (deletedMediaCursor.moveToFirst()) {
                    deletedMediaGeneration = deletedMediaCursor.getLong(
                            deletedMediaGenerationIndex);
                }

                long maxGeneration = Math.max(mediaGeneration, deletedMediaGeneration);
                MatrixCursor result = new MatrixCursor(projection);
                result.addRow(new Long[]{maxGeneration});

                return result;
            }
        });
    }

    public Bundle getMediaCollectionInfo(long generation) {
        final Bundle bundle = new Bundle();
        try (Cursor cursor = getMediaCollectionInfoCursor(generation)) {
            if (cursor.moveToFirst()) {
                int generationIndex = cursor.getColumnIndexOrThrow(
                        MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);

                bundle.putString(MediaCollectionInfo.MEDIA_COLLECTION_ID, getMediaCollectionId());
                bundle.putLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION,
                        cursor.getLong(generationIndex));
            }
        }
        return bundle;
    }

    /**
     * Returns the media item categories from the files table.
     * Categories are determined with the {@link #LOCAL_ALBUM_IDS}.
     * If there are no media items under an albumId, the album is skipped from the results.
     */
    public Cursor queryAlbums(String[] mimeTypes, ConfigStore configStore) {
        final MatrixCursor c = new MatrixCursor(AlbumColumns.ALL_PROJECTION);

        String[] albumIds = LOCAL_ALBUM_IDS;
        if (configStore.isLocalCategoriesInPhotoPickerEnabled()) {
            albumIds = COLLECTION_TAB_LOCAL_ALBUM_IDS;
        }
        for (String albumId : albumIds) {
            Cursor cursor = mDatabaseHelper.runWithTransaction(db -> {
                final SQLiteQueryBuilder qb = createMediaQueryBuilder();
                final List<String> selectionArgs = new ArrayList<>();
                selectionArgs.addAll(appendWhere(qb, albumId, mimeTypes));

                return qb.query(db, PROJECTION_ALBUM_DB, /* selection */ null,
                        selectionArgs.toArray(new String[selectionArgs.size()]), /* groupBy */ null,
                        /* having */ null, /* orderBy */ null);
            });

            if (cursor == null || !cursor.moveToFirst()) {
                continue;
            }

            long count = getCursorLong(cursor, AlbumColumns.MEDIA_COUNT);
            if (count == 0) {
                continue;
            }

            final String[] projectionValue = new String[]{
                    /* albumId */ albumId,
                    getCursorString(cursor, AlbumColumns.DATE_TAKEN_MILLIS),
                    /* displayName */ getLocalizedDisplayName(albumId, mContext),
                    getCursorString(cursor, AlbumColumns.MEDIA_COVER_ID),
                    String.valueOf(count),
                    PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY
            };

            c.addRow(projectionValue);
        }

        return c;
    }

    private static Cursor query(SQLiteQueryBuilder qb, SQLiteDatabase db, String[] projection,
            String[] selectionArgs) {
        return qb.query(db, projection, /* select */ null, selectionArgs,
                /* groupBy */ null, /* having */ null, /* orderBy */ null);
    }

    private static List<String> appendWhere(SQLiteQueryBuilder qb, String albumId,
            String[] mimeTypes) {
        final List<String> selectionArgs = new ArrayList<>();

        addMimeTypesToQueryBuilderAndSelectionArgs(qb, selectionArgs, mimeTypes);

        if (albumId == null) {
            return selectionArgs;
        }

        switch (albumId) {
            case ALBUM_ID_CAMERA:
                qb.appendWhereStandalone(WHERE_RELATIVE_PATH);
                selectionArgs.add(RELATIVE_PATH_CAMERA);
                break;
            case ALBUM_ID_SCREENSHOTS:
                qb.appendWhereStandalone(WHERE_RELATIVE_PATH_IS_SCREENSHOT_DIR);
                break;
            case ALBUM_ID_DOWNLOADS:
                qb.appendWhereStandalone(WHERE_IS_DOWNLOAD);
                break;
            default:
                Log.w(TAG, "No match for album: " + albumId);
                break;
        }

        return selectionArgs;
    }

    private static void addMimeTypesToQueryBuilderAndSelectionArgs(SQLiteQueryBuilder qb,
            List<String> selectionArgs, String[] mimeTypes) {
        if (mimeTypes == null) {
            return;
        }

        mimeTypes = replaceMatchAnyChar(mimeTypes);
        ArrayList<String> whereMimeTypes = new ArrayList<>();
        for (String mimeType : mimeTypes) {
            if (!TextUtils.isEmpty(mimeType)) {
                whereMimeTypes.add(WHERE_MIME_TYPE);
                selectionArgs.add(mimeType);
            }
        }

        if (whereMimeTypes.isEmpty()) {
            return;
        }
        qb.appendWhereStandalone(TextUtils.join(" OR ", whereMimeTypes));
    }

    private static SQLiteQueryBuilder createDeletedMediaQueryBuilder() {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_DELETED_MEDIA);

        return qb;
    }

    private SQLiteQueryBuilder createMediaQueryBuilder() {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_FILES);
        qb.appendWhereStandalone(WHERE_MEDIA_TYPE);
        qb.appendWhereStandalone(WHERE_NOT_TRASHED);
        qb.appendWhereStandalone(WHERE_NOT_PENDING);

        // the file is corrupted if both datetaken and takenmodified are null.
        // hence exclude those files.
        qb.appendWhereStandalone(getDateTakenOrDateModifiedNonNull());

        String[] volumes = getVolumeList();
        if (volumes.length > 0) {
            qb.appendWhereStandalone(buildWhereVolumeIn(volumes));
        }

        return qb;
    }

    @Nullable
    private SQLiteQueryBuilder createMediaQueryBuilderForMediaSets(
            @CloudMediaProviderContract.MediaCategoryType String categoryType) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(TABLE_FILES);
        qb.appendWhereStandalone(WHERE_MEDIA_TYPE);
        qb.appendWhereStandalone(WHERE_NOT_TRASHED);
        qb.appendWhereStandalone(WHERE_NOT_PENDING);

        // the file is corrupted if both datetaken and date_modified are null.
        // hence exclude those files.
        qb.appendWhereStandalone(getDateTakenOrDateModifiedNonNull());

        String[] volumes = switch (categoryType) {
            case MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS -> getExternalPrimaryVolume();
            case MEDIA_CATEGORY_TYPE_SD_CARD -> getSdCardVolumeList();
            default -> getVolumeList();
        };
        if (volumes.length > 0) {
            qb.appendWhereStandalone(buildWhereVolumeIn(volumes));
        } else if (MEDIA_CATEGORY_TYPE_SD_CARD.equals(categoryType)) {
            Log.w(TAG, "No sd card volumes found.");
            return null;
        }

        return qb;
    }

    private CharSequence getDateTakenOrDateModifiedNonNull() {
        return MediaColumns.DATE_TAKEN + " IS NOT NULL OR "
                + MediaColumns.DATE_MODIFIED + " IS NOT NULL";
    }

    private String buildWhereVolumeIn(String[] volumes) {
        return String.format(WHERE_VOLUME_IN_PREFIX, bindList((Object[]) volumes));
    }

    private String[] getVolumeList() {
        String[] volumeNames = mVolumeCache.getExternalVolumeNames().toArray(new String[0]);
        Arrays.sort(volumeNames);

        return volumeNames;
    }

    @NonNull
    private String[] getExternalPrimaryVolume() {
        if (mVolumeCache.getExternalVolumeNames().contains(MediaStore.VOLUME_EXTERNAL_PRIMARY)) {
            return new String[] {MediaStore.VOLUME_EXTERNAL_PRIMARY};
        }
        return new String[] {};
    }

    @NonNull
    private String[] getSdCardVolumeList() {
        return mVolumeCache.getExternalVolumeNames().stream()
                .filter(volume -> !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume))
                .toArray(String[]::new);
    }

    private String getMediaCollectionId() {
        final String[] volumes = getVolumeList();
        if (volumes.length == 0) {
            return MediaStore.getVersion(mContext);
        }

        return MediaStore.getVersion(mContext) + ":" + TextUtils.join(":", getVolumeList());
    }

    /**
     * Queries media categories based on the provided MIME types.
     * This method retrieves cursor for the two local categories "From this device" collection
     * and "From your apps" collection.
     *
     * <p>The "From this device" collection includes:</p>
     * <ul>
     * <li>Media files marked as downloads ({@link #WHERE_IS_DOWNLOAD}).</li>
     * <li>Media files residing in device folders, excluding the Screenshots and Camera directories
     * </ul>
     *
     * <p>The "From your apps" collection includes:</p>
     * <ul>
     * <li>Media files owned by non-system applications
     * </ul>
     *
     * <p>The results are returned as a merged {@link Cursor} containing the category metadata.</p>
     *
     * @param mimeTypes An array of MIME types to filter the media files. If {@code null},
     *                  all media files ("image/*" and "video/*") are considered.
     * @param configStore The configuration used to access feature flags that determine
     *                    the query's behavior, such as including the SD card media category.
     * @return A {@link Cursor} containing the metadata of the collections.
     */
    @NonNull
    public Cursor queryMediaCategories(
            @Nullable String[] mimeTypes,
            @NonNull ConfigStore configStore
    ) {
        // Separate query for download media (is_download = 1).
        // This is necessary because downloads can reside in various file locations
        // and need to be included independently to bypass standard folder filters.
        final Cursor downloadCursor = getDownloadsMediaSet(mimeTypes);
        final Cursor deviceFoldersCursor =
                getLocalMediaSets(mimeTypes, /*pageSize*/ 4, /*pageToken*/ null,
                        MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS, configStore);
        Cursor deviceFolders = new MergeCursor(new Cursor[]{downloadCursor, deviceFoldersCursor});

        final Cursor appFolders =
                getLocalMediaSets(mimeTypes, /*pageSize*/ 4, /*pageToken*/null,
                        MEDIA_CATEGORY_TYPE_APP_FOLDERS, configStore);

        Cursor deviceFolderCategory =
                getCategoryFromFolderCursor(deviceFolders, MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS);
        Cursor appFolderCategory =
                getCategoryFromFolderCursor(appFolders, MEDIA_CATEGORY_TYPE_APP_FOLDERS);

        if (configStore.isSdCardCategoryInPhotoPickerEnabled()) {
            final Cursor sdCardFoldersCursor =
                    getLocalMediaSets(mimeTypes, /*pageSize*/ 4, /*pageToken*/ null,
                            MEDIA_CATEGORY_TYPE_SD_CARD, configStore);
            Cursor sdCardFolderCategory =
                    getCategoryFromFolderCursor(sdCardFoldersCursor, MEDIA_CATEGORY_TYPE_SD_CARD);
            return new MergeCursor(
                    new Cursor[]{deviceFolderCategory, sdCardFolderCategory, appFolderCategory});
        }

        return new MergeCursor(
                new Cursor[]{deviceFolderCategory, appFolderCategory});

    }

    /**
     * Queries and returns a cursor of media sets based on the specified category type,
     * applying appropriate filtering and pagination.
     *
     * <p>The behavior depends on the {@code mediaCategoryId}:</p>
     * <ul>
     * <li> "From this device" category: Returns a media sets representing local device folders.
     * If querying the first page (pageToken is null), this includes the Downloads folder first,
     * followed by other local device folders.
     * Camera folders and screenshots are always excluded from the local folders list.</li>
     *
     * <li> "From your apps" category: Returns media sets representing media owned by installed
     * applications. Folders related to Downloads, Camera and non-launchable applications
     * are excluded.</li>
     *
     * <li>Unrecognized Category ID: Logs an error and returns an empty cursor.</li>
     * </ul>
     *
     * @param mediaCategoryId The identifier for the desired category.
     *                        See {@code CloudMediaProviderContract} for specific constants.
     * @param mimeTypes       Optional array of MIME types to filter the media within sets
     *                        (e.g., "image/png", "video/mp4"). If null or empty, defaults to
     *                        including all image and video media types.
     * @param pageSize        The maximum number of media sets to return in this page.
     * @param pageToken       A token representing the starting point for the next page of results.
     * @param configStore The configuration used to access feature flags that determine
     *                    the query's behavior, such as including the SD card media category.
     * @return A {@link Cursor} containing the requested media sets, ordered appropriately.
     * Returns an empty cursor if the category ID is unrecognized or no matching sets are found.
     * The cursor should be closed after use.
     */
    @NonNull
    public Cursor queryMediaSets(
            @Nullable String mediaCategoryId,
            @Nullable String[] mimeTypes,
            int pageSize,
            @Nullable String pageToken,
            @NonNull ConfigStore configStore) {
        try {
            Cursor cursor;
            if (MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS.equals(mediaCategoryId)) {
                Cursor downloadCursor = null;
                if (pageToken == null) {
                    downloadCursor = getDownloadsMediaSet(mimeTypes);
                    if (downloadCursor.getCount() > 0) {
                        pageSize = pageSize - 1;
                    }
                }
                final Cursor deviceFoldersCursor =
                        getLocalMediaSets(mimeTypes, pageSize, pageToken,
                                MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS, configStore);
                if (downloadCursor == null) {
                    cursor = deviceFoldersCursor;
                } else {
                    cursor = new MergeCursor(new Cursor[]{downloadCursor, deviceFoldersCursor});
                    cursor.setExtras(deviceFoldersCursor.getExtras());
                }
            } else if (MEDIA_CATEGORY_TYPE_APP_FOLDERS.equals(mediaCategoryId)) {
                cursor = getLocalMediaSets(mimeTypes, pageSize, pageToken,
                        MEDIA_CATEGORY_TYPE_APP_FOLDERS, configStore);
            } else if (configStore.isSdCardCategoryInPhotoPickerEnabled()
                    && MEDIA_CATEGORY_TYPE_SD_CARD.equals(mediaCategoryId)) {
                cursor = getLocalMediaSets(mimeTypes, pageSize, pageToken,
                        MEDIA_CATEGORY_TYPE_SD_CARD, configStore);
            } else {
                Log.e(TAG, "Found unrecognized mediaCategoryId: " + mediaCategoryId);
                cursor = new MatrixCursor(MediaSetColumns.ALL_PROJECTION);
            }
            return cursor;
        } catch (Exception exception) {
            Log.e(TAG, String.format(Locale.ROOT,
                            "Query to get media sets could not complete for: "
                                    + "mediaCategoryId = %s, mimeTypes = %s, pageSize = %s, "
                                    + "pageToken = %s",
                            mediaCategoryId,
                            Arrays.toString(mimeTypes),
                            pageSize,
                            pageToken),
                    exception);
            Log.d(TAG, "Returning empty cursor");
            return new MatrixCursor(MediaSetColumns.ALL_PROJECTION);
        }
    }

    /**
     * Retrieves a paginated and sorted list of media items belonging to a specified media set.
     *
     * @param mediaSetId The unique identifier for the media set from which to query media.
     * @param mimeTypes  Optional array of MIME types to filter the media within sets
     *                   (e.g., "image/png", "video/mp4"). If null or empty, defaults to
     *                   including all image and video media types.
     * @param pageSize   The maximum number of media sets to return in this page.
     * @param pageToken  A token representing the starting point for the next page of results.
     * @param sortOrder  An integer constant defining the sorting criteria for the returned media.
     * @param configStore The configuration used to access feature flags that determine
     *                    the query's behavior, such as including the SD card media category.
     * @return A {@link Cursor} containing the queried media items, matching the specified criteria.
     */
    @NonNull
    public Cursor queryMediaInMediaSet(
            @Nullable String mediaSetId,
            @Nullable String[] mimeTypes,
            int pageSize,
            @Nullable String pageToken,
            int sortOrder,
            @NonNull ConfigStore configStore) {
        final List<String> selectionArgs = new ArrayList<>();
        final String orderBy = getOrderByClauseForMediaInMediaSet(sortOrder);

        Log.d(TAG, "Token received for queryMediaInMediaSet = " + pageToken);

        String categoryType;
        if (mediaSetId != null) {
            try {
                categoryType = mediaSetId.split(":")[0];
            } catch (RuntimeException exception) {
                Log.e(TAG, "Error while getting the category type for media set: " + mediaSetId,
                        exception);
                return new MatrixCursor(PROJECTION_MEDIA_COLUMNS);
            }
        } else {
            Log.e(TAG, "Received null mediaSetId, returning empty cursor.");
            return new MatrixCursor(PROJECTION_MEDIA_COLUMNS);
        }

        final Cursor cursor = mDatabaseHelper.runWithTransaction(db -> {
            SQLiteQueryBuilder qb = createMediaQueryBuilder();
            if (configStore.isSdCardCategoryInPhotoPickerEnabled() && categoryType != null) {
                qb = createMediaQueryBuilderForMediaSets(categoryType);
            }

            if (pageToken != null) {
                String[] lastMedia = parsePageToken(pageToken);
                if (lastMedia != null) {
                    qb.appendWhereStandalone(getDateTakenWhereClause());
                    addSelectionArgsForWhereClause(lastMedia, selectionArgs);
                }
            }

            List<String> mediaSetSelectionArgs = appendWhereForMediaSets(qb, mediaSetId, mimeTypes);
            if (mediaSetSelectionArgs == null) {
                return new MatrixCursor(PROJECTION_MEDIA_COLUMNS);
            }
            selectionArgs.addAll(mediaSetSelectionArgs);

            return qb.query(db, PROJECTION_MEDIA_COLUMNS, /* select */ null,
                    selectionArgs.toArray(new String[selectionArgs.size()]), /* groupBy */ null,
                    /* having */ null, orderBy, String.valueOf(pageSize));
        });

        String nextPageToken = null;
        if (cursor.getCount() > 0 && pageSize != INT_DEFAULT) {
            nextPageToken = setPageToken(cursor);

        }
        cursor.setExtras(getCursorExtrasForMediaSet(nextPageToken, pageSize, sortOrder, mimeTypes));
        return cursor;
    }

    @NonNull
    private Cursor getDownloadsMediaSet(@Nullable String[] mimeTypes) {
        final MatrixCursor downloadMediaSet = new MatrixCursor(MediaSetColumns.ALL_PROJECTION);
        final String orderBy = getMediaSetOrderByClause();

        try (Cursor downloadCursor = mDatabaseHelper.runWithoutTransaction(db -> {
            final SQLiteQueryBuilder qb = createMediaQueryBuilder();
            final List<String> selectionArgs =
                    appendWhereForMediaSets(qb, null, mimeTypes);
            qb.appendWhereStandalone(WHERE_IS_DOWNLOAD_MEDIA_SET);
            return qb.query(db, PROJECTION_DOWNLOADS_FOLDER, /* selection */ null,
                    selectionArgs.toArray(new String[selectionArgs.size()]),
                    /* groupBy */ null, /* having */ null, orderBy, /* limit */ "1");
        })) {
            if (!downloadCursor.moveToFirst()) {
                return downloadMediaSet;
            }
            Long count = 0L;
            try (Cursor countCursor = mDatabaseHelper.runWithoutTransaction(db ->
                    db.rawQuery(getDownloadCursorCountQuery(), null))) {
                if (countCursor.moveToFirst()) {
                    count = countCursor.getLong(
                            countCursor.getColumnIndexOrThrow(MediaSetColumns.MEDIA_COUNT));
                }
            }
            if (count == 0) {
                return downloadMediaSet;
            }

            final String[] projectionValue = new String[]{
                    /*mediaSetId*/ String.format(
                    Locale.ROOT,
                    "%s:%s",
                    MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                    ALBUM_ID_DOWNLOADS),
                    /*displayName*/ getLocalizedDisplayName(
                    ALBUM_ID_DOWNLOADS, mContext),
                    /*mediaCount*/ String.valueOf(count),
                    /*mediaCoverId*/ getCursorString(downloadCursor,
                    MediaSetColumns.MEDIA_COVER_ID)
            };
            downloadMediaSet.addRow(projectionValue);
            return downloadMediaSet;
        }

    }

    private static String getDownloadCursorCountQuery() {
        return String.format(Locale.ROOT, "SELECT COUNT(*) AS %s FROM %s WHERE (%s OR %s)",
                MediaSetColumns.MEDIA_COUNT,
                TABLE_FILES,
                WHERE_IS_DOWNLOAD,
                WHERE_RELATIVE_PATH_IS_DOWNLOAD);
    }

    @NonNull
    private Cursor getLocalMediaSets(
            @Nullable String[] mimeTypes,
            int pageSize,
            @Nullable String pageToken,
            @CloudMediaProviderContract.MediaCategoryType String categoryType,
            @NonNull ConfigStore configStore) {
        final List<String> selectionArgs = new ArrayList<>();
        final String orderBy = getMediaSetOrderByClause();
        final String[] projection = switch (categoryType) {
            case MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS -> PROJECTION_DEVICE_MEDIA_SET;
            case MEDIA_CATEGORY_TYPE_APP_FOLDERS -> PROJECTION_APPS_MEDIA_SET;
            case MEDIA_CATEGORY_TYPE_SD_CARD -> PROJECTION_SD_CARD_MEDIA_SET;
            default -> null;
        };
        // return an empty cursor if the projection is null
        if (projection == null) {
            Log.e(TAG, String.format(
                    "Returning an empty cursor as unrecognized media category type received: %s",
                    categoryType));
            return new MatrixCursor(MediaSetColumns.ALL_PROJECTION);
        }

        final Cursor cursor = mDatabaseHelper.runWithoutTransaction(db -> {
            final SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
            final String subQuery = getMediaSetSubQuery(categoryType, selectionArgs, mimeTypes,
                    configStore);

            // return an empty cursor if the sub query is null
            if (subQuery == null) {
                return new MatrixCursor(projection);
            }
            queryBuilder.setTables(subQuery);
            queryBuilder.appendWhereStandalone(WHERE_ROW_NUMBER_IS_ONE);
            if (pageToken != null) {
                String[] lastMediaSet = parsePageToken(pageToken);
                if (lastMediaSet != null) {
                    queryBuilder.appendWhereStandalone(getDateTakenWhereClause());
                    addSelectionArgsForWhereClause(lastMediaSet, selectionArgs);
                }
            }
            try {
                return queryBuilder.query(db, projection, /* selection */ null,
                        selectionArgs.toArray(new String[selectionArgs.size()]), /* groupBy */ null,
                        /* having */ null, orderBy, /* limit */ String.valueOf(pageSize));
            } catch (SQLiteException exception) {
                Log.e(TAG, "The SQLite query could not complete.", exception);
                return new MatrixCursor(projection);
            }
        });

        String nextPageToken = null;
        if (cursor.getCount() > 0 && pageSize != INT_DEFAULT) {
            nextPageToken = setPageToken(cursor);
        }

        cursor.setExtras(getCursorExtrasForMediaSet(nextPageToken, pageSize,
                /* sortOrder */ INT_DEFAULT, mimeTypes));

        return createMediaSetCursor(cursor, categoryType);
    }

    private Cursor createMediaSetCursor(
            @NonNull Cursor cursor,
            @CloudMediaProviderContract.MediaCategoryType String categoryType) {
        final MatrixCursor mediaSetCursor = new MatrixCursor(PROJECTION_MEDIA_SET);
        final Bundle extras = cursor.getExtras();
        if (extras != null) {
            mediaSetCursor.setExtras(extras);
        }

        if (!cursor.moveToFirst()) {
            return mediaSetCursor; // Return empty cursor if source is empty
        }

        do {
            try {
                final ContentValues values = new ContentValues();
                DatabaseUtils.cursorRowToContentValues(cursor, values);

                String displayName = values.getAsString(MediaSetColumns.DISPLAY_NAME);

                if (displayName == null || displayName.isEmpty()) {
                    displayName = switch (categoryType) {
                        // TODO(b/429580294): Replace the string resource with the finalized name.
                        case MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS ->
                                mContext.getResources().getString(R.string.storage_description);
                        case MEDIA_CATEGORY_TYPE_APP_FOLDERS -> getAppDisplayName(cursor);
                        case MEDIA_CATEGORY_TYPE_SD_CARD -> mContext.getResources().getString(
                                R.string.sd_card_album_display_name);
                        default -> throw new IllegalArgumentException(
                                        "Unsupported category type provided: " + categoryType);
                    };
                    values.put(MediaSetColumns.DISPLAY_NAME, displayName);
                }

                final Object[] newRow = new Object[PROJECTION_MEDIA_SET.length];
                for (int i = 0; i < PROJECTION_MEDIA_SET.length; i++) {
                    newRow[i] = values.get(PROJECTION_MEDIA_SET[i]);
                }
                mediaSetCursor.addRow(newRow);

            } catch (RuntimeException | PackageManager.NameNotFoundException exception) {
                Log.e(TAG,
                        String.format(
                                Locale.ROOT,
                                "Failed to process a media set item of category %s. Skipping.",
                                categoryType),
                        exception);
            }
        } while (cursor.moveToNext());

        return mediaSetCursor;
    }

    @NonNull
    private String getAppDisplayName(@NonNull Cursor cursor)
            throws IllegalArgumentException, PackageManager.NameNotFoundException {
        String ownerPackageName = getCursorString(cursor, MediaColumns.OWNER_PACKAGE_NAME);
        if (ownerPackageName == null) {
            throw new IllegalArgumentException("Received NULL owner package name from cursor.");
        }

        final PackageManager packageManager = mContext.getPackageManager();
        final ApplicationInfo applicationInfo = packageManager
                .getApplicationInfo(ownerPackageName, 0);
        return packageManager.getApplicationLabel(applicationInfo).toString();
    }

    @Nullable
    private String getMediaSetSubQuery(
            @CloudMediaProviderContract.MediaCategoryType String categoryType,
            @NonNull List<String> selectionArgs,
            @Nullable String[] mimeTypes,
            @NonNull ConfigStore configStore) {
        SQLiteQueryBuilder subQueryBuilder = createMediaQueryBuilder();
        if (configStore.isSdCardCategoryInPhotoPickerEnabled()) {
            // With SD card category enabled the list of volume the query should run for depends on
            // the category type
            subQueryBuilder = createMediaQueryBuilderForMediaSets(categoryType);
        }
        if (subQueryBuilder == null) {
            return null;
        }
        selectionArgs.addAll(appendWhereForMediaSets(subQueryBuilder, null, mimeTypes));

        final String subQueryString = switch (categoryType) {
            case MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS ->
                    appendWhereForDeviceMediaSet(subQueryBuilder, selectionArgs);
            case MEDIA_CATEGORY_TYPE_APP_FOLDERS ->
                    appendWhereForAppsMediaSet(subQueryBuilder, selectionArgs);
            case MEDIA_CATEGORY_TYPE_SD_CARD ->
                buildSubQueryForSdCardMediaSet(subQueryBuilder);
            default -> {
                Log.e(TAG, "Unrecognized media category type received: " + categoryType);
                yield null;
            }
        };
        if (subQueryString == null) {
            return null;
        }
        return String.format(
                Locale.ROOT,
                "(%s) AS %s",
                subQueryString,
                TABLE_SUBQUERY);
    }

    @Nullable
    private static List<String> appendWhereForMediaSets(
            @NonNull SQLiteQueryBuilder qb,
            @Nullable String mediaSetId,
            @Nullable String[] mimeTypes) {
        final List<String> selectionArgs = new ArrayList<>();

        addMimeTypesToQueryBuilderAndSelectionArgs(qb, selectionArgs, mimeTypes);

        if (mediaSetId == null) {
            return selectionArgs;
        }

        try {
            String[] mediaSetIdSplit = mediaSetId.split(":");
            String categoryType = mediaSetIdSplit[0];
            String id = mediaSetIdSplit[1];
            switch (categoryType) {
                case MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS -> {
                    if (ALBUM_ID_DOWNLOADS.equals(id)) {
                        qb.appendWhereStandalone(WHERE_IS_DOWNLOAD_MEDIA_SET);
                    } else {
                        qb.appendWhereStandalone(WHERE_BUCKET_ID_IS);
                        selectionArgs.add(id);
                    }
                }
                case MEDIA_CATEGORY_TYPE_APP_FOLDERS -> {
                    qb.appendWhereStandalone(WHERE_OWNER_PACKAGE_NAME_IS);
                    selectionArgs.add(id);
                }
                case MEDIA_CATEGORY_TYPE_SD_CARD -> {
                    qb.appendWhereStandalone(WHERE_BUCKET_ID_IS);
                    selectionArgs.add(id);
                }
                default -> {
                    Log.w(TAG, "No match for category type: " + categoryType);
                    return null;
                }
            }
        } catch (Exception exception) {
            Log.e(TAG, "Error occurred while appending where clause for " + mediaSetId,
                    exception);
            return null;
        }

        return selectionArgs;
    }

    @NonNull
    private String appendWhereForDeviceMediaSet(
            @NonNull SQLiteQueryBuilder subQueryBuilder,
            @NonNull List<String> selectionArgs) {
        subQueryBuilder.appendWhereStandalone(WHERE_BUCKET_ID_NOT_NULL);
        subQueryBuilder.appendWhereStandalone(WHERE_RELATIVE_PATH_IS_NOT_SCREENSHOT_DIR);
        subQueryBuilder.appendWhereStandalone(WHERE_RELATIVE_PATH_IS_NOT_DOWNLOAD);
        subQueryBuilder.appendWhereStandalone(WHERE_RELATIVE_PATH_NOT);
        selectionArgs.add(RELATIVE_PATH_CAMERA);
        return subQueryBuilder.buildQuery(
                PROJECTION_DEVICE_MEDIA_SET_SUBQUERY,
                /* selection */ null,
                /* groupBy */ null,
                /* having */ null,
                /* sortOrder */ null,
                /* limit */ null
        );
    }

    @NonNull
    private String buildSubQueryForSdCardMediaSet(@NonNull SQLiteQueryBuilder subQueryBuilder) {
        return subQueryBuilder.buildQuery(
                PROJECTION_DEVICE_MEDIA_SET_SUBQUERY,
                /* selection */ null,
                /* groupBy */ null,
                /* having */ null,
                /* sortOrder */ null,
                /* limit */ null
        );
    }

    @NonNull
    private String appendWhereForAppsMediaSet(
            @NonNull SQLiteQueryBuilder subQueryBuilder,
            @NonNull List<String> selectionArgs) {
        subQueryBuilder.appendWhereStandalone(WHERE_OWNER_PACKAGE_NAME_IS_NOT_NULL);
        subQueryBuilder.appendWhereStandalone(WHERE_IS_NOT_DOWNLOAD);
        Set<String> launchableOwnerPackageNameSet = getLaunchableOwnerPackageNameSet();
        if (!launchableOwnerPackageNameSet.isEmpty()) {
            String inClause = String.format(Locale.ROOT,
                    "%s IN (%s)",
                    MediaColumns.OWNER_PACKAGE_NAME,
                    TextUtils.join(
                            ",",
                            Collections.nCopies(launchableOwnerPackageNameSet.size(), "?")));

            subQueryBuilder.appendWhereStandalone(inClause);

            // Add each item from the set as a separate selection argument
            selectionArgs.addAll(launchableOwnerPackageNameSet);
        } else {
            // Return an null subQuery
            return null;
        }
        return subQueryBuilder.buildQuery(
                PROJECTION_APPS_MEDIA_SET_SUBQUERY,
                /* selection */ null,
                /* groupBy */ null,
                /* having */ null,
                /* sortOrder */ null,
                /* limit */ null
        );
    }

    @NonNull
    private Set<String> getLaunchableOwnerPackageNameSet() {
        List<LauncherActivityInfo> launcherActivityInfos = null;
        Set<String> launchableOwnerPackageNameSet = new HashSet<>();
        try {
            LauncherApps launcherApps = mContext.getSystemService(LauncherApps.class);

            UserHandle userHandle = mContext.getUser();

            if (launcherApps != null) {
                launcherActivityInfos = launcherApps.getActivityList(null, userHandle);
            } else {
                Log.e(TAG, "LauncherApps service was null.");
                return launchableOwnerPackageNameSet;
            }

        } catch (RuntimeException e) {
            Log.e(TAG, "Error getting launcher activity infos.", e);
            return launchableOwnerPackageNameSet;
        }

        for (LauncherActivityInfo info : launcherActivityInfos) {
            if (info != null) {
                ComponentName componentName = info.getComponentName();
                if (componentName != null) {
                    String ownerPackageName = componentName.getPackageName();
                    launchableOwnerPackageNameSet.add(ownerPackageName);
                } else {
                    Log.w(TAG, "LauncherActivityInfo had a null ComponentName.");
                }
            } else {
                Log.w(TAG, "Encountered a null LauncherActivityInfo in the list.");
            }
        }
        return launchableOwnerPackageNameSet;
    }

    @NonNull
    private static String getMediaSetOrderByClause() {
        return CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS + " DESC, "
                + MediaColumns._ID + " DESC";
    }

    @NonNull
    private static String getOrderByClauseForMediaInMediaSet(int sortOrder) {
        // Currently sortOrder can only be SORT_ORDER_DESC_DATE_TAKEN
        if (sortOrder != CloudMediaProviderContract.SORT_ORDER_DESC_DATE_TAKEN) {
            Log.e(TAG, "Received incorrect sort order: " + sortOrder);
        }
        return CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS + " DESC,"
                + CloudMediaProviderContract.MediaColumns.ID + " DESC";
    }

    @NonNull
    private Bundle getCursorExtrasForMediaSet(String pageToken, int pageSize, int sortOrder,
            String[] mimeTypes) {
        final Bundle bundle = new Bundle();
        final ArrayList<String> honoredArgs = new ArrayList<>();

        if (pageSize > INT_DEFAULT) {
            honoredArgs.add(EXTRA_PAGE_SIZE);
        }

        if (pageToken != null) {
            honoredArgs.add(EXTRA_PAGE_TOKEN);
            bundle.putString(EXTRA_PAGE_TOKEN, pageToken);
        }

        if (sortOrder == CloudMediaProviderContract.SORT_ORDER_DESC_DATE_TAKEN) {
            honoredArgs.add(EXTRA_SORT_ORDER);
        }

        if (mimeTypes != null && mimeTypes.length != 0) {
            honoredArgs.add(Intent.EXTRA_MIME_TYPES);
        }

        bundle.putStringArrayList(EXTRA_HONORED_ARGS, honoredArgs);

        return bundle;
    }

    @Nullable
    private static String getLocalizedDisplayName(
            @Nullable String displayName,
            @Nullable Context appContext) {
        if (displayName == null || appContext == null) {
            return null;
        }
        Resources resources = appContext.getResources();
        switch (displayName) {
            case MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS -> {
                return resources.getString(R.string.device_folders_collection_display_name);
            }
            case MEDIA_CATEGORY_TYPE_APP_FOLDERS -> {
                return resources.getString(R.string.app_folders_collection_display_name);
            }
            case MEDIA_CATEGORY_TYPE_SD_CARD -> {
                return resources.getString(R.string.sd_card_collection_display_name);
            }
            case ALBUM_ID_CAMERA -> {
                return resources.getString(R.string.camera_album_display_name);
            }
            case ALBUM_ID_SCREENSHOTS -> {
                return resources.getString(R.string.screenshots_album_display_name);
            }
            case ALBUM_ID_DOWNLOADS -> {
                return resources.getString(R.string.downloads_album_display_name);
            }
            default -> {
                // if not a known case return the original display name
                return displayName;
            }
        }
    }

    @NonNull
    private Cursor getCategoryFromFolderCursor(
            @Nullable Cursor folder,
            @CloudMediaProviderContract.MediaCategoryType String mediaCategoryType) {
        final MatrixCursor cursor = new MatrixCursor(MediaCategoryColumns.ALL_PROJECTION);

        if (folder == null || !folder.moveToFirst()) {
            return cursor;
        }

        final MatrixCursor.RowBuilder row = cursor.newRow();
        final List<String> coverIdColumnNames = new ArrayList<>(Arrays.asList(
                MediaCategoryColumns.MEDIA_COVER_ID1,
                MediaCategoryColumns.MEDIA_COVER_ID2,
                MediaCategoryColumns.MEDIA_COVER_ID3,
                MediaCategoryColumns.MEDIA_COVER_ID4));
        folder.moveToFirst();
        for (int i = 0; i < Math.min(folder.getCount(), coverIdColumnNames.size()); ++i) {
            String columnName = coverIdColumnNames.get(i);
            if (MEDIA_CATEGORY_TYPE_APP_FOLDERS.equals(mediaCategoryType)) {
                String mediaCoverId = getAppIconCoverId(
                        getCursorString(folder, MediaSetColumns.ID));
                row.add(columnName, mediaCoverId);
            } else {
                row.add(columnName, getCursorString(folder, MediaSetColumns.MEDIA_COVER_ID));
            }
            folder.moveToNext();
        }
        row.add(MediaCategoryColumns.DISPLAY_NAME, getLocalizedDisplayName(
                mediaCategoryType, mContext));
        row.add(MediaCategoryColumns.ID, mediaCategoryType);
        row.add(MediaCategoryColumns.MEDIA_CATEGORY_TYPE, mediaCategoryType);

        return cursor;
    }

    @Nullable
    private String getAppIconCoverId(@Nullable String mediaSetId) {
        String coverId = null;
        if (mediaSetId == null) {
            return coverId;
        }
        try {
            // mediaSetId for an app media set is of the form "<category_type>:<owner_package_name>"
            String ownerPackageName = mediaSetId.split(":")[1];
            PackageManager packageManager = mContext.getPackageManager();
            try {
                ApplicationInfo applicationInfo = packageManager
                        .getApplicationInfo(ownerPackageName, /* flags = */0);
                int appIconResId = applicationInfo.icon;
                // cover id of the form "<package_name>/<res_id>"
                coverId = String.format(
                        Locale.ROOT,
                        "%s/%s",
                        ownerPackageName,
                        appIconResId);
            } catch (PackageManager.NameNotFoundException exception) {
                Log.e(TAG, String.format(
                                Locale.ROOT,
                                "Package info not found for %s",
                                ownerPackageName),
                        exception);
            } catch (RuntimeException exception) {
                Log.e(TAG, String.format(
                                Locale.ROOT,
                                "Error getting package info for %s",
                                ownerPackageName),
                        exception);
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, String.format(
                            Locale.ROOT,
                            "Error getting cover id for media set %s",
                            mediaSetId),
                    exception);
        }
        return coverId;
    }
}
