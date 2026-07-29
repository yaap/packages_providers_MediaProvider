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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.android.signature.R
import com.android.signature.data.SignatureFont
import com.android.signature.data.SignatureFonts
import com.android.signature.ui.SignatureViewModel

/**
 * Composable for the "Type" tab in the Create Signature screen.
 * Allows the user to type their name and select a font style.
 *
 * @param viewModel The [SignatureViewModel] to manage state.
 * @param onSave Callback invoked when the user selects a font to save.
 */
@Composable
internal fun TypeTab(
    viewModel: SignatureViewModel,
    onSave: (String, SignatureFont) -> Unit
) {
    val text by viewModel.typedText.collectAsState()
    val selectedFont by viewModel.selectedFont.collectAsState()
    val density = LocalDensity.current

    Column(
        Modifier.fillMaxSize().padding(dimensionResource(R.dimen.padding_medium)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                viewModel.setTypedText(it)
            },
            placeholder = { Text(stringResource(R.string.enter_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(dimensionResource(R.dimen.text_field_corner_radius))
        )

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.padding_medium)))

        val previewText = if (text.isNotBlank()) text else stringResource(R.string.signature_preview_default)

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_small)),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_small)),
            contentPadding = PaddingValues(vertical = dimensionResource(R.dimen.padding_small))
        ) {
            items(SignatureFonts.defaultFonts) { font ->
                val isSelected = selectedFont == font

                val fontSize = with(density) {
                    dimensionResource(font.fontSizeResId).toSp()
                }
                val lineHeight = with(density) {
                    dimensionResource(font.lineHeightResId).toSp()
                }

                Text(
                    text = previewText,
                    style = TextStyle(
                        fontFamily = font.composeFontFamily,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.fillMaxWidth()
                        .background(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(dimensionResource(R.dimen.corner_radius))
                        )
                        // Clicking an item saves it directly if text is not blank
                        .clickable {
                            viewModel.setSelectedFont(font)
                            if (text.isNotBlank()) {
                                onSave(text, font)
                            }
                        }
                        .padding(
                            vertical = dimensionResource(R.dimen.padding_medium),
                            horizontal = dimensionResource(R.dimen.padding_small)
                        )
                )
            }
        }
    }
}
