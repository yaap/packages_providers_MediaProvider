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

package com.android.photopicker.features.search

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import android.widget.photopicker.PhotoPickerSelectionParams
import android.widget.photopicker.PhotoPickerUiCustomizationParams
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.net.toUri
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
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.PhotopickerRuntimeEnv
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.selection.Selection
import com.android.photopicker.data.DataService
import com.android.photopicker.data.TestDataServiceImpl
import com.android.photopicker.data.TestSearchDataServiceImpl
import com.android.photopicker.data.model.Icon
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.PhotopickerFeatureBaseTest
import com.android.photopicker.features.search.data.SearchDataService
import com.android.photopicker.features.search.model.GlobalSearchState
import com.android.photopicker.inject.PhotopickerTestModule
import com.android.photopicker.tests.HiltTestActivity
import com.android.photopicker.util.test.dragInIncrements
import com.android.photopicker.util.test.mockSystemService
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
class SearchFeatureTest : PhotopickerFeatureBaseTest() {

    /* Hilt's rule needs to come first to ensure the DI container is setup for the test. */
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule(activityClass = HiltTestActivity::class.java)
    @get:Rule(order = 2) var setFlagsRule = SetFlagsRule()
    @get:Rule(order = 3)
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

    @Inject lateinit var events: Events
    @Inject lateinit var selection: Selection<Media>
    @Inject lateinit var featureManager: FeatureManager
    @Inject lateinit var userHandle: UserHandle
    @Inject override lateinit var configurationManager: Lazy<ConfigurationManager>
    @Inject lateinit var dataService: DataService
    @Inject lateinit var searchDataService: SearchDataService

    @BindValue @ApplicationOwned val contentResolver: ContentResolver = MockContentResolver()

    @Inject lateinit var mockContext: Context
    @Mock lateinit var mockUserManager: UserManager
    @Mock lateinit var mockPackageManager: PackageManager
    @Mock lateinit var mockConnectivityManager: ConnectivityManager

    val deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>> =
        mapOf(
            PrefetchResultKey.SEARCH_STATE to
                runBlocking {
                    async {
                        return@async GlobalSearchState.ENABLED
                    }
                }
        )

    private val cloudProviderName = "My cloud"
    var localProvider = Provider("local_authority", MediaSource.LOCAL, 0, "Local")
    var cloudProvider = Provider("cloud_authority", MediaSource.REMOTE, 1, cloudProviderName)

