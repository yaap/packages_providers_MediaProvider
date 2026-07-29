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

package android.provider.mediaprocessingservice;

import android.provider.mediaprocessingservice.MediaProcessingResponse;
import android.provider.mediaprocessingservice.ErrorMessage;

/**
 * Callback for the MediaProcessingService.processMedia method
 * @hide
 */
oneway interface IMediaProcessingCallback {
    /**
     * Called when media processing is successful.
     *
     * @param responses A list of {@link MediaProcessingResponse} objects.
     */
    void onResult(in List<MediaProcessingResponse> responses);

    /**
     * Called when media processing fails.
     *
     * @param error An {@link ErrorMessage} object describing the failure.
     */
    void onError(in ErrorMessage error);
}
