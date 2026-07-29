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

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * A [PhotopickerDatabase] entity that persists the user's interaction state with a banner.
 *
 * This table tracks mutable data such as dismissal status and the number of times a banner has been
 * shown. The primary key is a composite of [bannerId], [appUid], and [packageName] to ensure a
 * unique entry for each banner and its dismissal context.
 *
 * @property bannerId The [BannerDefinition] this state refers to.
 * @property appUid The application UID this state is for. A value of `0` represents a device-wide
 *   (global) state.
 * @property packageName The application package name this state is for. A value of `"system"`
 *   represents a device-wide (global) state.
 * @property isDismissed Whether the banner has been dismissed.
 * @property shownCount The number of times the banner has been shown to the user.
 */
@Entity(
    tableName = "banner_interaction_states",
    primaryKeys = ["bannerId", "appUid", "packageName"],
)
data class BannerInteractionState(
    val bannerId: BannerDefinition,
    val appUid: Int, // 0 for device-wide, app uid for PER_UID
    val packageName: String, // "system" for device-wide, package name for PER_UID
    var isDismissed: Boolean,
    var shownCount: Int,
)

/** An interface to read and write rows from the [BannerInteractionState] table. */
@Dao
interface BannerInteractionStateDao {

    /**
     * Retrieves the list of banner interaction states relevant to a certain application, in
     * addition to global device level states.
     *
     * This method queries the `banner_interaction_states` table to find records that apply to the
     * specific calling app (defined by [uid] and [packageName]) as well as global records (defined
     * by `appUid = 0` or `packageName = 'system'`).
     *
     * @param uid The UID of the calling application.
     * @param packageName The package name of the calling application.
     * @return A list of [BannerInteractionState] objects matching the criteria, or null.
     */
    @Query(
        """
    SELECT * FROM banner_interaction_states
    WHERE (appUid = :uid OR appUid = 0)
    AND (packageName = :packageName OR packageName = 'system')
"""
    )
    fun getBannerInteractionStates(uid: Int, packageName: String): List<BannerInteractionState>?

    /**
     * Write a row for a specific [BannerInteractionState].
     *
     * This is an upsert method that will first try to insert the row, but will update the existing
     * row on primary key conflict.
     *
     * @param bannerInteractionState The row to write to the database.
     */
    @Upsert fun setBannerInteractionState(bannerInteractionState: BannerInteractionState)
}
