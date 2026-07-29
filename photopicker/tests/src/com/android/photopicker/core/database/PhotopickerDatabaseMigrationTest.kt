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

package com.android.photopicker.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotopickerDatabaseMigrationTest {
    private lateinit var database: SupportSQLiteDatabase
    private val TEST_DB = "migration-test"

    @JvmField
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            PhotopickerDatabase::class.java.canonicalName,
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    @Throws(IOException::class)
    fun migrate1To2() {
        // Create the database in version 1
        database =
            helper.createDatabase(TEST_DB, version = 1).apply {
                // Insert a row that SHOULD be migrated (uid = 0)
                execSQL("INSERT INTO banner_state (bannerId, uid, dismissed) VALUES ('1', 0, 1)")
                // Insert a row that SHOULD NOT be migrated (uid != 0)
                execSQL("INSERT INTO banner_state (bannerId, uid, dismissed) VALUES ('2', 100, 1)")
                close()
            }

        // Run the migration
        database =
            helper.runMigrationsAndValidate(TEST_DB, 2, true, PhotopickerDatabase.MIGRATION_1_2)

        // Verify that the new table 'banner_interaction_states' exists and has the correct columns
        val cursor = database.query("SELECT * FROM banner_interaction_states")
        val columns = cursor.columnNames.toList()

        assertThat(columns.contains("bannerId")).isTrue()
        assertThat(columns.contains("appUid")).isTrue()
        assertThat(columns.contains("packageName")).isTrue()
        assertThat(columns.contains("isDismissed")).isTrue()
        assertThat(columns.contains("shownCount")).isTrue()

        // Verify data migration:
        // 1. Only the row with uid=0 should be copied.
        // 2. packageName should be 'system'.
        // 3. shownCount should be 1.
        assertThat(cursor.count).isEqualTo(1)

        cursor.moveToFirst()
        val bannerIdIndex = cursor.getColumnIndex("bannerId")
        val appIdIndex = cursor.getColumnIndex("appUid")
        val packageNameIndex = cursor.getColumnIndex("packageName")
        val isDismissedIndex = cursor.getColumnIndex("isDismissed")
        val shownCountIndex = cursor.getColumnIndex("shownCount")

        assertThat(cursor.getString(bannerIdIndex)).isEqualTo("1")
        assertThat(cursor.getInt(appIdIndex)).isEqualTo(0)
        assertThat(cursor.getString(packageNameIndex)).isEqualTo("system")
        assertThat(cursor.getInt(isDismissedIndex)).isEqualTo(1)
        assertThat(cursor.getInt(shownCountIndex)).isEqualTo(1)

        cursor.close()
    }

    @Test
    fun migrateDowngrade2To1() {
        // Create the database in version 2
        var db =
            helper.createDatabase(TEST_DB, 2).apply {
                execSQL(
                    "INSERT INTO `banner_interaction_states` " +
                        "(`bannerId`, `appUid`, `packageName`, `isDismissed`, `shownCount`) " +
                        "VALUES ('banner_privacy', 1001, 'com.example.app', 1, 5)"
                )

                execSQL(
                    "INSERT INTO `banner_interaction_states` " +
                        "(`bannerId`, `appUid`, `packageName`, `isDismissed`, `shownCount`) " +
                        "VALUES ('banner_cloud', 1002, 'com.example.photos', 0, 1)"
                )
                close()
            }

        // Run migration to version 1
        db = helper.runMigrationsAndValidate(TEST_DB, 1, true, PhotopickerDatabase.MIGRATION_2_1)

        // Verify data was copied back to the old table (banner_state)
        val cursor = db.query("SELECT * FROM `banner_state`")

        assertThat(cursor.count).isEqualTo(2)

        while (cursor.moveToNext()) {
            val bannerId = cursor.getString(cursor.getColumnIndex("bannerId"))
            val uid = cursor.getInt(cursor.getColumnIndex("uid"))
            val dismissed = cursor.getInt(cursor.getColumnIndex("dismissed"))

            when (bannerId) {
                "banner_privacy" -> {
                    assertThat(uid).isEqualTo(1001)
                    assertThat(dismissed).isEqualTo(1)
                }
                "banner_cloud" -> {
                    assertThat(uid).isEqualTo(1002)
                    assertThat(dismissed).isEqualTo(0)
                }
            }
        }
        cursor.close()

        // Verify the new table was dropped
        val tableCheckCursor =
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='banner_interaction_states'"
            )
        assertThat(tableCheckCursor.count).isEqualTo(0)
        tableCheckCursor.close()
    }
}
