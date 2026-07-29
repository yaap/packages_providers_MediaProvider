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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.database.PhotopickerDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class BannerInteractionStateDaoTest {

    private lateinit var database: PhotopickerDatabase
    private lateinit var bannerInteractionStateDao: BannerInteractionStateDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    PhotopickerDatabase::class.java,
                )
                .build()
        bannerInteractionStateDao = database.bannerInteractionStateDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testSetAndGetBannerInteractionState_systemWide() {
        val state =
            BannerInteractionState(
                bannerId = BannerDefinition.CLOUD_CHOOSE_ACCOUNT,
                appUid = 0,
                packageName = "system",
                isDismissed = true,
                shownCount = 5,
            )

        bannerInteractionStateDao.setBannerInteractionState(state)

        val states =
            bannerInteractionStateDao.getBannerInteractionStates(
                uid = 123,
                packageName = "com.example.app",
            )
        assertThat(states).isNotNull()
        assertThat(states).hasSize(1)
        assertThat(states!![0]).isEqualTo(state)
    }

    @Test
    fun testSetAndGetBannerInteractionState_perApp() {
        val state =
            BannerInteractionState(
                bannerId = BannerDefinition.SWITCH_PROFILE,
                appUid = 123,
                packageName = "com.example.app",
                isDismissed = false,
                shownCount = 2,
            )

        bannerInteractionStateDao.setBannerInteractionState(state)

        val states =
            bannerInteractionStateDao.getBannerInteractionStates(
                uid = 123,
                packageName = "com.example.app",
            )
        assertThat(states).isNotNull()
        assertThat(states).hasSize(1)
        assertThat(states!![0]).isEqualTo(state)
    }

    @Test
    fun testGetBannerInteractionStates_returnsSystemAndAppSpecific() {
        val systemState =
            BannerInteractionState(
                bannerId = BannerDefinition.CLOUD_CHOOSE_ACCOUNT,
                appUid = 0,
                packageName = "system",
                isDismissed = true,
                shownCount = 5,
            )
        val appState =
            BannerInteractionState(
                bannerId = BannerDefinition.SWITCH_PROFILE,
                appUid = 123,
                packageName = "com.example.app",
                isDismissed = false,
                shownCount = 2,
            )
        val otherAppState =
            BannerInteractionState(
                bannerId = BannerDefinition.PRIVACY_EXPLAINER,
                appUid = 456,
                packageName = "com.example.other",
                isDismissed = true,
                shownCount = 1,
            )

        bannerInteractionStateDao.setBannerInteractionState(systemState)
        bannerInteractionStateDao.setBannerInteractionState(appState)
        bannerInteractionStateDao.setBannerInteractionState(otherAppState)

        val states =
            bannerInteractionStateDao.getBannerInteractionStates(
                uid = 123,
                packageName = "com.example.app",
            )
        assertThat(states).isNotNull()
        assertThat(states).hasSize(2)
        assertThat(states).containsExactly(systemState, appState)
    }

    @Test
    fun testUpsertUpdatesExistingState() {
        val initialState =
            BannerInteractionState(
                bannerId = BannerDefinition.CLOUD_CHOOSE_ACCOUNT,
                appUid = 123,
                packageName = "com.example.app",
                isDismissed = false,
                shownCount = 1,
            )
        bannerInteractionStateDao.setBannerInteractionState(initialState)

        val updatedState =
            BannerInteractionState(
                bannerId = BannerDefinition.CLOUD_CHOOSE_ACCOUNT,
                appUid = 123,
                packageName = "com.example.app",
                isDismissed = true,
                shownCount = 2,
            )
        bannerInteractionStateDao.setBannerInteractionState(updatedState)

        val states =
            bannerInteractionStateDao.getBannerInteractionStates(
                uid = 123,
                packageName = "com.example.app",
            )
        assertThat(states).isNotNull()
        assertThat(states).hasSize(1)
        assertThat(states!![0]).isEqualTo(updatedState)
    }

    @Test
    fun testGetBannerInteractionStates_filtersCorrectlyPerUid() {
        val app1State =
            BannerInteractionState(
                bannerId = BannerDefinition.SWITCH_PROFILE,
                appUid = 123,
                packageName = "com.example.app1",
                isDismissed = false,
                shownCount = 1,
            )
        val app2State =
            BannerInteractionState(
                bannerId = BannerDefinition.SWITCH_PROFILE,
                appUid = 456,
                packageName = "com.example.app2",
                isDismissed = false,
                shownCount = 1,
            )

        bannerInteractionStateDao.setBannerInteractionState(app1State)
        bannerInteractionStateDao.setBannerInteractionState(app2State)

        val states1 =
            bannerInteractionStateDao.getBannerInteractionStates(
                uid = 123,
                packageName = "com.example.app1",
            )
        assertThat(states1).containsExactly(app1State)

        val states2 =
            bannerInteractionStateDao.getBannerInteractionStates(
                uid = 456,
                packageName = "com.example.app2",
            )
        assertThat(states2).containsExactly(app2State)
    }

    @Test
    fun testGetBannerInteractionStates_noMatch() {
        val states =
            bannerInteractionStateDao.getBannerInteractionStates(
                uid = 123,
                packageName = "com.example.app",
            )
        assertThat(states).isEmpty()
    }

    @Test
    fun testGetBannerInteractionStates_mismatchedPackageName() {
        val state =
            BannerInteractionState(
                bannerId = BannerDefinition.SWITCH_PROFILE,
                appUid = 123,
                packageName = "com.example.app",
                isDismissed = false,
                shownCount = 2,
            )

        bannerInteractionStateDao.setBannerInteractionState(state)

        // Query with same UID but different package name
        val states =
            bannerInteractionStateDao.getBannerInteractionStates(
                uid = 123,
                packageName = "com.example.other",
            )
        assertThat(states).isEmpty()
    }
}
