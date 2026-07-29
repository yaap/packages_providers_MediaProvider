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

package com.android.providers.media.photopicker.v2.model;

import android.os.Bundle;
import android.util.Log;

/**
 * This is a convenience class for Media page key list related SQL queries performed on the Picker
 * Database.
 */
public class MediaPageKeyListQuery extends MediaQuery{
    private int mItemIndexInterval;
    private static final int MINIMUM_ITEM_INDEX_INTERVAL = 1;
    private static final String TAG = "MediaPageKeyListQuery";

    public MediaPageKeyListQuery(Bundle queryArgs) {
        super(queryArgs);
        mItemIndexInterval = queryArgs.getInt("item_index_interval", MINIMUM_ITEM_INDEX_INTERVAL);

        // Validate and log error if interval is below minimum
        if (mItemIndexInterval < 1) {
            Log.e(TAG, "Invalid item index interval: " + mItemIndexInterval
                    + ". Resetting to minimum allowed value: " + MINIMUM_ITEM_INDEX_INTERVAL);
            mItemIndexInterval = MINIMUM_ITEM_INDEX_INTERVAL;
        }
    }
    public int getItemIndexInterval() {
        return mItemIndexInterval;
    }
}
