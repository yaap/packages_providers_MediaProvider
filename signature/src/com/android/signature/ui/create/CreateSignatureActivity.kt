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

package com.android.signature.ui.create

import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.signature.flags.Flags
import com.android.signature.logging.SignatureEventLogger
import com.android.signature.ui.SignatureViewModel
import com.android.signature.ui.create.CreateSignatureActivity.Companion.EXTRA_SIGNATURE_ID
import com.android.signature.ui.theme.SignatureTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Activity for creating a new signature.
 *
 * This activity allows the user to create a signature by drawing, typing, or uploading an image.
 * Upon successful creation, it returns the ID of the new signature in the result intent
 * using [EXTRA_SIGNATURE_ID].
 */
@AndroidEntryPoint(ComponentActivity::class)
@OptIn(ExperimentalMaterial3Api::class)
class CreateSignatureActivity : Hilt_CreateSignatureActivity() {
    @Inject
    lateinit var eventLogger: SignatureEventLogger

    // Define a companion object for the intent extra key.
    companion object {
        /**
         * Intent extra key for the ID of the created signature.
         */
        const val EXTRA_SIGNATURE_ID = "com.android.signature.EXTRA_SIGNATURE_ID"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        eventLogger.logSignatureCreateLaunched()

        // Runtime check for the feature flag
        if (!Flags.enableSignature()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            SignatureTheme(dynamicColor = false) {
                // Hilt will provide the ViewModel
                val viewModel: SignatureViewModel = viewModel()
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val scope = rememberCoroutineScope()

                val onDismiss = {
                    scope
                        .launch {
                            sheetState.hide()
                        }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                finish()
                            }
                        }
                }

                ModalBottomSheet(
                    onDismissRequest = { onDismiss() },
                    sheetState = sheetState,
                ) {
                    CreateSignatureScreen(
                        viewModel = viewModel,
                        // The callback now receives the newly created signature.
                        onSignatureCreated = { newSignature ->
                            eventLogger.logSignatureCreated(
                                newSignature.type,
                                newSignature.imageData?.size ?: 0,
                            )
                            // Put the new ID in the result intent.
                            val resultIntent =
                                Intent().putExtra(
                                    EXTRA_SIGNATURE_ID,
                                    newSignature.id,
                                )
                            setResult(RESULT_OK, resultIntent)
                            onDismiss()
                        },
                        onCancel = {
                            onDismiss()
                        },
                    )
                }

                LaunchedEffect(Unit) {
                    sheetState.show()
                }
            }
        }
    }
}
