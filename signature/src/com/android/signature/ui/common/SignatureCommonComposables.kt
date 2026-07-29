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

package com.android.signature.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.android.signature.R
import com.android.signature.data.Signature
import com.android.signature.data.composeFontFamily
import com.android.signature.ui.theme.SignatureTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A composable that displays an empty state message when no signatures are available.
 *
 * @param modifier Modifier to be applied to the layout.
 * @param text The text to display. Defaults to "No signatures saved."
 */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier, text: String = stringResource(R.string.no_signatures_saved)
) {
    Box(
        modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) {
        Text(
            text = text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * A dialog that asks for confirmation before deleting a signature.
 *
 * @param onConfirm Callback invoked when the user confirms deletion.
 * @param onDismiss Callback invoked when the user dismisses the dialog.
 */
@Composable
fun DeleteSignatureDialog(
    onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_signature_title)) },
        text = { Text(stringResource(R.string.delete_signature_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_action))
            }
        })
}

@Preview
@Composable
fun PreviewEmptyState() {
    SignatureTheme {
        EmptyState()
    }
}

@Preview
@Composable
fun PreviewDeleteSignatureDialog() {
    SignatureTheme {
        DeleteSignatureDialog(onConfirm = {}, onDismiss = {})
    }
}

/**
 * Reusable composable that renders a signature's content based on its type.
 *
 * It gracefully handles rendering drawn, uploaded, and typed signatures.
 * Additionally, it automatically adapts the signature's color to the current
 * system theme (light/dark mode) so that black signatures remain visible
 * on dark backgrounds.
 *
 * @param signature The [Signature] object containing the data to display.
 * @param modifier Modifier to be applied to the outermost container.
 * @param imageModifier Modifier to be applied specifically to the rendered image
 *                      (used for drawn or uploaded signatures).
 * @param textStyle The [TextStyle] to be applied to the rendered text
 *                  (used for typed signatures).
 */
@Composable
fun SignatureContent(
    signature: Signature,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier,
    textStyle: TextStyle
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        when (signature.type) {
            Signature.TYPE_DRAWN, Signature.TYPE_UPLOADED -> {
                signature.imageData?.let { imageData ->
                    val bitmapState = produceState<ImageBitmap?>(initialValue = null, imageData) {
                        value = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                                ?.asImageBitmap()
                        }
                    }

                    bitmapState.value?.let { bitmap ->
                        // Only apply the dynamic tint if the signature was drawn by the user.
                        // Uploaded signatures might be colored photos, so they should not be tinted.
                        val colorFilter = if (signature.type == Signature.TYPE_DRAWN) {
                            ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                        } else {
                            null
                        }

                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.drawn_signature_content_description),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = imageModifier,
                            colorFilter = colorFilter
                        )
                    }
                }
            }

            Signature.TYPE_TYPED -> {
                signature.textData?.let { text ->
                    Text(
                        text = text,
                        style = textStyle,
                        fontFamily = signature.composeFontFamily,
                        // Ensure text color also adapts to light/dark themes
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
