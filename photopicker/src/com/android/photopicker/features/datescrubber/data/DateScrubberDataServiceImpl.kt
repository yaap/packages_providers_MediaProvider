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

package com.android.photopicker.features.datescrubber.data

import android.database.ContentObserver
import android.util.Log
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.data.DataService
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.NotificationService
import com.android.photopicker.data.model.ItemsPerMonth
import com.android.photopicker.data.paging.MediaPagingSource.Companion.TAG
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Provides item count information for each available month to correctly position the date scrubber
 * cursor on the Photo grid UI. The data is sourced from a [ContentProvider] called [MediaProvider].
 *
 * Changes in the underlying data of [MediaProvider] are observed using [ContentObserver]s. When a
 * change is detected, the data is re-fetched from the [MediaProvider] process, and the item count
 * per month data list is updated accordingly.
 *
 * Note: Currently supports Photo grid data only, but may be extended for other grids in the future.
 *
 * @param dataService Core Picker's data service that provides data related to core functionality.
 * @param config A [StateFlow] that emits [PhotopickerConfiguration] changes.
 * @param scope The [CoroutineScope] the data flows will be shared in.
 * @param dispatcher A [CoroutineDispatcher] to run the coroutines in.
 * @param mediaProviderClient An instance of [MediaProviderClient] responsible to get data from
 *   MediaProvider.
 * @param notificationService An instance of [NotificationService] responsible to listen to data
 *   change notifications.
 */
class DateScrubberDataServiceImpl(
    private val dataService: DataService,
    private val config: StateFlow<PhotopickerConfiguration>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val mediaProviderClient: MediaProviderClient,
    private val notificationService: NotificationService,
) : DateScrubberDataService {

    /**
     * Holds a list of pairs representing item count for each month. Each pair consists of a date
     * string in "MMMM yyyy" format (all in local time) and the number of items in that month.
     */
    private val _monthItemsCountList = MutableStateFlow<List<Pair<String, Int>>?>(null)

    /**
     * Total number of media items in the data source after the relevant Photopicker config filters
     * are applied eg. mime type filters
     */
    private val _totalItemsCount = MutableStateFlow<Int?>(null)

    init {
        scope.launch(dispatcher) {
            Log.d(DateScrubberDataService.TAG, "Data update notification received")
            // Listen to media update notifications and update items per month data list
            dataService.mediaInvalidationFlow.collect { fetchItemsPerMonthData() }
        }
    }

    /**
     * Get the Items Per Month Data from [MediaProviderClient.fetchItemsPerMonth] and update
     * [_monthItemsCountList] and [_totalItemsCount].
     *
     * [MediaProviderClient.fetchItemsPerMonth] returns a list of [ItemsPerMonth] objects, where
     * each object represents a year (Int), month (Int), and the corresponding item count. This date
     * info again needs to be reformatted into "MMMM yyyy" (e.g., "January 2024" A desired format in
     * which the date should be displayed along with the date scrubber cursor).
     */
    private suspend fun fetchItemsPerMonthData() {
        try {
            val itemsPerMonthData =
                mediaProviderClient.fetchItemsPerMonth(
                    dataService.activeContentResolver.value,
                    dataService.availableProviders.value,
                    config.value,
                )

            _monthItemsCountList.value =
                itemsPerMonthData.map { (year, month, count) ->
                    val formattedDate =
                        LocalDate.of(year, month, 1)
                            .format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                    formattedDate to count
                }

            _totalItemsCount.value = _monthItemsCountList.value?.sumOf { it.second }
            Log.d(
                DateScrubberDataService.TAG,
                "Received ${_monthItemsCountList.value?.size} months/years with a total of ${_totalItemsCount.value} items.",
            )
        } catch (e: Exception) {
            Log.e(
                DateScrubberDataService.TAG,
                "Could not fetch Items Per Month Data from Media Provider",
                e,
            )
            _monthItemsCountList.value = null
            _totalItemsCount.value = null
        }
    }

    /**
     * Returns a list of Pair<String, Int> where:
     * - The first element [String] represents the date in "MMMM yyyy" format (e.g.,"July 2025"), in
     *   local time.
     * - The second element [Int] represents the total number of items associated with that month.
     */
    override fun getItemsCountPerMonthList(): List<Pair<String, Int>>? {
        return _monthItemsCountList.value
    }

    /** Returns total no of items available in Media table */
    override fun getTotalItemsCount(): Int? {
        return _totalItemsCount.value
    }
}
