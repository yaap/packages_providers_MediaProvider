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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.android.signature.R
import com.android.signature.ui.util.getFileSize
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_IMAGE_SIZE_MB = 2
private const val MAX_IMAGE_SIZE_BYTES = MAX_IMAGE_SIZE_MB * 1024 * 1024L
private const val IMAGE_MIME_TYPE = "image/*"

/**
 * Composable for the "Upload" tab in the Create Signature screen.
 * Allows the user to select an image from the gallery to use as a signature.
 *
 * @param bitmap The current bitmap selected by the user.
 * @param onBitmapChanged Callback invoked when a new bitmap is selected or cleared.
 * @param onSave Callback invoked when the user saves the uploaded image.
 * @param onCancel Callback invoked when the user cancels.
 * @param onShowError Callback invoked when an error occurs (e.g. image loading failed).
 */
@Composable
internal fun UploadTab(
    bitmap: Bitmap?,
    onBitmapChanged: (Bitmap?) -> Unit,
    onSave: (Bitmap) -> Unit,
    onCancel: () -> Unit,
    onShowError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val errorSizeLimit = stringResource(R.string.error_image_size_limit, MAX_IMAGE_SIZE_MB)
    val errorLoad = stringResource(R.string.error_image_load)

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri ->
            uri?.let {
                scope.launch {
                    // Check file size (limit to 2MB)
                    val size = withContext(Dispatchers.IO) { getFileSize(context, it) }
                    if (size > MAX_IMAGE_SIZE_BYTES) {
                        onShowError(errorSizeLimit)
                        return@launch
                    }

                    try {
                        val loadedBitmap =
                            withContext(Dispatchers.IO) {
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                                    context.contentResolver.openInputStream(it)?.use { stream ->
                                        BitmapFactory.decodeStream(stream)
                                    }
                                } else {
                                    val source = ImageDecoder.createSource(context.contentResolver, it)
                                    ImageDecoder.decodeBitmap(source)
                                }
                            }
                        onBitmapChanged(loadedBitmap)
                    } catch (e: IOException) {
                        onShowError(errorLoad)
                    } catch (e: SecurityException) {
                        onShowError(errorLoad)
                    }
                }
            }
        }

    Column(
        Modifier.fillMaxSize().padding(dimensionResource(R.dimen.padding_medium)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UploadPreview(
            bitmap = bitmap,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.padding_medium)))

        UploadActions(
            hasImage = bitmap != null,
            onSelectImage = { launcher.launch(IMAGE_MIME_TYPE) },
            onCancel = onCancel,
            onSave = { bitmap?.let { onSave(it) } },
        )
    }
}

@Composable
private fun UploadPreview(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .padding(dimensionResource(R.dimen.padding_medium))
                .background(
                    Color.White,
                    RoundedCornerShape(dimensionResource(R.dimen.corner_radius)),
                ).clip(RoundedCornerShape(dimensionResource(R.dimen.corner_radius))),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription =
                    stringResource(
                        R.string.uploaded_signature_content_description,
                    ),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } ?: Text(
            stringResource(R.string.no_image_selected),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun UploadActions(
    hasImage: Boolean,
    onSelectImage: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End, // Align buttons to the end
    ) {
        if (!hasImage) {
            // When no image, show Select Image and Cancel
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.padding(end = dimensionResource(R.dimen.padding_small)),
                border =
                    BorderStroke(
                        dimensionResource(R.dimen.border_width),
                        MaterialTheme.colorScheme.primary,
                    ),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
            ) {
                Text(stringResource(R.string.cancel_action))
            }
            Button(onClick = onSelectImage) {
                Text(stringResource(R.string.select_image))
            }
        } else {
            // When image selected: Back, Cancel, Add
            OutlinedButton(
                onClick = onSelectImage,
                modifier = Modifier.padding(end = dimensionResource(R.dimen.padding_small)),
                border =
                    BorderStroke(
                        dimensionResource(R.dimen.border_width),
                        MaterialTheme.colorScheme.primary,
                    ),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
            ) {
                Text(stringResource(R.string.back_action))
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.padding(end = dimensionResource(R.dimen.padding_small)),
                border =
                    BorderStroke(
                        dimensionResource(R.dimen.border_width),
                        MaterialTheme.colorScheme.primary,
                    ),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
            ) {
                Text(stringResource(R.string.cancel_action))
            }
            Button(onClick = onSave) {
                Text(stringResource(R.string.add_action))
            }
        }
    }
}
