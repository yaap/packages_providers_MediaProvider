/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.media.util;

import static com.android.providers.media.MediaProviderStatsLog.MEDIA_CONTENT_DELETED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_IDLE_MAINTENANCE_FINISHED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__BULK_UPDATE_OEM_METADATA_CALL;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__GET_REDACTED_MEDIA_URI_LIST_CALL;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__MEDIA_PROVIDER_OP_UNSPECIFIED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__RESOLVE_PLAYLIST_MEMBERS_CALL;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__URI_TYPE__URI_MEDIA;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__URI_TYPE__URI_MEDIA_DOCUMENT;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__URI_TYPE__URI_MEDIA_PICKER;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__URI_TYPE__URI_MEDIA_REDACTED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__URI_TYPE__URI_UNSPECIFIED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__VOLUME__EXTERNAL_OTHER;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__VOLUME__EXTERNAL_PRIMARY;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__VOLUME__INTERNAL;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__VOLUME__UNKNOWN;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_PERMISSION_REQUESTED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_PERMISSION_REQUESTED__RESULT__USER_DENIED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_PERMISSION_REQUESTED__RESULT__USER_GRANTED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__EXTERNAL_OTHER;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__EXTERNAL_PRIMARY;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__INTERNAL;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__UNKNOWN;
import static com.android.providers.media.MediaProviderStatsLog.MEDIA_PROVIDER_SCHEMA_CHANGED;
import static com.android.providers.media.scan.MediaScanner.REASON_DEMAND;
import static com.android.providers.media.scan.MediaScanner.REASON_IDLE;
import static com.android.providers.media.scan.MediaScanner.REASON_MOUNTED;
import static com.android.providers.media.scan.MediaScanner.REASON_UNKNOWN;

import android.content.ContentProviderClient;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import com.android.providers.media.MediaProvider;
import com.android.providers.media.MediaProviderStatsLog;
import com.android.providers.media.metrics.DeviceStorageStateMetricsCollector;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Class that emits common metrics to both remote and local endpoints to aid in
 * regression investigations and bug triage.
 */
public class Metrics {
    private static final String TAG = "MediaProviderMetrics";
    private Metrics() {
        // Utility class, cannot be instantiated
    }

    public static void logScan(@NonNull String volumeName, int reason, long itemCount,
            long durationMillis, int insertCount, int updateCount, int deleteCount) {
        Logging.logPersistent(
                "Scanned %s due to %s, found %d items in %dms, %d inserts %d updates %d deletes",
                volumeName, translateReason(reason), itemCount, durationMillis, insertCount,
                updateCount, deleteCount);

        final float normalizedDurationMillis = ((float) durationMillis) / itemCount;
        final float normalizedInsertCount = ((float) insertCount) / itemCount;
        final float normalizedUpdateCount = ((float) updateCount) / itemCount;
        final float normalizedDeleteCount = ((float) deleteCount) / itemCount;

        MediaProviderStatsLog.write(MEDIA_PROVIDER_SCAN_OCCURRED,
                translateVolumeName(volumeName), reason, itemCount, normalizedDurationMillis,
                normalizedInsertCount, normalizedUpdateCount, normalizedDeleteCount);
    }

    /**
     * Logs persistent deletion logs on-device.
     */
    public static void logDeletionPersistent(@NonNull String volumeName, String reason,
            int[] countPerMediaType) {
        final StringBuilder builder = new StringBuilder("Deleted ");
        for (int count: countPerMediaType) {
            builder.append(count).append(' ');
        }
        builder.append("items on ")
                .append(volumeName)
                .append(" due to ")
                .append(reason);

        Logging.logPersistent(builder.toString());
    }

    /**
     * Logs persistent deletion logs on-device and stats metrics. Count of items per-media-type
     * are not uploaded to MediaProviderStats logs.
     */
    public static void logDeletion(@NonNull String volumeName, int uid, String packageName,
            int itemCount, int[] countPerMediaType) {
        logDeletionPersistent(volumeName, packageName, countPerMediaType);
        MediaProviderStatsLog.write(MEDIA_CONTENT_DELETED,
                translateVolumeName(volumeName), uid, itemCount);
    }

