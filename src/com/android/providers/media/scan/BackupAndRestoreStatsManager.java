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

package com.android.providers.media.scan;

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;

import static com.android.providers.media.MediaProviderStatsLog.MEDIA_METADATA_EXTRACTION_REPORTED;

import com.android.providers.media.MediaProviderStatsLog;

class BackupAndRestoreStatsManager {
    /**
     * The log will only be generated if number of files scanned will be greater than this
     * threshold. This is to avoid to many logs for every incremental media scan.
     */
    private static final int LOGGING_THRESHOLD = 10;

    private static int sNumFilesRestoredFromBackup = 0;

    private static final MediaStatsCollector IMAGE_STATS = new MediaStatsCollector();
    private static final MediaStatsCollector VIDEO_STATS = new MediaStatsCollector();
    private static final MediaStatsCollector AUDIO_STATS = new MediaStatsCollector();
    private static final MediaStatsCollector DOCUMENT_STATS = new MediaStatsCollector();

    private static int sTotalFilesScanned = 0;
    private static long sTotalExtractionTimeNs = 0;

    private BackupAndRestoreStatsManager() {
    }

    /**
     * Adds metadata extraction statistics for a specific media type.
     *
     * @param mediaType MediaType of the file
     * @param fromBackup True if metadata was extracted using backup
     * @param timeTakenNs Time taken in nanoseconds
     */
    public static void addStatForMediaType(int mediaType, boolean fromBackup, long timeTakenNs) {
        switch (mediaType) {
            case MEDIA_TYPE_IMAGE:
                IMAGE_STATS.record(fromBackup, timeTakenNs);
                break;
            case MEDIA_TYPE_VIDEO:
                VIDEO_STATS.record(fromBackup, timeTakenNs);
                break;
            case MEDIA_TYPE_AUDIO:
                AUDIO_STATS.record(fromBackup, timeTakenNs);
                break;
            case MEDIA_TYPE_DOCUMENT:
                DOCUMENT_STATS.record(fromBackup, timeTakenNs);
                break;
            default:
                return;
        }

        if (fromBackup) {
            sNumFilesRestoredFromBackup++;
        }

        sTotalFilesScanned++;
        sTotalExtractionTimeNs += timeTakenNs;
    }

    /**
     * Logs stats if threshold is met and resets all tracked values.
     */
    public static void logStats() {
        if (sTotalFilesScanned < LOGGING_THRESHOLD) {
            reset();
            return;
        }

        long avgTimeNs = sTotalExtractionTimeNs / sTotalFilesScanned;

        MediaProviderStatsLog.write(MEDIA_METADATA_EXTRACTION_REPORTED,
                sTotalFilesScanned,
                sNumFilesRestoredFromBackup,
                avgTimeNs,
                IMAGE_STATS.getWithBackupCount(),
                IMAGE_STATS.getWithBackupAvgTimeNs(),
                IMAGE_STATS.getWithoutBackupCount(),
                IMAGE_STATS.getWithoutBackupAvgTimeNs(),
                VIDEO_STATS.getWithBackupCount(),
                VIDEO_STATS.getWithBackupAvgTimeNs(),
                VIDEO_STATS.getWithoutBackupCount(),
                VIDEO_STATS.getWithoutBackupAvgTimeNs(),
                AUDIO_STATS.getWithBackupCount(),
                AUDIO_STATS.getWithBackupAvgTimeNs(),
                AUDIO_STATS.getWithoutBackupCount(),
                AUDIO_STATS.getWithoutBackupAvgTimeNs(),
                DOCUMENT_STATS.getWithBackupCount(),
                DOCUMENT_STATS.getWithBackupAvgTimeNs(),
                DOCUMENT_STATS.getWithoutBackupCount(),
                DOCUMENT_STATS.getWithoutBackupAvgTimeNs()
        );

        reset();
    }

    /**
     * Resets all tracked counters and timers.
     */
    private static void reset() {
        sNumFilesRestoredFromBackup = 0;
        sTotalFilesScanned = 0;
        sTotalExtractionTimeNs = 0;
        IMAGE_STATS.reset();
        VIDEO_STATS.reset();
        AUDIO_STATS.reset();
        DOCUMENT_STATS.reset();
    }

    private static class MediaStatsCollector {
        private int mWithBackupCount = 0;
        private long mWithBackupTotalTimeNs = 0;
        private int mWithoutBackupCount = 0;
        private long mWithoutBackupTotalTimeNs = 0;

        void record(boolean usedBackup, long timeNs) {
            if (usedBackup) {
                mWithBackupCount++;
                mWithBackupTotalTimeNs += timeNs;
            } else {
                mWithoutBackupCount++;
                mWithoutBackupTotalTimeNs += timeNs;
            }
        }

        int getWithBackupCount() {
            return mWithBackupCount;
        }

        long getWithBackupAvgTimeNs() {
            return mWithBackupCount > 0 ? mWithBackupTotalTimeNs / mWithBackupCount : 0;
        }

        int getWithoutBackupCount() {
            return mWithoutBackupCount;
        }

        long getWithoutBackupAvgTimeNs() {
            return mWithoutBackupCount > 0 ? mWithoutBackupTotalTimeNs / mWithoutBackupCount : 0;
        }

        void reset() {
            mWithBackupCount = 0;
            mWithBackupTotalTimeNs = 0;
            mWithoutBackupCount = 0;
            mWithoutBackupTotalTimeNs = 0;
        }
    }
}
