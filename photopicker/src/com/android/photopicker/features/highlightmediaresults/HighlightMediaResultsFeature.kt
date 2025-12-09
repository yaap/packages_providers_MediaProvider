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

package com.android.photopicker.features.highlightmediaresults

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.events.Event
import com.android.photopicker.core.events.RegisteredEventClass
import com.android.photopicker.core.features.FeatureManager
import com.android.photopicker.core.features.FeatureRegistration
import com.android.photopicker.core.features.FeatureToken
import com.android.photopicker.core.features.Location
import com.android.photopicker.core.features.LocationParams
import com.android.photopicker.core.features.PhotopickerUiFeature
import com.android.photopicker.core.features.PrefetchResultKey
import com.android.photopicker.core.features.Priority
import com.android.photopicker.features.highlightmediaresults.model.HighlightAlbum
import com.android.photopicker.features.highlightmediaresults.model.HighlightQuery
import com.android.photopicker.features.search.SearchFeature
import kotlinx.coroutines.Deferred

/** Feature class for HighlightMediaResulst feature of the photopicker */
class HighlightMediaResultsFeature : PhotopickerUiFeature {

    companion object Registration : FeatureRegistration {

        override val TAG: String = "HighlightMediaResults"

        override fun isEnabled(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ): Boolean {
            // Highlight feature flags should be enabled for highlight search or album to take
            // effect. Highlight search will require photopicker search to be enabled while
            // album highlight is agnostic of search.
            return config.flags.PICKER_HIGHLIGHT_MEDIA_FEATURE_ENABLED &&
                (isAlbumHighlightFeasible(config) ||
                    isSearchHighlightFeasible(config, deferredPrefetchResultsMap))
        }

        override fun build(featureManager: FeatureManager) = HighlightMediaResultsFeature()

        private fun isAlbumHighlightFeasible(config: PhotopickerConfiguration): Boolean {
            val highlightQuery = config.highlightQueryResultsParams.queryResultsHighlightQuery
            return when (highlightQuery) {
                is HighlightQuery.Album ->
                    return highlightQuery.album != HighlightAlbum.UNSET_HIGHLIGHT_ALBUM
                else -> false
            }
        }

        private fun isSearchHighlightFeasible(
            config: PhotopickerConfiguration,
            deferredPrefetchResultsMap: Map<PrefetchResultKey, Deferred<Any?>>,
        ): Boolean {
            return SearchFeature.isEnabled(config, deferredPrefetchResultsMap)
        }
    }

    override fun registerLocations(): List<Pair<Location, Int>> {
        return listOf(Pair(Location.HIGHLIGHT_MEDIA_CAROUSEL, Priority.HIGH.priority))
    }

    @Composable
    override fun compose(location: Location, modifier: Modifier, params: LocationParams) {
        when (location) {
            Location.HIGHLIGHT_MEDIA_CAROUSEL ->
                HighlightMedia(modifier = modifier, params = params)
            else -> {}
        }
    }

    override val token = FeatureToken.HIGHLIGHT_MEDIA_RESULTS.token

    override val eventsConsumed = setOf<RegisteredEventClass>()

    /** Events produced by the highlight media feature */
    override val eventsProduced =
        setOf(
            Event.LogPhotopickerUIEvent::class.java,
            Event.ReportPhotopickerSearchInfo::class.java,
        )
}
