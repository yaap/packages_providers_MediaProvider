/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.photopicker.features.datescrubber

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
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.SetFlagsRule
import android.test.mock.MockContentResolver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.navigation.testing.TestNavHostController
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
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
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.database.DatabaseManager
import com.android.photopicker.core.embedded.EmbeddedState
import com.android.photopicker.core.embedded.LocalEmbeddedState
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.TestDateScrubberDataServiceImpl
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.features.datescrubber.data.DateScrubberDataService
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.mockSystemService
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
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@UninstallModules(
    ActivityModule::class,
    ApplicationModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class DateScrubberFeatureTest : PhotopickerFeatureBaseTest() {
    companion object {
        private fun isHardwareSupported(): Boolean {
            // These UI tests are not optimised for Watches, TVs, Auto;
            // IoT devices do not have a UI to run these UI tests
            val pm = InstrumentationRegistry.getInstrumentation().context.packageManager
            return !pm.hasSystemFeature(PackageManager.FEATURE_EMBEDDED) &&
                !pm.hasSystemFeature(PackageManager.FEATURE_WATCH) &&
                !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
                !pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        }
    }

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

    @BindValue @ApplicationOwned val contentResolver: ContentResolver = MockContentResolver()
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
    @Inject lateinit var dateScrubberDataService: Lazy<DateScrubberDataService>
    @Inject lateinit var databaseManager: Lazy<DatabaseManager>

    private val MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING = "taken on"
    private val DISPLAYED_DATE_CONTENT_DESCRIPTION_SUBSTRING = "Currently showing:"

    private val DATE_SCRUBBER_CURSOR_DESCRIPTION = "Date Scrubber Cursor"

    val sessionId = generatePickerSessionId()

    val testDateScrubberDataService = TestDateScrubberDataServiceImpl()
    val DATA_SIZE = 300
    private val fixedCurrentDateTime =
        LocalDateTime.of(2025, 8, 26, 12, 0) // August 26, 2025, 12:00 PM

    /**
     * For DATA_SIZE = 300, and assuming [fixedCurrentDateTime] is a reference for the current time,
     * here is what the list would be for the UTC time zone:
     * [(August 2025, 76), (July 2025, 93), (June 2025, 90), (May 2025, 41)]
     */
    // DATA, having different months and years
    val DATA: List<Media>
        get() {
            return buildList() {
                for (i in 1..DATA_SIZE) {
                    add(
                        Media.Image(
                            mediaId = "$i",
                            pickerId = i.toLong(),
                            authority = "a",
                            mediaSource = MediaSource.LOCAL,
                            mediaUri =
                                Uri.EMPTY.buildUpon()
                                    .apply {
                                        scheme("content")
                                        authority("media")
                                        path("picker")
                                        path("a")
                                        path("$i")
                                    }
                                    .build(),
                            glideLoadableUri =
                                Uri.EMPTY.buildUpon()
                                    .apply {
                                        scheme("content")
                                        authority("a")
                                        path("$i")
                                    }
                                    .build(),
                            dateTakenMillisLong =
                                fixedCurrentDateTime
                                    .minus(i.toLong() * 8, ChronoUnit.HOURS)
                                    .toEpochSecond(ZoneOffset.UTC) * 1000,
                            sizeInBytes = 1000L,
                            mimeType = "image/png",
                            standardMimeTypeExtension = 1,
                            width = 512,
                            height = 512,
                        )
                    )
                }
            }
        }

    @Before
    fun setup() {
        Assume.assumeTrue(isHardwareSupported())

        MockitoAnnotations.openMocks(this)
        hiltRule.inject()
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)

        mockSystemService(mockContext, ConnectivityManager::class.java) { mockConnectivityManager }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_DATESCRUBBER)
    fun testDateScrubber_becomesVisibleOnScroll_whenFlagIsEnabled() {
        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        val testDateScrubberDataService =
            dateScrubberDataService.get() as? TestDateScrubberDataServiceImpl
        checkNotNull(testDateScrubberDataService) { "Expected a TestDateScrubberDataServiceImpl" }

        // Prepare fake data for both services
        testDataService.mediaList = DATA
        testDateScrubberDataService.mediaList = DATA

        testScope.runTest {
            // Launch the main PhotoPicker UI
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Give ViewModel a bit of time to load media data
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Matcher to locate all photo items in the grid
            val allPhotosMatcher =
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )

            // Wait until at least one photo node is rendered
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodes(allPhotosMatcher).fetchSemanticsNodes().isNotEmpty()
            }

            // Find the date scrubber cursor and displayed date nodes
            val cursorImage =
                composeTestRule.onNode(hasContentDescription(DATE_SCRUBBER_CURSOR_DESCRIPTION))

            val displayDate =
                composeTestRule.onNode(
                    hasContentDescription(
                        value = DISPLAYED_DATE_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )

            // Initially, both cursor and date label should be hidden
            displayDate.assertIsNotDisplayed()
            cursorImage.assertIsNotDisplayed()

            // Perform a swipe on the media grid to simulate scroll
            val allPhotos = composeTestRule.onAllNodes(allPhotosMatcher)
            val mediaGrid = allPhotos.onFirst().onParent()
            mediaGrid.performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()

            // After scroll, only cursor should be visible (date label still hidden)
            displayDate.assertIsNotDisplayed()
            cursorImage.assertIsDisplayed()
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_DATESCRUBBER)
    fun testDateScrubber_staysHidden_whenFlagIsDisabled() {
        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }

        // Provide fake data to populate the media grid
        testDataService.mediaList = DATA

        testScope.runTest {
            // Launch the main PhotoPicker UI with the flag disabled
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Allow ViewModel to load data and UI to update
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Matcher to locate photo items
            val allPhotosMatcher =
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )

            // Wait until at least one photo item is rendered
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodes(allPhotosMatcher).fetchSemanticsNodes().isNotEmpty()
            }

            // Locate the date scrubber cursor node
            val cursorImage =
                composeTestRule.onNode(hasContentDescription(DATE_SCRUBBER_CURSOR_DESCRIPTION))

            // Initially, cursor should not be visible
            cursorImage.assertIsNotDisplayed()

            // Perform a scroll on the media grid
            val allPhotos = composeTestRule.onAllNodes(allPhotosMatcher)
            val mediaGrid = allPhotos.onFirst().onParent()
            mediaGrid.performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()

            // Even after scroll, cursor must not appear since flag is disabled
            cursorImage.assertIsNotDisplayed()
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_DATESCRUBBER)
    fun testCursorPositionUpdatesOnManualGridScroll() {
        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        val testDateScrubberDataService =
            dateScrubberDataService.get() as? TestDateScrubberDataServiceImpl
        checkNotNull(testDateScrubberDataService) { "Expected a TestDateScrubberDataServiceImpl" }

        // Provide fake data to populate the grid and date scrubber
        testDataService.mediaList = DATA
        testDateScrubberDataService.mediaList = DATA

        testScope.runTest {
            // Launch the PhotoPicker UI
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Give the ViewModel time to load data and update the UI
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Matcher to find photo items in the grid
            val allPhotosMatcher =
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )

            // Wait until at least one photo item is loaded
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodes(allPhotosMatcher).fetchSemanticsNodes().isNotEmpty()
            }

            // Locate the date scrubber cursor node
            val cursorImage =
                composeTestRule.onNode(hasContentDescription(DATE_SCRUBBER_CURSOR_DESCRIPTION))
            // Initially cursor should be hidden
            cursorImage.assertIsNotDisplayed()

            // Perform a swipe on the media grid to trigger scrolling
            val allPhotos = composeTestRule.onAllNodes(allPhotosMatcher)
            val mediaGrid = allPhotos.onFirst().onParent()

            mediaGrid.performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()

            // Cursor should become visible once grid starts scrolling.
            // This implies that viewModel.onGridStartedScrolling() was internally invoked.
            cursorImage.assertIsDisplayed()

            // Capture initial Y position of the cursor
            val initialCursorYPosition = cursorImage.getUnclippedBoundsInRoot().top

            // Scroll again to change the firstVisibleItemIndex
            mediaGrid.performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()

            // Cursor should still be visible
            cursorImage.assertIsDisplayed()

            // Capture final Y position of the cursor
            val finalCursorYPosition = cursorImage.getUnclippedBoundsInRoot().top

            // Cursor Y position should have changed as grid scrolled.
            // This implies that viewModel.onScrollPositionChanged() was internally invoked
            // to update the cursor's scroll position when firstVisibleItemIndex changed.
            assertThat(finalCursorYPosition).isGreaterThan(initialCursorYPosition)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_DATESCRUBBER)
    fun testCursorHidesAfterScrollEnds() {
        val testDataService = dataService.get() as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
        val testDateScrubberDataService =
            dateScrubberDataService.get() as? TestDateScrubberDataServiceImpl
        checkNotNull(testDateScrubberDataService) { "Expected a TestDateScrubberDataServiceImpl" }

        // Provide fake data to populate the grid and date scrubber
        testDataService.mediaList = DATA
        testDateScrubberDataService.mediaList = DATA

        testScope.runTest {
            // Launch the PhotoPicker UI
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager.get(),
                    selection = selection.get(),
                    events = events.get(),
                )
            }

            // Give the ViewModel time to load data and update the UI
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Matcher to find photo items in the grid
            val allPhotosMatcher =
                hasContentDescription(
                    value = MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )

            // Wait until at least one photo item is loaded
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodes(allPhotosMatcher).fetchSemanticsNodes().isNotEmpty()
            }

            // Locate the date scrubber cursor node
            val cursorImage =
                composeTestRule.onNode(hasContentDescription(DATE_SCRUBBER_CURSOR_DESCRIPTION))
            // Initially cursor should be hidden
            cursorImage.assertIsNotDisplayed()

            // Perform a swipe on the media grid to trigger scrolling
            val allPhotos = composeTestRule.onAllNodes(allPhotosMatcher)
            val mediaGrid = allPhotos.onFirst().onParent()

            mediaGrid.performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()

            // Cursor should become visible once grid starts scrolling.
            // This implies that viewModel.onGridStartedScrolling() was internally invoked.
            cursorImage.assertIsDisplayed()

            // Advance the clock by the configured delay for hiding the cursor
            advanceTimeBy(DateScrubberViewModel.DELAY_BEFORE_HIDING_CURSOR_MS + 1)

            // Cursor should now be hidden after scroll ends.
            // This implies that viewModel.onGridStoppedScrolling() was internally invoked
            // to schedule the hide-cursor job.
            cursorImage.assertIsNotDisplayed()
        }
    }

    @Test
    fun testCursorPositionAndDateUpdateOnDrag() = runTest {
        testDateScrubberDataService.mediaList = DATA
        val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

        val parentHeightState = mutableStateOf(0f)
        lateinit var gridState: LazyGridState

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    },
                LocalNavController provides TestNavHostController(getTestableContext()),
            ) {
                gridState = rememberLazyGridState()
                Box(
                    modifier = Modifier.fillMaxSize(), // parent fills screen
                    contentAlignment = Alignment.Center, // center child
                ) {
                    Box(
                        modifier =
                            Modifier.size(300.dp, 500.dp) // fixed size for better visibility
                                .onGloballyPositioned { layoutCoordinates ->
                                    val newHeightPx = layoutCoordinates.size.height.toFloat()
                                    if (parentHeightState.value != newHeightPx) {
                                        parentHeightState.value = newHeightPx
                                    }
                                }
                    ) {
                        DateScrubber(
                            viewModel = viewModel,
                            params =
                                object : LocationParams.WithDateScrubber {
                                    override val parentHeight = parentHeightState
                                    override val gridState = gridState
                                },
                        )
                    }
                }
            }
        }

        // Wait for composition/layout to happen and parentHeight to be set
        composeTestRule.waitUntil(timeoutMillis = 5_000) { parentHeightState.value > 0f }

        composeTestRule.waitForIdle()

        // Calculate offsets based on measured pixels
        val halfHeightPx = parentHeightState.value / 2f
        val topOffsetPx = with(composeTestRule.density) { DATE_SCRUBBER_TOP_OFFSET_MAX.toPx() }
        val bottomOffsetPx =
            with(composeTestRule.density) { DATE_SCRUBBER_BOTTOM_OFFSET_MAX.toPx() }
        val maxScrollOffsetTop = (-halfHeightPx + topOffsetPx).coerceAtMost(0f)
        val maxScrollOffsetBottom = (halfHeightPx - bottomOffsetPx).coerceAtLeast(0f)
        val totalScrollableRange = maxScrollOffsetBottom - maxScrollOffsetTop

        val cursorImage =
            composeTestRule.onNode(hasContentDescription(DATE_SCRUBBER_CURSOR_DESCRIPTION))

        val displayDate =
            composeTestRule.onNode(
                hasContentDescription(
                    value = DISPLAYED_DATE_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )
            )

        // Cursor and date label should not be visible initially
        cursorImage.assertIsNotDisplayed()
        displayDate.assertIsNotDisplayed()

        // Simulate grid starting to scroll to make cursor visible.
        // This implies that viewModel.onGridStartedScrolling() was internally invoked.
        composeTestRule.runOnIdle {
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = DATA_SIZE / 2, // Giving as half of total item To display
                // the cursor in better middle viewport
                maxScrollOffsetTop = maxScrollOffsetTop,
                maxScrollOffsetBottom = maxScrollOffsetBottom,
            )
        }
        composeTestRule.waitForIdle()

        // Cursor should now be visible, but date label still hidden
        cursorImage.assertIsDisplayed()
        displayDate.assertIsNotDisplayed()

        val initialScrollOffset = viewModel.scrollOffset.value

        // Perform a drag gesture on the cursor (down → move → up)
        cursorImage.performTouchInput {
            down(center)
            moveBy(Offset(0f, totalScrollableRange / 4))
            up()
        }

        // Let coroutines/scroll settle
        composeTestRule.waitForIdle()

        val finalScrollOffset = viewModel.scrollOffset.value

        // Check if scrollOffset changed.
        // This implies that viewModel.onDrag() was internally invoked to update scrollOffset.
        assertThat(finalScrollOffset).isGreaterThan(initialScrollOffset)

        cursorImage.assertIsDisplayed()

        // Check if the displayed date label became visible.
        // This implies that viewModel.updateDateDisplayed() was internally invoked to update the
        // date.
        displayDate.assertIsDisplayed()
    }

    @Test
    fun testCursorHidesImmediately_whenDataBecomesNullDuringDrag() = runTest {
        testDateScrubberDataService.mediaList = DATA
        val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

        val parentHeightState = mutableStateOf(0f)
        lateinit var gridState: LazyGridState

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    },
                LocalNavController provides TestNavHostController(getTestableContext()),
            ) {
                gridState = rememberLazyGridState()
                Box(
                    modifier = Modifier.fillMaxSize(), // parent fills screen
                    contentAlignment = Alignment.Center, // center child
                ) {
                    Box(
                        modifier =
                            Modifier.size(300.dp, 500.dp) // fixed size for better visibility
                                .onGloballyPositioned { layoutCoordinates ->
                                    val newHeightPx = layoutCoordinates.size.height.toFloat()
                                    if (parentHeightState.value != newHeightPx) {
                                        parentHeightState.value = newHeightPx
                                    }
                                }
                    ) {
                        DateScrubber(
                            viewModel = viewModel,
                            params =
                                object : LocationParams.WithDateScrubber {
                                    override val parentHeight = parentHeightState
                                    override val gridState = gridState
                                },
                        )
                    }
                }
            }
        }

        // Wait for composition/layout to happen and parentHeight to be set
        composeTestRule.waitUntil(timeoutMillis = 5_000) { parentHeightState.value > 0f }

        composeTestRule.waitForIdle()

        // Calculate offsets based on measured pixels
        val halfHeightPx = parentHeightState.value / 2f
        val topOffsetPx = with(composeTestRule.density) { DATE_SCRUBBER_TOP_OFFSET_MAX.toPx() }
        val bottomOffsetPx =
            with(composeTestRule.density) { DATE_SCRUBBER_BOTTOM_OFFSET_MAX.toPx() }
        val maxScrollOffsetTop = (-halfHeightPx + topOffsetPx).coerceAtMost(0f)
        val maxScrollOffsetBottom = (halfHeightPx - bottomOffsetPx).coerceAtLeast(0f)
        val totalScrollableRange = maxScrollOffsetBottom - maxScrollOffsetTop

        val cursorImage =
            composeTestRule.onNode(hasContentDescription(DATE_SCRUBBER_CURSOR_DESCRIPTION))

        val displayDate =
            composeTestRule.onNode(
                hasContentDescription(
                    value = DISPLAYED_DATE_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )
            )

        // Cursor and date label should not be visible initially
        cursorImage.assertIsNotDisplayed()
        displayDate.assertIsNotDisplayed()

        // Simulate grid starting to scroll to make cursor visible.
        // This implies that viewModel.onGridStartedScrolling() was internally invoked.
        composeTestRule.runOnIdle {
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = DATA_SIZE / 2, // Giving as half of total item To display
                // the cursor in better middle viewport
                maxScrollOffsetTop = maxScrollOffsetTop,
                maxScrollOffsetBottom = maxScrollOffsetBottom,
            )
        }
        composeTestRule.waitForIdle()

        // Cursor should now be visible, but date label still hidden
        cursorImage.assertIsDisplayed()
        displayDate.assertIsNotDisplayed()
        val initialScrollOffset = viewModel.scrollOffset.value

        // Perform a drag gesture on the cursor (down → move → up)
        cursorImage.performTouchInput {
            down(center)
            moveBy(Offset(0f, totalScrollableRange / 4))
            up()
        }

        // Let coroutines/scroll settle
        composeTestRule.waitForIdle()

        val finalScrollOffset = viewModel.scrollOffset.value

        // Check if scrollOffset changed.
        // This implies viewModel.onDrag() was internally invoked to update scrollOffset.
        assertThat(finalScrollOffset).isGreaterThan(initialScrollOffset)

        cursorImage.assertIsDisplayed()

        // Check if the displayed date label became visible.
        // This implies that viewModel.updateDateDisplayed() was internally invoked to update the
        // date.
        displayDate.assertIsDisplayed()

        // Make data null during drag
        testDateScrubberDataService.mediaList = null

        // Perform further drag
        cursorImage.performTouchInput {
            down(center)
            moveBy(Offset(0f, totalScrollableRange / 4))
            up()
        }

        // Let coroutines/scroll settle
        composeTestRule.waitForIdle()

        // Both cursor and date should hide immediately
        cursorImage.assertIsNotDisplayed()
        displayDate.assertIsNotDisplayed()
    }

    @Test
    fun testCursorAndDateAreHiddenAfterDragEnd() = runTest {
        testDateScrubberDataService.mediaList = DATA
        val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

        val parentHeightState = mutableStateOf(0f)
        lateinit var gridState: LazyGridState

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    },
                LocalNavController provides TestNavHostController(getTestableContext()),
            ) {
                gridState = rememberLazyGridState()
                Box(
                    modifier = Modifier.fillMaxSize(), // parent fills screen
                    contentAlignment = Alignment.Center, // center child
                ) {
                    Box(
                        modifier =
                            Modifier.size(300.dp, 500.dp) // fixed size for better visibility
                                .onGloballyPositioned { layoutCoordinates ->
                                    val newHeightPx = layoutCoordinates.size.height.toFloat()
                                    if (parentHeightState.value != newHeightPx) {
                                        parentHeightState.value = newHeightPx
                                    }
                                }
                    ) {
                        DateScrubber(
                            viewModel = viewModel,
                            params =
                                object : LocationParams.WithDateScrubber {
                                    override val parentHeight = parentHeightState
                                    override val gridState = gridState
                                },
                        )
                    }
                }
            }
        }

        // Wait for composition/layout to happen and parentHeight to be set
        composeTestRule.waitUntil(timeoutMillis = 5_000) { parentHeightState.value > 0f }

        composeTestRule.waitForIdle()

        // Calculate offsets based on measured pixels
        val halfHeightPx = parentHeightState.value / 2f
        val topOffsetPx = with(composeTestRule.density) { DATE_SCRUBBER_TOP_OFFSET_MAX.toPx() }
        val bottomOffsetPx =
            with(composeTestRule.density) { DATE_SCRUBBER_BOTTOM_OFFSET_MAX.toPx() }
        val maxScrollOffsetTop = (-halfHeightPx + topOffsetPx).coerceAtMost(0f)
        val maxScrollOffsetBottom = (halfHeightPx - bottomOffsetPx).coerceAtLeast(0f)
        val totalScrollableRange = maxScrollOffsetBottom - maxScrollOffsetTop

        val cursorImage =
            composeTestRule.onNode(hasContentDescription(DATE_SCRUBBER_CURSOR_DESCRIPTION))

        val displayDate =
            composeTestRule.onNode(
                hasContentDescription(
                    value = DISPLAYED_DATE_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )
            )

        // Cursor and date label should not be visible initially
        cursorImage.assertIsNotDisplayed()
        displayDate.assertIsNotDisplayed()

        // Simulate grid starting to scroll to make cursor visible.
        // This implies that viewModel.onGridStartedScrolling() was internally invoked.
        composeTestRule.runOnIdle {
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = DATA_SIZE / 2, // Giving as half of total item To display
                // the cursor in better middle viewport
                maxScrollOffsetTop = maxScrollOffsetTop,
                maxScrollOffsetBottom = maxScrollOffsetBottom,
            )
        }
        composeTestRule.waitForIdle()

        // Cursor should now be visible, but date label still hidden
        cursorImage.assertIsDisplayed()
        displayDate.assertIsNotDisplayed()
        val initialScrollOffset = viewModel.scrollOffset.value

        // Simulate grid stopped scrolling
        // This implies that viewModel.onGridStoppedScrolling() was internally invoked.
        composeTestRule.runOnIdle { viewModel.onGridStoppedScrolling() }
        composeTestRule.waitForIdle()

        // Cursor should now be visible, but date label still hidden
        cursorImage.assertIsDisplayed()
        displayDate.assertIsNotDisplayed()

        // Perform a drag gesture on the cursor (down → move → up)
        cursorImage.performTouchInput {
            down(center)
            moveBy(Offset(0f, totalScrollableRange / 4))
            up()
        }

        // Let coroutines/scroll settle
        composeTestRule.waitForIdle()

        val finalScrollOffset = viewModel.scrollOffset.value

        // Check if scrollOffset changed.
        // This implies viewModel.onDrag() was internally invoked to update scrollOffset.
        assertThat(finalScrollOffset).isGreaterThan(initialScrollOffset)

        cursorImage.assertIsDisplayed()

        // Check if the displayed date label became visible.
        // This implies that viewModel.updateDateDisplayed() was internally invoked to update the
        // date.
        displayDate.assertIsDisplayed()

        // Advance test time so the delayed hide job can run
        advanceTimeBy(DateScrubberViewModel.DELAY_BEFORE_HIDING_CURSOR_MS + 1)

        // After the delay, cursor should be hidden.
        // This implies viewModel.onDragStopped() scheduled the hide-cursor job.
        cursorImage.assertIsNotDisplayed()
        displayDate.assertIsNotDisplayed()
    }

    @Test
    fun testCursorAndDateAreHiddenAfterDragCancel() = runTest {
        testDateScrubberDataService.mediaList = DATA
        val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

        val parentHeightState = mutableStateOf(0f)
        lateinit var gridState: LazyGridState

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        action("TEST_ACTION")
                        intent(Intent("TEST_ACTION"))
                    },
                LocalNavController provides TestNavHostController(getTestableContext()),
            ) {
                gridState = rememberLazyGridState()
                Box(
                    modifier = Modifier.fillMaxSize(), // parent fills screen
                    contentAlignment = Alignment.Center, // center child
                ) {
                    Box(
                        modifier =
                            Modifier.size(300.dp, 500.dp) // fixed size for better visibility
                                .onGloballyPositioned { layoutCoordinates ->
                                    val newHeightPx = layoutCoordinates.size.height.toFloat()
                                    if (parentHeightState.value != newHeightPx) {
                                        parentHeightState.value = newHeightPx
                                    }
                                }
                    ) {
                        DateScrubber(
                            viewModel = viewModel,
                            params =
                                object : LocationParams.WithDateScrubber {
                                    override val parentHeight = parentHeightState
                                    override val gridState = gridState
                                },
                        )
                    }
                }
            }
        }

        // Wait for composition/layout to happen and parentHeight to be set
        composeTestRule.waitUntil(timeoutMillis = 5_000) { parentHeightState.value > 0f }

        composeTestRule.waitForIdle()

        // Calculate offsets based on measured pixels
        val halfHeightPx = parentHeightState.value / 2f
        val topOffsetPx = with(composeTestRule.density) { DATE_SCRUBBER_TOP_OFFSET_MAX.toPx() }
        val bottomOffsetPx =
            with(composeTestRule.density) { DATE_SCRUBBER_BOTTOM_OFFSET_MAX.toPx() }
        val maxScrollOffsetTop = (-halfHeightPx + topOffsetPx).coerceAtMost(0f)
        val maxScrollOffsetBottom = (halfHeightPx - bottomOffsetPx).coerceAtLeast(0f)
        val totalScrollableRange = maxScrollOffsetBottom - maxScrollOffsetTop

        val cursorImage =
            composeTestRule.onNode(hasContentDescription(DATE_SCRUBBER_CURSOR_DESCRIPTION))

        val displayDate =
            composeTestRule.onNode(
                hasContentDescription(
                    value = DISPLAYED_DATE_CONTENT_DESCRIPTION_SUBSTRING,
                    substring = true,
                )
            )

        // Cursor and date label should not be visible initially
        cursorImage.assertIsNotDisplayed()
        displayDate.assertIsNotDisplayed()

        // Simulate grid starting to scroll to make cursor visible.
        // This implies that viewModel.onGridStartedScrolling() was internally invoked.
        composeTestRule.runOnIdle {
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = DATA_SIZE / 2, // Giving as half of total item To display
                // the cursor in better middle viewport
                maxScrollOffsetTop = maxScrollOffsetTop,
                maxScrollOffsetBottom = maxScrollOffsetBottom,
            )
        }
        composeTestRule.waitForIdle()

        // Cursor should now be visible, but date label still hidden
        cursorImage.assertIsDisplayed()
        displayDate.assertIsNotDisplayed()
        val initialScrollOffset = viewModel.scrollOffset.value

        // Simulate grid stopped scrolling
        // This implies that viewModel.onGridStoppedScrolling() was internally invoked.
        composeTestRule.runOnIdle { viewModel.onGridStoppedScrolling() }
        composeTestRule.waitForIdle()

        // Cursor should now be visible, but date label still hidden
        cursorImage.assertIsDisplayed()
        displayDate.assertIsNotDisplayed()

        // Perform a drag gesture on the cursor (down → move → up)
        cursorImage.performTouchInput {
            down(center)
            moveBy(Offset(0f, totalScrollableRange / 4))
            // cancel the pointer gesture (this triggers onDragCancel in detectVerticalDragGestures)
            cancel()
        }

        // Let coroutines/scroll settle
        composeTestRule.waitForIdle()

        val finalScrollOffset = viewModel.scrollOffset.value

        // Check if scrollOffset changed.
        // This implies viewModel.onDrag() was internally invoked to update scrollOffset.
        assertThat(finalScrollOffset).isGreaterThan(initialScrollOffset)

        cursorImage.assertIsDisplayed()

        // Check if the displayed date label became visible.
        // This implies that viewModel.updateDateDisplayed() was internally invoked to update the
        // date.
        displayDate.assertIsDisplayed()

        // Advance test time so the delayed hide job can run
        advanceTimeBy(DateScrubberViewModel.DELAY_BEFORE_HIDING_CURSOR_MS + 1)

        // After the delay, cursor should be hidden.
        // This implies viewModel.onDragCancel callback scheduled the hide-cursor job.
        cursorImage.assertIsNotDisplayed()
        displayDate.assertIsNotDisplayed()
    }

    @Test
    fun testDateScrubberIsEnabledInEmbeddedExpandedMode() = runTest {
        testDateScrubberDataService.mediaList = DATA
        val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

        val parentHeightState = mutableStateOf(0f)
        lateinit var gridState: LazyGridState

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides
                    EmbeddedState(isExpanded = true), // Embedded + Expanded mode
            ) {
                gridState = rememberLazyGridState()
                Box(
                    modifier = Modifier.fillMaxSize(), // parent fills screen
                    contentAlignment = Alignment.Center, // center child
                ) {
                    Box(
                        modifier =
                            Modifier.size(300.dp, 500.dp) // fixed size for predictable testing
                                .onGloballyPositioned { layoutCoordinates ->
                                    val newHeightPx = layoutCoordinates.size.height.toFloat()
                                    if (parentHeightState.value != newHeightPx) {
                                        parentHeightState.value = newHeightPx
                                    }
                                }
                    ) {
                        DateScrubber(
                            viewModel = viewModel,
                            params =
                                object : LocationParams.WithDateScrubber {
                                    override val parentHeight = parentHeightState
                                    override val gridState = gridState
                                },
                        )
                    }
                }
            }
        }

        // Wait for layout measurement and parentHeight initialization
        composeTestRule.waitUntil(timeoutMillis = 5_000) { parentHeightState.value > 0f }
        composeTestRule.waitForIdle()

        // Calculate the valid vertical drag range in pixels
        val halfHeightPx = parentHeightState.value / 2f
        val topOffsetPx = with(composeTestRule.density) { DATE_SCRUBBER_TOP_OFFSET_MAX.toPx() }
        val bottomOffsetPx =
            with(composeTestRule.density) { DATE_SCRUBBER_BOTTOM_OFFSET_MAX.toPx() }
        val maxScrollOffsetTop = (-halfHeightPx + topOffsetPx).coerceAtMost(0f)
        val maxScrollOffsetBottom = (halfHeightPx - bottomOffsetPx).coerceAtLeast(0f)

        val cursorImage =
            composeTestRule.onNode(hasContentDescription(DATE_SCRUBBER_CURSOR_DESCRIPTION))

        // Initially, cursor should not be visible
        cursorImage.assertIsNotDisplayed()

        // Simulate grid starting to scroll to make cursor visible.
        // This implies that viewModel.onGridStartedScrolling() was internally invoked.
        composeTestRule.runOnIdle {
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex = DATA_SIZE / 2, // Giving as half of total item To display
                // the cursor in better middle viewport
                maxScrollOffsetTop = maxScrollOffsetTop,
                maxScrollOffsetBottom = maxScrollOffsetBottom,
            )
        }
        composeTestRule.waitForIdle()

        // Cursor should now be visible in Embedded + Expanded mode
        cursorImage.assertIsDisplayed()
    }

    @Test
    fun testDateScrubberIsNotEnabledInEmbeddedCollapseMode() = runTest {
        testDateScrubberDataService.mediaList = DATA
        val viewModel = DateScrubberViewModel(this.backgroundScope, testDateScrubberDataService)

        val parentHeightState = mutableStateOf(0f)
        lateinit var gridState: LazyGridState

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                    },
                LocalEmbeddedState provides
                    EmbeddedState(isExpanded = false), // Embedded + Collapsed mode
            ) {
                gridState = rememberLazyGridState()
                Box(
                    modifier = Modifier.fillMaxSize(), // parent fills screen
                    contentAlignment = Alignment.Center, // center child
                ) {
                    Box(
                        modifier =
                            Modifier.size(300.dp, 500.dp) // fixed size for predictable testing
                                .onGloballyPositioned { layoutCoordinates ->
                                    val newHeightPx = layoutCoordinates.size.height.toFloat()
                                    if (parentHeightState.value != newHeightPx) {
                                        parentHeightState.value = newHeightPx
                                    }
                                }
                    ) {
                        DateScrubber(
                            viewModel = viewModel,
                            params =
                                object : LocationParams.WithDateScrubber {
                                    override val parentHeight = parentHeightState
                                    override val gridState = gridState
                                },
                        )
                    }
                }
            }
        }

        // Wait for layout measurement and parentHeight initialization
        composeTestRule.waitUntil(timeoutMillis = 5_000) { parentHeightState.value > 0f }
        composeTestRule.waitForIdle()

        // Calculate the valid vertical drag range in pixels (kept for parity with other tests)
        val halfHeightPx = parentHeightState.value / 2f
        val topOffsetPx = with(composeTestRule.density) { DATE_SCRUBBER_TOP_OFFSET_MAX.toPx() }
        val bottomOffsetPx =
            with(composeTestRule.density) { DATE_SCRUBBER_BOTTOM_OFFSET_MAX.toPx() }
        val maxScrollOffsetTop = (-halfHeightPx + topOffsetPx).coerceAtMost(0f)
        val maxScrollOffsetBottom = (halfHeightPx - bottomOffsetPx).coerceAtLeast(0f)

        val cursorImage =
            composeTestRule.onNode(hasContentDescription(DATE_SCRUBBER_CURSOR_DESCRIPTION))

        // In Embedded + Collapsed mode the cursor should not be visible by default
        cursorImage.assertIsNotDisplayed()

        // Try to make the cursor visible by simulating grid start scroll.
        // Even though viewModel.onGridStartedScrolling() may get invoked, the UI must keep the
        // cursor hidden
        // because the DateScrubber should be suppressed in Embedded + Collapsed mode
        // (isEmbeddedAndCollapsed).
        composeTestRule.runOnIdle {
            viewModel.onGridStartedScrolling(
                firstVisibleItemIndex =
                    DATA_SIZE / 2, // Giving as half of totalItems To display cursor
                // in better middle viewport
                maxScrollOffsetTop = maxScrollOffsetTop,
                maxScrollOffsetBottom = maxScrollOffsetBottom,
            )
        }
        composeTestRule.waitForIdle()

        // Cursor must remain hidden in collapsed embedded mode.
        // This implies that the composable logic (isEmbeddedAndCollapsed guard) prevents showing
        // the cursor
        // even when the ViewModel receives grid-started events.
        cursorImage.assertIsNotDisplayed()
    }
}
