/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.photopicker.features.cloudmedia

import android.app.Instrumentation.ActivityMonitor
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.activity.compose.setContent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.banners.BannerDefinition
import com.android.photopicker.core.banners.BannerDefinitions
import com.android.photopicker.core.banners.BannerInteractionState
import com.android.photopicker.core.banners.BannerInteractionStateDao
import com.android.photopicker.core.banners.BannerLocation
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.banners.BannerState
import com.android.photopicker.core.banners.BannerStateDao
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.DeviceConfigProxy
import com.android.photopicker.core.configuration.FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST
import com.android.photopicker.core.configuration.FEATURE_CLOUD_MEDIA_FEATURE_ENABLED
import com.android.photopicker.core.configuration.FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST
import com.android.photopicker.core.configuration.NAMESPACE_MEDIAPROVIDER
import com.android.photopicker.core.configuration.PhotopickerFlags
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.database.DatabaseManager
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.Priority
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.user.UserMonitor
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.features.overflowmenu.OverflowMenuFeature
import com.android.photopicker.features.profileselector.SwitchProfileBannerTest.Companion.USER_HANDLE_PRIMARY
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.mockSystemService
import com.android.photopicker.util.test.nonNullableEq
import com.android.photopicker.util.test.whenever
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class CloudMediaFeatureTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) var setFlagsRule = SetFlagsRule()
    @get:Rule(order = 3)
    val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 4) val glideRule = GlideTestRule()

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for ActivityModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope

    /* Overrides for the ConcurrencyModule */
    @BindValue @Main val mainDispatcher: CoroutineDispatcher = testDispatcher
    @BindValue @Background val backgroundDispatcher: CoroutineDispatcher = testDispatcher

    @BindValue @ApplicationOwned val contentResolver: ContentResolver = MockContentResolver()

    @Inject lateinit var selection: Lazy<Selection<Media>>
    @Inject lateinit var featureManager: Lazy<FeatureManager>
    @Inject lateinit var bannerManager: Lazy<BannerManager>
    @Inject lateinit var events: Lazy<Events>
    @Inject lateinit var dataService: Lazy<DataService>
    @Inject lateinit var databaseManager: DatabaseManager
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>

    // Needed for UserMonitor
    @Inject lateinit var mockContext: Context
    @Inject lateinit var deviceConfig: DeviceConfigProxy
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    @Mock lateinit var mockConnectivityManager: ConnectivityManager
    @Inject @Background lateinit var injectedBackgroundDispatcher: CoroutineDispatcher

    private lateinit var testBackgroundDispatcher: TestDispatcher

    private val localProvider =
        Provider(
            authority = "local_authority",
            mediaSource = MediaSource.LOCAL,
            uid = 1,
            displayName = "Local Provider",
        )
    private val cloudProvider =
        Provider(
            authority = "clout_authority",
            mediaSource = MediaSource.REMOTE,
            uid = 2,
            displayName = "Cloud Provider",
        )

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        hiltRule.inject()
        testBackgroundDispatcher = injectedBackgroundDispatcher as TestDispatcher

        val testDeviceConfigProxy =
            checkNotNull(deviceConfig as? TestDeviceConfigProxyImpl) {
                "Expected a TestDeviceConfigProxy"
            }

        testDeviceConfigProxy.setFlag(
            NAMESPACE_MEDIAPROVIDER,
            FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.first,
            true,
        )
        testDeviceConfigProxy.setFlag(
            NAMESPACE_MEDIAPROVIDER,
            FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST.first,
            true,
        )
        testDeviceConfigProxy.setFlag(
            NAMESPACE_MEDIAPROVIDER,
            FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST.first,
            "com.android.test.cloudpicker",
        )

        configurationManager
            .get()
            .setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )
        // Stub for MockContentResolver constructor
        whenever(mockContext.getApplicationInfo()) { getTestableContext().getApplicationInfo() }
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
        mockSystemService(mockContext, ConnectivityManager::class.java) { mockConnectivityManager }
    }

    @Test
    fun testCloudMediaEnabledInConfigurations() {
        assertWithMessage("CloudMediaFeature is not always enabled (ACTION_PICK_IMAGES)")
            .that(
                CloudMediaFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                        flags(
                            PhotopickerFlags(
                                CLOUD_MEDIA_ENABLED = true,
                                CLOUD_ALLOWED_PROVIDERS = arrayOf("cloud_authority"),
                            )
                        )
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("CloudMediaFeature is enabled with invalid flags (ACTION_PICK_IMAGES)")
            .that(
                CloudMediaFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("CloudMediaFeature is not always enabled (ACTION_GET_CONTENT)")
            .that(
                CloudMediaFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                        flags(
                            PhotopickerFlags(
                                CLOUD_MEDIA_ENABLED = true,
                                CLOUD_ALLOWED_PROVIDERS = arrayOf("cloud_authority"),
                            )
                        )
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("CloudMediaFeature is enabled with invalid flags (ACTION_GET_CONTENT)")
            .that(
                CloudMediaFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("CloudMediaFeature is not always disabled (USER_SELECT_FOR_APP)")
            .that(
                CloudMediaFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                        intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                        callingPackage("com.example.test")
                        callingPackageUid(1234)
                        callingPackageLabel("test_app")
                        flags(
                            PhotopickerFlags(
                                CLOUD_MEDIA_ENABLED = true,
                                CLOUD_ALLOWED_PROVIDERS = arrayOf("cloud_authority"),
                            )
                        )
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage(
                "CloudMediaFeature is not always disabled (default flags) (USER_SELECT_FOR_APP)"
            )
            .that(
                CloudMediaFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                        intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                        callingPackage("com.example.test")
                        callingPackageUid(1234)
                        callingPackageLabel("test_app")
                    }
                )
            )
            .isEqualTo(false)
    }

    @Test
    fun testCloudMediaOverflowMenuItemIsDisplayed() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            assertWithMessage("OverflowMenuFeature is not enabled")
                .that(featureManager.get().isFeatureEnabled(OverflowMenuFeature::class.java))
                .isTrue()

            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_overflow_menu_description)
                    )
                )
                .performClick()

            composeTestRule
                .onNode(
                    hasText(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_overflow_cloud_media_app)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    fun testCloudMediaOverflowMenuItemLaunchesCloudSettings() =
        testScope.runTest {

            // Setup an intentFilter that matches the settings action
            val intentFilter =
                IntentFilter().apply { addAction(MediaStore.ACTION_PICK_IMAGES_SETTINGS) }

            // Setup an activityMonitor to catch any launched intents to settings
            val activityMonitor =
                ActivityMonitor(intentFilter, /* result= */ null, /* block= */ true)
            InstrumentationRegistry.getInstrumentation().addMonitor(activityMonitor)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            assertWithMessage("OverflowMenuFeature is not enabled")
                .that(featureManager.get().isFeatureEnabled(OverflowMenuFeature::class.java))
                .isTrue()

            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_overflow_menu_description)
                    )
                )
                .performClick()

            composeTestRule
                .onNode(
                    hasText(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_overflow_cloud_media_app)
                    )
                )
                .assertIsDisplayed()
                .performClick()

            activityMonitor.waitForActivityWithTimeout(5000L)
            assertWithMessage("Settings activity wasn't launched")
                .that(activityMonitor.getHits())
                .isEqualTo(1)
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudMediaAvailableBanner() =
        testScope.runTest {
            val bannerStateDao = databaseManager.acquireDao(BannerStateDao::class.java)

            // Treat privacy explainer as already dismissed since it's a higher priority.
            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                    dismissed = true,
                    uid = 12345,
                )
            }
            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.CLOUD_MEDIA_AVAILABLE.id),
                    anyInt(),
                )
            ) {
                null
            }
            val networkCap: NetworkCapabilities =
                NetworkCapabilities.Builder()
                    .apply { addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                    .build()

            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { networkCap }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))
            testDataService.collectionInfo.put(
                cloudProvider,
                CollectionInfo(
                    authority = cloudProvider.authority,
                    collectionId = "collection-id",
                    accountName = "abc@xyz.com",
                    accountConfigurationIntent = Intent(),
                ),
            )

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_cloud_media_available_title)
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_banner_cloud_media_available_message,
                    cloudProvider.displayName,
                    "abc@xyz.com",
                )

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            composeTestRule.waitForIdle()
            advanceTimeBy(500)
            composeTestRule.waitForIdle()
            advanceTimeBy(500)
            composeTestRule.onNode(hasText(expectedTitle)).assertIsDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsDisplayed()
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudMediaAvailableBannerAsDismissed() =
        testScope.runTest {
            val bannerStateDao = databaseManager.acquireDao(BannerStateDao::class.java)

            // Treat privacy explainer as already dismissed since it's a higher priority.
            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                    dismissed = true,
                    uid = 12345,
                )
            }
            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.CLOUD_MEDIA_AVAILABLE.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.CLOUD_MEDIA_AVAILABLE.id,
                    dismissed = true,
                    uid = 12345,
                )
            }
            val networkCap: NetworkCapabilities =
                NetworkCapabilities.Builder()
                    .apply { addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                    .build()

            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { networkCap }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))
            testDataService.collectionInfo.put(
                cloudProvider,
                CollectionInfo(
                    authority = cloudProvider.authority,
                    collectionId = "collection-id",
                    accountName = "abc@xyz.com",
                    accountConfigurationIntent = Intent(),
                ),
            )

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_cloud_media_available_title)
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_banner_cloud_media_available_message,
                    cloudProvider.displayName,
                    "abc@xyz.com",
                )

            advanceTimeBy(100)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            composeTestRule.waitForIdle()
            advanceTimeBy(500)
            composeTestRule.waitForIdle()
            advanceTimeBy(500)
            composeTestRule.onNode(hasText(expectedTitle)).assertIsNotDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsNotDisplayed()
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudChooseAccountBanner() =
        testScope.runTest {
            val bannerStateDao = databaseManager.acquireDao(BannerStateDao::class.java)

            // Treat privacy explainer as already dismissed since it's a higher priority.
            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                    dismissed = true,
                    uid = 12345,
                )
            }

            val networkCap: NetworkCapabilities =
                NetworkCapabilities.Builder()
                    .apply { addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                    .build()

            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { networkCap }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))
            testDataService.collectionInfo.put(
                cloudProvider,
                CollectionInfo(
                    authority = cloudProvider.authority,
                    collectionId = null,
                    accountName = null,
                    accountConfigurationIntent = Intent(),
                ),
            )

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(
                    R.string.photopicker_banner_cloud_choose_account_title,
                    cloudProvider.displayName,
                )
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_banner_cloud_choose_account_message,
                    cloudProvider.displayName,
                )

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(500)
            composeTestRule.waitForIdle()
            advanceTimeBy(500)
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasText(expectedTitle)).assertIsDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsDisplayed()
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudChooseAccountBannerAsDismissed() =
        testScope.runTest {
            val bannerStateDao = databaseManager.acquireDao(BannerStateDao::class.java)

            // Treat privacy explainer as already dismissed since it's a higher priority.
            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                    dismissed = true,
                    uid = 12345,
                )
            }

            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.CLOUD_CHOOSE_ACCOUNT.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.CLOUD_CHOOSE_ACCOUNT.id,
                    dismissed = true,
                    uid = 12345,
                )
            }

            val networkCap: NetworkCapabilities =
                NetworkCapabilities.Builder()
                    .apply { addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                    .build()

            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { networkCap }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))
            testDataService.collectionInfo.put(
                cloudProvider,
                CollectionInfo(
                    authority = cloudProvider.authority,
                    collectionId = null,
                    accountName = null,
                    accountConfigurationIntent = Intent(),
                ),
            )

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_cloud_choose_account_title)
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_banner_cloud_choose_account_message,
                    cloudProvider.displayName,
                )

            advanceTimeBy(100)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasText(expectedTitle)).assertIsNotDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsNotDisplayed()
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudChooseProviderBanner() =
        testScope.runTest {
            val bannerStateDao = databaseManager.acquireDao(BannerStateDao::class.java)

            // Treat privacy explainer as already dismissed since it's a higher priority.
            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                    dismissed = true,
                    uid = 12345,
                )
            }

            val networkCap: NetworkCapabilities =
                NetworkCapabilities.Builder()
                    .apply { addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                    .build()

            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { networkCap }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.allowedProviders = listOf(cloudProvider)
            testDataService.setAvailableProviders(listOf(localProvider))

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_cloud_choose_provider_title)
            val expectedMessage =
                resources.getString(R.string.photopicker_banner_cloud_choose_provider_message)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(500)
            composeTestRule.waitForIdle()
            advanceTimeBy(500)
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasText(expectedTitle)).assertIsDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsDisplayed()
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudChooseProviderBannerAsDismissed() =
        testScope.runTest {
            val bannerStateDao = databaseManager.acquireDao(BannerStateDao::class.java)

            // Treat privacy explainer as already dismissed since it's a higher priority.
            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.PRIVACY_EXPLAINER.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.PRIVACY_EXPLAINER.id,
                    dismissed = true,
                    uid = 12345,
                )
            }

            whenever(
                bannerStateDao.getBannerState(
                    nonNullableEq(BannerDefinitions.CLOUD_CHOOSE_PROVIDER.id),
                    anyInt(),
                )
            ) {
                BannerState(
                    bannerId = BannerDefinitions.CLOUD_CHOOSE_PROVIDER.id,
                    dismissed = true,
                    uid = 12345,
                )
            }

            val networkCap: NetworkCapabilities =
                NetworkCapabilities.Builder()
                    .apply { addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                    .build()

            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { networkCap }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.allowedProviders = listOf(cloudProvider)
            testDataService.setAvailableProviders(listOf(localProvider))

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_cloud_choose_provider_title)
            val expectedMessage =
                resources.getString(R.string.photopicker_banner_cloud_choose_provider_message)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasText(expectedTitle)).assertIsNotDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsNotDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_OFFLINE_BANNERS)
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testNetworkUnavailable_whenFlagEnabled_bannerIsShown() =
        testScope.runTest {
            // No network available
            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { null }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_no_network_connection_title)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()

            composeTestRule.onNode(hasText(expectedTitle)).assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_OFFLINE_BANNERS)
    fun testNetworkAvailable_whenFlagEnabled_bannerNotShown() =
        testScope.runTest {
            val networkCap: NetworkCapabilities =
                NetworkCapabilities.Builder()
                    .apply { addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
                    .build()

            // network available
            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { networkCap }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_no_network_connection_title)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()

            composeTestRule.onNode(hasText(expectedTitle)).assertIsNotDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_OFFLINE_BANNERS)
    fun testNetworkUnavailable_whenFlagEnabledAndNoCloudProvider_offlineBannerNotDisplayed() =
        testScope.runTest {
            // No network available
            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { null }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(emptyList())

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_no_network_connection_title)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()

            composeTestRule.onNode(hasText(expectedTitle)).assertIsNotDisplayed()
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_OFFLINE_BANNERS)
    fun testNetworkUnavailable_whenFlagDisabled_bannerNotDisplayed() =
        testScope.runTest {
            // No network available
            whenever(mockConnectivityManager.getNetworkCapabilities(any())) { null }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_no_network_connection_title)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()

            composeTestRule.onNode(hasText(expectedTitle)).assertIsNotDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudFeatureOwnedBanners_withBannerRedesignEnabled() {
        testScope.runTest {
            val feature = CloudMediaFeature()
            val bannerDefinitions = feature.ownedBannersDefinitions
            assertThat(bannerDefinitions).hasSize(3)
            assertThat(bannerDefinitions).contains(BannerDefinition.CLOUD_MEDIA_AVAILABLE)
            assertThat(bannerDefinitions).contains(BannerDefinition.CLOUD_CHOOSE_ACCOUNT)
            assertThat(bannerDefinitions).contains(BannerDefinition.CLOUD_CHOOSE_PROVIDER)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudFeatureBannerPriority_withBannerRedesignEnabled() {
        testScope.runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    TestDeviceConfigProxyImpl(),
                    generatePickerSessionId(),
                )
            val userMonitor =
                UserMonitor(
                    mockContext,
                    provideTestConfigurationFlow(
                        scope = this.backgroundScope,
                        defaultConfiguration =
                            TestPhotopickerConfiguration.build {
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ),
                    this.backgroundScope,
                    StandardTestDispatcher(this.testScheduler),
                    USER_HANDLE_PRIMARY,
                )
            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.allowedProviders = listOf(cloudProvider)
            testDataService.setAvailableProviders(listOf(localProvider))
            val feature = CloudMediaFeature()
            val chooseProviderPriority =
                feature.getBannerPriority(
                    BannerDefinition.CLOUD_CHOOSE_PROVIDER,
                    bannerInteractionState = null,
                    config = configurationManager.configuration.value,
                    dataService = testDataService,
                    userMonitor = userMonitor,
                    bannerLocation = BannerLocation.PHOTO_GRID_BANNER,
                )
            assertThat(chooseProviderPriority).isEqualTo(Priority.MEDIUM.priority)

            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))
            testDataService.collectionInfo.put(
                cloudProvider,
                CollectionInfo(
                    authority = cloudProvider.authority,
                    collectionId = null,
                    accountName = null,
                    accountConfigurationIntent = Intent(),
                ),
            )

            val chooseAccountPriority =
                feature.getBannerPriority(
                    BannerDefinition.CLOUD_CHOOSE_ACCOUNT,
                    bannerInteractionState = null,
                    config = configurationManager.configuration.value,
                    dataService = testDataService,
                    userMonitor = userMonitor,
                    bannerLocation = BannerLocation.PHOTO_GRID_BANNER,
                )
            assertThat(chooseAccountPriority).isEqualTo(Priority.MEDIUM.priority)

            testDataService.collectionInfo.put(
                cloudProvider,
                CollectionInfo(
                    authority = cloudProvider.authority,
                    collectionId = "collection-id",
                    accountName = "abc@xyz.com",
                    accountConfigurationIntent = Intent(),
                ),
            )

            val cloudMediaAvailablePriority =
                feature.getBannerPriority(
                    BannerDefinition.CLOUD_MEDIA_AVAILABLE,
                    bannerInteractionState = null,
                    config = configurationManager.configuration.value,
                    dataService = testDataService,
                    userMonitor = userMonitor,
                    bannerLocation = BannerLocation.PHOTO_GRID_BANNER,
                )
            assertThat(cloudMediaAvailablePriority).isEqualTo(Priority.MEDIUM.priority)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudMediaAvailableBanner_withBannerRedesign_Enabled() =
        testScope.runTest {
            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))
            testDataService.collectionInfo.put(
                cloudProvider,
                CollectionInfo(
                    authority = cloudProvider.authority,
                    collectionId = "collection-id",
                    accountName = "abc@xyz.com",
                    accountConfigurationIntent = Intent(),
                ),
            )

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(
                    R.string.photopicker_banner_backed_up_cloud_media_available_title
                )
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_banner_cloud_backed_up_media_available_message,
                    "abc@xyz.com",
                    cloudProvider.displayName,
                )

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            advanceUntilIdle()
            composeTestRule.onNode(hasText(expectedTitle)).assertIsDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudChooseAccountBanner_withBannerRedesign_Enabled() =
        testScope.runTest {
            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))
            testDataService.collectionInfo.put(
                cloudProvider,
                CollectionInfo(
                    authority = cloudProvider.authority,
                    collectionId = null,
                    accountName = null,
                    accountConfigurationIntent = Intent(),
                ),
            )

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_cloud_select_account_title)
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_banner_cloud_select_account_message,
                    cloudProvider.displayName,
                )

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceUntilIdle()
            composeTestRule.onNode(hasText(expectedTitle)).assertIsDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudChooseProviderBanner_withBannerRedesign_Enabled() =
        testScope.runTest {
            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.allowedProviders = listOf(cloudProvider)
            testDataService.setAvailableProviders(emptyList())

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_cloud_choose_media_app_title)
            val expectedMessage =
                resources.getString(R.string.photopicker_banner_cloud_choose_media_app_message)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceUntilIdle()
            composeTestRule.onNode(hasText(expectedTitle)).assertIsDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testCloudMediaAvailableBanner_notShownWhenAlreadyDismissed_withBannerRedesignEnabled() =
        testScope.runTest {
            val bannerInteractionStateDao =
                databaseManager.acquireDao(BannerInteractionStateDao::class.java)

            // Set show count to max limit to verify autodismissal
            whenever(
                bannerInteractionStateDao.getBannerInteractionStates(
                    12345,
                    "com.android.test.package",
                )
            ) {
                listOf(
                    BannerInteractionState(
                        bannerId = BannerDefinition.CLOUD_MEDIA_AVAILABLE,
                        isDismissed = true,
                        shownCount = 1,
                        appUid = 0,
                        packageName = "system",
                    )
                )
            }

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(listOf(localProvider, cloudProvider))
            testDataService.collectionInfo.put(
                cloudProvider,
                CollectionInfo(
                    authority = cloudProvider.authority,
                    collectionId = "collection-id",
                    accountName = "abc@xyz.com",
                    accountConfigurationIntent = Intent(),
                ),
            )

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(
                    R.string.photopicker_banner_backed_up_cloud_media_available_title
                )
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_banner_cloud_backed_up_media_available_message,
                    cloudProvider.displayName,
                    "abc@xyz.com",
                )

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
            testBackgroundDispatcher.scheduler.advanceUntilIdle()
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)

            testBackgroundDispatcher.scheduler.advanceUntilIdle()

            composeTestRule.onNode(hasText(expectedTitle)).assertIsNotDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsNotDisplayed()
        }
}
