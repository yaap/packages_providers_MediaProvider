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

package com.android.providers.media.photopicker.v2;

import static android.provider.MediaStore.MY_USER_ID;

import static com.android.providers.media.photopicker.util.PickerDbTestUtils.CLOUD_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_ID_2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.LOCAL_PROVIDER;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.PACKAGE_NAME1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.PACKAGE_NAME2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.RES_ID1;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.RES_ID2;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.assertAddMediaOperation;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getAndroidResourceUriString;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getCloudMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getDrawableMediaId;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getLocalMediaCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getMediaCategoriesCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getMediaSetsCursor;
import static com.android.providers.media.photopicker.util.PickerDbTestUtils.getPickerUriString;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.CloudMediaProviderContract;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.cloudproviders.SearchProvider;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.photopicker.data.PickerDatabaseHelper;
import com.android.providers.media.photopicker.data.PickerDbFacade;
import com.android.providers.media.photopicker.sync.PickerSyncLockManager;
import com.android.providers.media.photopicker.v2.model.MediaGroup;
import com.android.providers.media.photopicker.v2.sqlite.MediaGroupCursorUtils;
import com.android.providers.media.photopicker.v2.sqlite.PickerSQLConstants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MediaGroupCursorUtilsTest {
    @Mock
    private PickerSyncController mMockSyncController;
    @Mock
    private Context mMockContext;
    @Mock
    PackageManager mMockPackageManager;
    private PickerDbFacade mFacade;
    private ApplicationInfo mApplicationInfo = new ApplicationInfo();


    @Before
    public void setUp() {
        initMocks(this);
        PickerSyncController.setInstance(mMockSyncController);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dbPath = context.getDatabasePath(PickerDatabaseHelper.PICKER_DATABASE_NAME);
        dbPath.delete();
        mFacade = new PickerDbFacade(context, new PickerSyncLockManager(), LOCAL_PROVIDER);
        mFacade.setCloudProvider(SearchProvider.AUTHORITY);

        doReturn(mFacade).when(mMockSyncController).getDbFacade();
        doReturn(LOCAL_PROVIDER).when(mMockSyncController).getLocalProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController).getCloudProvider();
        doReturn(SearchProvider.AUTHORITY).when(mMockSyncController)
                .getCloudProviderOrDefault(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any());
        doReturn(true).when(mMockSyncController).shouldQueryCloudMedia(any(), any());
        doReturn(mMockPackageManager).when(mMockContext).getPackageManager();
    }

    @After
    public void tearDown() {
        if (mFacade != null) {
            mFacade.setCloudProvider(null);
        }
    }

    @Test
    public void testGetLocalIdForCloudUri() {
        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, SearchProvider.AUTHORITY, cursor2, 1);
        final Cursor cursor3 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);

        final List<String> mediaUris = List.of(
                "content://" + SearchProvider.AUTHORITY + "/" + CLOUD_ID_1,
                "content://" + LOCAL_PROVIDER + "/" + LOCAL_ID_1
        );

        final Map<String, String> result = MediaGroupCursorUtils.getLocalIds(mediaUris);

        assertWithMessage("Result map should not be null")
                .that(result)
                .isNotNull();
        assertWithMessage("Result map size is not as expected")
                .that(result.size())
                .isEqualTo(1);
        assertWithMessage("Result map should contain cloud id as key")
                .that(result.containsKey(CLOUD_ID_1))
                .isTrue();
        assertWithMessage("Mapped local id is incorrect")
                .that(result.get(CLOUD_ID_1))
                .isEqualTo(LOCAL_ID_1);
    }

    @Test
    public void testGetLocalIdForCloudUriNoMatch() {
        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_1, /* localId */ null, 0);
        assertAddMediaOperation(mFacade, SearchProvider.AUTHORITY, cursor2, 1);
        final Cursor cursor3 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);

        final List<String> mediaUris = List.of(
                "content://" + SearchProvider.AUTHORITY + "/" + CLOUD_ID_1
        );

        final Map<String, String> result = MediaGroupCursorUtils.getLocalIds(mediaUris);

        assertWithMessage("Result map should not be null")
                .that(result)
                .isNotNull();
        assertWithMessage("Result map size is not as expected")
                .that(result.size())
                .isEqualTo(0);
    }

    @Test
    public void testGetValidLocalIdForCloudUri() {
        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_1, LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, SearchProvider.AUTHORITY, cursor2, 1);

        final List<String> mediaUris = List.of(
                "content://" + SearchProvider.AUTHORITY + "/" + CLOUD_ID_1
        );

        final Map<String, String> result = MediaGroupCursorUtils.getLocalIds(mediaUris);

        assertWithMessage("Result map should not be null")
                .that(result)
                .isNotNull();
        assertWithMessage("Result map size is not as expected")
                .that(result.size())
                .isEqualTo(0);
    }

    @Test
    public void testGetValidLocalIdForEmptyUriList() {
        final Cursor cursor1 = getLocalMediaCursor(LOCAL_ID_1, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor1, 1);
        final Cursor cursor2 = getCloudMediaCursor(CLOUD_ID_1, /* localId */ null, 0);
        assertAddMediaOperation(mFacade, SearchProvider.AUTHORITY, cursor2, 1);
        final Cursor cursor3 = getLocalMediaCursor(LOCAL_ID_2, 0);
        assertAddMediaOperation(mFacade, LOCAL_PROVIDER, cursor3, 1);

        final List<String> mediaUris = List.of();

        final Map<String, String> result = MediaGroupCursorUtils.getLocalIds(mediaUris);

        assertWithMessage("Result map should not be null")
                .that(result)
                .isNotNull();
        assertWithMessage("Result map size is not as expected")
                .that(result.size())
                .isEqualTo(0);
    }

    @Test
    public void testGetLocalUri() {
        final String cloudAuthority = "cloud.authority";
        final String cloudMediaId = "cloud-id";
        final String localMediaId = "local-id";
        final Uri cloudUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .encodedAuthority(cloudAuthority)
                .appendPath("media")
                .appendPath(cloudMediaId)
                .build();

        final String localAuthority = PickerSyncController.getInstanceOrThrow().getLocalProvider();
        final Uri expectedLocalUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .encodedAuthority(UserHandle.myUserId() + "@" + localAuthority)
                .appendPath("media")
                .appendPath(localMediaId)
                .build();

        final String actualLocalUri = MediaGroupCursorUtils.maybeGetLocalUri(
                cloudUri.toString(), Map.of(cloudMediaId, localMediaId));

        assertWithMessage("Mapped local uri is not as expected.")
                .that(actualLocalUri)
                .isEqualTo(expectedLocalUri.toString());
    }

    @Test
    public void testGetLocalUriWithNoMapping() {
        final String cloudAuthority = "cloud.authority";
        final String cloudMediaId = "cloud-id";
        final Uri cloudUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .encodedAuthority(cloudAuthority)
                .appendPath("media")
                .appendPath(cloudMediaId)
                .build();

        final String actualUri = MediaGroupCursorUtils.maybeGetLocalUri(
                cloudUri.toString(), Map.of());

        assertWithMessage("Returned uri is not as expected.")
                .that(actualUri)
                .isEqualTo(cloudUri.toString());
    }

    @Test
    public void testGetCustomAndroidResourceUri_validMediaId_returnsAndroidId() {
        final String mediaId = getDrawableMediaId(PACKAGE_NAME1, RES_ID1);
        final String uriString = getAndroidResourceUriString(PACKAGE_NAME1, RES_ID1);
        final Uri expectedUri = Uri.parse(uriString);
        final Uri actualUri = MediaGroupCursorUtils.getCustomAndroidResourceUri(mediaId);

        assertWithMessage("Unexpected uri returned.")
                .that(actualUri)
                .isEqualTo(expectedUri);
    }

    @Test
    public void testGetCustomAndroidResourceUri_nullMediaId_returnsEmptyUri() {
        final String mediaId = null;
        final Uri actualUri = MediaGroupCursorUtils.getCustomAndroidResourceUri(mediaId);

        assertWithMessage("Unexpected uri returned.")
                .that(actualUri)
                .isEqualTo(Uri.EMPTY);
    }

    @Test
    public void testGetCustomAndroidResourceUri_inValidMediaId_returnsEmptyUri() {
        // resource id is missing
        final String invalidMediaId = String.format(
                Locale.ROOT,
                "%s/",
                PACKAGE_NAME1
        );
        final Uri actualUri = MediaGroupCursorUtils.getCustomAndroidResourceUri(invalidMediaId);

        assertWithMessage("Unexpected uri returned.")
                .that(actualUri)
                .isEqualTo(Uri.EMPTY);
    }

    @Test
    public void testGetMediaGroupCursorForCategories_forDeviceCategory_createsPickerUri() {
        final String localAuthority = "local.authority";
        Cursor categoryCursor = getMediaCategoriesCursor(
                CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS);

        Cursor mediaGroupCursor = MediaGroupCursorUtils.getMediaGroupCursorForCategories(
                categoryCursor,
                localAuthority,
                1L,
                CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS);
        assertWithMessage("Expected media group cursor to non null")
                .that(mediaGroupCursor).isNotNull();
        assertWithMessage("Unexpected number of rows found in media group cursor")
                .that(mediaGroupCursor.getCount()).isEqualTo(1);

        mediaGroupCursor.moveToFirst();
        assertWithMessage("Unexpected media group")
                .that(MediaGroup.valueOf(
                        mediaGroupCursor.getString(mediaGroupCursor.getColumnIndexOrThrow(
                                PickerSQLConstants
                                        .MediaGroupResponseColumns.MEDIA_GROUP.getColumnName()))))
                .isEqualTo(MediaGroup.CATEGORY);

        final List<String> mediaCoverIdColumns = List.of(
                PickerSQLConstants.MediaGroupResponseColumns.UNWRAPPED_COVER_URI.getColumnName(),
                PickerSQLConstants.MediaGroupResponseColumns
                        .ADDITIONAL_UNWRAPPED_COVER_URI_1.getColumnName(),
                PickerSQLConstants.MediaGroupResponseColumns
                        .ADDITIONAL_UNWRAPPED_COVER_URI_2.getColumnName(),
                PickerSQLConstants.MediaGroupResponseColumns
                        .ADDITIONAL_UNWRAPPED_COVER_URI_3.getColumnName()
        );

        List<String> expectedUnwrappedCoverUris = Arrays.asList(
                getPickerUriString(LOCAL_ID_1, localAuthority, MY_USER_ID),
                getPickerUriString(LOCAL_ID_2, localAuthority, MY_USER_ID),
                null,
                null
        );
        List<String> actualUnwrappedCoverUris = new ArrayList<>();
        for (String columnName : mediaCoverIdColumns) {
            final String mediaCoverId = mediaGroupCursor.getString(
                    mediaGroupCursor.getColumnIndexOrThrow(columnName));
            actualUnwrappedCoverUris.add(mediaCoverId);
        }
        assertWithMessage("Unexpected list of unwrapped cover uris found")
                .that(actualUnwrappedCoverUris)
                .containsExactlyElementsIn(expectedUnwrappedCoverUris);
    }

    @Test
    public void testGetMediaGroupCursorForCategories_forAppCategory_createsAndroidResourceUri() {
        final String localAuthority = "local.authority";
        Cursor categoryCursor = getMediaCategoriesCursor(
                CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS);

        Cursor mediaGroupCursor = MediaGroupCursorUtils.getMediaGroupCursorForCategories(
                categoryCursor,
                localAuthority,
                1L,
                CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS);
        assertWithMessage("Expected media group cursor to non null")
                .that(mediaGroupCursor).isNotNull();
        assertWithMessage("Unexpected number of rows found in media group cursor")
                .that(mediaGroupCursor.getCount()).isEqualTo(1);

        mediaGroupCursor.moveToFirst();
        assertWithMessage("Unexpected media group")
                .that(MediaGroup.valueOf(
                        mediaGroupCursor.getString(mediaGroupCursor.getColumnIndexOrThrow(
                                PickerSQLConstants
                                        .MediaGroupResponseColumns.MEDIA_GROUP.getColumnName()))))
                .isEqualTo(MediaGroup.CATEGORY);

        final List<String> mediaCoverIdColumns = List.of(
                PickerSQLConstants.MediaGroupResponseColumns.UNWRAPPED_COVER_URI.getColumnName(),
                PickerSQLConstants.MediaGroupResponseColumns
                        .ADDITIONAL_UNWRAPPED_COVER_URI_1.getColumnName(),
                PickerSQLConstants.MediaGroupResponseColumns
                        .ADDITIONAL_UNWRAPPED_COVER_URI_2.getColumnName(),
                PickerSQLConstants.MediaGroupResponseColumns
                        .ADDITIONAL_UNWRAPPED_COVER_URI_3.getColumnName()
        );

        List<String> expectedUnwrappedCoverUris = Arrays.asList(
                getAndroidResourceUriString(PACKAGE_NAME1, RES_ID1),
                getAndroidResourceUriString(PACKAGE_NAME2, RES_ID2),
                null,
                null
        );
        List<String> actualUnwrappedCoverUris = new ArrayList<>();
        for (String columnName : mediaCoverIdColumns) {
            final String mediaCoverId = mediaGroupCursor.getString(
                    mediaGroupCursor.getColumnIndexOrThrow(columnName));
            actualUnwrappedCoverUris.add(mediaCoverId);
        }
        assertWithMessage("Unexpected list of unwrapped cover uris found")
                .that(actualUnwrappedCoverUris)
                .containsExactlyElementsIn(expectedUnwrappedCoverUris);
    }

    @Test
    public void testGetMediaGroupCursorForCategories_forSdCardCategory_createsPickerUri() {
        final String localAuthority = "local.authority";
        Cursor categoryCursor = getMediaCategoriesCursor(
                CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_SD_CARD);

        Cursor mediaGroupCursor = MediaGroupCursorUtils.getMediaGroupCursorForCategories(
                categoryCursor,
                localAuthority,
                1L,
                CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_SD_CARD);
        assertWithMessage("Expected media group cursor to non null")
                .that(mediaGroupCursor).isNotNull();
        assertWithMessage("Unexpected number of rows found in media group cursor")
                .that(mediaGroupCursor.getCount()).isEqualTo(1);

        mediaGroupCursor.moveToFirst();
        assertWithMessage("Unexpected media group")
                .that(MediaGroup.valueOf(
                        mediaGroupCursor.getString(mediaGroupCursor.getColumnIndexOrThrow(
                                PickerSQLConstants
                                        .MediaGroupResponseColumns.MEDIA_GROUP.getColumnName()))))
                .isEqualTo(MediaGroup.CATEGORY);

        final List<String> mediaCoverIdColumns = List.of(
                PickerSQLConstants.MediaGroupResponseColumns.UNWRAPPED_COVER_URI.getColumnName(),
                PickerSQLConstants.MediaGroupResponseColumns
                        .ADDITIONAL_UNWRAPPED_COVER_URI_1.getColumnName(),
                PickerSQLConstants.MediaGroupResponseColumns
                        .ADDITIONAL_UNWRAPPED_COVER_URI_2.getColumnName(),
                PickerSQLConstants.MediaGroupResponseColumns
                        .ADDITIONAL_UNWRAPPED_COVER_URI_3.getColumnName()
        );

        List<String> expectedUnwrappedCoverUris = Arrays.asList(
                getPickerUriString(LOCAL_ID_1, localAuthority, MY_USER_ID),
                getPickerUriString(LOCAL_ID_2, localAuthority, MY_USER_ID),
                null,
                null
        );
        List<String> actualUnwrappedCoverUris = new ArrayList<>();
        for (String columnName : mediaCoverIdColumns) {
            final String mediaCoverId = mediaGroupCursor.getString(
                    mediaGroupCursor.getColumnIndexOrThrow(columnName));
            actualUnwrappedCoverUris.add(mediaCoverId);
        }
        assertWithMessage("Unexpected list of unwrapped cover uris found")
                .that(actualUnwrappedCoverUris)
                .containsExactlyElementsIn(expectedUnwrappedCoverUris);
    }

    @Test
    public void testGetMediaGroupCursorForMediaSets_forNonAppCategory_returnsNullBadgeUri()
            throws PackageManager.NameNotFoundException {
        Cursor categoryCursor = getMediaSetsCursor(
                CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                PACKAGE_NAME1);
        mApplicationInfo.icon = RES_ID1;

        doReturn(mApplicationInfo).when(mMockPackageManager).getApplicationInfo(PACKAGE_NAME1, 0);

        Cursor mediaSetGroupCursor = MediaGroupCursorUtils.getMediaGroupCursorForMediaSets(
                mMockContext,
                categoryCursor);
        assertWithMessage("Expected media set group cursor to be non null")
                .that(mediaSetGroupCursor).isNotNull();
        assertWithMessage("Unexpected number of rows found in media group cursor")
                .that(mediaSetGroupCursor.getCount()).isEqualTo(1);

        String expectedGroupId =
                CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS + ":" + PACKAGE_NAME1;
        mediaSetGroupCursor.moveToFirst();
        assertWithMessage("Unexpected media set group id")
                .that(mediaSetGroupCursor.getString(mediaSetGroupCursor.getColumnIndexOrThrow(
                        PickerSQLConstants
                                .MediaGroupResponseColumns.GROUP_ID.getColumnName())))
                .isEqualTo(expectedGroupId);

        assertWithMessage("Incorrect badge icon uri found, expected it be null")
                .that(mediaSetGroupCursor.getString(mediaSetGroupCursor.getColumnIndexOrThrow(
                        PickerSQLConstants
                                .MediaGroupResponseColumns.BADGE_ICON_URI.getColumnName())))
                .isNull();
    }

    @Test
    public void testGetMediaGroupCursorForMediaSets_forAppCategory_returnsBadgeUri()
            throws PackageManager.NameNotFoundException {
        Cursor categoryCursor = getMediaSetsCursor(
                CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                PACKAGE_NAME1);
        mApplicationInfo.icon = RES_ID1;

        doReturn(mApplicationInfo).when(mMockPackageManager).getApplicationInfo(PACKAGE_NAME1, 0);

        Cursor mediaSetGroupCursor = MediaGroupCursorUtils.getMediaGroupCursorForMediaSets(
                mMockContext,
                categoryCursor);
        assertWithMessage("Expected media set group cursor to be non null")
                .that(mediaSetGroupCursor).isNotNull();
        assertWithMessage("Unexpected number of rows found in media group cursor")
                .that(mediaSetGroupCursor.getCount()).isEqualTo(1);

        String expectedGroupId =
                CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS + ":" + PACKAGE_NAME1;
        mediaSetGroupCursor.moveToFirst();
        assertWithMessage("Unexpected media set group id")
                .that(mediaSetGroupCursor.getString(mediaSetGroupCursor.getColumnIndexOrThrow(
                        PickerSQLConstants
                                .MediaGroupResponseColumns.GROUP_ID.getColumnName())))
                .isEqualTo(expectedGroupId);

        String expectedBadgeUri = String.format(
                Locale.ROOT,
                "android.resource://%s@%s/%s",
                MY_USER_ID, PACKAGE_NAME1, RES_ID1
        );
        assertWithMessage("Incorrect badge icon uri found, expected it be null")
                .that(mediaSetGroupCursor.getString(mediaSetGroupCursor.getColumnIndexOrThrow(
                        PickerSQLConstants
                                .MediaGroupResponseColumns.BADGE_ICON_URI.getColumnName())))
                .isEqualTo(expectedBadgeUri);
    }
}
