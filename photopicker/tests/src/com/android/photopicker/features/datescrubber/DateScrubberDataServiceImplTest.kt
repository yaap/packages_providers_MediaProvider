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

package com.android.photopicker.features.datescrubber

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcel
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.events.Events
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.user.UserProfile
import com.android.photopicker.core.user.UserStatus
import com.android.photopicker.data.DataService
import com.android.photopicker.data.DataServiceImpl
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.TestMediaProvider
import com.android.photopicker.data.TestNotificationServiceImpl
import com.android.photopicker.data.TestPrefetchDataService
import com.android.photopicker.data.model.ItemsPerMonth
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.cloudmedia.CloudMediaFeature
import com.android.photopicker.features.datescrubber.data.DateScrubberDataService
import com.android.photopicker.features.datescrubber.data.DateScrubberDataServiceImpl
import com.android.photopicker.util.test.whenever
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DateScrubberDataServiceImplTest {
    companion object {
        private fun createUserHandle(userId: Int = 0): UserHandle {
            val parcel = Parcel.obtain()
            parcel.writeInt(userId)
            parcel.setDataPosition(0)
            val userHandle = UserHandle(parcel)
            parcel.recycle()
            return userHandle
        }

        private val mediaUpdateUri = Uri.parse("content://media/picker_internal/v2/media/update")
        private val availableProvidersUpdateUri =
            Uri.parse("content://media/picker_internal/v2/available_providers/update")
        private val userProfilePrimary: UserProfile =
            UserProfile(handle = createUserHandle(0), profileType = UserProfile.ProfileType.PRIMARY)
    }

    private lateinit var testFeatureManager: FeatureManager
    private lateinit var testContentProvider: TestMediaProvider
    private lateinit var testContentResolver: ContentResolver
    private lateinit var notificationService: TestNotificationServiceImpl
    @Mock private lateinit var mockMediaProviderClient: MediaProviderClient
    private lateinit var userStatus: UserStatus
    private lateinit var mockContext: Context
    private lateinit var mockPackageManager: PackageManager
    private lateinit var events: Events
    private lateinit var config: StateFlow<PhotopickerConfiguration>
    private lateinit var userStatusFlow: MutableStateFlow<UserStatus>

    @Before
    fun setup() {
        val scope = TestScope()
        testContentProvider = TestMediaProvider()
        testContentResolver = ContentResolver.wrap(testContentProvider)
        notificationService = TestNotificationServiceImpl()
        mockContext = mock(Context::class.java)
        mockPackageManager = mock(PackageManager::class.java)
        config = provideTestConfigurationFlow(scope = scope.backgroundScope)
        MockitoAnnotations.openMocks(this)
        userStatus =
            UserStatus(
                activeUserProfile = userProfilePrimary,
                allProfiles = listOf(userProfilePrimary),
                activeContentResolver = testContentResolver,
            )
        testFeatureManager =
            FeatureManager(
                config,
                scope,
                TestPrefetchDataService(),
                setOf(CloudMediaFeature.Registration),
                setOf<RegisteredEventClass>(),
                setOf<RegisteredEventClass>(),
            )
        userStatusFlow = MutableStateFlow(userStatus)
        events = Events(scope = scope.backgroundScope, config, testFeatureManager)
    }

    /** Verifies that the date scrubber data is fetched once during initialization. */
    @Test
    fun testItemsPerMonthDataFetchOnInitialization() = runTest {
        val dataService = getDataService(this)
        val dateScrubberDataService = getDateScrubberDataService(this, dataService)

        // Wait for the initial data fetch to complete on initialization.
        advanceTimeBy(100)

        // Verify that fetchItemsPerMonth was called once during initialization.
        verify(mockMediaProviderClient, times(1))
            .fetchItemsPerMonth(
                dataService.activeContentResolver.value,
                dataService.availableProviders.value,
                config.value,
            )
    }

    /**
     * Verifies that the date scrubber data is updated when the list of available providers changes.
     *
     * This test simulates a change in available providers and asserts that the `fetchItemsPerMonth`
     * method is called again with the new list of providers to update the date scrubber's data.
     */
    @Test
    fun testUpdateItemsPerMonthDataOnProviderChange() = runTest {
        val dataService = getDataService(this)
        val dateScrubberDataService = getDateScrubberDataService(this, dataService)

        // Wait for the initial data fetch to complete on initialization.
        advanceTimeBy(100)

        // Verify that fetchItemsPerMonth was called once during initialization.
        verify(mockMediaProviderClient, times(1))
            .fetchItemsPerMonth(
                dataService.activeContentResolver.value,
                dataService.availableProviders.value,
                config.value,
            )

        val newAvailableContentProvider =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                )
            )

        testContentProvider.providers = newAvailableContentProvider

        notificationService.dispatchChangeToObservers(availableProvidersUpdateUri)
        advanceTimeBy(100)

        // Verify that fetchItemsPerMonth was called a second time with the new provider list.
        verify(mockMediaProviderClient, times(1))
            .fetchItemsPerMonth(
                dataService.activeContentResolver.value,
                newAvailableContentProvider,
                config.value,
            )
    }

    /**
     * Verifies that media update notifications are throttled correctly.
     *
     * This test simulates a rapid burst of media update notifications and asserts that the
     * `fetchItemsPerMonth` method is not called for every notification.
     */
    @Test
    fun testMediaUpdateNotificationThrottling() = runTest {
        val dataService = getDataService(this)
        val dateScrubberDataService = getDateScrubberDataService(this, dataService)
        advanceTimeBy(100)

        verify(mockMediaProviderClient, times(1))
            .fetchItemsPerMonth(
                dataService.activeContentResolver.value,
                dataService.availableProviders.value,
                config.value,
            )

        // Send a burst of notifications.
        notificationService.dispatchChangeToObservers(mediaUpdateUri)
        notificationService.dispatchChangeToObservers(mediaUpdateUri)
        notificationService.dispatchChangeToObservers(mediaUpdateUri)

        // The first notification should call fetchItemsPerMonth immediately.
        // The collector then starts its delay, and further notifications are conflated.
        advanceTimeBy(100)
        verify(mockMediaProviderClient, times(2))
            .fetchItemsPerMonth(
                dataService.activeContentResolver.value,
                dataService.availableProviders.value,
                config.value,
            )

        // Advance time, but less than the throttle duration.
        // The conflated notification should not have been processed yet.
        advanceTimeBy(DataServiceImpl.UPDATE_FLOW_THROTTLE_MILLIS - 200)
        verify(mockMediaProviderClient, times(2))
            .fetchItemsPerMonth(
                dataService.activeContentResolver.value,
                dataService.availableProviders.value,
                config.value,
            )

        // Advance time past the throttle duration.
        // The conflated notification should now be processed, triggering one final fetch.
        advanceTimeBy(200)
        verify(mockMediaProviderClient, times(3))
            .fetchItemsPerMonth(
                dataService.activeContentResolver.value,
                dataService.availableProviders.value,
                config.value,
            )
    }

    /**
     * Verifies that the date scrubber data is re-fetched when the active ContentResolver changes.
     *
     * This test simulates a change in the active user's ContentResolver and asserts that the
     * `fetchItemsPerMonth` method is called again to get fresh data for the new user profile.
     */
    @Test
    fun testUpdateItemsPerMonthDataOnActiveContentResolverUpdate() = runTest {
        val dataService = getDataService(this)
        val dateScrubberDataService = getDateScrubberDataService(this, dataService)

        // Wait for the initial data fetch to complete on initialization.
        advanceTimeBy(100)

        // Verify that fetchItemsPerMonth was called once during initialization.
        verify(mockMediaProviderClient, times(1))
            .fetchItemsPerMonth(
                dataService.activeContentResolver.value,
                dataService.availableProviders.value,
                config.value,
            )

        val newAvailableContentProvider =
            mutableListOf(
                Provider(
                    authority = "local_authority",
                    mediaSource = MediaSource.LOCAL,
                    uid = 0,
                    displayName = "",
                )
            )

        // Simulate an update to the active ContentResolver.
        updateActiveContentResolver(newAvailableContentProvider)
        advanceTimeBy(1000)

        // Verify that fetchItemsPerMonth was called a second time with the updated resolver and
        // providers.
        verify(mockMediaProviderClient, times(1))
            .fetchItemsPerMonth(
                dataService.activeContentResolver.value,
                newAvailableContentProvider,
                config.value,
            )
    }

    /**
     * Verifies that [DateScrubberDataService.getItemsCountPerMonthList] returns data in correct
     * required "MMMM YYYY" date string format and [DateScrubberDataService.getTotalItemsCount]
     * returns correct items count.
     */
    @Test
    fun testGetItemsPerMonthList() = runTest {
        val rawItemsPerMonthData =
            listOf(
                ItemsPerMonth(year = 2025, month = 8, itemCount = 150),
                ItemsPerMonth(year = 2025, month = 7, itemCount = 200),
                ItemsPerMonth(year = 2025, month = 6, itemCount = 75),
                ItemsPerMonth(year = 2024, month = 12, itemCount = 300),
                ItemsPerMonth(year = 2024, month = 11, itemCount = 120),
                ItemsPerMonth(year = 2024, month = 10, itemCount = 90),
                ItemsPerMonth(year = 2023, month = 9, itemCount = 250),
                ItemsPerMonth(year = 2023, month = 8, itemCount = 180),
                ItemsPerMonth(year = 2023, month = 7, itemCount = 110),
                ItemsPerMonth(year = 2022, month = 6, itemCount = 50),
            )

        // Reformat data received from the backend into the desired "MMMM yyyy" date string format.
        val formattedItemsPerMonthData =
            rawItemsPerMonthData.map { (year, month, count) ->
                val formattedDate =
                    LocalDate.of(year, month, 1)
                        .format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                formattedDate to count
            }

        val totalCount = formattedItemsPerMonthData.sumOf { it.second }

        val dataService = getDataService(this)
        whenever(
                mockMediaProviderClient.fetchItemsPerMonth(
                    dataService.activeContentResolver.value,
                    dataService.availableProviders.value,
                    config.value,
                )
            )
            .thenReturn(rawItemsPerMonthData)

        val dateScrubberDataService = getDateScrubberDataService(this, dataService)
        advanceTimeBy(100)

        verify(mockMediaProviderClient, times(1))
            .fetchItemsPerMonth(
                dataService.activeContentResolver.value,
                dataService.availableProviders.value,
                config.value,
            )

        assertThat(dateScrubberDataService.getItemsCountPerMonthList())
            .isEqualTo(formattedItemsPerMonthData)
        assertThat(dateScrubberDataService.getTotalItemsCount()).isEqualTo(totalCount)
    }

    /**
     * This test verifies that when [MediaProviderClient.fetchItemsPerMonth] throws an exception,
     * the DateScrubberDataService correctly handles the error by setting its ItemsPerMonthList and
     * totalItemsCount as null
     */
    @Test
    fun testGetItemsPerMonthList_onException() = runTest {
        val dataService = getDataService(this)
        whenever(
                mockMediaProviderClient.fetchItemsPerMonth(
                    dataService.activeContentResolver.value,
                    dataService.availableProviders.value,
                    config.value,
                )
            )
            .thenThrow(
                IllegalStateException(
                    "Received a null response for Items Per Month from Test Content Provider."
                )
            )

        val dateScrubberDataService = getDateScrubberDataService(this, dataService)
        advanceTimeBy(100)

        verify(mockMediaProviderClient, times(1))
            .fetchItemsPerMonth(
                dataService.activeContentResolver.value,
                dataService.availableProviders.value,
                config.value,
            )

        assertThat(dateScrubberDataService.getItemsCountPerMonthList()).isEqualTo(null)
        assertThat(dateScrubberDataService.getTotalItemsCount()).isEqualTo(null)
    }

    private fun updateActiveContentResolver(newAvailableContentProvider: MutableList<Provider>) {
        val updatedContentProvider = TestMediaProvider()
        val updatedContentResolver: ContentResolver = ContentResolver.wrap(updatedContentProvider)
        updatedContentProvider.providers = newAvailableContentProvider
        userStatusFlow.update { it.copy(activeContentResolver = updatedContentResolver) }
    }

    private fun getDataService(scope: TestScope): DataService {
        return DataServiceImpl(
            userStatus = userStatusFlow,
            scope = scope.backgroundScope,
            notificationService = notificationService,
            mediaProviderClient = mockMediaProviderClient,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            config = config,
            featureManager = testFeatureManager,
            appContext = mockContext,
            events = events,
            processOwnerHandle = userProfilePrimary.handle,
        )
    }

    private fun getDateScrubberDataService(
        scope: TestScope,
        dataService: DataService,
    ): DateScrubberDataService {
        return DateScrubberDataServiceImpl(
            dataService = dataService,
            config = config,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            mediaProviderClient = mockMediaProviderClient,
            notificationService = notificationService,
        )
    }
}
