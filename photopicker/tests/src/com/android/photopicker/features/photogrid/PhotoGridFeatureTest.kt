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

package com.android.photopicker.features.photogrid

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import android.widget.photopicker.PhotoPickerSelectionParams
import android.widget.photopicker.PhotoPickerUiCustomizationParams
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.filters.SdkSuppress
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.banners.BannerLocation
import com.android.photopicker.core.banners.BannerManager
import com.android.photopicker.core.banners.BannerStateDao
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.database.DatabaseManager
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.MockContentProviderWrapper
import com.android.photopicker.util.test.dragInIncrements
import com.android.photopicker.util.test.mockSystemService
import com.android.photopicker.util.test.whenever
import com.android.providers.media.flags.Flags
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
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class PhotoGridFeatureTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()
    @get:Rule(order = 3) var setFlagsRule = SetFlagsRule()
    @get:Rule(order = 4)
    val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    /* Setup dependencies for the UninstallModules for the test class. */
    @Module @InstallIn(SingletonComponent::class) class TestModule : PhotopickerTestModule()

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

    private val MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING = "taken on"

    val sessionId = generatePickerSessionId()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        hiltRule.inject()

        // Stub for MockContentResolver constructor
        whenever(mockContext.getApplicationInfo()) { getTestableContext().getApplicationInfo() }

        // Stub out the content resolver for Glide
        val mockContentResolver = MockContentResolver(mockContext)
        provider = MockContentProviderWrapper(mockContentProvider)
        mockContentResolver.addProvider(MockContentProviderWrapper.AUTHORITY, provider)
        contentResolver = mockContentResolver

        // Return a resource png so that glide actually has something to load
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any(), any())) {
            getTestableContext().getResources().openRawResourceFd(R.drawable.android)
        }
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
        mockSystemService(mockContext, ConnectivityManager::class.java) { mockConnectivityManager }
    }

    @Test
    fun testPhotoGridIsAlwaysEnabled() {
        val configOne = PhotopickerConfiguration(action = "TEST_ACTION", sessionId = sessionId)
        assertWithMessage("PhotoGridFeature is not always enabled for TEST_ACTION")
            .that(PhotoGridFeature.Registration.isEnabled(configOne))
            .isEqualTo(true)

        val configTwo =
            PhotopickerConfiguration(action = MediaStore.ACTION_PICK_IMAGES, sessionId = sessionId)
        assertWithMessage("PhotoGridFeature is not always enabled")
            .that(PhotoGridFeature.Registration.isEnabled(configTwo))
            .isEqualTo(true)

        val configThree =
            PhotopickerConfiguration(action = Intent.ACTION_GET_CONTENT, sessionId = sessionId)
        assertWithMessage("PhotoGridFeature is not always enabled")
            .that(PhotoGridFeature.Registration.isEnabled(configThree))
            .isEqualTo(true)
    }

    @Test
    fun testPhotoGridIsTheInitialRoute() {
        // Explicitly create a new feature manager that uses the same production feature
        // registrations to ensure this test will fail if the default production behavior changes.
        val featureManager =
            FeatureManager(
                registeredFeatures = FeatureManager.KNOWN_FEATURE_REGISTRATIONS,
                scope = testBackgroundScope,
                prefetchDataService = TestPrefetchDataService(),
                configuration = provideTestConfigurationFlow(scope = testBackgroundScope),
            )

        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            advanceUntilIdle()

            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Initial route is not the PhotoGridFeature")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
        }
    }

    @Test
    fun testPhotosCanBeSelected() {
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            assertWithMessage("Expected selection to initially be empty.")
                .that(selection.get().snapshot().size)
                .isEqualTo(0)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performClick()

            // Wait for PhotoGridViewModel to modify Selection
            advanceTimeBy(100)

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.get().snapshot().size)
                .isEqualTo(1)
        }
    }

    @Test
    fun testPhotosAreDisplayed() {
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .assert(hasClickAction())
                .assertIsDisplayed()
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSwipeLeftToNavigateToAlbumGrid_searchFlagOff() {
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performTouchInput { swipeLeft() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to AlbumGrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSwipeLeftToNavigateToCategoryGrid_searchFlagOn() {
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performTouchInput { swipeLeft() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to Album Grid for category")
                .that(route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)
        }
    }

    @Test
    fun testShowsEmptyStateWhenEmpty() {

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.mediaSetSize = 0

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    fun testShowsEmptyStateWhenEmpty_VideoOnlyMimeType() {

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.mediaSetSize = 0

        val resources = getTestableContext().getResources()

        testScope.runTest {
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayListOf("video/*", "video/mpeg"))
                }
            configurationManager.get().setIntent(testIntent)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_videos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testShowsBannersInGrid() {

        testScope.runTest {
            val bannerStateDao = databaseManager.get().acquireDao(BannerStateDao::class.java)
            whenever(bannerStateDao.getBannerState(anyString(), anyInt())) { null }

            configurationManager
                .get()
                .setCaller(
                    callingPackage = "com.android.test.package",
                    callingPackageUid = 12345,
                    callingPackageLabel = "Test Package",
                )

            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(100)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            val resources = getTestableContext().getResources()
            val expectedPrivacyMessage =
                resources.getString(R.string.photopicker_privacy_explainer, "Test Package")

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule.onNode(hasText(expectedPrivacyMessage)).assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testShowsBannersInGrid_withBannerRedesignEnabled() {

        testScope.runTest {
            val bannerStateDao = databaseManager.get().acquireDao(BannerStateDao::class.java)
            whenever(bannerStateDao.getBannerState(anyString(), anyInt())) { null }

            configurationManager
                .get()
                .setCaller(
                    callingPackage = "com.android.test.package",
                    callingPackageUid = 12345,
                    callingPackageLabel = "Test Package",
                )

            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(100)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            val resources = getTestableContext().getResources()
            val expectedPrivacyMessage =
                resources.getString(R.string.photopicker_privacy_explainer_message, "Test Package")

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule.onNode(hasText(expectedPrivacyMessage)).assertIsDisplayed()
        }
    }

    @Test
    fun testPhotoGridDragSelect() =
        testScope.runTest {

            // Update configuration to support multi-select. Use a high limit to avoid capping.
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
                }
            configurationManager.get().setIntent(testIntent)
            advanceTimeBy(100)

            // Ensure banners are ready before starting UI.
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(100)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val allPhotosMatcher =
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )

            // Wait until photos are loaded.
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodes(allPhotosMatcher).fetchSemanticsNodes().isNotEmpty()
            }

            val allPhotos = composeTestRule.onAllNodes(allPhotosMatcher)

            // Using getBoundsInRoot() on SemanticsNodeInteraction returns DpRect, so we need
            // density to convert to pixels for comparison with SemanticsNode.boundsInRoot (which is
            // in pixels).
            val firstPhotoBounds = allPhotos.onFirst().getBoundsInRoot()
            val firstPhotoTopPx = with(composeTestRule.density) { firstPhotoBounds.top.toPx() }
            val columns =
                allPhotos.fetchSemanticsNodes(atLeastOneRootRequired = true).count {
                    // An item is in the first row if its top y-coordinate is about the same as the
                    // first item. A small tolerance is used for floating point comparisons.
                    (it.boundsInRoot.top - firstPhotoTopPx).absoluteValue < 1f
                }

            val rootBounds = composeTestRule.onRoot().getBoundsInRoot()
            val screenWidthPx =
                with(composeTestRule.density) { (rootBounds.right - rootBounds.left).toPx() }

            val firstPhoto = allPhotos.onFirst()

            with(firstPhoto) {
                assertIsDisplayed()
                performTouchInput {
                    down(center)
                    // Wait for the long press to register to enable drag-to-select.
                    advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                    dragInIncrements(
                        // Drag across the screen to select all items in the first row.
                        totalOffset = screenWidthPx,
                        vertical = false,
                    )
                    // Wait for the scroll to finish.
                    advanceEventTime(1000)
                    up()
                }
            }

            advanceTimeBy(1000)
            composeTestRule.waitForIdle()

            assertWithMessage(
                    "Expected $columns items in selection, but found ${selection.get().size()}"
                )
                .that(selection.get().size())
                .isEqualTo(columns)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    fun testPhotoGridDragSelect_skipsDisabledItems() =
        testScope.runTest {
            val maxFileSize = SIZE_100KB
            val selectionParams =
                PhotoPickerSelectionParams.Builder().setMaxMediaItemSizeInBytes(maxFileSize).build()

            // 1st item: enabled
            // 2nd item: disabled
            // 3rd item: enabled
            val mediaList =
                listOf(
                    createImage(
                        mediaId = "1",
                        pickerId = 1L,
                        selectionParams = selectionParams,
                        sizeInBytes = maxFileSize,
                    ),
                    createImage(
                        mediaId = "2",
                        pickerId = 2L,
                        selectionParams = selectionParams,
                        sizeInBytes = 2 * maxFileSize,
                    ),
                    createImage(
                        mediaId = "3",
                        pickerId = 3L,
                        selectionParams = selectionParams,
                        sizeInBytes = maxFileSize,
                    ),
                )

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.mediaList = mediaList

            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)
                }
            configurationManager.get().setIntent(testIntent)
            configurationManager.get().setCaller("com.android.test", 123, TEST_APP_LABEL)
            advanceTimeBy(100)

            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val allPhotosMatcher =
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )

            val rootBounds = composeTestRule.onRoot().getBoundsInRoot()
            val screenWidthPx =
                with(composeTestRule.density) { (rootBounds.right - rootBounds.left).toPx() }

            // Start drag on the first photo
            composeTestRule.onAllNodes(allPhotosMatcher).onFirst().performTouchInput {
                down(center)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                // Drag across the screen to select items in the first row.
                dragInIncrements(totalOffset = screenWidthPx, vertical = false)
                advanceEventTime(1000)
                up()
            }

            advanceTimeBy(1000)
            composeTestRule.waitForIdle()

            // Verify that items 1 and 3 are selected, but 2 is not.
            val selectedItems = selection.get().snapshot()
            assertWithMessage("Expected 2 items in selection").that(selectedItems.size).isEqualTo(2)

            assertWithMessage("Item 2 should not be selected")
                .that(selectedItems.any { it.mediaId == "2" })
                .isFalse()

            assertWithMessage("Item 1 should be selected")
                .that(selectedItems.any { it.mediaId == "1" })
                .isTrue()

            assertWithMessage("Item 3 should be selected")
                .that(selectedItems.any { it.mediaId == "3" })
                .isTrue()
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSwipeRightToNavigateToAlbumGridInRtl_searchFlagOff() {
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    callPhotopickerMain(
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performTouchInput { swipeRight() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to AlbumGrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSwipeRightToNavigateToCategoryGridInRtl_searchFlagOn() {
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    callPhotopickerMain(
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performTouchInput { swipeRight() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to Album Grid for category")
                .that(route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)
        }
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
    )
    fun testPhotoGrid_withDefaultAspectRatio_displaysSquareThumbnail() {
        testScope.runTest {
            // No UI params set, should use default 1:1
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val mediaItem =
                composeTestRule
                    .onAllNodes(
                        hasContentDescription(
                            MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                            substring = true,
                        )
                    )
                    .onFirst()
            mediaItem.assertExists()

            val size = mediaItem.fetchSemanticsNode().size
            val ratio = size.width.toFloat() / size.height.toFloat()
            assertWithMessage("Default aspect ratio should be 1:1")
                .that(ratio)
                .isWithin(0.05f)
                .of(1f)
        }
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
    )
    fun testPhotoGrid_withPortraitAspectRatio_displaysPortraitThumbnail() {
        testScope.runTest {
            val uiParams =
                PhotoPickerUiCustomizationParams.Builder()
                    .setAspectRatio(PhotoPickerUiCustomizationParams.ASPECT_RATIO_PORTRAIT_9_16)
                    .build()
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_UI_CUSTOMIZATION_PARAMS, uiParams)
                }
            configurationManager.get().setIntent(testIntent)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val mediaItem =
                composeTestRule
                    .onAllNodes(
                        hasContentDescription(
                            MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                            substring = true,
                        )
                    )
                    .onFirst()
            mediaItem.assertExists()

            val size = mediaItem.fetchSemanticsNode().size
            val ratio = size.width.toFloat() / size.height.toFloat()
            assertWithMessage("Aspect ratio should be 9:16")
                .that(ratio)
                .isWithin(0.05f)
                .of(9f / 16f)
        }
    }

    @Test
    @DisableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
    )
    fun testPhotoGrid_withUiCustomizationParams_isIgnoredIfFlagDisabled() {
        testScope.runTest {
            val uiParams =
                PhotoPickerUiCustomizationParams.Builder()
                    .setAspectRatio(PhotoPickerUiCustomizationParams.ASPECT_RATIO_PORTRAIT_9_16)
                    .build()
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_UI_CUSTOMIZATION_PARAMS, uiParams)
                }
            configurationManager.get().setIntent(testIntent)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val mediaItem =
                composeTestRule
                    .onAllNodes(
                        hasContentDescription(
                            value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                            substring = true,
                        )
                    )
                    .onFirst()
                    .assert(hasClickAction())
                    .assertIsDisplayed()
            mediaItem.assertExists()

            val size = mediaItem.fetchSemanticsNode().size
            val ratio = size.width.toFloat() / size.height.toFloat()
            assertWithMessage("Aspect ratio should be 1:1 when flag is disabled")
                .that(ratio)
                .isWithin(0.05f)
                .of(1f)
        }
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    fun testPhotoWithDisabledReasonCannotBeSelected() {
        val maxFileSize = SIZE_100KB
        val selectionParams =
            PhotoPickerSelectionParams.Builder().setMaxMediaItemSizeInBytes(maxFileSize).build()
        val mediaWithDisabledReason =
            createImage(
                mediaId = "1",
                pickerId = 1L,
                selectionParams = selectionParams,
                sizeInBytes = 2 * maxFileSize,
            )

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        testDataService.mediaList = listOf(mediaWithDisabledReason)

        // Set the selection params in the configuration
        configurationManager.get().setCaller("com.android.test", 123, TEST_APP_LABEL)

        testScope.runTest {
            val intent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)
                }
            configurationManager.get().setIntent(intent)

            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .performClick()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler did NOT update the selection.
            assertWithMessage("Expected selection to be empty as item has disabled reason.")
                .that(selection.get().snapshot().size)
                .isEqualTo(0)

            val resources = getTestableContext().resources
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_selection_max_media_item_size_error_kb,
                    TEST_APP_LABEL,
                    maxFileSize / 1024,
                )

            assertSnackbarIsShown(expectedMessage, composeTestRule)
        }
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    fun testPhotoGridItemCannotBeSelectedWhenBatchSizeLimitIsExceeded() {
        val maxBatchSize = 2 * SIZE_100KB
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMaxSelectionBatchSizeInBytes(maxBatchSize)
                .build()

        val item1 =
            createImage(
                mediaId = "1",
                pickerId = 1L,
                selectionParams = selectionParams,
                sizeInBytes = SIZE_100KB,
            )
        val item2 =
            createImage(
                mediaId = "2",
                pickerId = 2L,
                selectionParams = selectionParams,
                sizeInBytes = SIZE_100KB + 1,
            )

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        testDataService.mediaList = listOf(item1, item2)

        // Set the selection params in the configuration
        configurationManager.get().setCaller("com.android.test", 123, TEST_APP_LABEL)

        testScope.runTest {
            val intent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)
                }
            configurationManager.get().setIntent(intent)

            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val allPhotosMatcher =
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )

            // Select first item
            composeTestRule.onAllNodes(allPhotosMatcher).onFirst().performClick()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected selection to contain 1 item.")
                .that(selection.get().snapshot().size)
                .isEqualTo(1)

            // Select second item (should fail)
            composeTestRule.onAllNodes(allPhotosMatcher)[1].performClick()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler did NOT update the selection for the second item.
            assertWithMessage("Expected selection to still contain 1 item.")
                .that(selection.get().snapshot().size)
                .isEqualTo(1)

            val resources = getTestableContext().resources
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_selection_max_selection_batch_size_error_kb,
                    TEST_APP_LABEL,
                    maxBatchSize / 1024,
                )

            assertSnackbarIsShown(expectedMessage, composeTestRule)
        }
    }
}
