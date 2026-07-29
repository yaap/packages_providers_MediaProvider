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

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.android.signature.R
import com.android.signature.data.Signature
import com.android.signature.logging.SignatureEventLogger
import com.android.signature.ui.SignatureLimitException
import com.android.signature.ui.SignatureViewModel
import com.android.signature.ui.util.createBitmapFromText
import kotlinx.coroutines.launch

/**
 * Composable function that displays the Create Signature screen.
 *
 * This screen allows the user to create a signature using one of three methods:
 * Drawing, Typing, or Uploading an image.
 *
 * @param viewModel The [SignatureViewModel] used to manage signature creation state.
 * @param onSignatureCreated Callback invoked when a new signature is successfully created.
 * @param onCancel Callback invoked when the user cancels the creation process.
 */
@VisibleForTesting
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSignatureScreen(
    viewModel: SignatureViewModel,
    onSignatureCreated: (Signature) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedTabIndex by viewModel.selectedTabIndex.collectAsState()
    val tabs =
        listOf(
            stringResource(R.string.tab_draw),
            stringResource(R.string.tab_type),
            stringResource(R.string.tab_upload),
        )
    val density = LocalDensity.current
    val bitmapTextSize =
        with(density) {
            dimensionResource(R.dimen.signature_bitmap_text_size).toPx()
        }
    val errorSavingMessage = stringResource(R.string.error_save_signature)
    val errorLimitReachedMessage =
        stringResource(
            R.string.error_max_signatures_reached,
            SignatureViewModel.MAX_SIGNATURES,
        )
    val launchSaveOperation = { saveAction: suspend () -> Signature ->
        scope.launch {
            try {
                val newSignature = saveAction()
                onSignatureCreated(newSignature)
            } catch (e: Exception) {
                // Safely type-check the exception
                val errorType =
                    when (e) {
                        is SignatureLimitException -> {
                            snackbarHostState.showSnackbar(errorLimitReachedMessage)
                            SignatureEventLogger.AppErrorType.DB_WRITE_FAILED
                        }

                        else -> {
                            snackbarHostState.showSnackbar(errorSavingMessage)
                            SignatureEventLogger.AppErrorType.DB_WRITE_FAILED
                        }
                    }

                // We'll log the specific tab index as the "type" since the signature failed to save
                val failedType =
                    when (selectedTabIndex) {
                        0 -> Signature.TYPE_DRAWN
                        1 -> Signature.TYPE_TYPED
                        2 -> Signature.TYPE_UPLOADED
                        else -> 0
                    }
                viewModel.eventLogger.logSignatureAppError(errorType, failedType)
            }
        }
    }
    // The root is a Column, suitable for a bottom sheet.
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow) // Use theme color
                .navigationBarsPadding() // Add padding for system navigation bars
                .imePadding(),
        // Handle keyboard
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            // A custom header to replace the TopAppBar
            Text(
                text = stringResource(R.string.create_signature_title),
                style = MaterialTheme.typography.titleLarge,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(dimensionResource(R.dimen.padding_medium)),
                textAlign = TextAlign.Start,
            )
            // Chip bar style tabs
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dimensionResource(R.dimen.padding_medium))
                        .height(dimensionResource(R.dimen.chip_bar_height))
                        .shadow(
                            elevation = dimensionResource(R.dimen.card_elevation),
                            shape = CircleShape,
                        ).background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape,
                        ).clip(CircleShape),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedTabIndex == index
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(dimensionResource(R.dimen.chip_height))
                                .padding(horizontal = dimensionResource(R.dimen.chip_spacing))
                                .clip(CircleShape)
                                .background(
                                    color =
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            Color.Transparent
                                        },
                                    shape = CircleShape,
                                ).clickable { viewModel.setSelectedTabIndex(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }
                }
            }
            // The content of the tabs is placed in a Box with a fixed height
            // to ensure it fits well within a bottom sheet and becomes scrollable if needed.
            Box(
                modifier =
                    Modifier.height(
                        dimensionResource(R.dimen.bottom_sheet_max_height),
                    ),
            ) {
                when (selectedTabIndex) {
                    0 -> {
                        DrawTab(
                            viewModel = viewModel,
                            onSave = { bitmap ->
                                launchSaveOperation { viewModel.saveDrawnSignature(bitmap) }
                            },
                            onCancel = onCancel,
                        )
                    }

                    1 -> {
                        TypeTab(viewModel = viewModel, onSave = { text, font ->
                            val bitmap =
                                createBitmapFromText(
                                    text,
                                    font.androidTypeface,
                                    bitmapTextSize,
                                )
                            launchSaveOperation {
                                viewModel.saveTypedSignature(text, font.name, bitmap)
                            }
                        })
                    }

                    2 -> {
                        val uploadedImage by viewModel.uploadedImage.collectAsState()
                        UploadTab(
                            bitmap = uploadedImage,
                            onBitmapChanged = { viewModel.setUploadedImage(it) },
                            onSave = { bitmap ->
                                launchSaveOperation { viewModel.saveUploadedSignature(bitmap) }
                            },
                            onCancel = onCancel,
                            onShowError = { message ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                        )
                    }
                }
            }
        }
        // SnackbarHost
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
