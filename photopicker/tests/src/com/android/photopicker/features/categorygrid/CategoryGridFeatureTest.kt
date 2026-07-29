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

package com.android.photopicker.features.categorygrid

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import android.widget.photopicker.PhotoPickerSelectionParams
import android.widget.photopicker.PhotoPickerUiCustomizationParams
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import com.android.photopicker.core.components.MediaGridItem
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.CategoryType
import com.android.photopicker.data.model.GlideIcon
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Icon
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.paging.FakeInMemoryAlbumPagingSource
import com.android.photopicker.data.paging.FakeInMemoryCategoryPagingSource.Companion.TEST_ALBUM_NAME_PREFIX
import com.android.photopicker.extensions.navigateToAlbumMediaGridForCategories
import com.android.photopicker.extensions.navigateToCategoryGrid
import com.android.photopicker.extensions.navigateToMediaSetContentGrid
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.features.categorygrid.categoryIcon.IconGrid
import com.android.photopicker.features.categorygrid.data.CategoryDataService
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
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.MockitoAnnotations
import src.com.android.photopicker.features.categorygrid.data.TestCategoryDataServiceImpl

@UninstallModules(
    ActivityModule::class,
    EmbeddedServiceModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class CategoryGridFeatureTest : PhotopickerFeatureBaseTest() {

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

    @Inject lateinit var mockContext: Context
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var events: Events
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    @Inject lateinit var dataService: DataService
    @Inject lateinit var categoryDataService: CategoryDataService

    private val MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING = "taken on"

    private val BADGE_ICON = Icon(Icons.Outlined.FolderCopy)
    private val BADGE_TEST_TAG = "badge_overlay_icon"
    private val MEDIA_SET_NAME = "My Folder"

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
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testCategoryGridIsEnabledWhenSearchFlagOn() {
        assertWithMessage("CategoryGridFeature is not enabled for TEST_ACTION")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("CategoryGridFeature is not enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("CategoryGridFeature is not enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("AlbumGridFeature is not enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                        intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                        callingPackage("com.example.test")
                        callingPackageUid(1234)
                        callingPackageLabel("test_app")
                    }
                )
            )
            .isEqualTo(true)
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testCategoryGridIsDisabledWhenSearchFlagOff() {
        assertWithMessage("CategoryGridFeature is enabled for TEST_ACTION")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("CategoryGridFeature is enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("CategoryGridFeature is enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("AlbumGridFeature is enabled")
            .that(
                CategoryGridFeature.Registration.isEnabled(
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
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigateCategoryGridAndAlbumsAreVisible() =
        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            // In the [FakeInMemoryPagingSource] the albums are names using TEST_ALBUM_NAME_PREFIX
            // appended by a count in their sequence. Verify that an album with the name exists
            composeTestRule
                .onNode(hasText(TEST_ALBUM_NAME_PREFIX + "1"))
                .assert(hasClickAction())
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testConsistentCategoryFocus() =
        testScope.runTest {
            val dataList =
                buildList<Group.Category> {
                    for (i in 1..3) {
                        add(
                            Group.Category(
                                id = "$i",
                                pickerId = i.toLong(),
                                authority = "a",
                                displayName =
                                    FakeInMemoryAlbumPagingSource.Companion.TEST_ALBUM_NAME_PREFIX +
                                        "$i",
                                categoryType = CategoryType.PEOPLE_AND_PETS,
                                icons = emptyList(),
                                isLeafCategory = true,
                                badge = null,
                            )
                        )
                    }
                }

            val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
            checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
            testCategoryDataService.categoryAlbumList = dataList

            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // wait for the composition to finish
            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            composeTestRule.waitForIdle()

            // wait for the album grid to show up
            advanceTimeBy(100)

            val allAlbumNodes =
                composeTestRule.onAllNodes(
                    hasText(
                        text = FakeInMemoryAlbumPagingSource.Companion.TEST_ALBUM_NAME_PREFIX,
                        substring = true,
                    )
                )

            allAlbumNodes[0].assert(hasClickAction()).assertIsDisplayed().performClick()

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule.waitForIdle()

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // wait for the album grid to show up
            advanceTimeBy(150)

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                try {
                    composeTestRule
                        .onNode(
                            hasText(
                                FakeInMemoryAlbumPagingSource.Companion.TEST_ALBUM_NAME_PREFIX +
                                    "1",
                                substring = true,
                            )
                        )
                        .assertExists()
                        .assertIsFocused()
                    true // Condition met
                } catch (e: AssertionError) {
                    false // Condition not yet met
                }
            }

            allAlbumNodes[0].assertIsFocused()
            allAlbumNodes[1].assertIsNotFocused()
            allAlbumNodes[2].assertIsNotFocused()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testIconGridHasBadgeThenBadgeIsDisplayed() =
        testScope.runTest {
            composeTestRule.setContent {
                IconGrid(
                    icons = emptyList(),
                    modifier = Modifier.size(100.dp),
                    categoryType = CategoryType.DEVICE_FOLDERS,
                    badgeIcon = BADGE_ICON,
                    // Pass the test tag via the modifier parameter
                    badgeIconModifier = Modifier.testTag(BADGE_TEST_TAG),
                )
            }

            advanceTimeBy(100)

            composeTestRule.onNodeWithTag(BADGE_TEST_TAG).assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testIconGridHasNullBadgeThenBadgeIsNotDisplayed() =
        testScope.runTest {
            composeTestRule.setContent {
                IconGrid(
                    icons = emptyList(),
                    modifier = Modifier.size(100.dp),
                    categoryType = CategoryType.DEVICE_FOLDERS,
                    badgeIcon = null,
                    // Pass the test tag via the modifier parameter
                    badgeIconModifier = Modifier.testTag(BADGE_TEST_TAG),
                )
            }

            advanceTimeBy(100)

            composeTestRule.onNodeWithTag(BADGE_TEST_TAG).assertIsNotDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumsCanBeSelected() =
        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = TEST_ALBUM_NAME_PREFIX + "1"
            // In the [FakeInMemoryPagingSource] the albums are names using TEST_ALBUM_NAME_PREFIX
            // appended by a count in their sequence. Verify that an album with the name exists
            composeTestRule.onNode(hasText(testAlbumDisplayName)).assertIsDisplayed()

            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected route to be album media grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_MEDIA_GRID.route)
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSwipeLeftToNavigateToPhotoGrid() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            composeTestRule.onNode(hasText(TEST_ALBUM_NAME_PREFIX + "1")).performTouchInput {
                swipeRight()
            }
            composeTestRule.waitForIdle()

            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to Photogrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumMediaShowsEmptyStateWhenEmpty() {

        val dataService = dataService as? TestDataServiceImpl
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
        checkNotNull(dataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        dataService.albumMediaSetSize = 0

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = TEST_ALBUM_NAME_PREFIX + "1"
            // In the [FakeInMemoryPagingSource] the albums are names using TEST_ALBUM_NAME_PREFIX
            // appended by a count in their sequence. Verify that an album with the name exists
            composeTestRule.onNode(hasText(testAlbumDisplayName)).assertIsDisplayed()
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

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

    fun testAlbumMediaShowsEmptyStateWhenEmpty_videoOnlyMimeType() {

        val dataService = dataService as? TestDataServiceImpl
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
        checkNotNull(dataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        dataService.albumMediaSetSize = 0

        val resources = getTestableContext().getResources()

        testScope.runTest {
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayListOf("video/*", "video/mpeg"))
                }
            configurationManager.get().setIntent(testIntent)

            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = TEST_ALBUM_NAME_PREFIX + "1"
            // In the [FakeInMemoryPagingSource] the albums are names using TEST_ALBUM_NAME_PREFIX
            // appended by a count in their sequence. Verify that an album with the name exists
            composeTestRule.onNode(hasText(testAlbumDisplayName)).assertIsDisplayed()
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

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
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForFavorites() {

        val testDataService = dataService as? TestDataServiceImpl
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Album(
                    id = ALBUM_ID_FAVORITES,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Favorites",
                    coverUri =
                        Uri.EMPTY.buildUpon()
                            .apply {
                                scheme("content")
                                authority("a")
                                path("1234")
                            }
                            .build(),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )
            )

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val testAlbumDisplayName = "Favorites"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_favorites_empty_state_title))
                )
                .assertIsDisplayed()

            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_favorites_empty_state_body))
                )
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForVideos() {

        val testDataService = dataService as? TestDataServiceImpl
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Album(
                    id = ALBUM_ID_VIDEOS,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Videos",
                    coverUri =
                        Uri.EMPTY.buildUpon()
                            .apply {
                                scheme("content")
                                authority("a")
                                path("1234")
                            }
                            .build(),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )
            )

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = "Videos"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_videos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_videos_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForCamera() {
        val testDataService = dataService as? TestDataServiceImpl
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Album(
                    id = ALBUM_ID_CAMERA,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Camera",
                    coverUri =
                        Uri.EMPTY.buildUpon()
                            .apply {
                                scheme("content")
                                authority("a")
                                path("1234")
                            }
                            .build(),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )
            )

        val resources = getTestableContext().getResources()

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            val testAlbumDisplayName = "Camera"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_camera_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testMediaSetCanBeSelected() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "People & Pets"
        val testMediaSetname = "mediaset"

        testCategoryDataService.mediaSetContentSize = 0
        // Force the data service to return no data for all test sources during this test.
        testCategoryDataService.mediaSetList =
            listOf(
                Group.MediaSet(
                    id = testMediaSetname,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testMediaSetname,
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.PEOPLE_AND_PETS.key,
                )
            )

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.PEOPLE_AND_PETS,
                    icons = emptyList(),
                    isLeafCategory = true,
                    badge = null,
                )
            )

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule.onNode(hasText(testMediaSetname)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            assertWithMessage("Expected route to be mediasetcontentgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_CONTENT_GRID.route)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForMediaSet() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "People & Pets"
        val testMediaSetname = "mediaset"

        val resources = getTestableContext().getResources()

        testCategoryDataService.mediaSetContentSize = 0
        // Force the data service to return no data for all test sources during this test.
        testCategoryDataService.mediaSetList =
            listOf(
                Group.MediaSet(
                    id = testMediaSetname,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testMediaSetname,
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.PEOPLE_AND_PETS.key,
                )
            )

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.PEOPLE_AND_PETS,
                    icons = emptyList(),
                    isLeafCategory = true,
                    badge = null,
                )
            )

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule.onNode(hasText(testMediaSetname)).performClick()

            composeTestRule.waitForIdle()

            // Allow the PreviewViewModel to collect flows
            advanceTimeBy(100)

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSpinnerForMediaSetContent() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "People & Pets"
        val testMediaSetname = "mediaset"
        val mediaItemsContentDescriptionSubstring = "taken on"

        val resources = getTestableContext().getResources()

        testCategoryDataService.mediaSetContentDelay = 5000L
        testCategoryDataService.mediaSetContentSize = 4
        // Force the data service to return no data for all test sources during this test.
        testCategoryDataService.mediaSetList =
            listOf(
                Group.MediaSet(
                    id = testMediaSetname,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testMediaSetname,
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.PEOPLE_AND_PETS.key,
                )
            )

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.PEOPLE_AND_PETS,
                    icons = emptyList(),
                    isLeafCategory = true,
                    badge = null,
                )
            )

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule.onNode(hasText(testMediaSetname)).performClick()

            composeTestRule.waitForIdle()

            // Wait for the Spinner to show
            advanceTimeBy(2000)
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_loading_media_items_description)
                    )
                )
                .assertIsDisplayed()

            // Wait for the media items to show
            advanceTimeBy(4000)
            composeTestRule
                .onAllNodes(
                    hasContentDescription(
                        value = mediaItemsContentDescriptionSubstring,
                        substring = true,
                    )
                )
                .onFirst()
                .assert(hasClickAction())
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSpinnerForEmptyMediaSetContent() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "People & Pets"
        val testMediaSetname = "mediaset"

        val resources = getTestableContext().getResources()

        testCategoryDataService.mediaSetContentDelay = 5000L
        testCategoryDataService.mediaSetContentSize = 0
        // Force the data service to return no data for all test sources during this test.
        testCategoryDataService.mediaSetList =
            listOf(
                Group.MediaSet(
                    id = testMediaSetname,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testMediaSetname,
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.PEOPLE_AND_PETS.key,
                )
            )

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.PEOPLE_AND_PETS,
                    icons = emptyList(),
                    isLeafCategory = true,
                    badge = null,
                )
            )

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule.onNode(hasText(testMediaSetname)).performClick()

            composeTestRule.waitForIdle()

            // Wait for the Spinner to show
            advanceTimeBy(2000)
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_loading_media_items_description)
                    )
                )
                .assertIsDisplayed()

            // Wait for the Empty Page message to show
            advanceTimeBy(4000)
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testTimeOutForContentForMediaSet() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "People & Pets"
        val testMediaSetname = "mediaset"

        val resources = getTestableContext().getResources()

        testCategoryDataService.mediaSetContentDelay = 12000L
        testCategoryDataService.mediaSetContentSize = 0
        // Force the data service to return no data for all test sources during this test.
        testCategoryDataService.mediaSetList =
            listOf(
                Group.MediaSet(
                    id = testMediaSetname,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testMediaSetname,
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.PEOPLE_AND_PETS.key,
                )
            )

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.PEOPLE_AND_PETS,
                    icons = emptyList(),
                    isLeafCategory = true,
                    badge = null,
                )
            )

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule.onNode(hasText(testMediaSetname)).performClick()

            composeTestRule.waitForIdle()

            // Wait for the Spinner to show
            advanceTimeBy(2000)
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_loading_media_items_description)
                    )
                )
                .assertIsDisplayed()

            // Wait for the Empty Page message to show after timeout
            advanceTimeBy(12000)
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForPeoplePetsCategory() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "People & Pets"

        val resources = getTestableContext().getResources()

        testCategoryDataService.mediaSetSize = 0
        // Force the data service to return no data for all test sources during this test.
        testCategoryDataService.mediaSetList = emptyList()

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.PEOPLE_AND_PETS,
                    icons = emptyList(),
                    isLeafCategory = true,
                    badge = null,
                )
            )

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_people_category_empty_state_title)
                    )
                )
                .assertIsDisplayed()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_people_category_empty_state_body)
                    )
                )
                .assertIsDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForOtherCategory() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "Other Categoreis"

        val resources = getTestableContext().getResources()

        testCategoryDataService.mediaSetSize = 0
        // Force the data service to return no data for all test sources during this test.
        testCategoryDataService.mediaSetList = emptyList()

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.USER_ALBUMS,
                    icons = emptyList(),
                    isLeafCategory = true,
                    badge = null,
                )
            )

        testScope.runTest {
            composeTestRule.setContent {
                // Set an explicit size to prevent errors in glide being unable to measure
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()

            advanceTimeBy(100)

            assertWithMessage("Expected route to be media set grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_GRID.route)

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()

            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumMediaGridDragSelect() =
        testScope.runTest {
            val videosAlbum =
                Group.Album(
                    id = ALBUM_ID_VIDEOS,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Videos",
                    coverUri =
                        Uri.EMPTY.buildUpon()
                            .apply {
                                scheme("content")
                                authority("a")
                                path("1234")
                            }
                            .build(),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )

            // Update configuration to support multi-select. Use a high limit to avoid capping.
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
                }
            configurationManager.get().setIntent(testIntent)
            advanceTimeBy(100)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToAlbumMediaGridForCategories(album = videosAlbum)
            })

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected route to be category album grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_MEDIA_GRID.route)

            // Let collectors run
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val allPhotosMatcher =
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )

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
                    // Wait for the long press to register to enable drag-to-select
                    advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                    dragInIncrements(totalOffset = screenWidthPx, vertical = false)
                    // Wait for the scroll to finish.
                    advanceEventTime(1000)
                    up()
                }
            }

            advanceTimeBy(1000)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected $columns items in selection, but found ${selection.size()}")
                .that(selection.size())
                .isEqualTo(columns)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumMediaGridDragSelectSkipsDisabledItems() =
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

            val testDataService = dataService as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.albumMediaList = mediaList

            val cameraAlbum =
                Group.Album(
                    id = ALBUM_ID_CAMERA,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Camera",
                    coverUri =
                        Uri.EMPTY.buildUpon()
                            .apply {
                                scheme("content")
                                authority("a")
                                path("1234")
                            }
                            .build(),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )

            // Update configuration to support multi-select. Use a high limit to avoid capping.
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)
                }
            configurationManager.get().setIntent(testIntent)
            advanceTimeBy(100)

            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToAlbumMediaGridForCategories(album = cameraAlbum)
            })

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected route to be category album grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_MEDIA_GRID.route)

            // Let collectors run
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val allPhotosMatcher =
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodes(allPhotosMatcher).fetchSemanticsNodes().isNotEmpty()
            }

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
            val selectedItems = selection.snapshot()
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
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testMediaSetGridDragSelect() =
        testScope.runTest {
            val testMediaSet =
                Group.MediaSet(
                    id = "mediaset",
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Media Set",
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.PEOPLE_AND_PETS.key,
                )

            // Update configuration to support multi-select. Use a high limit to avoid capping.
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
                }
            configurationManager.get().setIntent(testIntent)
            advanceTimeBy(100)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToMediaSetContentGrid(mediaSet = testMediaSet)
            })

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected route to be media set content grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_CONTENT_GRID.route)

            // Let collectors run
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val allPhotosMatcher =
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodes(allPhotosMatcher).fetchSemanticsNodes().isNotEmpty()
            }

            val allPhotos = composeTestRule.onAllNodes(allPhotosMatcher)
            val firstPhotoBounds = allPhotos.onFirst().getBoundsInRoot()
            val firstPhotoTopPx = with(composeTestRule.density) { firstPhotoBounds.top.toPx() }
            val columns =
                allPhotos.fetchSemanticsNodes(atLeastOneRootRequired = true).count {
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
                    // Wait for the long press to register to enable drag-to-select
                    advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                    dragInIncrements(totalOffset = screenWidthPx, vertical = false)
                    // Wait for the scroll to finish.
                    advanceEventTime(1000)
                    up()
                }
            }

            advanceTimeBy(1000)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected $columns items in selection, but found ${selection.size()}")
                .that(selection.size())
                .isEqualTo(columns)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testMediaSetGridDragSelectSkipsDisabledItems() =
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

            val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
            checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
            testCategoryDataService.mediaSetContentList = mediaList

            val testMediaSet =
                Group.MediaSet(
                    id = "mediaset",
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Media Set",
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.DEVICE_FOLDERS.key,
                )

            // Update configuration to support multi-select. Use a high limit to avoid capping.
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)
                }
            configurationManager.get().setIntent(testIntent)
            advanceTimeBy(100)

            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({
                navController.navigateToMediaSetContentGrid(mediaSet = testMediaSet)
            })

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            assertWithMessage("Expected route to be media set content grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.MEDIA_SET_CONTENT_GRID.route)

            // Let collectors run
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val allPhotosMatcher =
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodes(allPhotosMatcher).fetchSemanticsNodes().isNotEmpty()
            }

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
            val selectedItems = selection.snapshot()
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
    fun testMediaSetHasBadgeThenBadgeIsDisplayed() {
        val mediaSetWithBadge =
            Group.MediaSet(
                id = "1",
                pickerId = 1L,
                authority = "a",
                displayName = MEDIA_SET_NAME,
                icon = GlideIcon(Uri.EMPTY, MediaSource.LOCAL),
                badge = GlideIcon(Uri.EMPTY, MediaSource.REMOTE),
                parentCategoryType = CategoryType.APP_FOLDERS.key,
            )
        val gridItem = MediaGridItem.MediaSetItem(mediaSetWithBadge)

        composeTestRule.setContent {
            mediaSetContentFactory(
                item = gridItem,
                onClick = {},
                badgeIconModifier = Modifier.testTag(BADGE_TEST_TAG),
            )
        }

        composeTestRule.onNodeWithText(MEDIA_SET_NAME).assertIsDisplayed()
        composeTestRule.onNode(hasTestTag(BADGE_TEST_TAG), useUnmergedTree = true).assertExists()
    }

    @Test
    fun testMediaSetHasNullBadgeThenBadgeIsNotDisplayed() {
        val mediaSetWithoutBadge =
            Group.MediaSet(
                id = "2",
                pickerId = 2L,
                authority = "a",
                displayName = MEDIA_SET_NAME,
                icon = GlideIcon(Uri.EMPTY, MediaSource.LOCAL),
                badge = null,
                parentCategoryType = CategoryType.APP_FOLDERS.key,
            )
        val gridItem = MediaGridItem.MediaSetItem(mediaSetWithoutBadge)

        composeTestRule.setContent {
            mediaSetContentFactory(
                item = gridItem,
                onClick = {},
                badgeIconModifier = Modifier.testTag(BADGE_TEST_TAG),
            )
        }

        composeTestRule.onNodeWithText(MEDIA_SET_NAME).assertIsDisplayed()
        composeTestRule
            .onNode(hasTestTag(BADGE_TEST_TAG), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun testMediaSetIsFromUserAlbumsCategoryThenBadgeIsNotDisplayed() {
        val mediaSetWithoutBadge =
            Group.MediaSet(
                id = "2",
                pickerId = 2L,
                authority = "a",
                displayName = MEDIA_SET_NAME,
                icon = GlideIcon(Uri.EMPTY, MediaSource.LOCAL),
                badge = GlideIcon(Uri.EMPTY, MediaSource.REMOTE),
                parentCategoryType = CategoryType.USER_ALBUMS.key,
            )
        val gridItem = MediaGridItem.MediaSetItem(mediaSetWithoutBadge)

        composeTestRule.setContent {
            mediaSetContentFactory(
                item = gridItem,
                onClick = {},
                badgeIconModifier = Modifier.testTag(BADGE_TEST_TAG),
            )
        }

        composeTestRule.onNodeWithText(MEDIA_SET_NAME).assertIsDisplayed()
        composeTestRule
            .onNode(hasTestTag(BADGE_TEST_TAG), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSwipeLeftToNavigateToPhotoGridInRtl() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    callPhotopickerMain(
                        featureManager = featureManager,
                        selection = selection,
                        events = events,
                    )
                }
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            assertWithMessage("Expected route to be category albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            composeTestRule.onNode(hasText(TEST_ALBUM_NAME_PREFIX + "1")).performTouchInput {
                swipeLeft()
            }
            composeTestRule.waitForIdle()

            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to Photogrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
    )
    fun testMediaSetContentGrid_withDefaultAspectRatio_displaysSquareThumbnail() =
        testScope.runTest {
            val testMediaSet =
                Group.MediaSet(
                    id = "mediaset",
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Media Set",
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.PEOPLE_AND_PETS.key,
                )

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)
            composeTestRule.runOnUiThread({
                navController.navigateToMediaSetContentGrid(mediaSet = testMediaSet)
            })

            composeTestRule.waitForIdle()
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

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
    )
    fun testMediaSetContentGrid_withPortraitAspectRatio_displaysPortraitThumbnail() =
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

            val testMediaSet =
                Group.MediaSet(
                    id = "mediaset",
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Media Set",
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.PEOPLE_AND_PETS.key,
                )

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)
            composeTestRule.runOnUiThread({
                navController.navigateToMediaSetContentGrid(mediaSet = testMediaSet)
            })

            composeTestRule.waitForIdle()
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

    @Test
    @DisableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
    )
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testMediaSetContentGrid_withUiCustomizationParams_isIgnoredIfFlagDisabled() =
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

            val testMediaSet =
                Group.MediaSet(
                    id = "mediaset",
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Media Set",
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.PEOPLE_AND_PETS.key,
                )

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)
            composeTestRule.runOnUiThread({
                navController.navigateToMediaSetContentGrid(mediaSet = testMediaSet)
            })

            composeTestRule.waitForIdle()
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
            assertWithMessage("Aspect ratio should be 1:1 when flag is disabled")
                .that(ratio)
                .isWithin(0.05f)
                .of(1f)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
    )
    fun testAlbumMediaGrid_withDefaultAspectRatio_displaysSquareThumbnail() =
        testScope.runTest {
            val testAlbum =
                Group.Album(
                    id = "album",
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Album",
                    coverUri = Uri.parse(""),
                    dateTakenMillisLong = 0L,
                    coverMediaSource = MediaSource.LOCAL,
                )

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)
            composeTestRule.runOnUiThread({
                navController.navigateToAlbumMediaGridForCategories(album = testAlbum)
            })

            composeTestRule.waitForIdle()
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

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
    )
    fun testAlbumMediaGrid_withPortraitAspectRatio_displaysPortraitThumbnail() =
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

            val testAlbum =
                Group.Album(
                    id = "album",
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Album",
                    coverUri = Uri.parse(""),
                    dateTakenMillisLong = 0L,
                    coverMediaSource = MediaSource.LOCAL,
                )

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)
            composeTestRule.runOnUiThread({
                navController.navigateToAlbumMediaGridForCategories(album = testAlbum)
            })

            composeTestRule.waitForIdle()
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

    @Test
    @DisableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
    )
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumMediaGrid_withUiCustomizationParams_isIgnoredIfFlagDisabled() =
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

            val testAlbum =
                Group.Album(
                    id = "album",
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Album",
                    coverUri = Uri.parse(""),
                    dateTakenMillisLong = 0L,
                    coverMediaSource = MediaSource.LOCAL,
                )

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)
            composeTestRule.runOnUiThread({
                navController.navigateToAlbumMediaGridForCategories(album = testAlbum)
            })

            composeTestRule.waitForIdle()
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
            assertWithMessage("Aspect ratio should be 1:1 when flag is disabled")
                .that(ratio)
                .isWithin(0.05f)
                .of(1f)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
    )
    fun testMediaSetContentGridItemWithDisabledReasonCannotBeSelected() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "People & Pets"
        val testMediaSetName = "mediaset"
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

        testCategoryDataService.mediaSetContentList = listOf(mediaWithDisabledReason)
        testCategoryDataService.mediaSetList =
            listOf(
                Group.MediaSet(
                    id = testMediaSetName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testMediaSetName,
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.PEOPLE_AND_PETS.key,
                )
            )

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.PEOPLE_AND_PETS,
                    icons = emptyList(),
                    isLeafCategory = true,
                    badge = null,
                )
            )

        testScope.runTest {
            val intent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)
                }
            configurationManager.get().setIntent(intent)
            configurationManager.get().setCaller("com.android.test", 123, TEST_APP_LABEL)

            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule.onNode(hasText(testMediaSetName)).performClick()

            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodes(hasContentDescription(value = "taken on", substring = true))
                .onFirst()
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler did NOT update the selection.
            assertWithMessage("Expected selection to be empty as item has disabled reason.")
                .that(selection.snapshot().size)
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
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
    )
    fun testMediaSetContentGridItemCannotBeSelectedWhenBatchSizeLimitIsExceeded() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }

        val testCategoryDisplayName = "People & Pets"
        val testMediaSetName = "mediaset"

        val maxBatchSizeLimit = 2 * SIZE_100KB
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMaxSelectionBatchSizeInBytes(maxBatchSizeLimit)
                .build()

        val item1 = createImage(mediaId = "1", pickerId = 1L, selectionParams = selectionParams)
        val item2 =
            createImage(
                mediaId = "2",
                pickerId = 2L,
                selectionParams = selectionParams,
                sizeInBytes = SIZE_100KB + 1,
            )

        testCategoryDataService.mediaSetContentList = listOf(item1, item2)
        testCategoryDataService.mediaSetList =
            listOf(
                Group.MediaSet(
                    id = testMediaSetName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testMediaSetName,
                    icon = GlideIcon(Uri.parse(""), MediaSource.LOCAL),
                    badge = null,
                    parentCategoryType = CategoryType.PEOPLE_AND_PETS.key,
                )
            )

        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Category(
                    id = testCategoryDisplayName,
                    pickerId = 1234L,
                    authority = "a",
                    displayName = testCategoryDisplayName,
                    categoryType = CategoryType.PEOPLE_AND_PETS,
                    icons = emptyList(),
                    isLeafCategory = true,
                    badge = null,
                )
            )

        testScope.runTest {
            val intent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
                }
            configurationManager.get().setIntent(intent)
            configurationManager.get().setCaller("com.android.test", 123, TEST_APP_LABEL)

            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            composeTestRule.onNode(hasText(testCategoryDisplayName)).performClick()

            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule.onNode(hasText(testMediaSetName)).performClick()

            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val mediaItems =
                composeTestRule.onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )

            // Select first item
            mediaItems[0].performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            assertWithMessage("Selection should contain 1 item")
                .that(selection.snapshot().size)
                .isEqualTo(1)

            // Select second item (should fail)
            mediaItems[1].performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            // Ensure the click handler did NOT update the selection.
            assertWithMessage(
                    "Expected selection to still contain 1 item as second item exceeds batch limit."
                )
                .that(selection.snapshot().size)
                .isEqualTo(1)

            val resources = getTestableContext().resources
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_selection_max_selection_batch_size_error_kb,
                    TEST_APP_LABEL,
                    maxBatchSizeLimit / 1024,
                )

            assertSnackbarIsShown(expectedMessage, composeTestRule)
        }
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
    )
    fun testAlbumMediaGridItemWithDisabledReasonCannotBeSelected() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

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
        val albumName = "Camera"

        testDataService.albumMediaList = listOf(mediaWithDisabledReason)
        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Album(
                    id = "Camera",
                    pickerId = 1234L,
                    authority = "a",
                    displayName = albumName,
                    coverUri = Uri.parse(""),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )
            )

        testScope.runTest {
            val intent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)
                }
            configurationManager.get().setIntent(intent)
            configurationManager.get().setCaller("com.android.test", 123, TEST_APP_LABEL)

            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            composeTestRule.onNode(hasText(albumName)).performClick()

            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onAllNodes(hasContentDescription(value = "taken on", substring = true))
                .onFirst()
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler did NOT update the selection.
            assertWithMessage("Expected selection to be empty as item has disabled reason.")
                .that(selection.snapshot().size)
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
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    fun testAlbumMediaGridItemCannotBeSelectedWhenBatchSizeLimitIsExceeded() {
        val testCategoryDataService = categoryDataService as? TestCategoryDataServiceImpl
        checkNotNull(testCategoryDataService) { "Expected a TestCategoryDataServiceImpl" }
        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        val maxBatchSizeLimit = 2 * SIZE_100KB
        val selectionParams =
            PhotoPickerSelectionParams.Builder()
                .setMaxSelectionBatchSizeInBytes(maxBatchSizeLimit)
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
        testDataService.albumMediaList = listOf(item1, item2)
        testCategoryDataService.categoryAlbumList =
            listOf(
                Group.Album(
                    id = "Camera",
                    pickerId = 1234L,
                    authority = "a",
                    displayName = "Camera",
                    coverUri = Uri.parse(""),
                    dateTakenMillisLong = 12345678L,
                    coverMediaSource = MediaSource.LOCAL,
                )
            )

        val intent =
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)
            }
        configurationManager.get().setIntent(intent)
        configurationManager.get().setCaller("com.android.test", 123, TEST_APP_LABEL)

        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToCategoryGrid() })

            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            composeTestRule.onNode(hasText("Camera")).performClick()

            composeTestRule.waitForIdle()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            val mediaItems =
                composeTestRule.onAllNodes(
                    hasContentDescription(
                        value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )

            // Select first item
            mediaItems[0].performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            assertWithMessage("Selection should contain 1 item")
                .that(selection.snapshot().size)
                .isEqualTo(1)

            // Select second item (should fail)
            mediaItems[1].performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            // Ensure the click handler did NOT update the selection.
            assertWithMessage(
                    "Expected selection to still contain 1 item as second item exceeds batch limit."
                )
                .that(selection.snapshot().size)
                .isEqualTo(1)

            val resources = getTestableContext().resources
            val expectedMessage =
                resources.getString(
                    R.string.photopicker_selection_max_selection_batch_size_error_kb,
                    TEST_APP_LABEL,
                    maxBatchSizeLimit / 1024,
                )

            assertSnackbarIsShown(expectedMessage, composeTestRule)
        }
    }
}
