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

/**
 * Powers UI with data for the date scrubber feature in the Photo Grid.
 *
 * This class owns the responsibility to:
 * - fetch Items per month data on demand
 * - keep track of data updates in the data source
 * - detect and refresh stale data
 *
 * NOTE: Currently scoped to the Photo Grid only. Future extensions may generalize this service to
 * support other grids.
 */
interface DateScrubberDataService {
    companion object {
        const val TAG: String = "PhotoPickerDateScrubberDataService"
    }

    /**
     * Get a list of item counts grouped by month.
     *
     * @return A list of Pair<String, Int> where:
     * - The first element [String] represents the date in "MMMM yyyy" format (e.g.,"July 2025"), in
     *   local time.
     * - The second element [Int] represents the total number of items associated with that month in
     *   the Photo grid.
     *
     * Returns null if data is unavailable.
     */
    fun getItemsCountPerMonthList(): List<Pair<String, Int>>?

    /** Get total no of items count currently available in Photo Grid */
    fun getTotalItemsCount(): Int?
}
