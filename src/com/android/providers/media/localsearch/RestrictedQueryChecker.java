/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.providers.media.localsearch;

import android.content.Context;
import android.icu.text.BreakIterator;
import android.os.Trace;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.R;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public final class RestrictedQueryChecker {
    private static final String TAG = "RestrictedQueryChecker";
    // words in the blocklist are separated by this delimiter
    private static final String DELIMITER = ":::";
    private final Set<String> mRestrictedTokens;

    public RestrictedQueryChecker(Context context) throws Exception {
        this.mRestrictedTokens = Collections.unmodifiableSet(loadBlocklist(context));
    }

    /**
     * Evaluates whether a search query contains any restricted terms.
     *
     * @param searchQuery The raw user-provided search text.
     * @return {@code true} if the query contains a restricted term; {@code false} otherwise.
     */
    @VisibleForTesting
    public boolean isQueryRestricted(String searchQuery) {
        try {
            Trace.beginSection("RestrictedQueryChecker.isQueryRestricted");
            if (searchQuery == null || searchQuery.trim().isEmpty()) {
                return false;
            }

            String normalizedQuery = normalize(searchQuery.trim());
            if (mRestrictedTokens.contains(normalizedQuery)) {
                return true;
            }

            BreakIterator wordIterator = BreakIterator.getWordInstance(Locale.getDefault());
            wordIterator.setText(normalizedQuery);
            int startIndex = wordIterator.first();
            int endIndex = wordIterator.next();
            while (endIndex != BreakIterator.DONE) {
                String token = normalizedQuery.substring(startIndex, endIndex);
                if (!token.isEmpty() && mRestrictedTokens.contains(token)) {
                    return true;
                }
                startIndex = endIndex;
                endIndex = wordIterator.next();
            }

            return false;
        } finally {
            Trace.endSection();
        }
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String lowered = input.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(lowered, Normalizer.Form.NFKC);
    }

    private static Set<String> loadBlocklist(Context context) throws Exception {
        if (!SdkLevel.isAtLeastT()) {
            // local search is enabled only for T+ devices. Do not load binary for older versions.
            return new HashSet<>();
        }

        try (GZIPInputStream inputStream = new GZIPInputStream(
                context.getResources().openRawResource(R.raw.search_blocklist))) {
            String decoded = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return Pattern.compile(Pattern.quote(DELIMITER))
                    .splitAsStream(decoded)
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            Log.e(TAG, "Failed to load restricted query binary from resources", e);
            throw e;
        }
    }
}
