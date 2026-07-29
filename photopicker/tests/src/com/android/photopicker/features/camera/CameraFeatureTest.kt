/*
 * Copyright 2026 The Android Open Source Project
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

package com.android.photopicker.features.camera

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.UserManager
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerFlags
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.database.DatabaseManager
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.navigateToCamera
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.inject.TestOptions
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.MockContentProviderWrapper
import com.android.photopicker.util.test.mockSystemService
import com.android.photopicker.util.test.whenever
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertThat
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    EmbeddedServiceModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@EnableFlags()
class CameraFeatureTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()
    @get:Rule(order = 3) var setFlagsRule = SetFlagsRule()
    @get:Rule(order = 4)
    val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module
    @InstallIn(SingletonComponent::class)
    class TestModule :
        PhotopickerTestModule(
            TestOptions.Builder().runtimeEnv(PhotopickerRuntimeEnv.ACTIVITY).build()
        )

    val testDispatcher = StandardTestDispatcher()

    /* Overrides for ActivityModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope

    /* Overrides for ViewModelModule */
    @BindValue val viewModelScopeOverride: CoroutineScope? = testScope.backgroundScope

    /* Overrides for the ConcurrencyModule */
    @BindValue @Main val mainDispatcher: CoroutineDispatcher = testDispatcher
    @BindValue @Background val backgroundDispatcher: CoroutineDispatcher = testDispatcher

    /**
     * Preview uses Glide for loading images, so we have to mock out the dependencies for Glide
     * Replace the injected ContentResolver binding in [ApplicationModule] with this test value.
     */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper
    @Mock lateinit var mockContentProvider: ContentProvider

    // Needed for UserMonitor
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    @Mock lateinit var mockConnectivityManager: ConnectivityManager

    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    @Inject lateinit var mockContext: Context
    @Inject lateinit var selection: Lazy<Selection<Media>>
    @Inject lateinit var featureManager: Lazy<FeatureManager>
    @Inject lateinit var events: Lazy<Events>
    @Inject lateinit var bannerManager: Lazy<BannerManager>
    @Inject lateinit var dataService: Lazy<DataService>
    @Inject lateinit var databaseManager: Lazy<DatabaseManager>

    val sessionId = generatePickerSessionId()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        hiltRule.inject()

        // Stub for MockContentResolver constructor
        whenever(mockContext.getApplicationInfo()) { getTestableContext().getApplicationInfo() }

        // Stub out the content resolver
        val mockContentResolver = MockContentResolver(mockContext)
        provider = MockContentProviderWrapper(mockContentProvider)
        mockContentResolver.addProvider(MockContentProviderWrapper.AUTHORITY, provider)
        contentResolver = mockContentResolver

        // Mock system services
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
        mockSystemService(mockContext, ConnectivityManager::class.java) { mockConnectivityManager }

        // Create NavController
        navController = createNavController()

        // Set intent in photopicker configuration
        val testIntent = Intent(MediaStore.ACTION_PICK_IMAGES)
        configurationManager.get().setIntent(testIntent)
    }

    @Test
    fun testCameraFeatureIsEnabled_withEligibleConfig() {
        val testConfig =
            PhotopickerConfiguration(
                runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                action = MediaStore.ACTION_PICK_IMAGES,
                flags = PhotopickerFlags(POLAROID_ENABLED = true),
                sessionId = 1234,
            )
        val result = CameraFeature.Registration.isEnabled(testConfig, emptyMap())
        assertThat(result).isTrue()
    }

    @Test
    fun testCameraFeatureIsEnabled_withGetContentAction() {
        val testConfig =
            PhotopickerConfiguration(
                runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                action = Intent.ACTION_GET_CONTENT,
                flags = PhotopickerFlags(POLAROID_ENABLED = true),
                sessionId = 1234,
            )
        val result = CameraFeature.Registration.isEnabled(testConfig, emptyMap())
        assertThat(result).isTrue()
    }

    @Test
    fun testCameraFeatureIsDisabled_whenWrongRuntime() {
        val testConfig =
            PhotopickerConfiguration(
                runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                action = MediaStore.ACTION_PICK_IMAGES,
                flags = PhotopickerFlags(POLAROID_ENABLED = true),
                sessionId = 1234,
            )
        val result = CameraFeature.Registration.isEnabled(testConfig, emptyMap())
        assertThat(result).isFalse()
    }

    @Test
    fun testCameraFeatureIsDisabled_whenWrongAction() {
        val testConfig =
            PhotopickerConfiguration(
                runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                action = MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP,
                flags = PhotopickerFlags(POLAROID_ENABLED = true),
                sessionId = 1234,
            )
        val result = CameraFeature.Registration.isEnabled(testConfig, emptyMap())
        assertThat(result).isFalse()
    }

    @Test
    fun testCameraFeatureIsDisabled_whenFlagIsDisabled() {
        val testConfig =
            PhotopickerConfiguration(
                runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                action = MediaStore.ACTION_PICK_IMAGES,
                flags = PhotopickerFlags(POLAROID_ENABLED = false),
                sessionId = 1234,
            )
        val result = CameraFeature.Registration.isEnabled(testConfig, emptyMap())
        assertThat(result).isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_PHOTOPICKER_POLAROID)
    fun testNavigateToCamera() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    navController = navController,
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            advanceTimeBy(100)

            // Default start destination is PHOTO_GRID
            assertThat(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)

            val previousBackstackSize = navController.backStack.size

            // Navigate to Camera route, which should add the route to the backstack
            composeTestRule.runOnUiThread { navController.navigateToCamera() }
            composeTestRule.waitForIdle()

            assertThat(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.CAMERA.route)
            assertThat(navController.backStack.size).isEqualTo(previousBackstackSize + 1)
        }

    @Test
    @EnableFlags(Flags.FLAG_PHOTOPICKER_POLAROID)
    fun testNavigateToCamera_alreadyOnCamera() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    navController = navController,
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            advanceTimeBy(100)

            composeTestRule.runOnUiThread {
                navController.navigate(PhotopickerDestinations.CAMERA.route)
            }
            composeTestRule.waitForIdle()
            assertThat(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.CAMERA.route)

            val backStackSizeBefore = navController.backStack.size

            composeTestRule.runOnUiThread { navController.navigateToCamera() }
            composeTestRule.waitForIdle()

            assertThat(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.CAMERA.route)
            assertThat(navController.backStack.size).isEqualTo(backStackSizeBefore)
        }
}
