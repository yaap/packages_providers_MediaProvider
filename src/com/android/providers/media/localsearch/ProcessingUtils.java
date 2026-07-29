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

package com.android.providers.media.localsearch;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import com.android.providers.media.R;
import com.android.providers.media.appsearch.AppSearchDbManager;
import com.android.providers.media.flags.Flags;

import java.util.Optional;

public class ProcessingUtils {
    private static final String TAG = ProcessingUtils.class.getSimpleName();
    private static Optional<Boolean> sIsMediaProcessingRequired = Optional.empty();

    private static Optional<Boolean> sDefaultServiceSupported = Optional.empty();

    /**
     * Checks if the default search media service is supported and caches the result.
     */
    public static boolean isDefaultSearchMediaServiceSupported(Context context) {
        if (sDefaultServiceSupported.isEmpty()) {
            sDefaultServiceSupported = Optional.of(checkServiceSupported(context));
        }
        return sDefaultServiceSupported.get();
    }

    /**
     * Returns whether media processing tasks should be scheduled on this device
     */
    public static boolean isMediaProcessingRequired(Context context) {
        if (sIsMediaProcessingRequired.isEmpty()) {
            String searchMediaServicePackage = MediaStore.getPackageForSearchMediaService(
                    context.getContentResolver());
            if (context.getPackageName().equalsIgnoreCase(searchMediaServicePackage)) {
                sIsMediaProcessingRequired = Optional.of(true);
            } else {
                Log.i(TAG, "OEM defined SearchMediaService is used or SearchMediaService is "
                        + "not enabled. Skip media processing.");
                sIsMediaProcessingRequired = Optional.of(false);
            }
        }

        return sIsMediaProcessingRequired.get();
    }

    private static boolean checkServiceSupported(Context context) {
        if (!Flags.enableMediaSearch()) {
            Log.e(TAG, "enable_media_search flag is disabled.");
            return false;
        }

        if (!isMediaProcessingEnabled(context)) {
            Log.e(TAG, "Media processing is disabled.");
            return false;
        }

        if (!isAppSearchDbSupported(context)) {
            Log.e(TAG, "AppSearch is unavailable or incompatible.");
            return false;
        }

        return true;
    }

    private static boolean isMediaProcessingEnabled(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
            Log.v(TAG, "Media processing is not supported on this device. "
                    + "Supported only on C+ devices.");
            return false;
        }

        if (!Flags.enableMediaProcessing()) {
            Log.v(TAG, "Media processing feature flag is not enabled.");
            return false;
        }

        boolean disableMediaProcessing = context.getResources().getBoolean(
                R.bool.config_disable_media_processing_for_search);
        if (disableMediaProcessing) {
            Log.v(TAG, "Media processing disabled via overlayable configuration");
            return false;
        }

        // Support media processing only on phones, tablets and PC device types which can possibly
        // support UI for search services.
        PackageManager pm = context.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_WATCH)
                || pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature(PackageManager.FEATURE_EMBEDDED)
                || pm.hasSystemFeature(PackageManager.FEATURE_XR_PERIPHERAL)) {
            Log.v(TAG, "Media processing is not supported on this device type.");
            return false;
        }

        if (context.getSystemService(ActivityManager.class).isLowRamDevice()) {
            Log.v(TAG, "Media processing is not supported on low RAM devices");
            return false;
        }

        return true;
    }

    private static boolean isAppSearchDbSupported(Context context) {
        AppSearchDbManager appSearchDbManager = null;
        try {
            appSearchDbManager = new AppSearchDbManager(context);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (appSearchDbManager != null) {
                appSearchDbManager.disconnect();
            }
        }
    }
}
