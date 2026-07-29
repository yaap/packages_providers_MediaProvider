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

package com.android.photopicker.data

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import android.widget.photopicker.PhotoPickerSelectionParams
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.SdCard
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.paging.PagingSource.LoadResult
import com.android.modules.utils.build.SdkLevel
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.data.MediaProviderClient.Companion.SEARCH_REQUEST_INIT_CALL_METHOD
import com.android.photopicker.data.model.CategoryType
import com.android.photopicker.data.model.CollectionInfo
import com.android.photopicker.data.model.GlideIcon
import com.android.photopicker.data.model.Group
import com.android.photopicker.data.model.GroupPageKey
import com.android.photopicker.data.model.Icon
import com.android.photopicker.data.model.ItemsPerMonth
import com.android.photopicker.data.model.KeyToCategoryType
import com.android.photopicker.data.model.Media
import com.android.photopicker.data.model.MediaPageKey
import com.android.photopicker.data.model.MediaSource
import com.android.photopicker.data.model.Provider
import com.android.photopicker.features.search.model.KeyToSearchSuggestionType
import com.android.photopicker.features.search.model.SearchRequest
import com.android.photopicker.features.search.model.SearchSuggestion
import com.android.photopicker.features.search.model.SearchSuggestionType

/**
 * A client class that is responsible for holding logic required to interact with [MediaProvider].
 *
 * It typically fetches data from [MediaProvider] using content queries and call methods.
 */
open class MediaProviderClient {
    companion object {
        private const val TAG = "MediaProviderClient"
        private const val MEDIA_SETS_INIT_CALL_METHOD: String = "picker_media_sets_init_call"
        private const val MEDIA_SET_CONTENTS_INIT_CALL_METHOD: String =
            "picker_media_in_media_set_init"
        private const val EXTRA_MIME_TYPES = "mime_types"
        private const val EXTRA_INTENT_ACTION = "intent_action"
        private const val EXTRA_PROVIDERS = "providers"
        private const val EXTRA_LOCAL_ONLY = "is_local_only"
        private const val EXTRA_ALBUM_ID = "album_id"
        private const val EXTRA_ALBUM_AUTHORITY = "album_authority"
        private const val COLUMN_GRANTS_COUNT = "grants_count"
        private const val PRE_SELECTION_URIS = "pre_selection_uris"
        const val MEDIA_INIT_CALL_METHOD: String = "picker_media_init"
        const val SEARCH_REQUEST_INIT_CALL_METHOD = "picker_internal_search_media_init"
        const val GET_SEARCH_PROVIDERS_CALL_METHOD = "picker_internal_get_search_providers"
        const val SEARCH_PROVIDER_AUTHORITIES = "search_provider_authorities"
        const val SEARCH_REQUEST_ID = "search_request_id"
    }

    /** Contains all optional and mandatory keys required to make a Media query */
    private enum class MediaQuery(val key: String) {
        PICKER_ID("picker_id"),
        DATE_TAKEN("date_taken_millis"),
        CURRENT_PAGE_SIZE("current_page_size"),
        NEXT_PAGE_SIZE("next_page_size"),
        ENABLE_ITEMS_BEFORE_COUNT("enable_items_before_count"),
        ENABLE_ITEMS_AFTER_COUNT("enable_items_after_count"),
    }

    /** Contains all optional and mandatory keys required to make a Media page key query */
    private enum class MediaPageKeyQuery(val key: String) {
        ITEM_POSITION("item_position")
    }

    /** Contains all optional and mandatory keys required to make a Media page key List query */
    private enum class MediaPageKeyListQuery(val key: String) {
        ITEM_INDEX_INTERVAL("item_index_interval")
    }

    /**
     * Contains all mandatory keys required to make an Album Media query that are not present in
     * [MediaQuery] already.
     */
    private enum class AlbumMediaQuery(val key: String) {
        ALBUM_AUTHORITY("album_authority")
    }

    /**
     * Contains all mandatory keys required to make a Category and Album query that are not present
     * in [MediaQuery] already.
     */
    private enum class CategoryAndAlbumQuery(val key: String) {
        PARENT_CATEGORY_ID("parent_category_id")
    }

    /**
     * Contains all mandatory keys required to make a Media Set query that are not present in
     * [MediaQuery] already.
     */
    private enum class MediaSetsQuery(val key: String) {
        PARENT_CATEGORY_ID("parent_category_id"),
        PARENT_CATEGORY_AUTHORITY("parent_category_authority"),
    }

    /**
     * Contains all mandatory keys required to make a Media Set contents query that are not present
     * in [MediaQuery] already.
     */
    private enum class MediaSetContentsQuery(val key: String) {
        PARENT_MEDIA_SET_PICKER_ID("media_set_picker_id"),
        PARENT_MEDIA_SET_AUTHORITY("media_set_picker_authority"),
    }

    /**
     * Contains all optional and mandatory keys for data in the Available Providers query response.
     */
    enum class AvailableProviderResponse(val key: String) {
        AUTHORITY("authority"),
        MEDIA_SOURCE("media_source"),
        UID("uid"),
        DISPLAY_NAME("display_name"),
    }

    enum class CollectionInfoResponse(val key: String) {
        AUTHORITY("authority"),
        COLLECTION_ID("collection_id"),
        ACCOUNT_NAME("account_name"),
    }

    /** Contains all optional and mandatory keys for data in the Media query response. */
    enum class MediaResponse(val key: String) {
        MEDIA_ID("id"),
        PICKER_ID("picker_id"),
        AUTHORITY("authority"),
        MEDIA_SOURCE("media_source"),
        MEDIA_URI("wrapped_uri"),
        LOADABLE_URI("unwrapped_uri"),
        DATE_TAKEN("date_taken_millis"),
        SIZE("size_bytes"),
        MIME_TYPE("mime_type"),
        STANDARD_MIME_TYPE_EXT("standard_mime_type_extension"),
        DURATION("duration_millis"),
        WIDTH("width"),
        HEIGHT("height"),
        IS_PRE_GRANTED("is_pre_granted"),
    }

    /** Contains all optional and mandatory keys for data in the Media query response extras. */
    enum class MediaResponseExtras(val key: String) {
        PREV_PAGE_ID("prev_page_picker_id"),
        PREV_PAGE_DATE_TAKEN("prev_page_date_taken"),
        NEXT_PAGE_ID("next_page_picker_id"),
        NEXT_PAGE_DATE_TAKEN("next_page_date_taken"),
        ITEMS_BEFORE_COUNT("items_before_count"),
        ITEMS_AFTER_COUNT("items_after_count"),
    }

    /** Contains all optional and mandatory keys for data in the Media query response. */
    enum class AlbumResponse(val key: String) {
        ALBUM_ID("id"),
        PICKER_ID("picker_id"),
        AUTHORITY("authority"),
        DATE_TAKEN("date_taken_millis"),
        ALBUM_NAME("display_name"),
        UNWRAPPED_COVER_URI("unwrapped_cover_uri"),
        COVER_MEDIA_SOURCE("media_source"),
    }

    /** Contains all optional and mandatory keys for the Preview Media Query. */
    enum class PreviewMediaQuery(val key: String) {
        CURRENT_SELECTION("current_selection"),
        CURRENT_DE_SELECTION("current_de_selection"),
        IS_FIRST_PAGE("is_first_page"),
    }

    enum class SearchRequestInitRequest(val key: String) {
        SEARCH_TEXT("search_text"),
        MEDIA_SET_ID("media_set_id"),
        AUTHORITY("authority"),
        TYPE("search_suggestion_type"),
    }

    enum class SearchSuggestionsQuery(val key: String) {
        LIMIT("limit"),
        HISTORY_LIMIT("history_limit"),
        PREFIX("prefix"),
    }

    enum class SearchSuggestionsResponse(val key: String) {
        AUTHORITY("authority"),
        MEDIA_SET_ID("media_set_id"),
        SEARCH_TEXT("display_text"),
        COVER_MEDIA_URI("cover_media_uri"),
        SUGGESTION_TYPE("suggestion_type"),
    }

    /** Contains all keys for data in the Items per Month query response. */
    enum class ItemsPerMonthResponse(val key: String) {
        // The year is formatted as yyyy and is derived considering the device's local time.
        YEAR_TAKEN("year_taken"),

        // The month is formatted as MM and is derived considering the device's local time.
        MONTH_TAKEN("month_taken"),

        // ITEM_COUNT represents the column containing the count of items for a specific
        // year and month combination.
        ITEM_COUNT("item_count"),
    }

    enum class GroupResponse(val key: String) {
        MEDIA_GROUP("media_group"),

        /** Identifier received from CMP. This cannot be null. */
        GROUP_ID("group_id"),

