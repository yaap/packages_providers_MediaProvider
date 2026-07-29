/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static android.provider.BaseColumns._ID;
import static android.provider.MediaStore.Images.ImageColumns.LATITUDE;
import static android.provider.MediaStore.Images.ImageColumns.LONGITUDE;
import static android.provider.MediaStore.MediaColumns.GENERATION_MODIFIED;

import static com.android.providers.media.MediaProvider.MEDIAPROVIDER_PREFS;
import static com.android.providers.media.localsearch.MediaProcessingStatus.FILE_ID_COLUMN;
import static com.android.providers.media.localsearch.MediaProcessingStatus.GEN_MODIFIED;
import static com.android.providers.media.localsearch.MediaProcessingStatus.LOCATION_LABEL_STATUS;
import static com.android.providers.media.localsearch.MediaProcessingStatus.MEDIA_PROCESSING_STATUS_TABLE;
import static com.android.providers.media.localsearch.MediaProcessingStatus.MEDIA_TYPE;
import static com.android.providers.media.localsearch.MediaProcessingStatus.RETRY_LIMIT;
import static com.android.providers.media.localsearch.MediaProcessingStatus.bulkUpdateLabelStatusAsSuccess;
import static com.android.providers.media.localsearch.MediaProcessingStatus.insertMetadataProcessedRowInStatusTable;
import static com.android.providers.media.localsearch.MediaProcessingStatus.updateLocationLabelStatus;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.OperationCanceledException;
import android.os.Trace;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.mediaprocessingservice.IMediaProcessingService;
import android.provider.mediaprocessingservice.MediaProcessingService;
import android.provider.mediaprocessingservice.MediaProcessingServiceWrapper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.appsearch.app.SearchResult;
import androidx.appsearch.app.SearchResults;
import androidx.appsearch.app.SearchSpec;

import com.android.providers.media.DatabaseHelper;
import com.android.providers.media.MediaBackgroundThread;
import com.android.providers.media.R;
import com.android.providers.media.appsearch.AppSearchDbManager;
import com.android.providers.media.appsearch.AppSearchDbManager.UpdateSpec;
import com.android.providers.media.appsearch.MediaItem;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.localsearch.MediaLocationResolver.LocationLabelInfo;
import com.android.providers.media.localsearch.MediaLocationResolver.LocationLabelsCallback;
import com.android.providers.media.localsearch.MediaLocationResolver.MediaLocationInfo;
import com.android.providers.media.localsearch.MetadataLabelResolver.MetadataInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Helper class that manages media processing for local search.
 * <p>
 * This class manages the connection to the {@link MediaProcessingService}, queries the
 * MediaProvider database for files requiring processing, and updates the AppSearch index
 * with the results (Metadata, Location labels, etc.).
 */
public class ProcessingHelper implements AutoCloseable {
    private static final String TAG = "MediaProcessingHelper";

    public static final String LAST_GEN_MODIFIED_WITH_MEDIA_LABEL =
            "last_gen_modified_with_media_label";
    public static final String LAST_GEN_MODIFIED_WITH_LOCATION_LABEL =
            "last_gen_modified_with_location";
    public static final String LAST_GEN_MODIFIED_WITH_METADATA_LABEL =
            "last_gen_modified_with_metadata";
    private static final String[] METADATA_PROJECTION =
            new String[]{FileColumns._ID, FileColumns.DISPLAY_NAME, FileColumns.RELATIVE_PATH,
                    FileColumns.MEDIA_TYPE, FileColumns.MIME_TYPE, FileColumns.SPECIAL_FORMAT,
                    FileColumns.DATE_TAKEN, FileColumns.DATE_ADDED, FileColumns.DATE_MODIFIED,
                    FileColumns.IS_FAVORITE, FileColumns.IS_DOWNLOAD, FileColumns.ARTIST,
                    FileColumns.ALBUM, FileColumns.GENRE, FileColumns.GENERATION_MODIFIED,
                    FileColumns.VOLUME_NAME};

    private static final String[] LOCATION_PROJECTION =
            new String[]{FileColumns._ID, LATITUDE, LONGITUDE, FileColumns.GENERATION_MODIFIED};

    private static final int SERVICE_CONNECTION_TIMEOUT_SECONDS = 5;
    private static final int GET_PROCESSING_REQUESTED_TIMEOUT_SECONDS = 1;
    private static final int GET_PROCESSING_LIMIT_TIMEOUT_SECONDS = 1;
    private static final int LOCATION_LABEL_PROCESSING_TIMEOUT_MINUTES = 1;
    private static final int MAX_BATCH_SIZE_FOR_METADATA_PROCESSING = 500;
    private static final int DEFAULT_METADATA_LABEL_PROCESSING_LIMIT = 100;
    private static final int MAX_BATCH_SIZE_FOR_LOCATION_PROCESSING = 100;
    private static final int DEFAULT_LOCATION_LABEL_PROCESSING_LIMIT = 50;
    private static final int MAX_BATCH_SIZE_FOR_MEDIA_LABEL_PROCESSING = 50;
    public static final int DEFAULT_MEDIA_LABEL_PROCESSING_LIMIT = 10;
    private static final int DELETE_STALE_ROWS_FROM_APPSEARCH_LIMIT = 500;
    private static final int RETRY_LOCATION_BATCH_MULTIPLIER = 2;
    /** The ranking expression to sort by date taken. */
    public static final String EXPR_RANKING_DATE_TAKEN =
            "maxOrDefault(getScorableProperty(\"" + MediaItem.SCHEMA_TYPE + "\", \""
                    + MediaItem.PROPERTY_DATE_TAKEN + "\"), 0.0)";


    final Map<Integer, Integer> mProcessingRequestedPerMediaType;
    final DatabaseHelper mExternalDatabase;
    final MediaLocationResolver mLocationResolver;
    final MetadataLabelResolver mMetadataLabelResolver;
    final AppSearchDbManager mAppSearchDbManager;
    SharedPreferences mPrefs;
    private final Context mContext;
    private MediaProcessingServiceWrapper mMediaProcessingService;

