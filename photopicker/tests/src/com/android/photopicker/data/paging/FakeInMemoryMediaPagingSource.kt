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

package com.android.photopicker.data.paging

import android.net.Uri
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.features.datescrubber.DateScrubberFeature
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/**
 * This [FakeInMemoryMediaPagingSource] class is responsible to providing paginated media data from
 * Picker Database by serving requests from Paging library.
 *
 * It generates and returns its own fake data.
 */
class FakeInMemoryMediaPagingSource
private constructor(
    val DATA_SIZE: Int = DEFAULT_SIZE,
    private val DATA_LIST: List<Media>? = null,
    private val DELAY_IN_MS: Long = 0L,
    // If this is true, the load method will return empty data, causing the grid to display only
    // placeholders.
    private val IS_PLACEHOLDER_GRID: Boolean = false,
    private val featureManager: FeatureManager? = null,
    private val nextPageSize: Int,
) : PagingSource<MediaPageKey, Media>() {

    companion object {
        const val DEFAULT_SIZE = 1_000
    }

    constructor(
        dataSize: Int = DEFAULT_SIZE,
        delay: Long = 0L,
        testFeatureManager: FeatureManager? = null,
        nextPageSize: Int,
    ) : this(dataSize, null, delay, false, testFeatureManager, nextPageSize)

    constructor(
        dataList: List<Media>,
        delay: Long = 0L,
        testFeatureManager: FeatureManager? = null,
        nextPageSize: Int,
    ) : this(DEFAULT_SIZE, dataList, delay, false, testFeatureManager, nextPageSize)

    constructor(
        isPlaceholderGrid: Boolean,
        dataSize: Int = DEFAULT_SIZE,
        nextPageSize: Int,
    ) : this(
        DATA_SIZE = dataSize,
        IS_PLACEHOLDER_GRID = isPlaceholderGrid,
        nextPageSize = nextPageSize,
    )

    private val currentDateTime = LocalDateTime.now()

    // If a [DATA_LIST] was provided, use it, otherwise generate a list of the requested size.
    val DATA =
        DATA_LIST
            ?: buildList<Media>() {
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
                                currentDateTime
                                    .minus(i.toLong(), ChronoUnit.MINUTES)
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

    /**
     * The [featureManager] parameter is only provided from mediaPagingSource inside
     * [TestDataServiceImpl] to support jumping in Photo Grid. For other grids, config is null,
     * which means jumping should not be enabled.
     */
    val isJumpingEnabled =
        featureManager?.isFeatureEnabled(DateScrubberFeature::class.java) ?: false

    override suspend fun load(params: LoadParams<MediaPageKey>): LoadResult<MediaPageKey, Media> {
        delay(DELAY_IN_MS)

        // Return empty data, causing the grid to display only placeholders.
        if (IS_PLACEHOLDER_GRID) {
            return LoadResult.Page(
                data = emptyList(),
                nextKey = null,
                prevKey = null,
                itemsBefore = 0,
                itemsAfter = DATA_SIZE,
            )
        }

        // Handle a data size of 0 for the first page, and return an empty page with no further
        // keys.
        if (DATA_SIZE == 0 && params.key == null) {
            return LoadResult.Page(data = emptyList(), nextKey = null, prevKey = null)
        }

        // This is inefficient, but a reliable way to locate the record being requested by the
        // [MediaPageKey] without having to keep track of offsets.
        val startIndex =
            if (params.key == null) 0
            else DATA.indexOfFirst({ item -> item.pickerId == params.key?.pickerId ?: 1 })

        // The list is zero-based, and loadSize isn't; so, offset by 1
        val endIndex = Math.min((startIndex + params.loadSize) - 1, DATA.lastIndex)

        // Item at start position doesn't exist, so this isn't a valid page.
        if (DATA.getOrNull(startIndex) == null) {
            return LoadResult.Invalid()
        }

        val pageData = DATA.slice(startIndex..endIndex)

        // Find the start of the next page and generate a Page key.
        val nextRow = DATA.getOrNull(endIndex + 1)
        val nextKey =
            if (nextRow == null) null
            else
                MediaPageKey(
                    pickerId = nextRow.pickerId,
                    dateTakenMillis = nextRow.dateTakenMillisLong,
                )

        // Find the start of the previous page and generate a Page key.
        val prevPageRow = DATA.getOrNull((startIndex) - nextPageSize)
        val prevKey =
            if (prevPageRow == null) null
            else
                MediaPageKey(
                    pickerId = prevPageRow.pickerId,
                    dateTakenMillis = prevPageRow.dateTakenMillisLong,
                )

        val itemsBeforeCount = startIndex
        val itemsAfterCount = DATA.size - endIndex - 1

        return LoadResult.Page(
            data = pageData,
            nextKey = nextKey,
            prevKey = prevKey,
            itemsBefore = itemsBeforeCount,
            itemsAfter = itemsAfterCount,
        )
    }

    override fun getRefreshKey(state: PagingState<MediaPageKey, Media>): MediaPageKey? {
        if (isJumpingEnabled) {
            val currentAnchorPosition = state.anchorPosition ?: 0
            // Calculates the nearest valid page start position based on current
            // state.anchorPosition
            // For example, if pageSize is 50, Valid start positions follow the pattern: 0, 50,
            // 100,etc.
            val validRefreshPosition = currentAnchorPosition - currentAnchorPosition % nextPageSize
            val media = DATA[validRefreshPosition]
            return MediaPageKey(media.pickerId, media.dateTakenMillisLong)
        }
        return null
    }

    override val jumpingSupported = isJumpingEnabled
}
