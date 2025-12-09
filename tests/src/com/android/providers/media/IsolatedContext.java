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

package com.android.providers.media;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.CloudMediaProvider;
import android.provider.MediaStore;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.cloudproviders.CloudProviderPrimary;
import com.android.providers.media.cloudproviders.FlakyCloudProvider;
import com.android.providers.media.dao.FileRow;
import com.android.providers.media.photopicker.PhotoPickerProvider;
import com.android.providers.media.photopicker.PickerSyncController;
import com.android.providers.media.util.FileUtils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Class to support mocking Context class for tests.
 */
public class IsolatedContext extends ContextWrapper {
    private final File mDir;
    private final MockContentResolver mResolver;
    private final MediaProvider mMediaProvider;
    private final UserHandle mUserHandle;
    private final FlakyCloudProvider mFlakyCloudProvider;
    private final LauncherApps mLauncherApps;

    private PackageManager mSpyPackageManager;
    private Map<String, ApplicationInfo> mPackageNameToAppInfoMap = new HashMap<>();
    private boolean mByPassTargetSdkCheckForTrash;
    private boolean mByPassManageExternalStorageCheckForTrash;

    public IsolatedContext(Context base, String tag, boolean asFuseThread) {
        this(base, tag, asFuseThread, base.getUser());
    }

    public IsolatedContext(Context base, String tag, boolean asFuseThread,
            UserHandle userHandle) {
        this(base, tag, asFuseThread, userHandle, new TestConfigStore());
    }

    public IsolatedContext(Context base, String tag, boolean asFuseThread,
            UserHandle userHandle, ConfigStore configStore) {
        this(base, tag, asFuseThread, userHandle, configStore,
                base.getSystemService(LauncherApps.class));
    }

    public IsolatedContext(Context base, String tag, boolean asFuseThread,
            LauncherApps launcherApps) {
        this(base, tag, asFuseThread, base.getUser(), new TestConfigStore(), launcherApps);
    }

    public IsolatedContext(Context base, String tag, boolean asFuseThread,
            UserHandle userHandle, ConfigStore configStore, LauncherApps launcherApps) {
        super(base);
        mDir = new File(base.getFilesDir(), tag);
        mDir.mkdirs();
        FileUtils.deleteContents(mDir);

        mResolver = new MockContentResolver(this);
        mUserHandle = userHandle;

        mMediaProvider = getMockedMediaProvider(asFuseThread, configStore);
        attachInfoAndAddProvider(base, mMediaProvider, MediaStore.AUTHORITY);

        MediaDocumentsProvider documentsProvider = new MediaDocumentsProvider();
        attachInfoAndAddProvider(base, documentsProvider, MediaDocumentsProvider.AUTHORITY);

        mResolver.addProvider(Settings.AUTHORITY, new MockContentProvider() {
            @Override
            public Bundle call(String method, String request, Bundle args) {
                return Bundle.EMPTY;
            }
        });

        PhotoPickerProvider photoPickerProvider = new PhotoPickerProvider();
        attachInfoAndAddProvider(getBaseContext(), photoPickerProvider,
                PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY);

        final CloudMediaProvider cmp = new CloudProviderPrimary();
        attachInfoAndAddProvider(base, cmp, CloudProviderPrimary.AUTHORITY);

        mFlakyCloudProvider = new FlakyCloudProvider();
        attachInfoAndAddProvider(base, mFlakyCloudProvider, FlakyCloudProvider.AUTHORITY);

        MediaStore.waitForIdle(mResolver);

        mSpyPackageManager = spy(base.getPackageManager());

        mLauncherApps = launcherApps;
    }

    private MediaProvider getMockedMediaProvider(boolean asFuseThread,
            ConfigStore configStore) {
        return new MediaProvider() {
            @Override
            public boolean isFuseThread() {
                return asFuseThread;
            }

            @Override
            protected ConfigStore provideConfigStore() {
                return configStore;
            }

            @Override
            protected DatabaseBackupAndRecovery createDatabaseBackupAndRecovery() {
                return new TestDatabaseBackupAndRecovery(configStore, getVolumeCache());
            }

            @Override
            protected void storageNativeBootPropertyChangeListener() {
                // Ignore this as test app cannot read device config
            }

            @Override
            public boolean isCallingPackageTargetSdkVersionGreaterThanB() {
                if (mByPassTargetSdkCheckForTrash) {
                    return true;
                }
                return super.isCallingPackageTargetSdkVersionGreaterThanB();
            }

            @Override
            public void verifyCallerHasManageExternalStoragePermission() {
                if (!mByPassManageExternalStorageCheckForTrash) {
                    super.verifyCallerHasManageExternalStoragePermission();
                }
            }

            @Override
            protected void updateQuotaTypeForUri(@NonNull FileRow row) {
                return;
            }

            @Override
            boolean shouldLockdownMediaStoreVersion() {
                // TODO(b/370999570): Set to true once Baklava is in dev
                return false;
            }

            @Override
            protected void enforcePermissionCheckForOemMetadataUpdate(){

            }
        };
    }

