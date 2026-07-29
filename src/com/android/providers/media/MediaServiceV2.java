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

package com.android.providers.media;

import static com.android.providers.media.MediaService.ACTION_SCAN_VOLUME;
import static com.android.providers.media.MediaService.EXTRA_MEDIAVOLUME;
import static com.android.providers.media.MediaService.EXTRA_SCAN_REASON;
import static com.android.providers.media.scan.MediaScanner.REASON_MOUNTED;
import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.os.Trace;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.modules.utils.build.SdkLevel;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class MediaServiceV2 extends Worker {
    private static final String KEY_INTENT_ACTION = "intent_action";
    private static final String KEY_SCAN_REASON = "scan_reason";
    private static final String KEY_PACKAGE_NAME = "package_name";
    private static final String KEY_UID = "uid";
    private static final String KEY_PATH = "path";
    private static final String KEY_VOLUME_ID = "volume_id";
    private static final String SCAN_VOLUME_WORK_CHAIN = "scan_volume_work_chain";
    private static final String MEDIA_BROADCAST_WORK_CHAIN = "media_broadcast_work_chain";
    private static final long INITIAL_DELAY_MS = 30_000;
    private static final String TAG = MediaServiceV2.class.getSimpleName();
    private final Context mContext;

    public MediaServiceV2(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        mContext = context;
    }

    /**
     * Queues a volume scan operation.
     *
     * @param context The application context.
     * @param volume The {@link MediaVolume} to scan.
     * @param reason The reason for the scan.
     *
     * @return {@link UUID} UUID of work request for scan volume
     */
    public static Optional<UUID> queueVolumeScan(Context context, MediaVolume volume, int reason) {
        Intent intent = new Intent(ACTION_SCAN_VOLUME);
        intent.putExtra(EXTRA_MEDIAVOLUME, volume);
        intent.putExtra(EXTRA_SCAN_REASON, reason);
        return enqueueWork(context, intent);
    }

    /**
     * Enqueues work for given given intent. Ensure that SdkLevel >= S before calling this function.
     */
    public static Optional<UUID> enqueueWork(Context context, Intent intent) {
        if (!SdkLevel.isAtLeastS()) {
            Log.e(TAG, "Work not enqueued because enable_media_service_v2 flag was disabled "
                    + "or SdkLevel was less than S.");
            return Optional.empty();
        }

        Log.i(TAG, "Creating work for intent " + intent.toString());
        String action = intent.getAction();

        if (action == null) {
            Log.e(TAG, "Intent does not have action. No work created.");
            return Optional.empty();
        }

        WorkManager workManager = WorkManagerInitializer.getWorkManager(context);

        // All scan volume work and media mounted (which internally calls scan volume) work are
        // enqueued on one thread and all other broadcasts are enqueued on other thread.
        final String uniqueChainName;
        if (ACTION_SCAN_VOLUME.equalsIgnoreCase(action)
                || Intent.ACTION_MEDIA_MOUNTED.equalsIgnoreCase(action)) {
            uniqueChainName = SCAN_VOLUME_WORK_CHAIN;
        }  else {
            uniqueChainName = MEDIA_BROADCAST_WORK_CHAIN;
        }

        Optional<OneTimeWorkRequest> workRequestOptional = getWorkRequest(intent);
        if (workRequestOptional.isEmpty()) {
            return Optional.empty();
        }

        OneTimeWorkRequest workRequest = workRequestOptional.get();
        workManager.enqueueUniqueWork(uniqueChainName,
                ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest);
        Log.i(TAG, "Work enqueued for intent: " + intent);

        return Optional.of(workRequest.getId());
    }

    private static Optional<OneTimeWorkRequest> getWorkRequest(Intent intent) {
        OneTimeWorkRequest.Builder workRequestBuilder =
                new OneTimeWorkRequest.Builder(MediaServiceV2.class);
        Data.Builder dataBuilder = new Data.Builder();
        String action = intent.getAction();

        dataBuilder.put(KEY_INTENT_ACTION, action);

        switch (action) {
            case Intent.ACTION_PACKAGE_FULLY_REMOVED:
            case Intent.ACTION_PACKAGE_DATA_CLEARED: {
                dataBuilder.putString(KEY_PACKAGE_NAME, intent.getData().getSchemeSpecificPart());
                dataBuilder.putInt(KEY_UID, intent.getIntExtra(Intent.EXTRA_UID, 0));
                break;
            }
            case Intent.ACTION_MEDIA_SCANNER_SCAN_FILE: {
                dataBuilder.putString(KEY_PATH, intent.getData().getPath());
                break;
            }
            case Intent.ACTION_MEDIA_MOUNTED: {
                final StorageVolume storageVolume =
                        intent.getParcelableExtra(StorageVolume.EXTRA_STORAGE_VOLUME);
                dataBuilder.putString(KEY_VOLUME_ID, storageVolume.getId());
                delayWorkIfRequired(workRequestBuilder);
                break;
            }
            case ACTION_SCAN_VOLUME: {
                MediaVolume mediaVolume = intent.getParcelableExtra(EXTRA_MEDIAVOLUME);
                dataBuilder.put(KEY_VOLUME_ID, mediaVolume.getId());
                int scanReason = intent.getIntExtra(EXTRA_SCAN_REASON, REASON_UNKNOWN);
                dataBuilder.putInt(KEY_SCAN_REASON, scanReason);
                delayWorkIfRequired(workRequestBuilder);
                break;
            }
            case Intent.ACTION_LOCALE_CHANGED: {
                // This is a no-op
                break;
            }
            default : {
                Log.e(TAG, "Unknown intent action " + action);
                return Optional.empty();
            }
        }

        Data inputData = dataBuilder.build();
        workRequestBuilder.setInputData(inputData);
        return Optional.of(workRequestBuilder.build());
    }

    private static void delayWorkIfRequired(OneTimeWorkRequest.Builder workRequestBuilder) {
        if (!MediaReceiver.isBootCompleted() && SystemClock.elapsedRealtime() < INITIAL_DELAY_MS) {
            workRequestBuilder.setInitialDelay(INITIAL_DELAY_MS, TimeUnit.MILLISECONDS);
        } else {
            workRequestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Data data = getInputData();
        String action = data.getString(KEY_INTENT_ACTION);

        Log.i(TAG, "Work initiated for action [ " + action + " ]");

        try {
            Trace.beginSection("MediaServiceV2.handle [ " + action +  " ]");
            checkIsWorkerStopped();
            Data inputData = getInputData();
            switch (action) {
                case Intent.ACTION_LOCALE_CHANGED: {
                    MediaService.onLocaleChanged(mContext);
                    break;
                }
                case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                case Intent.ACTION_PACKAGE_DATA_CLEARED: {
                    final String packageName = inputData.getString(KEY_PACKAGE_NAME);
                    final int uid = inputData.getInt(KEY_UID, 0);
                    MediaService.onPackageOrphaned(mContext, packageName, uid);
                    break;
                }
                case Intent.ACTION_MEDIA_SCANNER_SCAN_FILE: {
                    final String path = inputData.getString(KEY_PATH);
                    MediaService.onScanFile(mContext, path);
                    break;
                }
                case Intent.ACTION_MEDIA_MOUNTED: {
                    String id = data.getString(KEY_VOLUME_ID);
                    StorageVolume storageVolume = getStorageVolumeById(mContext, id);
                    if (storageVolume == null) {
                        Log.e(TAG, "No storage volume found for id " + id);
                        return Result.failure();
                    }
                    MediaService.onMediaMountedBroadcast(mContext, storageVolume);
                    break;
                }
                case ACTION_SCAN_VOLUME : {
                    String id = data.getString(KEY_VOLUME_ID);
                    StorageVolume storageVolume = getStorageVolumeById(mContext, id);
                    if (storageVolume == null) {
                        Log.e(TAG, "No storage volume found for id " + id);
                        return Result.failure();
                    }
                    MediaVolume volume = MediaVolume.fromStorageVolume(storageVolume);
                    int scanReason = data.getInt(KEY_SCAN_REASON, REASON_UNKNOWN);
                    Log.i(TAG, "Starting scan volume for " + volume.getName());
                    if (volume.isPublicVolume()) {
                        MediaService.recoverPublicVolumeIfNeeded(volume,
                                mContext.getContentResolver());
                    }
                    MediaService.onScanVolume(mContext, volume, scanReason);
                    break;
                }
                default: {
                    Log.i(TAG, "Unknown intent action [ " + action + " ]");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Work failed for action [" + action + " ]", e);
            return Result.failure();
        } finally {
            Log.i(TAG, "Work ended for action [ " + action + " ]");
            Trace.endSection();
        }

        return Result.success();
    }

    @Override
    public void onStopped() {
        Data data = getInputData();
        String action = data.getString(KEY_INTENT_ACTION);

        Log.i(TAG, "[onStopped] Work stopped for action: " + action);

        try {
            Trace.beginSection("MediaServiceV2.onStopped [ " + action +  " ]");
            switch (action) {
                case ACTION_SCAN_VOLUME : {
                    int scanReason = data.getInt(KEY_SCAN_REASON, REASON_UNKNOWN);
                    String id = data.getString(KEY_VOLUME_ID);
                    StorageVolume storageVolume = getStorageVolumeById(mContext, id);
                    if (storageVolume == null) {
                        Log.e(TAG, "No storage volume found for id " + id);
                        return;
                    }
                    MediaVolume mediaVolume = MediaVolume.fromStorageVolume(storageVolume);
                    stopScanVolume(mediaVolume, scanReason);
                    break;
                }
                case Intent.ACTION_MEDIA_MOUNTED: {
                    String id = data.getString(KEY_VOLUME_ID);
                    StorageVolume storageVolume = getStorageVolumeById(mContext, id);
                    if (storageVolume == null) {
                        Log.e(TAG, "No storage volume found for id " + id);
                        return;
                    }
                    final MediaVolume mediaVolume = MediaVolume.fromStorageVolume(storageVolume);
                    stopScanVolume(mediaVolume, REASON_MOUNTED);
                    break;
                }
                case Intent.ACTION_LOCALE_CHANGED:
                case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                case Intent.ACTION_PACKAGE_DATA_CLEARED:
                case Intent.ACTION_MEDIA_SCANNER_SCAN_FILE: {
                    // This is a no-op
                    break;
                }
                default: {
                    Log.e(TAG, "[onStopped] Unknown intent action received: " + action);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "[onStopped] Exception while handling stopped work for action: "
                    + action, e);
        } finally {
            Log.i(TAG, "[onStopped] Finished handling stop for action: " + action);
            Trace.endSection();
        }
    }

    private void stopScanVolume(MediaVolume volume, int scanReason) {
        try (ContentProviderClient cpc = mContext.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            ((MediaProvider) cpc.getLocalContentProvider())
                    .onScanVolumeWorkStopped(volume, scanReason);
        }
    }

    /**
     * If the system crashes and calls onStopped(), the work is rescheduled afterwards. So if the
     * work is running, we stop it.
     */
    private void checkIsWorkerStopped() throws Exception {
        if (isStopped()) {
            throw new Exception("Work is stopped. Id: " + getId());
        }
    }

    /**
     * Finds a StorageVolume by its ID string.
     *
     * @param context The application context.
     * @param id The ID string of the volume to find.
     * @return The matching {@link StorageVolume}, or {@code null} if not found.
     */
    private static StorageVolume getStorageVolumeById(Context context, @NonNull String id) {
        final StorageManager sm = context.getSystemService(StorageManager.class);
        for (StorageVolume vol : sm.getStorageVolumes()) {
            if (id.equalsIgnoreCase(vol.getId())) {
                return vol;
            }
        }
        return null;
    }

    /**
     * Performs cleanup of finished work requests from WorkManager.
     *
     * <p>
     * This method removes all work that has reached a terminal state (SUCCEEDED, FAILED, or
     * CANCELLED) from the WorkManager's internal database.
     * </p>
     *
     * @param context The application context.
     */
    public static void performCleanUp(Context context) {
        WorkManager workManager = WorkManagerInitializer.getWorkManager(context);
        workManager.pruneWork();
        Log.v(TAG, "WorkManager database pruned. Old and finished work removed.");
    }
}
