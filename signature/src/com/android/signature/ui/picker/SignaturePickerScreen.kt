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

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.android.signature.R
import com.android.signature.data.Signature
import com.android.signature.ui.SignatureViewModel
import com.android.signature.ui.common.DeleteSignatureDialog
import com.android.signature.ui.common.SignatureContent
import com.android.signature.logging.SignatureEventLogger
import kotlinx.coroutines.flow.first

/**
 * Composable function that displays the Signature Picker screen.
 *
 * This screen is designed to be shown within a bottom sheet. It lists available signatures,
 * allows adding new ones, and handles selection and deletion.
 *
 * @param viewModel The [SignatureViewModel] used to manage signature data.
 * @param onAddSignature Callback invoked when the "Add New" button is clicked.
 * @param onSignatureSelected Callback invoked when a signature is selected.
 * @param onCancel Callback invoked when the cancel action is triggered (though currently unused in UI).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignaturePickerScreen(
    viewModel: SignatureViewModel,
    onAddSignature: () -> Unit,
    onSignatureSelected: (Signature, Uri) -> Unit,
    onCancel: () -> Unit,
) {
    val signatures by viewModel.signatures.collectAsState()
    val newlyAddedSignatureIndex by viewModel.newlyAddedSignatureIndex.collectAsState()
    val signatureToDelete by viewModel.signatureToDelete.collectAsState()

    val listState = rememberLazyListState()

    // This effect triggers scrolling when a new index is received from the ViewModel.
    LaunchedEffect(newlyAddedSignatureIndex) {
        newlyAddedSignatureIndex?.let { index ->
            // Add 1 to index because of the "Add Signature" item at the top
            val targetIndex = index + 1

            // Wait until the layout is updated and knows about the new item's total count.
            snapshotFlow { listState.layoutInfo }.first { it.totalItemsCount > targetIndex }

            // Scroll to the new item directly since we are in a coroutine scope
            listState.animateScrollToItem(targetIndex)

            // Reset the ID so this effect doesn't run again on recomposition.
            viewModel.setNewSignatureId(null)
        }
    }

    // Confirmation dialog for deletion.
    signatureToDelete?.let { signature ->
        DeleteSignatureDialog(onConfirm = {
            viewModel.deleteSignature(signature, SignatureEventLogger.Screen.PICKER)
        }, onDismiss = { viewModel.setSignatureToDelete(null) })
    }

    // The main layout is a Column, suitable for a bottom sheet.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                // Add padding to respect the system navigation bar.
                .navigationBarsPadding()
                .padding(bottom = dimensionResource(R.dimen.padding_medium)),
    ) {
        // Header
        Text(
            text = stringResource(R.string.picker_title),
            style = MaterialTheme.typography.titleLarge,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = dimensionResource(R.dimen.padding_medium),
                        vertical = dimensionResource(R.dimen.padding_medium),
                    ).wrapContentHeight(align = Alignment.CenterVertically),
        )

        // The main content area with a constrained height.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = dimensionResource(R.dimen.bottom_sheet_max_height)), // Constrain height for scrollable content.
        ) {
            LazyColumn(
                modifier = Modifier.testTag("SignaturePickerList"),
                state = listState, // Assign the state to control scrolling
                contentPadding = PaddingValues(horizontal = dimensionResource(R.dimen.padding_medium)),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.spacing_medium)),
            ) {
                // Add Signature Item
                item {
                    AddSignatureItem(onClick = onAddSignature)
                }

                items(signatures, key = { it.id }) { signature ->
                    PickerSignatureItem(signature = signature, onClick = {
                        val uri = viewModel.getSignatureUri(signature)
                        onSignatureSelected(signature, uri)
                    }, onDelete = {
                        viewModel.setSignatureToDelete(signature)
                    })
                }
            }
        }
    }
}

/**
 * A list item representing a single saved signature within the picker.
 *
 * Displays an icon indicating the item is a signature, the signature's content
 * (either a rendered image or styled text), and a button to delete the signature.
 *
 * @param signature The [Signature] object containing the data to display.
 * @param onClick Callback invoked when the user selects this signature to use it.
 * @param onDelete Callback invoked when the user clicks the delete icon,
 * triggering the deletion confirmation dialog.
 */
@Composable
fun PickerSignatureItem(
    signature: Signature,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(dimensionResource(R.dimen.picker_item_height))
                .clickable(onClick = onClick)
                .padding(vertical = dimensionResource(R.dimen.padding_small)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading Icon (Signature icon)
        Icon(
            painter = painterResource(R.drawable.ic_signature),
            contentDescription = stringResource(R.string.signature_application_label),
            modifier = Modifier.size(dimensionResource(R.dimen.picker_icon_size)),
        )

        Spacer(modifier = Modifier.width(dimensionResource(R.dimen.padding_medium)))

        // Signature Content
        val density = LocalDensity.current
        val fontSize =
            with(density) {
                dimensionResource(R.dimen.signature_text_size).toSp()
            }

        SignatureContent(
            signature = signature,
            modifier = Modifier.weight(1f).height(dimensionResource(R.dimen.picker_image_height)),
            imageModifier = Modifier.width(dimensionResource(R.dimen.picker_image_width)),
            textStyle = TextStyle(fontSize = fontSize),
        )

        // Delete Button
        IconButton(onClick = onDelete) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.delete_signature_content_description),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A clickable list item that prompts the user to create a new signature.
 *
 * @param onClick Callback invoked when the item is clicked, triggering the signature creation flow.
 */
@Composable
fun AddSignatureItem(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = dimensionResource(R.dimen.padding_small)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.AddCircle,
            contentDescription = null,
            modifier = Modifier.size(dimensionResource(R.dimen.picker_icon_size)),
        )
        Spacer(modifier = Modifier.width(dimensionResource(R.dimen.padding_medium)))
        Text(
            text = stringResource(R.string.picker_add_new),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
