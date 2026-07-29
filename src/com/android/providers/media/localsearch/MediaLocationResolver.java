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

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.providers.media.flags.Flags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Resolves media locations (latitude and longitude) into human-readable location labels
 * using reverse geocoding.
 * <p>This class utilizes the Android {@link Geocoder} API. The generated labels are localized
 * based on the device's default {@link Locale}.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class MediaLocationResolver {

    private static final String TAG = "MediaLocationResolver";

    // Maximum number of results to return from the geocoder. We only expect the most relevant
    // address for a given pair of latitude/longitude coordinates.
    private static final int MAX_RESULTS = 1;
    private static final long GEOCODE_BATCH_TIMEOUT_SECONDS = 60;

    // Maximum number of uris processed by the geocoder in a single batch.
    public static final int MAX_BATCH_SIZE = 100;

    // Maximum number of string tokens to be added in the location label.
    private static final int MAX_TOKENS = 100;

    private final Geocoder mGeocoder;
    private final Executor mExecutor;

    /**
     * Constructs a MediaLocationResolver.
     *
     * @param context The application context.
     * @param backgroundExecutor An executor to run the geocoding tasks on a background thread.
     */
    private MediaLocationResolver(@NonNull Context context, @NonNull Executor backgroundExecutor) {
        mGeocoder = new Geocoder(context, Locale.getDefault());
        mExecutor = backgroundExecutor;
    }

    /**
     * Creates an instance of {@link MediaLocationResolver}.
     *
     * @param context            The application context.
     * @param backgroundExecutor An executor to run the geocoding tasks on a background thread.
     * @return An {@link Optional} containing the {@link MediaLocationResolver} if available,
     * otherwise {@link Optional#empty()}.
     */
    public static Optional<MediaLocationResolver> getMediaLocationResolver(@NonNull Context context,
            @NonNull Executor backgroundExecutor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Geocoder.isPresent()
                && Flags.enableMediaProcessing()) {
            return Optional.of(new MediaLocationResolver(context, backgroundExecutor));
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Log.i(TAG, "MediaLocationResolver is only supported on Android T and above.");
            } else if (!Geocoder.isPresent()) {
                Log.w(TAG, "Geocoder not present on this device.");
            }
            return Optional.empty();
        }
    }

    /**
     * Generates location labels for a list of media items with coordinates.
     * The results are provided as a map via the callback. This operation is performed
     * asynchronously.
     * <p>The number of items in {@code mediaLocationInfos} should not exceed
     * {@link #MAX_BATCH_SIZE}.
     * Requests exceeding this limit will be rejected, and the callback will receive an empty map.
     *
     * @param mediaLocationInfos A list of {@link MediaLocationInfo} objects containing the ID and
     *                           coordinates for each media item.
     * @param callback           The callback to receive the map of media IDs to generated location
     *                           labels.
     */
    public void generateLocationLabels(@NonNull final List<MediaLocationInfo> mediaLocationInfos,
            @NonNull final LocationLabelsCallback callback) {
        if (mGeocoder == null || mediaLocationInfos.isEmpty()) {
            callback.onLabelsResult(new HashMap<>());
            return;
        }

        if (mediaLocationInfos.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Requests batch exceeded size limit. Expected: " + MAX_BATCH_SIZE + " Actual: "
                            + mediaLocationInfos.size());
        }

        mExecutor.execute(() -> {
            Map<Long, LocationLabelInfo> resultsMap = processLocationLabels(mediaLocationInfos);
            callback.onLabelsResult(resultsMap);
        });
    }

    // Asynchronous processing for API 33+
    private Map<Long, LocationLabelInfo> processLocationLabels(
            @NonNull List<MediaLocationInfo> mediaLocationInfos) {
        long processLocationLabelsStartTime = SystemClock.elapsedRealtime();
        Map<Long, LocationLabelInfo> resultsMap = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(mediaLocationInfos.size());

        for (final MediaLocationInfo info : mediaLocationInfos) {
            if (!isValidLatLong(info.latitude, info.longitude)) {
                Log.w(TAG, "Invalid lat/long coordinates for rowId " + info.id);
                latch.countDown();
                continue;
            }

            try {
                mGeocoder.getFromLocation(info.latitude, info.longitude, MAX_RESULTS,
                        new Geocoder.GeocodeListener() {
                            @Override
                            public void onGeocode(@NonNull List<Address> addresses) {
                                // Label is an empty string if the list of addresses is empty
                                String label = toLocationLabel(addresses);
                                resultsMap.put(info.id,
                                        new LocationLabelInfo(info.genModified, label));
                                latch.countDown();
                            }

                            @Override
                            public void onError(@Nullable String errorMessage) {
                                Log.w(TAG, "Geocoder error for " + info.id + ": " + errorMessage);
                                // Put null strings as label on error
                                resultsMap.put(info.id,
                                        new LocationLabelInfo(info.genModified, /* label */ null));
                                latch.countDown();
                            }
                        });
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid lat/long coordinates in getFromLocation for rowId: " + info.id,
                        e);
                latch.countDown();
            }
        }

        try {
            // Wait for all async operations to complete, with a timeout
            if (!latch.await(GEOCODE_BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Reverse geocoding operation timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Reverse geocoding operation interrupted", e);
        }

        long processLocationLabelsExecutionTime =
                SystemClock.elapsedRealtime() - processLocationLabelsStartTime;
        Log.i(TAG, "Time taken to process geolocation labels for " + mediaLocationInfos.size()
                + " items: " + processLocationLabelsExecutionTime + " ms. Results generated : "
                + resultsMap.size());

        return resultsMap;
    }

    private boolean isValidLatLong(double latitude, double longitude) {
        return latitude >= -90.0 && latitude <= 90.0 && longitude >= -180.0 && longitude <= 180.0;
    }

    /**
     * Converts an Address object to a location label.
     *
     * @return a String with location address as space-separated labels.
     * For example, "mountain view santa clara county california united states us"
     */
    @Nullable
    private String toLocationLabel(@NonNull List<Address> addresses) {
        List<String> parts = new ArrayList<>();
        for (Address address : addresses) {
            if (parts.size() >= MAX_TOKENS) {
                break;
            }

            if (!TextUtils.isEmpty(address.getLocality())) {
                parts.add(address.getLocality());
            }
            if (!TextUtils.isEmpty(address.getSubAdminArea())) {
                parts.add(address.getSubAdminArea());
            }
            if (!TextUtils.isEmpty(address.getAdminArea())) {
                parts.add(address.getAdminArea());
            }
            if (!TextUtils.isEmpty(address.getCountryName())) {
                parts.add(address.getCountryName());
            }
            if (!TextUtils.isEmpty(address.getCountryCode())) {
                parts.add(address.getCountryCode());
            }
        }
        return TextUtils.join(" ", parts).toLowerCase(Locale.ROOT);
    }

    /**
     * Callback interface to receive the results of the location label generation.
     */
    public interface LocationLabelsCallback {

        /**
         * Called when the location label generation is complete for a batch.
         * The map contains entries only for items where geocoding was successful.
         *
         * @param labelsMap A map where keys are the media item IDs (from
         *                  {@link MediaLocationInfo#id})
         *                  and values are the generated location labels.
         */
        void onLabelsResult(@NonNull Map<Long, LocationLabelInfo> labelsMap);
    }

    /**
     * Data class to hold the ID and location coordinates (latitude and longitude) of a media item.
     */
    public static class MediaLocationInfo {
        public final long id;
        public final long genModified;
        public final double latitude;
        public final double longitude;

        /**
         * Constructs a MediaLocationInfo.
         *
         * @param id The unique identifier for the media item (e.g., row ID from MediaStore).
         * @param latitude The latitude of the media item.
         * @param longitude The longitude of the media item.
         */
        public MediaLocationInfo(long id, long genModified, double latitude, double longitude) {
            this.id = id;
            this.genModified = genModified;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public static class LocationLabelInfo {
        public final long genModified;
        public final Optional<String> label;

        public LocationLabelInfo(long genMod, String label) {
            this.genModified = genMod;
            this.label = Optional.ofNullable(label);
        }
    }
}
