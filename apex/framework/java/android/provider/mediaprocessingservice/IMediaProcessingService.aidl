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

import java.util.List;
import java.util.Map;
import android.provider.mediaprocessingservice.MediaProcessingRequest;
import android.provider.mediaprocessingservice.IMediaProcessingCallback;
import android.provider.mediaprocessingservice.IQueryProcessingCallback;
import android.provider.mediaprocessingservice.EmbeddingVector;
import android.provider.mediaprocessingservice.QueryProcessingResponse;

/**
 * @hide
 */
interface IMediaProcessingService {
    /**
     * Synchronously returns a map where keys are the  {@code MediaStore.MEDIA_TYPE} (eg.
     * {@code MediaStore.MEDIA_TYPE_IMAGE}, {@code MediaStore.MEDIA_TYPE_VIDEO}) and values are the
     * types of processing requested for that media type. MediaProvider caches this information for
     * the duration of the service connection.
     */
    Map getProcessingRequestedPerMediaType();

    /**
    * Synchronously returns the maximum number of MediaProcessingRequest items that the service implementation
    * prefers to handle in a single call to {@code processMedia}. MediaProvider will use this value
    * to batch requests. This will be a one time call cached by MediaProvider for the duration of a
    * service connection.
    *
    * The default implementation returns 10. Override this method if the service
    * should handle a different batch size.
    */
    int getProcessingLimit();

    /**
     * Asynchronously processes a list of media files.
     * <p>
     * OEMs can specify the batch size for media processing requests through the
     * {@code onGetProcessingLimit} method.
     * The default value is set at 10 requests
     *
     * @param mediaProcessingRequests a list of {@link MediaProcessingRequest} objects,
     *                                each specifying a media URI to be processed.
     * @param callback the {@link IMediaProcessingCallback} instance to receive the results or error.
     */
    oneway void processMedia(in List<MediaProcessingRequest> mediaProcessingRequests,
            in IMediaProcessingCallback callback);

    /**
     * Asynchronously generates an {@link EmbeddingVector} for the given search text.
     *
     * @param searchQuery the input text to convert into an embedding vector.
     * @param callback the {@link IQueryProcessingCallback} instance to receive the
     *                 {@link QueryProcessingResponse} or an {@link ErrorMessage}.
     */
    oneway void getEmbeddingVectorForSearchText(in String searchQuery,
            in IQueryProcessingCallback callback);
}
