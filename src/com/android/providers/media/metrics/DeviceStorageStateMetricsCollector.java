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

package com.android.providers.media.metrics;

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.MediaProvider;
import com.android.providers.media.MediaProviderStatsLog;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Worker} class responsible for collecting device storage state info and scheduling
 * a weekly job to push them as metrics atoms.
 */
public class DeviceStorageStateMetricsCollector extends Worker {

    // Job should run once a week
    private static final int WORK_INTERVAL_DAYS = 7;
    private static final String PERIODIC_WORK_NAME = "LogDeviceStorageStateMetrics";
    public static final int OTHER_MEDIA_TYPES = -1;
    private static MediaProvider sMediaProvider;
    public static final String TAG = "DeviceMetricsCollector";

    public DeviceStorageStateMetricsCollector(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    /**
     * Set MediaProvider instance for DeviceStorageStateMetricsCollector. Only used for testing.
     */
    @VisibleForTesting
    protected void setMediaProvider(MediaProvider mediaProvider) {
        sMediaProvider = mediaProvider;
    }

    /**
     * Create PeriodicWorkRequest which runs once every week with the following constraint - device
     * should be idle
     */
    @VisibleForTesting
    protected static PeriodicWorkRequest createPeriodicWorkRequest() {
        return new PeriodicWorkRequest.Builder(DeviceStorageStateMetricsCollector.class,
                WORK_INTERVAL_DAYS, TimeUnit.DAYS)
                .setConstraints(new Constraints.Builder().setRequiresDeviceIdle(true).build())
                .build();
    }

    /**
     * Schedule a periodic job to collect and log storage state metrics for a device.
     */
    public static void schedulePeriodicWork(@NonNull Context context, MediaProvider mediaProvider) {
        // Schedule job to run once every week
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, createPeriodicWorkRequest());
        sMediaProvider = mediaProvider;
    }

    private String getSelectionString(int mediaType) {
        String selectionString = switch (mediaType) {
            case MEDIA_TYPE_IMAGE, MEDIA_TYPE_AUDIO, MEDIA_TYPE_VIDEO ->
                    "media_type = " + mediaType;
            case MEDIA_TYPE_DOCUMENT ->
                    "(media_type = " + mediaType + " OR primary_directory = 'Documents')";
            case OTHER_MEDIA_TYPES ->
                    "media_type NOT IN (1, 2, 3, 6) AND (primary_directory IS NULL OR "
                            + "primary_directory != 'Documents')";
            default -> throw new IllegalArgumentException("Invalid media type : " + mediaType);
        };

        return selectionString.concat(" AND (is_trashed = 0 OR is_pending = 0)");
    }

    private Pair<Integer, Integer> queryStorageStateForMediaType(DatabaseHelper mExternalDb,
            int mediaType) {
        String[] projection = new String[]{"COUNT(*) AS num_files",
                "SUM(_size)/1000000 AS files_storage_size_mb"};

        try (Cursor c = mExternalDb.runWithoutTransaction((db) -> {
            return db.query("files", projection, getSelectionString(mediaType), null, null, null,
                    null);
        })) {
            if (c == null || c.getCount() == 0) {
                return new Pair<>(0, 0);
            }

            c.moveToFirst();
            return new Pair<>(c.getInt(0), c.getInt(1));
        }
    }

