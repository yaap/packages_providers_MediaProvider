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

package com.android.providers.media.photopicker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.CloudMediaProviderContract;
import android.provider.MediaStore;
import android.util.Log;

import androidx.appsearch.app.SearchSpec;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.appsearch.AppSearchDbManager;
import com.android.providers.media.appsearch.MediaItem;
import com.android.providers.media.flags.Flags;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
@RequiresFlagsEnabled({Flags.FLAG_ENABLE_MEDIA_SEARCH,
        Flags.FLAG_ENABLE_LOCAL_SEARCH_FOR_PHOTOPICKER})
public class PhotoPickerProviderTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private static final String TAG = PhotoPickerProviderTest.class.getSimpleName();
    private static final int LOCAL_SEARCH_TIMEOUT_MILLIS = 3000;
    private static final int WORKER_DISCONNECT_TIME_MILLIS = (int)
            TestPhotoPickerLocalSearchManager.DISCONNECT_SEARCH_SERVICE_DELAY_SECONDS * 1000;

    private IsolatedContext mIsolatedContext;
    private Context mTargetContext;
    private PhotoPickerProvider mPhotoPickerProvider;
    private AppSearchDbManager mAppSearchDbManager;
    private PhotoPickerLocalSearchManager mPhotoPickerLocalSearchManager;

    @Before
    public void setUp() throws Exception {
        mTargetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mIsolatedContext = new IsolatedContext(mTargetContext, TAG, /* asFuseThread */ false);

        mPhotoPickerProvider = mIsolatedContext.getPhotoPickerProvider();
        mPhotoPickerLocalSearchManager = new TestPhotoPickerLocalSearchManager(mIsolatedContext);
        mPhotoPickerProvider.setPhotoPickerLocalSearchManager(mPhotoPickerLocalSearchManager);
        PhotoPickerLocalSearchManager.setInstance(mPhotoPickerLocalSearchManager);

        boolean isAppSearchDbSupported = true;
        try {
            mAppSearchDbManager = new AppSearchDbManager(mIsolatedContext);
        } catch (UnsupportedOperationException ex) {
            // Required appSearch features are not supported.
            isAppSearchDbSupported = false;
            mAppSearchDbManager = null;
        }
        assumeTrue(isAppSearchDbSupported);
        deleteAllDocumentsFromAppsearch();
    }

    @After
    public void tearDown() throws Exception {
        if (mAppSearchDbManager != null) {
            deleteAllDocumentsFromAppsearch();
            mAppSearchDbManager.disconnect();
            mAppSearchDbManager = null;
        }
    }

    @Test(timeout = LOCAL_SEARCH_TIMEOUT_MILLIS)
    public void testSearchSuccessWithinTheTimeLimit() {
        String searchText = "random_query_" + System.currentTimeMillis();
        Bundle extras = new Bundle();
        extras.putInt(CloudMediaProviderContract.EXTRA_PAGE_SIZE, 500);
        try (Cursor cursor = mPhotoPickerProvider.onSearchMedia(searchText, extras,
                /* cancellationSignal */ null)) {
            assertNotNull("Search cursor should not be null", cursor);
        }
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_ENABLE_MEDIA_SEARCH,
            Flags.FLAG_ENABLE_LOCAL_SEARCH_FOR_PHOTOPICKER,
            Flags.FLAG_ENABLE_MEDIA_PROCESSING})
    public void testSearchForDefaultSearchService() throws Exception {
        String packageForSearchMediaService =
                MediaStore.getPackageForSearchMediaService(mIsolatedContext.getContentResolver());
        assumeTrue(mTargetContext.getPackageName().equals(packageForSearchMediaService));

        mAppSearchDbManager = new AppSearchDbManager(mIsolatedContext);
        try {
            int numItemsToInsert = 1100;
            insertDataInAppSearch(numItemsToInsert);

            String searchText = "test";
            int pageSize = 500;
            int totalFetched = 0;
            String pageToken = null;

            Bundle extras = new Bundle();
            extras.putInt(CloudMediaProviderContract.EXTRA_PAGE_SIZE, pageSize);

            do {
                if (pageToken != null) {
                    extras.putString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN, pageToken);
                }

                try (Cursor cursor = mPhotoPickerProvider.onSearchMedia(searchText, extras,
                        /* cancellationSignal */ null)) {
                    assertNotNull("Cursor should not be null", cursor);

                    int count = cursor.getCount();
                    totalFetched += count;

                    Bundle cursorExtras = cursor.getExtras();
                    pageToken = cursorExtras != null ? cursorExtras.getString(
                            CloudMediaProviderContract.EXTRA_PAGE_TOKEN) : null;
                }
            } while (pageToken != null);

            assertEquals(numItemsToInsert, totalFetched);
        } finally {
            deleteAllDocumentsFromAppsearch();
        }
    }

    @Test
    public void testSearchServiceDisconnectsAfterDelay() {
        // Skip the test if there are no valid implementation of SearchMediaService
        String packageForSearchMediaService =
                MediaStore.getPackageForSearchMediaService(mIsolatedContext.getContentResolver());
        assumeFalse(packageForSearchMediaService.isEmpty());

        Bundle extras = new Bundle();
        extras.putInt(CloudMediaProviderContract.EXTRA_PAGE_SIZE, 10);
        mPhotoPickerProvider.onSearchMedia("test", extras, null);

        Assert.assertTrue(mPhotoPickerLocalSearchManager.isServiceConnectedLocked());

        // Wait for WorkManager to complete the disconnect
        SystemClock.sleep(WORKER_DISCONNECT_TIME_MILLIS + 2000);

        Assert.assertFalse(mPhotoPickerLocalSearchManager.isServiceConnectedLocked());
    }

    private void insertDataInAppSearch(int numItems) throws Exception {
        List<MediaItem> mediaItems = new ArrayList<>();
        long currentTime = System.currentTimeMillis();

        for (int i = 0; i < numItems; i++) {
            long fileId = 10000L + i;
            long dateTaken = currentTime - (i * 1000L);

            MediaItem item = new MediaItem(fileId, /* mediaType */ 1, dateTaken,
                    MediaStore.VOLUME_EXTERNAL_PRIMARY);
            item.setNamespace(AppSearchDbManager.NAMESPACE);
            item.setMetadataExtracted("This is test item " + i);
            item.setDirty(false);

            mediaItems.add(item);

            if (mediaItems.size() >= 1000) {
                mAppSearchDbManager.insertDocuments(mediaItems);
                mediaItems.clear();
            }
        }
        if (!mediaItems.isEmpty()) {
            mAppSearchDbManager.insertDocuments(mediaItems);
        }
    }

    private void deleteAllDocumentsFromAppsearch() {
        if (mAppSearchDbManager == null) return;
        try {
            SearchSpec searchSpec = new SearchSpec.Builder()
                    .addFilterNamespaces(AppSearchDbManager.NAMESPACE)
                    .build();
            mAppSearchDbManager.deleteDocuments("", searchSpec);
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete documents", e);
        }
    }
}
