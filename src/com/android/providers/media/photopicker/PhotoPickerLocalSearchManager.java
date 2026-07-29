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

package com.android.providers.media.photopicker;

import static android.content.Context.BIND_AUTO_CREATE;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.ISearchMediaCallback;
import android.provider.ISearchMediaService;
import android.provider.MediaStore;
import android.provider.SearchMediaException;
import android.provider.SearchMediaResultPage;
import android.provider.SearchMediaService;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;

import com.android.providers.media.WorkManagerInitializer;
import com.android.providers.media.photopicker.sync.SearchMediaServiceDisconnectWorker;
import com.android.providers.media.util.BackgroundThreadPool;
import com.android.providers.media.util.ForegroundThreadPool;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manager class to handle local search requests from PhotoPicker.
 * <p>
 * Handles PhotoPicker's interactions with the {@link SearchMediaService}.
 */
public class PhotoPickerLocalSearchManager {
    private static final String TAG = "LocalSearchManager";
    private static final String DISCONNECT_SEARCH_SERVICE_WORK_NAME = "DisconnectSearchService";
    private static final Duration TIMEOUT_FOR_SEARCH = Duration.ofSeconds(3);
    private static final Duration TIMEOUT_FOR_SEMANTIC_SEARCH_CHECK = Duration.ofMillis(125);
    private static final Duration TIMEOUT_FOR_SEARCH_CANCELLATION = Duration.ofSeconds(1);
    private static final Duration TIMEOUT_TO_CONNECT_TO_SERVICE = Duration.ofSeconds(1);
    private static final Duration DISCONNECT_SEARCH_SERVICE_DELAY = Duration.ofMinutes(15);
    private static final ReentrantReadWriteLock sLock = new ReentrantReadWriteLock();
    private static PhotoPickerLocalSearchManager sInstance;
    @GuardedBy("sLock")
    private volatile ISearchMediaService mSearchMediaService;
    private CountDownLatch mServiceLatch;
    private ServiceConnection mServiceConnection;
    private final Context mContext;
    private Optional<String> mServicePackageName = Optional.empty();


    /**
     * Returns the singleton instance of {@link PhotoPickerLocalSearchManager}.
     *
     * @param context The application context.
     * @return The singleton instance.
     */
    @NonNull
    public static PhotoPickerLocalSearchManager getInstance(Context context) {
        sLock.writeLock().lock();
        try {
            if (sInstance == null) {
                sInstance = new PhotoPickerLocalSearchManager(context);
            }
            return sInstance;
        } finally {
            sLock.writeLock().unlock();
        }
    }

    @VisibleForTesting
    protected PhotoPickerLocalSearchManager(Context context) {
        mContext = context;
    }

    /**
     * Searches for media items matching the given query.
     *
     * @param searchText The text to search for.
     * @param searchId A unique identifier for this search request.
     * @param searchParams Additional parameters for the search.
     * @return A {@link SearchMediaResultPage} containing the search results.
     * @throws IllegalStateException If the search service is not connected.
     * @throws InterruptedException If the thread is interrupted while waiting for search results.
     * @throws TimeoutException If the wait timed out while fetching search results.
     */
    public SearchMediaResultPage searchMedia(String searchText, String searchId,
            Bundle searchParams) throws Exception {
        ensureServiceConnected();

        sLock.readLock().lock();
        try {
            if (!isServiceConnectedLocked()) {
                throw new IllegalStateException("Service not connected");
            }

            Log.v(TAG, "searching media for searchId " + searchId);
            CompletableFuture<SearchMediaResultPage> future = new CompletableFuture<>();
            ISearchMediaCallback.Stub callback = new ISearchMediaCallback.Stub() {
                @Override
                public void onSearchResultsSuccess(SearchMediaResultPage result) {
                    future.complete(result);
                }

                @Override
                public void onSearchResultsFailure(SearchMediaException exception) {
                    future.completeExceptionally(exception);
                }
            };
            mSearchMediaService.searchMedia(searchText, searchId, searchParams, callback);
            return future.get(TIMEOUT_FOR_SEARCH.toSeconds(), TimeUnit.SECONDS);
        } finally {
            sLock.readLock().unlock();
        }
    }

