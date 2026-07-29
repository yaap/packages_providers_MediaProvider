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

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.provider.CloudMediaProviderContract;
import android.util.Pair;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.util.exceptions.RequestObsoleteException;
import com.android.providers.media.photopicker.v2.model.MediaSetsQuery;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MediaSetsDatabaseUtilsTest {
    private SQLiteDatabase mDatabase;
    private Context mContext;
    private final String mMediaSetId = "mediaSetId";
    private final String mCategoryId = "categoryId";
    private final String mAuthority = "auth";
    private final String mMimeType = "img";
    private final String mDisplayName = "name";
    private final String mCoverId = "id";

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        PickerDatabaseHelper helper = new PickerDatabaseHelper(mContext);
        mDatabase = helper.getWritableDatabase();
    }

    @After
    public void teardown() {
        mDatabase.close();
        File dbPath = mContext.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
    }

    @Test
    public void testInsertMediaSetMetadataIntoMediaSetsTable() throws RequestObsoleteException {
        Cursor c = getCursorForMediaSetInsertionTest(mMediaSetId, mDisplayName, mCoverId);
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add(mMimeType);

        int mediaSetsInserted = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, mCategoryId, mAuthority, mimeTypes);
        assertEquals("Count of inserted media sets should be equal to the cursor size",
                /*expected*/ c.getCount(), /*actual*/ mediaSetsInserted);
    }

    @Test
    public void testInsertMediaSetMetadataIntoMediaTableMimeTypeFilter()
            throws RequestObsoleteException {
        Cursor c = getCursorForMediaSetInsertionTest(mMediaSetId, mDisplayName, mCoverId);
        List<String> firstMimeTypeFilter = new ArrayList<>();
        firstMimeTypeFilter.add("image/*");
        firstMimeTypeFilter.add("video/*");

        int firstInsertionCount = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, mCategoryId, mAuthority, firstMimeTypeFilter
               );
        assertEquals("Count of inserted media sets should be equal to the cursor size",
                /*expected*/ c.getCount(), /*actual*/ firstInsertionCount);

        // Reversing the order of the mimeTypeFilter.
        // It should still be treated the same and should not be reinserted
        List<String> secondMimeTypeFilter = new ArrayList<>();
        secondMimeTypeFilter.add("video/*");
        secondMimeTypeFilter.add("image/*");

        int secondInsertionCount = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, mCategoryId, mAuthority, secondMimeTypeFilter);
        assertEquals("MediaSet metadata with same mimetype filters should not be inserted "
                        + "again",
                /*expected*/ 0, /*actual*/ secondInsertionCount);

    }

    @Test
    public void testInsertMediaSetMetadataWhenMediaSetIdIsNull() throws RequestObsoleteException {
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add(mMimeType);

        String[] columns = new String[]{
                CloudMediaProviderContract.MediaSetColumns.ID,
                CloudMediaProviderContract.MediaSetColumns.DISPLAY_NAME,
                CloudMediaProviderContract.MediaSetColumns.MEDIA_COVER_ID
        };

        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(new Object[] { null, mDisplayName, mCoverId });

        int mediaSetsInserted = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, cursor, mCategoryId, mAuthority, mimeTypes);
        assertEquals("Count of inserted media sets should be 0 when the mediaSetId is null",
                /*expected*/0, /*actual*/ mediaSetsInserted);
    }

    @Test
    public void testGetMediaSetMetadataForCategory() throws RequestObsoleteException {
        Cursor c = getCursorForMediaSetInsertionTest(mMediaSetId, mDisplayName, mCoverId);
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add(mMimeType);

        long insertResult = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, mCategoryId, mAuthority, mimeTypes);
        // Assert successful insertion
        assertWithMessage("MediaSet metadata insertion failed")
                .that(insertResult)
                .isAtLeast(/* expected min row id */ 0);
        Bundle extras = new Bundle();
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_AUTHORITY, mAuthority);
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_ID, mCategoryId);
        extras.putStringArrayList(
                MediaSetsQuery.KEY_MIME_TYPES,
                new ArrayList<String>(mimeTypes));
        MediaSetsQuery requestParams = new MediaSetsQuery(extras);

        Cursor mediaSetCursor = MediaSetsDatabaseUtil.getMediaSetsForCategory(
                mDatabase, requestParams);
        assertNotNull(mediaSetCursor);
        assertWithMessage("Cursor size should be greater than 0. Expected size: 1")
                .that(mediaSetCursor.getCount())
                .isEqualTo(1);
        if (mediaSetCursor.moveToFirst()) {
            int mediaSetIdIndex = mediaSetCursor.getColumnIndex(PickerSQLConstants
                    .MediaSetsTableColumns.MEDIA_SET_ID.getColumnName());
            String retrievedMediaSetId = mediaSetCursor.getString(mediaSetIdIndex);
            assertEquals(mMediaSetId, retrievedMediaSetId);
        }
    }


    @Test
    public void testGetMediaSetsForCategoryPagination() {

        // Insert more media sets than the page size to test pagination
        int totalMediaSetsCount = 4;
        int pageSize = 2;
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add(mMimeType);

        for (int setCount = 0; setCount < totalMediaSetsCount; setCount++) {
            Cursor c = getCursorForMediaSetInsertionTest(
                    mMediaSetId + setCount,
                    mDisplayName + setCount,
                    mCoverId + setCount
            );
            long insertResult = MediaSetsDatabaseUtil.cacheMediaSets(
                    mDatabase, c, mCategoryId, mAuthority, mimeTypes);
            // Assert successful insertion
            assertWithMessage("MediaSet metadata insertion failed for set " + setCount)
                    .that(insertResult)
                    .isAtLeast(/* expected min row id */ 0);
        }

        Bundle extras = new Bundle();
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_AUTHORITY, mAuthority);
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_ID, mCategoryId);
        extras.putStringArrayList(MediaSetsQuery.KEY_MIME_TYPES, new ArrayList<String>(mimeTypes));
        extras.putInt(MediaSetsQuery.KEY_PAGE_SIZE, pageSize);
        extras.putLong(MediaSetsQuery.KEY_PICKER_ID, Long.MIN_VALUE);
        MediaSetsQuery firstQuery = new MediaSetsQuery(extras);

        // Query first page
        final Cursor firstPageCursor =
                MediaSetsDatabaseUtil.getMediaSetsForCategory(mDatabase, firstQuery);

        // Assert the correctness of the cursor with the data of the first page
        assertNotNull(firstPageCursor);
        assertEquals(pageSize, firstPageCursor.getCount());
        assertCursorItems(firstPageCursor, 0, pageSize);

        Bundle firstPageExtras = firstPageCursor.getExtras();
        long firstNextPageKey = firstPageExtras.getLong(
                PickerSQLConstants.MediaResponseExtras.NEXT_PAGE_ID.getKey(), -1);
        long firstPrevPageKey = firstPageExtras.getLong(
                PickerSQLConstants.MediaResponseExtras.PREV_PAGE_ID.getKey(), -1);
        // Assuming PICKER_IDs are 1, 2, 3, 4. The first page has items with ID 1 and 2.
        // The next page should start from ID 3.
        assertEquals("Next page key for first page should be the ID of the next item",
                3L,
                firstNextPageKey
        );
        assertEquals("No previous page key exists for a first page",
                -1L,
                firstPrevPageKey
        );

        extras.putLong(MediaSetsQuery.KEY_PICKER_ID, firstNextPageKey);
        MediaSetsQuery secondPageQuery = new MediaSetsQuery(extras);

        // Query second page
        final Cursor secondPageCursor =
                MediaSetsDatabaseUtil.getMediaSetsForCategory(mDatabase, secondPageQuery);

        // Assert the correctness of the cursor with the data of the second page
        assertNotNull(secondPageCursor);
        assertEquals(pageSize, secondPageCursor.getCount());
        assertCursorItems(secondPageCursor, 2, totalMediaSetsCount);

        Bundle secondPageExtras = secondPageCursor.getExtras();
        long secondNextPageKey = secondPageExtras.getLong(
                PickerSQLConstants.MediaResponseExtras.NEXT_PAGE_ID.getKey(), -1);
        long secondPrevPageKey = secondPageExtras.getLong(
                PickerSQLConstants.MediaResponseExtras.PREV_PAGE_ID.getKey(), -1);
        assertEquals("No next page key for last page",
                -1L,
                secondNextPageKey
        );
        assertEquals("Previous page key for last page should be ID of the prev page",
                1L,
                secondPrevPageKey
        );
    }

    @Test
    public void testGetMediaSetsForCategory_VerifyNextPageKeyWithGreaterOrEqualWhereClause() {
        final int totalMediaSetsCount = 3;
        final int pageSize = 1;
        final List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add(mMimeType);

        for (int i = 0; i < totalMediaSetsCount; i++) {
            final String mediaSetId = mMediaSetId + i;
            final String displayName = mDisplayName + i;
            final String coverId = mCoverId + i;
            final Cursor c = getCursorForMediaSetInsertionTest(mediaSetId, displayName, coverId);
            MediaSetsDatabaseUtil.cacheMediaSets(mDatabase, c, mCategoryId, mAuthority, mimeTypes);
        }

        // picker_id values are 1, 2, 3
        // With pageSize = 1 and pickerId = 1, the first page will contain item with picker_id = 1.
        // The next page should start from picker_id = 2.
        final Bundle extras = new Bundle();
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_AUTHORITY, mAuthority);
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_ID, mCategoryId);
        extras.putStringArrayList(MediaSetsQuery.KEY_MIME_TYPES, new ArrayList<>(mimeTypes));
        extras.putInt(MediaSetsQuery.KEY_PAGE_SIZE, pageSize);
        extras.putLong(MediaSetsQuery.KEY_PICKER_ID, 1L);
        final MediaSetsQuery query = new MediaSetsQuery(extras);

        final Cursor cursor = MediaSetsDatabaseUtil.getMediaSetsForCategory(mDatabase, query);

        // An incorrect implementation of a filtering where clause ">" would have returned 3L
        // as the next page key, skipping over item 2. The correct implementation with ">="
        // returns 2L.
        final Bundle cursorExtras = cursor.getExtras();
        final long nextPageKey = cursorExtras.getLong(
                PickerSQLConstants.MediaResponseExtras.NEXT_PAGE_ID.getKey(), -1);

        assertEquals("The next page key should be 2.", 2L, nextPageKey);
    }

    @Test
    public void testUpdateAndGetMediaInMediaSetResumeKey() throws RequestObsoleteException {
        Cursor c = getCursorForMediaSetInsertionTest(mMediaSetId, mDisplayName, mCoverId);
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add(mMimeType);

        long mediaSetsInserted = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, mCategoryId, mAuthority, mimeTypes);
        // Assert successful insertion
        assertEquals("Count of inserted media sets should be equal to the cursor size",
                /*expected*/ c.getCount(), /*actual*/ mediaSetsInserted);
        Bundle extras = new Bundle();
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_AUTHORITY, mAuthority);
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_ID, mCategoryId);
        extras.putStringArrayList(
                MediaSetsQuery.KEY_MIME_TYPES,
                new ArrayList<String>(mimeTypes));
        MediaSetsQuery requestParams = new MediaSetsQuery(extras);
        Cursor fetchMediaSetCursor = MediaSetsDatabaseUtil.getMediaSetsForCategory(
                mDatabase, requestParams);
        Long mediaSetPickerId = 1L;
        if (fetchMediaSetCursor.moveToFirst()) {
            mediaSetPickerId = fetchMediaSetCursor.getLong(
                    fetchMediaSetCursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName()));
        }

        String resumeKey = "resume";
        MediaSetsDatabaseUtil.updateMediaInMediaSetSyncResumeKey(
                mDatabase, mediaSetPickerId, resumeKey);
        String retrievedMediaSetResumeKey = MediaSetsDatabaseUtil.getMediaResumeKey(
                mDatabase, mediaSetPickerId);
        assertNotNull(retrievedMediaSetResumeKey);
        assertWithMessage("Retrieved mediaSetResumeKey did not match")
                .that(retrievedMediaSetResumeKey)
                .isEqualTo(resumeKey);
    }

    @Test
    public void testGetMediaSetIdAndMimeTypesUsingMediaSetPickerId()
            throws RequestObsoleteException {
        Cursor c = getCursorForMediaSetInsertionTest(mMediaSetId, mDisplayName, mCoverId);
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add(mMimeType);

        long mediaSetsInserted = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, mCategoryId, mAuthority, mimeTypes);
        // Assert successful insertion
        assertEquals("Count of inserted media sets should be equal to the cursor size",
                /*expected*/ c.getCount(), /*actual*/ mediaSetsInserted);
        Bundle extras = new Bundle();
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_AUTHORITY, mAuthority);
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_ID, mCategoryId);
        extras.putStringArrayList(
                MediaSetsQuery.KEY_MIME_TYPES,
                new ArrayList<String>(mimeTypes));
        MediaSetsQuery requestParams = new MediaSetsQuery(extras);
        Cursor fetchMediaSetCursor = MediaSetsDatabaseUtil.getMediaSetsForCategory(
                mDatabase, requestParams);
        Long mediaSetPickerId = 1L;
        if (fetchMediaSetCursor.moveToFirst()) {
            mediaSetPickerId = fetchMediaSetCursor.getLong(
                    fetchMediaSetCursor.getColumnIndexOrThrow(
                            PickerSQLConstants.MediaSetsTableColumns.PICKER_ID.getColumnName()));
        }

        Pair<String, String[]> retrievedData = MediaSetsDatabaseUtil
                .getMediaSetIdAndMimeType(mDatabase, mediaSetPickerId);
        assertEquals(/*expected*/retrievedData.first, /*actual*/mMediaSetId);
        assertTrue(Arrays.toString(retrievedData.second).contains(mMimeType));
    }

    @Test
    public void testGetMediaSetPickerIdsForCategoryId() {
        Cursor c = getCursorForMediaSetInsertionTest(mMediaSetId, mDisplayName, mCoverId);
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add(mMimeType);

        long mediaSetsInserted = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, mCategoryId, mAuthority, mimeTypes);
        // Assert successful insertion
        assertEquals("Count of inserted media sets should be equal to the cursor size",
                /*expected*/ c.getCount(), /*actual*/ mediaSetsInserted);

        List<String> mediaSetPickerIds = MediaSetsDatabaseUtil
                .getMediaSetPickerIdsForGivenCategoryId(mDatabase, mCategoryId, mAuthority);
        // Assert that the list has some sqlite generated ids
        assertNotNull(mediaSetPickerIds);
        assertTrue(!mediaSetPickerIds.isEmpty());
    }

    @Test
    public void testClearMediaSetsCache() {
        // Insert metadata into the table
        Cursor c = getCursorForMediaSetInsertionTest(mMediaSetId, mDisplayName, mCoverId);
        List<String> mimeTypes = new ArrayList<>();
        mimeTypes.add(mMimeType);

        int mediaSetsInserted = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, mCategoryId, mAuthority, mimeTypes);
        assertEquals("Count of inserted media sets should be equal to the cursor size",
                /*expected*/ c.getCount(), /*actual*/ mediaSetsInserted);

        String secondCategoryId = "secCategoryId";
        int mediaSetsInserted2 = MediaSetsDatabaseUtil.cacheMediaSets(
                mDatabase, c, secondCategoryId, mAuthority, mimeTypes);
        assertEquals("Count of inserted media sets should be equal to the cursor size",
                /*expected*/ c.getCount(), /*actual*/ mediaSetsInserted2);


        // Delete the inserted items
        MediaSetsDatabaseUtil.clearMediaSetsCache(mDatabase, mCategoryId, mAuthority);

        // Retrieved cursor should be empty for mCategoryId
        Bundle extras = new Bundle();
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_AUTHORITY, mAuthority);
        extras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_ID, mCategoryId);
        extras.putStringArrayList(
                MediaSetsQuery.KEY_MIME_TYPES,
                new ArrayList<String>(mimeTypes));
        MediaSetsQuery requestParams = new MediaSetsQuery(extras);

        Cursor mediaSetCursor = MediaSetsDatabaseUtil.getMediaSetsForCategory(
                mDatabase, requestParams);
        assertNotNull(mediaSetCursor);
        assertEquals(/*expected*/ 0, /*actual*/ mediaSetCursor.getCount());

        // Retrieved cursor should not be empty for secondCategoryId since only the media sets for
        // mCategoryId have been deleted in the previous call
        Bundle secondExtras = new Bundle();
        secondExtras.putString(
                MediaSetsQuery.KEY_PARENT_CATEGORY_AUTHORITY, mAuthority);
        secondExtras.putString(MediaSetsQuery.KEY_PARENT_CATEGORY_ID, secondCategoryId);
        secondExtras.putStringArrayList(
                MediaSetsQuery.KEY_MIME_TYPES,
                new ArrayList<String>(mimeTypes));
        MediaSetsQuery secondRequestParams =
                new MediaSetsQuery(secondExtras);

        Cursor secondMediaSetCursor = MediaSetsDatabaseUtil.getMediaSetsForCategory(
                mDatabase, secondRequestParams);
        assertNotNull(secondMediaSetCursor);
        assertEquals(/*expected*/ 1, /*actual*/ secondMediaSetCursor.getCount());
    }

    private Cursor getCursorForMediaSetInsertionTest(
            String mediaSetId, String displayName,
            String coverId
    ) {
        String[] columns = new String[]{
                CloudMediaProviderContract.MediaSetColumns.ID,
                CloudMediaProviderContract.MediaSetColumns.DISPLAY_NAME,
                CloudMediaProviderContract.MediaSetColumns.MEDIA_COVER_ID
        };

        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(new Object[] { mediaSetId, displayName, coverId });

        return cursor;
    }

    private void assertCursorItems(Cursor cursor, int startIndex, int endIndex) {
        cursor.moveToFirst();
        for (int i = startIndex; i < endIndex; i++) {
            final String expectedMediaSetId = mMediaSetId + i;
            final String actualMediaSetId = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.MediaSetsTableColumns.MEDIA_SET_ID.getColumnName()));
            assertEquals(expectedMediaSetId, actualMediaSetId);
            final String expectedDisplayName = mDisplayName + i;
            final String actualDisplayName = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.MediaSetsTableColumns.DISPLAY_NAME.getColumnName()));
            assertEquals(expectedDisplayName, actualDisplayName);
            final String expectedCoverId = mCoverId + i;
            final String actualCoverId = cursor.getString(cursor.getColumnIndexOrThrow(
                    PickerSQLConstants.MediaSetsTableColumns.COVER_ID.getColumnName()));
            assertEquals(expectedCoverId, actualCoverId);
            cursor.moveToNext();
        }
    }
}
