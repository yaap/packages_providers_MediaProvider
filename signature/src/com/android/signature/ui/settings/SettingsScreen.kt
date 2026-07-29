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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.android.signature.R
import com.android.signature.data.Signature
import com.android.signature.logging.SignatureEventLogger
import com.android.signature.ui.common.DeleteSignatureDialog
import com.android.signature.ui.common.EmptyState
import com.android.signature.ui.common.SignatureContent

/**
 * Composable function that displays the Settings screen.
 *
 * This screen lists all saved signatures and allows the user to delete them.
 * It observes the list of signatures from the [SettingsViewModel].
 *
 * @param viewModel The [SettingsViewModel] used to manage signature data.
 * @param onNavigateUp Callback invoked when the user clicks the back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateUp: () -> Unit,
) {
    val signatures by viewModel.signatures.collectAsState()
    val signatureToDelete by viewModel.signatureToDelete.collectAsState()
    val density = LocalDensity.current

    // Show confirmation dialog when a signature is selected for deletion
    signatureToDelete?.let { signature ->
        DeleteSignatureDialog(onConfirm = {
            viewModel.deleteSignature(signature, SignatureEventLogger.Screen.SETTINGS)
        }, onDismiss = { viewModel.setSignatureToDelete(null) })
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, topBar = {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.manage_signatures_title),
                        style =
                            MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize =
                                    with(density) {
                                        dimensionResource(R.dimen.settings_title_size).toSp()
                                    },
                            ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_content_description),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            )
            // Description text
            Text(
                text = stringResource(R.string.settings_description),
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier.padding(
                        horizontal = dimensionResource(R.dimen.padding_medium),
                        vertical = dimensionResource(R.dimen.padding_small),
                    ),
            )
        }
    }) { paddingValues ->
        if (signatures.isEmpty()) {
            EmptyState(modifier = Modifier.padding(paddingValues))
        } else {
            LazyColumn(
                modifier = Modifier.padding(paddingValues).testTag("SettingsList"),
                contentPadding = PaddingValues(dimensionResource(R.dimen.padding_medium)),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_item_spacing)),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.settings_subheader),
                        style =
                            MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize =
                                    with(density) {
                                        dimensionResource(R.dimen.settings_subheader_size).toSp()
                                    },
                                color = MaterialTheme.colorScheme.onSurfaceVariant, // Use theme color
                            ),
                        modifier = Modifier.padding(bottom = dimensionResource(R.dimen.padding_small)),
                    )
                }

                itemsIndexed(
                    items = signatures,
                    key = { _, signature -> signature.id },
                ) { index, signature ->
                    val isFirst = index == 0
                    val isLast = index == signatures.lastIndex

                    val topRadius =
                        if (isFirst) {
                            dimensionResource(R.dimen.settings_corner_radius_large)
                        } else {
                            dimensionResource(
                                R.dimen.settings_corner_radius_small,
                            )
                        }
                    val bottomRadius =
                        if (isLast) {
                            dimensionResource(R.dimen.settings_corner_radius_large)
                        } else {
                            dimensionResource(
                                R.dimen.settings_corner_radius_small,
                            )
                        }

                    SettingsSignatureItem(
                        signature = signature,
                        index = index,
                        shape =
                            RoundedCornerShape(
                                topStart = topRadius,
                                topEnd = topRadius,
                                bottomStart = bottomRadius,
                                bottomEnd = bottomRadius,
                            ),
                        onDelete = { viewModel.setSignatureToDelete(signature) },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSignatureItem(
    signature: Signature,
    index: Int,
    shape: RoundedCornerShape,
    onDelete: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, shape)
                .padding(dimensionResource(R.dimen.padding_medium)),
    ) {
        // Title Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_item_title_format, index + 1),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.delete_signature_content_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Image Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_signature),
                contentDescription = stringResource(R.string.signature_application_label),
                modifier = Modifier.size(dimensionResource(R.dimen.settings_icon_size)),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.padding_medium)))

            // Image Container
            SignatureContent(
                signature = signature,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(dimensionResource(R.dimen.settings_image_box_height))
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(dimensionResource(R.dimen.corner_radius)),
                        ).padding(dimensionResource(R.dimen.padding_small)),
                imageModifier = Modifier.fillMaxSize(),
                textStyle = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}
