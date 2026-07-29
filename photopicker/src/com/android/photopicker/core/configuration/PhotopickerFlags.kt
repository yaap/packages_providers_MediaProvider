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

import com.android.photopicker.util.hashCodeOf
import com.android.providers.media.flags.Flags

// Flag namespace for mediaprovider
val NAMESPACE_MEDIAPROVIDER = "mediaprovider"

// Cloud feature flags, and their default values.
val FEATURE_CLOUD_MEDIA_FEATURE_ENABLED = Pair("cloud_media_feature_enabled", true)
val FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST = Pair("allowed_cloud_providers", arrayOf<String>())
val FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST = Pair("cloud_media_enforce_provider_allowlist", true)

// Private space feature flags, and their default values.
val FEATURE_PRIVATE_SPACE_ENABLED = Pair("private_space_feature_enabled", true)

// Permissions feature flags, and their default values.
val FEATURE_PICKER_CHOICE_MANAGED_SELECTION = Pair("picker_choice_managed_selection_enabled", true)

// HighlightSearchResults feature flag and its default value
val FEATURE_HIGHLIGHT_SEARCH_RESULTS = Pair("highlight_search_results_enabled", false)

/** Data object that represents flag values in [DeviceConfig]. */
data class PhotopickerFlags(
    /**
     * Use arrays to get around type erasure when casting device config value String value to the
     * type Array<String> in [DeviceConfigProxyImpl].
     */
    val CLOUD_ALLOWED_PROVIDERS: Array<String> = FEATURE_CLOUD_MEDIA_PROVIDER_ALLOWLIST.second,
    val CLOUD_ENFORCE_PROVIDER_ALLOWLIST: Boolean = FEATURE_CLOUD_ENFORCE_PROVIDER_ALLOWLIST.second,
    val CLOUD_MEDIA_ENABLED: Boolean = FEATURE_CLOUD_MEDIA_FEATURE_ENABLED.second,
    val PRIVATE_SPACE_ENABLED: Boolean = FEATURE_PRIVATE_SPACE_ENABLED.second,
    val MANAGED_SELECTION_ENABLED: Boolean = FEATURE_PICKER_CHOICE_MANAGED_SELECTION.second,
    val PICKER_SEARCH_ENABLED: Boolean = Flags.enablePhotopickerSearch(),
    val PICKER_DATESCRUBBER_ENABLED: Boolean = Flags.enablePhotopickerDatescrubber(),
    val PICKER_LOCATION_METADATA_ENABLED: Boolean = Flags.enablePhotopickerLocationMetadata(),
    val PICKER_TRANSCODING_ENABLED: Boolean = Flags.enablePhotopickerTranscoding(),
    val PICKER_HIGHLIGHT_MEDIA_FEATURE_ENABLED: Boolean =
        Flags.enablePickerHighlightSearchResultsApis(),
    val OWNED_PHOTOS_ENABLED: Boolean = Flags.revokeAccessOwnedPhotos(),
    val PICKER_THUMBNAIL_PRELOAD_ENABLED: Boolean = Flags.enablePhotopickerThumbnailPreload(),
    val POLAROID_ENABLED: Boolean = Flags.photopickerPolaroid(),
    val MODERN_CLOUD_SETTINGS_ENABLED: Boolean = Flags.enableModernPhotopickerCloudSettingsPage(),
    val PICKER_DELETE_HISTORY_SUGGESTION: Boolean =
        Flags.enablePhotopickerDeleteHistorySuggestion(),
    val PHOTOPICKER_DESKTOP_ENABLED: Boolean = Flags.enablePhotopickerDesktop(),
    val PICKER_OFFLINE_BANNERS_ENABLED: Boolean = Flags.enablePhotopickerOfflineBanners(),
    val PICKER_BANNER_REDESIGN_ENABLED: Boolean = Flags.enablePhotopickerBannerRedesign(),
    val CMP_IMPROVEMENTS_ENABLED: Boolean = Flags.enableCmpImprovements(),
    val PICKER_UI_CUSTOMIZATION_ENABLED: Boolean =
        Flags.enablePhotopickerUiCustomizationParamsApi() &&
            Flags.enablePhotopickerUiCustomizationParamsUsage(),
    val PICKER_SELECTION_PARAMS_ENABLED: Boolean =
        Flags.enablePhotopickerSelectionParamsApi() && Flags.enablePhotopickerSelectionParamsUsage(),
) {
    /**
     * Implement a custom equals method to correctly check the equality of the Array member
     * variables in [PhotopickerFlags].
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is PhotopickerFlags) return false
        if (!CLOUD_ALLOWED_PROVIDERS.contentEquals(other.CLOUD_ALLOWED_PROVIDERS)) return false
        if (CLOUD_ENFORCE_PROVIDER_ALLOWLIST != other.CLOUD_ENFORCE_PROVIDER_ALLOWLIST) return false
        if (CLOUD_MEDIA_ENABLED != other.CLOUD_MEDIA_ENABLED) return false
        if (PRIVATE_SPACE_ENABLED != other.PRIVATE_SPACE_ENABLED) return false
        if (MANAGED_SELECTION_ENABLED != other.MANAGED_SELECTION_ENABLED) return false
        if (PICKER_SEARCH_ENABLED != other.PICKER_SEARCH_ENABLED) return false
        if (PICKER_DATESCRUBBER_ENABLED != other.PICKER_DATESCRUBBER_ENABLED) return false
        if (PICKER_LOCATION_METADATA_ENABLED != other.PICKER_LOCATION_METADATA_ENABLED) return false
        if (PICKER_TRANSCODING_ENABLED != other.PICKER_TRANSCODING_ENABLED) return false
        if (PICKER_HIGHLIGHT_MEDIA_FEATURE_ENABLED != other.PICKER_HIGHLIGHT_MEDIA_FEATURE_ENABLED)
            return false
        if (OWNED_PHOTOS_ENABLED != other.OWNED_PHOTOS_ENABLED) return false
        if (PICKER_THUMBNAIL_PRELOAD_ENABLED != other.PICKER_THUMBNAIL_PRELOAD_ENABLED) return false
        if (POLAROID_ENABLED != other.POLAROID_ENABLED) return false
        if (MODERN_CLOUD_SETTINGS_ENABLED != other.MODERN_CLOUD_SETTINGS_ENABLED) return false
        if (PICKER_DELETE_HISTORY_SUGGESTION != other.PICKER_DELETE_HISTORY_SUGGESTION) return false
        if (PHOTOPICKER_DESKTOP_ENABLED != other.PHOTOPICKER_DESKTOP_ENABLED) return false
        if (PICKER_OFFLINE_BANNERS_ENABLED != other.PICKER_OFFLINE_BANNERS_ENABLED) return false
        if (PICKER_BANNER_REDESIGN_ENABLED != other.PICKER_BANNER_REDESIGN_ENABLED) return false
        if (CMP_IMPROVEMENTS_ENABLED != other.CMP_IMPROVEMENTS_ENABLED) return false
        if (PICKER_UI_CUSTOMIZATION_ENABLED != other.PICKER_UI_CUSTOMIZATION_ENABLED) return false
        if (PICKER_SELECTION_PARAMS_ENABLED != other.PICKER_SELECTION_PARAMS_ENABLED) return false
        return true
    }

    /**
     * Implement a custom hashcode method to correctly check the equality of the Array member
     * variables in [PhotopickerFlags].
     */
    override fun hashCode(): Int =
        hashCodeOf(
            CLOUD_ALLOWED_PROVIDERS,
            CLOUD_ENFORCE_PROVIDER_ALLOWLIST,
            CLOUD_MEDIA_ENABLED,
            PRIVATE_SPACE_ENABLED,
            MANAGED_SELECTION_ENABLED,
            PICKER_SEARCH_ENABLED,
            PICKER_DATESCRUBBER_ENABLED,
            PICKER_LOCATION_METADATA_ENABLED,
            PICKER_TRANSCODING_ENABLED,
            PICKER_HIGHLIGHT_MEDIA_FEATURE_ENABLED,
            OWNED_PHOTOS_ENABLED,
            PICKER_THUMBNAIL_PRELOAD_ENABLED,
            POLAROID_ENABLED,
            MODERN_CLOUD_SETTINGS_ENABLED,
            PICKER_DELETE_HISTORY_SUGGESTION,
            PHOTOPICKER_DESKTOP_ENABLED,
            PICKER_OFFLINE_BANNERS_ENABLED,
            PICKER_BANNER_REDESIGN_ENABLED,
            CMP_IMPROVEMENTS_ENABLED,
            PICKER_UI_CUSTOMIZATION_ENABLED,
            PICKER_SELECTION_PARAMS_ENABLED,
        )
}