    /**
     * MediaProcessingService implementation package.
     */
    private final Optional<String> mMediaProcessingServicePackage;

    /**
     * Count down latch to process delay in connection to MediaProcessingService.
     */
    private CountDownLatch mCountDownLatchForProcessingServiceConnection = new CountDownLatch(1);

    private final CancellationSignal mCancellationSignal = new CancellationSignal();

    public ProcessingHelper(Context context, DatabaseHelper helper) throws Exception {
        this(context, helper, MediaBackgroundThread.getExecutor());
    }

    @SuppressWarnings("NewApi")
    ProcessingHelper(Context context, DatabaseHelper helper, Executor executor) throws Exception {
        if (!Flags.enableMediaProcessing()) {
            throw new IllegalStateException("Media processing feature flag is disabled");
        }

        this.mContext = context;
        mMediaProcessingServicePackage = getMediaProcessingServicePackage(context);
        mProcessingRequestedPerMediaType = getProcessingRequestedPerMediaType();
        mExternalDatabase = helper;
        mLocationResolver = MediaLocationResolver.getMediaLocationResolver(context, executor)
                .orElse(null);
        mMetadataLabelResolver = new MetadataLabelResolver(context);
        mAppSearchDbManager = new AppSearchDbManager(context);
        mPrefs = context.getSharedPreferences(MEDIAPROVIDER_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Returns whether active network connection is available on this device
     */
    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return false;
        }

        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }

        NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
        if (capabilities == null) {
            return false;
        }

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private Optional<String> getMediaProcessingServicePackage(Context context) {
        try {
            String packageName = context.getResources().getString(
                    R.string.config_default_media_processing_service_package);
            if (packageName == null || packageName.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(packageName);
        } catch (Resources.NotFoundException e) {
            return Optional.empty();
        }
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder iBinder) {
            Log.i(TAG, "MediaProcessingService connected: " + name);
            IMediaProcessingService service = IMediaProcessingService.Stub.asInterface(iBinder);
            mMediaProcessingService = new MediaProcessingServiceWrapper(service);
            mCountDownLatchForProcessingServiceConnection.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "MediaProcessingService disconnected: " + name);
            if (mMediaProcessingService != null) {
                mMediaProcessingService.shutdown();
            }
            mMediaProcessingService = null;
            mCountDownLatchForProcessingServiceConnection = new CountDownLatch(1);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.e(TAG, "MediaProcessingService binding died: " + name);
            mContext.unbindService(this);
            if (mMediaProcessingService != null) {
                mMediaProcessingService.shutdown();
            }
            mMediaProcessingService = null;
            mCountDownLatchForProcessingServiceConnection = new CountDownLatch(1);
        }
    };

    private synchronized void connectMediaProcessingService() {
        Trace.beginSection("MediaProcessing.connectMediaProcessingService");
        try {
            if (!Flags.enableMediaProcessingService()) {
                return;
            }

            if (mMediaProcessingServicePackage.isEmpty()) {
                Log.v(TAG, "No implementing package listed for MediaProcessingService");
                return;
            }

            if (mMediaProcessingService != null) {
                Log.i(TAG, "MediaProcessingService already connected");
                return;
            }

            Intent intent = new Intent(MediaProcessingService.SERVICE_INTERFACE);
            ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent,
                    PackageManager.MATCH_ALL);
            if (resolveInfo == null || resolveInfo.serviceInfo == null
                    || resolveInfo.serviceInfo.packageName == null
                    || !mMediaProcessingServicePackage.get()
                    .equalsIgnoreCase(resolveInfo.serviceInfo.packageName)
                    || resolveInfo.serviceInfo.permission == null
                    || !resolveInfo.serviceInfo.permission.equalsIgnoreCase(
                    MediaProcessingService.BIND_MEDIA_PROCESSING_SERVICE_PERMISSION)) {
                Log.v(TAG, "No valid package found for MediaProcessingService");
                return;
            }

            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            intent.setComponent(new ComponentName(serviceInfo.packageName, serviceInfo.name));
            mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
            mCountDownLatchForProcessingServiceConnection.await(
                    SERVICE_CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "Exception in connecting MediaProcessingService", e);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Checks if the AppSearch index size exceeds the limit and deletes oldest documents if needed.
     * <p>
     * This ensures the index stays within the defined {@link
     * AppSearchDbManager#MAX_DOCUMENT_COUNT}.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void enforceAppSearchDocumentLimit(int limit) {
        if (mCancellationSignal.isCanceled()) {
            return;
        }

        try {
            int totalDocuments = mAppSearchDbManager.getTotalDocumentsCount();
            if (totalDocuments <= limit) {
                return;
            }
            int numDocsToDelete = totalDocuments - limit;
            Log.i(TAG, "Deleting " + numDocsToDelete + " oldest documents.");

            List<Long> idsToDelete = getIdsOfOldDocumentsToDelete(numDocsToDelete);
            List<Long> batch = new ArrayList<>();
            int deletedCount = 0;
            for (Long id : idsToDelete) {
                batch.add(id);
                if (batch.size() == AppSearchDbManager.MAX_BULK_OPERATIONS_SIZE) {
                    if (mCancellationSignal.isCanceled()) {
                        break;
                    }
                    deletedCount += deleteDocumentsByIds(batch);
                }
            }

            if (!batch.isEmpty() && !mCancellationSignal.isCanceled()) {
                deletedCount += deleteDocumentsByIds(batch);
            }
            Log.i(TAG, "Deleted " + deletedCount + " documents.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to enforce AppSearch document limit", e);
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private int deleteDocumentsByIds(List<Long> batch) throws Exception {
        mAppSearchDbManager.deleteDocumentsByFileIds(batch);
        MediaProcessingStatus.deleteMediaIdsFromStatusTable(mExternalDatabase, batch);
        int deletedCount = batch.size();
        batch.clear();
        return deletedCount;
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private List<Long> getIdsOfOldDocumentsToDelete(int numDocsToDelete) throws Exception {
        List<Long> idsToDelete = new ArrayList<>();

        SearchSpec searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(AppSearchDbManager.NAMESPACE)
                .addFilterSchemas(MediaItem.SCHEMA_TYPE)
                .setRankingStrategy(EXPR_RANKING_DATE_TAKEN)
                .setOrder(SearchSpec.ORDER_ASCENDING)
                .addProjection(MediaItem.SCHEMA_TYPE, List.of(MediaItem.PROPERTY_FILE_ID))
                .setResultCountPerPage(AppSearchDbManager.MAX_BULK_OPERATIONS_SIZE)
                .setScorablePropertyRankingEnabled(true)
                .build();

        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments(
                /* query */ "", searchSpec)) {
            while (idsToDelete.size() < numDocsToDelete) {
                List<SearchResult> page = searchResults.getNextPageAsync().get();
                if (page == null || page.isEmpty()) {
                    break;
                }

                for (SearchResult result : page) {
                    idsToDelete.add(result.getGenericDocument().getPropertyLong(
                            MediaItem.PROPERTY_FILE_ID));
                    if (idsToDelete.size() == numDocsToDelete) {
                        break;
                    }
                }
            }
        }

        return idsToDelete;
    }

    @VisibleForTesting
    public MediaProcessingServiceWrapper getMediaProcessingService() {
        return mMediaProcessingService;
    }

    /**
     * Gets the map of processing types requested per media type from MediaProcessingService.
     */
    public Map<Integer, Integer> getProcessingRequestedPerMediaType() {
        if (mProcessingRequestedPerMediaType != null) {
            return mProcessingRequestedPerMediaType;
        }

        Trace.beginSection("MediaProcessing.getProcessingRequestedPerMediaType");
        try {
            if (mMediaProcessingServicePackage.isEmpty()) {
                return DefaultMediaLabelResolver.getProcessingRequestedPerMediaType();
            }

            if (mMediaProcessingService == null) {
                connectMediaProcessingService();
            }

            // Return empty if we are unable to connect to media processing service
            if (mMediaProcessingService == null) {
                return new HashMap<>();
            }

            return mMediaProcessingService.getProcessingRequestedPerMediaType(
                    /*serviceTimeoutInSeconds */ GET_PROCESSING_REQUESTED_TIMEOUT_SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "Error in fetching requested processing from MediaProcessingService", e);
            return new HashMap<>();
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Filters the configuration to find which media types require location processing.
     *
     * @return A list of media types that have the DEFAULT_LOCATION_PROCESSING flag set.
     */
    private List<String> getMediaTypesWithLocationRequested() {
        List<String> requestedMediaTypes = new ArrayList<>();
        for (int mediaType : mProcessingRequestedPerMediaType.keySet()) {
            if ((mProcessingRequestedPerMediaType.get(mediaType)
                    & MediaProcessingService.ProcessingType.DEFAULT_LOCATION_PROCESSING) != 0) {
                requestedMediaTypes.add(String.valueOf(mediaType));
            }
        }
        return requestedMediaTypes;
    }

    /**
     * Gets the map of processing types requested per media type from MediaProcessingService.
     */
    public int getProcessingLimitForMediaLabels() {
        Trace.beginSection("MediaProcessing.getProcessingLimitForMediaLabels");
        try {
            if (mMediaProcessingServicePackage.isEmpty()) {
                return DefaultMediaLabelResolver.getProcessingLimit(mContext);
            }

            if (mMediaProcessingService == null) {
                connectMediaProcessingService();
            }

            // Return 0 if we are unable to connect to media processing service
            if (mMediaProcessingService == null) {
                return 0;
            }

            int limitConfig = mMediaProcessingService.getProcessingLimit(
                    /*serviceTimeoutInSeconds */ GET_PROCESSING_LIMIT_TIMEOUT_SECONDS);
            if (limitConfig > MAX_BATCH_SIZE_FOR_MEDIA_LABEL_PROCESSING) {
                Log.w(TAG, "Batch size for location processing is too large. "
                        + "Expected: <=" + MAX_BATCH_SIZE_FOR_MEDIA_LABEL_PROCESSING
                        + " but found: " + limitConfig + ".");
                return MAX_BATCH_SIZE_FOR_MEDIA_LABEL_PROCESSING;
            }
            return limitConfig;
        } catch (Exception e) {
            Log.e(TAG, "Error in fetching requested processing from MediaProcessingService", e);
            return 0;
        } finally {
            Trace.endSection();
        }
    }

    private int getProcessingLimitForMetadataLabels() {
        try {
            int limitConfig = mContext.getResources().getInteger(
                    R.integer.config_default_metadata_processing_batch_size);
            if (limitConfig > MAX_BATCH_SIZE_FOR_METADATA_PROCESSING) {
                Log.w(TAG, "Batch size for metadata processing is too large. "
                        + "Expected: <=" + MAX_BATCH_SIZE_FOR_METADATA_PROCESSING
                        + " but found: " + limitConfig + ".");
                return MAX_BATCH_SIZE_FOR_METADATA_PROCESSING;
            }
            return limitConfig;
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Overlayable config for metadata processing batch size not found. Using"
                    + " default value " + DEFAULT_METADATA_LABEL_PROCESSING_LIMIT, e);
            return DEFAULT_METADATA_LABEL_PROCESSING_LIMIT;
        }
    }

    private int getProcessingLimitForLocationLabels() {
        try {
            int limitConfig = mContext.getResources().getInteger(
                    R.integer.config_default_location_processing_batch_size);
            if (limitConfig > MAX_BATCH_SIZE_FOR_LOCATION_PROCESSING) {
                Log.w(TAG, "Batch size for location processing is too large. "
                        + "Expected: <=" + MAX_BATCH_SIZE_FOR_LOCATION_PROCESSING
                        + " but found: " + limitConfig + ".");
                return MAX_BATCH_SIZE_FOR_LOCATION_PROCESSING;
            }
            return limitConfig;
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Overlayable config for location processing batch size not found. Using"
                    + " default value " + DEFAULT_LOCATION_LABEL_PROCESSING_LIMIT, e);
            return DEFAULT_LOCATION_LABEL_PROCESSING_LIMIT;
        }
    }

    private void updateRowTrackerInSharedPreferences(String key, long lastUpdatedRow) {
        SharedPreferences.Editor editor = mPrefs.edit();
        editor.putLong(key, lastUpdatedRow);
        editor.apply();
    }

    /**
     * Executes the {@link #processMetadataLabels()} method within and measures its execution
     * time using system trace markers.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void runProcessMetadataLabels() {
        Trace.beginSection("MediaProcessing.processMetadataLabels");
        try {
            processMetadataLabels();
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Processes a batch of media files to extract and store metadata labels.
     * <p>
     * This method performs the following steps:
     * <ol>
     * <li>Queries the {@code files} table for new items to be processed since the last run.</li>
     * <li>Generates metadata labels (e.g., from filename, path, date) for the batch.</li>
     * <li>Inserts the generated labels into the AppSearch database.</li>
     * <li>Inserts a tracking row into the {@code media_processing_status} table.</li>
     * <li>Updates the shared preferences tracker with the last processed {@code
     * generation_modified}.</li>
     * </ol>
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void processMetadataLabels() {
        if (mCancellationSignal.isCanceled()) {
            return;
        }

        int limit = getProcessingLimitForMetadataLabels();

        try {
            long lastProcessedGenModifiedForMetadataLabel = mExternalDatabase.runWithTransaction(
                    (db) -> {
                        long lastProcessedGenModifiedForMetadata = mPrefs.getLong(
                                LAST_GEN_MODIFIED_WITH_METADATA_LABEL, /*defaultValue*/ 0);

                        // 1. Determine the range of generation_modified IDs to process
                        long maxGenModifiedToProcessForMetadata =
                                findMaxGenerationModifiedToProcessForMetadata(db,
                                        lastProcessedGenModifiedForMetadata, limit);

                        if (maxGenModifiedToProcessForMetadata
                                == lastProcessedGenModifiedForMetadata) {
                            Log.v(TAG, "No new media files require metadata label processing "
                                    + "in this job run");
                            return maxGenModifiedToProcessForMetadata;
                        }

                        // 2. Query metadata from files table where
                        // genModified > lastProcessedGenModifiedForMetadata
                        // and genModified <= maxGenModifiedToProcessForMetadata
                        List<MetadataInfo> mediaInfos = new ArrayList<>();
                        Map<Long, MediaItem> fileIdToMediaItemMap = new HashMap<>();
                        Map<Long, Long> fileIdToGenModifiedMap = new HashMap<>();

                        fetchMetadataInfoForBatch(db, lastProcessedGenModifiedForMetadata,
                                maxGenModifiedToProcessForMetadata, mediaInfos,
                                fileIdToMediaItemMap, fileIdToGenModifiedMap);

                        if (mediaInfos.isEmpty()) {
                            // No rows found for metadata processing
                            return maxGenModifiedToProcessForMetadata;
                        }

                        // 3. Generate metadata labels for all files
                        Map<Long, String> fileIdToMetadataLabelMap =
                                mMetadataLabelResolver.generateMetadataLabels(mediaInfos);

                        List<MediaItem> mediaItemsToInsert = new ArrayList<>();
                        long updatedGenModified = lastProcessedGenModifiedForMetadata;

                        for (Long fileId : fileIdToMetadataLabelMap.keySet()) {
                            MediaItem item = fileIdToMediaItemMap.get(fileId);
                            item.setMetadataExtracted(fileIdToMetadataLabelMap.get(fileId));
                            mediaItemsToInsert.add(item);

                            // 4. Insert Row in media_processing_status table
                            insertMetadataProcessedRowInStatusTable(db, fileId, item.getMediaType(),
                                    fileIdToGenModifiedMap.get(fileId));

                            updatedGenModified = Math.max(updatedGenModified,
                                    fileIdToGenModifiedMap.get(fileId));
                        }

                        if (mCancellationSignal.isCanceled()) {
                            throw new CancellationException();
                        }

                        // 5. Insert documents in AppSearchDb
                        try {
                            mAppSearchDbManager.insertDocuments(mediaItemsToInsert);
                            Log.d(TAG, "Processed metadata labels for " + mediaItemsToInsert.size()
                                    + " files");
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to insert documents in AppSearchDb", e);
                            throw new RuntimeException(e);
                        }

                        return updatedGenModified;
                    });

            // Store the genModified of the last updated media file
            updateRowTrackerInSharedPreferences(LAST_GEN_MODIFIED_WITH_METADATA_LABEL,
                    lastProcessedGenModifiedForMetadataLabel);
        } catch (CancellationException | OperationCanceledException e) {
            Log.v(TAG, "Metadata processing cancelled");
        }
    }

    /**
     * Executes the {@link #processLocationLabels()} method within and measures its execution
     * time using system trace markers.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void runProcessLocationLabels() throws InterruptedException {
        Trace.beginSection("MediaProcessing.processLocationLabels");
        try {
            processLocationLabels();
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Processes a batch of media files to resolve and store location labels (geocoding).
     * <p>
     * This method identifies media files from the {@code media_processing_status} table
     * that have not yet completed location processing. It then queries the {@code files} table
     * for location metadata, attempts to reverse-geocode using Geocoder APIs, and updates the
     * AppSearch database.
     * <p>
     * <b>Note:</b> This operation is asynchronous; results are handled via a callback that
     * updates the database transactionally.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void processLocationLabels() throws InterruptedException {
        if (mCancellationSignal.isCanceled()) {
            return;
        }

        if (mLocationResolver == null) {
            Log.w(TAG, "Unable to create MediaLocationResolver");
            return;
        }

        int limit = getProcessingLimitForLocationLabels();
        String[] requestedMediaTypesForLocation =
                getMediaTypesWithLocationRequested().toArray(new String[0]);

        if (requestedMediaTypesForLocation.length == 0) {
            Log.v(TAG, "No media types require location processing");
            return;
        }

        long lastProcessedGenModifiedForLocationLabel =
                mPrefs.getLong(LAST_GEN_MODIFIED_WITH_LOCATION_LABEL, /* defaultValue */ 0);

        List<MediaLocationInfo> mediaInfos = new ArrayList<>();

        try {
            mExternalDatabase.runWithTransaction((db) -> {
                long maxGenModifiedToProcessForLocation =
                        findMaxGenerationModifiedToProcessForLocation(db,
                                lastProcessedGenModifiedForLocationLabel, limit,
                                requestedMediaTypesForLocation);

                List<Long> fileIdsToProcess = getFileIdsToProcessLocation(db,
                        lastProcessedGenModifiedForLocationLabel,
                        maxGenModifiedToProcessForLocation, requestedMediaTypesForLocation);

                if (fileIdsToProcess.isEmpty()) {
                    Log.v(TAG, "No media files with non-null location metadata in this job run");
                    return null;
                }

                updateStatusForNullLocation(db, fileIdsToProcess);

                fetchLocationInfoForBatch(db, fileIdsToProcess, mediaInfos);

                if (mediaInfos.isEmpty()) {
                    Log.d(TAG, "No media files with non-null location metadata in this job run");
                    updateRowTrackerInSharedPreferences(LAST_GEN_MODIFIED_WITH_LOCATION_LABEL,
                            maxGenModifiedToProcessForLocation);
                }

                return null;
            });
        } catch (CancellationException | OperationCanceledException e) {
            Log.v(TAG, "Location processing cancelled");
            return;
        }

        // 3. Generate and store location labels for all files
        if (mediaInfos.isEmpty()) {
            Log.d(TAG, "No media files with non-null location metadata in this job run");
            return;
        }

        mediaInfos.sort(Comparator.comparingLong(mediaInfo -> mediaInfo.genModified));

        int startIndex = 0;
        while (mediaInfos.size() > startIndex) {
            int toIndex = Math.min(startIndex + limit, mediaInfos.size());

            if (mCancellationSignal.isCanceled()) {
                return;
            }

            CountDownLatch latch = new CountDownLatch(1);
            mLocationResolver.generateLocationLabels(mediaInfos.subList(startIndex, toIndex),
                    (result) -> {
                        try {
                            mLocationLabelsCallback.onLabelsResult(result);
                        } finally {
                            latch.countDown();
                        }
                    });
            startIndex += limit;

            // Block the worker thread until the async callback completes work, or the process
            // times out
            if (!latch.await(LOCATION_LABEL_PROCESSING_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                Log.e(TAG, "Timed out waiting for location processing batch to complete");
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private final LocationLabelsCallback mLocationLabelsCallback = new LocationLabelsCallback() {
        @Override
        public void onLabelsResult(
                @NonNull Map<Long, LocationLabelInfo> fileIdToLocationLabelInfoMap) {
            if (mCancellationSignal.isCanceled()) {
                return;
            }

            if (fileIdToLocationLabelInfoMap.isEmpty()) {
                return;
            }

            try {
                if (mExternalDatabase == null) {
                    Log.e(TAG, "DatabaseHelper instance is null");
                    return;
                }

                long lastProcessedGenModifiedForLocation = mExternalDatabase.runWithTransaction(
                        (db) -> {
                            long updatedGenModified = 0;
                            Map<Long, UpdateSpec> fileIdToUpdateSpecMap = new HashMap<>();

                            for (Long fileId : fileIdToLocationLabelInfoMap.keySet()) {
                                LocationLabelInfo locationLabel =
                                        fileIdToLocationLabelInfoMap.get(fileId);
                                if (locationLabel.label.isEmpty()) {
                                    updateLocationLabelStatus(db, fileId, /* isSuccess */ false);
                                    continue;
                                }

                                Map<String, Object> propertyNameToValueMap = new HashMap<>();
                                propertyNameToValueMap.put(MediaItem.PROPERTY_LOCATION_EXTRACTED,
                                        locationLabel.label.get());
                                UpdateSpec updateSpec = new UpdateSpec(propertyNameToValueMap);

                                if (updateLocationLabelStatus(db, fileId, /* isSuccess */ true)) {
                                    fileIdToUpdateSpecMap.put(fileId, updateSpec);
                                    updatedGenModified = Math.max(updatedGenModified,
                                            locationLabel.genModified);
                                }
                            }

                            if (mCancellationSignal.isCanceled()) {
                                throw new CancellationException();
                            }

                            try {
                                if (mAppSearchDbManager == null) {
                                    Log.v(TAG, "AppSearchDbManager instance is null");
                                    return 0L;
                                }

                                mAppSearchDbManager.updateDocuments(fileIdToUpdateSpecMap);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to update documents in AppSearchDb", e);
                                throw new RuntimeException(e);
                            }

                            Log.v(TAG, "Processed location labels for "
                                    + fileIdToUpdateSpecMap.size() + " files");
                            return updatedGenModified;
                        });

                updateRowTrackerInSharedPreferences(LAST_GEN_MODIFIED_WITH_LOCATION_LABEL,
                        lastProcessedGenModifiedForLocation);
            } catch (CancellationException | OperationCanceledException e) {
                Log.v(TAG, "Location processing cancelled");
            }
        }
    };

    /**
     * Determines the maximum generation_modified to process in the current batch.
     *
     * @param db      The database instance.
     * @param lastGen The last successfully processed generation_modified.
     * @param limit   The batch size limit.
     * @return The maximum generation_modified value to include in this batch.
     */
    private long findMaxGenerationModifiedToProcessForMetadata(SQLiteDatabase db, long lastGen,
            int limit) {
        String selection = FileColumns.VOLUME_NAME + " = ?"
                + " AND " + FileColumns.GENERATION_MODIFIED + " > ?"
                + " AND " + FileColumns.IS_PENDING + " = 0"
                + " AND " + FileColumns.IS_TRASHED + " = 0"
                + " AND " + FileColumns.MIME_TYPE + " IS NOT NULL";

        String[] selectionArgs = new String[]{
                MediaStore.VOLUME_EXTERNAL_PRIMARY,
                String.valueOf(lastGen)
        };

        long maxGen = lastGen;
        try (Cursor c = db.query(/*distinct*/ true, MediaStore.Files.TABLE,
                new String[]{GENERATION_MODIFIED}, selection, selectionArgs, /*groupBy*/ null,
                /*having*/ null, /*orderBy*/ GENERATION_MODIFIED, /*limit*/ String.valueOf(limit),
                mCancellationSignal)) {
            if (c.moveToLast()) {
                maxGen = c.getLong(0);
            }
        }

        return maxGen;
    }

    /**
     * Queries file metadata for the current batch range.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void fetchMetadataInfoForBatch(SQLiteDatabase db, long lastGenModified,
            long maxGenModifiedToProcess, List<MetadataInfo> mediaInfos,
            Map<Long, MediaItem> fileIdToMediaItemMap, Map<Long, Long> fileIdToGenModifiedMap) {
        String selection = FileColumns.VOLUME_NAME + " = ?"
                + " AND " + FileColumns.GENERATION_MODIFIED + " > ?"
                + " AND " + FileColumns.GENERATION_MODIFIED + " <= ?"
                + " AND " + FileColumns.IS_PENDING + " = 0"
                + " AND " + FileColumns.IS_TRASHED + " = 0"
                + " AND " + FileColumns.MIME_TYPE + " IS NOT NULL";

        String[] selectionArgs = new String[]{MediaStore.VOLUME_EXTERNAL_PRIMARY,
                String.valueOf(lastGenModified), String.valueOf(maxGenModifiedToProcess)};

        try (Cursor c = db.query(/*distinct*/ true, MediaStore.Files.TABLE,
                /*projection*/ METADATA_PROJECTION, selection, selectionArgs, /*groupBy*/ null,
                /*having*/ null, /*orderBy*/ FileColumns.GENERATION_MODIFIED, /*limit*/ null,
                mCancellationSignal)) {
            while (c.moveToNext()) {
                MetadataInfo info = new MetadataInfo.Builder().setDataFromCursor(c).build();
                mediaInfos.add(info);

                long dateTaken = findDateTakenForMediaItem(c);

                MediaItem item = new MediaItem(c.getLong(c.getColumnIndexOrThrow(FileColumns._ID)),
                        c.getInt(c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)), dateTaken,
                        c.getString(c.getColumnIndexOrThrow(FileColumns.VOLUME_NAME)));
                fileIdToMediaItemMap.put(info.id, item);

                fileIdToGenModifiedMap.put(info.id,
                        c.getLong(c.getColumnIndexOrThrow(FileColumns.GENERATION_MODIFIED)));
            }
        }
    }

    private long findDateTakenForMediaItem(Cursor c) {
        long dateTaken = c.getLong(c.getColumnIndexOrThrow(FileColumns.DATE_TAKEN));
        if (dateTaken <= 0) {
            // DATE_ADDED and DATE_MODIFIED properties are recorded in seconds.
            // Converting to millis for consistency with DATE_TAKEN
            dateTaken = c.getLong(c.getColumnIndexOrThrow(FileColumns.DATE_ADDED)) * 1000;
            if (dateTaken <= 0) {
                dateTaken = c.getLong(c.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)) * 1000;
            }
        }

        return dateTaken;
    }

    /**
     * Finds the maximum generation modified for the location processing batch.
     */
    private long findMaxGenerationModifiedToProcessForLocation(SQLiteDatabase db,
            long lastGenModified, int limit, String[] requestedMediaTypes) {
        String selectMediaTypes = TextUtils.join(",", requestedMediaTypes);
        String selection = GEN_MODIFIED + " > ?"
                + " AND " + MediaProcessingStatus.LOCATION_LABEL_STATUS + " < " + RETRY_LIMIT
                + " AND " + MEDIA_TYPE + " IN (" + selectMediaTypes + ")";

        String[] selectionArgs = new String[]{String.valueOf(lastGenModified)};

        long maxGenModifiedToProcess = lastGenModified;
        try (Cursor c = db.query(/*distinct*/ true, MEDIA_PROCESSING_STATUS_TABLE,
                new String[]{GEN_MODIFIED}, selection, selectionArgs, /*groupBy*/ null,
                /*having*/ null, /*orderBy*/ GEN_MODIFIED, /*limit*/ String.valueOf(limit),
                mCancellationSignal)) {
            if (c.moveToLast()) {
                maxGenModifiedToProcess = c.getLong(0);
            }
        }

        return maxGenModifiedToProcess;
    }

    /**
     * Retrieves the list of File IDs that require location processing in this batch.
     */
    private List<Long> getFileIdsToProcessLocation(SQLiteDatabase db, long lastGenModified,
            long maxGenModifiedToProcess, String[] requestedMediaTypes) {
        String selectMediaTypes = TextUtils.join(",", requestedMediaTypes);
        String selection = GEN_MODIFIED + " > ? AND " + GEN_MODIFIED + " <= ? "
                + " AND " + MediaProcessingStatus.LOCATION_LABEL_STATUS + " < " + RETRY_LIMIT
                + " AND " + MEDIA_TYPE + " IN (" + selectMediaTypes + ")";

        String[] selectionArgs = new String[]{String.valueOf(lastGenModified), String.valueOf(
                maxGenModifiedToProcess)};

        List<Long> fileIdsToProcess = new ArrayList<>();
        try (Cursor c = db.query(/*distinct*/ true, MEDIA_PROCESSING_STATUS_TABLE,
                /*projection*/ new String[]{FILE_ID_COLUMN}, /*selection*/ selection,
                /*selectionArgs*/ selectionArgs, /*groupBy*/ null, /*having*/ null,
                /*orderBy*/ GEN_MODIFIED, /*limit*/ null, mCancellationSignal)) {
            while (c.moveToNext()) {
                fileIdsToProcess.add(c.getLong(0));
            }
        }
        return fileIdsToProcess;
    }

    /**
     * Marks files as processed if they are missing location metadata (Lat/Long is NULL).
     */
    private void updateStatusForNullLocation(SQLiteDatabase db, List<Long> fileIdsToProcess) {
        String listOfIds = "(" + TextUtils.join(",", fileIdsToProcess) + ")";

        String whereClause = FileColumns._ID + " IN " + listOfIds
                + " AND (" + LATITUDE + " IS NULL OR " + LONGITUDE + " IS NULL)";

        List<Long> fileIdsWithNullLocation = new ArrayList<>();

        // 2. Query files table for rows with null location metadata
        try (Cursor c = db.query(/*distinct*/ true, MediaStore.Files.TABLE,
                /*projection*/ new String[]{FileColumns._ID}, /*selection*/ whereClause,
                /*selectionArgs*/ null, /*groupBy*/ null, /*having*/ null, /*orderBy*/ null,
                /*limit*/ null, mCancellationSignal)) {
            while (c.moveToNext()) {
                fileIdsWithNullLocation.add(c.getLong(0));
            }
        }

        if (fileIdsWithNullLocation.isEmpty()) {
            return;
        }

        // 3. Mark location label status as COMPLETED
        int rowsAffected = bulkUpdateLabelStatusAsSuccess(db, fileIdsWithNullLocation,
                LOCATION_LABEL_STATUS);
        Log.v(TAG, "Updated location label status for " + rowsAffected
                + " files with null location metadata");
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void fetchLocationInfoForBatch(SQLiteDatabase db, List<Long> fileIdsToProcess,
            List<MediaLocationInfo> mediaInfos) {
        String listOfIds = "(" + TextUtils.join(",", fileIdsToProcess) + ")";

        String whereClause = FileColumns._ID + " IN " + listOfIds
                + " AND " + LATITUDE + " IS NOT NULL AND " + LONGITUDE + " IS NOT NULL";

        // 2. Query files table for location metadata required to generate location labels
        try (Cursor c = db.query(/*distinct*/ true, MediaStore.Files.TABLE,
                LOCATION_PROJECTION, /*selection*/ whereClause, /*selectionArgs*/ null,
                /*groupBy*/ null, /*having*/ null, /*orderBy*/ null, /*limit*/ null,
                mCancellationSignal)) {
            while (c.moveToNext()) {
                MediaLocationInfo info = new MediaLocationInfo(
                        c.getLong(c.getColumnIndexOrThrow(FileColumns._ID)),
                        c.getLong(c.getColumnIndexOrThrow(FileColumns.GENERATION_MODIFIED)),
                        c.getDouble(c.getColumnIndexOrThrow(LATITUDE)),
                        c.getDouble(c.getColumnIndexOrThrow(LONGITUDE)));
                mediaInfos.add(info);
            }
        }
    }

    /**
     * Executes the {@link #retryLocationLabels()} method within and measures its execution
     * time using system trace markers.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void runRetryLocationLabels() throws InterruptedException {
        Trace.beginSection("MediaProcessing.retryLocationLabels");
        try {
            retryLocationLabels();
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Attempts to re-process media files for which location label generation previously failed or
     * was not completed.
     *
     * <p>This method queries the {@code media_processing_status} table for files that meet the
     * following criteria:
     * <ul>
     *   <li>The media type is configured to require location processing.
     *   <li>The {@code generation_modified} is less than or equal to the last value fully
     *       processed by the main location processing job (as tracked in SharedPreferences).
     *   <li>The {@code location_label_status} indicates the file is not yet completed and has not
     *       exceeded the maximum retry limit ({@link MediaProcessingStatus#RETRY_LIMIT}).
     * </ul>
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void retryLocationLabels() throws InterruptedException {
        if (mCancellationSignal.isCanceled()) {
            return;
        }

        // Allow processing up to 2 batches at a time
        int limit = getProcessingLimitForLocationLabels() * RETRY_LOCATION_BATCH_MULTIPLIER;
        String[] requestedMediaTypes = getMediaTypesWithLocationRequested().toArray(new String[0]);

        if (requestedMediaTypes.length == 0) {
            Log.v(TAG, "No media types require location processing");
            return;
        }

        if (mLocationResolver == null) {
            Log.w(TAG, "Unable to create MediaLocationResolver");
            return;
        }

        long lastProcessedGenModified = mPrefs.getLong(LAST_GEN_MODIFIED_WITH_LOCATION_LABEL,
                /* defaultValue */ 0);

        List<MediaLocationInfo> mediaInfos = new ArrayList<>();

        try {
            mExternalDatabase.runWithTransaction((db) -> {
                List<Long> fileIdsToProcess = getFileIdsToRetryLocationProcessing(db,
                        lastProcessedGenModified, requestedMediaTypes, limit);

                if (fileIdsToProcess.isEmpty()) {
                    Log.v(TAG, "No media files require location label reprocessing in this "
                            + "job run");
                    return null;
                }

                fetchLocationInfoForBatch(db, fileIdsToProcess, mediaInfos);

                if (mediaInfos.isEmpty()) {
                    Log.d(TAG, "No media files with non-null location metadata in this job run");
                }

                return null;
            });
        } catch (CancellationException | OperationCanceledException e) {
            Log.v(TAG, "Location processing cancelled");
            return;
        }

        // 3. Generate and store location labels for all files
        if (mediaInfos.isEmpty()) {
            Log.d(TAG, "No media files with non-null location metadata in this job run");
            return;
        }

        int startIndex = 0;
        while (mediaInfos.size() > startIndex) {
            int toIndex = Math.min(startIndex + limit, mediaInfos.size());

            if (mCancellationSignal.isCanceled()) {
                return;
            }

            CountDownLatch latch = new CountDownLatch(1);
            mLocationResolver.generateLocationLabels(mediaInfos.subList(startIndex, toIndex),
                    (result) -> {
                        try {
                            mLocationLabelsCallback.onLabelsResult(result);
                        } finally {
                            latch.countDown();
                        }
                    });
            startIndex += limit;

            // Block the worker thread until the async callback completes work, or the process
            // times out
            if (!latch.await(LOCATION_LABEL_PROCESSING_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                Log.e(TAG, "Timed out waiting for location processing batch to complete");
            }
        }
    }

    /**
     * Retrieves the list of File IDs that require retrying of location processing in this batch.
     */
    private List<Long> getFileIdsToRetryLocationProcessing(SQLiteDatabase db,
            long lastProcessedGenModified, String[] requestedMediaTypes, int limit) {
        String selectMediaTypes = TextUtils.join(",", requestedMediaTypes);
        String selection = GEN_MODIFIED + " <= ? "
                + " AND " + MediaProcessingStatus.LOCATION_LABEL_STATUS + " < " + RETRY_LIMIT
                + " AND " + MEDIA_TYPE + " IN (" + selectMediaTypes + ")";
        String[] selectionArgs = new String[]{String.valueOf(lastProcessedGenModified)};


        List<Long> fileIdsToProcess = new ArrayList<>();
        try (Cursor c = db.query(/*distinct*/ true, MEDIA_PROCESSING_STATUS_TABLE,
                /*projection*/ new String[]{FILE_ID_COLUMN}, /*selection*/ selection,
                /*selectionArgs*/  selectionArgs, /*groupBy*/null, /*having*/ null,
                /*orderBy*/ GEN_MODIFIED, String.valueOf(limit), mCancellationSignal)) {
            while (c.moveToNext()) {
                fileIdsToProcess.add(c.getLong(0));
            }
        }
        return fileIdsToProcess;
    }

    /**
     * Cleans up the AppSearch database by removing documents that were deleted from files table but
     * still exist in AppSearch.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public void deleteStaleRowsFromAppSearch() {
        if (mCancellationSignal.isCanceled()) {
            return;
        }

        Trace.beginSection("MediaProcessing.deleteStaleRowsFromAppSearch");

        try {
            AppSearchDbManager appSearchDbManager = new AppSearchDbManager(mContext);
            if (appSearchDbManager == null) {
                Log.w(TAG, "Unable to create AppSearchDbManager");
                return;
            }

            Set<Long> fileIds = appSearchDbManager.getAllFileIds();
            if (fileIds.isEmpty()) {
                Log.v(TAG, "No files found in AppSearch to check for staleness.");
                return;
            }

            String selection = FileColumns.VOLUME_NAME + " = ?"
                    + " AND " + FileColumns.IS_PENDING + " = 0"
                    + " AND " + FileColumns.IS_TRASHED + " = 0"
                    + " AND " + FileColumns.MIME_TYPE + " IS NOT NULL";

            String[] selectionArgs = new String[]{MediaStore.VOLUME_EXTERNAL_PRIMARY};

            mExternalDatabase.runWithoutTransaction((db) -> {
                try (Cursor c = db.query(/*distinct*/ true, MediaStore.Files.TABLE,
                        new String[]{_ID}, selection, selectionArgs, /*groupBy*/ null,
                        /*having*/ null, /*orderBy*/ null, /*limit*/  null, mCancellationSignal)) {
                    while (c.moveToNext()) {
                        fileIds.remove(c.getLong(0));
                    }
                }
                return null;
            });

            if (!fileIds.isEmpty()) {
                List<Long> missingIds = new ArrayList<>(fileIds);

                for (int startIndex = 0; startIndex < missingIds.size();
                        startIndex += DELETE_STALE_ROWS_FROM_APPSEARCH_LIMIT) {
                    int endIndex = Math.min(startIndex + DELETE_STALE_ROWS_FROM_APPSEARCH_LIMIT,
                            missingIds.size());

                    appSearchDbManager.deleteDocumentsByFileIds(
                            missingIds.subList(startIndex, endIndex));
                }

                Log.i(TAG, "Cleaned " + missingIds.size() + " stale documents from AppSearch");
            }
        } catch (OperationCanceledException e) {
            Log.v(TAG, "AppSearch db cleanup cancelled");
        } catch (Exception e) {
            Log.e(TAG, "Failed to clean stale documents from AppSearch", e);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Signals all processing loops to stop work.
     */
    public void cancelOutstandingWork() {
        mCancellationSignal.cancel();
    }

    /**
     * Closes this resource, informs ongoing operations to stop and releases service connections.
     */
    @Override
    public void close() throws Exception {
        mCancellationSignal.cancel();

        if (mMediaProcessingService != null) {
            try {
                mContext.unbindService(mServiceConnection);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Failed to unbind MediaProcessingService", e);
            }
            mMediaProcessingService = null;
        }

        if (mAppSearchDbManager != null) {
            mAppSearchDbManager.disconnect();
        }
    }
}
