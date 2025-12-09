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

import static android.provider.MediaStore.MY_USER_ID;

import static java.util.Objects.requireNonNull;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.CloudMediaProviderContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.v2.model.MediaGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Utility class that prepares cursor response in the format
 * {@link PickerSQLConstants.MediaGroupResponseColumns}.
 */
public class MediaGroupCursorUtils {
    private static final String TAG = "MediaGroupCursorUtils";

    private static final String[] ALL_MEDIA_GROUP_RESPONSE_PROJECTION = new String[]{
            PickerSQLConstants.MediaGroupResponseColumns.MEDIA_GROUP.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.GROUP_ID.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.PICKER_ID.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.DISPLAY_NAME.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.AUTHORITY.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.UNWRAPPED_COVER_URI.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns
                    .ADDITIONAL_UNWRAPPED_COVER_URI_1.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns
                    .ADDITIONAL_UNWRAPPED_COVER_URI_2.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns
                    .ADDITIONAL_UNWRAPPED_COVER_URI_3.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.CATEGORY_TYPE.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.IS_LEAF_CATEGORY.getColumnName(),
    };

    private static final String[] MEDIA_SET_RESPONSE_PROJECTION = new String[]{
            PickerSQLConstants.MediaGroupResponseColumns.GROUP_ID.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.PICKER_ID.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.DISPLAY_NAME.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.AUTHORITY.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.UNWRAPPED_COVER_URI.getColumnName(),
            PickerSQLConstants.MediaGroupResponseColumns.BADGE_ICON_URI.getColumnName(),
    };

    private static final List<String> MEDIA_COVER_ID_COLUMNS = List.of(
            CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID1,
            CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID2,
            CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID3,
            CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID4
    );

