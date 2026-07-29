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

package com.android.signature.data

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import com.android.signature.R

/**
 * Data class to hold font information for the "Type" tab.
 */
data class SignatureFont(
    val name: String,
    val composeFontFamily: FontFamily,
    val androidTypeface: Typeface,
    val fontSizeResId: Int = R.dimen.signature_font_size_default,
    val lineHeightResId: Int = R.dimen.signature_line_height_default
)

/**
 * A predefined list of fonts for the user to choose from.
 */
internal object SignatureFonts {
    val defaultFonts = listOf(
        SignatureFont(
            "Cursive",
            FontFamily.Cursive,
            Typeface.create("cursive", Typeface.NORMAL),
            fontSizeResId = R.dimen.signature_font_size_default,
            lineHeightResId = R.dimen.signature_line_height_default
        ), SignatureFont(
            "Sans Serif",
            FontFamily.SansSerif,
            Typeface.SANS_SERIF,
            fontSizeResId = R.dimen.signature_font_size_medium,
            lineHeightResId = R.dimen.signature_line_height_default
        ), SignatureFont(
            "Serif",
            FontFamily.Serif,
            Typeface.SERIF,
            fontSizeResId = R.dimen.signature_font_size_small,
            lineHeightResId = R.dimen.signature_line_height_default
        ), SignatureFont(
            "Monospace",
            FontFamily.Monospace,
            Typeface.MONOSPACE,
            fontSizeResId = R.dimen.signature_font_size_small,
            lineHeightResId = R.dimen.signature_line_height_default
        )
    )
}
