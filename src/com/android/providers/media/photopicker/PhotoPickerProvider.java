/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker;

import static android.provider.CloudMediaProviderContract.EXTRA_AUTHORITY;
import static android.provider.CloudMediaProviderContract.EXTRA_MEDIASTORE_THUMB;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_CONTROLLER;
import static android.provider.CloudMediaProviderContract.EXTRA_SURFACE_STATE_CALLBACK;
import static android.provider.CloudMediaProviderContract.METHOD_CREATE_SURFACE_CONTROLLER;

import static com.android.providers.media.photopicker.PickerSyncController.PAGE_SIZE;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.CloudMediaProvider;
import android.provider.CloudMediaProviderContract;
import android.provider.CloudMediaProviderContract.Capabilities;
import android.provider.ICloudMediaSurfaceController;
import android.provider.MediaStore;
import android.provider.SearchMediaResult;
import android.provider.SearchMediaResultPage;
import android.provider.SearchMediaService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.LocalCallingIdentity;
import com.android.providers.media.MediaApplication;
import com.android.providers.media.MediaProvider;
import com.android.providers.media.PickerUriResolver;
import com.android.providers.media.flags.Flags;
import com.android.providers.media.photopicker.data.CloudProviderQueryExtras;
import com.android.providers.media.photopicker.data.ExternalDbFacade;
import com.android.providers.media.util.MimeUtils;

import java.io.FileNotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Implements the {@link CloudMediaProvider} interface over the local items in the MediaProvider
 * database.
 */
public class PhotoPickerProvider extends CloudMediaProvider {
    private static final String TAG = PhotoPickerProvider.class.getSimpleName();
    private static final String PHOTOPICKER_SEARCH_ID_PREFIX = "photopicker_search_id_";
    private MediaProvider mMediaProvider;
    private ExternalDbFacade mDbFacade;
    private ConfigStore mConfigStore;
    private PhotoPickerLocalSearchManager mPhotoPickerLocalSearchManager;

    @Override
    public boolean onCreate() {
        mMediaProvider = getMediaProvider();
        mDbFacade = mMediaProvider.getExternalDbFacade();
        mConfigStore = MediaApplication.getConfigStore();
        mPhotoPickerLocalSearchManager = PhotoPickerLocalSearchManager.getInstance(getContext());
        return true;
    }

    @Override
    public void shutdown() {
        mPhotoPickerLocalSearchManager.stop();
    }

    @Override
    public Cursor onQueryMedia(@Nullable Bundle extras) {
        // TODO(b/190713331): Handle extra_page
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        return mDbFacade.queryMedia(queryExtras.getGeneration(), queryExtras.getAlbumId(),
                queryExtras.getMimeTypes(), queryExtras.getPageSize(), queryExtras.getPageToken(),
                CloudMediaProviderContract.SORT_ORDER_DESC_DATE_TAKEN);
    }

    @Override
    public Cursor onQueryDeletedMedia(@Nullable Bundle extras) {
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        return mDbFacade.queryDeletedMedia(queryExtras.getGeneration());
    }

    @Override
    public Cursor onQueryAlbums(@Nullable Bundle extras) {
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        return mDbFacade.queryAlbums(queryExtras.getMimeTypes(), mConfigStore);
    }

    @Override
    public AssetFileDescriptor onOpenPreview(@NonNull String mediaId, @NonNull Point size,
            @NonNull Bundle extras, @NonNull CancellationSignal signal)
            throws FileNotFoundException {
        final Bundle opts = new Bundle();
        opts.putParcelable(ContentResolver.EXTRA_SIZE, size);

        String mimeTypeFilter = null;
        if (extras.getBoolean(EXTRA_MEDIASTORE_THUMB)) {
            // This is a request for thumbnail, set "image/*" to get cached thumbnails from
            // MediaProvider.
            mimeTypeFilter = "image/*";
        }

        final LocalCallingIdentity token = mMediaProvider.clearLocalCallingIdentity();
        try {
            return mMediaProvider.openTypedAssetFile(fromMediaId(mediaId), mimeTypeFilter, opts);
        } finally {
            mMediaProvider.restoreLocalCallingIdentity(token);
        }
    }

