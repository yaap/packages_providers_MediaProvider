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

package com.android.photopicker.features.albumgrid

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_CAMERA
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_VIDEOS
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import android.widget.photopicker.PhotoPickerSelectionParams
import android.widget.photopicker.PhotoPickerUiCustomizationParams
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
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
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.paging.FakeInMemoryAlbumPagingSource.Companion.TEST_ALBUM_NAME_PREFIX
import com.android.photopicker.extensions.navigateToAlbumGrid
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.MockContentProviderWrapper
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
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
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
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.MockitoAnnotations

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
class AlbumGridFeatureTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()
    @get:Rule(order = 3) var setFlagsRule = SetFlagsRule()

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

    @Inject lateinit var mockContext: Context
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var events: Events
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    @Inject lateinit var dataService: DataService

    private val MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING = "taken on"

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
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumGridIsEnabledWhenSearchFlagOff() {
        assertWithMessage("AlbumGridFeature is not always enabled for TEST_ACTION")
            .that(
                AlbumGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("AlbumGridFeature is not always enabled")
            .that(
                AlbumGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("AlbumGridFeature is not always enabled")
            .that(
                AlbumGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                    }
                )
            )
            .isEqualTo(true)

        assertWithMessage("AlbumGridFeature is not always enabled")
            .that(
                AlbumGridFeature.Registration.isEnabled(
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
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumGridIsDisabledWhenSearchFlagOn() {
        assertWithMessage("AlbumGridFeature is enabled for TEST_ACTION")
            .that(
                AlbumGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("AlbumGridFeature is always enabled")
            .that(
                AlbumGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("AlbumGridFeature is always enabled")
            .that(
                AlbumGridFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        action(Intent.ACTION_GET_CONTENT)
                        intent(Intent(Intent.ACTION_GET_CONTENT))
                    }
                )
            )
            .isEqualTo(false)

        assertWithMessage("AlbumGridFeature is always enabled")
            .that(
                AlbumGridFeature.Registration.isEnabled(
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
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigateAlbumGridAndAlbumsAreVisible() =
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
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
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
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testConsistentAlbumFocus() =
        testScope.runTest {
            val currentDateTime = LocalDateTime.now()
            val dataList =
                buildList<Group.Album> {
                    for (i in 1..3) {
                        add(
                            Group.Album(
                                id = "$i",
                                pickerId = i.toLong(),
                                authority = "a",
                                displayName = TEST_ALBUM_NAME_PREFIX + "$i",
                                coverUri =
                                    Uri.EMPTY.buildUpon()
                                        .apply {
                                            scheme("content")
                                            authority("a")
                                            path("$i")
                                        }
                                        .build(),
                                dateTakenMillisLong =
                                    currentDateTime
                                        .minus(i.toLong(), ChronoUnit.DAYS)
                                        .toEpochSecond(ZoneOffset.UTC) * 1000,
                                coverMediaSource = MediaSource.LOCAL,
                            )
                        )
                    }
                }

            val testDataService = dataService as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.albumsList = dataList

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
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            composeTestRule.waitForIdle()

            // wait for the album grid to show up
            advanceTimeBy(100)

            val allAlbumNodes =
                composeTestRule.onAllNodes(hasText(text = TEST_ALBUM_NAME_PREFIX, substring = true))

            allAlbumNodes[0].assert(hasClickAction()).assertIsDisplayed().performClick()

            assertWithMessage("Expected route to be album media grid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_MEDIA_GRID.route)

            composeTestRule.waitForIdle()

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)

            composeTestRule.waitForIdle()

            // wait for the album grid to show up
            advanceTimeBy(150)

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                try {
                    composeTestRule
                        .onNode(hasText(TEST_ALBUM_NAME_PREFIX + "1", substring = true))
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
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
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
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
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

            assertWithMessage("Expected route to be albummediagrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_MEDIA_GRID.route)
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
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
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
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
            assertWithMessage("Expected swipe to navigate to AlbumGrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumMediaShowsEmptyStateWhenEmpty() {

        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0

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
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
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

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForFavorites() {

        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testDataService.albumsList =
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
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

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
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForVideos() {

        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testDataService.albumsList =
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
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
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
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testEmptyStateContentForCamera() {

        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Force the data service to return no data for all test sources during this test.
        testDataService.albumMediaSetSize = 0
        testDataService.albumsList =
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
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
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
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
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
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
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
            assertWithMessage("Expected swipe to navigate to PhotoGrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
    )
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumMediaGrid_withDefaultAspectRatio_displaysSquareThumbnail() =
        testScope.runTest {
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            val testAlbumDisplayName = TEST_ALBUM_NAME_PREFIX + "1"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()
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
    )
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
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

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            val testAlbumDisplayName = TEST_ALBUM_NAME_PREFIX + "1"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()
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
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
    )
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

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            val testAlbumDisplayName = TEST_ALBUM_NAME_PREFIX + "1"
            composeTestRule.onNode(hasText(testAlbumDisplayName)).performClick()
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
    )
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testAlbumMediaGridItemWithDisabledReasonCannotBeSelected() =
        testScope.runTest {
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

            testDataService.albumMediaList = listOf(mediaWithDisabledReason)

            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)

            // Navigate on the UI thread (similar to a click handler)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })

            assertWithMessage("Expected route to be albumgrid")
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

            assertWithMessage("Expected route to be albummediagrid")
                .that(navController.currentBackStackEntry?.destination?.route)
                .isEqualTo(PhotopickerDestinations.ALBUM_MEDIA_GRID.route)

            // Attempt to select the disabled media item
            composeTestRule
                .onNode(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .performClick()

            composeTestRule.waitForIdle()

            assertWithMessage("Media item with disabled reason should not be selectable")
                .that(selection.size())
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

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSelectionIsNotAllowedWhenBatchSizeLimitExceeded() =
        testScope.runTest {
            val testDataService = dataService as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

            // Force the data service to return no data for all test sources during this test.
            testDataService.albumMediaSetSize = 2
            testDataService.albumsList =
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

            val maxBatchSizeLimit = 2 * SIZE_100KB
            val selectionParams =
                PhotoPickerSelectionParams.Builder()
                    .setMaxSelectionBatchSizeInBytes(maxBatchSizeLimit)
                    .build()

            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
                }
            configurationManager.get().setIntent(testIntent)
            configurationManager.get().setCaller("com.android.test", 123, TEST_APP_LABEL)

            val item1 = createImage(mediaId = "1", pickerId = 1L, selectionParams = selectionParams)
            val item2 =
                createImage(
                    mediaId = "2",
                    pickerId = 2L,
                    selectionParams = selectionParams,
                    sizeInBytes = SIZE_100KB + 1,
                )

            testDataService.albumMediaList = listOf(item1, item2)

            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            advanceTimeBy(100)
            composeTestRule.runOnUiThread({ navController.navigateToAlbumGrid() })
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            composeTestRule.onNode(hasText("Camera")).performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            val mediaItems =
                composeTestRule.onAllNodes(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )

            mediaItems[0].performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            assertWithMessage("Selection should contain 1 item")
                .that(selection.snapshot().size)
                .isEqualTo(1)

            mediaItems[1].performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(100)

            assertWithMessage(
                    "Selection should still contain 1 item as second item exceeds batch limit"
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