    private boolean collectDeviceStorageStateMetrics(DatabaseHelper mExternalDb) {
        String[] projection = new String[]{"SUM(_size)/1000000 AS device_storage_size_mb",
                "COUNT(*) AS num_files_shared_storage",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Documents/%\" THEN 1 ELSE 0 END) "
                        + "AS num_default_documents_dir",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Download/%\" THEN 1 ELSE 0 END) "
                        + "AS num_default_download_dir",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Android/media/%\" THEN 1 ELSE 0 "
                        + "END) AS num_android_media_dir",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%\" THEN _size ELSE 0 END)/1000000 "
                        + "AS files_shared_storage_size_mb",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Documents/%\" THEN _size ELSE 0 "
                        + "END)/1000000 AS default_documents_dir_storage_size_mb",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Download/%\" THEN _size ELSE 0 "
                        + "END)/1000000 AS default_download_dir_storage_size_mb",
                "SUM(CASE WHEN _data LIKE \"/storage/emulated/%/Android/media/%\" THEN _size ELSE"
                        + " 0 END)/1000000 AS android_media_dir_storage_size_mb"};

        String selection = "is_trashed = 0 OR is_pending = 0";

        try (Cursor c = mExternalDb.runWithoutTransaction((db) -> {
            return db.query("files", projection, selection, null, null, null, null);
        })) {
            if (c == null || c.getCount() == 0) {
                return false;
            }

            Pair<Integer, Integer> imagesCountAndSize = queryStorageStateForMediaType(mExternalDb,
                    MEDIA_TYPE_IMAGE);
            Pair<Integer, Integer> videosCountAndSize = queryStorageStateForMediaType(mExternalDb,
                    MEDIA_TYPE_VIDEO);
            Pair<Integer, Integer> audioCountAndSize = queryStorageStateForMediaType(mExternalDb,
                    MEDIA_TYPE_AUDIO);
            Pair<Integer, Integer> documentCountAndSize = queryStorageStateForMediaType(mExternalDb,
                    MEDIA_TYPE_DOCUMENT);
            Pair<Integer, Integer> otherMediaTypesCountAndSize = queryStorageStateForMediaType(
                    mExternalDb, OTHER_MEDIA_TYPES);

            if (c.moveToFirst()) {
                logDeviceStorageStateReported(
                        /* device_storage_size_mb */ c.getInt(0),
                        /* num_files_in_shared_storage */ c.getInt(1),
                        /* num_images */ imagesCountAndSize.first,
                        /* num_videos */ videosCountAndSize.first,
                        /* num_audio */ audioCountAndSize.first,
                        /* num_documents */ documentCountAndSize.first,
                        /* num_other_media */ otherMediaTypesCountAndSize.first,
                        /* num_in_default_documents */ c.getInt(2),
                        /* num_in_default_downloads */ c.getInt(3),
                        /* num_in_android_media */ c.getInt(4),
                        /* files_shared_storage_size_mb */ c.getInt(5),
                        /* images_storage_size_mb */ imagesCountAndSize.second,
                        /* videos_storage_size_mb */ videosCountAndSize.second,
                        /* audio_storage_size_mb */ audioCountAndSize.second,
                        /* documents_storage_size_mb */ documentCountAndSize.second,
                        /* other_media_storage_size_mb */ otherMediaTypesCountAndSize.second,
                        /* default_downloads_storage_size_mb */ c.getInt(6),
                        /* default_documents_storage_size_mb */ c.getInt(7),
                        /* android_media_storage_size_mb */ c.getInt(8));

                return true;
            }
        }

        return false;
    }

    @VisibleForTesting
    protected void logDeviceStorageStateReported(long deviceStorageSizeMb,
            int numFilesInSharedStorage, int numImages, int numVideos, int numAudio,
            int numDocuments, int numOtherMedia, int numInDefaultDocuments,
            int numInDefaultDownloads, int numInAndroidMedia, int filesSharedStorageSizeMb,
            int imagesStorageSizeMb, int videosStorageSizeMb, int audioStorageSizeMb,
            int documentsStorageSizeMb, int otherMediaStorageSizeMb,
            int defaultDownloadsStorageSizeMb, int defaultDocumentsStorageSizeMb,
            int androidMediaStorageSizeMb) {
        MediaProviderStatsLog.write(MediaProviderStatsLog.DEVICE_STORAGE_STATE_REPORTED,
                deviceStorageSizeMb, numFilesInSharedStorage, numImages, numVideos, numAudio,
                numDocuments, numOtherMedia, numInDefaultDocuments, numInDefaultDownloads,
                numInAndroidMedia, filesSharedStorageSizeMb, imagesStorageSizeMb,
                videosStorageSizeMb, audioStorageSizeMb, documentsStorageSizeMb,
                otherMediaStorageSizeMb, defaultDownloadsStorageSizeMb,
                defaultDocumentsStorageSizeMb, androidMediaStorageSizeMb);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (sMediaProvider == null) {
            return Result.failure();
        }

        try {
            Optional<DatabaseHelper> mExternalDb = sMediaProvider.getDatabaseHelper(
                    DatabaseHelper.EXTERNAL_DATABASE_NAME);
            if (mExternalDb.isPresent() && collectDeviceStorageStateMetrics(mExternalDb.get())) {
                return Result.success();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to log device storage state metrics. " + e.getMessage());
            return Result.failure();
        }

        return Result.failure();
    }
}