    private val MEDIA_ITEM_CONTENT_DESCRIPTION_SUBSTRING = "taken on"

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        hiltRule.inject()
        setupTestForUserMonitor(mockContext, mockUserManager, contentResolver, mockPackageManager)
        mockSystemService(mockContext, ConnectivityManager::class.java) { mockConnectivityManager }
    }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_USAGE,
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
    )
    fun testSearchGrid_withDefaultAspectRatio_displaysSquareThumbnail() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            advanceUntilIdle()

            // Click on the search bar to enter the search view
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            advanceUntilIdle()

            // Enter a query and perform search
            val searchQuery = "test"
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .performTextInput(searchQuery)
            composeTestRule.onNodeWithText(searchQuery).performImeAction()

            // Wait for results
            advanceUntilIdle()
            composeTestRule.waitForIdle()
            advanceUntilIdle()
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
    fun testSearchGrid_withPortraitAspectRatio_displaysPortraitThumbnail() =
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

            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            advanceUntilIdle()

            // Click on the search bar to enter the search view
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            advanceUntilIdle()

            // Enter a query and perform search
            val searchQuery = "test"
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .performTextInput(searchQuery)
            composeTestRule.onNodeWithText(searchQuery).performImeAction()

            // Wait for results
            advanceUntilIdle()
            composeTestRule.waitForIdle()
            advanceUntilIdle()
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
    fun testSearchGrid_withUiCustomizationParams_isIgnoredIfFlagDisabled() =
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

            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            advanceUntilIdle()

            // Click on the search bar to enter the search view
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            advanceUntilIdle()

            // Enter a query and perform search
            val searchQuery = "test"
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .performTextInput(searchQuery)
            composeTestRule.onNodeWithText(searchQuery).performImeAction()

            // Wait for results
            advanceUntilIdle()
            composeTestRule.waitForIdle()
            advanceUntilIdle()
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

    /* Ensures the Search feature is not enabled when flag is disabled. */
    @Test
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchFeature_whenFlagDisabled_isNotEnabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage("SearchBar is always enabled when search flag is disabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)

        val testGetContentConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(Intent.ACTION_GET_CONTENT)
                intent(Intent(Intent.ACTION_GET_CONTENT))
            }
        assertWithMessage("Search Feature is always enabled when search flag is disabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testGetContentConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)

        val testUserSelectImagesForAppConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                callingPackage("com.example.test")
                callingPackageUid(1234)
                callingPackageLabel("test_app")
            }
        assertWithMessage("Search Feature is always enabled when search flag is disabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testUserSelectImagesForAppConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)
    }

    /* Verify Search feature is enabled when Search flag enabled.*/
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchFeature_whenFlagEnabled_isEnabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage("Search Feature is not always enabled when search flag enabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(true)

        val testGetContentConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(Intent.ACTION_GET_CONTENT)
                intent(Intent(Intent.ACTION_GET_CONTENT))
            }
        assertWithMessage("Search Feature is not always enabled when search flag enabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testGetContentConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(true)
    }

    /* Verify Search feature is enabled when Search flag and Embedded picker is enabled.*/
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH, Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER)
    fun testSearchFeature_whenEmbeddedPickerEnabled_isEnabled() {
        val testActionPickImagesConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                action(MediaStore.ACTION_PICK_IMAGES)
                intent(Intent(MediaStore.ACTION_PICK_IMAGES))
            }
        assertWithMessage("Search Feature is not always enabled when search flag enabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testActionPickImagesConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(true)

        val testGetContentConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                action(Intent.ACTION_GET_CONTENT)
                intent(Intent(Intent.ACTION_GET_CONTENT))
            }
        assertWithMessage("Search Feature is not always enabled when search flag enabled")
            .that(
                SearchFeature.Registration.isEnabled(
                    testGetContentConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(true)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchFeature_inPermissionMode_isDisabled() {
        val testUserSelectImagesForAppConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                callingPackage("com.example.test")
                callingPackageUid(1234)
                callingPackageLabel("test_app")
            }
        assertWithMessage("Search Feature is always enabled in Permission mode")
            .that(
                SearchFeature.Registration.isEnabled(
                    testUserSelectImagesForAppConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH, Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER)
    fun testSearchFeature_whenEmbeddedPickerEnabledInPermissionMode_isDisabled() {
        val testUserSelectImagesForAppConfiguration: PhotopickerConfiguration =
            TestPhotopickerConfiguration.build {
                runtimeEnv(PhotopickerRuntimeEnv.EMBEDDED)
                action(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                intent(Intent(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP))
                callingPackage("com.example.test")
                callingPackageUid(1234)
                callingPackageLabel("test_app")
            }
        assertWithMessage("Search Feature in embedded picker is always enabled in Perission mode")
            .that(
                SearchFeature.Registration.isEnabled(
                    testUserSelectImagesForAppConfiguration,
                    deferredPrefetchResultsMap,
                )
            )
            .isEqualTo(false)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_whenFlagEnabled_isDisplayed() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            composeTestRule
                .onNode(
                    hasText(
                        getTestableContext()
                            .getResources()
                            .getString(R.string.photopicker_search_placeholder_text)
                    )
                )
                .assertIsDisplayed()
            composeTestRule.onNode(
                hasContentDescription(
                    resources.getString(R.string.photopicker_search_placeholder_text)
                )
            )
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_whenClicked_opensSearchViewWithBackAction() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
                .performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            // Asserts search view page with its placeholder text displayed
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .assertIsDisplayed()

            // Perform click action on back button in search bar of search view page
            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option))
                )
                .assert(hasClickAction())
                .performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(300)
            composeTestRule.waitForIdle()
            advanceTimeBy(300)
            composeTestRule.waitForIdle()
            advanceTimeBy(300)

            // Search bar with Search text placeholder is displayed
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_onBackAction_clearsQuery() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            // Input test query in search bar and verify it is displayed
            val testQuery = "testquery"
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .performTextInput(testQuery)

            composeTestRule.onNodeWithText(testQuery).assertIsDisplayed()

            // Perform click action on back button in search bar of search view page
            composeTestRule
                .onNode(
                    hasContentDescription(resources.getString(R.string.photopicker_back_option))
                )
                .performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(300)
            composeTestRule.waitForIdle()
            advanceTimeBy(300)
            composeTestRule.waitForIdle()
            advanceTimeBy(300)

            // Make sure test query is cleared and Search text placeholder is displayed
            composeTestRule.onNodeWithText(testQuery).assertIsNotDisplayed()
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_mimetypeOnlyVideo_showsVideoPlaceHolderText() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayListOf("video/*", "video/mpeg"))
                }
            configurationManager.get().setIntent(testIntent)

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_videos_placeholder_text)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_mimetypeOnlyImage_showsPhotosPlaceHolderText() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayListOf("image/*", "image/png"))
                }
            configurationManager.get().setIntent(testIntent)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_mimetypeImageAndVideo_showsPhotosPlaceHolderText() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayListOf("image/*", "video/*"))
                }
            configurationManager.get().setIntent(testIntent)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_mimeTypeAll_showsPhotosPlaceHolderText() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayListOf("*/*"))
                }
            configurationManager.get().setIntent(testIntent)
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchGridDragSelect() =
        testScope.runTest {

            // Update configuration to support multi-select
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 5)
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
            advanceUntilIdle()

            val resources = getTestableContext().getResources()

            // Click on the search bar to enter the search view
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            advanceUntilIdle()

            // Enter a query and perform search
            val searchQuery = "test"
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .performTextInput(searchQuery)
            composeTestRule.onNodeWithText(searchQuery).performImeAction()

            // Wait twice to let the search results come back and for the UI collectors to pick them
            // up.
            advanceUntilIdle()
            composeTestRule.waitForIdle()
            advanceUntilIdle()
            composeTestRule.waitForIdle()

            val firstPhoto =
                composeTestRule
                    .onAllNodes(hasContentDescription(value = "taken on", substring = true))
                    .onFirst()

            with(firstPhoto) {
                assertIsDisplayed()
                performTouchInput {
                    down(center)
                    // Wait for the long press to register to enable drag-to-select
                    advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
                    dragInIncrements(
                        // Drag across 3 items in the grid.
                        totalOffset = getBoundsInRoot().right.toPx() * 3,
                        vertical = false,
                    )
                    // Wait for the scroll to finish.
                    advanceEventTime(1000)
                    up()
                }
            }

            advanceUntilIdle()

            assertWithMessage("expected items in selection from search grid drag")
                .that(selection.size())
                .isEqualTo(3)
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchGridDragSelectSkipsDisabledItems() =
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

            val testSearchDataService = searchDataService as TestSearchDataServiceImpl
            testSearchDataService.mediaList = mediaList

            // Update configuration to support multi-select
            val testIntent =
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)
                }
            configurationManager.get().setIntent(testIntent)
            advanceTimeBy(100)

            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerApp(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            advanceUntilIdle()

            // Click on the search bar to enter the search view
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            advanceUntilIdle()

            // Enter a query and perform search
            val searchQuery = "test"
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .performTextInput(searchQuery)
            composeTestRule.onNodeWithText(searchQuery).performImeAction()

            // Wait for results
            advanceUntilIdle()
            composeTestRule.waitForIdle()
            advanceUntilIdle()
            composeTestRule.waitForIdle()

            val firstPhoto =
                composeTestRule
                    .onAllNodes(hasContentDescription(value = "taken on", substring = true))
                    .onFirst()

            with(firstPhoto) {
                assertIsDisplayed()
                performTouchInput {
                    down(center)
                    // Wait for the long press to register to enable drag-to-select
                    advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
                    dragInIncrements(
                        // Drag across 3 items in the grid.
                        totalOffset = getBoundsInRoot().right.toPx() * 3,
                        vertical = false,
                    )
                    // Wait for the scroll to finish.
                    advanceEventTime(1000)
                    up()
                }
            }

            advanceUntilIdle()

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
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_withSearchableCloudProvider_showsProviderNameInPlaceholder() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val testSearchDataService = searchDataService as TestSearchDataServiceImpl
            testSearchDataService.setSearchableProviders(listOf(cloudProvider))

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            advanceTimeBy(100)

            val expectedSearchBarPlaceholder =
                resources.getString(
                    R.string.photopicker_search_provider_placeholder_text,
                    cloudProviderName,
                )
            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(expectedSearchBarPlaceholder))
                .assertIsDisplayed()
                .performClick()
            advanceTimeBy(100)

            val expectedSearchViewPlaceholder =
                resources.getString(
                    R.string.photopicker_search_with_provider_placeholder_text,
                    cloudProviderName,
                )
            composeTestRule.onNode(hasText(expectedSearchViewPlaceholder)).assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_withSearchableCloudAndLocalProvider_showsDefaultPlaceholder() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            val testSearchDataService = searchDataService as TestSearchDataServiceImpl
            testSearchDataService.setSearchableProviders(listOf(cloudProvider, localProvider))

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            advanceTimeBy(100)

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_placeholderUpdates_whenProviderChanges() =
        testScope.runTest {
            val resources = getTestableContext().getResources()

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            advanceTimeBy(100)

            // Click search bar and check initial placeholder
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
                .performClick()

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .assertIsDisplayed()

            val testSearchDataService = searchDataService as TestSearchDataServiceImpl
            testSearchDataService.setSearchableProviders(listOf(cloudProvider))

            val providerPlaceholder =
                resources.getString(
                    R.string.photopicker_search_with_provider_placeholder_text,
                    cloudProviderName,
                )
            advanceTimeBy(100)
            composeTestRule.onNode(hasText(providerPlaceholder)).assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchBar_whenClicked_opensSearchViewWithVoiceSearchIcon() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
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
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testClearSearchQueryIcon_clearsQuery() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar to focus it
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            composeTestRule.waitForIdle()

            // Input test query in search bar
            val testQuery = "testquery"
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .performTextInput(testQuery)
            composeTestRule.waitForIdle()

            // Verify the clear icon is displayed
            val clearTextContentDescription =
                resources.getString(R.string.photopicker_search_clear_text)
            composeTestRule
                .onNode(hasContentDescription(clearTextContentDescription))
                .assertIsDisplayed()

            // Click the clear icon
            composeTestRule
                .onNode(hasContentDescription(clearTextContentDescription))
                .performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            // Verify the query is cleared
            composeTestRule.onNodeWithText(testQuery).assertIsNotDisplayed()

            // Verify the placeholder is shown again
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .assertIsDisplayed()

            // Verify the clear icon is gone
            composeTestRule
                .onNode(hasContentDescription(clearTextContentDescription))
                .assertIsNotDisplayed()

            // Verify the voice search icon is now displayed
            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(
                            R.string.photopicker_search_voice_search_button_description
                        )
                    )
                )
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchSuggestionBadge_onlyCloudProviderWithIcon_isDisplayed() =
        testScope.runTest {
            val testSearchDataService = searchDataService as TestSearchDataServiceImpl
            testSearchDataService.setSearchableProviders(listOf(cloudProvider))
            val testDataService = dataService as TestDataServiceImpl
            val testIcon = Icon("icon_uri".toUri(), MediaSource.LOCAL)
            testDataService.setProviderToIconMap(mapOf(cloudProvider to testIcon))

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Click on the search bar to enter the search view
            composeTestRule
                .onNode(
                    hasText(
                        getTestableContext()
                            .resources
                            .getString(
                                R.string.photopicker_search_provider_placeholder_text,
                                cloudProviderName,
                            )
                    )
                )
                .performClick()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Verify the cloud provider icon is displayed
            composeTestRule.onNode(hasContentDescription(cloudProviderName)).assertIsDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchSuggestionBadge_onlyCloudProviderWithoutIcon_isNotDisplayed() =
        testScope.runTest {
            val testSearchDataService = searchDataService as TestSearchDataServiceImpl
            testSearchDataService.setSearchableProviders(listOf(cloudProvider))
            val testDataService = dataService as TestDataServiceImpl
            testDataService.setProviderToIconMap(emptyMap())

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Click on the search bar to enter the search view
            composeTestRule
                .onNode(
                    hasText(
                        getTestableContext()
                            .resources
                            .getString(
                                R.string.photopicker_search_provider_placeholder_text,
                                cloudProviderName,
                            )
                    )
                )
                .performClick()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Verify the cloud provider icon is NOT displayed
            composeTestRule.onNode(hasContentDescription(cloudProviderName)).assertDoesNotExist()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    fun testSearchSuggestionBadge_withLocalAndCloudProvider_isNotDisplayed() =
        testScope.runTest {
            val testSearchDataService = searchDataService as TestSearchDataServiceImpl
            testSearchDataService.setSearchableProviders(listOf(cloudProvider, localProvider))
            val testDataService = dataService as TestDataServiceImpl
            val testIcon = Icon("icon_uri".toUri(), MediaSource.LOCAL)
            testDataService.setProviderToIconMap(mapOf(cloudProvider to testIcon))

            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Click on the search bar to enter the search view
            composeTestRule
                .onNode(
                    hasText(
                        getTestableContext()
                            .resources
                            .getString(R.string.photopicker_search_placeholder_text)
                    )
                )
                .performClick()
            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Verify no provider icon is displayed
            composeTestRule
                .onNode(hasContentDescription(localProvider.displayName))
                .assertDoesNotExist()
            composeTestRule
                .onNode(hasContentDescription(cloudProvider.displayName))
                .assertDoesNotExist()
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PHOTOPICKER_DELETE_HISTORY_SUGGESTION,
    )
    fun testHistorySuggestion_whenLongClicked_opensTooltip() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
                .performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            composeTestRule.onNode(hasText("paris")).performTouchInput { longClick() }
            composeTestRule.waitForIdle()
            advanceTimeBy(500)

            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_history_suggestion_delete_text)
                    ),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PHOTOPICKER_DELETE_HISTORY_SUGGESTION,
    )
    fun testNonHistorySuggestion_whenLongClicked_notOpensTooltip() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
                .performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            composeTestRule.onNode(hasText("France")).performTouchInput { longClick() }
            composeTestRule.waitForIdle()
            advanceTimeBy(500)

            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_history_suggestion_delete_text)
                    )
                )
                .assertIsNotDisplayed()
        }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH)
    @DisableFlags(Flags.FLAG_ENABLE_PHOTOPICKER_DELETE_HISTORY_SUGGESTION)
    fun testHistorySuggestion_whenLongClicked_flagOff_NoTooltip() =
        testScope.runTest {
            val resources = getTestableContext().getResources()
            composeTestRule.setContent {
                callPhotopickerMain(
                    featureManager = featureManager,
                    selection = selection,
                    events = events,
                )
            }

            // Perform click action on the Search bar
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .assertIsDisplayed()
                .performClick()
            composeTestRule.waitForIdle()
            advanceTimeBy(1000)

            composeTestRule.onNode(hasText("paris")).performTouchInput { longClick() }
            composeTestRule.waitForIdle()
            advanceTimeBy(500)

            composeTestRule
                .onNode(
                    hasContentDescription(
                        resources.getString(R.string.photopicker_history_suggestion_delete_text)
                    ),
                    useUnmergedTree = true,
                )
                .assertIsNotDisplayed()
        }

    @Test
    @EnableFlags(
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    fun testSearchGridItemWithDisabledReasonCannotBeSelected() =
        testScope.runTest {
            val testSearchDataService = searchDataService as? TestSearchDataServiceImpl
            checkNotNull(testSearchDataService) { "Expected a TestSearchDataServiceImpl" }

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

            testSearchDataService.mediaList = listOf(mediaWithDisabledReason)

            val resources = getTestableContext().resources
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
            advanceUntilIdle()

            // Click on the search bar to enter the search view
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            advanceUntilIdle()

            // Enter a query and perform search
            val searchQuery = "test"
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .performTextInput(searchQuery)
            composeTestRule.onNodeWithText(searchQuery).performImeAction()

            // Wait for results
            advanceUntilIdle()
            composeTestRule.waitForIdle()
            advanceUntilIdle()
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

            advanceTimeBy(100)
            composeTestRule.waitForIdle()

            // Ensure the click handler did NOT update the selection.
            assertWithMessage("Expected selection to be empty as item has disabled reason.")
                .that(selection.snapshot().size)
                .isEqualTo(0)

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
        Flags.FLAG_ENABLE_PHOTOPICKER_SEARCH,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API,
        Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_USAGE,
    )
    fun testSearchGridItemCannotBeSelectedWhenBatchSizeLimitExceeded() =
        testScope.runTest {
            val testSearchDataService = searchDataService as? TestSearchDataServiceImpl
            checkNotNull(testSearchDataService) { "Expected a TestSearchDataServiceImpl" }

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

            testSearchDataService.mediaList = listOf(item1, item2)

            val resources = getTestableContext().resources
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
            advanceUntilIdle()

            // Click on the search bar to enter the search view
            composeTestRule
                .onNode(hasText(resources.getString(R.string.photopicker_search_placeholder_text)))
                .performClick()
            advanceUntilIdle()

            // Enter a query and perform search
            val searchQuery = "test"
            composeTestRule
                .onNode(
                    hasText(
                        resources.getString(R.string.photopicker_search_photos_placeholder_text)
                    )
                )
                .performTextInput(searchQuery)
            composeTestRule.onNodeWithText(searchQuery).performImeAction()

            // Wait for results
            advanceUntilIdle()
            composeTestRule.waitForIdle()
            advanceUntilIdle()
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

            val expectedMessage =
                resources.getString(
                    R.string.photopicker_selection_max_selection_batch_size_error_kb,
                    TEST_APP_LABEL,
                    maxBatchSizeLimit / 1024,
                )

            assertSnackbarIsShown(expectedMessage, composeTestRule)
        }
}