    /**
     * @param cursor Input
     *               {@link CloudMediaProviderContract.MediaSetColumns} cursor.
     * @return Cursor with the columns {@link PickerSQLConstants.MediaGroupResponseColumns}.
     */
    public static Cursor getMediaGroupCursorForMediaSets(
            Context appContext,
            @Nullable Cursor cursor) {
        if (cursor == null) {
            return null;
        }

        MatrixCursor mediaSetsResponse = new MatrixCursor(MEDIA_SET_RESPONSE_PROJECTION);

        // Get the list of Uris from the cursor.
        final List<String> uris = new ArrayList<>();
        if (cursor.moveToFirst()) {
            do {
                String authority = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_AUTHORITY.getColumnName()
                ));
                String coverId = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaSetsTableColumns.COVER_ID.getColumnName()
                ));
                String coverUri = getUri(coverId, authority).toString();
                if (coverUri != null) {
                    uris.add(coverUri);
                }
            } while (cursor.moveToNext());
        }

        // Get list of local ids if local copy exists for corresponding cloud ids.
        final Map<String, String> cloudToLocalIdMap = getLocalIds(uris);

        if (cursor.moveToFirst()) {
            do {
                String mediaSetId = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_ID.getColumnName()
                ));
                String mediaSetPickerId = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName()
                ));
                String displayName = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaSetsTableColumns.DISPLAY_NAME.getColumnName()
                ));
                String authority = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_AUTHORITY.getColumnName()
                ));
                String coverId = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.MediaSetsTableColumns.COVER_ID.getColumnName()
                ));
                String coverUri = getUri(coverId, authority).toString();
                String unwrappedCoverUri = maybeGetLocalUri(coverUri, cloudToLocalIdMap);
                String badgeUri = getMediaSetBadgeUri(appContext, mediaSetId);

                mediaSetsResponse.addRow(new Object[]{
                        mediaSetId,
                        mediaSetPickerId,
                        displayName,
                        authority,
                        unwrappedCoverUri,
                        badgeUri
                });
            } while (cursor.moveToNext());
        }
        return mediaSetsResponse;
    }

    private static String getMediaSetBadgeUri(Context appContext, String mediaSetId) {
        try {
            String[] segments = mediaSetId.split(":");
            if (!CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS.equals(segments[0])) {
                return null;
            }
            String packageName = segments[1];
            ApplicationInfo applicationInfo =
                    appContext.getPackageManager().getApplicationInfo(packageName, 0);
            int resId = applicationInfo.icon;
            return getCustomAndroidResourceUri(String.format(
                    Locale.ROOT,
                    "%s/%s",
                    packageName,
                    resId)).toString();
        } catch (PackageManager.NameNotFoundException exception) {
            Log.e(TAG, "Package not found for media set: " + mediaSetId, exception);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Error occurred while getting badge uri for media set: " + mediaSetId,
                    exception);
        }
        return null;
    }

    /**
     * @param cursor Input
     *               {@link com.android.providers.media.photopicker.v2.model.AlbumsCursorWrapper}
     * @param index  The index for the first album in the given albums cursor.
     *               The index value can be used to generate unique picker id for albums.
     * @return Cursor with the columns {@link PickerSQLConstants.MediaGroupResponseColumns}.
     */
    @Nullable
    public static Cursor getMediaGroupCursorForAlbums(@Nullable Cursor cursor, long index) {
        if (cursor == null) {
            return null;
        }

        final MatrixCursor response = new MatrixCursor(ALL_MEDIA_GROUP_RESPONSE_PROJECTION);

        // Get the list of Uris from the cursor.
        final List<String> uris = new ArrayList<>();
        if (cursor.moveToFirst()) {
            do {
                final String unwrappedCoverUri =
                        cursor.getString(cursor.getColumnIndexOrThrow(PickerSQLConstants
                                .AlbumResponse.UNWRAPPED_COVER_URI.getColumnName()));
                if (unwrappedCoverUri != null) {
                    uris.add(unwrappedCoverUri);
                }
            } while (cursor.moveToNext());
        }

        // Get list of local ids if local copy exists for corresponding cloud ids.
        final Map<String, String> cloudToLocalIdMap = getLocalIds(uris);

        if (cursor.moveToFirst()) {
            do {
                final String albumId = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.AlbumResponse.ALBUM_ID.getColumnName()));

                // Sets the picker id of the current album and increments the index for the
                // next album.
                final long pickerId = index++;

                final String displayName = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.AlbumResponse.ALBUM_NAME.getColumnName()));

                final String authority = cursor.getString(cursor.getColumnIndexOrThrow(
                        PickerSQLConstants.AlbumResponse.AUTHORITY.getColumnName()));

                final String unwrappedCoverUri = maybeGetLocalUri(
                        cursor.getString(cursor.getColumnIndexOrThrow(PickerSQLConstants
                                .AlbumResponse.UNWRAPPED_COVER_URI.getColumnName())),
                        cloudToLocalIdMap);

                response.addRow(new Object[]{
                        MediaGroup.ALBUM.name(),
                        albumId,
                        pickerId,
                        displayName,
                        authority,
                        unwrappedCoverUri,
                        /* MediaGroupResponseColumns.ADDITIONAL_UNWRAPPED_COVER_URI_1 */ null,
                        /* MediaGroupResponseColumns.ADDITIONAL_UNWRAPPED_COVER_URI_2 */ null,
                        /* MediaGroupResponseColumns.ADDITIONAL_UNWRAPPED_COVER_URI_3 */ null,
                        /* MediaGroupResponseColumns.CATEGORY_TYPE */ null,
                        /* MediaGroupResponseColumns.IS_LEAF_CATEGORY */ null
                });
            } while (cursor.moveToNext());
        }

        return response;
    }

    /**
     * @param cursor    Input
     *                  {@link CloudMediaProviderContract.MediaCategoryColumns} cursor.
     * @param authority The authority of the category's CMP.
     * @param index     The index for the first category in the given categories cursor.
     *                  The index value can be used to generate unique picker id for categories.
     * @param validCategoryType The category type to validate the input cursor.
     *                          Category type received from the input cursor should be same as this.
     * @return Cursor with the columns {@link PickerSQLConstants.MediaGroupResponseColumns}.
     */
    @Nullable
    public static Cursor getMediaGroupCursorForCategories(
            @Nullable Cursor cursor,
            @NonNull String authority,
            long index,
            @NonNull String validCategoryType) {
        if (cursor == null) {
            return null;
        }

        final MatrixCursor response = new MatrixCursor(ALL_MEDIA_GROUP_RESPONSE_PROJECTION);

        final List<String> uris = new ArrayList<>();
        if (cursor.moveToFirst()) {
            do {
                for (String columnName : MEDIA_COVER_ID_COLUMNS) {
                    final String mediaCoverId = cursor.getString(
                            cursor.getColumnIndexOrThrow(columnName));
                    if (mediaCoverId != null) {
                        uris.add(getUri(mediaCoverId, authority).toString());
                    }
                }
            } while (cursor.moveToNext());
        }

        // Get list of local ids if local copy exists for corresponding cloud ids.
        final Map<String, String> cloudToLocalIdMap = getLocalIds(uris);
        if (cursor.moveToFirst()) {
            if (cursor.getCount() > 1) {
                Log.e(TAG, "Only one category is expected but received "
                        + cursor.getCount());
            }

            final String categoryType = cursor.getString(cursor.getColumnIndexOrThrow(
                    CloudMediaProviderContract.MediaCategoryColumns.MEDIA_CATEGORY_TYPE));

            if (!validCategoryType.equals(categoryType)) {
                Log.e(TAG, "Could not recognize category type. Skipping it: " + categoryType);
                return response;
            }

            final String categoryId = requireNonNull(
                    cursor.getString(cursor.getColumnIndexOrThrow(
                            CloudMediaProviderContract.MediaCategoryColumns.ID)));

            final String displayName = cursor.getString(cursor.getColumnIndexOrThrow(
                    CloudMediaProviderContract.MediaCategoryColumns.DISPLAY_NAME));

            List<String> coverUris = new ArrayList<>(MEDIA_COVER_ID_COLUMNS.size());
            for (String columnName: MEDIA_COVER_ID_COLUMNS) {
                final String mediaCoverId = cursor.getString(
                        cursor.getColumnIndexOrThrow(columnName));
                if (mediaCoverId == null) {
                    coverUris.add(null);
                    continue;
                }
                final String coverUri;
                if (CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS.equals(
                        categoryType)) {
                    coverUri = getCustomAndroidResourceUri(mediaCoverId).toString();
                } else {
                    coverUri = maybeGetLocalUri(
                            getUri(mediaCoverId, authority).toString(),
                            cloudToLocalIdMap);
                }
                coverUris.add(coverUri);
            }

            response.addRow(new Object[]{
                    MediaGroup.CATEGORY.name(),
                    categoryId,
                    index,
                    displayName,
                    authority,
                    coverUris.get(0),
                    coverUris.get(1),
                    coverUris.get(2),
                    coverUris.get(3),
                    categoryType,
                    // Default is 1, we don't have recursive categories yet.
                    /* MediaGroupResponseColumns.IS_LEAF_CATEGORY */ 1
            });
        }

        return response;
    }

    /**
     * @param uris List of Uris received in a cursor. These could be local Uris, or cloud Uris.
     * @return A map of valid cloud id -> local ids. Cloud ids will be extracted from input list of
     * uris.
     */
    public static Map<String, String> getLocalIds(@NonNull List<String> uris) {
        final PickerSyncController syncController = PickerSyncController.getInstanceOrThrow();
        final String localAuthority = syncController.getLocalProvider();

        // Filter cloud Ids from the input list of Uris.
        final Map<String, String> cloudToLocalIdMap = new HashMap<>();

        try {
            requireNonNull(uris);

            final List<String> cloudUris = new ArrayList<>();
            for (String inputUri : uris) {
                final Uri coverUri = Uri.parse(inputUri);
                final String authority = coverUri.getAuthority();

                if (Objects.equals(localAuthority, authority)) {
                    Log.d(TAG, "Cover uri already refers to a local media item.");
                } else {
                    cloudUris.add(coverUri.getLastPathSegment());
                }
            }

            // Get a map of local ids for their corresponding cloud ids from the database.
            final SelectSQLiteQueryBuilder localUriQueryBuilder =
                    new SelectSQLiteQueryBuilder(syncController.getDbFacade().getDatabase());
            localUriQueryBuilder.setTables(PickerSQLConstants.Table.MEDIA.name())
                    .setProjection(new String[]{
                            PickerDbFacade.KEY_LOCAL_ID,
                            PickerDbFacade.KEY_CLOUD_ID});
            localUriQueryBuilder.appendWhereStandalone(String.format(
                    Locale.ROOT, "%s IN ('%s')", PickerDbFacade.KEY_CLOUD_ID,
                    String.join("','", cloudUris)));
            localUriQueryBuilder.appendWhereStandalone(String.format(
                    Locale.ROOT, "%s IS NULL", PickerDbFacade.KEY_IS_VISIBLE));
            localUriQueryBuilder.appendWhereStandalone(String.format(
                    Locale.ROOT, "%s IS NOT NULL", PickerDbFacade.KEY_LOCAL_ID));

            try (Cursor cursor = syncController.getDbFacade().getDatabase()
                    .rawQuery(localUriQueryBuilder.buildQuery(), /*selectionArgs*/ null)) {
                if (cursor.moveToFirst()) {
                    do {
                        final String localId = cursor.getString(cursor.getColumnIndexOrThrow(
                                PickerDbFacade.KEY_LOCAL_ID));
                        final String cloudId = cursor.getString(cursor.getColumnIndexOrThrow(
                                PickerDbFacade.KEY_CLOUD_ID));
                        cloudToLocalIdMap.put(cloudId, localId);
                    } while (cursor.moveToNext());
                }
            }

            // Validate that local ids correspond to a valid local media item on the device.
            final SelectSQLiteQueryBuilder validateLocalIdQueryBuilder =
                    new SelectSQLiteQueryBuilder(syncController.getDbFacade().getDatabase());
            validateLocalIdQueryBuilder.setTables(PickerSQLConstants.Table.MEDIA.name())
                    .setProjection(new String[]{PickerDbFacade.KEY_LOCAL_ID});
            validateLocalIdQueryBuilder.appendWhereStandalone(String.format(
                    Locale.ROOT, "%s IS NULL", PickerDbFacade.KEY_CLOUD_ID));
            validateLocalIdQueryBuilder.appendWhereStandalone(String.format(
                    Locale.ROOT, "%s = 1", PickerDbFacade.KEY_IS_VISIBLE));
            validateLocalIdQueryBuilder.appendWhereStandalone(String.format(
                    Locale.ROOT, "%s IN ('%s')", PickerDbFacade.KEY_LOCAL_ID,
                    String.join("','", cloudToLocalIdMap.values())));

            final Set<String> validLocalIds = new HashSet<>();
            try (Cursor cursor = syncController.getDbFacade().getDatabase()
                    .rawQuery(validateLocalIdQueryBuilder.buildQuery(), /*selectionArgs*/ null)) {
                if (cursor.moveToFirst()) {
                    do {
                        final String localId = cursor.getString(cursor.getColumnIndexOrThrow(
                                PickerDbFacade.KEY_LOCAL_ID));
                        validLocalIds.add(localId);
                    } while (cursor.moveToNext());
                }
            }

            // Filter map so that it only contains valid local Ids.
            cloudToLocalIdMap.keySet().removeIf(
                    cloudId -> !validLocalIds.contains(cloudToLocalIdMap.get(cloudId)));
        } catch (Exception e) {
            Log.e(TAG, "Could not get local ids for cloud items", e);
        }

        return cloudToLocalIdMap;
    }

    /**
     * Checks if the input coverUri points to a cloud media object. If it does, then tries to
     * find the local copy of it and returns the URI of the local copy. Otherwise returns the input
     * coverUri as it is.
     */
    @VisibleForTesting
    public static String maybeGetLocalUri(
            @Nullable String rawCoverUri,
            @NonNull Map<String, String> cloudToLocalIdMap) {
        if (rawCoverUri == null || rawCoverUri.isEmpty()) {
            return null;
        }

        final String localAuthority = PickerSyncController.getInstanceOrThrow().getLocalProvider();
        try {
            final Uri coverUri = Uri.parse(rawCoverUri);
            final String mediaId = coverUri.getLastPathSegment();
            if (cloudToLocalIdMap.containsKey(mediaId)) {
                return getUri(cloudToLocalIdMap.get(mediaId), localAuthority).toString();
            } else {
                return rawCoverUri;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error occurred in parsing Uri received from CMP", e);
        }

        return rawCoverUri;
    }

    private static Uri getUri(String mediaId, String authority) {
        return PickerUriResolver
                .getMediaUri(getEncodedUserAuthority(authority))
                .buildUpon()
                .appendPath(mediaId)
                .build();
    }

    /**
     * Parses a custom string-based media identifier and builds a standard {@code android.resource}
     * URI from it. The input {@code mediaId} must be in the format
     * "{@code <package_name>/<resource_id>/<user_id>}". If the {@code mediaId} is null or
     * malformed, this method logs an error and returns {@link Uri#EMPTY}.
     */
    @VisibleForTesting
    @NonNull
    public static Uri getCustomAndroidResourceUri(@Nullable String mediaId) {
        if (mediaId == null) {
            return Uri.EMPTY;
        }
        // mediaId is of the form "<package_name>/<res_id>"
        try {
            final String[] segments = mediaId.split("/");
            final String packageName = segments[0];
            final String resId = segments[1];

            Uri.Builder builder =  new Uri.Builder()
                    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                    .encodedAuthority(getEncodedUserAuthority(packageName))
                    .path(resId);
            return builder.build();
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing userId from mediaId: " + mediaId, e);
        } catch (ArrayIndexOutOfBoundsException e) {
            Log.e(TAG, "Error splitting mediaId, incorrect format: " + mediaId, e);
        } catch (Exception e) {
            Log.e(TAG, "Error occurred while getting Uri for android resource: " + mediaId, e);
        }
        return Uri.EMPTY;
    }

    private static String getEncodedUserAuthority(String authority) {
        if (authority.contains("@")) {
            return authority;
        } else {
            return MY_USER_ID + "@" + authority;
        }
    }
}
