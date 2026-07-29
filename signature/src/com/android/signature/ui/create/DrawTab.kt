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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import com.android.signature.R
import com.android.signature.ui.SignatureViewModel
import com.android.signature.ui.util.createBitmapFromPaths

/**
 * Composable for the "Draw" tab in the Create Signature screen.
 * Allows the user to draw a signature on a canvas.
 *
 * @param viewModel The [SignatureViewModel] to manage state.
 * @param onSave Callback invoked when the user saves the drawing.
 * @param onCancel Callback invoked when the user cancels.
 */
@Composable
internal fun DrawTab(
    viewModel: SignatureViewModel,
    onSave: (Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    val drawingPaths by viewModel.drawingPaths.collectAsState()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var isUserDrawing by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val strokeWidth =
        with(density) {
            dimensionResource(R.dimen.signature_stroke_width).toPx()
        }
    val canvasPaddingPx =
        with(density) { dimensionResource(R.dimen.signature_canvas_padding).toPx() }

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(dimensionResource(R.dimen.padding_medium))
                    .background(
                        Color.White,
                        RoundedCornerShape(dimensionResource(R.dimen.corner_radius)),
                    ).clip(RoundedCornerShape(dimensionResource(R.dimen.corner_radius)))
                    .onSizeChanged { size: IntSize ->
                        canvasSize = size
                    }.testTag("DrawingCanvas"),
        ) {
            // Background elements
            // Text at Top
            if (drawingPaths.isEmpty() && !isUserDrawing) {
                Text(
                    text = stringResource(R.string.signature_draw_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = dimensionResource(R.dimen.signature_draw_text_padding_top)),
                )
            }

            // X and Line at Bottom (Always visible)
            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            ) {
                Text(
                    text = stringResource(R.string.signature_x_mark),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = dimensionResource(R.dimen.padding_medium)),
                )
                HorizontalDivider(
                    modifier =
                        Modifier.fillMaxWidth().padding(
                            bottom = dimensionResource(R.dimen.signature_draw_divider_padding_bottom),
                            start = dimensionResource(R.dimen.padding_medium),
                            end = dimensionResource(R.dimen.padding_medium),
                        ),
                    color = Color.Black,
                )
            }

            DrawingCanvas(
                paths = drawingPaths,
                onDragStart = { isUserDrawing = true },
                onDragEnd = { pathState ->
                    isUserDrawing = false
                    pathState?.let {
                        viewModel.setDrawingPaths(drawingPaths + it)
                    }
                },
                strokeWidth = strokeWidth,
                color = Color.Black,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(dimensionResource(R.dimen.padding_medium)),
            horizontalArrangement = Arrangement.End, // Align buttons to the end
        ) {
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
            Button(
                onClick = {
                    if (canvasSize != IntSize.Zero) {
                        val bitmap =
                            createBitmapFromPaths(
                                paths = drawingPaths,
                                size = canvasSize,
                                density = density,
                                layoutDirection = layoutDirection,
                                padding = canvasPaddingPx,
                            )
                        onSave(bitmap)
                    }
                },
                enabled = drawingPaths.isNotEmpty(),
            ) {
                Text(stringResource(R.string.add_action))
            }
        }
    }
}
