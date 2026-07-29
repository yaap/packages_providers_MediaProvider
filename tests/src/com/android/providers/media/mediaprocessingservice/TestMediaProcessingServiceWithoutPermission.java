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

package com.android.providers.media.mediaprocessingservice;

import android.annotation.NonNull;
import android.os.OutcomeReceiver;
import android.provider.mediaprocessingservice.ErrorMessage;
import android.provider.mediaprocessingservice.MediaProcessingRequest;
import android.provider.mediaprocessingservice.MediaProcessingResponse;
import android.provider.mediaprocessingservice.MediaProcessingService;
import android.provider.mediaprocessingservice.QueryProcessingResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestMediaProcessingServiceWithoutPermission extends MediaProcessingService {
    @NonNull
    @Override
    public Map<Integer, Integer> onProcessingRequestedPerMediaType() {
        return new HashMap<>();
    }

    @Override
    public int onGetProcessingLimit() {
        return super.onGetProcessingLimit();
    }

    @Override
    public void onProcessMedia(@NonNull List<MediaProcessingRequest> mediaProcessingRequests,
            @NonNull OutcomeReceiver<List<MediaProcessingResponse>, ErrorMessage> outcomeReceiver) {
    }

    @Override
    public void onGetEmbeddingVectorForSearchText(@NonNull String searchQuery,
            @NonNull OutcomeReceiver<QueryProcessingResponse, ErrorMessage> outcomeReceiver) {
    }
}
