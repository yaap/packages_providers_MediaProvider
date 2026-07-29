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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.signature.data.Signature
import com.android.signature.data.SignatureRepository
import com.android.signature.logging.SignatureEventLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Settings screen.
 *
 * This ViewModel retrieves and manages the list of saved signatures.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val repository: SignatureRepository,
        private val eventLogger: SignatureEventLogger,
    ) : ViewModel() {
        private val creationTime = System.currentTimeMillis()
        private var hasLoggedInitialLoad = false // Track the first emission

        private val _signatureToDelete = MutableStateFlow<Signature?>(null)

        /**
         * The signature currently selected for deletion, triggering the confirmation dialog.
         */
        val signatureToDelete: StateFlow<Signature?> = _signatureToDelete.asStateFlow()

        /**
         * A stream of all signatures observed from the database.
         * Starts as empty until the data loads.
         * Kept active for 5 seconds after the UI disconnects
         * to avoid re-querying the database during screen rotations.
         */
        val signatures: StateFlow<List<Signature>> =
            repository
                .getAllSignatures()
                .onEach { list ->
                    // Only log if it's the first non-empty emission
                    if (list.isNotEmpty() && !hasLoggedInitialLoad) {
                        hasLoggedInitialLoad = true
                        val duration = System.currentTimeMillis() - creationTime
                        eventLogger.logSignaturesLoadDuration(
                            duration,
                            list.size,
                            SignatureEventLogger.Screen.SETTINGS,
                        )
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = emptyList(),
                )

        /**
         * Sets the signature to be deleted.
         */
        fun setSignatureToDelete(signature: Signature?) {
            _signatureToDelete.value = signature
        }

        /**
         * Deletes a given signature from the data source.
         *
         * @param signature The signature to be deleted.
         */
        fun deleteSignature(
            signature: Signature,
            screen: SignatureEventLogger.Screen,
        ) {
            viewModelScope.launch {
                val start = System.currentTimeMillis()
                repository.deleteSignature(signature)
                _signatureToDelete.value = null
                val duration = System.currentTimeMillis() - start
                eventLogger.logSignatureDeleteDuration(duration, screen)
                eventLogger.logSignatureDeleted(signature.type, screen)
            }
        }
    }
