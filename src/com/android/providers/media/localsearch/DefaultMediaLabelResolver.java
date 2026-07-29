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

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_NONE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
import static android.provider.mediaprocessingservice.MediaProcessingService.ProcessingType.DEFAULT_LOCATION_PROCESSING;
import static android.provider.mediaprocessingservice.MediaProcessingService.ProcessingType.DEFAULT_MEDIA_LABELS_PROCESSING;
import static android.provider.mediaprocessingservice.MediaProcessingService.ProcessingType.DEFAULT_METADATA_PROCESSING;

import static com.android.providers.media.localsearch.ProcessingHelper.DEFAULT_MEDIA_LABEL_PROCESSING_LIMIT;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import com.android.providers.media.R;
import com.android.providers.media.flags.Flags;

import java.util.HashMap;
import java.util.Map;

public class DefaultMediaLabelResolver {
    private static final String TAG = "MediaLabelResolver";

    DefaultMediaLabelResolver() {
        //TODO(b/428140364) : Add integration for processing media label and embeddings
    }

    /**
     * Returns default processing types requested per media type
     */
    public static Map<Integer, Integer> getProcessingRequestedPerMediaType() {
        Map<Integer, Integer> processingRequested = new HashMap<>();

        if (Flags.enableMediaProcessingService()) {
            processingRequested.put(MEDIA_TYPE_IMAGE,
                    DEFAULT_MEDIA_LABELS_PROCESSING | DEFAULT_LOCATION_PROCESSING
                            | DEFAULT_METADATA_PROCESSING);
            processingRequested.put(MEDIA_TYPE_VIDEO,
                    DEFAULT_LOCATION_PROCESSING | DEFAULT_METADATA_PROCESSING);
            processingRequested.put(MEDIA_TYPE_AUDIO, DEFAULT_METADATA_PROCESSING);
            processingRequested.put(MEDIA_TYPE_DOCUMENT, DEFAULT_METADATA_PROCESSING);
            processingRequested.put(MEDIA_TYPE_NONE, DEFAULT_METADATA_PROCESSING);
        }

        return processingRequested;
    }

    /**
     * Returns processing limit for default media label processing
     */
    public static int getProcessingLimit(Context context) {
        try {
            return context.getResources().getInteger(
                    R.integer.config_default_media_processing_batch_size);
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Default config for media label processing batch size not found. Using"
                    + " default value " + DEFAULT_MEDIA_LABEL_PROCESSING_LIMIT, e);
            return DEFAULT_MEDIA_LABEL_PROCESSING_LIMIT;
        }
    }
}
