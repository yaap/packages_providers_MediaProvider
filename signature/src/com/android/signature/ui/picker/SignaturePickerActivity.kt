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

package com.android.signature.ui.picker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.android.signature.flags.Flags
import com.android.signature.logging.SignatureEventLogger
import com.android.signature.ui.SignatureViewModel
import com.android.signature.ui.create.CreateSignatureActivity
import com.android.signature.ui.theme.SignatureTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Activity that presents a bottom sheet for picking a signature.
 *
 * This activity is launched when an app requests a signature (ACTION_PICK_SIGNATURE).
 * It displays a list of saved signatures and allows the user to create a new one.
 * When a signature is selected, it returns the result to the calling activity and
 * grants read permission to the signature's URI.
 */
@AndroidEntryPoint(ComponentActivity::class)
@OptIn(ExperimentalMaterial3Api::class)
class SignaturePickerActivity : Hilt_SignaturePickerActivity() {
    @Inject
    lateinit var eventLogger: SignatureEventLogger

    private val viewModel: SignatureViewModel by viewModels()

    // The launcher now extracts the new signature's ID from the result intent.
    private val createSignatureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Get the ID from the result and update the state.
                // This will trigger recomposition and scroll to the newly created signature.
                val id = result.data?.getStringExtra(CreateSignatureActivity.EXTRA_SIGNATURE_ID)
                if (id != null) {
                    viewModel.setNewSignatureId(id)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        eventLogger.logSignaturePickerLaunched()

        // Runtime check for the feature flag
        if (!Flags.enableSignature()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            SignatureTheme(dynamicColor = false) {
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
                    SignaturePickerScreen(viewModel = viewModel, onAddSignature = {
                        val intent =
                            Intent(
                                this@SignaturePickerActivity,
                                CreateSignatureActivity::class.java,
                            )
                        createSignatureLauncher.launch(intent)
                    }, onSignatureSelected = { signature, uri ->
                        eventLogger.logSignatureSelected(signature.type)
                        val resultIntent = Intent().setData(uri)
                        // Only grant permission if there is a calling package
                        callingPackage?.let { pkg ->
                            grantUriPermission(
                                pkg,
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        setResult(RESULT_OK, resultIntent)
                        onDismiss()
                    }, onCancel = {
                        setResult(RESULT_CANCELED)
                        onDismiss()
                    })
                }

                LaunchedEffect(Unit) {
                    sheetState.show()
                }
            }
        }
    }
}
