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

package com.android.photopicker.features.datescrubber.inject

import android.util.Log
import com.android.photopicker.core.Background
import com.android.photopicker.core.EmbeddedServiceComponent
import com.android.photopicker.core.SessionScoped
import com.android.photopicker.core.configuration.ConfigurationManager
import com.android.photopicker.data.DataService
import com.android.photopicker.data.MediaProviderClient
import com.android.photopicker.data.NotificationService
import com.android.photopicker.features.datescrubber.data.DateScrubberDataService
import com.android.photopicker.features.datescrubber.data.DateScrubberDataServiceImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Injection Module for date scrubber feature specific dependencies, that provides access to objects
 * bound to a single [EmbeddedServiceComponent].
 *
 * The module is bound to a single instance of the embedded Photopicker, and first obtained in the
 * [Session].
 *
 * Note: Jobs that are launched in the [CoroutineScope] provided by this module will be
 * automatically cancelled when the [EmbeddedLifecycle] provided by this module ends.
 */
@Module
@InstallIn(EmbeddedServiceComponent::class)
class DateScrubberEmbeddedServiceModule {
    companion object {
        val TAG: String = "DateScrubberEmbeddedModule"
    }

    // Avoid initialization until it's actually needed.
    private lateinit var dateScrubberDataService: DateScrubberDataService

    /** Provider for an implementation of [DateScrubberDataService]. */
    @Provides
    @SessionScoped
    fun provideDateScrubberDataService(
        dataService: DataService,
        configurationManager: ConfigurationManager,
        @Background scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
        notificationService: NotificationService,
    ): DateScrubberDataService {
        if (::dateScrubberDataService.isInitialized) {
            return dateScrubberDataService
        } else {
            Log.d(
                DateScrubberDataService.TAG,
                "DateScrubberDataService requested but not yet initialized." +
                    " Initializing DateScrubberDataService.",
            )

            dateScrubberDataService =
                DateScrubberDataServiceImpl(
                    dataService,
                    configurationManager.configuration,
                    scope,
                    dispatcher,
                    MediaProviderClient(),
                    notificationService,
                )
            return dateScrubberDataService
        }
    }
}
