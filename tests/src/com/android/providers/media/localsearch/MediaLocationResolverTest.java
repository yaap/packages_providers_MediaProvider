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

package com.android.providers.media.localsearch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.location.Geocoder;
import android.os.Build;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.IsolatedContext;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.localsearch.MediaLocationResolver.LocationLabelInfo;
import com.android.providers.media.localsearch.MediaLocationResolver.MediaLocationInfo;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_PROCESSING)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
public class MediaLocationResolverTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final long TEST_URI_ID = 1;
    private static final long TEST_GEN_MODIFIED = 100;
    private static final double TEST_LAT = 37.422;
    private static final double TEST_LONG = -122.084;

    private Context mContext;
    private MediaLocationResolver mLocationResolver;
    // Use a direct executor to run async tasks on the same thread
    private final Executor mDirectExecutor = Runnable::run;

    /**
     * A test callback that wraps a mockable Runnable
     */
    private static class TestCallback implements MediaLocationResolver.LocationLabelsCallback {
        private final Runnable mRunnable;
        public Map<Long, LocationLabelInfo> resultMap;

        TestCallback(Runnable runnable) {
            mRunnable = runnable;
        }

        @Override
        public void onLabelsResult(Map<Long, LocationLabelInfo> labelsMap) {
            this.resultMap = labelsMap;
            // Notify the runnable that the callback was invoked
            mRunnable.run();
        }
    }

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        mContext = new IsolatedContext(context, "test", false);
        // Create the resolver using the static factory method
        Optional<MediaLocationResolver> resolver = MediaLocationResolver.getMediaLocationResolver(
                mContext, mDirectExecutor);

        // Skip tests if the resolver cannot be created (e.g., Geocoder not present, flag off)
        assumeTrue(resolver.isPresent());
        mLocationResolver = resolver.get();

        // Additional assumption to ensure Geocoder is indeed present for tests that need it.
        assumeTrue(Geocoder.isPresent());
    }

    /**
     * Tests that the generateLocationLabels callback is eventually invoked.
     * This test will make a REAL network call. It passes if the callback
     * (onLabelsResult) is called, regardless of whether the geocoding
     * succeeded (got a label) or failed (got an empty map).
     */
    @Test
    public void testGenerateLabels_callbackInvoked() {
        Runnable runnable = mock(Runnable.class);
        TestCallback callback = new TestCallback(runnable);

        List<MediaLocationInfo> infos = Collections.singletonList(
                new MediaLocationInfo(TEST_URI_ID, TEST_GEN_MODIFIED, TEST_LAT, TEST_LONG));

        mLocationResolver.generateLocationLabels(infos, callback);

        // Verify that onLabelsResult() was called within a 10-second timeout
        verify(runnable, timeout(10000)).run();

        // We can verify the map isn't null, but we can't reliably
        // assert its contents since the network might fail.
        assertNotNull(callback.resultMap);

        if (!callback.resultMap.isEmpty()) {
            assertTrue(callback.resultMap.containsKey(TEST_URI_ID));
            LocationLabelInfo labelInfo = callback.resultMap.get(TEST_URI_ID);
            assertEquals(TEST_GEN_MODIFIED, labelInfo.genModified);
            assertTrue(labelInfo.label.isPresent());
            String locationLabel = labelInfo.label.get();
            // Geocoder API can return an empty string due to any network error.
            if (!locationLabel.isEmpty()) {
                assertTrue(locationLabel.contains("mountain view")); // Locality
                assertTrue(locationLabel.contains("california")); // Admin Area
                assertTrue(locationLabel.contains("united states")); // Country Name
                assertTrue(locationLabel.contains("us")); // Country Code
            }
        }
    }

    /**
     * Tests the internal logic for invalid coordinates.
     * This should NOT make a network call and should be very fast.
     */
    @Test
    public void testGenerateLabels_invalidLatLong_returnsEmpty() {
        Runnable runnable = mock(Runnable.class);
        TestCallback callback = new TestCallback(runnable);

        List<MediaLocationInfo> infos = Collections.singletonList(// Invalid Latitude
                new MediaLocationInfo(TEST_URI_ID, TEST_GEN_MODIFIED, 200.0, TEST_LONG));

        mLocationResolver.generateLocationLabels(infos, callback);

        // Should return immediately
        verify(runnable, timeout(1000)).run();

        assertNotNull(callback.resultMap);
        assertTrue("Map should be empty for invalid coordinates", callback.resultMap.isEmpty());
    }

    /**
     * Tests that an IllegalArgumentException is thrown when the batch size limit is exceeded.
     */
    @Test
    public void testGenerateLabels_batchSizeExceeded_throwsException() {
        Runnable runnable = mock(Runnable.class);
        TestCallback callback = new TestCallback(runnable);

        List<MediaLocationInfo> infos = new ArrayList<>();
        for (int i = 0; i < MediaLocationResolver.MAX_BATCH_SIZE + 1; i++) {
            infos.add(new MediaLocationInfo(i, TEST_GEN_MODIFIED, TEST_LAT, TEST_LONG));
        }

        assertThrows(IllegalArgumentException.class, () -> {
            mLocationResolver.generateLocationLabels(infos, callback);
        });
    }

    /**
     * Tests that an empty list results in a callback with an empty map.
     */
    @Test
    public void testGenerateLabels_emptyList_returnsEmpty() {
        Runnable runnable = mock(Runnable.class);
        TestCallback callback = new TestCallback(runnable);

        List<MediaLocationInfo> infos = new ArrayList<>();

        mLocationResolver.generateLocationLabels(infos, callback);

        // Should return immediately
        verify(runnable, timeout(100)).run();

        assertNotNull(callback.resultMap);
        assertTrue("Map should be empty for an empty input list", callback.resultMap.isEmpty());
    }
}
