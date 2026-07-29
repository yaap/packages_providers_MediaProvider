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

package com.android.providers.media.appsearch;

import androidx.annotation.NonNull;
import androidx.appsearch.app.EmbeddingVector;
import androidx.appsearch.app.GenericDocument;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MediaItem {
    public static final String SCHEMA_TYPE = "MediaItem";
    public static final String PROPERTY_ID = "id";
    public static final String PROPERTY_NAMESPACE = "namespace";
    public static final String PROPERTY_FILE_ID = "fileId";
    public static final String PROPERTY_DATE_TAKEN = "dateTaken";
    public static final String PROPERTY_MEDIA_TYPE = "mediaType";
    public static final String PROPERTY_METADATA_EXTRACTED = "metadataExtracted";
    public static final String PROPERTY_LOCATION_EXTRACTED = "locationExtracted";
    public static final String PROPERTY_LABELS_EXTRACTED = "labelsExtracted";
    public static final String PROPERTY_DIRTY = "dirty";
    public static final String PROPERTY_EMBEDDINGS = "embeddings";
    public static final String PROPERTY_VOLUME_NAME = "volumeName";

    /**
     * The namespace of the document. This is required for AppSearch documents.
     */
    String namespace;

    /**
     * The unique identifier for the document.
     */
    final String id;

    /**
     * The file id corresponding to media item present in files table.
     */
    final long fileId;

    /**
     * The date taken of the media item.
     */
    final long dateTaken;

    /**
     * The media type of the media item.
     */
    final long mediaType;

    /**
     * Metadata labels extracted from file metadata.
     * This includes file name, directory names, and file extension (e.g., "pdf") etc.
     */
    String metadataExtracted;

    /**
     * Location labels corresponding to the file.
     * This includes country name, locality name, country code etc.
     */
    String locationExtracted;

    /**
     * These are machine-generated labels (e.g., "water", "dog", "pet").
     */
    String labelsExtracted;

    /**
     * Whether the media item is dirty.
     * This value is stored as long values (0L/1L) in appsearch.
     */
    boolean dirty;

    /**
     * The volume to which the media item belongs to.
     */
    final String volumeName;

    /**
     * These are machine-generated embeddings. They are used for semantic search.
     */
    List<EmbeddingVector> embeddings;

    /**
     * Constructs a new {@code MediaItem}.
     *
     * @throws IllegalArgumentException if {@code fileId} or {@code dateTaken} are invalid values
     *                                  (less than or equal to 0).
     */
    public MediaItem(long fileId, long mediaType, long dateTaken, @NonNull String volumeName) {
        validateArgs(fileId, dateTaken);
        this.fileId = fileId;
        this.id = String.valueOf(fileId);
        this.namespace = AppSearchDbManager.NAMESPACE;
        this.mediaType = mediaType;
        this.dateTaken = dateTaken;
        this.volumeName = volumeName;
    }

    private void validateArgs(long fileId, long dateTaken) {
        if (fileId <= 0) {
            throw new IllegalArgumentException(
                    "Invalid media item. FileId must be > 0. Actual : " + fileId);
        }

        if (dateTaken <= 0) {
            throw new IllegalArgumentException(
                    "Invalid media item. Date taken must be > 0. Actual : " + dateTaken);
        }
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getId() {
        return id;
    }

    public long getFileId() {
        return fileId;
    }

    public long getDateTaken() {
        return dateTaken;
    }

    public long getMediaType() {
        return mediaType;
    }

    public String getMetadataExtracted() {
        return metadataExtracted;
    }

    public void setMetadataExtracted(String metadataExtracted) {
        this.metadataExtracted = metadataExtracted;
    }

    public String getLocationExtracted() {
        return locationExtracted;
    }

    public void setLocationExtracted(String locationExtracted) {
        this.locationExtracted = locationExtracted;
    }

    public String getLabelsExtracted() {
        return labelsExtracted;
    }

    public void setLabelsExtracted(String labelsExtracted) {
        this.labelsExtracted = labelsExtracted;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public String getVolumeName() {
        return volumeName;
    }

    public List<EmbeddingVector> getEmbeddings() {
        return embeddings;
    }

    public void setEmbeddings(List<EmbeddingVector> embeddings) {
        this.embeddings = embeddings;
    }


    /**
     * Adds embedding vectors to the media item.
     *
     * @param embeddingVectors The embedding vectors to add.
     */
    public void addEmbeddings(EmbeddingVector... embeddingVectors) {
        if (this.embeddings == null) {
            this.embeddings = new ArrayList<>();
        }
        embeddings.addAll(Arrays.asList(embeddingVectors));
    }

    @Override
    public String toString() {
        return "MediaItem{"
                + "namespace='" + namespace + '\''
                + ", id='" + id + '\''
                + ", fileId=" + fileId
                + ", dateTaken=" + dateTaken
                + ", mediaType=" + mediaType
                + ", metadataExtracted='" + metadataExtracted + '\''
                + ", locationExtracted='" + locationExtracted + '\''
                + ", labelsExtracted='" + labelsExtracted + '\''
                + ", dirty=" + dirty
                + ", volumeName='" + volumeName + '\''
                + ", embeddings=" + embeddings
                + '}';
    }

    /**
     * Converts this {@link MediaItem} object into a {@link GenericDocument}.
     *
     * @return The {@link GenericDocument} representation of this media item.
     */
    public GenericDocument toGenericDocument() {
        GenericDocument.Builder<?> builder =
                new GenericDocument.Builder<>(namespace, id, SCHEMA_TYPE)
                .setPropertyLong(PROPERTY_FILE_ID, fileId)
                .setPropertyLong(PROPERTY_DATE_TAKEN, dateTaken)
                .setPropertyLong(PROPERTY_MEDIA_TYPE, mediaType)
                .setPropertyLong(PROPERTY_DIRTY, dirty ? 1L : 0L);

        if (metadataExtracted != null) {
            builder.setPropertyString(PROPERTY_METADATA_EXTRACTED, metadataExtracted);
        }
        if (locationExtracted != null) {
            builder.setPropertyString(PROPERTY_LOCATION_EXTRACTED, locationExtracted);
        }
        if (labelsExtracted != null) {
            builder.setPropertyString(PROPERTY_LABELS_EXTRACTED, labelsExtracted);
        }
        if (volumeName != null) {
            builder.setPropertyString(PROPERTY_VOLUME_NAME, volumeName);
        }
        if (embeddings != null && !embeddings.isEmpty()) {
            builder.setPropertyEmbedding(PROPERTY_EMBEDDINGS,
                    embeddings.toArray(new EmbeddingVector[0]));
        }

        return builder.build();
    }
}
