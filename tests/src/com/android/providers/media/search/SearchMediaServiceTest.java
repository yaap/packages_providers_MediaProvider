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
package com.android.providers.media.search;

import static com.android.providers.media.search.TestSearchMediaService.DEFAULT_ERROR_MESSAGE;
import static com.android.providers.media.search.TestSearchMediaService.DUMMY_SEARCH_RESULT_SIZE;
import static com.android.providers.media.search.TestSearchMediaService.SHOULD_THROW_ERROR;
import static com.android.providers.media.search.TestSearchMediaService.getSearchResults;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.ISearchMediaService;
import android.provider.MediaStore;
import android.provider.SearchMediaResult;
import android.provider.SearchMediaService;
import android.text.TextUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.R;
import com.android.providers.media.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
@RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_SEARCH)
public class SearchMediaServiceTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private final CountDownLatch mServiceLatch = new CountDownLatch(1);
    private ISearchMediaService mSearchMediaService;
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(SearchMediaService.SERVICE_INTERFACE);
        intent.setClassName("com.android.providers.media.tests",
                "com.android.providers.media.search.TestSearchMediaService");
        mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        mServiceLatch.await(3, TimeUnit.SECONDS);
        assumeNotNull(mSearchMediaService);
    }

    @After
    public void tearDown() throws Exception {
        mContext.unbindService(mServiceConnection);
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mSearchMediaService = ISearchMediaService.Stub.asInterface(iBinder);
            mServiceLatch.countDown();
        }
        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mSearchMediaService = null;
        }
    };

    @Test
    public void testSearchMediaSuccessScenario() throws Exception {
        Bundle searchParams = new Bundle();
        searchParams.putBoolean(SHOULD_THROW_ERROR, false);
        searchParams.putLong(DUMMY_SEARCH_RESULT_SIZE, 1000L);
        TestSearchMediaCallback callback = new TestSearchMediaCallback();

        mSearchMediaService.searchMedia(/* searchText */ "abc", /* searchId */ "123", searchParams,
                callback);
        callback.await(3, TimeUnit.SECONDS);

        assertNull(callback.getSearchMediaException());
        assertNotNull(callback.getSearchMediaResultPage().getSearchResults());
        assertCallbackHasAllSearchResults(callback, /* expectedRows */ 1000L, searchParams);
    }

    @Test
    public void testSearchMediaFailureScenario() throws Exception {
        Bundle searchParams = new Bundle();
        searchParams.putBoolean(SHOULD_THROW_ERROR, true);
        TestSearchMediaCallback callback = new TestSearchMediaCallback();

        mSearchMediaService.searchMedia(/* searchText */ "abc", /* searchId */ "123", searchParams,
                callback);
        callback.await(3, TimeUnit.SECONDS);

        assertNotNull(callback.getSearchMediaException());
        assertEquals(DEFAULT_ERROR_MESSAGE, callback.getSearchMediaException().getErrorMessage());
    }

    @Test
    public void testIsSemanticSearchSupportedApi() throws Exception {
        boolean semanticSearchSupported = mSearchMediaService.isSemanticSearchSupported();
        assertTrue(semanticSearchSupported);
    }

    @Test
    public void testGetPackageForDefaultSearchMediaServiceApiTest() {
        String expectedPackage = mContext.getResources().getString(
                R.string.config_default_search_media_service_package);

        // We are only testing for default search service. Empty string here denotes that OEM has
        // not defined their own search service and default search service will be used.
        assumeTrue(TextUtils.isEmpty(expectedPackage));

        String packageNameFromApi = MediaStore.getPackageForSearchMediaService(
                mContext.getContentResolver());

        // Empty package name means that default search service is not enabled. This may be because
        // appsearch is not available on the device or does not support required functionalities.
        assumeFalse(TextUtils.isEmpty(packageNameFromApi));

        assertEquals(getMediaProviderPackageName(), packageNameFromApi);
    }

    private static String getMediaProviderPackageName() {
        final Instrumentation inst = androidx.test.InstrumentationRegistry.getInstrumentation();
        final PackageManager packageManager = inst.getContext().getPackageManager();
        final ProviderInfo providerInfo = packageManager.resolveContentProvider(
                MediaStore.AUTHORITY, PackageManager.MATCH_ALL);
        return providerInfo.packageName;
    }

    private static void assertCallbackHasAllSearchResults(TestSearchMediaCallback callback,
            long expectedRows, Bundle searchParams) {
        List<SearchMediaResult> searchResults =
                callback.getSearchMediaResultPage().getSearchResults();

        assertEquals(expectedRows, searchResults.size());

        List<SearchMediaResult> expectedSearchResults = getSearchResults(searchParams);
        for (int i = 0; i < expectedRows; i++) {
            assertEquals(expectedSearchResults.get(i), searchResults.get(i));
        }
    }
}