        /** Identifier used in Picker Backend, if any. */
        PICKER_ID("picker_id"),
        DISPLAY_NAME("display_name"),
        AUTHORITY("authority"),
        UNWRAPPED_COVER_URI("unwrapped_cover_uri"),
        ADDITIONAL_UNWRAPPED_COVER_URI_1("additional_cover_uri_1"),
        ADDITIONAL_UNWRAPPED_COVER_URI_2("additional_cover_uri_2"),
        ADDITIONAL_UNWRAPPED_COVER_URI_3("additional_cover_uri_3"),
        CATEGORY_TYPE("category_type"),
        IS_LEAF_CATEGORY("is_leaf_category"),
        BADGE_ICON_URI("badge_icon_uri"),
    }

    enum class GroupType() {
        CATEGORY,
        MEDIA_SET,
        ALBUM,
    }

    /** Fetch available [Provider]-s from the Media Provider process. */
    fun fetchAvailableProviders(contentResolver: ContentResolver): List<Provider> {
        try {
            contentResolver
                .query(
                    AVAILABLE_PROVIDERS_URI,
                    /* projection */ null,
                    /* queryArgs */ null,
                    /* cancellationSignal */ null, // TODO(b/405340486)
                )
                .use { cursor ->
                    cursor?.let {
                        return getListOfProviders(it)
                    }
                    Log.w(TAG, "Cursor in fetchAvailableProviders was unexpectedly null")
                    return emptyList()
                }
        } catch (e: Exception) {
            // If we can't fetch the available providers, basic functionality of photopicker does
            // not work. In order to catch this earlier in testing, throw an error instead of
            // silencing it.
            throw RuntimeException("Could not fetch available providers", e)
        }
    }

    /** Ensure that available providers are up to date. */
    suspend fun ensureProviders(contentResolver: ContentResolver) {
        try {
            contentResolver.call(
                MEDIA_PROVIDER_AUTHORITY,
                "ensure_providers_call",
                /* arg */ null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ensure providers failed", e)
        }
    }

    /** Fetch a list of [Media] from MediaProvider for the given page key. */
    open suspend fun fetchMedia(
        pageKey: MediaPageKey,
        currentPageSize: Int,
        nextPageSize: Int,
        contentResolver: ContentResolver,
        availableProviders: List<Provider>,
        config: PhotopickerConfiguration,
        shouldEnableItemsBeforeCount: Boolean = false,
        shouldEnableItemsAfterCount: Boolean = false,
    ): LoadResult<MediaPageKey, Media> {
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                MediaQuery.PICKER_ID.key to pageKey.pickerId,
                MediaQuery.DATE_TAKEN.key to pageKey.dateTakenMillis,
                MediaQuery.CURRENT_PAGE_SIZE.key to currentPageSize,
                MediaQuery.NEXT_PAGE_SIZE.key to nextPageSize,
                MediaQuery.ENABLE_ITEMS_BEFORE_COUNT.key to shouldEnableItemsBeforeCount,
                MediaQuery.ENABLE_ITEMS_AFTER_COUNT.key to shouldEnableItemsAfterCount,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        availableProviders.forEach { provider -> add(provider.authority) }
                    },
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                Intent.EXTRA_UID to config.callingPackageUid,
            )

