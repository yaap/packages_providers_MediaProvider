/*
 * Copyright 2025 The Android Open Source Project
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

package features.highlightmediaresults

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.CloudMediaProviderContract.AlbumColumns.ALBUM_ID_FAVORITES
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.os.bundleOf
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.android.photopicker.R
import com.android.photopicker.core.ActivityModule
import com.android.photopicker.core.ApplicationModule
import com.android.photopicker.core.ApplicationOwned
import com.android.photopicker.core.Background
import com.android.photopicker.core.ConcurrencyModule
import com.android.photopicker.core.EmbeddedServiceModule
import com.android.photopicker.core.Main
import com.android.photopicker.core.PhotopickerMain
import com.android.photopicker.core.ViewModelModule
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.core.configuration.DeviceConfigProxy
import com.android.photopicker.core.configuration.FEATURE_HIGHLIGHT_SEARCH_RESULTS
import com.android.photopicker.core.configuration.LocalPhotopickerConfiguration
import com.android.photopicker.core.configuration.NAMESPACE_MEDIAPROVIDER
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestDeviceConfigProxyImpl
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.LocalEvents
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.LocalFeatureManager
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.glide.GlideTestRule
import com.android.photopicker.core.navigation.LocalNavController
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.core.selection.LocalSelection
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.core.theme.PhotopickerTheme
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.TestSearchDataServiceImpl
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.features.highlightmediaresults.HighlightMedia
import com.android.photopicker.features.highlightmediaresults.HighlightMediaResultsFeature
import com.android.photopicker.features.highlightmediaresults.model.HighlightAlbum
import com.android.photopicker.features.highlightmediaresults.model.HighlightQuery
import com.android.photopicker.features.highlightmediaresults.model.HighlightQueryResultsParams
import com.android.photopicker.features.highlightmediaresults.model.QueryResultsHighlightType
import com.android.photopicker.features.search.data.SearchDataService
import com.android.photopicker.features.search.model.GlobalSearchState
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.LocalLocalizationHelper
import com.android.photopicker.util.LocalizationHelper
import com.android.photopicker.util.test.MockContentProviderWrapper
import com.android.photopicker.util.test.StubProvider
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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
    ApplicationModule::class,
    ConcurrencyModule::class,
    EmbeddedServiceModule::class,
    ViewModelModule::class,
)
@HiltAndroidTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class HighlightMediaResultsFeatureTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) var setFlagsRule = SetFlagsRule()
    @get:Rule(order = 3) val glideRule = GlideTestRule()

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

    @Inject lateinit var events: Events
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var userHandle: UserHandle
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    @Inject lateinit var dataService: DataService
    @Inject lateinit var searchDataService: SearchDataService

    @BindValue @ApplicationOwned val contentResolver: ContentResolver = MockContentResolver()
    private lateinit var provider: MockContentProviderWrapper

    @Inject lateinit var mockContext: Context
    @Inject lateinit var deviceConfig: DeviceConfigProxy
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    @Mock lateinit var mockContentProvider: ContentProvider
    private val MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING = "taken on"

    val deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>> =
        mapOf(
            PrefetchResultKey.SEARCH_STATE to
                runBlocking {
                    async {
                        return@async GlobalSearchState.ENABLED
                    }
                }
        )

    private val HIGHLIGHT_GRID_TEST_TAG = "highlight-grid"

    // All highlight search feature enabled tests should be tested only for ACTION_PICK_IMAGES.
    // Any other action will throw an exception while parsing the intent.

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        hiltRule.inject()
        provider = MockContentProviderWrapper(mockContentProvider)
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)

        // Return a resource png so that glide actually has something to load
        whenever(mockContentProvider.openTypedAssetFile(any(), any(), any(), any())) {
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getResources()
                .openRawResourceFd(R.drawable.android)
        }

        val testDeviceConfigProxy =
            checkNotNull(deviceConfig as? TestDeviceConfigProxyImpl) {
                "Expected a TestDeviceConfigProxy"
            }

        testDeviceConfigProxy.setFlag(
            NAMESPACE_MEDIAPROVIDER,
            FEATURE_HIGHLIGHT_SEARCH_RESULTS.first,
            false,
        )
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
    )
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testHighlightMediaFeatureWhenSearchIsDisabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage(
                "HighlightMediaResults feature should be disabled when search is disabled"
            )
            .that(
                HighlightMediaResultsFeature.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    @DisableFlags(
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
    )
    fun testHighlightMediaFeatureWhenHighlightMediaFlagsAreDisabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage(
                "HighlightMediaResults feature should be disabled when its flags are disabled"
            )
            .that(
                HighlightMediaResultsFeature.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH, Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE)
    @DisableFlags(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testHighlightMediaFeatureWhenHighlightMediaApiFlagIsDisabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage(
                "HighlightMediaResults feature should be disabled when API flag is disabled"
            )
            .that(
                HighlightMediaResultsFeature.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
    )
    @DisableFlags(Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE)
    fun testHighlightMediaFeatureWhenHighlightMediaFeatureFlagIsDisabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage(
                "HighlightMediaResults feature should be disabled when feature flag is disabled"
            )
            .that(
                HighlightMediaResultsFeature.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
    )
    fun testHighlightMediaFeatureWhenSearchAndHighlightMediaFeatureFlagAreEnabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage(
                "HighlightMediaResults feature should be enabled when search and highlight media flags " +
                    "are enabled"
            )
            .that(
                HighlightMediaResultsFeature.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(true)
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testHighlightMediaFeatureInEmbeddedWhenSearchAndHighlightMediaFeatureFlagAreEnabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage(
                "HighlightMediaResults feature should be enabled when search and highlight media flags " +
                    "are enabled in embedded mode"
            )
            .that(
                HighlightMediaResultsFeature.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(true)
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    @EnableFlags(
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
    )
    fun testHighlightMediaFeatureWhenAlbumHighlightIsRequested() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                highlightQueryResultsParams(
                    HighlightQueryResultsParams(
                        queryResultsHighlightQuery =
                            HighlightQuery.Album(album = HighlightAlbum.HIGHLIGHT_ALBUM_CAMERA),
                        queryResultsHighlightType =
                            QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                    )
                )
            }
        assertWithMessage(
                "HighlightMediaResults feature should be disabled when its flags are disabled"
            )
            .that(
                HighlightMediaResultsFeature.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(true)
    }

    @Test
    @DisableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
    )
    fun testAlbumHighlightMediaFeatureWithHighlightAndSearchFlagsDisabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                highlightQueryResultsParams(
                    HighlightQueryResultsParams(
                        queryResultsHighlightQuery =
                            HighlightQuery.Album(album = HighlightAlbum.HIGHLIGHT_ALBUM_CAMERA),
                        queryResultsHighlightType =
                            QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                    )
                )
            }
        assertWithMessage(
                "HighlightMediaResults feature should be disabled when its flags are disabled"
            )
            .that(
                HighlightMediaResultsFeature.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testHighlightMediaDisplaysAllUiElementsForSearchHighlightType() =
        testScope.runTest {
            val testQuery = "cats"
            val highlightParams =
                HighlightQueryResultsParams(
                    queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                    queryResultsHighlightQuery = HighlightQuery.Search(testQuery),
                )
            val callingPackageLabel = "TestPackage"

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            callingPackageLabel(callingPackageLabel)
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalFeatureManager provides featureManager,
                    LocalEvents provides events,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                highlightQueryResultsParams(highlightParams)
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                                selectionLimit(50)
                            },
                    ) {
                        // Calling just the Highlight composable to avoid any assertion conflicts
                        // with
                        // the photogrid
                        HighlightGrid()
                    }
                }
            }

            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()

            // Verify highlight query text label, Recents label and the SeeAll button are displayed
            val resources = getTestableContext().getResources()
            val highlightText =
                resources.getString(R.string.photopicker_hsr_suggestions_for_label, testQuery)
            composeTestRule
                .onNode(hasText(highlightText), useUnmergedTree = true)
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

            // Verify the lazy grid is displayed, there should be only one scrollable component
            composeTestRule
                .onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .assertCountEquals(1)

            // Wait for highlight items to be loaded
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Assert all children of the grid i.e. highlight items are clickable items
            composeTestRule
                .onNode(hasScrollAction(), useUnmergedTree = true) // Locates the LazyHorizontalGrid
                .onChildren()
                .assertAll(hasClickAction())

            // Assert the horizontal elements are clickable and selectable in the final selection
            composeTestRule
                .onNode(hasScrollAction(), useUnmergedTree = true) // Locates the LazyHorizontalGrid
                .onChildren()
                .onFirst()
                .performClick()

            advanceTimeBy(100)

            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testHighlightSearchMediaSeeAllButtonInteraction() =
        testScope.runTest {
            val testQuery = "cats"
            val highlightParams =
                HighlightQueryResultsParams(
                    queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                    queryResultsHighlightQuery = HighlightQuery.Search(testQuery),
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalEvents provides events,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                highlightQueryResultsParams(highlightParams)
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ) {
                        // Compose the entire tree for button test
                        PhotopickerMain(disruptiveDataNotification = flow { emit(0) })
                    }
                }
            }

            // Wait sufficiently for albums list to be available
            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            // Assert the UI elements before button click
            val resources = getTestableContext().getResources()
            val highlightText =
                resources.getString(R.string.photopicker_hsr_suggestions_for_label, testQuery)
            composeTestRule
                .onNode(hasText(highlightText), useUnmergedTree = true)
                .assertIsDisplayed()
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
            // There is a vertically scrollable photogrid and a horizontally scrollable highlight
            // grid
            composeTestRule
                .onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .assertCountEquals(2)

            // Click the "See All" button
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_see_all_button_label)),
                    useUnmergedTree = true,
                )
                .performClick()

            // Assert components of the search page to open: back button, search query text is
            // visible
            // with a scrollable grid.
            // Also assert on current destination. For search page, the underlying destination is
            // the
            // PhotoGrid itself with an expanded search bar and its content
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Current destination should be the album media grid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            composeTestRule.onNode(hasText(testQuery), useUnmergedTree = true).assertIsDisplayed()
            composeTestRule
                .onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .assertCountEquals(1)

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

            // Wait for grids to show up
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // There is a vertically scrollable photogrid and a horizontally scrollable highlight
            // grid
            // when back is pressed
            composeTestRule
                .onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .assertCountEquals(2)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testHighlightMediaDisplaysAllUiElementsForAlbumHighlightType() =
        testScope.runTest {
            val highlightAlbum = HighlightAlbum.HIGHLIGHT_ALBUM_FAVORITES
            val highlightParams =
                HighlightQueryResultsParams(
                    queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                    queryResultsHighlightQuery = HighlightQuery.Album(album = highlightAlbum),
                )

            val testDataService = dataService as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
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
            testDataService.albumMediaList = StubProvider.getTestMediaFromStubProvider(count = 15)
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
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalFeatureManager provides featureManager,
                    LocalEvents provides events,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                highlightQueryResultsParams(highlightParams)
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ) {
                        // Compose only the highlight section to prevent assertion conflicts with
                        // the photo grid
                        HighlightGrid()
                    }
                }
            }

            // Wait sufficiently for the album list to be available. Null will be thrown otherwise
            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(2000)

            // Verify album name, Recents label and the SeeAll button are displayed but the info
            // icon is not displayed
            val resources = getTestableContext().getResources()
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_hsr_tooltip_icon_description)
                    )
                )
                .assertIsNotDisplayed()
            composeTestRule
                .onNode(
                    hasText(
                        HighlightAlbum.getAlbumNameFromAlbum(getTestableContext(), highlightAlbum)
                    ),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
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

            // Verify the lazy highlight grid is displayed, there should be only one scrollable
            // component
            composeTestRule
                .onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .assertCountEquals(1)

            // Wait for highlight items to be loaded
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Assert all children of the grid i.e. highlight items are clickable and selectable
            // items
            composeTestRule
                .onNode(hasScrollAction(), useUnmergedTree = true) // Locates the LazyHorizontalGrid
                .onChildren()
                .assertAll(hasClickAction())

            advanceTimeBy(100)

            composeTestRule
                .onNode(hasScrollAction(), useUnmergedTree = true) // Locates the LazyHorizontalGrid
                .onChildren()
                .onFirst()
                .performClick()

            advanceTimeBy(100)

            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testHighlightAlbumMediaSeeAllButtonInteraction() =
        testScope.runTest {
            val highlightAlbum = HighlightAlbum.HIGHLIGHT_ALBUM_FAVORITES
            val highlightParams =
                HighlightQueryResultsParams(
                    queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                    queryResultsHighlightQuery = HighlightQuery.Album(album = highlightAlbum),
                )

            val testDataService = dataService as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
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
            testDataService.albumMediaList = StubProvider.getTestMediaFromStubProvider(count = 15)
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
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalEvents provides events,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                highlightQueryResultsParams(highlightParams)
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ) {
                        // Compose the entire tree to test button behaviour
                        PhotopickerMain(disruptiveDataNotification = flow { emit(0) })
                    }
                }
            }

            // Wait sufficiently for album list to be available
            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            // Verify the UI elements
            composeTestRule
                .onNode(
                    hasText(
                        HighlightAlbum.getAlbumNameFromAlbum(getTestableContext(), highlightAlbum)
                    ),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            val resources = getTestableContext().getResources()
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

            // There is a vertically scrollable photogrid and a horizontally scrollable highlight
            // grid
            composeTestRule
                .onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .assertCountEquals(2)

            // Click the "See All" button
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_see_all_button_label)),
                    useUnmergedTree = true,
                )
                .performClick()
            // Assert components of the album media grid page to open: back button and album name
            // are visible with a scrollable grid.
            // Also assert on the current destination which should be the AlbumMediaGrid
            val route = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Current destination should be the album media grid")
                .that(route)
                .isEqualTo(PhotopickerDestinations.ALBUM_MEDIA_GRID.route)

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
            composeTestRule
                .onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .assertCountEquals(1)

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

            // Wait for the grids to show up
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // There is a vertically scrollable photogrid and a horizontally scrollable highlight
            // grid when back is pressed
            composeTestRule
                .onAllNodes(hasScrollAction(), useUnmergedTree = true)
                .assertCountEquals(2)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testExpandedHighlightTypeForAlbumHighlight() = runTest {
        val highlightAlbum = HighlightAlbum.HIGHLIGHT_ALBUM_FAVORITES
        val highlightParams =
            HighlightQueryResultsParams(
                queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_RESULTS,
                queryResultsHighlightQuery = HighlightQuery.Album(album = highlightAlbum),
            )

        val testDataService = dataService as? TestDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
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
        testDataService.albumMediaList = StubProvider.getTestMediaFromStubProvider(count = 15)
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
            CompositionLocalProvider(
                LocalFeatureManager provides featureManager,
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        startDestination(PhotopickerDestinations.HIGHLIGHT_ALBUM_MEDIA_GRID)
                        highlightQueryResultsParams(highlightParams)
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                        selectionLimit(50)
                    },
                LocalNavController provides createNavController(),
                LocalSelection provides selection,
                LocalEvents provides events,
                LocalLocalizationHelper provides LocalizationHelper(),
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            startDestination(PhotopickerDestinations.HIGHLIGHT_ALBUM_MEDIA_GRID)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                        },
                ) {
                    // Compose the entire tree to test button behaviour
                    PhotopickerMain(disruptiveDataNotification = flow { emit(0) })
                }
            }
        }

        // Wait sufficiently for album list to be available
        advanceTimeBy(3000)
        composeTestRule.waitForIdle()
        advanceTimeBy(1000)
        composeTestRule.waitForIdle()
        advanceTimeBy(1000)
        composeTestRule.waitForIdle()
        advanceTimeBy(1000)

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
                hasText(HighlightAlbum.getAlbumNameFromAlbum(getTestableContext(), highlightAlbum)),
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
        assertWithMessage("Current destination should be the photo grid")
            .that(backRoute)
            .isEqualTo(PhotopickerDestinations.ALBUM_GRID.route)
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testHighlightMediaSectionIsNotShownForEmptyHighlightQuery() = runTest {
        val testQuery = ""
        val highlightParams =
            HighlightQueryResultsParams(
                queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                queryResultsHighlightQuery = HighlightQuery.Search(testQuery),
            )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalFeatureManager provides featureManager,
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        highlightQueryResultsParams(highlightParams)
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                        selectionLimit(50)
                    },
                LocalNavController provides createNavController(),
                LocalSelection provides selection,
                LocalEvents provides events,
                LocalLocalizationHelper provides LocalizationHelper(),
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                        },
                ) {
                    HighlightGrid()
                }
            }
        }

        advanceTimeBy(100)
        composeTestRule.waitForIdle()

        composeTestRule
            .onNode(hasTestTag(HIGHLIGHT_GRID_TEST_TAG), useUnmergedTree = true)
            .assertIsNotDisplayed()
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testHighlightMediaDisplaysNoElementsForEmptyHighlightParams() =
        testScope.runTest {
            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalEvents provides events,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ) {
                        HighlightGrid()
                    }
                }
            }

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasTestTag(HIGHLIGHT_GRID_TEST_TAG), useUnmergedTree = true)
                .assertIsNotDisplayed()
        }

    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    @DisableFlags(Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE)
    fun testHighlightSectionIsNotDisplayedWhenFeatureFlagIsDisabled() =
        testScope.runTest {
            val testQuery = "cats"
            val highlightParams =
                HighlightQueryResultsParams(
                    queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                    queryResultsHighlightQuery = HighlightQuery.Search(testQuery),
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalFeatureManager provides featureManager,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                highlightQueryResultsParams(highlightParams)
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ) {
                        // Calling just the Highlight composable to avoid any assertion conflicts
                        // with
                        // the photogrid
                        HighlightGrid()
                    }
                }
            }

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(hasTestTag(HIGHLIGHT_GRID_TEST_TAG), useUnmergedTree = true)
                .assertIsNotDisplayed()
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun highlightSectionContent_initialState_showsLoadingPlaceholders() =
        testScope.runTest {
            val testQuery = "cats"
            val highlightParams =
                HighlightQueryResultsParams(
                    queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                    queryResultsHighlightQuery = HighlightQuery.Search(testQuery),
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalFeatureManager provides featureManager,
                    LocalEvents provides events,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                highlightQueryResultsParams(highlightParams)
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ) {
                        // Calling just the Highlight composable to avoid any assertion conflicts
                        // with
                        // the photogrid
                        HighlightGrid()
                    }
                }
            }

            // Advance time to allow initial composition but not past timeout
            advanceTimeBy(500) // Small delay to allow composition

            val resources = getTestableContext().getResources()
            val highlightText =
                resources.getString(R.string.photopicker_hsr_suggestions_for_label, testQuery)
            composeTestRule
                .onNode(hasText(highlightText), useUnmergedTree = true)
                .assertIsNotDisplayed()
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
                .assertIsDisplayed()
            // Placeholders are displayed
            composeTestRule
                .onNodeWithContentDescription(
                    resources.getString(R.string.photopicker_hsr_query_placeholder_text)
                )
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithContentDescription(
                    resources.getString(R.string.photopicker_hsr_media_placeholder_text)
                )
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun highlightSectionContent_afterTimeout_whenNoResult_hasNoHighlight() = runTest {
        val testQuery = ""
        val highlightParams =
            HighlightQueryResultsParams(
                queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                queryResultsHighlightQuery = HighlightQuery.Search(testQuery),
            )

        val testDataService = searchDataService as? TestSearchDataServiceImpl
        checkNotNull(testDataService) { "Expected a TestSearchDataServiceImpl" }
        testDataService.mediaSetSize = 0

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalFeatureManager provides featureManager,
                LocalPhotopickerConfiguration provides
                    TestPhotopickerConfiguration.build {
                        highlightQueryResultsParams(highlightParams)
                        action(MediaStore.ACTION_PICK_IMAGES)
                        intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                        selectionLimit(50)
                    },
                LocalNavController provides createNavController(),
                LocalSelection provides selection,
                LocalEvents provides events,
                LocalLocalizationHelper provides LocalizationHelper(),
            ) {
                PhotopickerTheme(
                    isDarkTheme = false,
                    config =
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                        },
                ) {
                    HighlightGrid()
                }
            }
        }

        advanceTimeBy(4000)
        composeTestRule.waitForIdle()

        composeTestRule
            .onNode(hasTestTag(HIGHLIGHT_GRID_TEST_TAG), useUnmergedTree = true)
            .assertIsNotDisplayed()
        composeTestRule.onNode(hasText("Suggestions")).assertIsNotDisplayed()
        composeTestRule.onNode(hasText(testQuery)).assertIsNotDisplayed()
        val resources = getTestableContext().getResources()
        composeTestRule
            .onNode(
                hasText(resources.getString(R.string.photopicker_hsr_recents_label)),
                useUnmergedTree = true,
            )
            .assertIsNotDisplayed()
        // Placeholders are not displayed
        composeTestRule
            .onNodeWithContentDescription(
                resources.getString(R.string.photopicker_hsr_query_placeholder_text)
            )
            .assertIsNotDisplayed()
        composeTestRule
            .onNodeWithContentDescription(
                resources.getString(R.string.photopicker_hsr_media_placeholder_text)
            )
            .assertIsNotDisplayed()
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun highlightSectionContent_whenResults_afterTimeout_showsHighlightGrid() =
        testScope.runTest {
            val testQuery = "Test"
            val highlightParams =
                HighlightQueryResultsParams(
                    queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                    queryResultsHighlightQuery = HighlightQuery.Search(testQuery),
                )

            val testDataService = searchDataService as? TestSearchDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestSearchDataServiceImpl" }
            testDataService.mediaSetSize = 1
            testDataService.mediaList =
                listOf(
                    Media.Image(
                        mediaId = "Test",
                        pickerId = 1000L,
                        authority = "a",
                        mediaSource = MediaSource.LOCAL,
                        mediaUri =
                            Uri.EMPTY.buildUpon()
                                .apply {
                                    scheme("content")
                                    authority("media")
                                    path("picker")
                                    path("a")
                                    path("id")
                                }
                                .build(),
                        glideLoadableUri =
                            Uri.EMPTY.buildUpon()
                                .apply {
                                    scheme("content")
                                    authority(MockContentProviderWrapper.AUTHORITY)
                                    path("id")
                                }
                                .build(),
                        dateTakenMillisLong = 123456789L,
                        sizeInBytes = 1000L,
                        mimeType = "image/png",
                        standardMimeTypeExtension = 1,
                    )
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalEvents provides events,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                highlightQueryResultsParams(highlightParams)
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ) {
                        HighlightGrid()
                    }
                }
            }

            // Repeated calls to advanceTimeBy followed by waitForIdle  are necessary because the
            // animations/transitions relies on the passage of time to complete its rendering.
            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            val resources = getTestableContext().getResources()
            // Placeholders are not displayed
            composeTestRule
                .onNodeWithContentDescription(
                    resources.getString(R.string.photopicker_hsr_query_placeholder_text)
                )
                .assertIsNotDisplayed()
            composeTestRule
                .onNodeWithContentDescription(
                    resources.getString(R.string.photopicker_hsr_media_placeholder_text)
                )
                .assertIsNotDisplayed()

            composeTestRule
                .onNode(hasTestTag(HIGHLIGHT_GRID_TEST_TAG), useUnmergedTree = true)
                .assertIsDisplayed()
            val highlightText =
                resources.getString(R.string.photopicker_hsr_suggestions_for_label, testQuery)
            composeTestRule.onNode(hasText(highlightText)).assertIsDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_recents_label)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_see_all_button_label)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithContentDescription(
                    resources.getString(R.string.photopicker_hsr_media_text)
                )
                .onChildren()
                .filter(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .onFirst()
                .performClick()

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler correctly ran by checking the selection snapshot.
            assertWithMessage("Expected selection to contain an item, but it did not.")
                .that(selection.snapshot().size)
                .isEqualTo(1)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun highlightSectionContent_onProviderChange_resetHighlightGrid() =
        testScope.runTest {
            val testQuery = "Test"
            val highlightParams =
                HighlightQueryResultsParams(
                    queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                    queryResultsHighlightQuery = HighlightQuery.Search(testQuery),
                )

            val testDataService = dataService as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(
                listOf(
                    Provider(
                        authority = "remote_authority",
                        mediaSource = MediaSource.REMOTE,
                        uid = 1,
                        displayName = "Cloud Provider",
                    )
                )
            )

            val testSearchDataService = searchDataService as? TestSearchDataServiceImpl
            checkNotNull(testSearchDataService) { "Expected a TestSearchDataServiceImpl" }
            testSearchDataService.mediaSetSize = 1
            testSearchDataService.mediaList =
                listOf(
                    Media.Image(
                        mediaId = "Test",
                        pickerId = 1000L,
                        authority = "a",
                        mediaSource = MediaSource.LOCAL,
                        mediaUri =
                            Uri.EMPTY.buildUpon()
                                .apply {
                                    scheme("content")
                                    authority("media")
                                    path("picker")
                                    path("a")
                                    path("id")
                                }
                                .build(),
                        glideLoadableUri =
                            Uri.EMPTY.buildUpon()
                                .apply {
                                    scheme("content")
                                    authority(MockContentProviderWrapper.AUTHORITY)
                                    path("id")
                                }
                                .build(),
                        dateTakenMillisLong = 123456789L,
                        sizeInBytes = 1000L,
                        mimeType = "image/png",
                        standardMimeTypeExtension = 1,
                    )
                )

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalEvents provides events,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                highlightQueryResultsParams(highlightParams)
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ) {
                        PhotopickerMain(disruptiveDataNotification = flow { emit(0) })
                    }
                }
            }

            // Repeated calls to advanceTimeBy followed by waitForIdle  are necessary because the
            // animations/transitions relies on the passage of time to complete its rendering.
            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            val resources = getTestableContext().getResources()
            // Placeholders are not displayed
            composeTestRule
                .onNodeWithContentDescription(
                    resources.getString(R.string.photopicker_hsr_query_placeholder_text)
                )
                .assertIsNotDisplayed()
            composeTestRule
                .onNodeWithContentDescription(
                    resources.getString(R.string.photopicker_hsr_media_placeholder_text)
                )
                .assertIsNotDisplayed()

            val highlightText =
                resources.getString(R.string.photopicker_hsr_suggestions_for_label, testQuery)
            composeTestRule.onNode(hasText(highlightText)).assertIsDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_recents_label)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_see_all_button_label)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithContentDescription(
                    resources.getString(R.string.photopicker_hsr_media_text)
                )
                .onChildren()
                .filter(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .assertCountEquals(1)

            testSearchDataService.mediaSetSize = 0
            testSearchDataService.mediaList = null
            testSearchDataService.invalidateFakeInCache()
            advanceTimeBy(500)
            composeTestRule.waitForIdle()

            testDataService.setAvailableProviders(emptyList())
            advanceTimeBy(500)
            composeTestRule.waitForIdle()

            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()

            composeTestRule
                .onNodeWithContentDescription(
                    resources.getString(R.string.photopicker_hsr_media_text)
                )
                .onChildren()
                .filter(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .assertCountEquals(0)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun highlightSectionContent_onProviderChange_showHighlightGrid() =
        testScope.runTest {
            val testQuery = "Test"
            val highlightParams =
                HighlightQueryResultsParams(
                    queryResultsHighlightType = QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION,
                    queryResultsHighlightQuery = HighlightQuery.Search(testQuery),
                )

            val testSearchDataService = searchDataService as? TestSearchDataServiceImpl
            checkNotNull(testSearchDataService) { "Expected a TestSearchDataServiceImpl" }
            testSearchDataService.mediaSetSize = 0
            testSearchDataService.invalidateFakeInCache()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            highlightQueryResultsParams(highlightParams)
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalEvents provides events,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                highlightQueryResultsParams(highlightParams)
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ) {
                        HighlightGrid()
                    }
                }
            }

            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            val resources = getTestableContext().getResources()
            val highlightText =
                resources.getString(R.string.photopicker_hsr_suggestions_for_label, testQuery)

            composeTestRule
                .onNodeWithContentDescription(
                    resources.getString(R.string.photopicker_hsr_media_text)
                )
                .assertIsNotDisplayed()

            composeTestRule.onNode(hasText(highlightText)).assertIsNotDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_see_all_button_label)),
                    useUnmergedTree = true,
                )
                .assertIsNotDisplayed()

            testSearchDataService.invalidateFakeInCache()
            testSearchDataService.mediaSetSize = 1
            testSearchDataService.mediaList =
                listOf(
                    Media.Image(
                        mediaId = "Test",
                        pickerId = 1000L,
                        authority = "a",
                        mediaSource = MediaSource.LOCAL,
                        mediaUri =
                            Uri.EMPTY.buildUpon()
                                .apply {
                                    scheme("content")
                                    authority("media")
                                    path("picker")
                                    path("a")
                                    path("id")
                                }
                                .build(),
                        glideLoadableUri =
                            Uri.EMPTY.buildUpon()
                                .apply {
                                    scheme("content")
                                    authority(MockContentProviderWrapper.AUTHORITY)
                                    path("id")
                                }
                                .build(),
                        dateTakenMillisLong = 123456789L,
                        sizeInBytes = 1000L,
                        mimeType = "image/png",
                        standardMimeTypeExtension = 1,
                    )
                )

            advanceTimeBy(500)
            composeTestRule.waitForIdle()

            val testDataService = dataService as? TestDataServiceImpl
            checkNotNull(testDataService) { "Expected a TestDataServiceImpl" }
            testDataService.setAvailableProviders(emptyList())
            testDataService.setAvailableProviders(
                listOf(
                    Provider(
                        authority = "local_authority",
                        mediaSource = MediaSource.LOCAL,
                        uid = 1,
                        displayName = "Local Provider",
                    )
                )
            )

            // Repeated calls to advanceTimeBy followed by waitForIdle  are necessary because the
            // animations/transitions relies on the passage of time to complete its rendering.
            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(3000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            composeTestRule.onNode(hasText(highlightText)).assertIsDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_recents_label)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            composeTestRule
                .onNode(
                    hasText(resources.getString(R.string.photopicker_hsr_see_all_button_label)),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
            composeTestRule
                .onNodeWithContentDescription(
                    resources.getString(R.string.photopicker_hsr_media_text)
                )
                .onChildren()
                .filter(
                    hasContentDescription(
                        MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING,
                        substring = true,
                    )
                )
                .assertCountEquals(1)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS,
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testExpandedHighlightTypeForNonEmptySearchQuery() =
        testScope.runTest {
            val testQuery = "cats"
            val bundle =
                bundleOf(
                    MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                        MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED,
                    MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY to testQuery,
                )
            val intent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS, bundle)
                }
            // The params need to be set in the intent itself because the view model uses the
            // config manager itself when its init block is executed.
            configurationManager.get().setIntent(intent)

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(intent)
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalEvents provides events,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(intent)
                            },
                    ) {
                        PhotopickerMain(disruptiveDataNotification = flow { emit(0) })
                    }
                }
            }

            // Assert components of the search page that opens up: back button and the search
            // query text is visible since the search text is set as the highlight query.
            // Also assert on current destination. For search page, the underlying destination
            // is the PhotoGrid itself with an expanded search bar.
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
        Flags.FLAG_HIGHLIGHT_SEARCH_RESULTS_FEATURE,
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
    )
    fun testExpandedHighlightTypeForEmptySearchQuery() =
        testScope.runTest {
            val testQuery = ""
            val bundle =
                bundleOf(
                    MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                        MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED,
                    MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY to testQuery,
                )
            val intent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS, bundle)
                }
            // The params need to be set in the intent itself because the view model uses the
            // config manager itself when its init block is executed.
            configurationManager.get().setIntent(intent)

            composeTestRule.setContent {
                CompositionLocalProvider(
                    LocalFeatureManager provides featureManager,
                    LocalPhotopickerConfiguration provides
                        TestPhotopickerConfiguration.build {
                            action(MediaStore.ACTION_PICK_IMAGES)
                            intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            selectionLimit(50)
                        },
                    LocalNavController provides createNavController(),
                    LocalSelection provides selection,
                    LocalEvents provides events,
                    LocalLocalizationHelper provides LocalizationHelper(),
                ) {
                    PhotopickerTheme(
                        isDarkTheme = false,
                        config =
                            TestPhotopickerConfiguration.build {
                                action(MediaStore.ACTION_PICK_IMAGES)
                                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
                            },
                    ) {
                        PhotopickerMain(disruptiveDataNotification = flow { emit(0) })
                    }
                }
            }

            val resources = getTestableContext().getResources()
            // Assert that no back button is visible. There's no point on asserting on empty
            // highlight string matcher.
            // It will give a match. Assert on other picker components instead.
            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option)),
                    useUnmergedTree = true,
                )
                .assertIsNotDisplayed()
            val backRoute = navController.currentBackStackEntry?.destination?.route
            assertWithMessage("Current destination should be the photo grid")
                .that(backRoute)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID.route)
            // Search bar with placeholder text
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
        }

    @Composable
    private fun HighlightGrid(modifier: Modifier = Modifier.testTag(HIGHLIGHT_GRID_TEST_TAG)) {
        HighlightMedia(modifier = modifier)
    }
}
