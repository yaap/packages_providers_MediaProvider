/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.core.banners

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.UserProperties
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.database.DatabaseManagerTestImpl
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.network.NetworkMonitor
import com.android.photopicker.core.network.NetworkStatus
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.highpriorityuifeature.HighPriorityUiFeature
import com.android.photopicker.features.simpleuifeature.SimpleUiFeature
import com.android.photopicker.util.test.mockSystemService
import com.android.photopicker.util.test.nonNullableAny
import com.android.photopicker.util.test.nonNullableEq
import com.android.photopicker.util.test.whenever
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/** Unit tests for the [BannerManagerImpl] */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BannerManagerImplTest {

    @get:Rule(order = 0) var setFlagsRule = SetFlagsRule()
    @get:Rule(order = 1)
    val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    /**
     * Class that exposes the @hide api [targetUserId] in order to supply proper values for
     * reflection based code that is inspecting this field.
     *
     * @property targetUserId
     */
    private class ReflectedResolveInfo(@JvmField val targetUserId: Int) : ResolveInfo() {

        override fun isCrossProfileIntentForwarderActivity(): Boolean = true
    }

    // Isolate the test device by providing a test wrapper around device config so that the
    // tests can control the flag values that are returned.
    val deviceConfigProxy = TestDeviceConfigProxyImpl()
    private val PLATFORM_PROVIDED_PROFILE_LABEL = "Platform Label"

    private val USER_HANDLE_PRIMARY: UserHandle
    private val USER_ID_PRIMARY: Int = 0
    private val PRIMARY_PROFILE_BASE: UserProfile

    private val USER_HANDLE_MANAGED: UserHandle
    private val USER_ID_MANAGED: Int = 10
    private val MANAGED_PROFILE_BASE: UserProfile
    private val mockContentResolver: ContentResolver = mock(ContentResolver::class.java)

    @Mock lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager

    @Mock lateinit var mockConnectivityManager: ConnectivityManager
    private lateinit var databaseManager: DatabaseManagerTestImpl

    private lateinit var testScope: TestScope
    private lateinit var configurationManager: ConfigurationManager
    private lateinit var featureManager: FeatureManager
    private lateinit var userMonitor: UserMonitor
    private lateinit var bannerManager: BannerManagerImpl
    private lateinit var dataService: TestDataServiceImpl
    lateinit var networkMonitor: NetworkMonitor

    init {
        val parcel1 = Parcel.obtain()
        parcel1.writeInt(USER_ID_PRIMARY)
        parcel1.setDataPosition(0)
        USER_HANDLE_PRIMARY = UserHandle(parcel1)
        parcel1.recycle()

        PRIMARY_PROFILE_BASE =
            UserProfile(
                handle = USER_HANDLE_PRIMARY,
                profileType = UserProfile.ProfileType.PRIMARY,
                label = PLATFORM_PROVIDED_PROFILE_LABEL,
            )

        val parcel2 = Parcel.obtain()
        parcel2.writeInt(USER_ID_MANAGED)
        parcel2.setDataPosition(0)
        USER_HANDLE_MANAGED = UserHandle(parcel2)
        parcel2.recycle()

        MANAGED_PROFILE_BASE =
            UserProfile(
                handle = USER_HANDLE_MANAGED,
                profileType = UserProfile.ProfileType.MANAGED,
                label = PLATFORM_PROVIDED_PROFILE_LABEL,
            )

        databaseManager = DatabaseManagerTestImpl()
        // Default mock behavior for databaseManager
        whenever(
            databaseManager.bannerInteractionState.getBannerInteractionStates(anyInt(), anyString())
        ) {
            null
        }
    }

    val sessionId = generatePickerSessionId()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        deviceConfigProxy.reset()
        val resources = InstrumentationRegistry.getInstrumentation().getContext().getResources()

        mockSystemService(mockContext, UserManager::class.java) { mockUserManager }
        mockSystemService(mockContext, ConnectivityManager::class.java) { mockConnectivityManager }
        whenever(mockContext.packageManager) { mockPackageManager }
        whenever(mockContext.packageName) { "" }
        whenever(mockContext.contentResolver) { mockContentResolver }
        whenever(mockContext.createPackageContextAsUser(any(), anyInt(), any())) { mockContext }
        whenever(mockContext.createContextAsUser(any(UserHandle::class.java), anyInt())) {
            mockContext
        }

        // Initial setup state: Two profiles (Personal/Work), both enabled
        whenever(mockUserManager.userProfiles) { listOf(USER_HANDLE_PRIMARY, USER_HANDLE_MANAGED) }

        // Default responses for relevant UserManager apis
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_PRIMARY)) { false }
        whenever(mockUserManager.isManagedProfile(USER_ID_PRIMARY)) { false }
        whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
        whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
        whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { USER_HANDLE_PRIMARY }

        val mockResolveInfo = ReflectedResolveInfo(USER_ID_MANAGED)
        whenever(
            mockPackageManager.queryIntentActivitiesAsUser(
                any(Intent::class.java),
                anyInt(),
                eq(USER_HANDLE_PRIMARY),
            )
        ) {
            listOf(mockResolveInfo)
        }

        if (SdkLevel.isAtLeastV()) {
            whenever(mockUserManager.getUserBadge()) {
                resources.getDrawable(R.drawable.android, /* theme= */ null)
            }
            whenever(mockUserManager.getProfileLabel()) { PLATFORM_PROVIDED_PROFILE_LABEL }
            whenever(
                mockUserManager.getUserProperties(USER_HANDLE_PRIMARY)
            ) @JvmSerializableLambda { UserProperties.Builder().build() }
            // By default, allow managed profile to be available
            whenever(
                mockUserManager.getUserProperties(USER_HANDLE_MANAGED)
            ) @JvmSerializableLambda {
                UserProperties.Builder()
                    .setCrossProfileContentSharingStrategy(
                        UserProperties.CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT
                    )
                    .build()
            }
        }

        testScope = TestScope(StandardTestDispatcher())
        configurationManager = createConfigurationManager(testScope)
        featureManager = createFeatureManager(testScope, configurationManager)
        userMonitor = createUserMonitor(testScope)
        dataService = TestDataServiceImpl()
        networkMonitor = NetworkMonitor(mockContext, testScope.backgroundScope)
    }

    /**
     * Ensures that the [BannerManagerImpl] does not emits any Banner when all features are
     * disabled.
     */
    @Test
    fun testEmitsNoBannerWhenNoFeaturesEnabled() =
        testScope.runTest {
            featureManager = createFeatureManager(this, configurationManager, emptySet())
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )

            assertWithMessage("Expected no banner to be emitted")
                .that(bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value)
                .isNull()
        }

    /** Ensures that the [BannerManagerImpl] emits its current Banner. */
    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testEmitsCorrectBannerByPriority() =
        testScope.runTest {
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )
            whenever(databaseManager.bannerState.getBannerState(anyString(), anyInt())) { null }

            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            assertWithMessage("Incorrect banner was chosen.")
                .that(
                    bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value?.declaration
                )
                .isEqualTo(BannerDefinitions.PRIVACY_EXPLAINER)
        }

    /** Ensures that the [BannerManagerImpl] emits its current Banner. */
    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testEmitsCorrectBannerByPriorityPreviouslyDismissed() =
        testScope.runTest {
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )
            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            // Mock out the database state for PRIVACY_EXPLAINER and mark it as previously
            // dismissed.
            whenever(
                databaseManager.bannerState.getBannerState(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                    uid = 0,
                    dismissed = true,
                )
            }

            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            // Ensure BannerManager fetches the database state for the banner, with the correct uid
            verify(databaseManager.bannerState)
                .getBannerState(BannerDefinitions.PRIVACY_EXPLAINER.id, 12345)

            assertWithMessage("Incorrect banner was chosen.")
                .that(bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value)
                .isNull()
        }

    /** Ensures that the [BannerManagerImpl] emits the highest priority Banner. */
    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testEmitsHighestPriorityBanner() =
        testScope.runTest {
            val networkCap: NetworkCapabilities =
                NetworkCapabilities.Builder()
                    .apply { addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                    .build()

            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { networkCap }
            networkMonitor = NetworkMonitor(mockContext, this.backgroundScope)

            advanceTimeBy(100)

            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )

            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            // Mock out database state as no previously dismissed banners
            whenever(databaseManager.bannerState.getBannerState(anyString(), anyInt())) { null }

            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            // Ensure BannerManager fetches the database state for the banner, with the correct uids
            verify(databaseManager.bannerState)
                .getBannerState(BannerDefinitions.PRIVACY_EXPLAINER.id, 12345)
            verify(databaseManager.bannerState)
                .getBannerState(BannerDefinitions.CLOUD_CHOOSE_ACCOUNT.id, 0)

            assertWithMessage("Incorrect banner was chosen.")
                .that(
                    bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value?.declaration
                )
                .isEqualTo(BannerDefinitions.CLOUD_CHOOSE_ACCOUNT)
        }

    /** Ensures that when flag disabled the [BannerManagerImpl] does not emit the offline banner. */
    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_OFFLINE_BANNERS)
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testNetworkUnavailable_whenOfflineFlagDisabled_doesNotEmitOfflineBanner() =
        testScope.runTest {
            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { null }
            networkMonitor = NetworkMonitor(mockContext, this.backgroundScope)

            advanceTimeBy(100)

            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )

            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            // Mock out database state as no previously dismissed banners
            whenever(databaseManager.bannerState.getBannerState(anyString(), anyInt())) { null }

            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            assertWithMessage("Incorrect banner was chosen.")
                .that(
                    bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value?.declaration
                )
                .isNotEqualTo(BannerDefinitions.DEVICE_NETWORK_UNAVAILABLE)
        }

    /**
     * Ensures that when flag enabled the [BannerManagerImpl] emits the offline banner when no
     * network.
     */
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_OFFLINE_BANNERS)
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testNetworkUnavailable_whenOfflineFlagEnabled_emitsOfflineBanner() =
        testScope.runTest {
            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { null }
            networkMonitor = NetworkMonitor(mockContext, this.backgroundScope)

            // Wait for the network status to update from its initial "Unknown" state.
            networkMonitor.networkStatus.first { it == NetworkStatus.Unavailable }

            dataService.setAvailableProviders(
                listOf(
                    Provider(
                        authority = "clout_authority",
                        mediaSource = MediaSource.REMOTE,
                        uid = 2,
                        displayName = "Cloud Provider",
                    )
                )
            )

            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )

            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            // Mock out database state as no previously dismissed banners
            whenever(databaseManager.bannerState.getBannerState(anyString(), anyInt())) { null }

            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            advanceTimeBy(100)

            assertWithMessage("Incorrect banner was chosen.")
                .that(
                    bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value?.declaration
                )
                .isEqualTo(BannerDefinitions.DEVICE_NETWORK_UNAVAILABLE)
        }

    /**
     * Ensures that when flag enabled the and current cloud provider not selected then does not emit
     * offline banner.
     */
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_OFFLINE_BANNERS)
    fun testNetworkUnavailable_noCloudProvider_doesNotEmitOfflineBanner() =
        testScope.runTest {
            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { null }
            networkMonitor = NetworkMonitor(mockContext, this.backgroundScope)

            // Wait for the network status to update from its initial "Unknown" state.
            networkMonitor.networkStatus.first { it == NetworkStatus.Unavailable }

            dataService.setAvailableProviders(emptyList())

            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )

            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            // Mock out database state as no previously dismissed banners
            whenever(databaseManager.bannerState.getBannerState(anyString(), anyInt())) { null }

            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            advanceTimeBy(100)

            assertWithMessage("Incorrect banner was chosen.")
                .that(
                    bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value?.declaration
                )
                .isNotEqualTo(BannerDefinitions.DEVICE_NETWORK_UNAVAILABLE)
        }

    /**
     * Ensures that when flag enabled the [BannerManagerImpl] does not emit the offline banner when
     * the network is available.
     */
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_OFFLINE_BANNERS)
    fun testNetworkAvailable_whenOfflineFlagEnabled_doesNotEmitOfflineBanner() =
        testScope.runTest {
            val networkCap: NetworkCapabilities =
                NetworkCapabilities.Builder()
                    .apply { addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                    .build()

            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { networkCap }
            networkMonitor = NetworkMonitor(mockContext, this.backgroundScope)

            advanceTimeBy(100)

            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )

            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            // Mock out database state as no previously dismissed banners
            whenever(databaseManager.bannerState.getBannerState(anyString(), anyInt())) { null }

            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            assertWithMessage("Incorrect banner was chosen.")
                .that(
                    bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value?.declaration
                )
                .isNotEqualTo(BannerDefinitions.DEVICE_NETWORK_UNAVAILABLE)
        }

    /** Ensures that the [BannerManagerImpl] immediately shows the requested banner. */
    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testShowBanner() =
        testScope.runTest {
            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )

            assertWithMessage("Initial banner was not null.")
                .that(bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value)
                .isNull()

            bannerManager.showBanner(
                BannerDefinitions.PRIVACY_EXPLAINER,
                BannerLocation.PHOTO_GRID_BANNER,
            )

            val shownBanner =
                withTimeout(1000) {
                    bannerManager
                        .getBannerFlow(BannerLocation.PHOTO_GRID_BANNER)
                        .filterNotNull()
                        .first()
                }

            assertWithMessage("Incorrect banner was shown.")
                .that(shownBanner.declaration)
                .isEqualTo(BannerDefinitions.PRIVACY_EXPLAINER)
        }

    /** Ensures that the [BannerManagerImpl] immediately hides the shown banner. */
    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testHideBanner() =
        testScope.runTest {
            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )

            assertWithMessage("Initial banner was not null.")
                .that(bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value)
                .isNull()

            bannerManager.showBanner(
                BannerDefinitions.PRIVACY_EXPLAINER,
                BannerLocation.PHOTO_GRID_BANNER,
            )

            // Wait for the banner to appear before proceeding
            val shownBanner =
                withTimeout(1000) {
                    bannerManager
                        .getBannerFlow(BannerLocation.PHOTO_GRID_BANNER)
                        .filterNotNull()
                        .first()
                }

            assertWithMessage("Incorrect banner was shown.")
                .that(shownBanner.declaration)
                .isEqualTo(BannerDefinitions.PRIVACY_EXPLAINER)

            bannerManager.hideBanners()

            val hiddenBanner =
                withTimeout(1000) {
                    bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).first {
                        it == null
                    }
                }

            assertWithMessage("Expected current banner to be null.").that(hiddenBanner).isNull()
        }

    /**
     * Ensures that the [BannerManagerImpl] persists dismiss state for the once dismissal strategy.
     */
    @Test
    fun testMarkBannerAsDismissedOnceStrategy() =
        testScope.runTest {
            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )

            bannerManager.markBannerAsDismissed(BannerDefinitions.CLOUD_CHOOSE_ACCOUNT)
            verify(databaseManager.bannerState)
                .setBannerState(
                    BannerState(
                        bannerId = BannerDefinitions.CLOUD_CHOOSE_ACCOUNT.id,
                        uid = 0,
                        dismissed = true,
                    )
                )
        }

    /**
     * Ensures that the [BannerManagerImpl] persists dismiss state for the per uid dismissal
     * strategy.
     */
    @Test
    fun testMarkBannerAsDismissedPerUidStrategy() =
        testScope.runTest {
            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )
            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            bannerManager.markBannerAsDismissed(BannerDefinitions.PRIVACY_EXPLAINER)
            verify(databaseManager.bannerState)
                .setBannerState(
                    BannerState(
                        bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                        uid = 12345,
                        dismissed = true,
                    )
                )
        }

    /**
     * Ensures that the [BannerManagerImpl] persists dismiss state for the per uid dismissal
     * strategy.
     */
    @Test
    fun testMarkBannerAsDismissedSessionStrategy() =
        testScope.runTest {
            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )
            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )
            val test_session_banner =
                object : BannerDeclaration {
                    override val id = "test_session_banner"
                    override val dismissableStrategy = BannerDeclaration.DismissStrategy.SESSION
                    override val dismissable = true
                }

            bannerManager.markBannerAsDismissed(test_session_banner)

            assertWithMessage("Expected banner state to be dismissed")
                .that(bannerManager.getBannerState(test_session_banner)?.dismissed)
                .isTrue()

            // Ensure no calls to persist the state in the database.
            verify(databaseManager.bannerState, never())
                .setBannerState(
                    BannerState(
                        bannerId = BannerDefinitions.SWITCH_PROFILE.id,
                        uid = 12345,
                        dismissed = true,
                    )
                )
        }

    /** Ensures that the [BannerManagerImpl] never shows banners with a priority less than zero. */
    @Test
    fun testIgnoresBannersWithNegativePriority() =
        testScope.runTest {

            // Mock out a feature and provide a fake registration that provides the mock.
            val mockSimpleUiFeature: SimpleUiFeature = mock(SimpleUiFeature::class.java)
            val mockRegistration =
                object : FeatureRegistration {
                    override val TAG = "MockedFeature"

                    override fun isEnabled(
                        config: PhotopickerConfiguration,
                        deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
                    ) = true

                    override fun build(featureManager: FeatureManager) = mockSimpleUiFeature
                }

            featureManager =
                createFeatureManager(this, configurationManager, setOf(mockRegistration))
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )

            // Set the caller because PRIVACY_EXPLAINER is PER_UID dismissal.
            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            whenever(mockSimpleUiFeature.ownedBanners) {
                setOf(BannerDefinitions.PRIVACY_EXPLAINER)
            }
            whenever(
                mockSimpleUiFeature.getBannerPriority(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER),
                    isNull(),
                    nonNullableEq(configurationManager.configuration.value),
                    nonNullableEq(dataService),
                    nonNullableEq(userMonitor),
                    nonNullableAny(NetworkStatus::class.java, NetworkStatus.Unknown),
                    nonNullableEq(BannerLocation.PHOTO_GRID_BANNER),
                )
            ) {
                -1
            }

            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            assertWithMessage("Incorrect banner was chosen.")
                .that(bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value)
                .isNull()
        }

    /** Ensures that the [BannerManagerImpl] emits its current Banner. */
    @Test
    fun testHidesBannersOnProfileSwitch() =
        testScope.runTest {
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )
            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            userMonitor.requestSwitchActiveUserProfile(
                requested = MANAGED_PROFILE_BASE,
                mockContext,
            )
            advanceTimeBy(100)

            assertWithMessage("Incorrect banner was chosen.")
                .that(bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value)
                .isNull()
        }

    /**
     * Ensures that when no cloud feature is supported then [BannerManagerImpl] emits
     * PRIVACY_EXPLAINER as active Banner.
     */
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testEmitsPrivacyExplainer_withBannerRedesignEnabled_noCloudProvider() =
        testScope.runTest {
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )
            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            assertWithMessage("Incorrect banner was chosen.")
                .that(
                    bannerManager
                        .getBannerFlow(BannerLocation.PHOTO_GRID_BANNER)
                        .value
                        ?.bannerDefinition
                )
                .isEqualTo(BannerDefinition.PRIVACY_EXPLAINER)
        }

    /** Ensures that the [BannerManagerImpl] emits the next highest priority banner on dismissal. */
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testEmitsNextPriorityBannerOnDismissal_withBannerRedesignEnabled() =
        testScope.runTest {
            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            // Configure DataService with a provider so CLOUD_CHOOSE_ACCOUNT is eligible
            dataService.setAvailableProviders(
                listOf(
                    Provider(
                        authority = "",
                        mediaSource = MediaSource.REMOTE,
                        uid = 2,
                        displayName = "",
                    )
                )
            )

            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService = dataService,
                    userMonitor = userMonitor,
                    networkMonitor = networkMonitor,
                )

            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            // Ensure BannerManager fetches the database state for the banner, with the correct uids
            verify(databaseManager.bannerInteractionState)
                .getBannerInteractionStates(12345, "com.android.test.package")

            assertWithMessage("Incorrect banner was chosen.")
                .that(
                    bannerManager
                        .getBannerFlow(BannerLocation.PHOTO_GRID_BANNER)
                        .value
                        ?.bannerDefinition
                )
                .isEqualTo(HighPriorityUiFeature.OWNED_BANNER_DEFINITION)

            bannerManager.markBannerAsManuallyDismissed(BannerDefinition.CLOUD_CHOOSE_ACCOUNT)

            advanceUntilIdle()

            assertWithMessage("Incorrect banner was chosen.")
                .that(
                    bannerManager
                        .getBannerFlow(BannerLocation.PHOTO_GRID_BANNER)
                        .value
                        ?.bannerDefinition
                )
                .isEqualTo(SimpleUiFeature.OWNED_BANNER_DEFINITION)
        }

    /** Ensures that the [BannerManagerImpl] immediately hides the shown banner. */
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testHideBanner_withBannerRedesignEnabled() =
        testScope.runTest {
            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration, HighPriorityUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    userMonitor = userMonitor,
                    networkMonitor = networkMonitor,
                )

            assertWithMessage("Initial banner was not null.")
                .that(bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value)
                .isNull()

            bannerManager.showBanner(
                BannerDefinition.PRIVACY_EXPLAINER,
                BannerLocation.PHOTO_GRID_BANNER,
            )

            // Wait for the banner to appear before proceeding
            val shownBanner =
                withTimeout(1000) {
                    bannerManager
                        .getBannerFlow(BannerLocation.PHOTO_GRID_BANNER)
                        .filterNotNull()
                        .first()
                }

            assertWithMessage("Incorrect banner was shown.")
                .that(shownBanner.bannerDefinition)
                .isEqualTo(BannerDefinition.PRIVACY_EXPLAINER)

            bannerManager.hideBanners()

            val hiddenBanner =
                withTimeout(1000) {
                    bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).first {
                        it == null
                    }
                }

            assertWithMessage("Expected current banner to be null.").that(hiddenBanner).isNull()
        }

    /**
     * Ensures that [BannerManagerImpl] automatically dismisses a banner once it has reached its
     * maximum show count.
     */
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testBannerAutoDismissedAfterMaxShowCount() =
        testScope.runTest {
            featureManager =
                createFeatureManager(
                    this,
                    configurationManager,
                    setOf(SimpleUiFeature.Registration),
                )
            bannerManager =
                createBannerManager(
                    this,
                    configurationManager,
                    featureManager,
                    dataService,
                    userMonitor,
                    networkMonitor,
                )

            configurationManager.setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )

            // Initially, no banner interaction state in the database
            whenever(
                databaseManager.bannerInteractionState.getBannerInteractionStates(
                    anyInt(),
                    anyString(),
                )
            ) {
                null
            }

            // Refresh the banner for the first time
            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            // Verify that the banner is shown initially
            assertWithMessage("Banner should be shown initially.")
                .that(
                    bannerManager
                        .getBannerFlow(BannerLocation.PHOTO_GRID_BANNER)
                        .value
                        ?.bannerDefinition
                )
                .isEqualTo(SimpleUiFeature.OWNED_BANNER_DEFINITION)

            // Verify that the banner interaction state was saved with isDismissed = true
            // since PRIVACY_EXPLAINER has maxShowCount = 1 and dismissiblePer = PER_DEVICE
            verify(databaseManager.bannerInteractionState)
                .setBannerInteractionState(
                    BannerInteractionState(
                        bannerId = BannerDefinition.PRIVACY_EXPLAINER,
                        appUid = 0,
                        packageName = "system",
                        isDismissed = true,
                        shownCount = 1,
                    )
                )

            // Refresh the banner again. Now it should be dismissed in the internal cache.
            bannerManager.refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            // Verify that the banner is no longer shown
            assertWithMessage("Banner should be dismissed after reaching max show count.")
                .that(bannerManager.getBannerFlow(BannerLocation.PHOTO_GRID_BANNER).value)
                .isNull()
        }

    private fun createConfigurationManager(scope: TestScope): ConfigurationManager {
        return ConfigurationManager(
            runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            deviceConfigProxy,
            sessionId,
        )
    }

    private fun createFeatureManager(
        scope: TestScope,
        configurationManager: ConfigurationManager,
        features: Set<FeatureRegistration> = setOf(SimpleUiFeature.Registration),
    ): FeatureManager {
        return FeatureManager(
            configurationManager.configuration,
            scope.backgroundScope,
            TestPrefetchDataService(),
            features,
        )
    }

    private fun createUserMonitor(scope: TestScope): UserMonitor {
        return UserMonitor(
            mockContext,
            provideTestConfigurationFlow(
                scope = scope.backgroundScope,
                defaultConfiguration =
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    },
            ),
            scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler),
            USER_HANDLE_PRIMARY,
        )
    }

    private fun createBannerManager(
        scope: TestScope,
        configurationManager: ConfigurationManager,
        featureManager: FeatureManager,
        dataService: DataService = TestDataServiceImpl(),
        userMonitor: UserMonitor,
        networkMonitor: NetworkMonitor,
    ): BannerManagerImpl {
        return BannerManagerImpl(
            scope = scope.backgroundScope,
            backgroundDispatcher = StandardTestDispatcher(scope.testScheduler),
            configurationManager = configurationManager,
            databaseManager = databaseManager,
            featureManager = featureManager,
            dataService = dataService,
            userMonitor = userMonitor,
            networkMonitor = networkMonitor,
            processOwnerHandle = USER_HANDLE_PRIMARY,
        )
    }
}
