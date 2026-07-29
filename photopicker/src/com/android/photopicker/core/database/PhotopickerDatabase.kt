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

package com.android.photopicker.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.android.photopicker.core.banners.BannerDefinition
import com.android.photopicker.core.banners.BannerInteractionState
import com.android.photopicker.core.banners.BannerInteractionStateDao
import com.android.photopicker.core.banners.BannerState
import com.android.photopicker.core.banners.BannerStateDao

/**
 * A [Room] database for persisting data.
 *
 * Add new @Entity classes to the [entities] mapping, and increment the schema version. Any new @Dao
 * interfaces need to be added to this abstract class so that the Room library will generate a
 * matching implementation.
 *
 * A schema will be generated in packages/providers/MediaProvider/photopicker/schemas when
 * Photopicker is compiled, and be sure to commit any schema changes to source control for managing
 * migrations between versions.
 */
@Database(entities = [BannerState::class, BannerInteractionState::class], version = 2)
@TypeConverters(Converters::class)
abstract class PhotopickerDatabase : RoomDatabase() {
    abstract fun bannerStateDao(): BannerStateDao

    abstract fun bannerInteractionStateDao(): BannerInteractionStateDao

    companion object {
        /**
         * Database migration from version 1 to version 2 adds new table banner_interaction_states
         */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `banner_interaction_states` (`bannerId` TEXT NOT NULL, `appUid` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `isDismissed` INTEGER NOT NULL, `shownCount` INTEGER NOT NULL, PRIMARY KEY(`bannerId`, `appUid`, `packageName`))"
                    )

                    // Copy data from banner_state to banner_interaction_states
                    // Sets packageName to 'system' where uid is 0
                    // Defaults shownCount to 1
                    db.execSQL(
                        "INSERT INTO `banner_interaction_states` (`bannerId`, `appUid`, `packageName`, `isDismissed`, `shownCount`) " +
                            "SELECT `bannerId`, `uid`, 'system', `dismissed`, 1 " +
                            "FROM `banner_state` " +
                            "WHERE `uid` = 0"
                    )
                }
            }

        /**
         * Database migration from version 2 to version 1. Copies data back to banner_state and
         * drops banner_interaction_states.
         */
        val MIGRATION_2_1 =
            object : Migration(2, 1) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Copy data from banner_interaction_states to banner_state
                    // We use INSERT OR REPLACE to update existing records or insert new ones
                    db.execSQL(
                        "INSERT OR REPLACE INTO `banner_state` (`bannerId`, `dismissed`, `uid`) " +
                            "SELECT `bannerId`, `isDismissed`, `appUid` " +
                            "FROM `banner_interaction_states`"
                    )

                    // Drop the new table to revert schema to version 1
                    db.execSQL("DROP TABLE IF EXISTS `banner_interaction_states`")
                }
            }
    }
}

// In Converters.kt
class Converters {
    @TypeConverter
    fun fromBannerId(value: BannerDefinition?): String? {
        return value?.name
    }

    @TypeConverter
    fun toBannerId(value: String?): BannerDefinition? {
        return value?.let { enumValueOf<BannerDefinition>(it) }
    }
}
