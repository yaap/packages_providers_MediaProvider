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
package com.android.photopicker.core.embedded

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Parcel
import android.os.UserHandle
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import android.view.SurfaceControlViewHost
import android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo
import android.widget.photopicker.PhotoPickerSelectionParams
import android.widget.photopicker.PhotoPickerUiCustomizationParams
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.height
import androidx.test.filters.SdkSuppress
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.banners.BannerDefinitions
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
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.database.DatabaseManager
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.categorygrid.data.CategoryDataService
import com.android.photopicker.features.highlightmediaresults.model.HighlightAlbum
import com.android.photopicker.features.overflowmenu.OverflowMenuFeature
import com.android.photopicker.features.preview.PreviewFeature
import com.android.photopicker.features.search.data.SearchDataService
import com.android.photopicker.features.snackbar.SnackbarFeature
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.inject.TestOptions
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.MockContentProviderWrapper
import com.android.photopicker.util.test.dragInIncrements
import com.android.photopicker.util.test.mockSystemService
import com.android.photopicker.util.test.nonNullableEq
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    EmbeddedServiceModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class EmbeddedFeaturesTest : EmbeddedPhotopickerFeatureBaseTest() {
    /** Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) val glideRule = GlideTestRule()
    @get:Rule(order = 3) var setFlagsRule = SetFlagsRule()

    /** Setup dependencies for the UninstallModules for the test class. */
    @Module
    @InstallIn(SingletonComponent::class)
    class TestModule :
        PhotopickerTestModule(TestOptions.build { runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED) })

    val testDispatcher = StandardTestDispatcher()
    /* Overrides for EmbeddedServiceModule */
    val testScope: TestScope = TestScope(testDispatcher)
    @BindValue @Main val mainScope: CoroutineScope = testScope
    @BindValue @Background var testBackgroundScope: CoroutineScope = testScope.backgroundScope
    @Inject @Main lateinit var mainDispatcher: CoroutineDispatcher
    /* Overrides for ViewModelModule */
    @BindValue val viewModelScopeOverride: CoroutineScope? = testScope.backgroundScope
    /**
     * Preview uses Glide for loading images, so we have to mock out the dependencies for Glide
     * Replace the injected ContentResolver binding in [ApplicationModule] with this test value.
     */
    @BindValue @ApplicationOwned lateinit var contentResolver: ContentResolver
    private lateinit var provider: MockContentProviderWrapper
    @Mock lateinit var mockContentProvider: ContentProvider
    @Mock lateinit var mockSurfaceControlViewHost: SurfaceControlViewHost
    /**
     * A [EmbeddedState] having a mocked [SurfaceControlViewHost] instance that can be used for
     * testing in collapsed mode
     */
    private lateinit var testEmbeddedStateWithHostInCollapsedState: EmbeddedState
    /**
     * A [EmbeddedState] having a mocked [SurfaceControlViewHost] instance that can be used for
     * testing in Expanded state
     */
    private lateinit var testEmbeddedStateWithHostInExpandedState: EmbeddedState

    @Inject lateinit var events: Lazy<Events>
    @Inject lateinit var selection: Lazy<Selection<Media>>
    @Inject lateinit var featureManager: Lazy<FeatureManager>
    @Inject lateinit var userHandle: UserHandle
    @Inject lateinit var bannerManager: Lazy<BannerManager>
    @Inject lateinit var embeddedLifecycle: Lazy<EmbeddedLifecycle>
    @Inject lateinit var databaseManager: DatabaseManager
    @Inject lateinit var dataService: Lazy<DataService>
    @Inject lateinit var categoryDataService: CategoryDataService
    @Inject lateinit var searchDataService: Lazy<SearchDataService>
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    // Needed for UserMonitor
    @Inject lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    @Mock lateinit var mockConnectivityManager: ConnectivityManager
    @Inject lateinit var deviceConfig: DeviceConfigProxy
    private val USER_HANDLE_MANAGED: UserHandle
    private val USER_ID_MANAGED: Int = 10
    private val MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING = "taken on"
    private val SNACKBAR_TEST_MESSAGE = "This is a test message"
    private val SNACKBAR_POSITION_THRESHOLD_COLLAPSED = 50f
    private val SNACKBAR_POSITION_THRESHOLD_EXPANDED = 100f
    private val SNACKBAR_POSITION_THRESHOLD_LANDSCAPE = 100f

    init {
        // Create a UserHandle for a managed profile.
        val parcel = Parcel.obtain()
        parcel.writeInt(USER_ID_MANAGED)
        parcel.setDataPosition(0)
        USER_HANDLE_MANAGED = UserHandle(parcel)
        parcel.recycle()
    }

    private val TEST_TAG_SELECTION_BAR = "selection_bar"
    private val MEDIA_ITEM =
        Media.Image(
            mediaId = "1",
            pickerId = 1L,
            authority = "a",
            mediaSource = MediaSource.LOCAL,
            mediaUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("media")
                        path("picker")
                        path("a")
                        path("1")
                    }
                    .build(),
            glideLoadableUri =
                Uri.EMPTY.buildUpon()
                    .apply {
                        scheme("content")
                        authority("a")
                        path("1")
                    }
                    .build(),
            dateTakenMillisLong = 123456789L,
            sizeInBytes = 1000L,
            mimeType = "image/png",
            standardMimeTypeExtension = 1,
            width = 512,
            height = 512,
        )
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
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigationBarIsNotDisplayedInEmbeddedWhenCollapsed_searchFlagOff() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val photosGridNavButtonLabel =
                resources.getString(R.string.photopicker_photos_nav_button_label)
            val albumsGridNavButtonLabel =
                resources.getString(R.string.photopicker_albums_nav_button_label)
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateCollapsed) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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
                .onNode(
                    hasAnyChild(hasText(photosGridNavButtonLabel)) and
                        hasAnyChild(hasText(albumsGridNavButtonLabel))
                )
                .assertIsNotDisplayed()
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigationBarIsDisplayedInEmbeddedWhenExpanded_searchFlagOff() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val photosGridNavButtonLabel =
                resources.getString(R.string.photopicker_photos_nav_button_label)
            val albumsGridNavButtonLabel =
                resources.getString(R.string.photopicker_albums_nav_button_label)
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            // Photos Grid Nav Button and Albums Grid Nav Button
            composeTestRule
                .onNode(hasText(photosGridNavButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
            composeTestRule
                .onNode(hasText(albumsGridNavButtonLabel))
                .assertIsDisplayed()
                .assert(hasClickAction())
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testNavigationBarIsDisplayedInEmbeddedWhenExpanded_searchFlagOn() =
        testScope.runTest {
            val resources = getTestableContext().resources
            val photosGridNavButtonLabel =
                resources.getString(R.string.photopicker_photos_nav_button_label)
            val categoryGridNavButtonLabel =
                resources.getString(R.string.photopicker_categories_nav_button_label)
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            // Photos Grid Nav Button and Category Grid Nav Button
            composeTestRule
                .onNodeWithContentDescription(photosGridNavButtonLabel)
                .assertIsDisplayed()
                .assert(hasClickAction())
            composeTestRule
                .onNodeWithContentDescription(categoryGridNavButtonLabel)
                .assertIsDisplayed()
                .assert(hasClickAction())
        }

    @Test
    fun testSwipeLeftToNavigateDisabledInEmbeddedWhenCollapsed() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateCollapsed) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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
                .performTouchInput { swipeLeft() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to be disabled")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
        }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSwipeLeftToAlbumWorksInEmbeddedWhenExpanded_searchFlagOff() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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
                .performTouchInput { swipeLeft() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to AlbumGrid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSwipeLeftToCategoryWorksInEmbeddedWhenExpanded_searchFlagOn() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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
                .performTouchInput { swipeLeft() }
            composeTestRule.waitForIdle()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Expected swipe to navigate to Categories Album Grid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)
        }

    @Test
    fun testProfileSelectorIsNotDisplayedInEmbeddedWhenCollapsed() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateCollapsed) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_profile_primary_label)
                    )
                )
                .assertIsNotDisplayed()
        }

    @Test
    fun testProfileSelectorIsDisplayedInEmbeddedWhenExpanded() =
        testScope.runTest {
            // Initial setup state: Two profiles (Personal/Work), both enabled
            whenever(mockUserManager.userProfiles) { listOf(userHandle, USER_HANDLE_MANAGED) }
            whenever(mockUserManager.isManagedProfile(USER_ID_MANAGED)) { true }
            whenever(mockUserManager.isQuietModeEnabled(USER_HANDLE_MANAGED)) { false }
            whenever(mockUserManager.getProfileParent(USER_HANDLE_MANAGED)) { userHandle }
            withContext(Dispatchers.Main) {
                composeTestRule.setContent {
                    CompositionLocalProvider(
                        LocalEmbeddedState provides testEmbeddedStateExpanded
                    ) {
                        callEmbeddedPhotopickerApp(
                            embeddedLifecycle = embeddedLifecycle.get(),
                            featureManager = featureManager.get(),
                            selection = selection.get(),
                            events = events.get(),
                        )
                    }
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            composeTestRule
                .onNode(
                    hasContentDescription(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_profile_primary_label)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    fun testSnackbarIsAlwaysEnabledInEmbedded() {
        assertWithMessage("SnackbarFeature is not always enabled for action pick image")
            .that(
                SnackbarFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    }
                )
            )
            .isEqualTo(true)
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    fun testSnackbarDisplaysOnEventInCollapsedMode() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateCollapsed) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Advance the UI clock manually to control for the fade animations on the snackbar.
            composeTestRule.mainClock.autoAdvance = false
            events
                .get()
                .dispatch(Event.ShowSnackbarMessage(FeatureToken.CORE.token, SNACKBAR_TEST_MESSAGE))
            advanceTimeBy(500)
            // Advance ui clock to allow fade in
            composeTestRule.mainClock.advanceTimeBy(2000L)

            val snackbarNode = composeTestRule.onNode(hasText(SNACKBAR_TEST_MESSAGE))
            snackbarNode.assertIsDisplayed()

            // In collapsed mode, snackbar should be at the top of the picker.
            val pickerTop = composeTestRule.onRoot().getBoundsInRoot().top
            val snackbarTop = snackbarNode.getBoundsInRoot().top
            assertWithMessage("Snackbar should be at the top of the collapsed picker")
                .that(snackbarTop.value)
                .isWithin(SNACKBAR_POSITION_THRESHOLD_COLLAPSED)
                .of(pickerTop.value)

            // Advance ui clock to allow fade out
            composeTestRule.mainClock.advanceTimeBy(10_000L)
            composeTestRule.onNode(hasText(SNACKBAR_TEST_MESSAGE)).assertIsNotDisplayed()
        }

    @Test
    fun testSnackbarDisplaysOnEventInExpandedMode() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Advance the UI clock manually to control for the fade animations on the snackbar.
            composeTestRule.mainClock.autoAdvance = false
            events
                .get()
                .dispatch(Event.ShowSnackbarMessage(FeatureToken.CORE.token, SNACKBAR_TEST_MESSAGE))
            advanceTimeBy(500)
            // Advance ui clock to allow fade in
            composeTestRule.mainClock.advanceTimeBy(2000L)

            val snackbarNode = composeTestRule.onNode(hasText(SNACKBAR_TEST_MESSAGE))
            snackbarNode.assertIsDisplayed()

            // In expanded mode, snackbar should be at the bottom of the picker.
            val rootHeight = composeTestRule.onRoot().getBoundsInRoot().height
            val snackbarBottom = snackbarNode.getBoundsInRoot().bottom
            assertWithMessage("Snackbar should be at the bottom of the expanded picker")
                .that(snackbarBottom.value)
                .isWithin(SNACKBAR_POSITION_THRESHOLD_EXPANDED)
                .of(rootHeight.value)

            // Advance ui clock to allow fade out
            composeTestRule.mainClock.advanceTimeBy(10_000L)
            composeTestRule.onNode(hasText(SNACKBAR_TEST_MESSAGE)).assertIsNotDisplayed()
        }

    @Test
    fun testOverflowMenuDisabledInEmbedded() {
        assertWithMessage("Expected OverflowMenuFeature to be disabled in embedded runtime")
            .that(
                OverflowMenuFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    }
                )
            )
            .isEqualTo(false)
    }

    @Test
    fun testPreviewDisabledInEmbedded() {
        assertWithMessage("Expected PreviewFeature to be disabled in embedded runtime")
            .that(
                PreviewFeature.Registration.isEnabled(
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    }
                )
            )
            .isEqualTo(false)
    }

    @Test
    fun testBannerHidden_embeddedMode_collapsedState() = runTest {
        configurationManager
            .get()
            .setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )
        advanceTimeBy(1000)
        val resources = getTestableContext().getResources()
        val expectedPrivacyMessage =
            resources.getString(R.string.photopicker_privacy_explainer, "Test Package")
        composeTestRule.setContent {
            CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateCollapsed) {
                callEmbeddedPhotopickerApp(
                    embeddedLifecycle = embeddedLifecycle.get(),
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
        }
        composeTestRule.waitForIdle()
        bannerManager
            .get()
            .showBanner(BannerDefinitions.PRIVACY_EXPLAINER, BannerLocation.PHOTO_GRID_BANNER)
        advanceTimeBy(100)
        composeTestRule.onNodeWithText(expectedPrivacyMessage).assertIsNotDisplayed()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testBannerShown_embeddedMode_expandedState() = runTest {
        configurationManager
            .get()
            .setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )
        val resources = getTestableContext().getResources()
        val expectedPrivacyMessage =
            resources.getString(R.string.photopicker_privacy_explainer, "Test Package")
        bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
        advanceTimeBy(100)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                callEmbeddedPhotopickerApp(
                    embeddedLifecycle = embeddedLifecycle.get(),
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
        }
        advanceTimeBy(100)
        composeTestRule.waitForIdle()
        advanceTimeBy(100)
        composeTestRule.waitForIdle()

        bannerManager
            .get()
            .showBanner(BannerDefinitions.PRIVACY_EXPLAINER, BannerLocation.PHOTO_GRID_BANNER)
        advanceTimeBy(100)
        composeTestRule.waitForIdle()
        advanceTimeBy(100)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(expectedPrivacyMessage).assertIsDisplayed()
    }

    @Test
    fun testSwipeUpInCollapseMode_emptyPhotosGrid_transferTouchToHost() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInCollapsedState =
            EmbeddedState(isExpanded = false, host = mockSurfaceControlViewHost)

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        // Force the data service to return no data for all test sources during this test.
        testDataService.mediaSetSize = 0
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInCollapsedState
                ) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()
                .performTouchInput { swipeUp() }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
        }
    }

    @Test
    fun testSwipeUpInExpandedMode_emptyPhotosGrid_transferTouchToHost() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInExpandedState =
            EmbeddedState(isExpanded = true, host = mockSurfaceControlViewHost)

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        // Force the data service to return no data for all test sources during this test.
        testDataService.mediaSetSize = 0
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState
                ) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()
                .performTouchInput { swipeUp() }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
        }
    }

    @Test
    fun testSwipeDownInExpandedMode_emptyPhotosGrid_transferTouchToHost() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInExpandedState =
            EmbeddedState(isExpanded = true, host = mockSurfaceControlViewHost)

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        // Force the data service to return no data for all test sources during this test.
        testDataService.mediaSetSize = 0
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState
                ) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()
                .performTouchInput { swipeDown() }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, atLeast(1)).transferTouchGestureToHost()
        }
    }

    @Test
    fun testSwipeRightInExpandedMode_emptyPhotosGrid_notTransferTouchToHost() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInExpandedState =
            EmbeddedState(isExpanded = true, host = mockSurfaceControlViewHost)

        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        // Force the data service to return no data for all test sources during this test.
        testDataService.mediaSetSize = 0
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState
                ) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_body)))
                .assertIsDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_photos_empty_state_title)))
                .assertIsDisplayed()
                .performTouchInput { swipeRight() }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
        }
    }

    @Test
    fun testLongPressAndDragInCollapsedModeIsNotTransferred() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInExpandedState =
            EmbeddedState(isExpanded = false, host = mockSurfaceControlViewHost)

        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState
                ) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            with(
                composeTestRule
                    .onAllNodes(
                        hasContentDescription(
                            value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                            substring = true,
                        )
                    )
                    .onFirst()
            ) {
                assertIsDisplayed()
                performTouchInput {
                    down(center)
                    advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                    dragInIncrements(totalOffset = getBoundsInRoot().bottom.toPx(), vertical = true)
                    // Wait for the scroll to finish.
                    advanceEventTime(1000)
                    up()
                }
            }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
        }
    }

    @Test
    fun testLongPressAndDragInExpandedModeIsNotTransferred() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInExpandedState =
            EmbeddedState(isExpanded = true, host = mockSurfaceControlViewHost)

        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState
                ) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()
            with(
                composeTestRule
                    .onAllNodes(
                        hasContentDescription(
                            value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                            substring = true,
                        )
                    )
                    .onFirst()
            ) {
                assertIsDisplayed()
                performTouchInput {
                    down(center)
                    advanceEventTime(viewConfiguration.longPressTimeoutMillis + 1)
                    dragInIncrements(totalOffset = getBoundsInRoot().bottom.toPx(), vertical = true)
                    // Wait for the scroll to finish.
                    advanceEventTime(1000)
                    up()
                }
            }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
        }
    }

    @Test
    fun testPinchInExpandedModeIsNotTransferred() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInExpandedState =
            EmbeddedState(isExpanded = true, host = mockSurfaceControlViewHost)

        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState
                ) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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
                .assertIsDisplayed()
                .performTouchInput {
                    pinch(
                        start0 = Offset(10f, 10f),
                        end0 = Offset(10f, 10f),
                        start1 = Offset(20f, 10f),
                        end1 = Offset(50f, 10f),
                        durationMillis = 1000L,
                    )
                }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
        }
    }

    @Test
    fun testPinchInCollapsedModeIsNotTransferred() {
        // This test is only allowed to run on sdk level U+
        assumeTrue(SdkLevel.isAtLeastU())

        // Initialize [EmbeddedState] instances
        @Suppress("DEPRECATION")
        (whenever(mockSurfaceControlViewHost.transferTouchGestureToHost()) { true })
        testEmbeddedStateWithHostInExpandedState =
            EmbeddedState(isExpanded = false, host = mockSurfaceControlViewHost)

        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalEmbeddedState provides testEmbeddedStateWithHostInExpandedState
                ) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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
                .assertIsDisplayed()
                .performTouchInput {
                    pinch(
                        start0 = Offset(10f, 10f),
                        end0 = Offset(10f, 10f),
                        start1 = Offset(20f, 10f),
                        end1 = Offset(50f, 10f),
                        durationMillis = 1000L,
                    )
                }
            // Verify whether the method to transfer touch events is invoked during testing
            @Suppress("DEPRECATION")
            verify(mockSurfaceControlViewHost, never()).transferTouchGestureToHost()
        }
    }

    @Test
    fun testCloudChooseProviderBannerIsNotVisibleInEmbedded() =
        testScope.runTest {
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

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.allowedProviders = listOf(cloudProvider)
            testDataService.setAvailableProviders(listOf(localProvider))

            val resources = getTestableContext().getResources()
            val expectedTitle =
                resources.getString(R.string.photopicker_banner_cloud_choose_provider_title)
            val expectedMessage =
                resources.getString(R.string.photopicker_banner_cloud_choose_provider_message)
            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(100)
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasText(expectedTitle)).assertIsNotDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsNotDisplayed()
        }

    @Test
    fun testCloudChooseAccountBannerIsNotVisibleInEmbedded() =
        testScope.runTest {
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

            bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
            advanceTimeBy(100)
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNode(hasText(expectedTitle)).assertIsNotDisplayed()
            composeTestRule.onNode(hasText(expectedMessage)).assertIsNotDisplayed()
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testHighlightMediaSectionIsNotShownInCollapsedMode() =
        testScope.runTest {
            val testQuery = "cats"
            val info: EmbeddedPhotoPickerFeatureInfo =
                EmbeddedPhotoPickerFeatureInfo.Builder()
                    .setHighlightSearchMediaTextQuery(testQuery)
                    .setHighlightType(MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED)
                    .build()
            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateCollapsed) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

            // Verify search query, Recents label and the SeeAll button are not displayed
            val resources = getTestableContext().getResources()
            val highlightText =
                resources.getString(R.string.photopicker_hsr_suggestions_for_label, testQuery)
            composeTestRule.onNode(hasText(highlightText)).assertIsNotDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_see_all_button_label)),
                    useUnmergedTree = true,
                )
                .assertIsNotDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_recents_label)),
                    useUnmergedTree = true,
                )
                .assertIsNotDisplayed()
            // Verify the lazy grid is displayed, there should be only one scrollable component
            // which is the photo grid
            composeTestRule
                .onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .assertCountEquals(1)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testSearchHighlightMediaSectionIsShownInExpandedMode() =
        testScope.runTest {
            assumeTrue(SdkLevel.isAtLeastU())

            val testQuery = "cats"
            val info: EmbeddedPhotoPickerFeatureInfo =
                EmbeddedPhotoPickerFeatureInfo.Builder()
                    .setHighlightSearchMediaTextQuery(testQuery)
                    .setHighlightType(MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED)
                    .build()
            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)
            val callingPackageLabel = "TestPackage"
            configurationManager
                .get()
                .setCaller(
                    callingPackage = "com.android.test.package",
                    callingPackageUid = 12345,
                    callingPackageLabel = callingPackageLabel,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Verify search query, Recents label and the SeeAll button are displayed
            val resources = getTestableContext().getResources()
            val highlightText =
                resources.getString(R.string.photopicker_hsr_suggestions_for_label, testQuery)
            composeTestRule.onNode(hasText(highlightText)).assertIsDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_see_all_button_label)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            // Assert the info icon and tooltip display/dismiss behavior
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_hsr_tooltip_icon_description)
                    )
                )
                .assertIsDisplayed()
                .assert(hasClickAction())
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_hsr_tooltip_icon_description)
                    )
                )
                .performClick()

            val expectedTooltipText =
                resources.getString(R.string.photopicker_hsr_tooltip_text, callingPackageLabel)
            composeTestRule
                .onNode(hasText(expectedTooltipText), useUnmergedTree = true)
                .assertIsDisplayed()

            composeTestRule.mainClock.advanceTimeBy(5000L)

            composeTestRule
                .onNode(hasText(expectedTooltipText), useUnmergedTree = true)
                .assertIsNotDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_recents_label)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            // Verify the lazy grids are displayed, there should be two grid i.e. scrollable
            // components: photogrid with a vertical scroll nad highlight grid with a horizontal
            // scroll
            composeTestRule.onAllNodes(hasScrollAction()).assertCountEquals(2)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
        Flags.FLAG_ENABLE_EMBEDDED_PICKER_EXPANDED_HIGHLIGHT_TYPE_API,
    )
    fun testSearchHighlightMediaGridIsShownInEmbeddedExpandedMode() =
        testScope.runTest {
            assumeTrue(SdkLevel.isAtLeastU())

            val testQuery = "cats"
            val info: EmbeddedPhotoPickerFeatureInfo =
                EmbeddedPhotoPickerFeatureInfo.Builder()
                    .setHighlightSearchMediaTextQuery(testQuery)
                    .setHighlightType(MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED)
                    .setPickerLaunchedInExpandedState(true)
                    .build()
            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)
            val callingPackageLabel = "TestPackage"
            configurationManager
                .get()
                .setCaller(
                    callingPackage = "com.android.test.package",
                    callingPackageUid = 12345,
                    callingPackageLabel = callingPackageLabel,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Verify search page components
            val resources = getTestableContext().getResources()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Current destination should be the photo grid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            composeTestRule.onNode(hasText(testQuery), useUnmergedTree = true).assertIsDisplayed()
            // Assert back button navigates back to the photogrid
            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option)),
                    useUnmergedTree = true,
                )
                .performClick()

            val backRoute = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Current destination should be the photo grid")
                .that(backRoute)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
            // Search bar with placeholder text
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
        Flags.FLAG_ENABLE_EMBEDDED_PICKER_EXPANDED_HIGHLIGHT_TYPE_API,
    )
    fun testHighlightMediaGridIsNotShownInCollapsedMode() =
        testScope.runTest {
            val testQuery = "cats"
            val info: EmbeddedPhotoPickerFeatureInfo =
                EmbeddedPhotoPickerFeatureInfo.Builder()
                    .setHighlightSearchMediaTextQuery(testQuery)
                    .setHighlightType(MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED)
                    .build()
            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateCollapsed) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

            // Verify search page components are not displayed
            val resources = getTestableContext().getResources()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Current destination should be the photo grid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option)),
                    useUnmergedTree = true,
                )
                .assertIsNotDisplayed()
            composeTestRule
                .onNode(hasText(testQuery), useUnmergedTree = true)
                .assertIsNotDisplayed()
            // Verify the lazy grid is displayed, there should be only one scrollable component
            // which is the photo grid
            composeTestRule
                .onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .assertCountEquals(1)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testSearchHighlightMediaSectionIsShownInExpandedMode_nullCallingPackageLabel() =
        testScope.runTest {
            assumeTrue(SdkLevel.isAtLeastU())

            val testQuery = "cats"
            val info: EmbeddedPhotoPickerFeatureInfo =
                EmbeddedPhotoPickerFeatureInfo.Builder()
                    .setHighlightSearchMediaTextQuery(testQuery)
                    .setHighlightType(MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED)
                    .build()
            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)
            // Set a null calling package label
            configurationManager
                .get()
                .setCaller(
                    callingPackage = "com.android.test.package",
                    callingPackageUid = 12345,
                    callingPackageLabel = null,
                )

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Verify search query is displayed
            val resources = getTestableContext().getResources()
            val highlightText =
                resources.getString(R.string.photopicker_hsr_suggestions_for_label, testQuery)
            composeTestRule.onNode(hasText(highlightText)).assertIsDisplayed()

            // Verify the info icon is displayed
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_hsr_tooltip_icon_description)
                    )
                )
                .assertIsDisplayed()
                .assert(hasClickAction())
            // Click the info icon to show the tooltip
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_hsr_tooltip_icon_description)
                    )
                )
                .performClick()

            // Verify the tooltip text uses the generic app label
            val genericAppLabel = resources.getString(R.string.photopicker_hsr_generic_app_label)
            val expectedTooltipText =
                resources.getString(R.string.photopicker_hsr_tooltip_text, genericAppLabel)
            composeTestRule
                .onNode(hasText(expectedTooltipText), useUnmergedTree = true)
                .assertIsDisplayed()

            composeTestRule.mainClock.advanceTimeBy(5000L)

            composeTestRule
                .onNode(hasText(expectedTooltipText), useUnmergedTree = true)
                .assertIsNotDisplayed()
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testAlbumHighlightMediaSectionIsShownInExpandedMode() =
        testScope.runTest {
            assumeTrue(SdkLevel.isAtLeastU())

            val highlightAlbum = HighlightAlbum.HIGHLIGHT_ALBUM_FAVORITES
            val highlightAlbumId = MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_FAVORITES
            val info: EmbeddedPhotoPickerFeatureInfo =
                EmbeddedPhotoPickerFeatureInfo.Builder()
                    .setHighlightAlbumId(highlightAlbumId)
                    .setHighlightType(MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED)
                    .build()
            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.albumMediaSetSize = 1
            testDataService.albumSetSize = 1
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
            testDataService._availableProviders.value =
                listOf(
                    Provider(
                        authority = "local_authority",
                        mediaSource = MediaSource.LOCAL,
                        uid = 1,
                        displayName = "Local Provider",
                    )
                )

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

            // Wait sufficiently for albums list to be available
            // Repeated calls to advanceTimeBy followed by waitForIdle  are necessary because the
            // animations/transitions relies on the passage of time to complete its rendering.
            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            // Verify album name, Recents label and the SeeAll button are displayed. Info icon
            // is not displayed.
            val resources = getTestableContext().getResources()
            composeTestRule
                .onNode(
                    hasText(
                        HighlightAlbum.getAlbumNameFromAlbum(getTestableContext(), highlightAlbum)
                    )
                )
                .assertIsDisplayed()
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_hsr_tooltip_icon_description)
                    )
                )
                .assertIsNotDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_see_all_button_label)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_recents_label)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            // Verify the lazy grids are displayed, there should be two grid i.e. scrollable
            // components: photogrid with a vertical scroll and highlight grid with a horizontal
            // scroll
            composeTestRule.onAllNodes(hasScrollAction()).assertCountEquals(2)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
        Flags.FLAG_ENABLE_EMBEDDED_PICKER_EXPANDED_HIGHLIGHT_TYPE_API,
    )
    fun testAlbumHighlightMediaGridIsShownInEmbeddedExpandedMode() =
        testScope.runTest {
            assumeTrue(SdkLevel.isAtLeastU())

            val highlightAlbum = HighlightAlbum.HIGHLIGHT_ALBUM_FAVORITES
            val highlightAlbumId = MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_FAVORITES
            val info: EmbeddedPhotoPickerFeatureInfo =
                EmbeddedPhotoPickerFeatureInfo.Builder()
                    .setHighlightAlbumId(highlightAlbumId)
                    .setHighlightType(MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED)
                    .setPickerLaunchedInExpandedState(true)
                    .build()
            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)

            val testDataService = dataService.get() as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.albumMediaSetSize = 1
            testDataService.albumSetSize = 1
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
            testDataService._availableProviders.value =
                listOf(
                    Provider(
                        authority = "local_authority",
                        mediaSource = MediaSource.LOCAL,
                        uid = 1,
                        displayName = "Local Provider",
                    )
                )

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

            // Wait sufficiently for albums list to be available
            // Repeated calls to advanceTimeBy followed by waitForIdle  are necessary because the
            // animations/transitions relies on the passage of time to complete its rendering.
            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            // Verify elements of the album media page
            val resources = getTestableContext().getResources()
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Current destination should be the album media grid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.HIGHLIGHT_ALBUM_MEDIA_GRID.route)

            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            composeTestRule
                .onNode(
                    hasText(
                        HighlightAlbum.getAlbumNameFromAlbum(getTestableContext(), highlightAlbum)
                    ),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()

            // Verify back takes you the collections grid
            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option)),
                    useUnmergedTree = true,
                )
                .performClick()
            val backRoute = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Current destination should be the collections grid")
                .that(backRoute)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testHighlightMediaSectionIsNotShownInExpandedModeWithEmptyTestQuery() =
        testScope.runTest {
            val testQuery = ""
            val info: EmbeddedPhotoPickerFeatureInfo =
                EmbeddedPhotoPickerFeatureInfo.Builder()
                    .setHighlightSearchMediaTextQuery(testQuery)
                    .setHighlightType(MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED)
                    .build()
            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

            // Verify search query, Recents label and the SeeAll button are not displayed
            val resources = getTestableContext().getResources()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_see_all_button_label)),
                    useUnmergedTree = true,
                )
                .assertIsNotDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_recents_label)),
                    useUnmergedTree = true,
                )
                .assertIsNotDisplayed()

            // Verify the lazy grid is displayed, there should be only one scrollable component
            // which is the photo grid
            composeTestRule.onAllNodes(hasScrollAction()).assertCountEquals(1)
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH, Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER)
    fun testVoiceSearchInputIsNotAvailableInEmbedded() =
        testScope.runTest {
            configurationManager
                .get()
                .setCaller(
                    callingPackage = "com.android.test.package",
                    callingPackageUid = 12345,
                    callingPackageLabel = "Test Package",
                )

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

            advanceTimeBy(100)
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
                .performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(
                            R.string.photopicker_search_voice_search_button_description
                        )
                    )
                )
                .assertIsNotDisplayed()
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
    )
    fun testPhotoGridWithDefaultAspectRatioDisplaysSquareThumbnail() =
        testScope.runTest {
            val uiParams = PhotoPickerUiCustomizationParams.Builder().build()
            val info =
                EmbeddedPhotoPickerFeatureInfo.Builder().setUiCustomizationParams(uiParams).build()

            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

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
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
    )
    fun testPhotoGridWithCustomPortraitAspectRatioDisplaysPortraitThumbnail() =
        testScope.runTest {
            val uiParams =
                PhotoPickerUiCustomizationParams.Builder()
                    .setAspectRatio(PhotoPickerUiCustomizationParams.ASPECT_RATIO_PORTRAIT_9_16)
                    .build()
            val info =
                EmbeddedPhotoPickerFeatureInfo.Builder().setUiCustomizationParams(uiParams).build()

            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

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
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    fun testPhotoGridDragSelect_skipsDisabledItems_embeddedMode() =
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

            val info =
                EmbeddedPhotoPickerFeatureInfo.Builder()
                    .setMaxSelectionLimit(50)
                    .setSelectionParams(selectionParams)
                    .build()
            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)
            configurationManager.get().setCaller("com.android.test", 123, TEST_APP_LABEL)
            advanceTimeBy(100)

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
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
    @EnableFlags(
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    fun testPhotoGridItemWithDisabledReasonCannotBeSelectedInEmbedded() =
        testScope.runTest {
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

            // Set the selection params in the feature info
            val info =
                EmbeddedPhotoPickerFeatureInfo.Builder().setSelectionParams(selectionParams).build()
            configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)
            configurationManager.get().setCaller("com.android.test", 123, TEST_APP_LABEL)

            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
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

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    fun testPhotoGridItemCannotBeSelectedWhenBatchSizeLimitIsExceededInEmbedded() {
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

        // Set the selection params in the feature info
        val info =
            EmbeddedPhotoPickerFeatureInfo.Builder().setSelectionParams(selectionParams).build()
        configurationManager.get().setEmbeddedPhotopickerFeatureInfo(info)
        configurationManager.get().setCaller("com.android.test", 123, TEST_APP_LABEL)

        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                    callEmbeddedPhotopickerApp(
                        embeddedLifecycle = embeddedLifecycle.get(),
                        featureManager = featureManager.get(),
                        selection = selection.get(),
                        events = events.get(),
                    )
                }
            }

            // Wait for the PhotoGridViewModel to load data and for the UI to update.
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            assertMaxBatchSizeLimitEnforced()

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

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_BANNER_REDESIGN)
    fun testBannerShown_embeddedMode_expandedState_withBannerRedesignEnabled() = runTest {
        configurationManager
            .get()
            .setCaller(
                callingPackage = "com.android.test.package",
                callingPackageUid = 12345,
                callingPackageLabel = "Test Package",
            )
        val resources = getTestableContext().getResources()
        val expectedPrivacyMessage =
            resources.getString(R.string.photopicker_privacy_explainer_message, "Test Package")
        bannerManager.get().refreshBanner(BannerLocation.PHOTO_GRID_BANNER)
        advanceTimeBy(100)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalEmbeddedState provides testEmbeddedStateExpanded) {
                callEmbeddedPhotopickerApp(
                    embeddedLifecycle = embeddedLifecycle.get(),
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }
        }

        advanceUntilIdle()

        bannerManager
            .get()
            .showBanner(BannerDefinitions.PRIVACY_EXPLAINER, BannerLocation.PHOTO_GRID_BANNER)
        advanceUntilIdle()
        composeTestRule.onNodeWithText(expectedPrivacyMessage).assertIsDisplayed()
    }

    private suspend fun TestScope.assertMaxBatchSizeLimitEnforced(
        mediaItems: SemanticsNodeInteractionCollection =
            composeTestRule.onAllNodes(
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )
            )
    ) {

        mediaItems.assertCountEquals(2)
        // Select first item
        mediaItems[0].performClick()
        composeTestRule.waitForIdle()
        advanceTimeBy(100)

        assertWithMessage("Selection should contain 1 item")
            .that(selection.get().snapshot().size)
            .isEqualTo(1)

        // Select second item (should fail)
        mediaItems[1].performClick()
        composeTestRule.waitForIdle()
        advanceTimeBy(100)

        // Ensure the click handler did NOT update the selection.
        assertWithMessage(
                "Expected selection to still contain 1 item as second item exceeds batch limit."
            )
            .that(selection.get().snapshot().size)
            .isEqualTo(1)
    }
}
