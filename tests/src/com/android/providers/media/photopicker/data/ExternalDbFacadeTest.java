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
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_DOWNLOADS;
import static android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_SCREENSHOTS;
import static android.provider.CloudMediaProviderContract.EXTRA_ALBUM_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID;
import static android.provider.CloudMediaProviderContract.EXTRA_PAGE_SIZE;
import static android.provider.CloudMediaProviderContract.EXTRA_PAGE_TOKEN;
import static android.provider.CloudMediaProviderContract.EXTRA_SORT_ORDER;
import static android.provider.CloudMediaProviderContract.EXTRA_SYNC_GENERATION;
import static android.provider.CloudMediaProviderContract.MediaCollectionInfo;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_GIF;
import static android.provider.MediaStore.Files.FileColumns._SPECIAL_FORMAT_NONE;
import static android.provider.MediaStore.MediaColumns.DATE_TAKEN;
import static android.provider.MediaStore.MediaColumns.IS_DOWNLOAD;

import static com.android.providers.media.photopicker.data.ExternalDbFacade.COLUMN_OLD_ID;
import static com.android.providers.media.photopicker.data.ExternalDbFacade.TABLE_DELETED_MEDIA;
import static com.android.providers.media.photopicker.data.ExternalDbFacade.TABLE_FILES;
import static com.android.providers.media.photopicker.util.CursorUtils.getCursorString;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Environment;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.IsolatedContext;
import com.android.providers.media.ProjectionHelper;
import com.android.providers.media.TestConfigStore;
import com.android.providers.media.TestDatabaseBackupAndRecovery;
import com.android.providers.media.VolumeCache;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.util.UserCache;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class ExternalDbFacadeTest {
    private static final String TAG = "ExternalDbFacadeTest";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final long ID1 = 1;
    private static final long ID2 = 2;
    private static final long ID3 = 3;
    private static final long ID4 = 4;
    private static final long ID5 = 5;
    private static final long DATE_TAKEN_MS1 = 1624886050566L;
    private static final long DATE_TAKEN_MS2 = 1624886050567L;
    private static final long DATE_TAKEN_MS3 = 1624886050568L;
    private static final long DATE_TAKEN_MS4 = 1624886050569L;
    private static final long DATE_TAKEN_MS5 = 1624886050570L;
    private static final long DATE_MODIFIED_MS1 = 1625000011L;
    private static final long DATE_MODIFIED_MS2 = 1625000012L;
    private static final long DATE_MODIFIED_MS3 = 1625000013L;
    private static final long GENERATION_MODIFIED1 = 1;
    private static final long GENERATION_MODIFIED2 = 2;
    private static final long GENERATION_MODIFIED3 = 3;
    private static final long GENERATION_MODIFIED5 = 5;
    private static final long SIZE = 8000;
    private static final long HEIGHT = 500;
    private static final long WIDTH = 700;
    private static final long ORIENTATION = 1;
    private static final String IMAGE_MIME_TYPE = "image/jpeg";
    private static final String[] IMAGE_MIME_TYPES_QUERY = new String[]{"image/jpeg"};
    private static final String VIDEO_MIME_TYPE = "video/mp4";
    private static final String[] VIDEO_MIME_TYPES_QUERY = new String[]{"video/mp4"};
    private static final long DURATION_MS = 5;
    private static final int IS_FAVORITE = 0;
    private static final String MAIN_ACTIVITY = ".MainActivity";
    private static final String SYSTEM_PACKAGE = "com.android.app";
    private static final String PACKAGE_NAME1 = "com.example.app1";
    private static final String ACTIVITY_NAME1 = PACKAGE_NAME1 + MAIN_ACTIVITY;
    private static final String PACKAGE_NAME2 = "com.example.app2";
    private static final String ACTIVITY_NAME2 = PACKAGE_NAME2 + MAIN_ACTIVITY;
    private static final String PACKAGE_NAME3 = "com.example.app3";
    private static final String ACTIVITY_NAME3 = PACKAGE_NAME3 + MAIN_ACTIVITY;
    private static final String PACKAGE_NAME4 = "com.example.app4";
    private static final String ACTIVITY_NAME4 = PACKAGE_NAME4 + MAIN_ACTIVITY;
    private static final String PACKAGE_NAME5 = "com.example.app5";
    private static final String ACTIVITY_NAME5 = PACKAGE_NAME5 + MAIN_ACTIVITY;
    private static final String APP_LABEL = "mock_app";
    private static final int RES_ID = 123456;
    private static final String FOLDER_NAME1 = "Folder1";

    private static IsolatedContext sIsolatedContext;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        Map<String, ApplicationInfo> packageNameToAppInfoMap = new HashMap<>();
        packageNameToAppInfoMap.put(
                PACKAGE_NAME1,
                createFakeAppInfo(PACKAGE_NAME1, APP_LABEL, RES_ID));
        packageNameToAppInfoMap.put(
                PACKAGE_NAME2,
                createFakeAppInfo(PACKAGE_NAME2, APP_LABEL, RES_ID));
        packageNameToAppInfoMap.put(
                PACKAGE_NAME3,
                createFakeAppInfo(PACKAGE_NAME3, APP_LABEL, RES_ID));
        packageNameToAppInfoMap.put(
                PACKAGE_NAME4,
                createFakeAppInfo(PACKAGE_NAME4, APP_LABEL, RES_ID));
        packageNameToAppInfoMap.put(
                PACKAGE_NAME5,
                createFakeAppInfo(PACKAGE_NAME5, APP_LABEL, RES_ID));

        LauncherApps mockLauncherApps = mock(LauncherApps.class);
        List<String> packageNameList = Arrays.asList(PACKAGE_NAME1, PACKAGE_NAME2, PACKAGE_NAME3,
                PACKAGE_NAME4, PACKAGE_NAME5);
        List<String> activityNameList = Arrays.asList(ACTIVITY_NAME1, ACTIVITY_NAME2,
                ACTIVITY_NAME3, ACTIVITY_NAME4, ACTIVITY_NAME5);
        List<LauncherActivityInfo> activityList = new ArrayList<>();
        for (int i = 0; i < packageNameList.size(); ++i) {
            activityList.add(getMockLauncherActivityInfoFor(
                    packageNameList.get(i), activityNameList.get(i)));
        }

        sIsolatedContext = new IsolatedContext(context, TAG, /*asFuseThread*/ false,
                mockLauncherApps);
        when(mockLauncherApps.getActivityList(null, sIsolatedContext.getUser()))
                .thenReturn(activityList);
        sIsolatedContext.stubApplicationInfoCalls(packageNameToAppInfoMap);
    }

    private LauncherActivityInfo getMockLauncherActivityInfoFor(String packageName,
            String activityName) {
        LauncherActivityInfo mockLauncherActivityInfo = mock(LauncherActivityInfo.class);
        ComponentName componentName = new ComponentName(packageName, activityName);
        when(mockLauncherActivityInfo.getComponentName()).thenReturn(componentName);
        when(mockLauncherActivityInfo.getName()).thenReturn(activityName);
        return mockLauncherActivityInfo;
    }

    @Test
    public void testDeletedMedia_addAndRemove() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            if (!facade.addDeletedMedia(ID1)) {
                assertWithMessage("Adding item with ID %s failed",
                        ID1).fail();
            }
            if (!facade.addDeletedMedia(ID2)) {
                assertWithMessage("Adding item with ID %s failed",
                        ID2).fail();
            }

            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
                assertWithMessage(
                        "Number of rows in the deleted_media table with generation greater than 0"
                                + " was")
                        .that(cursor.getCount()).isEqualTo(2);
                ArrayList<Long> ids = new ArrayList<>();
                while (cursor.moveToNext()) {
                    ids.add(cursor.getLong(0));
                }
                assertWithMessage("The list of ids from delete_media table")
                        .that(ids).contains(ID1);
                assertWithMessage("The list of ids from delete_media table")
                        .that(ids).contains(ID2);
            }

            // Filter by generation should only return ID2
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 1)) {
                assertWithMessage(
                        "Number of rows in the deleted_media table with generation greater than 1"
                                + " is")
                        .that(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertWithMessage("ID fro row having generation greater than 1")
                        .that(cursor.getLong(0)).isEqualTo(ID2);
            }

            // Adding ids again should succeed but bump generation_modified of ID1 and ID2
            if (!facade.addDeletedMedia(ID1)) {
                assertWithMessage("Adding item with ID %s failed",
                        ID1).fail();
            }
            if (!facade.addDeletedMedia(ID2)) {
                assertWithMessage("Adding item with ID %s failed",
                        ID2).fail();
            }

            // Filter by generation again, now returns both ids since their generation_modified was
            // bumped
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 1)) {
                assertWithMessage(
                        "Number of rows in the deleted_media table with generation greater than 1"
                                + " is")
                        .that(cursor.getCount()).isEqualTo(2);
            }

            // Remove ID2 should succeed
            if (!facade.removeDeletedMedia(ID2)) {
                assertWithMessage("Removing item with ID %s failed", ID2).fail();
            }
            // Remove ID2 again should fail
            if (facade.removeDeletedMedia(ID2)) {
                assertWithMessage("Removing item with ID %s should have failed", ID2).fail();
            }

            // Verify only ID1 left
            try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
                assertWithMessage(
                        "Number of rows in the deleted_media table with generation greater than 0"
                                + " is")
                        .that(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertWithMessage(
                        "ID of the item left in the deleted_media table after deleting row with "
                                + "id=ID2 is")
                        .that(cursor.getLong(0)).isEqualTo(ID1);
            }
        }
    }

    @Test
    public void testDeletedMedia_onInsert() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            if (!facade.onFileInserted(FileColumns.MEDIA_TYPE_VIDEO, /* isPending */ false)) {
                assertWithMessage(
                        "Expected to return true but returned false on Insert of "
                                + "MEDIA_TYPE_VIDEO").fail();
            }
            if (!facade.onFileInserted(FileColumns.MEDIA_TYPE_IMAGE, /* isPending */ false)) {
                assertWithMessage(
                        "Expected to return true but returned false on Insert of "
                                + "MEDIA_TYPE_IMAGE").fail();
            }
            assertDeletedMediaEmpty(facade);

            if (facade.onFileInserted(FileColumns.MEDIA_TYPE_AUDIO, /* isPending */ false)) {
                assertWithMessage(
                        "Expected to return false but returned true on Insert of "
                                + "MEDIA_TYPE_AUDIO").fail();
            }
            if (facade.onFileInserted(FileColumns.MEDIA_TYPE_NONE, /* isPending */ false)) {
                assertWithMessage(
                        "Expected to return false but returned true on Insert of "
                                + "MEDIA_TYPE_NONE").fail();
            }
            if (facade.onFileInserted(FileColumns.MEDIA_TYPE_IMAGE, /* isPending */ true)) {
                assertWithMessage(
                        "Expected to return false but returned true on Insert of "
                                + " MEDIA_TYPE_IMAGE with isPending true").fail();
            }
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_mediaType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Non-media -> non-media: no-op
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return false but returned true on Update from "
                                + "MEDIA_TYPE_NONE to MEDIA_TYPE_NONE").fail();
            }
            assertDeletedMediaEmpty(facade);

            // Media -> non-media: added to deleted_media
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_NONE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on Update from "
                                + "MEDIA_TYPE_IMAGE to MEDIA_TYPE_NONE").fail();
            }
            assertDeletedMedia(facade, ID1);

            // Non-media -> non-media: no-op
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return false but returned true on Update from "
                                + "MEDIA_TYPE_NONE to MEDIA_TYPE_NONE").fail();
            }
            assertDeletedMedia(facade, ID1);

            // Non-media -> media: remove from deleted_media
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on Update from "
                                + "MEDIA_TYPE_NONE to MEDIA_TYPE_IMAGE").fail();
            }
            assertDeletedMediaEmpty(facade);

            // Non-media -> Non-media: no-op
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_NONE, FileColumns.MEDIA_TYPE_NONE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return false but returned true on Update from "
                                + "MEDIA_TYPE_NONE to MEDIA_TYPE_NONE").fail();
            }
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_trashed() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was trashed but is now neither trashed nor pending
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ true, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was trashed but the newMedia is neither trashed nor pending.")
                        .fail();
            }
            assertDeletedMediaEmpty(facade);

            // Was not trashed but is now trashed
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ true,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was not trashed but the newMedia is trashed.").fail();
            }
            assertDeletedMedia(facade, ID1);

            // Was trashed but is now neither trashed nor pending
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ true, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was trashed but the newMedia is neither trashed nor pending.")
                        .fail();
            }
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testDeletedMedia_onUpdate_pending() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was pending but is now neither trashed nor pending
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ true, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was pending but the newMedia is neither trashed nor pending.")
                        .fail();
            }
            assertDeletedMediaEmpty(facade);

            // Was not pending but is now pending
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ true,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was not pending but the newMedia is pending.").fail();
            }
            assertDeletedMedia(facade, ID1);

            // Was pending but is now neither trashed nor pending
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ true, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update, when the oldMedia "
                                + "was pending but the newMedia is neither trashed nor pending.")
                        .fail();
            }
            assertDeletedMediaEmpty(facade);
        }
    }

    @Test
    public void testOnUpdate_visibleFavorite() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was favorite but is now not favorited
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ true, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update with visible "
                                + "favorite, when the oldMedia "
                                + "was favorite but the newMedia is not favorite.").fail();
            }

            // Was not favorite but is now favorited
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ true,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update with visible "
                                + "favorite, when the oldMedia "
                                + "was not favorite but the newMedia is favorite.").fail();
            }
        }
    }

    @Test
    public void testOnUpdate_hiddenFavorite() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was favorite but is now not favorited
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ true, /* newIsTrashed */ true,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ true, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update with hidden "
                                + "favorite, when the oldMedia was favorite but the newMedia is "
                                + "not favorite.").fail();
            }

            // Was not favorite but is now favorited
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ true, /* newIsPending */ true,
                    /* oldIsFavorite */ false, /* newIsFavorite */ true,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return false but returned true on update with hidden "
                                + "favorite, when the oldMedia was not favorite but the newMedia "
                                + "is favorite.").fail();
            }
        }
    }

    @Test
    public void testOnUpdate_visibleSpecialFormat() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was _SPECIAL_FORMAT_NONE but is now _SPECIAL_FORMAT_GIF
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_GIF)) {
                assertWithMessage(
                        "Expected to return true but returned false on update with visible "
                                + "special format, when the oldSpecialFormat was NONE but the "
                                + "newSpecialFormat is GIF.").fail();
            }

            // Was _SPECIAL_FORMAT_GIF but is now _SPECIAL_FORMAT_NONE
            if (!facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_GIF,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return true but returned false on update with visible "
                                + "special format, when the oldSpecialFormat was GIF but the "
                                + "newSpecialFormat is NONE.").fail();
            }
        }
    }

    @Test
    public void testOnUpdate_hiddenSpecialFormat() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Was _SPECIAL_FORMAT_NONE but is now _SPECIAL_FORMAT_GIF
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ true, /* newIsTrashed */ true,
                    /* oldIsPending */ false, /* newIsPending */ false,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_NONE,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_GIF)) {
                assertWithMessage(
                        "Expected to return false but returned true on update with hidden special"
                                + " format, when the oldSpecialFormat was NONE but the "
                                + "newSpecialFormat is GIF.").fail();
            }

            // Was _SPECIAL_FORMAT_GIF but is now _SPECIAL_FORMAT_NONE
            if (facade.onFileUpdated(ID1,
                    FileColumns.MEDIA_TYPE_IMAGE, FileColumns.MEDIA_TYPE_IMAGE,
                    /* oldIsTrashed */ false, /* newIsTrashed */ false,
                    /* oldIsPending */ true, /* newIsPending */ true,
                    /* oldIsFavorite */ false, /* newIsFavorite */ false,
                    /* oldSpecialFormat */ _SPECIAL_FORMAT_GIF,
                    /* newSpecialFormat */ _SPECIAL_FORMAT_NONE)) {
                assertWithMessage(
                        "Expected to return false but returned true on update with hidden special"
                                + " format, when the oldSpecialFormat was GIF but the "
                                + "newSpecialFormat is NONE.").fail();
            }
        }
    }

    @Test
    public void testDeletedMedia_onDelete() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            if (facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_NONE)) {
                assertWithMessage(
                        "Expected to return false when the mediaType is NONE, but returned true "
                                + "on delete.").fail();
            }
            assertDeletedMediaEmpty(facade);

            if (!facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_IMAGE)) {
                assertWithMessage(
                        "Expected to return true when the mediaType is IMAGE, but returned false "
                                + "on delete.").fail();
            }
            assertDeletedMedia(facade, ID1);

            if (facade.onFileDeleted(ID1, FileColumns.MEDIA_TYPE_NONE)) {
                assertWithMessage(
                        "Expected to return false when the mediaType is NONE, but returned true "
                                + "on delete.").fail();
            }
            assertDeletedMedia(facade, ID1);
        }
    }

    @Test
    public void testQueryMedia_match() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Intentionally associate <date_taken_ms2 with generation_modifed1>
            // and <date_taken_ms1 with generation_modifed2> below.
            // This allows us verify that the sort order from queryMediaGeneration
            // is based on date_taken and not generation_modified.
            ContentValues cv = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS1);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(2);
                assertCursorExtras(cursor);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS2);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(GENERATION_MODIFIED1,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 10,
                    /*pageToken */ null, /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES "
                                + " with generation as GENERATION_MODIFIED1, and pageSize as 10")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                //PAGE_TOKEN will also be set since pageSize is not -1.
                assertCursorExtras(cursor, EXTRA_SYNC_GENERATION, EXTRA_PAGE_SIZE,
                        EXTRA_PAGE_TOKEN);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMedia_noMatch() throws Exception {
        ContentValues cvPending = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        cvPending.put(MediaColumns.IS_PENDING, 1);

        ContentValues cvTrashed = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED2);
        cvTrashed.put(MediaColumns.IS_TRASHED, 1);

        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvPending));
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cvTrashed));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Expected 0 rows on querying TABLES_FILES when the media present is "
                                + "trashed or pending.")
                        .that(cursor.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryMedia_withDateModified() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));
            long dateModifiedSeconds1 = DATE_TAKEN_MS1 / 1000;
            long dateModifiedSeconds2 = DATE_TAKEN_MS2 / 1000;
            // Intentionally associate <dateModifiedSeconds2 with generation_modifed1>
            // and <dateModifiedSeconds1 with generation_modifed2> below.
            // This allows us verify that the sort order from queryMediaGeneration
            // is based on date_taken and _id and not generation_modified.
            ContentValues cv = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED1);
            cv.remove(MediaColumns.DATE_TAKEN);
            cv.put(MediaColumns.DATE_MODIFIED, dateModifiedSeconds2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_MODIFIED, dateModifiedSeconds1);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, dateModifiedSeconds2 * 1000);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID1, dateModifiedSeconds1 * 1000);
            }

            try (Cursor cursor = facade.queryMedia(GENERATION_MODIFIED1,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ -1,
                    /*pageToken */ null, /* sortOrder */ -1)) {
                assertWithMessage(
                        "Number of rows on querying TABLE_FILES with modified date for "
                                + "(generation: "
                                + "GENERATION_MODIFIED1, albumId: null, mimeType: null, pageSize:"
                                + " -1) is")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, dateModifiedSeconds1 * 1000);
            }
        }
    }

    @Test
    public void testQueryMedia_withMimeType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert image
            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage("Number of rows on querying TABLES_FILES for all media is")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, VIDEO_MIME_TYPES_QUERY, /* pageSize*/ -1,
                    /* pageToken*/ null, /* sortOrder */ -1)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for media with mime type VIDEO is")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, IMAGE_MIME_TYPES_QUERY, /* pageSize*/ -1,
                    /* pageToken*/ null, /* sortOrder */ -1)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for media with mime type IMAGE is")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMedia_withAlbum() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            initMediaInAllAlbums(helper);

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ -1,
                    ALBUM_ID_CAMERA, /* mimeType */ null, /* pageSize*/ 20,
                    /* pageToken*/ null, /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for media "
                                + "from Camera album")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                //PAGE_TOKEN will also be set since pageSize is not -1.
                assertCursorExtras(cursor, EXTRA_ALBUM_ID, EXTRA_PAGE_SIZE, EXTRA_PAGE_TOKEN);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ -1,
                    ALBUM_ID_SCREENSHOTS, /* mimeType */ null, /* pageSize*/ -1,
                    /* pageToken*/ null, /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for media from "
                                + "Screenshots album")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                assertCursorExtras(cursor, EXTRA_ALBUM_ID);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS2);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ -1,
                    ALBUM_ID_DOWNLOADS, /* mimeType */ null, /* pageSize*/ 10,
                    /* pageToken*/ null, /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for media from "
                                + "Downloads album with pageSize 10")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                //PAGE_TOKEN will also be set since pageSize is not -1.
                assertCursorExtras(cursor, EXTRA_ALBUM_ID, EXTRA_PAGE_SIZE, EXTRA_PAGE_TOKEN);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID3, DATE_TAKEN_MS3);
            }
        }
    }

    @Test
    public void testQueryMedia_withAlbumAndMimeType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert image
            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            cv.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for all media")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    ALBUM_ID_SCREENSHOTS, IMAGE_MIME_TYPES_QUERY, /* pageSize*/ -1,
                    /* pageToken*/ null, /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for media from "
                                + "Screenshots album with IMAGE mime type")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    ALBUM_ID_CAMERA, VIDEO_MIME_TYPES_QUERY, /* pageSize*/ -1,
                    /* pageToken*/ null, /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for media from "
                                + "Camera album with VIDEO mime type")
                        .that(cursor.getCount())
                        .isEqualTo(0);

            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    ALBUM_ID_CAMERA, IMAGE_MIME_TYPES_QUERY, /* pageSize*/ -1,
                    /* pageToken*/ null, /* sortOrder */ -1)) {
                assertWithMessage(
                        "Number of rows on querying TABLES_FILES for media with ALBUM_ID_CAMERA "
                                + "and IMAGE_MIME_TYPES_QUERY is")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMedia_withPageSize_returnsCorrectSortOrder() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert 5 images with date_taken non-null
            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS3);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS4);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS5);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            // Verify that media returned in descending order of date_taken, _id
            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 2,
                    /* pageToken*/ null, /* sortOrder */ -1)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID5, DATE_TAKEN_MS5);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID4, DATE_TAKEN_MS4);
            }

            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 3,
                    /* pageToken*/ DATE_TAKEN_MS4 + "|" + ID4, /* sortOrder */ -1)) {
                assertThat(cursor.getCount()).isEqualTo(3);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID3, DATE_TAKEN_MS3);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS2);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMedia_withPageSizeMissingPageToken_returnsCorrectSortOrder()
            throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert 5 images, 2 with date_taken non-null and 3 with date_taken null
            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.remove(DATE_TAKEN);

            cv.put(MediaColumns.DATE_MODIFIED, DATE_MODIFIED_MS1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_MODIFIED, DATE_MODIFIED_MS2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_MODIFIED, DATE_MODIFIED_MS3);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            // Verify that media returned in descending order of date_taken, _id
            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 2,
                    /* pageToken*/ null, /* sortOrder */ -1)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID5, Long.valueOf(DATE_MODIFIED_MS3) * 1000);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID4, Long.valueOf(DATE_MODIFIED_MS2) * 1000);
            }

            String pageToken = Long.valueOf(DATE_MODIFIED_MS2) * 1000 + "|" + ID4;
            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 2,
                    /* pageToken*/ pageToken, /* sortOrder */ -1)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID3, Long.valueOf(DATE_MODIFIED_MS1) * 1000);

                cursor.moveToNext();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS2);
            }

            pageToken = DATE_TAKEN_MS2 + "|" + ID2;
            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 2,
                    /* pageToken*/ pageToken, /* sortOrder */ -1)) {
                assertThat(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            pageToken = DATE_MODIFIED_MS1 + "|" + ID1;
            try (Cursor cursor = facade.queryMedia(/* generation */ 0,
                    /* albumId */ null, /* mimeType */ null, /* pageSize*/ 2,
                    /* pageToken*/ pageToken, /* sortOrder */ -1)) {
                assertThat(cursor.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testGetMediaCollectionInfoFiltering() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            cv.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            cv.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            Bundle bundle = facade.getMediaCollectionInfo(/* generation */ 0);
            assertMediaCollectionInfo(facade, bundle, /* generation */ 2);

            bundle = facade.getMediaCollectionInfo(GENERATION_MODIFIED1);
            assertMediaCollectionInfo(facade, bundle, /* generation */ 2);

            bundle = facade.getMediaCollectionInfo(GENERATION_MODIFIED2);
            assertMediaCollectionInfo(facade, bundle, /* generation */ 0);
        }
    }

    @Test
    public void testGetMediaCollectionInfoVolumeNames() throws Exception {
        VolumeCache mockVolumeCache = mock(VolumeCache.class);
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mockVolumeCache);

            HashSet<String> volumes = new HashSet<>();
            volumes.add("foo");
            volumes.add("bar");
            when(mockVolumeCache.getExternalVolumeNames()).thenReturn(volumes);

            final String expectedMediaCollectionId = MediaStore.getVersion(sIsolatedContext)
                    + ":" + "bar:foo";

            final Bundle bundle = facade.getMediaCollectionInfo(/* generation */ 0);
            final String mediaCollectionId = bundle.getString(
                    MediaCollectionInfo.MEDIA_COLLECTION_ID);

            assertWithMessage("The mediaCollectionId is")
                    .that(mediaCollectionId).isEqualTo(expectedMediaCollectionId);
        }
    }

    @Test
    public void testGetMediaCollectionInfoWithDeleted() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            ContentValues cvDeleted = new ContentValues();
            cvDeleted.put(COLUMN_OLD_ID, ID2);
            cvDeleted.put(MediaColumns.GENERATION_MODIFIED, GENERATION_MODIFIED2);
            helper.runWithTransaction(db -> db.insert(TABLE_DELETED_MEDIA, null, cvDeleted));

            Bundle bundle = facade.getMediaCollectionInfo(/* generation */ 0);
            assertMediaCollectionInfo(facade, bundle, /* generation */ 2);
        }
    }

    @Test
    public void testQueryAlbumsEmpty() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            ContentValues cv = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES with for all media is")
                        .that(cursor.getCount())
                        .isEqualTo(1);
            }

            try (Cursor cursor = facade.queryAlbums(/* mimeType */ null)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for albums is")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }
        }
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_LOCAL_MEDIA_PROVIDER_CAPABILITIES)
    public void testQueryAlbums() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            initMediaInAllAlbums(helper);

            try (Cursor cursor = queryAllMedia(facade)) {
                assertThat(cursor.getCount()).isEqualTo(3);
            }

            try (Cursor cursor = facade.queryAlbums(/* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(3);

                // We verify the order of the albums:
                // Camera, Screenshots and Downloads
                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_CAMERA, DATE_TAKEN_MS1, /* count */ 1);

                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_SCREENSHOTS, DATE_TAKEN_MS2,
                        /* count */ 1);

                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_DOWNLOADS, DATE_TAKEN_MS3,
                        /* count */ 1);
            }
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_LOCAL_MEDIA_PROVIDER_CAPABILITIES)
    public void testQueryAlbums_withLocalCategoriesEnabled() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            initMediaInAllAlbums(helper);

            try (Cursor cursor = queryAllMedia(facade)) {
                assertThat(cursor.getCount()).isEqualTo(3);
            }

            try (Cursor cursor = facade.queryAlbums(/* mimeType */ null)) {
                assertThat(cursor.getCount()).isEqualTo(2);

                // We verify the order of the albums:
                // Camera, Screenshots
                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_CAMERA, DATE_TAKEN_MS1, /* count */ 1);

                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_SCREENSHOTS, DATE_TAKEN_MS2,
                        /* count */ 1);
            }
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_LOCAL_MEDIA_PROVIDER_CAPABILITIES)
    public void testQueryAlbums_categoriesEnabled_downloadsIsPartOfCollection() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            initMediaInAllAlbums(helper);

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for all media")
                        .that(cursor.getCount()).isEqualTo(3);
            }

            try (Cursor cursor = facade.queryAlbums(/* mimeType */ null)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for albums")
                        .that(cursor.getCount()).isEqualTo(2);

                // We verify the order of the albums:
                // Camera, Screenshots and Downloads
                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_CAMERA, DATE_TAKEN_MS1, /* count */ 1);

                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_SCREENSHOTS, DATE_TAKEN_MS2,
                        /* count */ 1);
            }

            try (Cursor cursor = facade.queryMediaCategories(/* mimeTypes */ null)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for categories")
                        .that(cursor.getCount()).isEqualTo(1);

                cursor.moveToFirst();
                assertWithMessage("Incorrect category id found in the cursor")
                        .that(getCursorString(cursor,
                                CloudMediaProviderContract.MediaCategoryColumns.ID))
                        .isEqualTo(CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS);
            }
        }
    }

    @Test
    public void testQueryAlbumsMimeType() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert image in camera album
            ContentValues cv1 = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            cv1.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv1));

            // Insert video in camera album
            ContentValues cv2 = getContentValues(DATE_TAKEN_MS5, GENERATION_MODIFIED5);
            cv2.put(FileColumns.MIME_TYPE, VIDEO_MIME_TYPE);
            cv2.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_VIDEO);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv2));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for all media")
                        .that(cursor.getCount())
                        .isEqualTo(2);
            }

            try (Cursor cursor = facade.queryAlbums(IMAGE_MIME_TYPES_QUERY)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for albums with "
                                + "IMAGE_MIME_TYPES_QUERY")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                // We verify the order of the albums only the image in camera is shown
                cursor.moveToNext();
                assertAlbumColumns(facade, cursor, ALBUM_ID_CAMERA, DATE_TAKEN_MS1, /* count */ 1);
            }
        }
    }

    @Test
    public void testOrderOfLocalAlbumIds() {
        // Camera, ScreenShots, Downloads
        assertWithMessage("Local album at 0th index is")
                .that(ExternalDbFacade.LOCAL_ALBUM_IDS[0])
                .isEqualTo(ALBUM_ID_CAMERA);
        assertWithMessage("Local album at 1st index is")
                .that(ExternalDbFacade.LOCAL_ALBUM_IDS[1])
                .isEqualTo(ALBUM_ID_SCREENSHOTS);
        assertWithMessage("Local album at 2nd index is")
                .that(ExternalDbFacade.LOCAL_ALBUM_IDS[2])
                .isEqualTo(ALBUM_ID_DOWNLOADS);
    }

    @Test
    public void testQueryCategoriesEmpty() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES with for all media")
                        .that(cursor.getCount())
                        .isEqualTo(1);
            }

            try (Cursor cursor = facade.queryMediaCategories(/* mimeType */ null)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for categories")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryMediaCategories_returnsBothLocalCategories() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            initMediaCategories(helper);

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES with for all media")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMediaCategories(/* mimeType */ null)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for categories")
                        .that(cursor.getCount())
                        .isEqualTo(2);

                final List<String> expectedID = new ArrayList<>();
                expectedID.add(CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS);
                expectedID.add(CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS);
                cursor.moveToFirst();
                int index = 0;
                do {
                    assertWithMessage("Incorrect category id found in the cursor")
                            .that(getCursorString(cursor,
                                    CloudMediaProviderContract.MediaCategoryColumns.ID))
                            .isEqualTo(expectedID.get(index++));
                } while (cursor.moveToNext());
            }
        }
    }

    @Test
    public void testQueryMediaCategories_deviceCategory_coverIdsInOrder() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // First 2 media items belong to the same media set,
            // with the second having the later date_taken value
            // The last media item belong to different media set and
            // has the same date_taken as the second media item, but has a higher _id
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(MediaColumns._ID, ID1);
            contentValues.put(MediaColumns.BUCKET_ID, ID4);
            contentValues.put(MediaColumns.RELATIVE_PATH, "");
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            contentValues.put(MediaColumns._ID, ID2);
            contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            contentValues.put(MediaColumns._ID, ID3);
            contentValues.put(MediaColumns.BUCKET_ID, ID5);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES with for all media")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMediaCategories(/* mimeType */ null)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for categories")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertWithMessage("Incorrect MEDIA_COVER_ID1 found, implying wrong order")
                        .that(getCursorString(cursor,
                                CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID1))
                        .isEqualTo(String.valueOf(ID3));

                assertWithMessage("Incorrect MEDIA_COVER_ID2 found, implying wrong order")
                        .that(getCursorString(cursor,
                                CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID2))
                        .isEqualTo(String.valueOf(ID2));

                assertWithMessage("Incorrect MEDIA_COVER_ID3 found, implying wrong order")
                        .that(getCursorString(cursor,
                                CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID3))
                        .isNull();

                assertWithMessage("Incorrect MEDIA_COVER_ID4 found, implying wrong order")
                        .that(getCursorString(cursor,
                                CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID4))
                        .isNull();
            }
        }
    }

    @Test
    public void testQueryMediaCategories_appsCategory_coverIdsInOrder() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // First 2 media items belong to the same media set,
            // with the second having the later date_taken value
            // The last media item belong to different media set and
            // has the same date_taken as the second media item, but has a higher _id
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(FileColumns._USER_ID, sIsolatedContext.getUserId());
            contentValues.put(MediaColumns._ID, ID1);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            contentValues.put(MediaColumns._ID, ID2);
            contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            contentValues.put(MediaColumns._ID, ID3);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES with for all media")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMediaCategories(/* mimeType */ null)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for categories")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                // Media cover ids for App Folder Collection is the app icon res id uri
                String expectedCoverIdForPackage1 = String.format(
                        Locale.ROOT,
                        "%s/%s/%s",
                        PACKAGE_NAME1, RES_ID, sIsolatedContext.getUserId());
                String expectedCoverIdForPackage2 = String.format(
                        Locale.ROOT,
                        "%s/%s/%s",
                        PACKAGE_NAME2, RES_ID, sIsolatedContext.getUserId());
                cursor.moveToFirst();
                assertWithMessage("Incorrect MEDIA_COVER_ID1 found, implying wrong order")
                        .that(getCursorString(cursor,
                                CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID1))
                        .isEqualTo(expectedCoverIdForPackage2);

                assertWithMessage("Incorrect MEDIA_COVER_ID2 found, implying wrong order")
                        .that(getCursorString(cursor,
                                CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID2))
                        .isEqualTo(expectedCoverIdForPackage1);

                assertWithMessage("Incorrect MEDIA_COVER_ID3 found, implying wrong order")
                        .that(getCursorString(cursor,
                                CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID3))
                        .isNull();

                assertWithMessage("Incorrect MEDIA_COVER_ID4 found, implying wrong order")
                        .that(getCursorString(cursor,
                                CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID4))
                        .isNull();
            }
        }
    }


    @Test
    public void testQueryMediaSets_noCategoryTypePresent_returnsEmpty() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Adding media to Camera and Screenshots directory,
            // these are excluded when querying for media set that belong to categories
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, SYSTEM_PACKAGE);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            contentValues.put(MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_SCREENSHOTS);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(2);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS, null, -1,
                    null)) {
                assertWithMessage(
                        "Unexpected number of media sets on querying TABLE_FILES for "
                                + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS.")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS, null, -1, null)) {
                assertWithMessage(
                        "Unexpected number of media sets on querying TABLE_FILES for "
                                + "MEDIA_CATEGORY_TYPE_APP_FOLDERS.")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryMediaSets_mediaSetForCategoryTypeDeviceFolders() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Downloads media set should be first in list irrespective of the date taken
            ContentValues cv_download = getContentValues(DATE_TAKEN_MS4, GENERATION_MODIFIED3);
            cv_download.put(MediaColumns.IS_DOWNLOAD, 1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv_download));

            // Intentionally insert DATE_TAKEN_MS1 before DATE_TAKEN_MS2,
            // this allows us to verify that the sort order is based on date_taken.
            // Also keep bucket_display_name same and the bucket_id different for the two entries,
            // this allows us to verify that media_set is grouped on bucket_id.
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED2);
            contentValues.put(MediaColumns.BUCKET_ID, ID1);
            contentValues.put(MediaColumns.RELATIVE_PATH, "");
            contentValues.put(MediaColumns.BUCKET_DISPLAY_NAME, FOLDER_NAME1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            contentValues.put(MediaColumns.BUCKET_ID, ID2);
            contentValues.put(MediaColumns.RELATIVE_PATH, "");
            contentValues.put(MediaColumns.BUCKET_DISPLAY_NAME, FOLDER_NAME1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS, null, -1,
                    null)) {
                assertWithMessage(
                        "Unexpected number of media sets on querying TABLE_FILES for "
                                + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS.")
                        .that(cursor.getCount())
                        .isEqualTo(3);

                // Downloads will always be the first media set given that it is present
                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        ALBUM_ID_DOWNLOADS, DATE_TAKEN_MS4, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID2), DATE_TAKEN_MS2, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID1), DATE_TAKEN_MS1, 1);
            }
        }
    }

    @Test
    public void testQueryMediaSets_mediaSetForCategoryTypeAppFolders() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Media with same package name should be clubbed into one media set
            // Media set ordering should depend on the date taken
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS3);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME2);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS, null, -1, null)) {
                assertWithMessage(
                        "Unexpected number of media sets on querying TABLE_FILES for "
                                + "MEDIA_CATEGORY_TYPE_APP_FOLDERS.")
                        .that(cursor.getCount())
                        .isEqualTo(2);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME2, DATE_TAKEN_MS3, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME1, DATE_TAKEN_MS2, 2);
            }
        }
    }

    @Test
    public void testQueryMediaSets_mimeTypesNotMatch() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);
            contentValues.put(MediaColumns.BUCKET_ID, ID1);
            contentValues.put(MediaColumns.RELATIVE_PATH, "");
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(1);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS, null, -1,
                    null)) {
                assertWithMessage(
                        "Unexpected number of media sets on querying TABLE_FILES for "
                                + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS.")
                        .that(cursor.getCount())
                        .isEqualTo(1);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS, null, -1, null)) {
                assertWithMessage(
                        "Unexpected number of media sets on querying TABLE_FILES for "
                                + "MEDIA_CATEGORY_TYPE_APP_FOLDERS.")
                        .that(cursor.getCount())
                        .isEqualTo(1);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                    VIDEO_MIME_TYPES_QUERY, -1,
                    null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS with video mime type")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                    VIDEO_MIME_TYPES_QUERY, -1,
                    null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_APP_FOLDERS with video mime type")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryMediaSets_dateTakenPresentForDeviceFolders_returnsCorrectSortOrder() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert 5 images with non-null date_taken and non_null bucket_id (device folders)
            // One of the image has is_download = 1
            initMediaSetsWithDateTaken(helper);

            // Verify that media returned in descending order of date_taken, _id
            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                    /* mimeType */ null, /* pageSize*/ 3, /* pageToken*/ null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS with pageSize 2")
                        .that(cursor.getCount()).isEqualTo(3);

                // Downloads media set always comes on top
                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        ALBUM_ID_DOWNLOADS, DATE_TAKEN_MS4, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID5), DATE_TAKEN_MS5, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID4), DATE_TAKEN_MS4, 1);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                    /* mimeType */ null, /* pageSize*/ 3,
                    /* pageToken*/ DATE_TAKEN_MS4 + "|" + ID4)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS with pageSize 2 and "
                        + "pageToken 'DATE_TAKEN_MS4|ID4'")
                        .that(cursor.getCount()).isEqualTo(3);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID3), DATE_TAKEN_MS3, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID2), DATE_TAKEN_MS2, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID1), DATE_TAKEN_MS1, 1);
            }
        }
    }

    @Test
    public void testQueryMediaSets_dateTakenPresentForAppFolders_returnsCorrectSortOrder() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert 5 images with non-null date_taken and non_null owner_package_name(app folders)
            // One of the image has is_download = 1
            initMediaSetsWithDateTaken(helper);

            // Verify that media returned in descending order of date_taken, _id
            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                    /* mimeType */ null, /* pageSize*/ 2, /* pageToken*/ null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_APP_FOLDERS with pageSize 2")
                        .that(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME5, DATE_TAKEN_MS5, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME3, DATE_TAKEN_MS3, 1);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                    /* mimeType */ null, /* pageSize*/ 3,
                    /* pageToken*/ DATE_TAKEN_MS3 + "|" + ID3)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_APP_FOLDERS with pageSize 2 and "
                        + "pageToken 'DATE_TAKEN_MS4|ID4'")
                        .that(cursor.getCount()).isEqualTo(2);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME2, DATE_TAKEN_MS2, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME1, DATE_TAKEN_MS1, 1);
            }
        }
    }

    @Test
    public void testQueryMediaSets_dateTakenMissingForDeviceFolders_returnsCorrectSortOrder() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert 5 images, 2 with non-null date_taken and 3 with null date_taken
            // First 2 images belong to same media set with bucket_id = 1
            initMediaSetsWithMissingDateTaken(helper);

            // Verify that media sets returned in descending order of date_taken, _id
            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS, /* mimeType */
                    null,
                    /* pageSize*/ 2, /* pageToken*/ null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS with pageSize 2 and "
                        + "pageToken NULL")
                        .that(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID5), DATE_MODIFIED_MS3 * 1000, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID4), DATE_MODIFIED_MS2 * 1000, 1);
            }

            String pageToken = DATE_MODIFIED_MS2 * 1000 + "|" + ID4;
            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS, /* mimeType */
                    null,
                    /* pageSize*/ 2, /* pageToken*/ pageToken)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS with pageSize 2 and "
                        + "pageToken " + pageToken)
                        .that(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID3), DATE_MODIFIED_MS1 * 1000, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID1), DATE_TAKEN_MS2, 2);
            }

            pageToken = DATE_TAKEN_MS2 + "|" + ID2;
            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS, /* mimeType */
                    null,
                    /* pageSize*/ 2, /* pageToken*/ pageToken)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS with pageSize 2 and "
                        + "pageToken " + pageToken)
                        .that(cursor.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryMediaSets_dateTakenMissingForAppFolders_returnsCorrectSortOrder() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert 5 images, 2 with non-null date_taken and 3 with null date_taken
            // First 2 images belong to same media set with owner_package_name = PACKAGE_NAME1
            initMediaSetsWithMissingDateTaken(helper);

            // Verify that media sets returned in descending order of date_taken, _id
            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS, /* mimeType */ null,
                    /* pageSize*/ 2, /* pageToken*/ null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_APP_FOLDERS with pageSize 2 and "
                        + "pageToken NULL")
                        .that(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME5, DATE_MODIFIED_MS3 * 1000, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME4, DATE_MODIFIED_MS2 * 1000, 1);
            }

            String pageToken = DATE_MODIFIED_MS2 * 1000 + "|" + ID4;
            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS, /* mimeType */ null,
                    /* pageSize*/ 2, /* pageToken*/ pageToken)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_APP_FOLDERS with pageSize 2 and "
                        + "pageToken " + pageToken)
                        .that(cursor.getCount()).isEqualTo(2);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME3, DATE_MODIFIED_MS1 * 1000, 1);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME1, DATE_TAKEN_MS2, 2);
            }

            pageToken = DATE_TAKEN_MS2 + "|" + ID2;
            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS, /* mimeType */ null,
                    /* pageSize*/ 2, /* pageToken*/ pageToken)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_APP_FOLDERS with pageSize 2 and "
                        + "pageToken " + pageToken)
                        .that(cursor.getCount()).isEqualTo(0);
            }
        }
    }

    @Test
    public void testQueryMediaSets_downloadsIncludesMediaInDownloadDirectory() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Downloaded media, irrespective of the relative_path
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(MediaColumns.IS_DOWNLOAD, 1);
            contentValues.put(MediaColumns._ID, ID1);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            // Media present in "Download/" but is not downloaded
            contentValues.put(MediaColumns.IS_DOWNLOAD, 0);
            contentValues.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_DOWNLOAD);
            contentValues.put(MediaColumns._ID, ID2);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            // Media present in a sub-folder of "Download/"
            contentValues.put(MediaColumns.IS_DOWNLOAD, 0);
            contentValues.put(MediaColumns._ID, ID3);
            contentValues.put(MediaColumns.RELATIVE_PATH, String.format(
                    Locale.ROOT,
                    "%s%s/",
                    ExternalDbFacade.RELATIVE_PATH_DOWNLOAD,
                    FOLDER_NAME1));
            contentValues.put(MediaColumns.BUCKET_ID, ID1);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                    /* mimeType */ null, /* pageSize */ -1, /* pageToken */ null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS")
                        .that(cursor.getCount())
                        .isEqualTo(2);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        ALBUM_ID_DOWNLOADS, DATE_TAKEN_MS1, 2);

                cursor.moveToNext();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID1), DATE_TAKEN_MS1, 1);
            }
        }
    }

    @Test
    public void testQueryMediaSets_downloadMediaSet_coverIdIsIdOfLatestMedia() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Media with date_taken as DATE_TAKEN_MS1 and _id as ID1
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(MediaColumns.IS_DOWNLOAD, 1);
            contentValues.put(MediaColumns._ID, ID1);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            // Media with date_modified as DATE_TAKEN_MS2 and _id as ID2
            contentValues.put(MediaColumns.IS_DOWNLOAD, 0);
            contentValues.put(MediaColumns._ID, ID2);
            contentValues.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_DOWNLOAD);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            // Media with date_taken as DATE_TAKEN_MS2 and _id as ID3
            contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            contentValues.put(MediaColumns._ID, ID3);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                    /* mimeType */ null, /* pageSize */ -1, /* pageToken */ null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        ALBUM_ID_DOWNLOADS, DATE_TAKEN_MS1, 3);
                assertWithMessage("Unexpected cover id for the media set")
                        .that(getCursorString(
                                cursor,
                                CloudMediaProviderContract.MediaSetColumns.MEDIA_COVER_ID))
                        .isEqualTo(String.valueOf(ID3));
            }
        }
    }

    @Test
    public void testQueryMediaSets_nonDownloadMediaSet_coverIdIsIdOfLatestMedia() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Media with date_taken as DATE_TAKEN_MS1 and _id as ID1
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(MediaColumns._ID, ID1);
            contentValues.put(MediaColumns.BUCKET_ID, ID4);
            contentValues.put(MediaColumns.RELATIVE_PATH, "");
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            // Media with date_modified as DATE_TAKEN_MS2 and _id as ID2
            contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            contentValues.put(MediaColumns._ID, ID2);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            // Media with date_taken as DATE_TAKEN_MS2 and _id as ID3
            contentValues.put(MediaColumns._ID, ID3);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));


            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                    /* mimeType */ null, /* pageSize */ -1, /* pageToken */ null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                        String.valueOf(ID4), DATE_TAKEN_MS2, 3);
                assertWithMessage("Unexpected cover id for the media set")
                        .that(getCursorString(
                                cursor,
                                CloudMediaProviderContract.MediaSetColumns.MEDIA_COVER_ID))
                        .isEqualTo(String.valueOf(ID3));
            }
        }
    }

    @Test
    public void testQueryMediaSets_appMediaSet_coverIdIsIdOfLatestMedia() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Media with date_taken as DATE_TAKEN_MS1 and _id as ID1
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(MediaColumns._ID, ID1);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            // Media with date_modified as DATE_TAKEN_MS2 and _id as ID2
            contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            contentValues.put(MediaColumns._ID, ID2);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            // Media with date_taken as DATE_TAKEN_MS2 and _id as ID3
            contentValues.put(MediaColumns._ID, ID3);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                    /* mimeType */ null, /* pageSize */ -1, /* pageToken */ null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_APP_FOLDERS")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME1, DATE_TAKEN_MS2, 3);
                assertWithMessage("Unexpected cover id for the media set")
                        .that(getCursorString(
                                cursor,
                                CloudMediaProviderContract.MediaSetColumns.MEDIA_COVER_ID))
                        .isEqualTo(String.valueOf(ID3));
            }
        }
    }

    @Test
    public void testQueryMediaInMediaSet() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            initMediaCategories(helper);

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for all media")
                        .that(cursor.getCount())
                        .isEqualTo(3);
            }

            try (Cursor cursor = facade.queryMediaInMediaSet(
                    initMediaSetId(
                            CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                            ALBUM_ID_DOWNLOADS),
                    /* mimeType */ null, /* pageSize*/ 10, /* pageToken*/ null,
                    /* sortOrder */ 1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for "
                                + "downloads media set")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                //PAGE_TOKEN will also be set since pageSize is not -1.
                assertCursorExtrasForMediaInMediaSet(
                        cursor,
                        EXTRA_PAGE_SIZE,
                        EXTRA_PAGE_TOKEN,
                        EXTRA_SORT_ORDER);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMediaInMediaSet(
                    initMediaSetId(
                            CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                            /* bucket id */ String.valueOf(ID4)),
                    new String[]{IMAGE_MIME_TYPE}, /* pageSize*/ 20, /* pageToken*/ null,
                    /* sortOrder */ 1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for "
                                + "device folder media set")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                // PAGE_TOKEN will also be set since pageSize is not -1.
                assertCursorExtrasForMediaInMediaSet(
                        cursor,
                        EXTRA_PAGE_SIZE,
                        EXTRA_PAGE_TOKEN,
                        EXTRA_SORT_ORDER,
                        Intent.EXTRA_MIME_TYPES);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID2, DATE_TAKEN_MS2);
            }

            try (Cursor cursor = facade.queryMediaInMediaSet(
                    initMediaSetId(
                            CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                            PACKAGE_NAME1),
                    /* mimeType */ null, /* pageSize*/ -1, /* pageToken*/ null,
                    /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for "
                                + "app folder media set")
                        .that(cursor.getCount())
                        .isEqualTo(1);
                assertCursorExtrasForMediaInMediaSet(cursor);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID3, DATE_TAKEN_MS3);
            }
        }
    }

    @Test
    public void testQueryMediaInMediaSet_withMimeType_returnsFilteredMedia() throws Exception {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Insert image
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(MediaColumns.RELATIVE_PATH, "");
            contentValues.put(MediaColumns.BUCKET_ID, FOLDER_NAME1);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);
            helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for all media")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }

            try (Cursor cursor = facade.queryMediaInMediaSet(
                    initMediaSetId(CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                            FOLDER_NAME1),
                    VIDEO_MIME_TYPES_QUERY,
                    /* pageSize*/ -1,
                    /* pageToken*/ null,
                    /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for media from "
                                + "FOLDER1 media set with VIDEO mime type")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMediaInMediaSet(
                    initMediaSetId(CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                            FOLDER_NAME1),
                    IMAGE_MIME_TYPES_QUERY,
                    /* pageSize*/ -1,
                    /* pageToken*/ null,
                    /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for media from "
                                + "FOLDER1 media set with IMAGE mime type")
                        .that(cursor.getCount())
                        .isEqualTo(1);
            }

            try (Cursor cursor = facade.queryMediaInMediaSet(
                    initMediaSetId(CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                            PACKAGE_NAME1),
                    VIDEO_MIME_TYPES_QUERY,
                    /* pageSize*/ -1,
                    /* pageToken*/ null,
                    /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for media from "
                                + "PACKAGE1 media set with VIDEO mime type")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }

            try (Cursor cursor = facade.queryMediaInMediaSet(
                    initMediaSetId(CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                            PACKAGE_NAME1),
                    IMAGE_MIME_TYPES_QUERY,
                    /* pageSize*/ -1,
                    /* pageToken*/ null,
                    /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for media from "
                                + "PACKAGE1 media set with IMAGE mime type")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaColumns(facade, cursor, ID1, DATE_TAKEN_MS1);
            }
        }
    }

    @Test
    public void testQueryMediaInMediaSet_deviceFolder_orderIsByLatestDateTakenThenLatestId() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            initMultipleMediaInOneMediaSet(helper);

            try (Cursor cursor = facade.queryMediaInMediaSet(
                    initMediaSetId(CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_DEVICE_FOLDERS,
                            FOLDER_NAME1),
                    /* mimeTypes */ null,
                    /* pageSize*/ -1,
                    /* pageToken*/ null,
                    /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for media from "
                                + "FOLDER1 media set.")
                        .that(cursor.getCount())
                        .isEqualTo(3);

                cursor.moveToFirst();
                assertWithMessage("Unexpected media id found, implying incorrect order")
                        .that(getCursorString(cursor, CloudMediaProviderContract.MediaColumns.ID))
                        .isEqualTo(String.valueOf(ID1));

                cursor.moveToNext();
                assertWithMessage("Unexpected media id found, implying incorrect order")
                        .that(getCursorString(cursor, CloudMediaProviderContract.MediaColumns.ID))
                        .isEqualTo(String.valueOf(ID3));

                cursor.moveToNext();
                assertWithMessage("Unexpected media id found, implying incorrect order")
                        .that(getCursorString(cursor, CloudMediaProviderContract.MediaColumns.ID))
                        .isEqualTo(String.valueOf(ID2));
            }
        }
    }

    @Test
    public void testQueryMediaInMediaSet_appFolder_orderIsByLatestDateTakenThenLatestId() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            initMultipleMediaInOneMediaSet(helper);

            try (Cursor cursor = facade.queryMediaInMediaSet(
                    initMediaSetId(CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                            PACKAGE_NAME1),
                    /* mimeTypes */ null,
                    /* pageSize*/ -1,
                    /* pageToken*/ null,
                    /* sortOrder */ -1)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLES_FILES for media from "
                                + "FOLDER1 media set.")
                        .that(cursor.getCount())
                        .isEqualTo(3);

                cursor.moveToFirst();
                assertWithMessage("Unexpected media id found, implying incorrect order")
                        .that(getCursorString(cursor, CloudMediaProviderContract.MediaColumns.ID))
                        .isEqualTo(String.valueOf(ID1));

                cursor.moveToNext();
                assertWithMessage("Unexpected media id found, implying incorrect order")
                        .that(getCursorString(cursor, CloudMediaProviderContract.MediaColumns.ID))
                        .isEqualTo(String.valueOf(ID3));

                cursor.moveToNext();
                assertWithMessage("Unexpected media id found, implying incorrect order")
                        .that(getCursorString(cursor, CloudMediaProviderContract.MediaColumns.ID))
                        .isEqualTo(String.valueOf(ID2));
            }
        }
    }

    @Test
    public void testQueryMediaSets_appMediaSet_nonLaunchableAppsAreSkipped() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Media owned by launchable app
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(MediaColumns._ID, ID1);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            // Media owned by non-launchable app
            contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            contentValues.put(MediaColumns._ID, ID2);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, SYSTEM_PACKAGE);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(2);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                    /* mimeType */ null, /* pageSize */ -1, /* pageToken */ null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_APP_FOLDERS")
                        .that(cursor.getCount())
                        .isEqualTo(1);

                cursor.moveToFirst();
                assertMediaSetColumns(cursor,
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                        PACKAGE_NAME1, DATE_TAKEN_MS1, 1);
            }
        }
    }

    @Test
    public void testQueryMediaSets_appMediaSetWithNoLaunchableApp_returnsEmptyCursor() {
        try (DatabaseHelper helper = new TestDatabaseHelper(sIsolatedContext)) {
            ExternalDbFacade facade = new ExternalDbFacade(sIsolatedContext, helper,
                    mock(VolumeCache.class));

            // Media owned by non-launchable app
            ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
            contentValues.put(MediaColumns._ID, ID1);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, SYSTEM_PACKAGE);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            // Media owned by non-launchable app
            contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
            contentValues.put(MediaColumns._ID, ID2);
            contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, SYSTEM_PACKAGE);
            helper.runWithTransaction(db ->
                    db.insert(TABLE_FILES, null, contentValues));

            try (Cursor cursor = queryAllMedia(facade)) {
                assertWithMessage(
                        "Unexpected number of rows on querying TABLE_FILES for all media.")
                        .that(cursor.getCount())
                        .isEqualTo(2);
            }

            try (Cursor cursor = facade.queryMediaSets(
                    CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_APP_FOLDERS,
                    /* mimeType */ null, /* pageSize */ -1, /* pageToken */ null)) {
                assertWithMessage("Unexpected number of media sets on querying TABLE_FILES for "
                        + "MEDIA_CATEGORY_TYPE_APP_FOLDERS")
                        .that(cursor.getCount())
                        .isEqualTo(0);
            }
        }
    }

    private void assertMediaSetColumns(
            Cursor cursor,
            String categoryType,
            String id,
            long dateTakenMs,
            long mediaCount) {
        int idIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaSetColumns.ID);
        int mediaCountIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaSetColumns.MEDIA_COUNT);
        int dateTakenIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS);
        String mediaSetId = id;
        if (!categoryType.isEmpty()) {
            mediaSetId = initMediaSetId(categoryType, id);
        }
        assertWithMessage("Incorrect id found for the media set.")
                .that(cursor.getString(idIndex))
                .isEqualTo(mediaSetId);
        assertWithMessage("Incorrect media count found for the media set.")
                .that(cursor.getLong(mediaCountIndex))
                .isEqualTo(mediaCount);
        // Downloads does not have the date_taken_millis field, hence return early
        if (ALBUM_ID_DOWNLOADS.equals(id)) {
            return;
        }
        assertWithMessage("Incorrect date taken found, implying incorrect order of media sets.")
                .that(cursor.getLong(dateTakenIndex))
                .isEqualTo(dateTakenMs);
    }

    private static void initMediaInAllAlbums(DatabaseHelper helper) {
        // Insert in camera album
        ContentValues cv1 = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        cv1.put(MediaColumns.RELATIVE_PATH, ExternalDbFacade.RELATIVE_PATH_CAMERA);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv1));

        // Insert in screenshots album
        ContentValues cv2 = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED2);
        cv2.put(
                MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + Environment.DIRECTORY_SCREENSHOTS + "/");
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv2));

        // Insert in download album
        ContentValues cv3 = getContentValues(DATE_TAKEN_MS3, GENERATION_MODIFIED3);
        cv3.put(MediaColumns.IS_DOWNLOAD, 1);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, cv3));
    }

    private static void initMediaCategories(DatabaseHelper helper) {
        // Insert a downloaded media
        ContentValues contentValues1 = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        contentValues1.put(MediaColumns._ID, ID1);
        contentValues1.put(MediaColumns.IS_DOWNLOAD, 1);
        contentValues1.put(MediaColumns.RELATIVE_PATH, "");
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues1));

        // Insert a user created media
        ContentValues contentValues2 = getContentValues(DATE_TAKEN_MS2, GENERATION_MODIFIED2);
        contentValues2.put(MediaColumns._ID, ID2);
        contentValues2.put(MediaColumns.BUCKET_ID, ID4);
        contentValues2.put(MediaColumns.RELATIVE_PATH, "");
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues2));

        // Insert an app created media
        ContentValues contentValues3 = getContentValues(DATE_TAKEN_MS3, GENERATION_MODIFIED3);
        contentValues3.put(MediaColumns._ID, ID3);
        // use self package name, as it is guaranteed to be present
        contentValues3.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);
        contentValues3.put(MediaColumns.RELATIVE_PATH, "");
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues3));
    }

    private boolean isEnglishLocale() {
        return Locale.getDefault().getLanguage().equals(Locale.ENGLISH.getLanguage());
    }

    private static void initMediaSetsWithDateTaken(DatabaseHelper helper) {
        // Insert 5 images with non-null date_taken, non-null bucket_id (device folders) and
        // non-null owner_package_name(app folders)
        ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        contentValues.put(MediaColumns.RELATIVE_PATH, "");
        contentValues.put(MediaColumns.BUCKET_ID, ID1);
        contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

        contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
        contentValues.put(MediaColumns.BUCKET_ID, ID2);
        contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME2);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

        contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS3);
        contentValues.put(MediaColumns.BUCKET_ID, ID3);
        contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME3);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

        contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS4);
        contentValues.put(MediaColumns.BUCKET_ID, ID4);
        contentValues.put(MediaColumns.IS_DOWNLOAD, 1);
        contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME4);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

        contentValues.remove(IS_DOWNLOAD);

        contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS5);
        contentValues.put(MediaColumns.BUCKET_ID, ID5);
        contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME5);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));
    }

    private static void initMediaSetsWithMissingDateTaken(DatabaseHelper helper) {
        // Insert 5 images, 2 with non-null date_taken and 3 with null date_taken
        // First 2 images belong to same media set
        // in both device folders category type and app folders category type
        ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        contentValues.put(MediaColumns.RELATIVE_PATH, "");
        contentValues.put(MediaColumns.BUCKET_ID, ID1);
        contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

        contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS2);
        contentValues.put(MediaColumns.BUCKET_ID, ID1);
        contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

        contentValues.remove(DATE_TAKEN);

        contentValues.put(MediaColumns.DATE_MODIFIED, DATE_MODIFIED_MS1);
        contentValues.put(MediaColumns.BUCKET_ID, ID3);
        contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME3);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

        contentValues.put(MediaColumns.DATE_MODIFIED, DATE_MODIFIED_MS2);
        contentValues.put(MediaColumns.BUCKET_ID, ID4);
        contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME4);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

        contentValues.put(MediaColumns.DATE_MODIFIED, DATE_MODIFIED_MS3);
        contentValues.put(MediaColumns.BUCKET_ID, ID5);
        contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME5);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));
    }

    private String initMediaSetId(String mediaCategoryType, String id2) {
        return mediaCategoryType + ":" + id2;
    }

    private ApplicationInfo createFakeAppInfo(String packageName, String appLabel, int resId) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = packageName;
        applicationInfo.nonLocalizedLabel = appLabel;
        applicationInfo.icon = resId;
        return applicationInfo;
    }

    private static void initMultipleMediaInOneMediaSet(DatabaseHelper helper) {
        // All the media belong to same device and app media set
        ContentValues contentValues = getContentValues(DATE_TAKEN_MS1, GENERATION_MODIFIED1);
        contentValues.put(MediaColumns.RELATIVE_PATH, "");
        contentValues.put(MediaColumns.BUCKET_ID, FOLDER_NAME1);
        contentValues.put(MediaColumns.OWNER_PACKAGE_NAME, PACKAGE_NAME1);

        // Media item with _id = ID2 and date_taken = DATE_TAKEN_MS1
        contentValues.put(MediaColumns._ID, ID2);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

        // Media item with _id = ID3 and date_taken = DATE_TAKEN_MS1
        contentValues.put(MediaColumns._ID, ID3);
        contentValues.put(MediaColumns.DATE_TAKEN, DATE_TAKEN_MS1);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));

        contentValues.remove(DATE_TAKEN);

        // Media item with _id = ID1 and date_modified = DATE_MODIFIED_MS1
        contentValues.put(MediaColumns._ID, ID1);
        contentValues.put(MediaColumns.DATE_MODIFIED, DATE_MODIFIED_MS1);
        helper.runWithTransaction(db -> db.insert(TABLE_FILES, null, contentValues));
    }

    private static void assertDeletedMediaEmpty(ExternalDbFacade facade) {
        try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
            assertWithMessage(
                    "Number of rows in the deleted_media table is")
                    .that(cursor.getCount()).isEqualTo(0);
        }
    }

    private static void assertDeletedMedia(ExternalDbFacade facade, long id) {
        try (Cursor cursor = facade.queryDeletedMedia(/* generation */ 0)) {
            assertWithMessage("Number of rows in the deleted_media table is")
                    .that(cursor.getCount())
                    .isEqualTo(1);

            cursor.moveToFirst();
            assertWithMessage("Row id for the deleted media is")
                    .that(cursor.getLong(0))
                    .isEqualTo(id);
            assertWithMessage("Name of the column at index 0 is")
                    .that(cursor.getColumnName(0))
                    .isEqualTo(CloudMediaProviderContract.MediaColumns.ID);
        }
    }

    private static void assertMediaColumns(ExternalDbFacade facade, Cursor cursor, long id,
            long dateTakenMs) {
        assertMediaColumns(facade, cursor, id, dateTakenMs, IS_FAVORITE);
    }

    private static void assertMediaColumns(ExternalDbFacade facade, Cursor cursor, long id,
            long dateTakenMs, int isFavorite) {
        assertMediaColumns(facade, cursor, id, dateTakenMs, isFavorite, IMAGE_MIME_TYPE);
    }

    private static void assertMediaColumns(ExternalDbFacade facade, Cursor cursor, long id,
            long dateTakenMs, int isFavorite, String mimeType) {
        int idIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaColumns.ID);
        int dateTakenIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS);
        int sizeIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaColumns.SIZE_BYTES);
        int mimeTypeIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.MIME_TYPE);
        int durationIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.DURATION_MILLIS);
        int isFavoriteIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.IS_FAVORITE);
        int heightIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaColumns.HEIGHT);
        int widthIndex = cursor.getColumnIndex(CloudMediaProviderContract.MediaColumns.WIDTH);
        int orientationIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.MediaColumns.ORIENTATION);

        assertWithMessage("Incorrect MediaColumns.ID in cursor.")
                .that(cursor.getLong(idIndex))
                .isEqualTo(id);
        assertWithMessage("Incorrect MediaColumns.DATE_TAKEN_MILLIS in cursor.")
                .that(cursor.getLong(dateTakenIndex))
                .isEqualTo(dateTakenMs);
        assertWithMessage("Incorrect MediaColumns.SIZE_BYTES in cursor")
                .that(cursor.getLong(sizeIndex))
                .isEqualTo(SIZE);
        assertWithMessage("Incorrect MediaColumns.MIME_TYPE in cursor.")
                .that(cursor.getString(mimeTypeIndex))
                .isEqualTo(mimeType);
        assertWithMessage("Incorrect MediaColumns.DURATION_MILLIS in cursor.")
                .that(cursor.getLong(durationIndex))
                .isEqualTo(DURATION_MS);
        assertWithMessage("Incorrect MediaColumns.IS_FAVORITE in cursor.")
                .that(cursor.getInt(isFavoriteIndex))
                .isEqualTo(isFavorite);
        assertWithMessage("Incorrect MediaColumns.HEIGHT in cursor.")
                .that(cursor.getInt(heightIndex))
                .isEqualTo(HEIGHT);
        assertWithMessage("Incorrect MediaColumns.WIDTH in cursor.")
                .that(cursor.getInt(widthIndex))
                .isEqualTo(WIDTH);
        assertWithMessage("Incorrect MediaColumns.ORIENTATION in cursor.")
                .that(cursor.getInt(orientationIndex))
                .isEqualTo(ORIENTATION);
    }

    private static void assertCursorExtras(Cursor cursor, String... honoredArg) {
        final Bundle bundle = cursor.getExtras();

        assertWithMessage("Cursor extras is")
                .that(bundle.getString(EXTRA_MEDIA_COLLECTION_ID))
                .isEqualTo(MediaStore.getVersion(sIsolatedContext));
        if (honoredArg != null) {
            assertWithMessage("Honored args are")
                    .that(bundle.getStringArrayList(EXTRA_HONORED_ARGS))
                    .containsExactlyElementsIn(Arrays.asList(honoredArg));
        }
    }

    private static void assertCursorExtrasForMediaInMediaSet(Cursor cursor, String... honoredArg) {
        final Bundle bundle = cursor.getExtras();

        if (honoredArg != null) {
            assertWithMessage("Honored args are")
                    .that(bundle.getStringArrayList(EXTRA_HONORED_ARGS))
                    .containsExactlyElementsIn(Arrays.asList(honoredArg));
        }
    }

    private static void assertAlbumColumns(ExternalDbFacade facade, Cursor cursor,
            String displayName, long dateTakenMs, long count) {
        int displayNameIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME);
        int idIndex = cursor.getColumnIndex(CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID);
        int dateTakenIndex = cursor.getColumnIndex(
                CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS);
        int countIndex = cursor.getColumnIndex(CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT);

        assertWithMessage("AlbumColumns.DISPLAY_NAME is")
                .that(cursor.getString(displayNameIndex)).isEqualTo(displayName);
        assertWithMessage("AlbumColumns.MEDIA_COVER_ID is")
                .that(cursor.getString(idIndex)).isNotNull();
        assertWithMessage("AlbumColumns.DATE_TAKEN_MILLIS is")
                .that(cursor.getLong(dateTakenIndex)).isEqualTo(dateTakenMs);
        assertWithMessage("AlbumColumns.MEDIA_COUNT is")
                .that(cursor.getLong(countIndex)).isEqualTo(count);
    }

    private static void assertMediaCollectionInfo(ExternalDbFacade facade, Bundle bundle,
            long expectedGeneration) {
        long generation = bundle.getLong(MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION);
        String mediaCollectionId = bundle.getString(MediaCollectionInfo.MEDIA_COLLECTION_ID);

        assertWithMessage("LAST_MEDIA_SYNC_GENERATION is")
                .that(generation).isEqualTo(expectedGeneration);
        assertWithMessage("MEDIA_COLLECTION_ID is")
                .that(mediaCollectionId).isEqualTo(MediaStore.getVersion(sIsolatedContext));
    }

    private static Cursor queryAllMedia(ExternalDbFacade facade) {
        return facade.queryMedia(/* generation */ -1, /* albumId */ null,
                /* mimeType */ null, /* pageSize*/ -1, /* pageToken*/ null, /* sortOrder */ -1);
    }

    private static ContentValues getContentValues(long dateTakenMs, long generation) {
        ContentValues cv = new ContentValues();
        cv.put(MediaColumns.SIZE, SIZE);
        cv.put(MediaColumns.DATE_TAKEN, dateTakenMs);
        cv.put(FileColumns.MIME_TYPE, IMAGE_MIME_TYPE);
        cv.put(FileColumns.MEDIA_TYPE, FileColumns.MEDIA_TYPE_IMAGE);
        cv.put(MediaColumns.DURATION, DURATION_MS);
        cv.put(MediaColumns.GENERATION_MODIFIED, generation);
        cv.put(MediaColumns.HEIGHT, HEIGHT);
        cv.put(MediaColumns.WIDTH, WIDTH);
        cv.put(MediaColumns.ORIENTATION, ORIENTATION);
        return cv;
    }

    private static class TestDatabaseHelper extends DatabaseHelper {
        public TestDatabaseHelper(Context context) {
            super(context, TEST_CLEAN_DB, 1, false, false, new ProjectionHelper(null, null), null,
                    null, null, null, false,
                    new TestDatabaseBackupAndRecovery(new TestConfigStore(),
                            new VolumeCache(context, new UserCache(context)), null));
        }
    }
}
