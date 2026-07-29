/*
 * Copyright 2026 The Android Open Source Project
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

package com.android.photopicker.core.banners

import com.android.photopicker.core.features.Priority

/**
 * Defines the static properties of all supported banners in Photopicker.
 *
 * This enum serves as a central registry for banners, specifying their identity and behavior. Each
 * entry represents a unique banner type with predefined attributes that control its display
 * priority, dismissibility, and max show count.
 *
 * @param id A unique string identifier for the banner.
 * @param priority The default display priority of the banner relative to others.
 * @param manualDismissible Whether the user is allowed to dismiss the banner manually.
 * @param autoDismissible Whether the system can dismiss the banner automatically after a certain
 *   number of views.
 * @param maxShowCount The maximum number of times the banner can be shown. A `null` value indicates
 *   no limit. This is typically used for auto-dismissible banners.
 * @param dismissiblePer The strategy for tracking the banner's dismissal state.
 */
enum class BannerDefinition(
    val id: String,
    val priority: Priority,
    val manualDismissible: Boolean,
    val autoDismissible: Boolean,
    val maxShowCount: Int? = null,
    val dismissiblePer: BannerDismissStrategy?,
) {
    // keep-sorted start
    CLOUD_CHOOSE_ACCOUNT(
        "cloud_choose_account",
        Priority.MEDIUM,
        true,
        true,
        5,
        BannerDismissStrategy.PER_DEVICE,
    ),
    CLOUD_CHOOSE_PROVIDER(
        "cloud_choose_provider",
        Priority.MEDIUM,
        true,
        true,
        5,
        BannerDismissStrategy.PER_DEVICE,
    ),
    CLOUD_MEDIA_AVAILABLE(
        "cloud_media_available",
        Priority.MEDIUM,
        true,
        true,
        1,
        BannerDismissStrategy.PER_DEVICE,
    ),
    PRIVACY_EXPLAINER(
        "privacy_explainer",
        Priority.LOW,
        true,
        true,
        1,
        BannerDismissStrategy.PER_DEVICE,
    ),
    PRIVACY_EXPLAINER_LIMITED_ACCESS(
        "privacy_explainer_limited_access",
        Priority.LOW,
        true,
        false,
        null,
        BannerDismissStrategy.PER_UID,
    ),
    SWITCH_PROFILE(
        "switch_profile",
        Priority.HIGH,
        true,
        false,
        null,
        BannerDismissStrategy.PER_UID,
    ),
    // keep-sorted end
}

/** Defines the strategy for how a banner's dismissal is tracked and persisted. */
enum class BannerDismissStrategy {
    /** The banner is dismissed once for the entire device. */
    PER_DEVICE,
    /** The banner is dismissed on a per-application basis. */
    PER_UID,
}