    @Override
    public ParcelFileDescriptor onOpenMedia(@NonNull String mediaId,
            @NonNull Bundle extras, @NonNull CancellationSignal signal)
            throws FileNotFoundException {
        final LocalCallingIdentity token = mMediaProvider.clearLocalCallingIdentity();
        try {
            return mMediaProvider.openFile(fromMediaId(mediaId), "r");
        } finally {
            mMediaProvider.restoreLocalCallingIdentity(token);
        }
    }

    @Override
    public Bundle onGetMediaCollectionInfo(@Nullable Bundle extras) {
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);

        return mDbFacade.getMediaCollectionInfo(queryExtras.getGeneration());
    }

    /**
     * Returns an empty cursor because the local provider does not support search suggestions.
     *
     * <p>The Photo Picker UI may display suggestions from the Cloud Provider, but the local
     * content is currently only searchable via raw text queries. Therefore, this method always
     * returns an empty cursor to indicate no local suggestions are available.
     */
    //TODO: b/486889963 -  Add support for search suggestions
    @NonNull
    @Override
    public Cursor onQuerySearchSuggestions(@NonNull String prefixText, @NonNull Bundle extras,
            @Nullable CancellationSignal cancellationSignal) {
        return new MatrixCursor(CloudMediaProviderContract.SearchSuggestionColumns.ALL_PROJECTION);
    }

    /**
     * Searches for media in the local provider based on a text query.
     *
     * <p>This method queries the {@link SearchMediaService} to get search results.
     *
     * @param searchText The text to search for within media metadata (e.g., labels, location).
     * @param extras A {@link Bundle} containing search configuration:
     *               <ul>
     *                   <li>{@link CloudMediaProviderContract#EXTRA_PAGE_TOKEN}: The token for
     *                   fetching the next page of results.</li>
     *                   <li>{@link CloudMediaProviderContract#EXTRA_PAGE_SIZE}: The maximum
     *                   number of results to return.</li>
     *                   <li>{@link Intent#EXTRA_MIME_TYPES}: An array of MIME types to filter
     *                   the results (e.g., "image/*", "video/*").</li>
     *               </ul>
     * @param cancellationSignal A signal to cancel the operation in progress. If cancelled,
     *                           the underlying search is cancelled.
     * @return A {@link Cursor} containing the search results. The cursor containing the ID and
     *         uri of local media items. The cursor extras may contain
     *         {@link CloudMediaProviderContract#EXTRA_PAGE_TOKEN} if more results are available.
     */
    @NonNull
    @Override
    public Cursor onSearchMedia(@NonNull String searchText, @NonNull Bundle extras,
            @Nullable CancellationSignal cancellationSignal) {
        // unique identifier for this search request
        String searchId = PHOTOPICKER_SEARCH_ID_PREFIX + System.nanoTime();

        if (cancellationSignal != null) {
            Log.v(TAG, "Search cancelled for searchText: " + searchText + " and searchId: "
                    + searchId);
            cancellationSignal.setOnCancelListener(() -> {
                try {
                    mPhotoPickerLocalSearchManager.cancelSearch(searchId);
                } catch (Exception e) {
                    Log.e(TAG, "Search service is not connected for searchId: " + searchId, e);
                }
            });
        }

        MatrixCursor matrixCursor = new MatrixCursor(
                new String[] {CloudMediaProviderContract.MediaColumns.ID,
                        CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI});

        try {
            SearchMediaResultPage resultPage = mPhotoPickerLocalSearchManager.searchMedia(
                    searchText, searchId, getSearchParams(extras));
            if (resultPage != null) {
                addSearchResultsToCursor(resultPage, matrixCursor);
            }
        } catch (TimeoutException ex) {
            Log.e(TAG, "Timed out waiting for search results for searchId: "
                    + searchId + ". Cancelling search for request " + searchId, ex);
            try {
                mPhotoPickerLocalSearchManager.cancelSearch(searchId);
            } catch (Exception e) {
                Log.e(TAG, "Search service is not connected for searchId: " + searchId, e);
            }
        } catch (IllegalStateException ex) {
            Log.e(TAG, "Search service is not connected for searchId: " + searchId, ex);
        } catch (Exception ex) {
            Log.e(TAG, "Search failed for searchId: " + searchId, ex);
        }

        return matrixCursor;
    }

    private static void addSearchResultsToCursor(SearchMediaResultPage resultPage,
            MatrixCursor matrixCursor) {
        List<SearchMediaResult> searchResults = resultPage.getSearchResults();
        Log.i(TAG, "onSearchMedia returned " + searchResults.size() + " results");

        for (SearchMediaResult result : searchResults) {
            String id = String.valueOf(result.getId());
            matrixCursor.addRow(new Object[]{id, fromMediaId(id)});
        }

        Bundle extras = resultPage.getExtras();
        if (extras.containsKey(SearchMediaService.EXTRA_NEXT_PAGE_TOKEN)) {
            Bundle cursorExtras = new Bundle();
            cursorExtras.putString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN,
                    extras.getString(SearchMediaService.EXTRA_NEXT_PAGE_TOKEN));
            matrixCursor.setExtras(cursorExtras);
        }
    }

    @NonNull
    private static Bundle getSearchParams(@NonNull Bundle extras) {
        Bundle searchParams = new Bundle();
        searchParams.putLong(SearchMediaService.EXTRA_SEARCH_RESULTS_PAGE_SIZE,
                extras.getInt(CloudMediaProviderContract.EXTRA_PAGE_SIZE, PAGE_SIZE));
        searchParams.putString(SearchMediaService.EXTRA_SEARCH_RESULTS_SORT_ORDER,
                SearchMediaService.EXTRA_SORT_BY_TIME);
        String[] mimeTypes = extras.getStringArray(Intent.EXTRA_MIME_TYPES);
        if (mimeTypes != null)  {
            Set<String> mediaTypes = new HashSet<>();
            for (String mimeType : mimeTypes) {
                mediaTypes.add(MimeUtils.extractPrimaryType(mimeType));
            }
            searchParams.putStringArray(SearchMediaService.EXTRA_MEDIA_TYPE_FILTER,
                    mediaTypes.toArray(new String[0]));
        }

        if (extras.containsKey(CloudMediaProviderContract.EXTRA_PAGE_TOKEN)) {
            searchParams.putString(SearchMediaService.EXTRA_NEXT_PAGE_TOKEN,
                    extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN));
        }
        return searchParams;
    }

    @Override
    @Nullable
    public CloudMediaSurfaceController onCreateCloudMediaSurfaceController(@NonNull Bundle config,
            CloudMediaSurfaceStateChangedCallback callback) {
        // The config has all parameters except the |callback|, so marshall that into the config
        config.putBinder(EXTRA_SURFACE_STATE_CALLBACK, callback.getIBinder());
        // Add the local provider authority so the RemoteVideoPreviewProvider knows who to forward
        // URI requests to
        config.putString(EXTRA_AUTHORITY, PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);

        final Bundle bundle = getContext().getContentResolver().call(
                PickerUriResolver.createSurfaceControllerUri(RemoteVideoPreviewProvider.AUTHORITY),
                METHOD_CREATE_SURFACE_CONTROLLER, /* arg */ null, config);

        final IBinder binder = bundle.getBinder(EXTRA_SURFACE_CONTROLLER);
        if (binder == null) {
            throw new IllegalStateException("Surface controller not created");
        }
        return new RemoteVideoPreviewProvider.SurfaceControllerProxy(
                ICloudMediaSurfaceController.Stub.asInterface(binder));
    }

    @Override
    @NonNull
    public Capabilities onGetCapabilities() {
        Capabilities.Builder capabilities = new Capabilities.Builder();
        capabilities.setMediaCategoriesEnabled(
                mConfigStore.isLocalCategoriesInPhotoPickerEnabled());
        capabilities.setSearchEnabled(isSearchEnabled());
        return capabilities.build();
    }

    private boolean isSearchEnabled() {
        try {
            return Flags.enableMediaSearch()
                    && Flags.enableLocalSearchForPhotopicker()
                    && SdkLevel.isAtLeastT()
                    && PhotoPickerLocalSearchManager.getInstance(getContext())
                    .isSemanticSearchSupported();
        } catch (Exception e) {
            Log.e(TAG, "Failed to check if local search is enabled", e);
            return false;
        }
    }

    @Override
    @NonNull
    public Cursor onQueryMediaCategories(
            @Nullable String parentCategoryId,
            @NonNull Bundle extras,
            @Nullable CancellationSignal cancellationSignal) {
        if (!mConfigStore.isLocalCategoriesInPhotoPickerEnabled()
                || (cancellationSignal != null && cancellationSignal.isCanceled())) {
            return new MatrixCursor(CloudMediaProviderContract.MediaCategoryColumns.ALL_PROJECTION);
        }
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);
        return mDbFacade.queryMediaCategories(queryExtras.getMimeTypes(), mConfigStore);
    }

    @Override
    @NonNull
    public Cursor onQueryMediaSets(
            @Nullable String mediaCategoryId,
            @NonNull Bundle extras,
            @Nullable CancellationSignal cancellationSignal) {
        if (!mConfigStore.isLocalCategoriesInPhotoPickerEnabled()
                || (cancellationSignal != null && cancellationSignal.isCanceled())) {
            return new MatrixCursor(CloudMediaProviderContract.MediaSetColumns.ALL_PROJECTION);
        }
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);
        return mDbFacade.queryMediaSets(mediaCategoryId, queryExtras.getMimeTypes(),
                queryExtras.getPageSize(), queryExtras.getPageToken(), mConfigStore);
    }

    @Override
    @NonNull
    public Cursor onQueryMediaInMediaSet(
            @Nullable String mediaSetId,
            @Nullable Bundle extras,
            @Nullable CancellationSignal cancellationSignal) {
        if (!mConfigStore.isLocalCategoriesInPhotoPickerEnabled()
                || (cancellationSignal != null && cancellationSignal.isCanceled())) {
            return new MatrixCursor(CloudMediaProviderContract.MediaColumns.ALL_PROJECTION);
        }
        final CloudProviderQueryExtras queryExtras =
                CloudProviderQueryExtras.fromCloudMediaBundle(extras);
        return mDbFacade.queryMediaInMediaSet(mediaSetId, queryExtras.getMimeTypes(),
                queryExtras.getPageSize(), queryExtras.getPageToken(), queryExtras.getSortOrder(),
                mConfigStore);
    }

    private MediaProvider getMediaProvider() {
        ContentResolver cr = getContext().getContentResolver();
        try (ContentProviderClient cpc = cr.acquireContentProviderClient(MediaStore.AUTHORITY)) {
            return (MediaProvider) cpc.getLocalContentProvider();
        } catch (OperationCanceledException e) {
            throw new IllegalStateException("Failed to acquire MediaProvider", e);
        }
    }

    private static Uri fromMediaId(String mediaId) {
        return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL,
                Long.parseLong(mediaId));
    }

    /**
     * To be used for testing only.
     */
    @VisibleForTesting
    public void setPhotoPickerLocalSearchManager(PhotoPickerLocalSearchManager instance) {
        mPhotoPickerLocalSearchManager = instance;
    }
}
