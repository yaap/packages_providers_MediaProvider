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
import android.provider.mediaprocessingservice.ErrorMessage.ErrorCode;
import android.provider.mediaprocessingservice.MediaProcessingRequest;
import android.provider.mediaprocessingservice.MediaProcessingResponse;
import android.provider.mediaprocessingservice.MediaProcessingService;
import android.provider.mediaprocessingservice.QueryProcessingResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class TestMediaProcessingService extends MediaProcessingService {

    private static Map<Integer, Integer> sExpectedProcessingMap = Collections.emptyMap();
    private static List<MediaProcessingResponse> sSimulateSuccessResponses;
    private static ErrorMessage sSimulateFailureError;
    private static int sProcessingLimit = -1; // -1 means use default
    private static String sExpectedSearchQuery;
    private static QueryProcessingResponse sSimulateSearchQuerySuccessResponse;
    private static ErrorMessage sSimulateSearchQueryFailureError;

    /**
     * Sets the expected processing map for the service.
     */
    public static void setExpectedProcessingMap(Map<Integer, Integer> map) {
        sExpectedProcessingMap = map;
    }

    /**
     * Sets the expected responses for the service.
     */
    public static void setSimulateProcessMediaSuccess(List<MediaProcessingResponse> responses) {
        sSimulateSuccessResponses = responses;
        sSimulateFailureError = null;
    }

    /**
     * Sets the expected error for the service.
     */
    public static void setSimulateProcessMediaFailure(ErrorMessage error) {
        sSimulateFailureError = error;
        sSimulateSuccessResponses = null;
    }

    /**
     * Sets the expected responses for the service.
     */
    public static void setSimulateSearchQuerySuccess(String query,
            QueryProcessingResponse response) {
        sExpectedSearchQuery = query;
        sSimulateSearchQuerySuccessResponse = response;
        sSimulateSearchQueryFailureError = null;
    }

    /**
     * Sets the expected error for the service.
     */
    public static void setSimulateSearchQueryFailure(String query, ErrorMessage error) {
        sExpectedSearchQuery = query;
        sSimulateSearchQueryFailureError = error;
        sSimulateSearchQuerySuccessResponse = null;
    }

    /**
     * Sets the processing limit for the service.
     */
    public static void setProcessingLimit(int limit) {
        sProcessingLimit = limit;
    }

    /**
     * Resets the service to its default state.
     */
    public static void reset() {
        sExpectedProcessingMap = Collections.emptyMap();
        sSimulateSuccessResponses = null;
        sSimulateFailureError = null;
        sProcessingLimit = -1;
        sExpectedSearchQuery = null;
        sSimulateSearchQuerySuccessResponse = null;
        sSimulateSearchQueryFailureError = null;
    }

    @NonNull
    @Override
    public Map<Integer, Integer> onProcessingRequestedPerMediaType() {
        return sExpectedProcessingMap;
    }

    @Override
    public int onGetProcessingLimit() {
        return (sProcessingLimit != -1) ? sProcessingLimit : super.onGetProcessingLimit();
    }

    @Override
    public void onProcessMedia(@NonNull List<MediaProcessingRequest> mediaProcessingRequests,
            @NonNull OutcomeReceiver<List<MediaProcessingResponse>, ErrorMessage> outcomeReceiver) {
        if (sSimulateFailureError != null) {
            outcomeReceiver.onError(sSimulateFailureError);
        } else if (sSimulateSuccessResponses != null) {
            outcomeReceiver.onResult(sSimulateSuccessResponses);
        } else {
            outcomeReceiver.onResult(Collections.emptyList());
        }
    }

    @Override
    public void onGetEmbeddingVectorForSearchText(@NonNull String searchQuery,
            @NonNull OutcomeReceiver<QueryProcessingResponse, ErrorMessage> outcomeReceiver) {
        if (sExpectedSearchQuery == null || !sExpectedSearchQuery.equals(searchQuery)) {
            outcomeReceiver.onError(
                    new ErrorMessage(ErrorCode.ERROR_UNKNOWN, "Unexpected query", false));
            return;
        }

        if (sSimulateSearchQueryFailureError != null) {
            outcomeReceiver.onError(sSimulateSearchQueryFailureError);
        } else if (sSimulateSearchQuerySuccessResponse != null) {
            outcomeReceiver.onResult(sSimulateSearchQuerySuccessResponse);
        } else {
            outcomeReceiver.onError(new ErrorMessage(ErrorCode.ERROR_UNKNOWN, "Not mocked", false));
        }
    }
}
