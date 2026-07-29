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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.Manifest;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.MediaStore;
import android.provider.mediaprocessingservice.EmbeddingVector;
import android.provider.mediaprocessingservice.ErrorMessage;
import android.provider.mediaprocessingservice.IMediaProcessingCallback;
import android.provider.mediaprocessingservice.IMediaProcessingService;
import android.provider.mediaprocessingservice.IQueryProcessingCallback;
import android.provider.mediaprocessingservice.MediaProcessingRequest;
import android.provider.mediaprocessingservice.MediaProcessingResponse;
import android.provider.mediaprocessingservice.MediaProcessingService;
import android.provider.mediaprocessingservice.QueryProcessingResponse;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ENABLE_MEDIA_PROCESSING_SERVICE)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
public class MediaProcessingServiceTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private static final String TAG = "ProcessingServiceTest";
    private static final long TIMEOUT_MS = 5000;
    private static final long POLLING_SLEEP_MILLIS = 100;
    private CountDownLatch mServiceLatch;
    private ServiceConnection mServiceConnection;
    private IMediaProcessingService mTestMediaProcessingService;
    private Context mContext;
    private final IMediaProcessingCallback mMockProcessMediaCallback = mock(
            IMediaProcessingCallback.class);
    private final IQueryProcessingCallback mMockQueryProcessingCallback = mock(
            IQueryProcessingCallback.class);

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mServiceLatch = new CountDownLatch(1);
    }

    @After
    public void tearDown() throws Exception {
        TestMediaProcessingService.reset();
        if (mServiceConnection != null) {
            mContext.unbindService(mServiceConnection);
            mServiceConnection = null;
        }
    }

    private void bindService() throws Exception {
        Intent intent = new Intent(MediaProcessingService.SERVICE_INTERFACE);
        intent.setClassName("com.android.providers.media.tests",
                "com.android.providers.media.mediaprocessingservice.TestMediaProcessingService");

        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder iBinder) {
                Log.i(TAG, "onServiceConnected : " + name);
                mTestMediaProcessingService = IMediaProcessingService.Stub.asInterface(iBinder);
                assertThat(mTestMediaProcessingService).isNotNull();
                mServiceLatch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "onServiceDisconnected : " + name);
                mTestMediaProcessingService = null;
            }
        };

        assertTrue(mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE));
        assertTrue(mServiceLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testGetProcessingRequestedPerMediaType() throws Exception {
        bindService();

        Map<Integer, Integer> expectedMap = new HashMap<>();
        expectedMap.put(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                MediaProcessingService.ProcessingType.DEFAULT_MEDIA_LABELS_PROCESSING
                        | MediaProcessingService.ProcessingType.CUSTOM_OEM_PROCESSING);
        TestMediaProcessingService.setExpectedProcessingMap(expectedMap);

        Map<Integer, Integer> actualMap =
                mTestMediaProcessingService.getProcessingRequestedPerMediaType();
        assertThat(actualMap).isEqualTo(expectedMap);
    }

    @Test
    public void testGetProcessingLimit() throws Exception {
        bindService();
        assertThat(mTestMediaProcessingService.getProcessingLimit()).isEqualTo(10);
        TestMediaProcessingService.setProcessingLimit(50);
        assertThat(mTestMediaProcessingService.getProcessingLimit()).isEqualTo(50);
    }


    @Test
    public void testProcessMedia_onSuccess() throws Exception {
        bindService();

        Uri testUri = Uri.parse("content://media/external/images/media/1");
        MediaProcessingRequest request = new MediaProcessingRequest(testUri,
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, System.currentTimeMillis());
        List<MediaProcessingRequest> requests = List.of(request);

        float[] values = new float[]{0.4f, 0.5f};
        EmbeddingVector embeddingVector = new EmbeddingVector(values, "model1");
        MediaProcessingResponse response = MediaProcessingResponse.builder(testUri, 123L)
                .setExtractedLabels("label1,label2")
                .setEmbeddingVectorList(List.of(embeddingVector)).build();
        List<MediaProcessingResponse> expectedResponses = List.of(response);

        TestMediaProcessingService.setSimulateProcessMediaSuccess(expectedResponses);

        mTestMediaProcessingService.processMedia(requests, mMockProcessMediaCallback);

        ArgumentCaptor<List<MediaProcessingResponse>> captor = ArgumentCaptor.forClass(List.class);
        verify(mMockProcessMediaCallback, timeout(TIMEOUT_MS)).onResult(captor.capture());

        List<MediaProcessingResponse> actualResponses = captor.getValue();
        assertThat(actualResponses).hasSize(1);
        MediaProcessingResponse actualResponse = actualResponses.get(0);
        assertThat(actualResponse.getUri()).isEqualTo(testUri);
        assertThat(actualResponse.getExtractedLabels()).isEqualTo("label1,label2");
        assertThat(actualResponse.getEmbeddingVectorList()).hasSize(1);
    }

    @Test
    public void testProcessMedia_onFailure() throws Exception {
        bindService();

        Uri testUri = Uri.parse("content://media/external/video/media/2");
        MediaProcessingRequest request = new MediaProcessingRequest(testUri,
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, System.currentTimeMillis());
        List<MediaProcessingRequest> requests = List.of(request);

        ErrorMessage expectedError = new ErrorMessage(ErrorMessage.ErrorCode.ERROR_UNKNOWN,
                "Test processing failed", false);
        TestMediaProcessingService.setSimulateProcessMediaFailure(expectedError);

        mTestMediaProcessingService.processMedia(requests, mMockProcessMediaCallback);

        ArgumentCaptor<ErrorMessage> captor = ArgumentCaptor.forClass(ErrorMessage.class);
        verify(mMockProcessMediaCallback, timeout(TIMEOUT_MS)).onError(captor.capture());

        ErrorMessage actualError = captor.getValue();
        assertThat(actualError.getErrorCode()).isEqualTo(expectedError.getErrorCode());
        assertThat(actualError.getMessage()).isEqualTo(expectedError.getMessage());
        assertThat(actualError.isRetryable()).isEqualTo(expectedError.isRetryable());
    }

    @Test
    public void testProcessMedia_emptyRequestList() throws Exception {
        bindService();
        List<MediaProcessingRequest> requests = new ArrayList<>();
        TestMediaProcessingService.setSimulateProcessMediaSuccess(new ArrayList<>());

        mTestMediaProcessingService.processMedia(requests, mMockProcessMediaCallback);

        ArgumentCaptor<List<MediaProcessingResponse>> captor = ArgumentCaptor.forClass(List.class);
        verify(mMockProcessMediaCallback, timeout(TIMEOUT_MS)).onResult(captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }


    @Test
    public void testGetEmbeddingVectorForSearchText_onSuccess() throws Exception {
        bindService();
        String query = "test query";
        float[] values = new float[]{0.8f, 0.9f};
        EmbeddingVector expectedVector = new EmbeddingVector(values, "query_model");
        QueryProcessingResponse expectedResponse = new QueryProcessingResponse(query,
                expectedVector);
        TestMediaProcessingService.setSimulateSearchQuerySuccess(query, expectedResponse);

        mTestMediaProcessingService.getEmbeddingVectorForSearchText(query,
                mMockQueryProcessingCallback);

        ArgumentCaptor<QueryProcessingResponse> captor = ArgumentCaptor.forClass(
                QueryProcessingResponse.class);
        verify(mMockQueryProcessingCallback, timeout(TIMEOUT_MS)).onResult(captor.capture());

        QueryProcessingResponse actualResponse = captor.getValue();
        assertThat(actualResponse.getSearchQuery()).isEqualTo(query);
        EmbeddingVector actualVector = actualResponse.getEmbeddingVector();
        assertThat(actualVector).isNotNull();
        assertThat(actualVector.getModelSignature()).isEqualTo(expectedVector.getModelSignature());
        assertThat(actualVector.getValues()).isEqualTo(expectedVector.getValues());
    }

    @Test
    public void testGetEmbeddingVectorForSearchText_onFailure() throws Exception {
        bindService();
        String query = "fail query";
        ErrorMessage expectedError = new ErrorMessage(ErrorMessage.ErrorCode.ERROR_UNKNOWN,
                "Model unavailable", true);
        TestMediaProcessingService.setSimulateSearchQueryFailure(query, expectedError);

        mTestMediaProcessingService.getEmbeddingVectorForSearchText(query,
                mMockQueryProcessingCallback);

        ArgumentCaptor<ErrorMessage> errorCaptor = ArgumentCaptor.forClass(ErrorMessage.class);
        verify(mMockQueryProcessingCallback, timeout(TIMEOUT_MS)).onError(errorCaptor.capture());

        ErrorMessage actualError = errorCaptor.getValue();
        assertThat(actualError.getErrorCode()).isEqualTo(expectedError.getErrorCode());
        assertThat(actualError.getMessage()).isEqualTo(expectedError.getMessage());
        assertThat(actualError.isRetryable()).isEqualTo(expectedError.isRetryable());
    }

    private void updateStateOfServiceWithPermission(int state) throws Exception {
        PackageManager packageManager = mContext.getPackageManager();
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        inst.getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE,
                Manifest.permission.LOG_COMPAT_CHANGE,
                Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                Manifest.permission.INTERACT_ACROSS_USERS);
        ComponentName componentName = new ComponentName(mContext,
                "com.android.providers.media.mediaprocessingservice.TestMediaProcessingService");
        packageManager.setComponentEnabledSetting(componentName, state,
                PackageManager.DONT_KILL_APP);

        waitForComponentToBeInExpectedState(packageManager, componentName, state);
    }

    private static void waitForComponentToBeInExpectedState(PackageManager packageManager,
            ComponentName componentName, int state) throws Exception {
        pollForCondition(() -> (packageManager.getComponentEnabledSetting(componentName) == state),
                /* errorMessage= */ "Timed out while waiting for component to be disabled");
    }

    private static void pollForCondition(Supplier<Boolean> condition, String errorMessage)
            throws Exception {
        for (int i = 0; i < TIMEOUT_MS / POLLING_SLEEP_MILLIS; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(POLLING_SLEEP_MILLIS);
        }
        throw new TimeoutException(errorMessage);
    }

}
