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

package com.android.providers.media.localsearch;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.R;
import com.android.providers.media.flags.Flags;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Generates searchable text labels from the metadata of media files.
 * The labels are constructed by processing various columns from the MediaStore.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class MetadataLabelResolver {

    private static final String TAG = "MetadataLabelResolver";

    private final Context mContext;

    public MetadataLabelResolver(@NonNull Context context) {
        mContext = context;
    }

    // Helper method to add non-null/empty strings to the list
    // Returns true if the string was added.
    private static boolean addIfNotNull(Set<String> labels, @Nullable String label) {
        if (!TextUtils.isEmpty(label)) {
            Collections.addAll(labels, label.trim().split("\\s+"));
            return true;
        }

        return false;
    }

    /**
     * Builds a concatenated metadata label string for a single media item.
     * The label is composed of various processed metadata fields, separated by spaces.
     *
     * @param info The {@link MetadataInfo} object containing the media's metadata.
     * @return A space-separated string label, or an empty string if no relevant metadata is
     * present.
     */
    @VisibleForTesting
    String buildMetadataLabel(@NonNull MetadataInfo info) {
        Set<String> labels = new ArraySet<>();

        addIfNotNull(labels, processDisplayName(info.displayName));
        addIfNotNull(labels, processRelativePath(info.relativePath));
        processMediaType(info.mediaType, labels);
        addIfNotNull(labels, processMimeType(info.mimeType));
        processSpecialFormat(info.specialFormat, labels);
        if (!addIfNotNull(labels, processTimestamp(info.dateTaken))) {
            addIfNotNull(labels, processTimestamp(info.dateAdded));
        }
        processIsFavorite(info.isFavorite, labels);
        processIsDownload(info.isDownload, labels);
        addIfNotNull(labels, info.artist);
        addIfNotNull(labels, info.album);
        addIfNotNull(labels, info.genre);

        return TextUtils.join(" ", labels).toLowerCase(Locale.ROOT);
    }

    /**
     * Generates metadata labels for a list of media items.
     *
     * @param mediaInfos A list of {@link MetadataInfo} objects.
     * @return A map where keys are the media item IDs and values are the generated
     *         metadata labels.
     */
    public Map<Long, String> generateMetadataLabels(@NonNull List<MetadataInfo> mediaInfos) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || !Flags.enableMediaProcessing()) {
            return null;
        }

        Map<Long, String> labelsMap = new HashMap<>();

        for (MetadataInfo info : mediaInfos) {
            labelsMap.put(info.id, buildMetadataLabel(info));
        }

        return labelsMap;
    }

    // Converts “IMG_20240911.jpg” to “IMG_20240911 jpg”
    private static String processDisplayName(String displayName) {
        if (displayName == null || TextUtils.isEmpty(displayName)) {
            return "";
        }

        int lastDotIndex = displayName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == 0) {
            return displayName;
        }

        String name = displayName.substring(0, lastDotIndex);
        String fileExt = displayName.substring(lastDotIndex + 1);
        return name + " " + fileExt;
    }

    // Converts "/DCIM/Camera/" to "DCIM Camera"
    private static String processRelativePath(String relativePath) {
        if (relativePath == null || TextUtils.isEmpty(relativePath)) {
            return "";
        }
        return relativePath.replace("/", " ").trim();
    }

    // Converts "image/jpeg" to "image jpeg"
    private static String processMimeType(String mimeType) {
        if (mimeType == null || TextUtils.isEmpty(mimeType)) {
            return "";
        }
        return mimeType.replace("/", " ");
    }

    // Converts media_type int to a string
    private void processMediaType(int mediaType, Set<String> labels) {
        switch (mediaType) {
            case FileColumns.MEDIA_TYPE_IMAGE -> {
                addIfNotNull(labels, mContext.getString(R.string.metadata_label_image));
                addIfNotNull(labels, mContext.getString(R.string.metadata_label_images));
                addIfNotNull(labels, mContext.getString(R.string.metadata_label_photo));
                addIfNotNull(labels, mContext.getString(R.string.metadata_label_photos));
            }
            case FileColumns.MEDIA_TYPE_VIDEO -> {
                addIfNotNull(labels, mContext.getString(R.string.metadata_label_video));
                addIfNotNull(labels, mContext.getString(R.string.metadata_label_videos));
            }
            case FileColumns.MEDIA_TYPE_AUDIO -> {
                addIfNotNull(labels, mContext.getString(R.string.metadata_label_audio));
                addIfNotNull(labels, mContext.getString(R.string.metadata_label_audios));
            }
            case FileColumns.MEDIA_TYPE_DOCUMENT -> {
                addIfNotNull(labels, mContext.getString(R.string.metadata_label_document));
                addIfNotNull(labels, mContext.getString(R.string.metadata_label_documents));
            }
        }
    }

    // Converts special_format int to a string
    private void processSpecialFormat(int specialFormat,
            Set<String> labels) {
        switch (specialFormat) {
            case FileColumns.SPECIAL_FORMAT_ANIMATED_WEBP ->
                    addIfNotNull(labels, mContext.getString(R.string.metadata_label_animated));
            case FileColumns.SPECIAL_FORMAT_MOTION_PHOTO ->
                addIfNotNull(labels, mContext.getString(R.string.metadata_label_motion_photo));
            case FileColumns.SPECIAL_FORMAT_GIF -> {
                addIfNotNull(labels, "gif gifs");
            }
        }
    }

    // Converts 1634048606830L to "October 2021"
    private static String processTimestamp(long timestamp) {
        if (timestamp <= 0) { // MediaStore uses 0 for unknown, and negative is invalid
            return "";
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM yyyy",
                    Locale.getDefault());
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp),
                    ZoneId.systemDefault()).format(formatter);
        } catch (Exception e) {
            Log.e(TAG, "Error formatting timestamp: " + timestamp, e);
            return "";
        }
    }

    // Returns the label if value is non-zero (true)
    private void processIsFavorite(int value, Set<String> labels) {
        if (value != 0) {
            addIfNotNull(labels, mContext.getString(R.string.metadata_label_favorite));
            addIfNotNull(labels, mContext.getString(R.string.metadata_label_favorites));
        }
    }

    private void processIsDownload(int value, Set<String> labels) {
        if (value != 0) {
            addIfNotNull(labels, mContext.getString(R.string.metadata_label_download));
            addIfNotNull(labels, mContext.getString(R.string.metadata_label_downloads));
        }
    }

    /**
     * Data class holding metadata for a single media item, used for generating labels.
     */
    public static class MetadataInfo {
        public final long id;
        public final String displayName;
        public final String relativePath;
        public final int mediaType;
        public final String mimeType;
        public final int specialFormat;
        public final long dateTaken;
        public final long dateAdded;
        public final int isFavorite;
        public final int isDownload;
        public final String artist;
        public final String album;
        public final String genre;

        // Private constructor: Only the Builder can instantiate this
        private MetadataInfo(Builder builder) {
            this.id = builder.mId;
            this.displayName = builder.mDisplayName;
            this.relativePath = builder.mRelativePath;
            this.mediaType = builder.mMediaType;
            this.mimeType = builder.mMimeType;
            this.specialFormat = builder.mSpecialFormat;
            this.dateTaken = builder.mDateTaken;
            this.dateAdded = builder.mDateAdded;
            this.isFavorite = builder.mIsFavorite;
            this.isDownload = builder.mIsDownload;
            this.artist = builder.mArtist;
            this.album = builder.mAlbum;
            this.genre = builder.mGenre;
        }

        /**
         * Builder for {@link MetadataInfo}.
         */
        public static class Builder {
            // Mutable fields with default values
            private long mId = -1;
            private String mDisplayName;
            private String mRelativePath;
            private int mMediaType;
            private String mMimeType;
            private int mSpecialFormat;
            private long mDateTaken;
            private long mDateAdded;
            private int mIsFavorite;
            private int mIsDownload;
            private String mArtist;
            private String mAlbum;
            private String mGenre;

            /**
             * Constructs a new Builder.
             */
            public Builder() {
            }

            /** Sets the media ID. */
            public Builder setId(long id) {
                this.mId = id;
                return this;
            }

            /** Sets the display name. */
            public Builder setDisplayName(String displayName) {
                this.mDisplayName = displayName;
                return this;
            }

            /** Sets the relative path. */
            public Builder setRelativePath(String relativePath) {
                this.mRelativePath = relativePath;
                return this;
            }

            /** Sets the media type (e.g., {@link FileColumns#MEDIA_TYPE_IMAGE}). */
            public Builder setMediaType(int mediaType) {
                this.mMediaType = mediaType;
                return this;
            }

            /** Sets the MIME type. */
            public Builder setMimeType(String mimeType) {
                this.mMimeType = mimeType;
                return this;
            }

            /** Sets the special format code. */
            public Builder setSpecialFormat(int specialFormat) {
                this.mSpecialFormat = specialFormat;
                return this;
            }

            /** Sets the date taken timestamp (milliseconds since epoch, typically UTC). */
            public Builder setDateTaken(long dateTaken) {
                this.mDateTaken = dateTaken;
                return this;
            }

            /** Sets the date added timestamp (milliseconds since epoch, local time). */
            public Builder setDateAdded(long dateAdded) {
                this.mDateAdded = dateAdded;
                return this;
            }

            /** Sets the favorite status (1 for favorite, 0 otherwise). */
            public Builder setIsFavorite(int isFavorite) {
                this.mIsFavorite = isFavorite;
                return this;
            }

            /** Sets the download status (1 for download, 0 otherwise). */
            public Builder setIsDownload(int isDownload) {
                this.mIsDownload = isDownload;
                return this;
            }

            /** Sets the artist name. */
            public Builder setArtist(String artist) {
                this.mArtist = artist;
                return this;
            }

            /** Sets the album name. */
            public Builder setAlbum(String album) {
                this.mAlbum = album;
                return this;
            }

            /** Sets the genre. */
            public Builder setGenre(String genre) {
                this.mGenre = genre;
                return this;
            }

            /**
             * Populates builder fields from a Cursor.
             * The Cursor must contain all the required columns from {@link MediaColumns} and
             * {@link FileColumns}.
             * <p>Note: For high-performance loops, consider resolving column indices
             * outside this method and creating a version that accepts pre-calculated indices.
             * @param c The Cursor to read data from.
             * @return This Builder instance.
             */
            public Builder setDataFromCursor(Cursor c) {
                this.mId = c.getLong(c.getColumnIndexOrThrow(MediaColumns._ID));
                this.mDisplayName = c.getString(
                        c.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME));
                this.mRelativePath = c.getString(
                        c.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH));
                this.mMediaType = c.getInt(c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE));
                this.mMimeType = c.getString(c.getColumnIndexOrThrow(MediaColumns.MIME_TYPE));
                this.mSpecialFormat = c.getInt(c.getColumnIndexOrThrow(FileColumns.SPECIAL_FORMAT));
                this.mDateTaken = c.getLong(c.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN));
                this.mDateAdded = c.getLong(c.getColumnIndexOrThrow(MediaColumns.DATE_ADDED));
                this.mIsFavorite = c.getInt(c.getColumnIndexOrThrow(MediaColumns.IS_FAVORITE));
                this.mIsDownload = c.getInt(c.getColumnIndexOrThrow(MediaColumns.IS_DOWNLOAD));
                this.mArtist = c.getString(c.getColumnIndexOrThrow(FileColumns.ARTIST));
                this.mAlbum = c.getString(c.getColumnIndexOrThrow(FileColumns.ALBUM));
                this.mGenre = c.getString(c.getColumnIndexOrThrow(FileColumns.GENRE));
                return this;
            }

            /**
             * Builds the {@link MetadataInfo} instance.
             *
             * @return A new MetadataInfo instance.
             */
            public MetadataInfo build() {
                return new MetadataInfo(this);
            }
        }
    }
}