        try {
            return contentResolver
                .query(
                    MEDIA_URI,
                    /* projection */ null,
                    input,
                    /* cancellationSignal */ null, // TODO(b/405340486)
                )
                .use { cursor ->
                    cursor?.let {
                        LoadResult.Page(
                            data = cursor.getListOfMedia(config.selectionParams),
                            prevKey = cursor.getPrevMediaPageKey(),
                            nextKey = cursor.getNextMediaPageKey(),
                            itemsBefore =
                                cursor.getItemsBeforeCount() ?: LoadResult.Page.COUNT_UNDEFINED,
                            itemsAfter =
                                cursor.getItemsAfterCount() ?: LoadResult.Page.COUNT_UNDEFINED,
                        )
                    }
                        ?: throw IllegalStateException(
                            "Received a null response from Content Provider"
                        )
                }
        } catch (e: Exception) {
            throw RuntimeException("Could not fetch media", e)
        }
    }

    /** Fetch search results as a list of [Media] from MediaProvider for the given page key. */
    suspend fun fetchSearchResults(
        searchRequestId: Int,
        pageKey: MediaPageKey,
        currentPageSize: Int,
        nextPageSize: Int,
        contentResolver: ContentResolver,
        availableProviders: List<Provider>,
        config: PhotopickerConfiguration,
        cancellationSignal: CancellationSignal?,
        shouldEnableItemsBeforeAndAfterCounts: Boolean = false,
    ): LoadResult<MediaPageKey, Media> {
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                MediaQuery.PICKER_ID.key to pageKey.pickerId,
                MediaQuery.DATE_TAKEN.key to pageKey.dateTakenMillis,
                MediaQuery.CURRENT_PAGE_SIZE.key to currentPageSize,
                MediaQuery.NEXT_PAGE_SIZE.key to nextPageSize,
                MediaQuery.ENABLE_ITEMS_BEFORE_COUNT.key to shouldEnableItemsBeforeAndAfterCounts,
                MediaQuery.ENABLE_ITEMS_AFTER_COUNT.key to shouldEnableItemsBeforeAndAfterCounts,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        availableProviders.forEach { provider -> add(provider.authority) }
                    },
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                Intent.EXTRA_UID to config.callingPackageUid,
            )

        try {
            return contentResolver
                .query(
                    getSearchResultsMediaUri(searchRequestId),
                    /* projection */ null,
                    input,
                    cancellationSignal,
                )
                .use { cursor ->
                    cursor?.let {
                        LoadResult.Page(
                            data = cursor.getListOfMedia(config.selectionParams),
                            prevKey = cursor.getPrevMediaPageKey(),
                            nextKey = cursor.getNextMediaPageKey(),
                            itemsBefore =
                                cursor.getItemsBeforeCount() ?: LoadResult.Page.COUNT_UNDEFINED,
                            itemsAfter =
                                cursor.getItemsAfterCount() ?: LoadResult.Page.COUNT_UNDEFINED,
                        )
                    }
                        ?: throw IllegalStateException(
                            "Received a null response from Media Provider for search results"
                        )
                }
        } catch (e: Exception) {
            throw RuntimeException("Could not fetch search results media", e)
        }
    }

    /** Fetch a list of [Media] from MediaProvider for the given page key. */
    suspend fun fetchPreviewMedia(
        pageKey: MediaPageKey,
        currentPageSize: Int,
        nextPageSize: Int,
        contentResolver: ContentResolver,
        availableProviders: List<Provider>,
        config: PhotopickerConfiguration,
        currentSelection: List<String> = emptyList(),
        currentDeSelection: List<String> = emptyList(),
        isFirstPage: Boolean = false,
    ): LoadResult<MediaPageKey, Media> {
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                MediaQuery.PICKER_ID.key to pageKey.pickerId,
                MediaQuery.DATE_TAKEN.key to pageKey.dateTakenMillis,
                MediaQuery.CURRENT_PAGE_SIZE.key to currentPageSize,
                MediaQuery.NEXT_PAGE_SIZE.key to nextPageSize,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        availableProviders.forEach { provider -> add(provider.authority) }
                    },
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                Intent.EXTRA_UID to config.callingPackageUid,
                PreviewMediaQuery.CURRENT_SELECTION.key to currentSelection,
                PreviewMediaQuery.CURRENT_DE_SELECTION.key to currentDeSelection,
                PreviewMediaQuery.IS_FIRST_PAGE.key to isFirstPage,
            )

        try {
            return contentResolver
                .query(
                    MEDIA_PREVIEW_URI,
                    /* projection */ null,
                    input,
                    /* cancellationSignal */ null, // TODO(b/405340486)
                )
                .use { cursor ->
                    cursor?.let {
                        LoadResult.Page(
                            data = cursor.getListOfMedia(config.selectionParams),
                            prevKey = cursor.getPrevMediaPageKey(),
                            nextKey = cursor.getNextMediaPageKey(),
                        )
                    }
                        ?: throw IllegalStateException(
                            "Received a null response from Content Provider"
                        )
                }
        } catch (e: Exception) {
            throw RuntimeException("Could not fetch preview media", e)
        }
    }

    /** Fetch a list of [Group.Album] from MediaProvider for the given page key. */
    open suspend fun fetchAlbums(
        pageKey: MediaPageKey,
        pageSize: Int,
        contentResolver: ContentResolver,
        availableProviders: List<Provider>,
        config: PhotopickerConfiguration,
    ): LoadResult<MediaPageKey, Group.Album> {
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                MediaQuery.PICKER_ID.key to pageKey.pickerId,
                MediaQuery.DATE_TAKEN.key to pageKey.dateTakenMillis,
                MediaQuery.CURRENT_PAGE_SIZE.key to pageSize,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        availableProviders.forEach { provider -> add(provider.authority) }
                    },
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                Intent.EXTRA_UID to config.callingPackageUid,
            )
        try {
            return contentResolver
                .query(
                    ALBUM_URI,
                    /* projection */ null,
                    input,
                    /* cancellationSignal */ null, // TODO(b/405340486)
                )
                .use { cursor ->
                    cursor?.let {
                        LoadResult.Page(
                            data = cursor.getListOfAlbums(),
                            prevKey = cursor.getPrevMediaPageKey(),
                            nextKey = cursor.getNextMediaPageKey(),
                        )
                    }
                        ?: throw IllegalStateException(
                            "Received a null response from Content Provider"
                        )
                }
        } catch (e: Exception) {
            throw RuntimeException("Could not fetch albums", e)
        }
    }

    /** Fetch a list of [Media] from MediaProvider for the given page key. */
    open suspend fun fetchAlbumMedia(
        albumId: String,
        albumAuthority: String,
        pageKey: MediaPageKey,
        currentPageSize: Int,
        nextPageSize: Int,
        contentResolver: ContentResolver,
        availableProviders: List<Provider>,
        config: PhotopickerConfiguration,
        shouldEnableItemsBeforeAndAfterCounts: Boolean = false,
    ): LoadResult<MediaPageKey, Media> {
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                AlbumMediaQuery.ALBUM_AUTHORITY.key to albumAuthority,
                MediaQuery.PICKER_ID.key to pageKey.pickerId,
                MediaQuery.DATE_TAKEN.key to pageKey.dateTakenMillis,
                MediaQuery.CURRENT_PAGE_SIZE.key to currentPageSize,
                MediaQuery.NEXT_PAGE_SIZE.key to nextPageSize,
                MediaQuery.ENABLE_ITEMS_BEFORE_COUNT.key to shouldEnableItemsBeforeAndAfterCounts,
                MediaQuery.ENABLE_ITEMS_AFTER_COUNT.key to shouldEnableItemsBeforeAndAfterCounts,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        availableProviders.forEach { provider -> add(provider.authority) }
                    },
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                Intent.EXTRA_UID to config.callingPackageUid,
            )

        try {
            return contentResolver
                .query(
                    getAlbumMediaUri(albumId),
                    /* projection */ null,
                    input,
                    /* cancellationSignal */ null, // TODO(b/405340486)
                )
                .use { cursor ->
                    cursor?.let {
                        LoadResult.Page(
                            data = cursor.getListOfMedia(config.selectionParams),
                            prevKey = cursor.getPrevMediaPageKey(),
                            nextKey = cursor.getNextMediaPageKey(),
                            itemsBefore =
                                cursor.getItemsBeforeCount() ?: LoadResult.Page.COUNT_UNDEFINED,
                            itemsAfter =
                                cursor.getItemsAfterCount() ?: LoadResult.Page.COUNT_UNDEFINED,
                        )
                    }
                        ?: throw IllegalStateException(
                            "Received a null response from Content Provider"
                        )
                }
        } catch (e: Exception) {
            throw RuntimeException("Could not fetch album media", e)
        }
    }

    /**
     * Tries to fetch the latest collection info for the available providers.
     *
     * @param resolver The [ContentResolver] of the current active user
     * @return list of [CollectionInfo]
     * @throws RuntimeException if data source is unable to fetch the collection info.
     */
    fun fetchCollectionInfo(resolver: ContentResolver): List<CollectionInfo> {
        try {
            resolver
                .query(
                    COLLECTION_INFO_URI,
                    /* projection */ null,
                    /* queryArgs */ null,
                    /* cancellationSignal */ null,
                )
                .use { cursor ->
                    cursor?.let {
                        return getListOfCollectionInfo(it)
                    }
                    Log.w(TAG, "Cursor in fetchCollectionInfo was unexpectedly null")
                    return emptyList()
                }
        } catch (e: Exception) {
            throw RuntimeException("Could not fetch collection info", e)
        }
    }

    /**
     * Fetches the count of pre-granted media for a given package from the MediaProvider.
     *
     * This function is designed to be used within the MediaProvider client-side context. It queries
     * the `MEDIA_GRANTS_URI` using a Bundle containing the calling package's UID to retrieve the
     * count of media grants.
     *
     * @param contentResolver The ContentResolver used to interact with the MediaProvider.
     * @param callingPackageUid The UID of the calling package (app) for which to fetch the count.
     * @return The count of media grants for the calling package.
     * @throws RuntimeException if an error occurs during the query or fetching of the grants count.
     */
    fun fetchMediaGrantsCount(contentResolver: ContentResolver, callingPackageUid: Int): Int {
        if (callingPackageUid < 0) {
            // return with 0 value since the input callingUid is invalid.
            Log.e(TAG, "invalid calling package UID.")
            throw IllegalArgumentException("Invalid input for uid.")
        }
        // Create a Bundle containing the calling package's UID. This is used as a selection
        // argument for the query.
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(Intent.EXTRA_UID to callingPackageUid)

        try {
            contentResolver.query(MEDIA_GRANTS_COUNT_URI, /* projection */ null, input, null).use {
                cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    // Move the cursor to the first row and extract the count.

                    return cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_GRANTS_COUNT))
                } else {
                    // return 0 if cursor is empty.
                    return 0
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Could not fetch media grants count. ", e)
        }
    }

    /** Fetches a list of [Media] from MediaProvider filtered by the input URI list. */
    fun fetchFilteredMedia(
        pageKey: MediaPageKey,
        pageSize: Int,
        contentResolver: ContentResolver,
        availableProviders: List<Provider>,
        config: PhotopickerConfiguration,
        uris: List<Uri>,
    ): List<Media> {
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                MediaQuery.PICKER_ID.key to pageKey.pickerId,
                MediaQuery.DATE_TAKEN.key to pageKey.dateTakenMillis,
                MediaQuery.CURRENT_PAGE_SIZE.key to pageSize,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        availableProviders.forEach { provider -> add(provider.authority) }
                    },
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                Intent.EXTRA_UID to config.callingPackageUid,
                PRE_SELECTION_URIS to
                    ArrayList<String>().apply { uris.forEach { uri -> add(uri.toString()) } },
            )

        try {
            return contentResolver
                .query(
                    MEDIA_PRE_SELECTION_URI,
                    /* projection */ null,
                    input,
                    /* cancellationSignal */ null, // TODO(b/405340486)
                )
                ?.getListOfMedia(config.selectionParams) ?: ArrayList()
        } catch (e: Exception) {
            throw RuntimeException("Could not fetch media", e)
        }
    }

    /** Deletes a search suggestion from the search history suggestions. */
    suspend fun deleteHistorySuggestion(resolver: ContentResolver, suggestion: SearchSuggestion) {
        try {
            val input =
                Bundle().apply {
                    putString(SearchSuggestionsResponse.AUTHORITY.key, suggestion.authority)
                    putString(SearchSuggestionsResponse.MEDIA_SET_ID.key, suggestion.mediaSetId)
                    putString(SearchSuggestionsResponse.SEARCH_TEXT.key, suggestion.displayText)
                }

            resolver.delete(SEARCH_SUGGESTIONS_URI, input)
        } catch (e: Exception) {
            throw RuntimeException("Could not delete search history", e)
        }
    }

    /**
     * Fetches a list of search suggestions from MediaProvider filtered by the input prefix string.
     */
    suspend fun fetchSearchSuggestions(
        resolver: ContentResolver,
        prefix: String,
        limit: Int,
        historyLimit: Int,
        availableProviders: List<Provider>,
        cancellationSignal: CancellationSignal?,
    ): List<SearchSuggestion> {
        try {
            val input: Bundle =
                @Suppress("DEPRECATION") // bundleOf is deprecated
                bundleOf(
                    SearchSuggestionsQuery.PREFIX.key to prefix,
                    SearchSuggestionsQuery.LIMIT.key to limit,
                    SearchSuggestionsQuery.HISTORY_LIMIT.key to historyLimit,
                    EXTRA_PROVIDERS to
                        ArrayList<String>().apply {
                            availableProviders.forEach { provider -> add(provider.authority) }
                        },
                )

            return resolver
                .query(SEARCH_SUGGESTIONS_URI, /* projection */ null, input, cancellationSignal)
                ?.getListOfSearchSuggestions(availableProviders) ?: ArrayList()
        } catch (e: Exception) {
            throw RuntimeException("Could not fetch search suggestions", e)
        }
    }

    /**
     * Fetches a list of categories and albums from MediaProvider filtered by the input list of
     * available providers, mime types and parent category id.
     */
    suspend fun fetchCategoriesAndAlbums(
        pageKey: GroupPageKey,
        pageSize: Int,
        contentResolver: ContentResolver,
        availableProviders: List<Provider>,
        parentCategoryId: String?,
        config: PhotopickerConfiguration,
        cancellationSignal: CancellationSignal?,
        providerToIconMap: Map<Provider, Icon>,
    ): LoadResult<GroupPageKey, Group> {
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                MediaQuery.PICKER_ID.key to pageKey.pickerId,
                MediaQuery.CURRENT_PAGE_SIZE.key to pageSize,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        availableProviders.forEach { provider -> add(provider.authority) }
                    },
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                Intent.EXTRA_UID to config.callingPackageUid,
                CategoryAndAlbumQuery.PARENT_CATEGORY_ID.key to parentCategoryId,
            )
        try {
            return contentResolver
                .query(
                    getCategoryUri(parentCategoryId),
                    /* projection */ null,
                    input,
                    cancellationSignal,
                )
                .use { cursor ->
                    cursor?.let {
                        LoadResult.Page(
                            data =
                                cursor.getListOfCategoriesAndAlbums(
                                    availableProviders,
                                    providerToIconMap,
                                ),
                            prevKey = cursor.getPrevGroupPageKey(),
                            nextKey = cursor.getNextGroupPageKey(),
                        )
                    }
                        ?: throw IllegalStateException(
                            "Received a null response from Content Provider"
                        )
                }
        } catch (e: Exception) {
            throw RuntimeException(
                "Could not fetch categories and albums for parent category $parentCategoryId",
                e,
            )
        }
    }

    /**
     * Fetches a list of media sets from MediaProvider filtered by the input list of available
     * providers, mime types and parent category id.
     */
    suspend fun fetchMediaSets(
        pageKey: GroupPageKey,
        pageSize: Int,
        contentResolver: ContentResolver,
        availableProviders: List<Provider>,
        parentCategory: Group.Category,
        config: PhotopickerConfiguration,
        cancellationSignal: CancellationSignal?,
        providerToIconMap: Map<Provider, Icon>,
    ): LoadResult<GroupPageKey, Group.MediaSet> {
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                MediaQuery.PICKER_ID.key to pageKey.pickerId,
                MediaQuery.CURRENT_PAGE_SIZE.key to pageSize,
                EXTRA_PROVIDERS to arrayListOf(parentCategory.authority),
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                MediaSetsQuery.PARENT_CATEGORY_ID.key to parentCategory.id,
                MediaSetsQuery.PARENT_CATEGORY_AUTHORITY.key to parentCategory.authority,
            )
        try {
            return contentResolver
                .query(MEDIA_SETS_URI, /* projection */ null, input, cancellationSignal)
                .use { cursor ->
                    cursor?.let {
                        LoadResult.Page(
                            data =
                                cursor.getListOfMediaSets(
                                    availableProviders,
                                    providerToIconMap,
                                    parentCategory.categoryType.key,
                                ),
                            prevKey = cursor.getPrevGroupPageKey(),
                            nextKey = cursor.getNextGroupPageKey(),
                        )
                    }
                        ?: throw IllegalStateException(
                            "Received a null response from Content Provider"
                        )
                }
        } catch (e: Exception) {
            throw RuntimeException(
                "Could not fetch media sets for parent category ${parentCategory.id}",
                e,
            )
        }
    }

    /**
     * Fetches a list of media items in a media set from MediaProvider filtered by the input list of
     * available providers, mime types and parent media set id.
     */
    suspend fun fetchMediaSetContents(
        pageKey: MediaPageKey,
        currentPageSize: Int,
        nextPageSize: Int,
        contentResolver: ContentResolver,
        parentMediaSet: Group.MediaSet,
        config: PhotopickerConfiguration,
        cancellationSignal: CancellationSignal?,
        shouldEnableItemsBeforeAndAfterCounts: Boolean = false,
    ): LoadResult<MediaPageKey, Media> {
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                MediaQuery.PICKER_ID.key to pageKey.pickerId,
                MediaQuery.DATE_TAKEN.key to pageKey.dateTakenMillis,
                MediaQuery.CURRENT_PAGE_SIZE.key to currentPageSize,
                MediaQuery.NEXT_PAGE_SIZE.key to nextPageSize,
                MediaQuery.ENABLE_ITEMS_BEFORE_COUNT.key to shouldEnableItemsBeforeAndAfterCounts,
                MediaQuery.ENABLE_ITEMS_AFTER_COUNT.key to shouldEnableItemsBeforeAndAfterCounts,
                EXTRA_PROVIDERS to arrayListOf(parentMediaSet.authority),
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                Intent.EXTRA_UID to config.callingPackageUid,
                MediaSetContentsQuery.PARENT_MEDIA_SET_PICKER_ID.key to parentMediaSet.pickerId,
                MediaSetContentsQuery.PARENT_MEDIA_SET_AUTHORITY.key to parentMediaSet.authority,
            )
        try {
            return contentResolver
                .query(MEDIA_SET_CONTENTS_URI, /* projection */ null, input, cancellationSignal)
                .use { cursor ->
                    cursor?.let {
                        LoadResult.Page(
                            data = cursor.getListOfMedia(config.selectionParams),
                            prevKey = cursor.getPrevMediaPageKey(),
                            nextKey = cursor.getNextMediaPageKey(),
                            itemsBefore =
                                cursor.getItemsBeforeCount() ?: LoadResult.Page.COUNT_UNDEFINED,
                            itemsAfter =
                                cursor.getItemsAfterCount() ?: LoadResult.Page.COUNT_UNDEFINED,
                        )
                    }
                        ?: throw IllegalStateException(
                            "Received a null response from Content Provider"
                        )
                }
        } catch (e: Exception) {
            throw RuntimeException(
                "Could not fetch media set contents for parent media set ${parentMediaSet.id}",
                e,
            )
        }
    }

    /**
     * Send a refresh media request to MediaProvider. This is a signal for MediaProvider to refresh
     * its cache, if required.
     */
    fun refreshMedia(
        @Suppress("UNUSED_PARAMETER") providers: List<Provider>,
        resolver: ContentResolver,
        config: PhotopickerConfiguration,
    ) {
        val extras = Bundle()

        // TODO(b/340246010): Currently, we trigger sync for all providers. This is because
        //  the UI is responsible for triggering syncs which is sometimes required to enable
        //  providers. This should be changed to triggering syncs for specific providers once the
        //  backend takes responsibility for the sync triggers.
        val initLocalOnlyMedia = false

        extras.putBoolean(EXTRA_LOCAL_ONLY, initLocalOnlyMedia)
        extras.putStringArrayList(EXTRA_MIME_TYPES, config.mimeTypes)
        extras.putString(EXTRA_INTENT_ACTION, config.action)
        extras.putInt(Intent.EXTRA_UID, config.callingPackageUid ?: -1)
        refreshMedia(extras, resolver)
    }

    /**
     * Send a refresh album media request to MediaProvider. This is a signal for MediaProvider to
     * refresh its cache for the given album media, if required.
     */
    suspend fun refreshAlbumMedia(
        albumId: String,
        albumAuthority: String,
        providers: List<Provider>,
        resolver: ContentResolver,
        config: PhotopickerConfiguration,
    ) {
        val extras = Bundle()
        val initLocalOnlyMedia: Boolean =
            providers.all { provider -> (provider.mediaSource == MediaSource.LOCAL) }
        extras.putBoolean(EXTRA_LOCAL_ONLY, initLocalOnlyMedia)
        extras.putStringArrayList(EXTRA_MIME_TYPES, config.mimeTypes)
        extras.putString(EXTRA_INTENT_ACTION, config.action)
        extras.putString(EXTRA_ALBUM_ID, albumId)
        extras.putString(EXTRA_ALBUM_AUTHORITY, albumAuthority)
        refreshMedia(extras, resolver)
    }

    /**
     * Send a refresh media sets request to MediaProvider. This is a signal for MediaProvider to
     * refresh its cache for the given parent category id and authority, if required.
     */
    suspend fun refreshMediaSets(
        contentResolver: ContentResolver,
        category: Group.Category,
        config: PhotopickerConfiguration,
        providers: List<Provider>,
    ) {
        val extras =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                EXTRA_MIME_TYPES to config.mimeTypes,
                MediaSetsQuery.PARENT_CATEGORY_ID.key to category.id,
                MediaSetsQuery.PARENT_CATEGORY_AUTHORITY.key to category.authority,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        providers.forEach { provider -> add(provider.authority) }
                    },
            )

        try {
            contentResolver.call(
                MEDIA_PROVIDER_AUTHORITY,
                MEDIA_SETS_INIT_CALL_METHOD,
                /* arg */ null,
                extras,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not send refresh media sets call to Media Provider $extras", e)
        }
    }

    /**
     * Send a refresh media set contents request to MediaProvider. This is a signal for
     * MediaProvider to refresh its cache for the given parent media set id and authority, if
     * required.
     */
    suspend fun refreshMediaSetContents(
        contentResolver: ContentResolver,
        mediaSet: Group.MediaSet,
        config: PhotopickerConfiguration,
        providers: List<Provider>,
    ) {
        val extras =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                EXTRA_MIME_TYPES to config.mimeTypes,
                MediaSetContentsQuery.PARENT_MEDIA_SET_PICKER_ID.key to mediaSet.pickerId,
                MediaSetContentsQuery.PARENT_MEDIA_SET_AUTHORITY.key to mediaSet.authority,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        providers.forEach { provider -> add(provider.authority) }
                    },
            )

        try {
            contentResolver.call(
                MEDIA_PROVIDER_AUTHORITY,
                MEDIA_SET_CONTENTS_INIT_CALL_METHOD,
                /* arg */ null,
                extras,
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Could not send refresh media set contents call to Media Provider $extras",
                e,
            )
        }
    }

    /**
     * Creates a search request with the data source.
     *
     * The data source is expected to return a search request id associated with the request.
     * [MediaProviderClient] can use this search request id to query search results throughout the
     * photopicker session.
     *
     * This call lets [MediaProvider] know that the Photopicker session has made a new search
     * request and the backend should prepare to handle search results queries for the given search
     * request.
     */
    suspend fun createSearchRequest(
        searchRequest: SearchRequest,
        providers: List<Provider>,
        resolver: ContentResolver,
        config: PhotopickerConfiguration,
    ): Int {
        val extras: Bundle =
            prepareSearchResultsExtras(
                searchRequest = searchRequest,
                providers = providers,
                config = config,
            )

        val result: Bundle? =
            resolver.call(
                MEDIA_PROVIDER_AUTHORITY,
                SEARCH_REQUEST_INIT_CALL_METHOD,
                /* arg */ null,
                extras,
            )
        return checkNotNull(result?.getInt(SEARCH_REQUEST_ID)) {
            "Search request ID cannot be null"
        }
    }

    /**
     * Notifies the Data Source that the previously known search query is performed again by the
     * user in the same session.
     *
     * This call lets [MediaProvider] know that the user has triggered a known search request again
     * and the backend should prepare to handle search results queries for the given search request.
     */
    suspend fun ensureSearchResults(
        searchRequest: SearchRequest,
        searchRequestId: Int,
        providers: List<Provider>,
        resolver: ContentResolver,
        config: PhotopickerConfiguration,
    ) {
        val extras: Bundle =
            prepareSearchResultsExtras(
                searchRequest = searchRequest,
                searchRequestId = searchRequestId,
                providers = providers,
                config = config,
            )

        resolver.call(
            MEDIA_PROVIDER_AUTHORITY,
            SEARCH_REQUEST_INIT_CALL_METHOD,
            /* arg */ null,
            extras,
        )
    }

    /**
     * Creates an extras [Bundle] with the required args for MediaProvider's
     * [SEARCH_REQUEST_INIT_CALL_METHOD].
     *
     * See [createSearchRequest] and [ensureSearchResults].
     */
    private fun prepareSearchResultsExtras(
        searchRequest: SearchRequest,
        searchRequestId: Int? = null,
        providers: List<Provider>,
        config: PhotopickerConfiguration,
    ): Bundle {
        val extras =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        providers.forEach { provider -> add(provider.authority) }
                    },
            )

        if (searchRequestId != null) {
            extras.putInt(SEARCH_REQUEST_ID, searchRequestId)
        }

        when (searchRequest) {
            is SearchRequest.SearchTextRequest ->
                extras.putString(SearchRequestInitRequest.SEARCH_TEXT.key, searchRequest.searchText)

            is SearchRequest.SearchSuggestionRequest -> {
                extras.putString(
                    SearchRequestInitRequest.SEARCH_TEXT.key,
                    searchRequest.suggestion.displayText,
                )
                extras.putString(
                    SearchRequestInitRequest.AUTHORITY.key,
                    searchRequest.suggestion.authority,
                )
                extras.putString(
                    SearchRequestInitRequest.MEDIA_SET_ID.key,
                    searchRequest.suggestion.mediaSetId,
                )
                extras.putString(
                    SearchRequestInitRequest.TYPE.key,
                    searchRequest.suggestion.type.name,
                )
            }
        }

        return extras
    }

    /**
     * Get available search providers from the Media Provider client using the available
     * [ContentResolver].
     *
     * If the available providers are known at the time of the query, this method will filter the
     * results of the call so that search providers are a subset of the available providers.
     *
     * @param resolver The [ContentResolver] that resolves to the desired instance of MediaProvider.
     *   (This may resolve in a cross profile instance of MediaProvider).
     * @param availableProviders
     */
    suspend fun fetchSearchProviderAuthorities(
        resolver: ContentResolver,
        availableProviders: List<Provider>? = null,
    ): List<String>? {
        try {
            val availableProviderAuthorities: Set<String>? =
                availableProviders?.map { it.authority }?.toSet()
            val result: Bundle? =
                resolver.call(
                    MEDIA_PROVIDER_AUTHORITY,
                    GET_SEARCH_PROVIDERS_CALL_METHOD,
                    /* arg */ null,
                    /* extras */ null,
                )
            return result?.getStringArrayList(SEARCH_PROVIDER_AUTHORITIES)?.filter {
                availableProviderAuthorities?.contains(it) ?: true
            }
        } catch (e: Exception) {
            // If we can't fetch the available providers, basic functionality of photopicker does
            // not work. In order to catch this earlier in testing, throw an error instead of
            // silencing it.
            Log.e(TAG, "Could not fetch providers with search enabled", e)
            return null
        }
    }

    /**
     * Fetches the number of media items available in MediaProvider for each month
     *
     * @param contentResolver The ContentResolver used to interact with the MediaProvider.
     * @param availableProviders Available providers to get the media items
     * @param config Given photopicker configurations
     * @return A list of [ItemsPerMonth] objects, where each object represents a year, month, and
     *   the corresponding item count (all in local time)
     * @throws RuntimeException if an error occurs during the query or fetching the items counts
     */
    open suspend fun fetchItemsPerMonth(
        contentResolver: ContentResolver,
        availableProviders: List<Provider>,
        config: PhotopickerConfiguration,
    ): List<ItemsPerMonth> {
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        availableProviders.forEach { provider -> add(provider.authority) }
                    },
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                Intent.EXTRA_UID to config.callingPackageUid,
            )
        return contentResolver
            .query(
                ITEMS_PER_MONTH_URI,
                /* projection= */ null,
                input,
                /* cancellationSignal= */ null, // TODO(b/405340486)
            )
            .use { cursor ->
                cursor?.getListOfItemCountPerMonth()
                    ?: throw IllegalStateException(
                        "Received a null response for Items Per Month from Content Provider."
                    )
            }
    }

    /**
     * Fetches the [MediaPageKey] for the item at the specified position in MediaProvider. The
     * cursor returned by the query only contains one row having picker Id and date taken of the
     * target item. But we intend to make this request cancellable in case the user navigates away
     * from the PhotoPicker page before the request completes. To support cancellation requests in
     * the future, we are using `contentResolver.query` instead of `contentResolver.call`.
     *
     * @param contentResolver The ContentResolver used to interact with the MediaProvider.
     * @param itemPosition The 0-based index of the desired media item.
     * @param availableProviders Available providers to get the media items
     * @param config Given photopicker configurations
     * @return The [MediaPageKey] for the item at the specified position.
     * @throws IllegalArgumentException If invalid or negative [itemPosition] is given in the input
     * @throws IllegalStateException If the Content Provider returns a null Cursor or if the Cursor
     *   does not contain a valid [MediaPageKey].
     */
    open fun fetchMediaPageKeyForItemPosition(
        contentResolver: ContentResolver,
        itemPosition: Int,
        availableProviders: List<Provider>,
        config: PhotopickerConfiguration,
    ): MediaPageKey {
        if (itemPosition < 0) {
            throw IllegalArgumentException("Received invalid itemPosition $itemPosition ")
        }
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                MediaPageKeyQuery.ITEM_POSITION.key to itemPosition,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        availableProviders.forEach { provider -> add(provider.authority) }
                    },
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                Intent.EXTRA_UID to config.callingPackageUid,
            )
        return contentResolver
            .query(
                MEDIA_PAGE_KEY_URI,
                /* projection= */ null,
                input,
                /* cancellationSignal= */ null, // TODO(b/405340486)
            )
            .use { cursor ->
                cursor?.getMediaPageKey()
                    ?: throw IllegalStateException(
                        "Received a null response for MediaPageKey at itemPosition $itemPosition " +
                            "from Content Provider"
                    )
            }
    }

    /**
     * Fetches the list of [MediaPageKey] for all the items coming at the given
     * [mediaPageKeyCacheInterval] interval in MediaProvider.
     *
     * @param contentResolver The ContentResolver used to interact with the MediaProvider.
     * @param mediaPageKeyCacheInterval The interval between the item indexes to fetch
     *   [MediaPageKey]s.
     * @param availableProviders Available providers to get the media items
     * @param config Given photopicker configurations
     * @return The List of [MediaPageKey] for all the items coming at the given
     *   [mediaPageKeyCacheInterval] interval. For example if [itemIndexInterval] = 100, then the
     *   returned list will contain all the [MediaPageKey] of items available at 0th, 100th 200th ..
     *   etc positions
     * @throws IllegalArgumentException If invalid [mediaPageKeyCacheInterval] is given in the input
     * @throws IllegalStateException If the Content Provider returns a null Cursor or if the Cursor
     *   does not contain a valid list of MediaPageKeys.
     */
    open suspend fun fetchMediaPageKeyList(
        contentResolver: ContentResolver,
        mediaPageKeyCacheInterval: Int,
        availableProviders: List<Provider>,
        config: PhotopickerConfiguration,
    ): List<MediaPageKey> {
        if (mediaPageKeyCacheInterval < 1) {
            throw IllegalArgumentException(
                "Received invalid itemIndexInterval $mediaPageKeyCacheInterval "
            )
        }
        val input: Bundle =
            @Suppress("DEPRECATION") // bundleOf is deprecated
            bundleOf(
                MediaPageKeyListQuery.ITEM_INDEX_INTERVAL.key to mediaPageKeyCacheInterval,
                EXTRA_PROVIDERS to
                    ArrayList<String>().apply {
                        availableProviders.forEach { provider -> add(provider.authority) }
                    },
                EXTRA_MIME_TYPES to config.mimeTypes,
                EXTRA_INTENT_ACTION to config.action,
                Intent.EXTRA_UID to config.callingPackageUid,
            )
        return contentResolver
            .query(
                MEDIA_PAGE_KEY_LIST_URI,
                /* projection= */ null,
                input,
                /* cancellationSignal= */ null, // TODO(b/405340486)
            )
            .use { cursor ->
                cursor?.getMediaPageKeyList()
                    ?: throw IllegalStateException(
                        "Received a null response for MediaPageKeyList from Content Provider"
                    )
            }
    }

    /**
     * Parses this Cursor to create a list of [MediaPageKey]s, sampling one key at a specified
     * regular interval.
     *
     * @param mediaPageKeyCacheInterval The interval at which to select rows (e.g., 100 selects rows
     *   0, 100, 200, etc.).
     * @return A [List] of the sampled [MediaPageKey]s.
     */
    private fun Cursor.getMediaPageKeyList(): List<MediaPageKey> {
        val result: MutableList<MediaPageKey> = mutableListOf()
        if (this.moveToFirst()) {
            do {
                val pickerId = getLong(getColumnIndexOrThrow(MediaResponse.PICKER_ID.key))
                val dateTaken = getLong(getColumnIndexOrThrow(MediaResponse.DATE_TAKEN.key))
                result.add(MediaPageKey(pickerId = pickerId, dateTakenMillis = dateTaken))
            } while (moveToNext())
        }
        return result
    }

    /** Creates a list of [Provider] from the given [Cursor]. */
    private fun getListOfProviders(cursor: Cursor): List<Provider> {
        val result: MutableList<Provider> = mutableListOf<Provider>()
        if (cursor.moveToFirst()) {
            do {
                result.add(
                    Provider(
                        authority =
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                    AvailableProviderResponse.AUTHORITY.key
                                )
                            ),
                        mediaSource =
                            MediaSource.valueOf(
                                cursor.getString(
                                    cursor.getColumnIndexOrThrow(
                                        AvailableProviderResponse.MEDIA_SOURCE.key
                                    )
                                )
                            ),
                        uid =
                            cursor.getInt(
                                cursor.getColumnIndexOrThrow(AvailableProviderResponse.UID.key)
                            ),
                        displayName =
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                    AvailableProviderResponse.DISPLAY_NAME.key
                                )
                            ),
                    )
                )
            } while (cursor.moveToNext())
        }

        return result
    }

    /** Creates a list of [CollectionInfo] from the given [Cursor]. */
    private fun getListOfCollectionInfo(cursor: Cursor): List<CollectionInfo> {
        val result: MutableList<CollectionInfo> = mutableListOf<CollectionInfo>()
        if (cursor.moveToFirst()) {
            do {
                val authority =
                    cursor.getString(
                        cursor.getColumnIndexOrThrow(CollectionInfoResponse.AUTHORITY.key)
                    )
                val accountConfigurationIntent: Intent? =
                    if (SdkLevel.isAtLeastT())
                    // Bundle.getParcelable API in T+
                    cursor.getExtras().getParcelable(authority, Intent::class.java)
                    // Fallback API for S or lower
                    else
                        @Suppress("DEPRECATION")
                        cursor.getExtras().getParcelable(authority) as? Intent
                result.add(
                    CollectionInfo(
                        authority = authority,
                        collectionId =
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                    CollectionInfoResponse.COLLECTION_ID.key
                                )
                            ),
                        accountName =
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                    CollectionInfoResponse.ACCOUNT_NAME.key
                                )
                            ),
                        accountConfigurationIntent = accountConfigurationIntent,
                    )
                )
            } while (cursor.moveToNext())
        }

        return result
    }

    /**
     * Creates a list of [Media] from the given [Cursor].
     *
     * [Media] can be of type [Media.Image] or [Media.Video].
     */
    private fun Cursor.getListOfMedia(
        selectionParams: PhotoPickerSelectionParams? = null
    ): List<Media> {
        val result: MutableList<Media> = mutableListOf<Media>()
        val itemsBeforeCount: Int? = getItemsBeforeCount()
        var indexCounter: Int? = itemsBeforeCount
        if (this.moveToFirst()) {
            do {
                val mediaId: String = getString(getColumnIndexOrThrow(MediaResponse.MEDIA_ID.key))
                val pickerId: Long = getLong(getColumnIndexOrThrow(MediaResponse.PICKER_ID.key))
                val index: Int? = indexCounter?.let { ++indexCounter }
                val authority: String =
                    getString(getColumnIndexOrThrow(MediaResponse.AUTHORITY.key))
                val mediaSource: MediaSource =
                    MediaSource.valueOf(
                        getString(getColumnIndexOrThrow(MediaResponse.MEDIA_SOURCE.key))
                    )
                val mediaUri: Uri =
                    Uri.parse(getString(getColumnIndexOrThrow(MediaResponse.MEDIA_URI.key)))
                val loadableUri: Uri =
                    Uri.parse(getString(getColumnIndexOrThrow(MediaResponse.LOADABLE_URI.key)))
                val dateTakenMillisLong: Long =
                    getLong(getColumnIndexOrThrow(MediaResponse.DATE_TAKEN.key))
                val sizeInBytes: Long = getLong(getColumnIndexOrThrow(MediaResponse.SIZE.key))
                val mimeType: String = getString(getColumnIndexOrThrow(MediaResponse.MIME_TYPE.key))
                val standardMimeTypeExtension: Int =
                    getInt(getColumnIndexOrThrow(MediaResponse.STANDARD_MIME_TYPE_EXT.key))
                val isPregranted: Int =
                    getInt(getColumnIndexOrThrow(MediaResponse.IS_PRE_GRANTED.key))
                val width = getInt(getColumnIndexOrThrow(MediaResponse.WIDTH.key))
                val height = getInt(getColumnIndexOrThrow(MediaResponse.HEIGHT.key))

                if (mimeType.startsWith("image/")) {
                    result.add(
                        Media.Image(
                            mediaId = mediaId,
                            pickerId = pickerId,
                            index = index,
                            authority = authority,
                            mediaSource = mediaSource,
                            mediaUri = mediaUri,
                            glideLoadableUri = loadableUri,
                            dateTakenMillisLong = dateTakenMillisLong,
                            sizeInBytes = sizeInBytes,
                            mimeType = mimeType,
                            standardMimeTypeExtension = standardMimeTypeExtension,
                            isPreGranted = (isPregranted == 1), // here 1 denotes true else false
                            width = width,
                            height = height,
                            selectionParams = selectionParams,
                        )
                    )
                } else if (mimeType.startsWith("video/")) {
                    val duration = getInt(getColumnIndexOrThrow(MediaResponse.DURATION.key))
                    result.add(
                        Media.Video(
                            mediaId = mediaId,
                            pickerId = pickerId,
                            index = index,
                            authority = authority,
                            mediaSource = mediaSource,
                            mediaUri = mediaUri,
                            glideLoadableUri = loadableUri,
                            dateTakenMillisLong = dateTakenMillisLong,
                            sizeInBytes = sizeInBytes,
                            mimeType = mimeType,
                            standardMimeTypeExtension = standardMimeTypeExtension,
                            duration = duration,
                            isPreGranted = (isPregranted == 1), // here 1 denotes true else false
                            width = width,
                            height = height,
                            selectionParams = selectionParams,
                        )
                    )
                } else {
                    throw UnsupportedOperationException("Could not recognize mime type $mimeType")
                }
            } while (moveToNext())
        }

        return result
    }

    /**
     * Extracts the previous media page key from the given [Cursor]. In case the cursor contains the
     * contents of the first page, the previous page key will be null.
     */
    private fun Cursor.getPrevMediaPageKey(): MediaPageKey? {
        val id: Long = extras.getLong(MediaResponseExtras.PREV_PAGE_ID.key, Long.MIN_VALUE)
        val date: Long =
            extras.getLong(MediaResponseExtras.PREV_PAGE_DATE_TAKEN.key, Long.MIN_VALUE)
        return if (date == Long.MIN_VALUE) {
            null
        } else {
            MediaPageKey(pickerId = id, dateTakenMillis = date)
        }
    }

    /**
     * Extracts the next media page key from the given [Cursor]. In case the cursor contains the
     * contents of the last page, the next page key will be null.
     */
    private fun Cursor.getNextMediaPageKey(): MediaPageKey? {
        val id: Long = extras.getLong(MediaResponseExtras.NEXT_PAGE_ID.key, Long.MIN_VALUE)
        val date: Long =
            extras.getLong(MediaResponseExtras.NEXT_PAGE_DATE_TAKEN.key, Long.MIN_VALUE)
        return if (date == Long.MIN_VALUE) {
            null
        } else {
            MediaPageKey(pickerId = id, dateTakenMillis = date)
        }
    }

    /**
     * Extracts the previous group page key from the given [Cursor]. In case the cursor contains the
     * contents of the first page, the previous page key will be null.
     */
    private fun Cursor.getPrevGroupPageKey(): GroupPageKey? {
        val id: Long = extras.getLong(MediaResponseExtras.PREV_PAGE_ID.key, Long.MIN_VALUE)
        return if (id == Long.MIN_VALUE) {
            null
        } else {
            GroupPageKey(pickerId = id)
        }
    }

    /**
     * Extracts the next group page key from the given [Cursor]. In case the cursor contains the
     * contents of the last page, the next page key will be null.
     */
    private fun Cursor.getNextGroupPageKey(): GroupPageKey? {
        val id: Long = extras.getLong(MediaResponseExtras.NEXT_PAGE_ID.key, Long.MAX_VALUE)
        return if (id == Long.MAX_VALUE) {
            null
        } else {
            GroupPageKey(pickerId = id)
        }
    }

    /**
     * Extracts the before items count from the given [Cursor]. In case the cursor does not contain
     * this value, return null.
     */
    private fun Cursor.getItemsBeforeCount(): Int? {
        val defaultValue = -1
        val itemsBeforeCount: Int =
            extras.getInt(MediaResponseExtras.ITEMS_BEFORE_COUNT.key, defaultValue)
        return if (defaultValue == itemsBeforeCount) null else itemsBeforeCount
    }

    /**
     * Extracts the after items count from the given [Cursor]. In case the cursor does not contain
     * this value, return null.
     */
    private fun Cursor.getItemsAfterCount(): Int? {
        val defaultValue = -1
        val itemsAfterCount: Int =
            extras.getInt(MediaResponseExtras.ITEMS_AFTER_COUNT.key, defaultValue)
        return if (defaultValue == itemsAfterCount) null else itemsAfterCount
    }

    /** Creates a list of [Group.Album]-s from the given [Cursor]. */
    private fun Cursor.getListOfAlbums(): List<Group.Album> {
        val result: MutableList<Group.Album> = mutableListOf<Group.Album>()

        if (this.moveToFirst()) {
            do {
                val albumId = getString(getColumnIndexOrThrow(AlbumResponse.ALBUM_ID.key))
                val coverUriString =
                    getString(getColumnIndexOrThrow(AlbumResponse.UNWRAPPED_COVER_URI.key))
                result.add(
                    Group.Album(
                        id = albumId,
                        // This is a temporary solution till we cache album data in Picker DB
                        pickerId = albumId.hashCode().toLong(),
                        authority = getString(getColumnIndexOrThrow(AlbumResponse.AUTHORITY.key)),
                        dateTakenMillisLong =
                            getLong(getColumnIndexOrThrow(AlbumResponse.DATE_TAKEN.key)),
                        displayName =
                            getString(getColumnIndexOrThrow(AlbumResponse.ALBUM_NAME.key)),
                        coverUri = coverUriString?.let { Uri.parse(it) } ?: Uri.parse(""),
                        coverMediaSource =
                            MediaSource.valueOf(
                                getString(
                                    getColumnIndexOrThrow(AlbumResponse.COVER_MEDIA_SOURCE.key)
                                )
                            ),
                    )
                )
            } while (moveToNext())
        }

        return result
    }

    /** Creates a list of [SearchSuggestion]-s from the given [Cursor]. */
    private fun Cursor.getListOfSearchSuggestions(
        availableProviders: List<Provider>
    ): List<SearchSuggestion> {
        val result: MutableList<SearchSuggestion> = mutableListOf<SearchSuggestion>()
        val authorityToSourceMap: Map<String, MediaSource> =
            availableProviders.associate { provider -> provider.authority to provider.mediaSource }

        if (this.moveToFirst()) {
            do {
                try {
                    result.add(
                        SearchSuggestion(
                            mediaSetId =
                                getString(
                                    getColumnIndexOrThrow(
                                        SearchSuggestionsResponse.MEDIA_SET_ID.key
                                    )
                                ),
                            authority =
                                getString(
                                    getColumnIndexOrThrow(SearchSuggestionsResponse.AUTHORITY.key)
                                ),
                            displayText =
                                getString(
                                    getColumnIndexOrThrow(SearchSuggestionsResponse.SEARCH_TEXT.key)
                                ),
                            type =
                                getSearchSuggestionType(
                                    getString(
                                        getColumnIndexOrThrow(
                                            SearchSuggestionsResponse.SUGGESTION_TYPE.key
                                        )
                                    )
                                ),
                            icon =
                                this.getIcon(
                                    authorityToSourceMap,
                                    SearchSuggestionsResponse.COVER_MEDIA_URI.key,
                                ),
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Received an invalid search suggestion. Skipping it.", e)
                }
            } while (moveToNext())
        }

        return result
    }

    /** Creates a list of [Group.Category]-s and [Group.Album]-s from the given [Cursor]. */
    private fun Cursor.getListOfCategoriesAndAlbums(
        availableProviders: List<Provider>,
        providerToIconMap: Map<Provider, Icon>,
    ): List<Group> {
        val result: MutableList<Group> = mutableListOf<Group>()
        val authorityToSourceMap: Map<String, MediaSource> =
            availableProviders.associate { provider -> provider.authority to provider.mediaSource }
        val authorityToProviderMap: Map<String, Provider> =
            availableProviders.associateBy { provider -> provider.authority }

        if (this.moveToFirst()) {
            do {
                try {
                    val groupType = getString(getColumnIndexOrThrow(GroupResponse.MEDIA_GROUP.key))
                    val authority = getString(getColumnIndexOrThrow(GroupResponse.AUTHORITY.key))
                    when (groupType) {
                        GroupType.CATEGORY.name -> {
                            val icons: List<GlideIcon> =
                                listOf<GlideIcon?>(
                                        this.getIcon(
                                            authorityToSourceMap,
                                            GroupResponse.UNWRAPPED_COVER_URI.key,
                                        ),
                                        this.getIcon(
                                            authorityToSourceMap,
                                            GroupResponse.ADDITIONAL_UNWRAPPED_COVER_URI_1.key,
                                        ),
                                        this.getIcon(
                                            authorityToSourceMap,
                                            GroupResponse.ADDITIONAL_UNWRAPPED_COVER_URI_2.key,
                                        ),
                                        this.getIcon(
                                            authorityToSourceMap,
                                            GroupResponse.ADDITIONAL_UNWRAPPED_COVER_URI_3.key,
                                        ),
                                    )
                                    .filterNotNull()
                            val categoryType =
                                KeyToCategoryType[
                                    getString(
                                        getColumnIndexOrThrow(GroupResponse.CATEGORY_TYPE.key)
                                    )]
                                    ?: throw IllegalArgumentException(
                                        "Could not recognize category type"
                                    )

                            result.add(
                                Group.Category(
                                    id =
                                        getString(
                                            getColumnIndexOrThrow(GroupResponse.GROUP_ID.key)
                                        ),
                                    pickerId =
                                        getLong(getColumnIndexOrThrow(GroupResponse.PICKER_ID.key)),
                                    authority = authority,
                                    displayName =
                                        getString(
                                            getColumnIndexOrThrow(GroupResponse.DISPLAY_NAME.key)
                                        ),
                                    categoryType = categoryType,
                                    icons = icons,
                                    isLeafCategory =
                                        getInt(
                                            getColumnIndexOrThrow(
                                                GroupResponse.IS_LEAF_CATEGORY.key
                                            )
                                        ) == 1,
                                    badge =
                                        getCategoryBadge(
                                            categoryType,
                                            authorityToProviderMap.getOrDefault(authority, null),
                                            providerToIconMap,
                                        ),
                                )
                            )
                        }

                        GroupType.ALBUM.name -> {
                            val coverUriString =
                                getString(
                                    getColumnIndexOrThrow(GroupResponse.UNWRAPPED_COVER_URI.key)
                                )
                            val coverUri = coverUriString?.let { Uri.parse(it) } ?: Uri.parse("")

                            result.add(
                                Group.Album(
                                    id =
                                        getString(
                                            getColumnIndexOrThrow(GroupResponse.GROUP_ID.key)
                                        ),
                                    pickerId =
                                        getLong(getColumnIndexOrThrow(GroupResponse.PICKER_ID.key)),
                                    authority = authority,
                                    dateTakenMillisLong =
                                        Long.MAX_VALUE, // This is not used and will soon be
                                    // obsolete
                                    displayName =
                                        getString(
                                            getColumnIndexOrThrow(GroupResponse.DISPLAY_NAME.key)
                                        ),
                                    coverUri = coverUri,
                                    coverMediaSource =
                                        coverUri?.let {
                                            authorityToSourceMap[coverUri.getAuthority()]
                                        } ?: MediaSource.LOCAL,
                                )
                            )
                        }

                        else -> {
                            Log.w(TAG, "Invalid group type: $groupType")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not extract category or album from cursor, skipping it", e)
                }
            } while (moveToNext())
        }

        return result
    }

    /** Creates a list of [Group.MediaSet]-s from the given [Cursor]. */
    private fun Cursor.getListOfMediaSets(
        availableProviders: List<Provider>,
        providerToIconMap: Map<Provider, Icon>,
        parentCategoryType: String,
    ): List<Group.MediaSet> {
        val result: MutableList<Group.MediaSet> = mutableListOf<Group.MediaSet>()
        val authorityToSourceMap: Map<String, MediaSource> =
            availableProviders.associate { provider -> provider.authority to provider.mediaSource }
        val authorityToProviderMap: Map<String, Provider> =
            availableProviders.associateBy { provider -> provider.authority }

        if (this.moveToFirst()) {
            do {
                try {
                    val authority = getString(getColumnIndexOrThrow(GroupResponse.AUTHORITY.key))
                    val badgeUri =
                        getString(getColumnIndexOrThrow(GroupResponse.BADGE_ICON_URI.key))?.toUri()
                    result.add(
                        Group.MediaSet(
                            id = getString(getColumnIndexOrThrow(GroupResponse.GROUP_ID.key)),
                            pickerId = getLong(getColumnIndexOrThrow(GroupResponse.PICKER_ID.key)),
                            authority = authority,
                            displayName =
                                getString(getColumnIndexOrThrow(GroupResponse.DISPLAY_NAME.key)),
                            icon =
                                this.getIcon(
                                    authorityToSourceMap,
                                    GroupResponse.UNWRAPPED_COVER_URI.key,
                                )
                                    ?: GlideIcon(
                                        uri = Uri.parse(""),
                                        mediaSource = MediaSource.LOCAL,
                                    ),
                            badge =
                                getMediaSetBadge(
                                    badgeUri,
                                    authorityToProviderMap.getOrDefault(authority, null),
                                    providerToIconMap,
                                ),
                            parentCategoryType = parentCategoryType,
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not extract media set from cursor, skipping it", e)
                }
            } while (moveToNext())
        }

        return result
    }

    /**
     * Creates an [GlideIcon] object from the current [Cursor] row. If an error occurs, returns
     * null.
     */
    private fun Cursor.getIcon(
        authorityToSourceMap: Map<String, MediaSource>,
        columnName: String,
    ): GlideIcon? {
        var unwrappedUriString: String? = null

        try {
            unwrappedUriString = getString(getColumnIndexOrThrow(columnName))
        } catch (e: Exception) {
            Log.e(TAG, "Could not get unwrapped uri $unwrappedUriString from cursor", e)
        }

        return unwrappedUriString?.let {
            val unwrappedUri: Uri = Uri.parse(unwrappedUriString)
            val authority: String? = unwrappedUri.getAuthority()
            val mediaSource: MediaSource = authorityToSourceMap[authority] ?: MediaSource.LOCAL
            val icon = GlideIcon(unwrappedUri, mediaSource)
            icon
        }
    }

    /**
     * Creates a list of Items count per Month from the given [Cursor].
     *
     * @return A list of [ItemsPerMonth] objects, where each object represents a year, month, and
     *   the corresponding item count (all in local time)
     */
    private fun Cursor.getListOfItemCountPerMonth(): List<ItemsPerMonth> {
        val result: MutableList<ItemsPerMonth> = mutableListOf()
        if (this.moveToFirst()) {
            do {
                val year =
                    getString(getColumnIndexOrThrow(ItemsPerMonthResponse.YEAR_TAKEN.key)).toInt()
                val month =
                    getString(getColumnIndexOrThrow(ItemsPerMonthResponse.MONTH_TAKEN.key)).toInt()
                val itemCount = getInt(getColumnIndexOrThrow(ItemsPerMonthResponse.ITEM_COUNT.key))
                result.add(ItemsPerMonth(year, month, itemCount))
            } while (moveToNext())
        }
        Log.d(
            TAG,
            "Items per month data : Found ${result.sumOf { it.itemCount }} " +
                "items over ${result.size} months/years.",
        )
        return result
    }

    /**
     * Extracts picker id and date taken from the given [Cursor]. In case the cursor does not
     * contain this value, return null.
     *
     * @return The [MediaPageKey] for the item at the specified position.
     */
    private fun Cursor.getMediaPageKey(): MediaPageKey? {
        if (this.moveToFirst()) {
            val pickerId = getLong(getColumnIndexOrThrow(MediaResponse.PICKER_ID.key))
            val dateTaken = getLong(getColumnIndexOrThrow(MediaResponse.DATE_TAKEN.key))
            return MediaPageKey(pickerId = pickerId, dateTakenMillis = dateTaken)
        }
        return null
    }

    /** Convert the input search suggestion type string to enum */
    private fun getSearchSuggestionType(stringSuggestionType: String?): SearchSuggestionType {
        requireNotNull(stringSuggestionType) { "Suggestion type is null" }
        return KeyToSearchSuggestionType[stringSuggestionType]
            ?: throw IllegalArgumentException(
                "Unrecognized search suggestion type $stringSuggestionType"
            )
    }

    /**
     * Send a refresh [Media] request to MediaProvider with the prepared input args. This is a
     * signal for MediaProvider to refresh its cache, if required.
     */
    private fun refreshMedia(extras: Bundle, contentResolver: ContentResolver) {
        try {
            contentResolver.call(
                MEDIA_PROVIDER_AUTHORITY,
                MEDIA_INIT_CALL_METHOD,
                /* arg */ null,
                extras,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not send refresh media call to Media Provider $extras", e)
        }
    }

    /**
     * Determines the appropriate badge icon for a given media category.
     *
     * This returns a static icon for device folders, no icon for app folders, and looks up the icon
     * from the provided map for all other categories.
     */
    private fun getCategoryBadge(
        categoryType: CategoryType,
        provider: Provider?,
        providerToIconMap: Map<Provider, Icon>,
    ): Icon? {
        return when (categoryType) {
            CategoryType.DEVICE_FOLDERS -> Icon(Icons.Outlined.FolderCopy)
            CategoryType.APP_FOLDERS -> null
            CategoryType.SD_CARD -> Icon(Icons.Outlined.SdCard)
            else -> providerToIconMap.getOrDefault(provider, null)
        }
    }

    private fun getMediaSetBadge(
        badgeUri: Uri?,
        provider: Provider?,
        providerToIconMap: Map<Provider, Icon>,
    ): Icon? {
        badgeUri?.let {
            return Icon(it, MediaSource.LOCAL)
        }
        return providerToIconMap.getOrDefault(provider, null)
    }
}
