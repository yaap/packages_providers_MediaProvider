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

package com.android.signature.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.signature.flags.Flags
import com.android.signature.logging.SignatureEventLogger
import com.android.signature.ui.theme.SignatureTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Activity that hosts the Settings screen.
 *
 * This activity is responsible for setting up the Compose content and providing the
 * [SettingsViewModel] to the [SettingsScreen]. It handles navigation back to the
 * previous screen when the user interacts with the back button in the top app bar.
 */
@AndroidEntryPoint(ComponentActivity::class)
class SettingsActivity : Hilt_SettingsActivity() {
    @Inject
    lateinit var eventLogger: SignatureEventLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        eventLogger.logSignatureSettingsLaunched()

        // Runtime check for the feature flag
        if (!Flags.enableSignature()) {
            finish()
            return
        }

        setContent {
            SignatureTheme {
                // Use Hilt to create the ViewModel
                val viewModel: SettingsViewModel = viewModel()

                // Pass the onNavigateUp parameter, which calls finish() on the activity.
                SettingsScreen(viewModel = viewModel, onNavigateUp = { finish() })
            }
        }
    }
}
