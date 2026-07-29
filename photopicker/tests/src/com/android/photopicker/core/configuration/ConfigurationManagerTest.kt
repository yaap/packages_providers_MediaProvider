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

package com.android.photopicker.core.configuration

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.provider.MediaStore
import android.widget.photopicker.EmbeddedPhotoPickerFeatureInfo
import android.widget.photopicker.PhotoPickerSelectionParams
import android.widget.photopicker.PhotoPickerUiCustomizationParams
import androidx.core.os.bundleOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.filters.SmallTest
import com.android.photopicker.core.events.generatePickerSessionId
import com.android.photopicker.core.navigation.PhotopickerDestinations
import com.android.photopicker.features.highlightmediaresults.model.HighlightAlbum
import com.android.photopicker.features.highlightmediaresults.model.HighlightQuery
import com.android.photopicker.features.highlightmediaresults.model.QueryResultsHighlightType
import com.android.providers.media.flags.Flags
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

/** Unit tests for the [ConfigurationManager] */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationManagerTest {

    @get:Rule val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    // Isolate the test device by providing a test wrapper around device config so that the
    // tests can control the flag values that are returned.
    val deviceConfigProxy = TestDeviceConfigProxyImpl()
    val sessionId = generatePickerSessionId()

    private val MAX_MEDIA_ITEM_SIZE_BYTES = 1024L
    private val MAX_VIDEO_DURATION = Duration.ofSeconds(100L)
    private val MIN_VIDEO_DURATION = Duration.ofSeconds(10L)
    private val MAX_MEDIA_ITEM_RESOLUTION_PIXELS = 1000L
    private val MIN_MEDIA_ITEM_RESOLUTION_PIXELS = 100L
    private val MIME_TYPES = listOf("image/png", "video/mp4")
    private val MAX_SELECTION_BATCH_SIZE_BYTES = 2048L

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        deviceConfigProxy.reset()
    }

    /** Ensures that the [ConfigurationManager] emits its current configuration. */
    @Test
    fun testEmitsConfiguration() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )

            // Expect the default configuration with an action matching the test action.
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            backgroundScope.launch {
                val reportedConfiguration = configurationManager.configuration.first()
                assertThat(reportedConfiguration).isEqualTo(expectedConfiguration)
            }
        }
    }

    /**
     * Ensures that the [ConfigurationManager] emits updated configuration when device flags are
     * changed.
     */
    @Test
    fun testConfigurationEmitsFlagChanges() {
        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration with an action matching the test action.
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            // wait for ConfigurationManager to register a listener
            advanceTimeBy(100)

            deviceConfigProxy.setFlag(
                NAMESPACE_MEDIAPROVIDER,
                FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.first,
                false,
            )

            // wait for debounce
            advanceTimeBy(1100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last())
                .isEqualTo(
                    expectedConfiguration.copy(
                        flags = PhotopickerFlags(CLOUD_MEDIA_ENABLED = false)
                    )
                )
        }
    }

    /**
     * Ensures that the [ConfigurationManager] batches configuration changes that are made within a
     * short period of time.
     */
    @Test
    fun testConfigurationChangesAreBatched() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration with an action matching the test action.
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            // wait for ConfigurationManager to register a listener
            advanceTimeBy(100)

            // Quickly set three flags in succession to their inverse-default values.
            deviceConfigProxy.setFlag(
                NAMESPACE_MEDIAPROVIDER,
                FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.first,
                !FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.second,
            )
            deviceConfigProxy.setFlag(
                NAMESPACE_MEDIAPROVIDER,
                FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST.first,
                "testallowlist",
            )
            deviceConfigProxy.setFlag(
                NAMESPACE_MEDIAPROVIDER,
                FEATURE_PRIVATE_SPACE_ENABLED.first,
                !FEATURE_PRIVATE_SPACE_ENABLED.second,
            )

            // wait for debounce
            advanceTimeBy(1100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last())
                .isEqualTo(
                    expectedConfiguration.copy(
                        flags =
                            PhotopickerFlags(
                                CLOUD_ALLOWED_PROVIDERS = arrayOf("testallowlist"),
                                CLOUD_MEDIA_ENABLED = !FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.second,
                                PRIVATE_SPACE_ENABLED = !FEATURE_PRIVATE_SPACE_ENABLED.second,
                            )
                    )
                )
        }
    }

    /**
     * Checks that the [ConfigurationManager] correctly identifies an authority in the device config
     * and converts it into a package name before emitting a new [PhotopickerConfiguration].
     */
    @Test
    fun testAllowlistedPackagesAreBackwardCompatible() = runTest {
        val configurationManager =
            ConfigurationManager(
                runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                scope = this.backgroundScope,
                dispatcher = StandardTestDispatcher(this.testScheduler),
                deviceConfigProxy,
                sessionId = sessionId,
            )
        // Expect the default configuration with an action matching the test action.
        val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

        val emissions = mutableListOf<PhotopickerConfiguration>()
        backgroundScope.launch { configurationManager.configuration.toList(emissions) }

        // wait for ConfigurationManager to register a listener
        advanceTimeBy(100)

        deviceConfigProxy.setFlag(
            NAMESPACE_MEDIAPROVIDER,
            FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST.first,
            "test.cmp1,test.cmp2.cloudprovider,test.cmp3.cloudpicker",
        )

        // wait for debounce
        advanceTimeBy(1100)

        assertThat(emissions.size).isEqualTo(2)
        assertThat(emissions.first()).isEqualTo(expectedConfiguration)
        assertThat(emissions.last())
            .isEqualTo(
                expectedConfiguration.copy(
                    flags =
                        PhotopickerFlags(
                            CLOUD_ALLOWED_PROVIDERS = arrayOf("test.cmp1", "test.cmp2", "test.cmp3")
                        )
                )
            )
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected action.
     */
    @Test
    fun testSetIntentUpdatesConfiguration() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(Intent("TEST_ACTION"))
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo("TEST_ACTION")
        }
    }

    @Test
    fun testSetCallerUpdatesConfiguration() {

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setCaller(
                callingPackage = "com.caller.package",
                callingPackageUid = 99999,
                callingPackageLabel = "Caller",
            )
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last())
                .isEqualTo(
                    expectedConfiguration.copy(
                        callingPackage = "com.caller.package",
                        callingPackageUid = 99999,
                        callingPackageLabel = "Caller",
                    )
                )
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected selection limit.
     */
    @Test
    fun testSetIntentSetsSelectionLimit() {

        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().selectionLimit)
                .isEqualTo(MediaStore.getPickImagesMaxLimit())
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected mimetypes.
     */
    @Test
    fun testSetIntentSetsMimeTypesSetType() {

        val intent = Intent().setAction(MediaStore.ACTION_PICK_IMAGES).setType("image/png")

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().mimeTypes).isEqualTo(arrayListOf("image/png"))
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected mimetypes.
     */
    @Test
    fun testSetIntentSetsMimeTypesSetExtrasBundle() {

        val intent = Intent().setAction(MediaStore.ACTION_PICK_IMAGES)
        val bundle = bundleOf(Intent.EXTRA_MIME_TYPES to arrayOf("image/png", "video/mp4"))
        intent.putExtras(bundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().mimeTypes).isEqualTo(arrayListOf("image/png", "video/mp4"))
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected mimetypes.
     */
    @Test
    fun testSetIntentSetsMimeTypesSetExtrasIntent() {

        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putStringArrayListExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayListOf("image/png", "video/mp4"),
                )

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().mimeTypes).isEqualTo(arrayListOf("image/png", "video/mp4"))
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration and ignore
     * any unsupported mimetypes.
     */
    @Test
    fun testSetIntentSetsMimeTypesPreventsUnsupportedMimeTypes() {

        val intent = Intent().setAction(MediaStore.ACTION_PICK_IMAGES)
        val bundle = bundleOf(Intent.EXTRA_MIME_TYPES to arrayListOf("application/binary"))
        intent.putExtras(bundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            assertThrows(IllegalIntentExtraException::class.java) {
                configurationManager.setIntent(intent)
            }
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected pickImagesInOrder.
     */
    @Test
    fun testSetIntentSetsPickImagesInOrder() {

        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, true)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().pickImagesInOrder).isTrue()
            assertThat(emissions.last().selectionLimit)
                .isEqualTo(MediaStore.getPickImagesMaxLimit())
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected preSelection URIs.
     */
    @Test
    fun testSetIntentSetsPickImagesPreSelectionUris() {
        val testUriPlaceHolder =
            "content://media/picker/0/com.android.providers.media.photopicker/media/%s"
        val inputUris =
            arrayListOf(
                Uri.parse(String.format(testUriPlaceHolder, "1")),
                Uri.parse(String.format(testUriPlaceHolder, "2")),
            )
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                .putParcelableArrayListExtra(MediaStore.EXTRA_PICKER_PRE_SELECTION_URIS, inputUris)
        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().preSelectedUris).isEqualTo(inputUris)
            assertThat(emissions.last().selectionLimit)
                .isEqualTo(MediaStore.getPickImagesMaxLimit())
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected default launch tab.
     */
    @Test
    fun testSetIntentSetsAlbumStartDestination() {

        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(
                    MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB,
                    MediaStore.PICK_IMAGES_TAB_ALBUMS,
                )

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().startDestination)
                .isEqualTo(PhotopickerDestinations.ALBUM_GRID)
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected default launch tab.
     */
    @Test
    fun testSetIntentSetsPhotoStartDestination() {

        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(
                    MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB,
                    MediaStore.PICK_IMAGES_TAB_IMAGES,
                )

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().startDestination)
                .isEqualTo(PhotopickerDestinations.PHOTO_GRID)
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected default launch tab.
     */
    @Test
    fun testSetIntentSetsDefaultStartDestinationForUnknownValue() {

        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(
                    MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB,
                    // This value isn't valid, and should result in a default start.
                    1000,
                )

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().startDestination).isEqualTo(PhotopickerDestinations.DEFAULT)
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected highlight query params info when HighlightType is HighlightMediaSection.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetIntentForSearchHighlightCollapsedHighlightType() {
        val highlightQuery = "dog"
        val highlightBundle =
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                    MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY to highlightQuery,
            )
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS, highlightBundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightType)
                .isEqualTo(QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightQuery)
                .isEqualTo(HighlightQuery.Search(searchQuery = highlightQuery))
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected highlight search info when HighlightType is the HighlightMediaResults.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetIntentForSearchHighlightExpandedHighlightType() {
        val highlightQuery = "dog"
        val highlightBundle =
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                    MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY to highlightQuery,
            )
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS, highlightBundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightType)
                .isEqualTo(QueryResultsHighlightType.HIGHLIGHT_MEDIA_RESULTS)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightQuery)
                .isEqualTo(HighlightQuery.Search(highlightQuery))
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected highlight search info for album highlight.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetIntentForAlbumHighlight() {
        val highlightAlbumQuery = MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_CAMERA
        val highlightBundle =
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                    MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID to highlightAlbumQuery,
            )
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM, highlightBundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightType)
                .isEqualTo(QueryResultsHighlightType.HIGHLIGHT_MEDIA_RESULTS)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightQuery)
                .isEqualTo(HighlightQuery.Album(album = HighlightAlbum.HIGHLIGHT_ALBUM_CAMERA))
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected highlight search info when HighlightType is invalid.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetIntentForAlbumHighlightInvalidAlbumName() {
        val highlightAlbumQuery = "album"
        val highlightBundle =
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                    MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID to highlightAlbumQuery,
            )
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM, highlightBundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)

            assertThrows(IllegalArgumentException::class.java) {
                configurationManager.setIntent(intent)
            }
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected highlight search info when HighlightType is invalid.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetIntentForSearchInvalidHighlightType() {
        val highlightQuery = "dog"
        val highlightBundle =
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to -1,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY to highlightQuery,
            )
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS, highlightBundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)

            assertThrows(IllegalArgumentException::class.java) {
                configurationManager.setIntent(intent)
            }
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected highlight search info when HighlightType is invalid.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetIntentForAlbumHighlightInvalidHighlightType() {
        val highlightAlbumQuery = MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_CAMERA
        val highlightBundle =
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to -1,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID to highlightAlbumQuery,
            )
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM, highlightBundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)

            assertThrows(IllegalArgumentException::class.java) {
                configurationManager.setIntent(intent)
            }
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected highlight search info when the HighlightSearchQuery is an empty string.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetIntentForSearchEmptyHighlightQuery() {
        val hsrInfoBundle =
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                    MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY to "",
            )
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS, hsrInfoBundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightType)
                .isEqualTo(QueryResultsHighlightType.HIGHLIGHT_MEDIA_RESULTS)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightQuery)
                .isEqualTo(HighlightQuery.Search(searchQuery = ""))
        }
    }

    /** Ensures that [ConfigurationManager#setAction] will throw an exception if query is null */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetIntentForSearchNullHighlightQuery() {
        val highlightBundle =
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                    MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_SEARCH_TEXT_QUERY to null,
            )
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS, highlightBundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)

            assertThrows(IllegalArgumentException::class.java) {
                configurationManager.setIntent(intent)
            }
        }
    }

    /** Ensures that [ConfigurationManager#setAction] will throw an exception if album is null */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetIntentForAlbumNullHighlightAlbum() {
        val highlightBundle =
            bundleOf(
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_TYPE to
                    MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED,
                MediaStore.KEY_PICK_IMAGES_HIGHLIGHT_ALBUM_ID to null,
            )
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_ALBUM, highlightBundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)

            assertThrows(IllegalArgumentException::class.java) {
                configurationManager.setIntent(intent)
            }
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will emit an updated configuration with the
     * expected highlight search info when the EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_INFO bundle is
     * null.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetIntentForNullHighlightSearchInfoBundle() {
        val highlightBundle: Bundle? = null
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_HIGHLIGHT_SEARCH_RESULTS, highlightBundle)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightType)
                .isEqualTo(QueryResultsHighlightType.UNSET_HIGHLIGHT_TYPE)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightQuery)
                .isEqualTo(HighlightQuery.Search(searchQuery = ""))
        }
    }

    /**
     * Ensures that [ConfigurationManager#setIntent] will reject illegal configurations for
     * pickImagesInOrder
     */
    @Test
    fun testSetIntentPickImagesInOrderThrowsOnIllegalConfiguration() {

        val intent =
            Intent()
                .setAction(Intent.ACTION_GET_CONTENT)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_IN_ORDER, true)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            assertThrows(IllegalIntentExtraException::class.java) {
                configurationManager.setIntent(intent)
            }
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(1)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will reject illegal selection limit
     * configurations.
     */
    @Test
    fun testSetIntentSetsSelectionLimitThrowsOnIllegalConfiguration() {

        val intent =
            Intent()
                .setAction(Intent.ACTION_GET_CONTENT)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            assertThrows(IllegalIntentExtraException::class.java) {
                configurationManager.setIntent(intent)
            }
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(1)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will reject illegal startDestination
     * configurations.
     */
    @Test
    fun testSetIntentLaunchTabThrowsOnIllegalConfiguration() {

        val intent =
            Intent()
                .setAction(Intent.ACTION_GET_CONTENT)
                .putExtra(
                    MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB,
                    MediaStore.PICK_IMAGES_TAB_ALBUMS,
                )

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            assertThrows(IllegalIntentExtraException::class.java) {
                configurationManager.setIntent(intent)
            }
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(1)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
        }
    }

    /**
     * Ensures that [ConfigurationManager#setAction] will reject illegal selection limit
     * configurations.
     */
    @Test
    fun testSetIntentSetsSelectionLimitThrowsOnIllegalRange() {

        val intentTooHigh =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                // One higher than the limit so we are outside the range
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit() + 1)

        val intentTooLow =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                // One higher than the limit so we are outside the range
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 0)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            assertThrows(IllegalIntentExtraException::class.java) {
                configurationManager.setIntent(intentTooHigh)
            }
            advanceTimeBy(100)
            advanceTimeBy(100)
            assertThrows(IllegalIntentExtraException::class.java) {
                configurationManager.setIntent(intentTooLow)
            }
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(1)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated
     * [PhotopickerConfiguration] with the expected [PhotopickerConfiguration.selectionLimit].
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER)
    fun testSetEmbeddedPhotopickerFeatureInfoSetsSelectionLimit() {
        val featureInfo = EmbeddedPhotoPickerFeatureInfo.Builder().build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration =
                PhotopickerConfiguration(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    action = "",
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().selectionLimit)
                .isEqualTo(MediaStore.getPickImagesMaxLimit())
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated
     * [PhotopickerConfiguration] with the expected [PhotopickerConfiguration.mimeTypes].
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER)
    fun testSetEmbeddedPhotopickerFeatureInfoSetsMimeTypes() {
        val featureInfo =
            EmbeddedPhotoPickerFeatureInfo.Builder()
                .setMimeTypes(arrayListOf("image/png", "video/mp4"))
                .build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration =
                PhotopickerConfiguration(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    action = "",
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().mimeTypes).isEqualTo(arrayListOf("image/png", "video/mp4"))
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated
     * [PhotopickerConfiguration] with the expected [PhotopickerConfiguration.pickImagesInOrder].
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER)
    fun testSetEmbeddedPhotopickerFeatureInfoSetsPickImagesInOrder() {
        val featureInfo = EmbeddedPhotoPickerFeatureInfo.Builder().setOrderedSelection(true).build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration =
                PhotopickerConfiguration(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    action = "",
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().pickImagesInOrder).isTrue()
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated
     * [PhotopickerConfiguration] with the expected [PhotopickerConfiguration.preSelectedUris].
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER)
    fun testSetEmbeddedPhotopickerFeatureInfoSetsPreSelectedUris() {
        val featureInfo =
            EmbeddedPhotoPickerFeatureInfo.Builder()
                .setPreSelectedUris(arrayListOf(Uri.EMPTY))
                .build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration =
                PhotopickerConfiguration(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    action = "",
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().preSelectedUris).isEqualTo(arrayListOf(Uri.EMPTY))
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated
     * [PhotopickerConfiguration] with the expected
     * [PhotopickerConfiguration.highlightQueryResultsParams] for valid highlightMediaQuery.
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetEmbeddedPhotopickerFeatureInfoForValidHighlightMediaQuery() {
        val highlightMediaQuery = "dog"
        val featureInfo =
            EmbeddedPhotoPickerFeatureInfo.Builder()
                .setHighlightSearchMediaTextQuery(highlightMediaQuery)
                .setHighlightType(MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_EXPANDED)
                .build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration =
                PhotopickerConfiguration(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    action = "",
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightQuery)
                .isEqualTo(HighlightQuery.Search(highlightMediaQuery))
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightType)
                .isEqualTo(QueryResultsHighlightType.HIGHLIGHT_MEDIA_RESULTS)
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated
     * [PhotopickerConfiguration] with the expected
     * [PhotopickerConfiguration.highlightQueryResultsParams] for invalid highlightMediaQuery.
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetEmbeddedPhotopickerFeatureInfoForInvalidHighlightMediaQuery() {
        val highlightMediaQuery = ""
        val featureInfo =
            EmbeddedPhotoPickerFeatureInfo.Builder()
                .setHighlightSearchMediaTextQuery(highlightMediaQuery)
                .build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration =
                PhotopickerConfiguration(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    action = "",
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightQuery)
                .isEqualTo(HighlightQuery.Search(highlightMediaQuery))
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightType)
                .isEqualTo(QueryResultsHighlightType.UNSET_HIGHLIGHT_TYPE)
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated
     * [PhotopickerConfiguration] with the expected
     * [PhotopickerConfiguration.highlightQueryResultsParams] for valid album.
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetEmbeddedPhotopickerFeatureInfoForValidHighlightAlbumName() {
        val highlightAlbumName = MediaStore.PICK_IMAGES_HIGHLIGHT_ALBUM_CAMERA
        val featureInfo =
            EmbeddedPhotoPickerFeatureInfo.Builder()
                .setHighlightAlbumId(highlightAlbumName)
                .setHighlightType(MediaStore.PICK_IMAGES_HIGHLIGHT_TYPE_COLLAPSED)
                .build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration =
                PhotopickerConfiguration(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    action = "",
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightQuery)
                .isEqualTo(HighlightQuery.Album(HighlightAlbum.HIGHLIGHT_ALBUM_CAMERA))
            assertThat(emissions.last().highlightQueryResultsParams.queryResultsHighlightType)
                .isEqualTo(QueryResultsHighlightType.HIGHLIGHT_MEDIA_SECTION)
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated
     * [PhotopickerConfiguration] with the expected
     * [PhotopickerConfiguration.highlightQueryResultsParams] for invalid album.
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_HIGHLIGHT_SEARCH_RESULTS_APIS)
    fun testSetEmbeddedPhotopickerFeatureInfoForInvalidHighlightAlbumName() {
        val highlightAlbumName = "camera"
        val featureInfo =
            EmbeddedPhotoPickerFeatureInfo.Builder().setHighlightAlbumId(highlightAlbumName).build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)

            assertThrows(IllegalArgumentException::class.java) {
                configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            }
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated configuration with the
     * expected location metadata access request in ACTION_PICK_IMAGES.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_LOCATION_METADATA_API)
    fun testSetIntentSetsLocationMetadataRequestedPickImages() {

        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_REQUEST_LOCATION_METADATA_ACCESS, true)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().locationMetadataAccessRequested).isTrue()
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated configuration with the
     * expected location metadata default value in ACTION_PICK_IMAGES.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_LOCATION_METADATA_API)
    fun testSetIntentSetsLocationMetadataRequestedTestDefaultValuePickImages() {

        val intent = Intent().setAction(MediaStore.ACTION_PICK_IMAGES)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().locationMetadataAccessRequested).isFalse()
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated configuration with the
     * expected location metadata access request in ACTION_GET_CONTENT.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_LOCATION_METADATA_API)
    fun testSetIntentSetsLocationMetadataRequestedGetContent() {

        val intent =
            Intent()
                .setAction(Intent.ACTION_GET_CONTENT)
                .putExtra(MediaStore.EXTRA_REQUEST_LOCATION_METADATA_ACCESS, true)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(Intent.ACTION_GET_CONTENT)
            assertThat(emissions.last().locationMetadataAccessRequested).isTrue()
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated configuration with the
     * expected location metadata default value in ACTION_GET_CONTENT.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_LOCATION_METADATA_API)
    fun testSetIntentSetsLocationMetadataRequestedTestDefaultValueGetContent() {

        val intent = Intent().setAction(Intent.ACTION_GET_CONTENT)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(Intent.ACTION_GET_CONTENT)
            assertThat(emissions.last().locationMetadataAccessRequested).isFalse()
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated configuration with the
     * expected location metadata access request.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_LOCATION_METADATA_API)
    fun testSetLocationMetadataRequestedEmbeddedRuntime() {

        val featureInfo =
            EmbeddedPhotoPickerFeatureInfo.Builder().setRequestLocationMetadata(true).build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            // Expect the default configuration
            val expectedConfiguration =
                PhotopickerConfiguration(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    action = "",
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().locationMetadataAccessRequested).isTrue()
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated configuration with the
     * expected location metadata access request.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PICKER_LOCATION_METADATA_API)
    fun testSetLocationMetadataRequestedEmbeddedRuntimeDefaultValue() {

        val featureInfo = EmbeddedPhotoPickerFeatureInfo.Builder().build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            // Expect the default configuration
            val expectedConfiguration =
                PhotopickerConfiguration(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    action = "",
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().locationMetadataAccessRequested).isFalse()
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated configuration with the
     * expected custom [PhotoPickerSelectionParams] in activity runtime.
     */
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API)
    @Test
    fun testSetIntentSetsSelectionParamsForActivityRuntime() {
        val selectionParams = createTestSelectionParams()
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_SELECTION_PARAMS, selectionParams)

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().selectionParams).isNotNull()
            assertTestSelectionParams(emissions.last().selectionParams!!)
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated configuration with the
     * expected custom [PhotoPickerSelectionParams] in embedded runtime.
     */
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API)
    @Test
    fun testSetEmbeddedPhotopickerFeatureInfoSetsSelectionParamsForEmbeddedRuntime() {
        val selectionParams = createTestSelectionParams()
        val featureInfo =
            EmbeddedPhotoPickerFeatureInfo.Builder().setSelectionParams(selectionParams).build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration =
                PhotopickerConfiguration(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    action = "",
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().selectionParams).isNotNull()
            assertTestSelectionParams(emissions.last().selectionParams!!)
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated configuration with the
     * expected custom [PhotoPickerUiCustomizationParams].
     */
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API)
    @Test
    fun testSetIntentSetsUiCustomizationParamsForActivityRuntime() {

        val uiCustomizationOptions = createTestUiCustomizationParams()
        val intent =
            Intent()
                .setAction(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(
                    MediaStore.EXTRA_PICK_IMAGES_UI_CUSTOMIZATION_PARAMS,
                    uiCustomizationOptions,
                )

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.ACTIVITY,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration = PhotopickerConfiguration(action = "", sessionId = sessionId)

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setIntent(intent)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().action).isEqualTo(MediaStore.ACTION_PICK_IMAGES)
            assertThat(emissions.last().uiCustomizationParams).isNotNull()
            assertTestUiCustomizationParams(emissions.last().uiCustomizationParams!!)
        }
    }

    /**
     * Ensures that [ConfigurationManager.configuration] will emit an updated configuration with the
     * expected custom [PhotoPickerUiCustomizationParams] in embedded runtime.
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @RequiresFlagsEnabled(
        Flags.FLAG_ENABLE_EMBEDDED_PHOTOPICKER,
        Flags.FLAG_ENABLE_PHOTOPICKER_UI_CUSTOMIZATION_PARAMS_API,
    )
    fun testSetEmbeddedPhotopickerFeatureInfoSetsUiCustomizationParamsForEmbeddedRuntime() {
        val uiCustomizationOptions = createTestUiCustomizationParams()
        val featureInfo =
            EmbeddedPhotoPickerFeatureInfo.Builder()
                .setUiCustomizationParams(uiCustomizationOptions)
                .build()

        runTest {
            val configurationManager =
                ConfigurationManager(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    scope = this.backgroundScope,
                    dispatcher = StandardTestDispatcher(this.testScheduler),
                    deviceConfigProxy,
                    sessionId = sessionId,
                )
            // Expect the default configuration
            val expectedConfiguration =
                PhotopickerConfiguration(
                    runtimeEnv = PhotopickerRuntimeEnv.EMBEDDED,
                    action = "",
                    sessionId = sessionId,
                )

            val emissions = mutableListOf<PhotopickerConfiguration>()
            backgroundScope.launch { configurationManager.configuration.toList(emissions) }

            advanceTimeBy(100)
            configurationManager.setEmbeddedPhotopickerFeatureInfo(featureInfo)
            advanceTimeBy(100)

            assertThat(emissions.size).isEqualTo(2)
            assertThat(emissions.first()).isEqualTo(expectedConfiguration)
            assertThat(emissions.last().uiCustomizationParams).isNotNull()
            assertTestUiCustomizationParams(emissions.last().uiCustomizationParams!!)
        }
    }

    private fun createTestSelectionParams(): PhotoPickerSelectionParams {
        return PhotoPickerSelectionParams.Builder()
            .setMaxMediaItemSizeInBytes(MAX_MEDIA_ITEM_SIZE_BYTES)
            .setMaxVideoDuration(MAX_VIDEO_DURATION)
            .setMinVideoDuration(MIN_VIDEO_DURATION)
            .setMaxMediaItemResolutionInPixels(MAX_MEDIA_ITEM_RESOLUTION_PIXELS)
            .setMinMediaItemResolutionInPixels(MIN_MEDIA_ITEM_RESOLUTION_PIXELS)
            .setMimeTypes(MIME_TYPES)
            .setMaxSelectionBatchSizeInBytes(MAX_SELECTION_BATCH_SIZE_BYTES)
            .build()
    }

    private fun createTestUiCustomizationParams(): PhotoPickerUiCustomizationParams {
        return PhotoPickerUiCustomizationParams.Builder()
            .setAspectRatio(PhotoPickerUiCustomizationParams.ASPECT_RATIO_PORTRAIT_9_16)
            .build()
    }

    private fun assertTestSelectionParams(params: PhotoPickerSelectionParams) {
        assertThat(params.maxMediaItemSizeInBytes).isEqualTo(MAX_MEDIA_ITEM_SIZE_BYTES)
        assertThat(params.maxVideoDuration).isEqualTo(MAX_VIDEO_DURATION)
        assertThat(params.minVideoDuration).isEqualTo(MIN_VIDEO_DURATION)
        assertThat(params.maxMediaItemResolutionInPixels)
            .isEqualTo(MAX_MEDIA_ITEM_RESOLUTION_PIXELS)
        assertThat(params.minMediaItemResolutionInPixels)
            .isEqualTo(MIN_MEDIA_ITEM_RESOLUTION_PIXELS)
        assertThat(params.mimeTypes).isEqualTo(MIME_TYPES)
        assertThat(params.maxSelectionBatchSizeInBytes).isEqualTo(MAX_SELECTION_BATCH_SIZE_BYTES)
    }

    private fun assertTestUiCustomizationParams(params: PhotoPickerUiCustomizationParams) {
        assertThat(params.aspectRatio)
            .isEqualTo(PhotoPickerUiCustomizationParams.ASPECT_RATIO_PORTRAIT_9_16)
    }
}
