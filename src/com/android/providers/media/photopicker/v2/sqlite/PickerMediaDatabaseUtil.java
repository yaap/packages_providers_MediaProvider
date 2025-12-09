/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.media.photopicker.v2.sqlite;

import static com.android.providers.media.MediaProvider.isOwnedPhotosEnabled;
import static com.android.providers.media.photopicker.PickerSyncController.getPackageNameFromUid;
import static com.android.providers.media.photopicker.v2.PickerDataLayerV2.getDefaultEmptyAlbum;
import static com.android.providers.media.photopicker.v2.sqlite.MediaProjection.prependTableName;

import static java.lang.Math.max;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.R;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.v2.model.AlbumMediaQuery;
import com.android.providers.media.photopicker.v2.model.AlbumsCursorWrapper;
import com.android.providers.media.photopicker.v2.model.FavoritesMediaQuery;
import com.android.providers.media.photopicker.v2.model.MediaPageKeyQuery;
import com.android.providers.media.photopicker.v2.model.MediaQuery;
import com.android.providers.media.photopicker.v2.model.VideoMediaQuery;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Utility class for querying the Picker DB to fetch media metadata.
 */
public class PickerMediaDatabaseUtil {
    private static final String TAG = "PickerMediaDBUtil";

    /**
     * Query media from the database and prepare a cursor in response.
     *
     * We need to make multiple queries to prepare a response for the media query.
     * {@link android.database.sqlite.SQLiteQueryBuilder} currently does not support the creation of
     * a transaction in {@code DEFERRED} mode. This is why we'll perform the read queries in
     * {@code IMMEDIATE} mode instead.
     *
     * @param appContext The application context.
     * @param syncController Instance of the PickerSyncController singleton.
     * @param query The MediaQuery object instance that tells us about the media query args.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     * @param cloudAuthority The effective cloud authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would
     *                       be null.
     * @return The cursor with the album media query results.
     */
    @NonNull
    public static Cursor queryMedia(
            @NonNull Context appContext,
            @NonNull PickerSyncController syncController,
            @NonNull MediaQuery query,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {
        try {
            final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

            try {
                database.beginTransactionNonExclusive();

                Cursor pageData = database.rawQuery(
                        getMediaPageQuery(
                                appContext,
                                query,
                                database,
                                PickerSQLConstants.Table.MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                Bundle extraArgs = new Bundle();
                Cursor nextPageKeyCursor = database.rawQuery(
                        getMediaNextPageKeyQuery(
                                appContext,
                                query,
                                database,
                                PickerSQLConstants.Table.MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                addNextPageKey(extraArgs, nextPageKeyCursor);

                Cursor prevPageKeyCursor = database.rawQuery(
                        getMediaPreviousPageQuery(
                                appContext,
                                query,
                                database,
                                PickerSQLConstants.Table.MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                addPrevPageKey(extraArgs, prevPageKeyCursor);

                if (query.shouldPopulateItemsBeforeCount()) {
                    Cursor itemsBeforeCountCursor = database.rawQuery(
                            getMediaItemsBeforeCountQuery(
                                    appContext,
                                    query,
                                    database,
                                    PickerSQLConstants.Table.MEDIA,
                                    localAuthority,
                                    cloudAuthority
                            ),
                            /* selectionArgs */ null
                    );
                    addItemsBeforeCountKey(extraArgs, itemsBeforeCountCursor);
                }

                if (query.shouldPopulateItemsAfterCount()) {
                    Cursor itemsAfterCountCursor = database.rawQuery(
                            getMediaItemsAfterCountIncludingCurrentPageQuery(
                                    appContext,
                                    query,
                                    database,
                                    PickerSQLConstants.Table.MEDIA,
                                    localAuthority,
                                    cloudAuthority
                            ),
                            /* selectionArgs */ null
                    );
                    addItemsAfterCountKey(extraArgs, itemsAfterCountCursor, query);
                }

                if (database.inTransaction()) {
                    database.setTransactionSuccessful();
                }
                pageData.setExtras(extraArgs);
                Log.i(TAG, "Returning " + pageData.getCount() + " media metadata");
                return pageData;
            } finally {
                if (database.inTransaction()) {
                    database.endTransaction();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch media", e);
        }
    }


    /**
     * @param appContext The application context.
     * @param syncController Instance of the PickerSyncController singleton.
     * @param query The AlbumMediaQuery object instance that tells us about the media query args.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     * @param cloudAuthority The effective cloud authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would
     *                       be null.
     * @return The cursor with the album media query results.
     */
    @NonNull
    public static Cursor queryAlbumMedia(
            @NonNull Context appContext,
            @NonNull PickerSyncController syncController,
            @NonNull AlbumMediaQuery query,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {
        try {
            final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

            try {
                database.beginTransactionNonExclusive();
                Cursor pageData = database.rawQuery(
                        getMediaPageQuery(
                                appContext,
                                query,
                                database,
                                PickerSQLConstants.Table.ALBUM_MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );

                Bundle extraArgs = new Bundle();
                Cursor nextPageKeyCursor = database.rawQuery(
                        getMediaNextPageKeyQuery(
                                appContext,
                                query,
                                database,
                                PickerSQLConstants.Table.ALBUM_MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                addNextPageKey(extraArgs, nextPageKeyCursor);

                Cursor prevPageKeyCursor = database.rawQuery(
                        getMediaPreviousPageQuery(
                                appContext,
                                query,
                                database,
                                PickerSQLConstants.Table.ALBUM_MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                addPrevPageKey(extraArgs, prevPageKeyCursor);

                if (query.shouldPopulateItemsBeforeCount()) {
                    Cursor itemsBeforeCountCursor = database.rawQuery(
                            getMediaItemsBeforeCountQuery(
                                    appContext,
                                    query,
                                    database,
                                    PickerSQLConstants.Table.ALBUM_MEDIA,
                                    localAuthority,
                                    cloudAuthority
                            ),
                            /* selectionArgs */ null
                    );
                    addItemsBeforeCountKey(extraArgs, itemsBeforeCountCursor);
                }

                if (query.shouldPopulateItemsAfterCount()) {
                    Cursor itemsAfterCountCursor = database.rawQuery(
                            getMediaItemsAfterCountIncludingCurrentPageQuery(
                                    appContext,
                                    query,
                                    database,
                                    PickerSQLConstants.Table.ALBUM_MEDIA,
                                    localAuthority,
                                    cloudAuthority
                            ),
                            /* selectionArgs */ null
                    );
                    addItemsAfterCountKey(extraArgs, itemsAfterCountCursor, query);
                }

                if (database.inTransaction()) {
                    database.setTransactionSuccessful();
                }

                pageData.setExtras(extraArgs);
                Log.i(TAG, "Returning " + pageData.getCount() + " album media items for album "
                        + query.getAlbumId());
                return pageData;
            } finally {
                if (database.inTransaction()) {
                    database.endTransaction();
                }
            }


        } catch (Exception e) {
            throw new RuntimeException("Could not fetch media", e);
        }
    }

    /**
     * Query media from the database filtered by pre-selection uris and prepare a cursor in
     * response.
     *
     * @param appContext The application context.
     * @param syncController Instance of the PickerSyncController singleton.
     * @param query The MediaQuery object instance that tells us about the media query args.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     * @param cloudAuthority The effective cloud authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would
     *                       be null.
     * @return The cursor with the album media query results.
     */
    @NonNull
    public static Cursor queryPreSelectedMedia(
            @NonNull Context appContext,
            @NonNull PickerSyncController syncController,
            @NonNull MediaQuery query,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {

        final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

        try {
            Cursor pageData = database.rawQuery(
                    getMediaPageQuery(
                            appContext,
                            query,
                            database,
                            PickerSQLConstants.Table.MEDIA,
                            localAuthority,
                            cloudAuthority
                    ),
                    /* selectionArgs */ null
            );
            Log.i(TAG, "Returning " + pageData.getCount() + " media metadata");
            return pageData;
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch media", e);
        }
    }


    /**
     * @param albumId The album id of the request album media.
     * @param appContext The application context.
     * @param syncController Instance of the PickerSyncController singleton.
     * @param queryArgs The Bundle with query args received with the request.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     * @param cloudAuthority The effective cloud authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would
     *                       be null.
     * @return The cursor with the album media query results.
     */
    @NonNull
    public static Cursor queryMergedAlbumMedia(
            @NonNull String albumId,
            @NonNull Context appContext,
            @NonNull PickerSyncController syncController,
            @NonNull Bundle queryArgs,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {
        try {
            MediaQuery query;
            switch (albumId) {
                case CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES:
                    query = new FavoritesMediaQuery(queryArgs);
                    break;
                case CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS:
                    query = new VideoMediaQuery(queryArgs);
                    if (!((VideoMediaQuery) query).shouldDisplayVideosAlbum()) {
                        // Return cursor with zero item count if after applying the input MIME type
                        // filters videos cannot be displayed
                        return new MatrixCursor(
                                CloudMediaProviderContract.AlbumColumns.ALL_PROJECTION);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Cannot recognize album " + albumId);
            }

            final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

            try {
                database.beginTransactionNonExclusive();
                Cursor pageData = database.rawQuery(
                        getMediaPageQuery(
                                appContext,
                                query,
                                database,
                                PickerSQLConstants.Table.MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );

                Bundle extraArgs = new Bundle();
                Cursor nextPageKeyCursor = database.rawQuery(
                        getMediaNextPageKeyQuery(
                                appContext,
                                query,
                                database,
                                PickerSQLConstants.Table.MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                addNextPageKey(extraArgs, nextPageKeyCursor);

                Cursor prevPageKeyCursor = database.rawQuery(
                        getMediaPreviousPageQuery(
                                appContext,
                                query,
                                database,
                                PickerSQLConstants.Table.MEDIA,
                                localAuthority,
                                cloudAuthority
                        ),
                        /* selectionArgs */ null
                );
                addPrevPageKey(extraArgs, prevPageKeyCursor);

                if (query.shouldPopulateItemsBeforeCount()) {
                    Cursor itemsBeforeCountCursor = database.rawQuery(
                            getMediaItemsBeforeCountQuery(
                                    appContext,
                                    query,
                                    database,
                                    PickerSQLConstants.Table.MEDIA,
                                    localAuthority,
                                    cloudAuthority
                            ),
                            /* selectionArgs */ null
                    );
                    addItemsBeforeCountKey(extraArgs, itemsBeforeCountCursor);
                }

                if (query.shouldPopulateItemsAfterCount()) {
                    Cursor itemsAfterCountCursor = database.rawQuery(
                            getMediaItemsAfterCountIncludingCurrentPageQuery(
                                    appContext,
                                    query,
                                    database,
                                    PickerSQLConstants.Table.MEDIA,
                                    localAuthority,
                                    cloudAuthority
                            ),
                            /* selectionArgs */ null
                    );
                    addItemsAfterCountKey(extraArgs, itemsAfterCountCursor, query);
                }

                if (database.inTransaction()) {
                    database.setTransactionSuccessful();
                }

                pageData.setExtras(extraArgs);
                Log.i(TAG, "Returning " + pageData.getCount() + " album media items for album "
                        + albumId);
                return pageData;
            } finally {
                if (database.inTransaction()) {
                    database.endTransaction();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch media", e);
        }
    }


    /**
     * Return merged albums cursor for the given merged album id.
     *
     * @param albumId        Merged album id.
     * @param queryArgs      Query arguments bundle that will be used to filter albums.
     * @param database       Instance of Picker SQLiteDatabase.
     * @param localAuthority The local authority if local albums should be returned, otherwise this
     *                       argument should be null.
     * @param cloudAuthority The cloud authority if cloud albums should be returned, otherwise this
     *                       argument should be null.
     */
    public static AlbumsCursorWrapper getMergedAlbumsCursor(
            @NonNull String albumId,
            Context appContext,
            @NonNull Bundle queryArgs,
            @NonNull SQLiteDatabase database,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        if (localAuthority == null && cloudAuthority == null) {
            Log.e(TAG, "Cannot get merged albums when no providers are available");
            return null;
        }

        final MediaQuery query;
        if (albumId.equals(CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS)) {
            VideoMediaQuery videoQuery = new VideoMediaQuery(queryArgs, 1);
            if (!videoQuery.shouldDisplayVideosAlbum()) {
                return null;
            }
            query = videoQuery;
        } else if (albumId.equals(CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES)) {
            query = new FavoritesMediaQuery(queryArgs, 1);
        } else {
            Log.e(TAG, "Cannot recognize merged album " + albumId);
            return null;
        }

        try {
            database.beginTransactionNonExclusive();
            Cursor pickerDBResponse = database.rawQuery(
                    getMediaPageQuery(
                            appContext,
                            query,
                            database,
                            PickerSQLConstants.Table.MEDIA,
                            localAuthority,
                            cloudAuthority
                    ),
                    /* selectionArgs */ null
            );

            if (pickerDBResponse.moveToFirst()) {
                // Conform to the album response projection. Temporary code, this will change once
                // we start caching album metadata.
                final MatrixCursor result =
                        new MatrixCursor(CloudMediaProviderContract.AlbumColumns.ALL_PROJECTION);
                final String authority = pickerDBResponse.getString(pickerDBResponse.getColumnIndex(
                        PickerSQLConstants.MediaResponse.AUTHORITY.getProjectedName()));
                final String[] projectionValue = new String[]{
                        /* albumId */ albumId,
                        pickerDBResponse.getString(pickerDBResponse.getColumnIndex(
                                PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName())),
                        /* displayName */ getLocalizedDisplayName(albumId, appContext, albumId),
                        pickerDBResponse.getString(pickerDBResponse.getColumnIndex(
                                PickerSQLConstants.MediaResponse.MEDIA_ID.getProjectedName())),
                        /* count */ "0", // This value is not used anymore
                        authority,
                };
                result.addRow(projectionValue);
                return new AlbumsCursorWrapper(result, authority, localAuthority);
            }

            pickerDBResponse.close();

            // Always show Videos album if cloud feature is turned on and the MIME types filter
            // would allow for video format(s).
            if (albumId.equals(CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS)
                    && cloudAuthority != null) {
                return new AlbumsCursorWrapper(
                        getDefaultEmptyAlbum(albumId),
                        /* albumAuthority */ localAuthority,
                        /* localAuthority */ localAuthority);
            }

            // Always show Favorites album.
            if (albumId.equals(CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES)) {
                return new AlbumsCursorWrapper(
                        getDefaultEmptyAlbum(albumId),
                        /* albumAuthority */ localAuthority,
                        /* localAuthority */ localAuthority);
            }

            return null;
        } finally {
            if (database.inTransaction()) {
                database.setTransactionSuccessful();
                database.endTransaction();
            }
        }
    }

    @Nullable
    private static String getLocalizedDisplayName(
            @Nullable String albumId,
            @Nullable Context appContext,
            @NonNull String defaultDisplayName) {
        if (albumId == null || appContext == null) {
            Log.e(TAG, "Received null albumId or null appContext, returning default display name.");
            return defaultDisplayName;
        }
        Resources resources = appContext.getResources();
        return switch (albumId) {
            case CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES -> resources.getString(
                    R.string.favorites_album_display_name);
            case CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS -> resources.getString(
                    R.string.videos_album_display_name);
            default -> {
                Log.e(TAG, "Unrecognized merged album id: " + albumId
                        + ", Could not localize the display name.");
                yield defaultDisplayName;
            }
        };
    }


    /**
     * Query items count available in each month from the database and a cursor in response
     *
     * @param appContext The application context.
     * @param syncController Instance of the PickerSyncController singleton.
     * @param query The MediaQuery object instance that tells us about the media query args.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     * @param cloudAuthority The effective cloud authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would
     *                       be null.
     * @return The cursor with number of media items available in each  month.
     */
    @NonNull
    public static Cursor queryItemsPerMonth(
            @NonNull Context appContext,
            @NonNull PickerSyncController syncController,
            @NonNull MediaQuery query,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {
        try {
            final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

            try {
                database.beginTransactionNonExclusive();

                String queryString = getItemsPerMonthQuery(
                        appContext,
                        query,
                        database,
                        PickerSQLConstants.Table.MEDIA,
                        localAuthority,
                        cloudAuthority
                );
                Log.d(TAG, "Items per month query: " + queryString);

                Cursor itemsPerMonthData = database.rawQuery(
                        Objects.requireNonNull(queryString), /* selectionArgs= */ null);

                database.setTransactionSuccessful();
                Log.i(TAG, "Items per month query: Returning " + itemsPerMonthData.getCount()
                        + " dates");
                return itemsPerMonthData;
            } finally {
                database.endTransaction();
            }
        } catch (RuntimeException e) {
            throw new RuntimeException("Photo grid error querying items per month.", e);
        }
    }

    /**
     * Builds and returns the SQL query to get media item count available in each month from media
     * table in Picker DB.
     */
    @Nullable
    private static String getItemsPerMonthQuery(
            @Nullable Context appContext,
            @NonNull MediaQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        final MediaProjection projectionUtil = new MediaProjection(
                localAuthority,
                cloudAuthority,
                query.getIntentAction(),
                table
        );

        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(
                        query.getTableWithRequiredJoins(table.toString(), appContext,
                                query.getCallingPackageUid(), query.getIntentAction()))
                .setProjection(List.of(
                        projectionUtil.getYearForItemsPerMonthData(),
                        projectionUtil.getMonthForItemsPerMonthData(),
                        projectionUtil.getItemsPerMonthCount()
                ))
                .setGroupBy(PickerSQLConstants.ItemsPerMonthResponse.YEAR_TAKEN.getProjectedName()
                        + ", "
                        + PickerSQLConstants.ItemsPerMonthResponse.MONTH_TAKEN.getProjectedName())
                .setSortOrder(PickerSQLConstants.ItemsPerMonthResponse.YEAR_TAKEN.getProjectedName()
                        + " DESC, "
                        + PickerSQLConstants.ItemsPerMonthResponse.MONTH_TAKEN.getProjectedName()
                        + " DESC");

        query.addWhereClause(
                queryBuilder,
                table,
                localAuthority,
                cloudAuthority,
                /* reverseOrder */ false
        );

        return queryBuilder.buildQuery();
    }

    /**
     * Builds and returns the SQL query to get the page contents from the Media table in Picker DB.
     */
    private static String getMediaPageQuery(
            @Nullable Context appContext,
            @NonNull MediaQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        int callingPackageUid = query.getCallingPackageUid();
        String[] packageNames = null;

        if (callingPackageUid != -1 && isOwnedPhotosEnabled(callingPackageUid) && appContext != null
                && appContext.getPackageManager() != null) {
            packageNames = getPackageNameFromUid(appContext, callingPackageUid);
        }

        final MediaProjection projectionUtil = new MediaProjection(
                localAuthority,
                cloudAuthority,
                query.getIntentAction(),
                table,
                callingPackageUid,
                packageNames
        );

        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(query.getTableWithRequiredJoins(table.toString(), appContext,
                        query.getCallingPackageUid(), query.getIntentAction()))
                .setProjection(projectionUtil.getAll())
                .setSortOrder(getSortOrder(table, /* reverseOrder */ false))
                .setLimit(query.getCurrentPageSize());

        query.addWhereClause(
                queryBuilder,
                table,
                localAuthority,
                cloudAuthority,
                /* reverseOrder */ false
        );

        return queryBuilder.buildQuery();
    }


    /**
     * Query Media page key for the item on a specified position in picker DB and a cursor
     * in response.
     *
     * @param appContext The application context.
     * @param syncController Instance of the PickerSyncController singleton.
     * @param query The MediaPageKeyQuery object instance that tells us about the media page key
     *              query args.
     * @param localAuthority The effective local authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would be null.
     * @param cloudAuthority The effective cloud authority that we need to consider for this
     *                       transaction. If the local items should not be queries but the local
     *                       authority has some value, the effective local authority would
     *                       be null.
     * @return The cursor having picker id and date taken for the item on a specified position
     * in picker DB. This cursor will be used to form a valid instance of MediaPageKey.
     */
    @NonNull
    public static Cursor queryMediaPageKey(
            @NonNull Context appContext,
            @NonNull PickerSyncController syncController,
            @NonNull MediaPageKeyQuery query,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority
    ) {
        try {
            final SQLiteDatabase database = syncController.getDbFacade().getDatabase();

            try {
                database.beginTransactionNonExclusive();
                Cursor pageData = database.rawQuery(
                        Objects.requireNonNull(getMediaPageKeyQuery(
                                appContext,
                                query,
                                database,
                                PickerSQLConstants.Table.MEDIA,
                                localAuthority,
                                cloudAuthority
                        )),
                        /* selectionArgs */ null
                );
                database.setTransactionSuccessful();
                return pageData;
            } finally {
                database.endTransaction();
            }
        } catch (RuntimeException e) {
            throw new RuntimeException("Error querying media page key.", e);
        }
    }

    /**
     * Builds and returns the SQL query to get picker id and date taken for the item at specified
     * position in Media table in Picker DB.
     */
    @Nullable
    private static String getMediaPageKeyQuery(
            @Nullable Context appContext,
            @NonNull MediaPageKeyQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        final MediaProjection projectionUtil = new MediaProjection(
                localAuthority,
                cloudAuthority,
                query.getIntentAction(),
                table
        );

        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(
                        query.getTableWithRequiredJoins(table.toString(), appContext,
                                query.getCallingPackageUid(), query.getIntentAction()))
                .setProjection(List.of(
                        projectionUtil.get(PickerSQLConstants.MediaResponse.PICKER_ID),
                        projectionUtil.get(PickerSQLConstants.MediaResponse.DATE_TAKEN_MS)
                ))
                .setSortOrder(getSortOrder(table, /* reverseOrder */ false))
                .setLimit(1)
                .setOffset(query.getItemPosition());

        query.addWhereClause(
                queryBuilder,
                table,
                localAuthority,
                cloudAuthority,
                /* reverseOrder */ false
        );
        return queryBuilder.buildQuery();
    }

    /**
     * Builds and returns the SQL query to get the next page key from the Media table in Picker DB.
     */
    @Nullable
    private static String getMediaNextPageKeyQuery(
            @Nullable Context appContext,
            @NonNull MediaQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        if (query.getCurrentPageSize() == Integer.MAX_VALUE) {
            return null;
        }

        final MediaProjection projectionUtil = new MediaProjection(
                localAuthority,
                cloudAuthority,
                query.getIntentAction(),
                table
        );

        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(
                        query.getTableWithRequiredJoins(table.toString(), appContext,
                                query.getCallingPackageUid(), query.getIntentAction()))
                .setProjection(List.of(
                        projectionUtil.get(PickerSQLConstants.MediaResponse.PICKER_ID),
                        projectionUtil.get(PickerSQLConstants.MediaResponse.DATE_TAKEN_MS)
                ))
                .setSortOrder(getSortOrder(table, /* reverseOrder */ false))
                .setLimit(1)
                .setOffset(query.getCurrentPageSize());

        query.addWhereClause(
                queryBuilder,
                table,
                localAuthority,
                cloudAuthority,
                /* reverseOrder */ false
        );

        return queryBuilder.buildQuery();
    }

    /**
     * Builds and returns the SQL query to get the previous page contents from the Media table in
     * Picker DB.
     *
     * We fetch the whole page and not just one key because it is possible that the previous page
     * is smaller than the page size. So, we get the whole page and only use the last row item to
     * get the previous page key.
     */
    private static String getMediaPreviousPageQuery(
            @Nullable Context appContext,
            @NonNull MediaQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        final MediaProjection projectionUtil = new MediaProjection(
                localAuthority,
                cloudAuthority,
                query.getIntentAction(),
                table
        );

        final SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(
                        query.getTableWithRequiredJoins(table.toString(), appContext,
                                query.getCallingPackageUid(), query.getIntentAction()))
                .setProjection(List.of(
                        projectionUtil.get(PickerSQLConstants.MediaResponse.PICKER_ID),
                        projectionUtil.get(PickerSQLConstants.MediaResponse.DATE_TAKEN_MS)
                )).setSortOrder(getSortOrder(table, /* reverseOrder */ true)
                ).setLimit(query.getNextPageSize());


        query.addWhereClause(
                queryBuilder,
                table,
                localAuthority,
                cloudAuthority,
                /* reverseOrder */ true
        );

        return queryBuilder.buildQuery();
    }

    /**
     * Builds and returns the SQL query to get the count of items before the given page from the
     * Media table in Picker DB.
     *
     * The result only contains one row with one column that will hold the count of the items.
     */
    private static String getMediaItemsBeforeCountQuery(
            @Nullable Context appContext,
            @NonNull MediaQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(
                        query.getTableWithRequiredJoins(table.toString(), appContext,
                                query.getCallingPackageUid(), query.getIntentAction()))
                .setProjection(List.of("Count(*) AS " + PickerSQLConstants.COUNT_COLUMN))
                .setSortOrder(getSortOrder(table, /* reverseOrder */ true));

        query.addWhereClause(
                queryBuilder,
                table,
                localAuthority,
                cloudAuthority,
                /* reverseOrder */ true
        );

        return queryBuilder.buildQuery();
    }

    /**
     * Counts the number of items starting from the first item of the given page
     * to the end of the Media table in the Picker database.
     *
     * The result only contains one row with one column that will hold the count of items (No of
     * items in given page + No of items after the given page) .
     */
    private static String getMediaItemsAfterCountIncludingCurrentPageQuery(
            @Nullable Context appContext,
            @NonNull MediaQuery query,
            @NonNull SQLiteDatabase database,
            @NonNull PickerSQLConstants.Table table,
            @Nullable String localAuthority,
            @Nullable String cloudAuthority) {
        SelectSQLiteQueryBuilder queryBuilder = new SelectSQLiteQueryBuilder(database)
                .setTables(
                        query.getTableWithRequiredJoins(table.toString(), appContext,
                                query.getCallingPackageUid(), query.getIntentAction()))
                .setProjection(List.of("Count(*) AS " + PickerSQLConstants.COUNT_COLUMN));

        query.addWhereClause(
                queryBuilder,
                table,
                localAuthority,
                cloudAuthority,
                /* reverseOrder */ false
        );

        return queryBuilder.buildQuery();
    }


    /**
     * Adds the previous page key to the cursor extras from the given cursor.
     *
     * This is not a part of the page data. Photo Picker UI uses the Paging library requires us to
     * provide the previous page key and the next page key as part of a page load response.
     * The page key in this case refers to the date taken and the picker id of the first item in
     * the page.
     */
    public static void addPrevPageKey(Bundle extraArgs, Cursor prevPageKeyCursor) {
        if (prevPageKeyCursor.moveToLast()) {
            final int pickerIdColumnIndex = prevPageKeyCursor.getColumnIndex(
                    PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
            );

            if (pickerIdColumnIndex >= 0) {
                extraArgs.putLong(PickerSQLConstants.MediaResponseExtras.PREV_PAGE_ID.getKey(),
                        prevPageKeyCursor.getLong(pickerIdColumnIndex)
                );
            }

            final int dateTakenColumnIndex = prevPageKeyCursor.getColumnIndex(
                    PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName()
            );

            if (dateTakenColumnIndex >= 0) {
                extraArgs.putLong(PickerSQLConstants.MediaResponseExtras
                                .PREV_PAGE_DATE_TAKEN.getKey(),
                        prevPageKeyCursor.getLong(dateTakenColumnIndex)
                );
            }
        }
    }

    /**
     * Adds items before count key to the cursor extras from the provided cursor.
     */
    private static void addItemsBeforeCountKey(Bundle extraArgs, Cursor itemsBeforeCountCursor) {
        if (itemsBeforeCountCursor.moveToFirst()) {
            final int itemsBeforeCountIndex =
                    itemsBeforeCountCursor.getColumnIndex(PickerSQLConstants.COUNT_COLUMN);
            extraArgs.putInt(
                    PickerSQLConstants.MediaResponseExtras.ITEMS_BEFORE_COUNT.getKey(),
                    itemsBeforeCountCursor.getInt(itemsBeforeCountIndex)
            );
        }
    }

    /**
     * Adds items after count key to the cursor extras from the provided cursor.
     */
    private static void addItemsAfterCountKey(Bundle extraArgs, Cursor itemsAfterCountCursor,
            MediaQuery query) {
        if (itemsAfterCountCursor.moveToFirst()) {
            final int itemsAfterCountIndex =
                    itemsAfterCountCursor.getColumnIndex(PickerSQLConstants.COUNT_COLUMN);
            // itemsAfterCountCursor contains the total number of items from the start of
            // the given page to the end of the dataset.(i.e. items on current page + items after)
            extraArgs.putInt(
                    PickerSQLConstants.MediaResponseExtras.ITEMS_AFTER_COUNT.getKey(),
                    max(0, itemsAfterCountCursor.getInt(itemsAfterCountIndex)
                            - query.getCurrentPageSize())
            );
        }
    }

    /**
     * Adds the next page key to the cursor extras from the given cursor.
     *
     * This is not a part of the page data. Photo Picker UI uses the Paging library requires us to
     * provide the previous page key and the next page key as part of a page load response.
     * The page key in this case refers to the date taken and the picker id of the first item in
     * the page.
     */
    public static void addNextPageKey(Bundle extraArgs, Cursor nextPageKeyCursor) {
        if (nextPageKeyCursor.moveToFirst()) {
            final int pickerIdColumnIndex = nextPageKeyCursor.getColumnIndex(
                    PickerSQLConstants.MediaResponse.PICKER_ID.getProjectedName()
            );

            if (pickerIdColumnIndex >= 0) {
                extraArgs.putLong(PickerSQLConstants.MediaResponseExtras.NEXT_PAGE_ID.getKey(),
                        nextPageKeyCursor.getLong(pickerIdColumnIndex)
                );
            }

            final int dateTakenColumnIndex = nextPageKeyCursor.getColumnIndex(
                    PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getProjectedName()
            );

            if (dateTakenColumnIndex >= 0) {
                extraArgs.putLong(PickerSQLConstants.MediaResponseExtras
                                .NEXT_PAGE_DATE_TAKEN.getKey(),
                        nextPageKeyCursor.getLong(dateTakenColumnIndex)
                );
            }
        }
    }

    private static String getSortOrder(
            @NonNull PickerSQLConstants.Table table,
            boolean reverseOrder) {
        if (reverseOrder) {
            return String.format(
                    Locale.ROOT,
                    "%s ASC, %s ASC",
                    prependTableName(
                            table,
                            PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getColumnName()),
                    prependTableName(
                            table,
                            PickerSQLConstants.MediaResponse.PICKER_ID.getColumnName())
            );
        } else  {
            return String.format(
                    Locale.ROOT,
                    "%s DESC, %s DESC",
                    prependTableName(
                            table,
                            PickerSQLConstants.MediaResponse.DATE_TAKEN_MS.getColumnName()),
                    prependTableName(
                            table,
                            PickerSQLConstants.MediaResponse.PICKER_ID.getColumnName())
            );
        }
    }
}
