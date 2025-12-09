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

package com.android.photopicker.data

import com.android.photopicker.data.model.ItemsPerMonth
import com.android.photopicker.data.model.Media
import com.android.photopicker.features.datescrubber.data.DateScrubberDataService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A test implementation of [DateScrubberDataService] that provides fake ItemsPerMonth data ans
 * total items count accordingly.
 */
class TestDateScrubberDataServiceImpl() : DateScrubberDataService {

    var mediaList: List<Media>? = null

    private val formattedItemsPerMonthData: List<Pair<String, Int>>?
        get() =
            mediaList?.let { list ->
                getItemsCountPerMonthFromMediaList(list).map { (year, month, count) ->
                    val formattedDate =
                        LocalDate.of(year, month, 1)
                            .format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                    formattedDate to count
                }
            }

    override fun getItemsCountPerMonthList(): List<Pair<String, Int>>? {
        return formattedItemsPerMonthData
    }

    override fun getTotalItemsCount(): Int? {
        return mediaList?.size
    }

    /**
     * Converts a list of [Media] items into a list of [ItemsPerMonth] objects.
     *
     * The function groups media items by the year and month they were taken (in a fixed UTC time
     * zone) and returns the count for each month.
     *
     * @param mediaList The list of media items to process.
     * @return A list of [ItemsPerMonth] objects.
     */
    fun getItemsCountPerMonthFromMediaList(mediaList: List<Media>): List<ItemsPerMonth> {
        val mediaByMonth = mutableMapOf<Pair<Int, Int>, Int>()

        mediaList.forEach { mediaItem ->
            val dateTakenSeconds = mediaItem.dateTakenMillisLong / 1000

            // Convert the timestamp assuming local as UTC date and time.
            val localDateTime = LocalDateTime.ofEpochSecond(dateTakenSeconds, 0, ZoneOffset.UTC)

            val year = localDateTime.year
            val month = localDateTime.monthValue
            val yearMonthPair = Pair(year, month)

            // Increment the count for the corresponding month.
            mediaByMonth[yearMonthPair] = mediaByMonth.getOrDefault(yearMonthPair, 0) + 1
        }

        return mediaByMonth
            .map { (yearMonthPair, count) ->
                ItemsPerMonth(
                    year = yearMonthPair.first,
                    month = yearMonthPair.second,
                    itemCount = count,
                )
            }
            .sortedWith(
                compareByDescending<ItemsPerMonth> { it.year }.thenByDescending { it.month }
            ) // Sort from most recent to oldest.
    }
}