    public static void logPermissionGranted(@NonNull String volumeName, int uid, String packageName,
            int itemCount) {
        Logging.logPersistent(
                "Granted permission to %3$d items on %1$s to %2$s",
                volumeName, packageName, itemCount);

        MediaProviderStatsLog.write(MEDIA_PROVIDER_PERMISSION_REQUESTED,
                translateVolumeName(volumeName), uid, itemCount,
                MEDIA_PROVIDER_PERMISSION_REQUESTED__RESULT__USER_GRANTED);
    }

    public static void logPermissionDenied(@NonNull String volumeName, int uid, String packageName,
            int itemCount) {
        Logging.logPersistent(
                "Denied permission to %3$d items on %1$s to %2$s",
                volumeName, packageName, itemCount);

        MediaProviderStatsLog.write(MEDIA_PROVIDER_PERMISSION_REQUESTED,
                translateVolumeName(volumeName), uid, itemCount,
                MEDIA_PROVIDER_PERMISSION_REQUESTED__RESULT__USER_DENIED);
    }

    public static void logSchemaChange(@NonNull String volumeName, int versionFrom, int versionTo,
            long itemCount, long durationMillis, @NonNull String databaseUuid) {
        Logging.logPersistent(
                "Changed schema version on %s from %d to %d, %d items taking %dms UUID %s",
                volumeName, versionFrom, versionTo, itemCount, durationMillis, databaseUuid);

        final float normalizedDurationMillis = ((float) durationMillis) / itemCount;

        MediaProviderStatsLog.write(MEDIA_PROVIDER_SCHEMA_CHANGED,
                translateVolumeName(volumeName), versionFrom, versionTo, itemCount,
                normalizedDurationMillis);
    }

    public static void logIdleMaintenance(@NonNull String volumeName, long itemCount,
            long durationMillis, int staleThumbnails, int expiredMedia) {
        Logging.logPersistent(
                "Idle maintenance on %s, %d items taking %dms, %d stale, %d expired",
                volumeName, itemCount, durationMillis, staleThumbnails, expiredMedia);

        final float normalizedDurationMillis = ((float) durationMillis) / itemCount;
        final float normalizedStaleThumbnails = ((float) staleThumbnails) / itemCount;
        final float normalizedExpiredMedia = ((float) expiredMedia) / itemCount;

        MediaProviderStatsLog.write(MEDIA_PROVIDER_IDLE_MAINTENANCE_FINISHED,
                translateVolumeName(volumeName), itemCount, normalizedDurationMillis,
                normalizedStaleThumbnails, normalizedExpiredMedia);
    }

    public static String translateReason(int reason) {
        switch (reason) {
            case REASON_UNKNOWN: return "REASON_UNKNOWN";
            case REASON_MOUNTED: return "REASON_MOUNTED";
            case REASON_DEMAND: return "REASON_DEMAND";
            case REASON_IDLE: return "REASON_IDLE";
            default: return String.valueOf(reason);
        }
    }

