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

import static androidx.appsearch.app.Features.LIST_FILTER_QUERY_LANGUAGE;
import static androidx.appsearch.app.Features.NUMERIC_SEARCH;
import static androidx.appsearch.app.Features.SCHEMA_EMBEDDING_PROPERTY_CONFIG;
import static androidx.appsearch.app.Features.SCHEMA_SCORABLE_PROPERTY_CONFIG;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appsearch.app.AppSearchBatchResult;
import androidx.appsearch.app.AppSearchSession;
import androidx.appsearch.app.EmbeddingVector;
import androidx.appsearch.app.Features;
import androidx.appsearch.app.GenericDocument;
import androidx.appsearch.app.PutDocumentsRequest;
import androidx.appsearch.app.RemoveByDocumentIdRequest;
import androidx.appsearch.app.SearchResult;
import androidx.appsearch.app.SearchResults;
import androidx.appsearch.app.SearchSpec;
import androidx.appsearch.app.SetSchemaRequest;
import androidx.appsearch.platformstorage.PlatformStorage;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.flags.Flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Manages all interactions with the AppSearch database used for local media search.
 *
 * <p>This class encapsulates the logic for connecting to the database, setting and updating the
 * schema, and performing thread-safe Create, Read, Update, and Delete (CRUD) operations on
 * {@link MediaItem} documents.
 *
 * <p>All bulk operations have a limit of {@link #MAX_BULK_OPERATIONS_SIZE} documents per call.
 *
 * <p>Usage of this class is only supported on devices running Android T (API 33) or higher and
 * when the {@code enable_media_search} flag is enabled.
 */
public final class AppSearchDbManager {
    private static final String TAG = AppSearchDbManager.class.getSimpleName();
    public static final String NAMESPACE = "media_appsearch_namespace";
    static final String DATABASE_NAME = "media_appsearch_db";
    static final int LATEST_SCHEMA_VERSION = 1;
    static final String SHARED_PREFERENCE_NAME = "media_appsearch_schema_version";
    static final String CURRENT_SCHEMA_VERSION = "media_appsearch_current_schema_version";
    public static final int MAX_BULK_OPERATIONS_SIZE = 1000;

    /** The maximum number of documents allowed in the AppSearch index. */
    public static final int MAX_DOCUMENT_COUNT = 50000;

    private AppSearchSession mAppSearchSession;
    private final Context mContext;

    /**
     * A read-write lock to allow concurrent reads while ensuring exclusive access for writes.
     */
    private static final ReentrantReadWriteLock sReadWriteLock = new ReentrantReadWriteLock();

    public AppSearchDbManager(@NonNull Context context) throws Exception {
        ensureAppSearchDbManagerSupported();
        this.mContext = context.getApplicationContext();
        if (SdkLevel.isAtLeastT()) {
            connect();
        }
    }

    private static void ensureAppSearchDbManagerSupported() {
        if (!Flags.enableMediaSearch()) {
            throw new UnsupportedOperationException("Flag enable_media_search should be enabled.");
        }

        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException("Localsearch is only enabled for "
                    + "Android T (API 33) or higher.");
        }
    }

    /**
     * Connects to the AppSearch database and sets the schema.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void connect() throws Exception {
        final long startTimeMillis = SystemClock.elapsedRealtime();
        try {
            if (mAppSearchSession != null) {
                Log.v(TAG, "Already connected to AppSearch. Ignoring connect() call.");
                return;
            }

            Log.d(TAG, "Connecting to AppSearch PlatformStorage for " + DATABASE_NAME);
            mAppSearchSession = PlatformStorage.createSearchSessionAsync(new
                    PlatformStorage.SearchContext.Builder(mContext, DATABASE_NAME).build()).get();
            ensureRequiredAppSearchFeaturesSupported();
            if (isSchemaUpdateRequired()) {
                SetSchemaRequest setSchemaRequest = AppSearchSchemaFactory.buildSchema();
                mAppSearchSession.setSchemaAsync(setSchemaRequest).get();
                updateSchemaVersionToLatest();
                Log.i(TAG, "Updated appsearch db schema to version " + LATEST_SCHEMA_VERSION);
            }

            Log.i(TAG, "Appsearch db connected.");
        } finally {
            Log.d(TAG, "connect() took " + (SystemClock.elapsedRealtime() - startTimeMillis)
                    + " ms");
        }
    }

    private void ensureRequiredAppSearchFeaturesSupported() {
        Features features = mAppSearchSession.getFeatures();
        boolean areFeaturesSupported =  features.isFeatureSupported(NUMERIC_SEARCH)
                && features.isFeatureSupported(SCHEMA_SCORABLE_PROPERTY_CONFIG)
                && features.isFeatureSupported(LIST_FILTER_QUERY_LANGUAGE)
                && features.isFeatureSupported(SCHEMA_EMBEDDING_PROPERTY_CONFIG);
        if (!areFeaturesSupported) {
            throw new UnsupportedOperationException(
                    "Required appsearch features are not supported");
        }
    }

    private void updateSchemaVersionToLatest() {
        mContext.getSharedPreferences(SHARED_PREFERENCE_NAME, Context.MODE_PRIVATE).edit()
                .putInt(CURRENT_SCHEMA_VERSION, LATEST_SCHEMA_VERSION).apply();
    }

    private boolean isSchemaUpdateRequired() {
        int savedVersion = mContext.getSharedPreferences(SHARED_PREFERENCE_NAME,
                Context.MODE_PRIVATE).getInt(CURRENT_SCHEMA_VERSION, /* default value */ 0);
        return savedVersion < LATEST_SCHEMA_VERSION;
    }

    /**
     * Disconnects from the AppSearch database.
     */
    public void disconnect() {
        final long startTimeMillis = SystemClock.elapsedRealtime();
        sReadWriteLock.writeLock().lock();
        try {
            if (mAppSearchSession != null) {
                mAppSearchSession.close();
                mAppSearchSession = null;
                Log.i(TAG, "Appsearch db disconnected");
            }
        } finally {
            sReadWriteLock.writeLock().unlock();
            Log.d(TAG, "disconnect() took " + (SystemClock.elapsedRealtime() - startTimeMillis)
                    + " ms");
        }
    }

    /**
     * Inserts documents in a thread safe manner.
     *
     * @param documents The list of documents to insert. The size of this list should not exceed
     *                  {@link #MAX_BULK_OPERATIONS_SIZE}.
     * @throws IllegalArgumentException if the list size exceeds {@link #MAX_BULK_OPERATIONS_SIZE}
     *                                  or if any {@link MediaItem} in the list contains invalid
     *                                  field values according to the validation checks in
     *                                  {@code validateMediaItemList}.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void insertDocuments(@NonNull List<MediaItem> documents) throws Exception {
        if (documents.size() > MAX_BULK_OPERATIONS_SIZE) {
            throw new IllegalArgumentException("Document list size exceeds the limit of "
                    + MAX_BULK_OPERATIONS_SIZE);
        }
        validateMediaItemList(documents);
        final long startTimeMillis = SystemClock.elapsedRealtime();
        sReadWriteLock.writeLock().lock();
        try {
            Trace.beginSection("AppSearchDbManager.insertDocuments");
            ensureAppSearchDbConnected();
            AppSearchBatchResult<String, Void> result = putDocuments(documents);

            Log.v(TAG, "Insert documents complete. Requested: " + documents.size()
                    + ", Success: " + result.getSuccesses().size()
                    + ", Failures: " + result.getFailures().size());
        } finally {
            sReadWriteLock.writeLock().unlock();
            Trace.endSection();
            Log.d(TAG, "insertDocuments() took " + (SystemClock.elapsedRealtime()
                    - startTimeMillis) + " ms");
        }
    }

    private void validateMediaItemList(@NonNull List<MediaItem> mediaItems) {
        for (MediaItem mediaItem : mediaItems) {
            List<String> invalidFields = new ArrayList<>();

            if (mediaItem.getFileId() <= 0) {
                invalidFields.add("fileId (must be > 0)");
            }
            if (mediaItem.getDateTaken() <= 0) {
                invalidFields.add("dateTaken (must be > 0)");
            }
            if (mediaItem.getVolumeName() == null) {
                invalidFields.add("volumeName (cannot be null)");
            }
            if (mediaItem.getId() == null) {
                invalidFields.add("id (cannot be null)");
            }
            if (mediaItem.getNamespace() == null) {
                invalidFields.add("namespace (cannot be null)");
            }

            if (!invalidFields.isEmpty()) {
                throw new IllegalArgumentException("Invalid MediaItem found. Issues: "
                        + invalidFields + ". MediaItem: " + mediaItem);
            }
        }
    }

    @NonNull
    private AppSearchBatchResult<String, Void> putDocuments(@NonNull List<MediaItem> documents)
            throws Exception {
        List<GenericDocument> docs =
                documents.stream().map(MediaItem::toGenericDocument).collect(Collectors.toList());

        PutDocumentsRequest.Builder putRequestBuilder = new PutDocumentsRequest.Builder();
        putRequestBuilder.addGenericDocuments(docs);

        return mAppSearchSession.putAsync(putRequestBuilder.build()).get();
    }

    /**
     * Updates existing documents in a thread-safe manner.
     *
     * @param updatesByFileId A map where the key is the fileId of the document to update,
     *                        and the value is an {@link UpdateSpec} containing the properties
     *                        to change. The size of this map should not exceed
     *                        {@link #MAX_BULK_OPERATIONS_SIZE}.
     * @throws IllegalArgumentException if the map size exceeds {@link #MAX_BULK_OPERATIONS_SIZE}
     *                                  or if any {@link MediaItem} to be updated results in an
     *                                  invalid state according to the validation checks in
     *                                  {@code validateMediaItemList}.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void updateDocuments(@NonNull Map<Long, UpdateSpec> updatesByFileId) throws Exception {
        if (updatesByFileId.size() > MAX_BULK_OPERATIONS_SIZE) {
            throw new IllegalArgumentException("Updates map size exceeds the limit of "
                    + MAX_BULK_OPERATIONS_SIZE);
        }
        final long startTimeMillis = SystemClock.elapsedRealtime();
        ensureAppSearchDbConnected();
        sReadWriteLock.writeLock().lock();
        try {
            Trace.beginSection("AppSearchDbManager.updateDocuments");
            List<Long> fileIds = new ArrayList<>(updatesByFileId.keySet());
            List<GenericDocument> documents = getDocumentsByFileIds(fileIds);
            List<MediaItem> docsToUpdate = new ArrayList<>();

            for (GenericDocument genericDocument : documents) {
                long fileId = genericDocument.getPropertyLong(MediaItem.PROPERTY_FILE_ID);
                UpdateSpec spec = updatesByFileId.get(fileId);
                if (spec == null) {
                    continue;
                }

                MediaItem mediaItem = convertGenericDocumentToMediaItem(genericDocument);
                if (mediaItem == null) {
                    continue;
                }

                for (Map.Entry<String, Object> entry : spec.getPropertiesToUpdate().entrySet()) {
                    String property = entry.getKey();
                    Object value = entry.getValue();

                    try {
                        switch (property) {
                            case MediaItem.PROPERTY_METADATA_EXTRACTED ->
                                    mediaItem.setMetadataExtracted((String) value);
                            case MediaItem.PROPERTY_LOCATION_EXTRACTED ->
                                    mediaItem.setLocationExtracted((String) value);
                            case MediaItem.PROPERTY_LABELS_EXTRACTED ->
                                    mediaItem.setLabelsExtracted((String) value);
                            case MediaItem.PROPERTY_DIRTY -> mediaItem.setDirty((boolean) value);
                            case MediaItem.PROPERTY_EMBEDDINGS -> {
                                @SuppressWarnings("unchecked")
                                List<EmbeddingVector> embeddingList = (List<EmbeddingVector>) value;
                                mediaItem.setEmbeddings(embeddingList);
                            }
                            default -> Log.w(TAG, "Attempted to update unknown property: "
                                    + property);
                        }
                    } catch (ClassCastException e) {
                        Log.w(TAG, "Invalid value type for property: " + property, e);
                    }
                }
                docsToUpdate.add(mediaItem);
            }

            if (!docsToUpdate.isEmpty()) {
                validateMediaItemList(docsToUpdate);
                AppSearchBatchResult<String, Void> result = putDocuments(docsToUpdate);
                Log.v(TAG, "Update documents complete. Requested: " + documents.size()
                        + ", Success: " + result.getSuccesses().size()
                        + ", Failures: " + result.getFailures().size());
            }
        } finally {
            sReadWriteLock.writeLock().unlock();
            Trace.endSection();
            Log.d(TAG, "updateDocuments() took " + (SystemClock.elapsedRealtime()
                    - startTimeMillis) + " ms");
        }
    }

    /**
     * Retrieves documents by their file IDs.
     * <p>
     * This is a read-only operation.
     *
     * @param fileIds The list of file IDs to retrieve.The size of this list should not exceed
     *                  {@link #MAX_BULK_OPERATIONS_SIZE}.
     * @throws IllegalArgumentException if the list size exceeds {@link #MAX_BULK_OPERATIONS_SIZE}.
     * @return A list of {@link GenericDocument} matching the file IDs.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public List<GenericDocument> getDocumentsByFileIds(@NonNull List<Long> fileIds)
            throws Exception {
        if (fileIds.size() > MAX_BULK_OPERATIONS_SIZE) {
            throw new IllegalArgumentException("File ids list size exceeds the limit of "
                    + MAX_BULK_OPERATIONS_SIZE);
        }

        final long startTimeMillis = SystemClock.elapsedRealtime();
        ensureAppSearchDbConnected();
        sReadWriteLock.readLock().lock();
        try {
            Trace.beginSection("AppSearchDbManager.getDocumentsByFileIds");
            if (fileIds.isEmpty()) {
                return new ArrayList<>();
            }

            List<GenericDocument> results = new ArrayList<>();
            String query = fileIds.stream()
                    .map(fileId -> MediaItem.PROPERTY_FILE_ID + " == " + fileId)
                    .collect(Collectors.joining(" OR "));

            SearchSpec searchSpec = new SearchSpec.Builder()
                    .addFilterNamespaces(NAMESPACE)
                    .setNumericSearchEnabled(true)
                    .setListFilterQueryLanguageEnabled(true)
                    .addFilterSchemas(MediaItem.SCHEMA_TYPE)
                    .build();

            try (SearchResults searchResults = searchDocuments(query, searchSpec)) {
                List<SearchResult> page = searchResults.getNextPageAsync().get();
                while (page != null && !page.isEmpty()) {
                    results.addAll(page.stream().map(SearchResult::getGenericDocument)
                            .collect(Collectors.toList()));
                    page = searchResults.getNextPageAsync().get();
                }
            }
            return results;
        } finally {
            Trace.endSection();
            sReadWriteLock.readLock().unlock();
            Log.d(TAG, "getDocumentsByFileIds() took " + (SystemClock.elapsedRealtime()
                    - startTimeMillis) + " ms");
        }
    }

    /**
     * Deletes documents by their file ids in a thread-safe manner.
     * <p>
     * This method acquires a lock to ensure deletions do not conflict with update operations.
     *
     * @param fileIds The string IDs of the documents to delete. The size of this list should not
     *                exceed {@link #MAX_BULK_OPERATIONS_SIZE}.
     * @throws IllegalArgumentException if the list size exceeds {@link #MAX_BULK_OPERATIONS_SIZE}.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void deleteDocumentsByFileIds(@NonNull List<Long> fileIds)
            throws Exception {
        if (fileIds.size() > MAX_BULK_OPERATIONS_SIZE) {
            throw new IllegalArgumentException("File ID list size exceeds the limit of "
                    + MAX_BULK_OPERATIONS_SIZE);
        }
        final long startTimeMillis = SystemClock.elapsedRealtime();
        ensureAppSearchDbConnected();
        sReadWriteLock.writeLock().lock();
        try {
            Trace.beginSection("AppSearchDbManager.deleteDocumentsByFileIds");
            if (fileIds.isEmpty()) {
                return;
            }

            List<String> idsToDelete = fileIds.stream().map(String::valueOf)
                    .collect(Collectors.toList());

            RemoveByDocumentIdRequest removeRequest =
                    new RemoveByDocumentIdRequest.Builder(NAMESPACE).addIds(idsToDelete).build();

            AppSearchBatchResult<String, Void> result =
                    mAppSearchSession.removeAsync(removeRequest).get();

            Log.v(TAG, "Remove documents complete. Requested: " + idsToDelete.size()
                    + ", Success: " + result.getSuccesses().size()
                    + ", Failures: " + result.getFailures().size());
        } finally {
            sReadWriteLock.writeLock().unlock();
            Trace.endSection();
            Log.d(TAG, "deleteDocumentsByFileIds() took " + (SystemClock.elapsedRealtime()
                    - startTimeMillis) + " ms");
        }
    }

    /**
     * Deletes documents from AppSearch that match the given {@code query} string, additionally
     * filtered by the criteria in the {@link SearchSpec} in a thread-safe manner.
     *
     * @param query      The query string.
     * @param searchSpec The specification for the search.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void deleteDocuments(String query, SearchSpec searchSpec) throws Exception {
        final long startTimeMillis = SystemClock.elapsedRealtime();
        ensureAppSearchDbConnected();
        sReadWriteLock.writeLock().lock();
        try {
            Trace.beginSection("AppSearchDbManager.deleteDocuments");
            mAppSearchSession.removeAsync(query, searchSpec).get();
        } finally {
            sReadWriteLock.writeLock().unlock();
            Trace.endSection();
            Log.d(TAG, "deleteDocuments() took " + (SystemClock.elapsedRealtime()
                    - startTimeMillis) + " ms");
        }
    }

    /**
     * Searches across documents in appsearch based on {@param searchspec}.
     * This is a read-only operation.
     *
     * @param query      The query string.
     * @param searchSpec The specification for the search.
     * @return A {@link SearchResults} object to iterate through results.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public SearchResults searchDocuments(String query, SearchSpec searchSpec) {
        final long startTimeMillis = SystemClock.elapsedRealtime();
        ensureAppSearchDbConnected();
        sReadWriteLock.readLock().lock();
        try {
            Trace.beginSection("AppSearchDbManager.searchDocuments");
            return mAppSearchSession.search(query, searchSpec);
        } catch (Exception e) {
            Log.e(TAG, "searchDocuments() failed for query " + query, e);
            throw new RuntimeException(e);
        } finally {
            sReadWriteLock.readLock().unlock();
            Trace.endSection();
            Log.d(TAG, "searchDocuments() took " + (SystemClock.elapsedRealtime()
                    - startTimeMillis) + " ms");
        }
    }

    /**
     * Returns the number of alive documents in the AppSearch index.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public int getTotalDocumentsCount() throws Exception {
        ensureAppSearchDbConnected();
        sReadWriteLock.readLock().lock();
        try {
            return mAppSearchSession.getStorageInfoAsync().get().getAliveDocumentsCount();
        } finally {
            sReadWriteLock.readLock().unlock();
        }
    }


    /**
     * Retrieves all file IDs currently indexed in the AppSearch database.
     * <p>
     * This method uses a projection to strictly return only the file ID field,
     * avoiding the overhead of loading embeddings or metadata.
     *
     * @return A set of all file IDs found in the namespace.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public Set<Long> getAllFileIds() throws Exception {
        final long startTimeMillis = SystemClock.elapsedRealtime();
        ensureAppSearchDbConnected();

        Set<Long> allFileIds = new HashSet<>();

        SearchSpec searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(NAMESPACE)
                .addFilterSchemas(MediaItem.SCHEMA_TYPE)
                .addProjection(MediaItem.SCHEMA_TYPE, List.of(MediaItem.PROPERTY_FILE_ID))
                .setResultCountPerPage(MAX_BULK_OPERATIONS_SIZE)
                .build();

        // Execute an empty query to match all documents
        sReadWriteLock.readLock().lock();
        try (SearchResults searchResults = mAppSearchSession.search("", searchSpec)) {
            List<SearchResult> page = searchResults.getNextPageAsync().get();
            while (page != null && !page.isEmpty()) {
                for (SearchResult result : page) {
                    GenericDocument doc = result.getGenericDocument();
                    allFileIds.add(doc.getPropertyLong(MediaItem.PROPERTY_FILE_ID));
                }
                page = searchResults.getNextPageAsync().get();
            }
        } catch (Exception e) {
            Log.e(TAG, "getAllFileIds() failed", e);
            throw e;
        } finally {
            sReadWriteLock.readLock().unlock();
            Log.d(TAG, "getAllFileIds() took " + (SystemClock.elapsedRealtime() - startTimeMillis)
                    + " ms, found " + allFileIds.size() + " items");
        }

        return allFileIds;
    }

    private void ensureAppSearchDbConnected() {
        if (mAppSearchSession == null) {
            throw new IllegalStateException("AppSearch session is not initialized.");
        }
    }

    /**
     * Converts a {@link GenericDocument} to a {@link MediaItem}.
     *
     * @param doc The {@link GenericDocument} to convert.
     * @return The converted {@link MediaItem}, or {@code null} if the input is null.
     */
    @Nullable
    private MediaItem convertGenericDocumentToMediaItem(@Nullable GenericDocument doc) {
        if (doc == null) {
            return null;
        }

        MediaItem item = new MediaItem(doc.getPropertyLong(MediaItem.PROPERTY_FILE_ID),
                doc.getPropertyLong(MediaItem.PROPERTY_MEDIA_TYPE),
                doc.getPropertyLong(MediaItem.PROPERTY_DATE_TAKEN),
                doc.getPropertyString(MediaItem.PROPERTY_VOLUME_NAME));
        item.setDirty(doc.getPropertyLong(MediaItem.PROPERTY_DIRTY) == 1L);
        item.setMetadataExtracted(doc.getPropertyString(MediaItem.PROPERTY_METADATA_EXTRACTED));
        item.setLocationExtracted(doc.getPropertyString(MediaItem.PROPERTY_LOCATION_EXTRACTED));
        item.setLabelsExtracted(doc.getPropertyString(MediaItem.PROPERTY_LABELS_EXTRACTED));
        EmbeddingVector[] embeddings = doc.getPropertyEmbeddingArray(MediaItem.PROPERTY_EMBEDDINGS);
        if (embeddings != null) {
            item.setEmbeddings(Arrays.asList(embeddings));
        }
        return item;
    }

    /**
     * A data class to encapsulate the information needed for a single document update.
     */
    public static final class UpdateSpec {
        private final Map<String, Object> mPropertiesToUpdate;

        public UpdateSpec(@NonNull Map<String, Object> propertiesToUpdate) {
            this.mPropertiesToUpdate = Objects.requireNonNull(propertiesToUpdate);
        }

        @NonNull
        public Map<String, Object> getPropertiesToUpdate() {
            return mPropertiesToUpdate;
        }
    }

}
