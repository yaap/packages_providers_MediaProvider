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

package android.provider;

import android.os.Bundle;
import android.provider.ISearchMediaCallback;

/**
* @hide
*/
interface ISearchMediaService {

  /**
   * Initiates an asynchronous search for local media files based on the
   * provided search text and additional search parameters contained within
   * a Bundle. Results or errors will be delivered via the provided callback
   * interface.
   *
   * Default search results size is 100. Caller can pass EXTRA_SEARCH_RESULTS_PAGE_SIZE
   * in the bundle. If EXTRA_SEARCH_RESULTS_SIZE is greater than 500, search will be
   * restricted to 500 results.
   *
   * The results can be sorted by relevance (EXTRA_SORT_BY_RELEVANCE) or date taken
   * (EXTRA_SORT_BY_TIME) by specifying the sort order. The order can be specified by setting
   * EXTRA_SEARCH_RESULTS_SORT_ORDER in searchParams . The default sort order is by relevance.
   *
   * @param searchText the text string to search for within media metadata
   * @param searchId An ID to uniquely identify the search request. Unique for every call,
   * to be used to identify response.
   * @param searchParams a Bundle containing additional search parameters
   * Expected keys:
   *    EXTRA_MEDIA_TYPE_FILTER: String[], specifies a set of media types to
   *    search for.
   *
   *    EXTRA_SEARCH_RESULTS_PAGE_SIZE: Long, specifies a size of search results
   *
   *    EXTRA_SEARCH_RESULTS_SORT_ORDER: String, specifies sort order of results.
   *    Possible values are EXTRA_SORT_BY_RELEVANCE and EXTRA_SORT_BY_TIME.
   *    This is an optional field, default sort option is SORT_BY_RELEVANCE.
   *
   *    EXTRA_NEXT_PAGE_TOKEN: String, required as parameter for querying next page.
   *    This string should be received from callback along with search results.
   *    The same string should be passed as is. This is an optional field.
   *    It should not be set while querying the first page.
   * @param callback the ISearchMediaCallback.aidl implementation
   * that will receive the search results or any error messages.
   */
  oneway void searchMedia(in String searchText, in String searchId, in Bundle searchParams,
  in ISearchMediaCallback callback);


  /**
   * Requests to cancel an ongoing search operation associated
   * for given searchId.
   *
   * @param searchId An ID to uniquely identify the search request. Unique for every call,
   * to be used to identify response.
   */
  oneway void cancelSearch(in String searchId);

  /**
   * Returns whether semantic search is supported.
   */
  boolean isSemanticSearchSupported();
}
