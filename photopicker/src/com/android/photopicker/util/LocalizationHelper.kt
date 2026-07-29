/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A helper class for localization tasks
 *
 * @property locale The locale to use for localization. Defaults to the device's default locale.
 */
data class LocalizationHelper(private val locale: Locale = Locale.getDefault()) {

    private val numberFormat = NumberFormat.getInstance(locale)

    // Shows "1" instead of "1.00", "1.5" instead of "1.50" and "1.33" for "1.3333"
    // TODO: b/483956548 Handle edge case of size having only the 3rd decimal place as non-zero
    private val decimalFormat =
        NumberFormat.getInstance(locale).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
        }

    /**
     * Returns a localized string representation of the given count.
     *
     * @param count The count to format.
     * @return The localized string representation of the count.
     */
    fun getLocalizedCount(count: Int): String {
        return numberFormat.format(count)
    }

    /**
     * Localized string for Long values for larger integer values.
     *
     * @param count The count as a Long to format.
     * @return The localized string representation of the count (e.g., "1,000,000" or "1.000.000").
     */
    fun getLocalizedCount(count: Long): String {
        return numberFormat.format(count)
    }

    /**
     * Localized string for Double values. Limits output to 2 decimal place.
     *
     * @param count The count as a Double to format.
     * @return The localized string representation of the count (e.g. "40.52").
     */
    fun getLocalizedCount(count: Double): String {
        return decimalFormat.format(count)
    }

    /**
     * Returns a localized date and time formatter.
     *
     * @param dateStyle The style of the date format (e.g., DateFormat.MEDIUM).
     * @param timeStyle The style of the time format (e.g., DateFormat.SHORT).
     * @return A DateFormat instance with the specified styles and locale.
     */
    fun getLocalizedDateTimeFormatter(dateStyle: Int, timeStyle: Int): DateFormat {
        return SimpleDateFormat.getDateTimeInstance(dateStyle, timeStyle, locale)
    }

    /**
     * Formats a raw byte count into a human-readable magnitude and unit.
     *
     * This method uses a 1024-base (binary) conversion:
     * - 1 KB = 1,024 bytes
     * - 1 MB = 1,048,576 bytes
     * - 1 GB = 1,073,741,824 bytes
     *
     * The scaled value is formatted to the correct localized string format (e.g., limiting to 2
     * decimal place).
     *
     * @param bytes The raw size in bytes to be formatted.
     * @return A [Pair] containing the determined [SizeUnit] and the localized string representation
     *   of the scaled value.
     */
    fun getFormattedSize(bytes: Long): Pair<SizeUnit, String> {
        val kb = 1024L
        val mb = kb * 1024L
        val gb = mb * 1024L

        return when {
            bytes >= gb -> {
                val value = bytes.toDouble() / gb
                Pair(SizeUnit.GB, getLocalizedCount(value))
            }
            bytes >= mb -> {
                val value = bytes.toDouble() / mb
                Pair(SizeUnit.MB, getLocalizedCount(value))
            }
            else -> {
                val value = bytes.toDouble() / kb
                Pair(SizeUnit.KB, getLocalizedCount(value))
            }
        }
    }
}

/** Enum defining the supported units for file size formatting. */
enum class SizeUnit {
    KB,
    MB,
    GB,
}

/**
 * Provides a [LocalizationHelper] instance that is remembered and updated when the locale changes.
 */
@Composable
fun rememberLocalizationHelper(): LocalizationHelper {
    val currentLocale = LocalConfiguration.current.locales.get(0) ?: Locale.getDefault()
    return remember(currentLocale) { LocalizationHelper(locale = currentLocale) }
}