    /**
     * Checks if the connected service supports semantic search.
     *
     * @return true if semantic search is supported, false otherwise.
     */
    public boolean isSemanticSearchSupported() {
        ensureServiceConnected();

        sLock.readLock().lock();
        try {
            if (!isServiceConnectedLocked()) {
                throw new IllegalStateException("Service not connected");
            }

            Log.v(TAG, "Checking if semantic search is supported by implementor");

            try {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return mSearchMediaService.isSemanticSearchSupported();
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                }, ForegroundThreadPool.getExecutor()).get(
                        TIMEOUT_FOR_SEMANTIC_SEARCH_CHECK.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Log.e(TAG, "Failed to check if semantic search is supported within timeout", e);
                return false;
            }
        } finally {
            sLock.readLock().unlock();
        }
    }

    /**
     * Cancels an ongoing search operation.
     *
     * @param searchId The unique identifier of the search to cancel.
     * @throws IllegalStateException If the search service is not connected.
     */
    public void cancelSearch(String searchId) {
        ensureServiceConnected();

        sLock.readLock().lock();
        try {
            if (!isServiceConnectedLocked()) {
                throw new IllegalStateException("Service not connected");
            }

            Log.v(TAG, "Cancelling search for searchId: " + searchId);
            try {
                CompletableFuture.runAsync(() -> {
                    try {
                        mSearchMediaService.cancelSearch(searchId);
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                }, BackgroundThreadPool.getExecutor()).get(
                        TIMEOUT_FOR_SEARCH_CANCELLATION.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.e(TAG, "Failed to cancel search for searchId " + searchId, e);
            }
        } finally {
            sLock.readLock().unlock();
        }
    }

    /**
     * Unbinds and disconnects from the SearchMediaService.
     */
    public void stop() {
        sLock.writeLock().lock();
        try {
            if (!isServiceConnectedLocked()) {
                return;
            }

            Log.v(TAG, "unbinding service");
            mContext.unbindService(mServiceConnection);
            mSearchMediaService = null;
            Log.d(TAG, "service disconnected");
        } finally {
            sLock.writeLock().unlock();
        }
    }

    public boolean isServiceConnectedLocked() {
        return mSearchMediaService != null;
    }

    private void ensureServiceConnected() {
        sLock.writeLock().lock();
        try {
            if (isServiceConnectedLocked()) {
                Log.v(TAG, "service already connected");
                return;
            }

            if (mServicePackageName.isEmpty()) {
                String packageName =
                        MediaStore.getPackageForSearchMediaService(mContext.getContentResolver());
                if (packageName.isEmpty()) {
                    Log.v(TAG, "Did not find any implementor of SearchMediaService.");
                    return;
                }
                mServicePackageName = Optional.of(packageName);
            }

            Log.v(TAG, "Connecting to package: " + mServicePackageName.get());
            Intent intent = new Intent(SearchMediaService.SERVICE_INTERFACE);
            intent.setPackage(mServicePackageName.get());
            mServiceLatch = new CountDownLatch(1);
            mServiceConnection = getServiceConnection();
            mContext.bindService(intent, BIND_AUTO_CREATE, BackgroundThreadPool.getExecutor(),
                    mServiceConnection);
            try {
                mServiceLatch.await(TIMEOUT_TO_CONNECT_TO_SERVICE.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to connect to SearchMediaService.", ex);
            } finally {
                scheduleSearchMediaServiceDisconnect();
            }
        } finally {
            sLock.writeLock().unlock();
        }
    }

    private ServiceConnection getServiceConnection() {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                mSearchMediaService = ISearchMediaService.Stub.asInterface(iBinder);
                Log.v(TAG, "service connected.");
                mServiceLatch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                Log.v(TAG, "service disconnected.");
                mSearchMediaService = null;
            }

            @Override
            public void onBindingDied(ComponentName name) {
                Log.e(TAG, "Binding died for component: " + name);
                stop();
            }
        };
    }

    /**
     * Schedules a {@link androidx.work.WorkManager} task to disconnect from the
     * {@link SearchMediaService} after a predefined delay.
     * <p>
     * This ensures that the service connection is not held open indefinitely when not in use.
     * If a disconnect task is already scheduled, this method replaces it, effectively resetting
     * the disconnect timer.
     */
    private void scheduleSearchMediaServiceDisconnect() {
        try {
            OneTimeWorkRequest disconnectWork = getSearchMediaServiceDisconnectWorkRequest();

            WorkManagerInitializer.getWorkManager(mContext).enqueueUniqueWork(
                    DISCONNECT_SEARCH_SERVICE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    disconnectWork
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule SearchMediaService disconnect work", e);
        }
    }

    @VisibleForTesting
    protected OneTimeWorkRequest getSearchMediaServiceDisconnectWorkRequest() {
        Log.v(TAG, "Scheduling SearchMediaService disconnect in "
                + DISCONNECT_SEARCH_SERVICE_DELAY.toMinutes() + " minutes.");

        Constraints constraints = new Constraints.Builder()
                .setRequiresDeviceIdle(true)
                .build();

        return new OneTimeWorkRequest.Builder(SearchMediaServiceDisconnectWorker.class)
                .setInitialDelay(DISCONNECT_SEARCH_SERVICE_DELAY.toMinutes(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
    }

    /**
     * To be used for testing only.
     */
    @VisibleForTesting
    static synchronized void setInstance(PhotoPickerLocalSearchManager instance) {
        sInstance = instance;
    }
}