    private static int translateVolumeName(@NonNull String volumeName) {
        switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL:
                return MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__INTERNAL;
            case MediaStore.VOLUME_EXTERNAL:
                // Callers using generic "external" volume name end up applying
                // to all external volumes, so we can't tell which volumes were
                // actually changed
                return MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__UNKNOWN;
            case MediaStore.VOLUME_EXTERNAL_PRIMARY:
                return MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__EXTERNAL_PRIMARY;
            default:
                return MEDIA_PROVIDER_SCAN_OCCURRED__VOLUME_TYPE__EXTERNAL_OTHER;
        }
    }

    public static final int UNSPECIFIED_URI = MEDIA_PROVIDER_OP_REPORTED__URI_TYPE__URI_UNSPECIFIED;
    public static final int MEDIA_URI = MEDIA_PROVIDER_OP_REPORTED__URI_TYPE__URI_MEDIA;
    public static final int PICKER_URI  = MEDIA_PROVIDER_OP_REPORTED__URI_TYPE__URI_MEDIA_PICKER;
    public static final int REDACTED_URI = MEDIA_PROVIDER_OP_REPORTED__URI_TYPE__URI_MEDIA_REDACTED;
    public static final int DOCUMENT_URI = MEDIA_PROVIDER_OP_REPORTED__URI_TYPE__URI_MEDIA_DOCUMENT;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            UNSPECIFIED_URI,
            MEDIA_URI,
            PICKER_URI,
            REDACTED_URI,
            DOCUMENT_URI
    })
    @interface UriType {}

    public static final int UNSPECIFIED_OP =
            MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__MEDIA_PROVIDER_OP_UNSPECIFIED;
    public static final int ON_CREATE =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__ON_CREATE;
    public static final int BULK_INSERT =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__BULK_INSERT;
    public static final int INSERT =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__INSERT;
    public static final int DELETE =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__DELETE;
    public static final int UPDATE =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__UPDATE;
    public static final int OPEN_FILE =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__OPEN_FILE;
    public static final int OPEN_TYPED_ASSET_FILE =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__OPEN_TYPED_ASSET_FILE;
    public static final int OPEN_FILE_ASYNC =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__OPEN_FILE_ASYNC;
    public static final int OPEN_ASSET_FILE_ASYNC =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__OPEN_ASSET_FILE_ASYNC;
    public static final int APPLY_BATCH =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__APPLY_BATCH;
    public static final int ATTACH_VOLUME =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__ATTACH_VOLUME;
    public static final int DETACH_VOLUME =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__DETACH_VOLUME;
    public static final int RESOLVE_PLAYLIST_MEMBERS_CALL =
            MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__RESOLVE_PLAYLIST_MEMBERS_CALL;
    public static final int GET_VERSION_CALL =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__GET_VERSION_CALL;
    public static final int GET_GENERATION_CALL =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__GET_GENERATION_CALL;
    public static final int GET_DOCUMENT_URI_CALL =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__GET_DOCUMENT_URI_CALL;
    public static final int GET_MEDIA_URI_CALL =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__GET_MEDIA_URI_CALL;
    public static final int GET_REDACTED_MEDIA_URI_CALL =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__GET_REDACTED_MEDIA_URI_CALL;
    public static final int GET_REDACTED_MEDIA_URI_LIST_CALL =
            MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__GET_REDACTED_MEDIA_URI_LIST_CALL;
    public static final int CREATE_WRITE_REQUEST_CALL =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__CREATE_WRITE_REQUEST_CALL;
    public static final int CREATE_FAVORITE_REQUEST_CALL =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__CREATE_FAVORITE_REQUEST_CALL;
    public static final int CREATE_TRASH_REQUEST_CALL =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__CREATE_TRASH_REQUEST_CALL;
    public static final int CREATE_DELETE_REQUEST_CALL =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__CREATE_DELETE_REQUEST_CALL;
    public static final int MARK_MEDIA_AS_FAVORITE =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__MARK_MEDIA_AS_FAVORITE;
    public static final int PICKER_TRANSCODE_CALL =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__PICKER_TRANSCODE_CALL;
    public static final int SYNC_PROVIDERS_CALL =
            MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__SYNC_PROVIDERS_CALL;
    public static final int BULK_UPDATE_OEM_METADATA_CALL =
            MEDIA_PROVIDER_OP_REPORTED__OP_TYPE__BULK_UPDATE_OEM_METADATA_CALL;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            UNSPECIFIED_OP,
            ON_CREATE,
            BULK_INSERT,
            INSERT,
            DELETE,
            UPDATE,
            OPEN_FILE,
            OPEN_TYPED_ASSET_FILE,
            OPEN_FILE_ASYNC,
            OPEN_ASSET_FILE_ASYNC,
            APPLY_BATCH,
            ATTACH_VOLUME,
            DETACH_VOLUME,

            // MediaStore Call APIs
            RESOLVE_PLAYLIST_MEMBERS_CALL,
            GET_VERSION_CALL,
            GET_GENERATION_CALL,
            GET_DOCUMENT_URI_CALL,
            GET_MEDIA_URI_CALL,
            GET_REDACTED_MEDIA_URI_CALL,
            GET_REDACTED_MEDIA_URI_LIST_CALL,
            CREATE_WRITE_REQUEST_CALL,
            CREATE_FAVORITE_REQUEST_CALL,
            CREATE_TRASH_REQUEST_CALL,
            CREATE_DELETE_REQUEST_CALL,
            MARK_MEDIA_AS_FAVORITE,
            PICKER_TRANSCODE_CALL,
            SYNC_PROVIDERS_CALL,
            BULK_UPDATE_OEM_METADATA_CALL
    })
    @interface MediaProviderOpType {}

    /**
     * Translate volume names strings to appropriate MediaProviderOpReported Volume enums
     */
    private static int translateVolumeNameForMediaProviderOp(String volumeName) {
        if (volumeName == null) {
            return MEDIA_PROVIDER_OP_REPORTED__VOLUME__UNKNOWN;
        }

        return switch (volumeName) {
            case MediaStore.VOLUME_INTERNAL ->
                MEDIA_PROVIDER_OP_REPORTED__VOLUME__INTERNAL;
            case MediaStore.VOLUME_EXTERNAL ->
                // Callers using generic "external" volume name end up applying
                // to all external volumes, so we can't tell which volumes were
                // actually changed
                MEDIA_PROVIDER_OP_REPORTED__VOLUME__UNKNOWN;
            case MediaStore.VOLUME_EXTERNAL_PRIMARY ->
                MEDIA_PROVIDER_OP_REPORTED__VOLUME__EXTERNAL_PRIMARY;
            default ->
                MEDIA_PROVIDER_OP_REPORTED__VOLUME__EXTERNAL_OTHER;
        };
    }

    /**
     * Logs performance metrics for MediaProvider operations
     *
     * @param opType Type of MediaProvider operation being performed
     * @param uriType Type of uri received in method call
     * @param volumeName Volume on which operation is performed
     * @param packageUid Calling package uid
     * @param opExecutionTime Execution time of operation in nanoseconds
     */
    public static void logMediaProviderOp(@MediaProviderOpType int opType, @UriType int uriType,
            String volumeName, int packageUid, long opExecutionTime) {
        MediaProviderStatsLog.write(MediaProviderStatsLog.MEDIA_PROVIDER_OP_REPORTED, opType,
                uriType, translateVolumeNameForMediaProviderOp(volumeName), packageUid,
                opExecutionTime);
    }

    /**
     * Logs performance metrics for MediaProvider operations
     * Translated Uri type and Volume type using the MediaProvider instance
     *
     * @param opType Type of MediaProvider operation being performed
     * @param mediaProvider MediaProvider instance
     * @param uri Uri received in method call
     * @param packageUid Calling package uid
     * @param opExecutionTime Execution time of operation in nanoseconds
     */
    public static void logMediaProviderOp(@MediaProviderOpType int opType,
            @NonNull MediaProvider mediaProvider, Uri uri, int packageUid, long opExecutionTime) {
        if (mediaProvider.isPickerUri(uri)) {
            logMediaProviderOp(opType, PICKER_URI, null, packageUid, opExecutionTime);
            return;
        }

        logMediaProviderOp(opType, mediaProvider.isRedactedUri(uri) ? REDACTED_URI : MEDIA_URI,
                MediaProvider.resolveVolumeName(uri), packageUid, opExecutionTime);
    }

    private static int translateCreateRequestOpEnum(String method) {
        return switch (method) {
            case MediaStore.CREATE_WRITE_REQUEST_CALL ->
                    Metrics.CREATE_WRITE_REQUEST_CALL;
            case MediaStore.CREATE_DELETE_REQUEST_CALL ->
                    Metrics.CREATE_DELETE_REQUEST_CALL;
            case MediaStore.CREATE_FAVORITE_REQUEST_CALL ->
                    Metrics.CREATE_FAVORITE_REQUEST_CALL;
            case MediaStore.CREATE_TRASH_REQUEST_CALL ->
                    Metrics.CREATE_TRASH_REQUEST_CALL;
            default ->
                    -1;
        };
    }

    /**
     * Log performance metrics for bulk CreateRequest Mediaprovider operations
     *
     * @param opType Type of createRequest method being performed
     * @param uriType Type of uri received in method call
     * @param volumeName Volume on which operation is performed
     * @param packageUid Calling package uid
     * @param opExecutionTime Execution time of operation in nanoseconds
     */
    public static void logCreateRequestOp(String opType, @UriType int uriType,
            String volumeName, int packageUid, long opExecutionTime) {
        logMediaProviderOp(translateCreateRequestOpEnum(opType), uriType, volumeName,
                packageUid, opExecutionTime);
    }

    /**
     * Schedule a periodic weekly job to collect and log device storage state metrics at device
     * idle maintenance. The job is scheduled at the first run of device idle maintenance job.
     */
    public static void scheduleDeviceStorageStateLoggingJob(@NonNull Context context) {
        try (ContentProviderClient cpc = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            if (cpc != null) {
                MediaProvider mediaProvider = (MediaProvider) cpc.getLocalContentProvider();
                DeviceStorageStateMetricsCollector.schedulePeriodicWork(context, mediaProvider);
            } else {
                Log.w(TAG, "Failed to acquire MediaProvider via ContentProviderClient");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule device storage state logging job", e);
        }
    }
}