    public void setByPassTargetSdkCheckForTrash(boolean shouldByPass) {
        mByPassTargetSdkCheckForTrash = shouldByPass;
    }

    public void setByPassManageExternalStorageCheckForTrash(boolean shouldByPass) {
        mByPassManageExternalStorageCheckForTrash = shouldByPass;
    }

    @Override
    public File getDatabasePath(String name) {
        return new File(mDir, name);
    }

    @Override
    public ContentResolver getContentResolver() {
        return mResolver;
    }

    @Override
    public UserHandle getUser() {
        return mUserHandle;
    }

    @Override
    public PackageManager getPackageManager() {
        if (!mPackageNameToAppInfoMap.isEmpty()) {
            return mSpyPackageManager;
        }
        return getBaseContext().getPackageManager();
    }

    @Override
    public Object getSystemService(@NonNull String name) {
        if (Context.LAUNCHER_APPS_SERVICE.equals(name)) {
            return mLauncherApps;
        }
        return super.getSystemService(name);
    }

    @Override
    @Nullable
    public String getSystemServiceName(@NonNull Class<?> serviceClass) {
        if (LauncherApps.class.equals(serviceClass)) {
            return Context.LAUNCHER_APPS_SERVICE;
        }
        return super.getSystemServiceName(serviceClass);
    }

    public void setPickerUriResolver(PickerUriResolver resolver) {
        mMediaProvider.setUriResolver(resolver);
    }

    public void attachInfoAndAddProvider(Context base, ContentProvider provider,
            String authority) {
        final ProviderInfo info = base.getPackageManager().resolveContentProvider(authority, 0);
        if (info != null) {
            provider.attachInfo(this, info);
            mResolver.addProvider(authority, provider);
        }
    }

    /**
     * @return {@link DatabaseHelper} The external database helper used by the test {@link
     * IsolatedContext}
     */
    public DatabaseHelper getExternalDatabase() throws IllegalStateException {
        Optional<DatabaseHelper> helper =
                mMediaProvider.getDatabaseHelper(DatabaseHelper.EXTERNAL_DATABASE_NAME);
        if (helper.isPresent()) {
            return helper.get();
        } else {
            throw new IllegalStateException("Failed to get Database helper");
        }
    }

    @VisibleForTesting
    public void setFlakyCloudProviderToFlakeInTheNextRequest() {
        mFlakyCloudProvider.setToFlakeInTheNextRequest();
    }

    @VisibleForTesting
    public void resetFlakyCloudProviderToNotFlakeInTheNextRequest() {
        mFlakyCloudProvider.resetToNotFlakeInTheNextRequest();
    }

    /**
     * Stubs {@link PackageManager#getApplicationInfo} and
     * {@link PackageManager#getApplicationLabel} on the internal PackageManager spy using the
     * provided package name to application info mapping.
     *
     * @param packageNameToAppInfoMap Map of package names to their desired {@link ApplicationInfo}
     */
    @VisibleForTesting
    public void stubApplicationInfoCalls(Map<String, ApplicationInfo> packageNameToAppInfoMap) {
        mSpyPackageManager = spy(getBaseContext().getPackageManager());
        mPackageNameToAppInfoMap = packageNameToAppInfoMap;
        for (String packageName: packageNameToAppInfoMap.keySet()) {
            try {
                doReturn(packageNameToAppInfoMap.get(packageName))
                        .when(mSpyPackageManager)
                        .getApplicationInfo(eq(packageName), anyInt());

                doReturn(packageNameToAppInfoMap.get(packageName).nonLocalizedLabel)
                        .when(mSpyPackageManager)
                        .getApplicationLabel(eq(packageNameToAppInfoMap.get(packageName)));


            } catch (PackageManager.NameNotFoundException e) {
                // This exception is declared but shouldn't happen during stubbing
                throw new RuntimeException("Failed to setup PackageManager spy", e);
            }
        }
    }
}
