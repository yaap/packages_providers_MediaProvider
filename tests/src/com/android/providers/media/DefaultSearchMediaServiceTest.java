/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.providers.media;

import static android.provider.SearchMediaService.EXTRA_NEXT_PAGE_TOKEN;
import static android.provider.SearchMediaService.EXTRA_SEARCH_RESULTS_PAGE_SIZE;
import static android.provider.SearchMediaService.EXTRA_SEARCH_RESULTS_SORT_ORDER;
import static android.provider.SearchMediaService.EXTRA_SORT_BY_RELEVANCE;
import static android.provider.SearchMediaService.EXTRA_SORT_BY_TIME;

import static com.android.providers.media.localsearch.ProcessingUtils.isDefaultSearchMediaServiceSupported;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.ISearchMediaService;
import android.provider.MediaStore;
import android.provider.SearchMediaException;
import android.provider.SearchMediaResult;
import android.provider.SearchMediaService;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appsearch.app.EmbeddingVector;
import androidx.appsearch.app.SearchResult;
import androidx.appsearch.app.SearchResults;
import androidx.appsearch.app.SearchSpec;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.providers.media.appsearch.AppSearchDbManager;
import com.android.providers.media.appsearch.MediaItem;
import com.android.providers.media.localsearch.RestrictedQueryChecker;
import com.android.providers.media.localsearch.SearchMediaExecutor;
import com.android.providers.media.search.TestSearchMediaCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
@RequiresFlagsEnabled({com.android.providers.media.flags.Flags.FLAG_ENABLE_MEDIA_SEARCH,
        com.android.providers.media.flags.Flags.FLAG_ENABLE_MEDIA_PROCESSING})
public class DefaultSearchMediaServiceTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final String TAG = DefaultSearchMediaServiceTest.class.getSimpleName();

    private static final Random sRandom = new Random();
    static final int EMBEDDING_DIMENSION = 2;
    static final String MODEL_SIG = "model_1";

    private final CountDownLatch mServiceLatch = new CountDownLatch(1);

    private ISearchMediaService mSearchMediaService;
    private Context mContext;
    private boolean mIsServiceConnected = false;
    private AppSearchDbManager mAppSearchDbManager;
    private boolean mAppsearchDbConnected;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mContext = new IsolatedContext(context, TAG, /* asFuseThread */ false);

        assumeTrue(isDefaultSearchMediaServiceSupported(mContext));

        try {
            mAppSearchDbManager = new AppSearchDbManager(mContext);
            mAppsearchDbConnected = true;
        } catch (UnsupportedOperationException ex) {
            // Required appSearch features are not supported.
            mAppsearchDbConnected = false;
        }
        assumeTrue(mAppsearchDbConnected);

        Intent intent = new Intent(SearchMediaService.SERVICE_INTERFACE);
        intent.setClassName("com.android.providers.media.tests",
                "com.android.providers.media.TestDefaultSearchMediaService");
        mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        mServiceLatch.await(10, TimeUnit.SECONDS);
        assumeNotNull(mSearchMediaService);
        deleteAllDocuments();
    }

    @After
    public void tearDown() throws Exception {
        if (mAppsearchDbConnected) {
            deleteAllDocuments();
            mAppSearchDbManager.disconnect();
        }
        if (mIsServiceConnected) {
            mContext.unbindService(mServiceConnection);
        }
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            mSearchMediaService = ISearchMediaService.Stub.asInterface(iBinder);
            mServiceLatch.countDown();
            mIsServiceConnected = true;
            Log.d(TAG, "service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mSearchMediaService = null;
            mIsServiceConnected = false;
            Log.d(TAG, "service disconnected");
        }
    };

    @Test
    public void testSearchWhenSortedByTime() throws Exception {
        indexDocumentsForSortByTime();

        Bundle extras = new Bundle();
        extras.putLong(EXTRA_SEARCH_RESULTS_PAGE_SIZE, 20);
        extras.putString(EXTRA_SEARCH_RESULTS_SORT_ORDER, EXTRA_SORT_BY_TIME);

        List<SearchMediaResult> allSearchMediaResults = new ArrayList<>();

        allSearchMediaResults.addAll(makeSearchMediaCall(extras, /* expectedSearchResults */ 20));
        assertNotNull(extras.getString(EXTRA_NEXT_PAGE_TOKEN));

        allSearchMediaResults.addAll(makeSearchMediaCall(extras, /* expectedSearchResults */ 20));
        assertNotNull(extras.getString(EXTRA_NEXT_PAGE_TOKEN));

        allSearchMediaResults.addAll(makeSearchMediaCall(extras, /* expectedSearchResults */ 10));
        assertNull(extras.getString(EXTRA_NEXT_PAGE_TOKEN));

        for (int i = 0; i < allSearchMediaResults.size() - 1; i++) {
            long dateTaken1 = allSearchMediaResults.get(i).getDateTaken();
            long dateTaken2 = allSearchMediaResults.get(i + 1).getDateTaken();
            assertTrue("dateTaken should be in descending order.",
                    dateTaken1 > dateTaken2);
        }
    }

    @Test
    public void testSearchWhenSortedByRelevance() throws Exception {
        indexDocumentsForSortByRelevance();

        Bundle extras = new Bundle();
        extras.putLong(EXTRA_SEARCH_RESULTS_PAGE_SIZE, 20);
        extras.putString(EXTRA_SEARCH_RESULTS_SORT_ORDER, EXTRA_SORT_BY_RELEVANCE);

        List<SearchMediaResult> allSearchMediaResults = new ArrayList<>();

        allSearchMediaResults.addAll(makeSearchMediaCall(extras, /* expectedSearchResults */ 20));
        assertNotNull(extras.getString(EXTRA_NEXT_PAGE_TOKEN));

        allSearchMediaResults.addAll(makeSearchMediaCall(extras, /* expectedSearchResults */ 20));
        assertNotNull(extras.getString(EXTRA_NEXT_PAGE_TOKEN));

        allSearchMediaResults.addAll(makeSearchMediaCall(extras, /* expectedSearchResults */ 10));
        assertNull(extras.getString(EXTRA_NEXT_PAGE_TOKEN));

        for (int i = 0; i < allSearchMediaResults.size() - 1; i++) {
            double score1 = allSearchMediaResults.get(i).getScore();
            double score2 = allSearchMediaResults.get(i + 1).getScore();
            assertTrue("Scores should be in descending order.", score1 >= score2);
        }
    }

    @Test
    public void testDefaultSearchServiceWithRestrictedQuery() throws Exception {
        // 1. Index 5 cats, 5 dogs, and 5 monkeys.
        indexDocumentsForRestrictedQuerySearch();

        // 2. Search for "cat" - Expecting 5 results as cat is not a restricted query.
        List<SearchMediaResult> results;
        results = performSearch(/* searchText */ "cat", /* searchId */ "123");
        assertNotNull(results);
        assertEquals(5, results.size());

        // 3. Search for "dog" - Expecting 5 results as dog is not a restricted query.
        results = performSearch(/* searchText */ "dog", /* searchId */ "456");
        assertNotNull(results);
        assertEquals(5, results.size());

        // 4. Search for "monkey" - Expecting 0 results as monkey is a restricted query.
        results = performSearch(/* searchText */ "monkey", /* searchId */ "789");
        assertNotNull(results);
        assertEquals(0, results.size());
    }

    @Test
    public void testIsQueryRestricted_japaneseTokenization() throws Exception {
        RestrictedQueryChecker checker = new RestrictedQueryChecker(mContext);

        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.JAPANESE);

            // "monkey" is a restricted token.
            // In Japanese, there are typically no spaces between words.
            // BreakIterator should correctly identify "monkey" as a separate token.
            String query = "これはmonkeyです";
            boolean isRestricted = checker.isQueryRestricted(query);

            assertTrue("Query '" + query + "' should be restricted because it contains 'monkey'",
                    isRestricted);
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    public void testIsQueryRestricted_frenchTokenization() throws Exception {
        RestrictedQueryChecker checker = new RestrictedQueryChecker(mContext);

        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRENCH);

            // "stupide" is a restricted token.
            String query = "stupide";
            boolean isRestricted = checker.isQueryRestricted(query);

            assertTrue("Query '" + query + "' should be restricted because it contains 'stupide'",
                    isRestricted);
        } finally {
            Locale.setDefault(originalLocale);
        }
    }


    @Test
    public void testIsQueryRestricted_chineseTokenization() throws Exception {
        RestrictedQueryChecker checker = new RestrictedQueryChecker(mContext);

        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.CHINESE);

            // "白癡" is a restricted token.
            String query = "白癡";
            boolean isRestricted = checker.isQueryRestricted(query);

            assertTrue("Query '" + query + "' should be restricted because it contains '白癡'",
                    isRestricted);
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    public void testDefaultSearchServiceWithTextLengthLimit() throws Exception {
        // 1. Verify search with a valid text length (under 100 characters)
        String validSearchText = "sample search text";
        TestSearchMediaCallback callback = new TestSearchMediaCallback();
        mSearchMediaService.searchMedia(validSearchText, "valid_search_id", new Bundle(), callback);
        callback.await(10, TimeUnit.SECONDS);

        assertNotNull(callback.getSearchMediaResultPage());

        // 2. Verify search with text exceeding MAX_ALLOWED_SEARCH_TEXT_LENGTH
        StringBuilder longSearchText = new StringBuilder();
        for (int i = 0; i < SearchMediaExecutor.MAX_ALLOWED_SEARCH_TEXT_LENGTH + 1; i++) {
            longSearchText.append("a");
        }

        callback = new TestSearchMediaCallback();
        mSearchMediaService.searchMedia(longSearchText.toString(), "error_search_id",
                new Bundle(), callback);
        callback.await(10, TimeUnit.SECONDS);

        assertNotNull(callback.getSearchMediaException());
        assertEquals(SearchMediaException.ERROR_INVALID_ARGUMENTS,
                callback.getSearchMediaException().getErrorCode());
    }

    private List<SearchMediaResult> performSearch(String searchText, String searchId)
            throws Exception {
        TestSearchMediaCallback callback = new TestSearchMediaCallback();
        mSearchMediaService.searchMedia(searchText, searchId, new Bundle(), callback);
        callback.await(10, TimeUnit.SECONDS);
        return callback.getSearchMediaResultPage().getSearchResults();
    }

    private void indexDocumentsForRestrictedQuerySearch() throws Exception {
        List<MediaItem> documents = new ArrayList<>();
        long fileId = 500;
        for (int i = 0; i < 5; i++) {
            documents.add(createMediaItem("cat", 1, fileId++));
        }
        for (int i = 0; i < 5; i++) {
            documents.add(createMediaItem("dog", 1, fileId++));
        }
        for (int i = 0; i < 5; i++) {
            documents.add(createMediaItem("monkey", 1, fileId++));
        }
        mAppSearchDbManager.insertDocuments(documents);
    }

    private List<SearchMediaResult> makeSearchMediaCall(Bundle extras, int expectedSearchResults)
            throws Exception {
        TestSearchMediaCallback callback = new TestSearchMediaCallback();
        String searchId = String.valueOf(SystemClock.elapsedRealtime()); // any unique searchId
        mSearchMediaService.searchMedia("cat", searchId, extras, callback);
        callback.await(10, TimeUnit.SECONDS);

        List<SearchMediaResult> searchMediaResults =
                callback.getSearchMediaResultPage().getSearchResults();
        assertNotNull(searchMediaResults);
        assertEquals(expectedSearchResults, searchMediaResults.size());
        String pageToken = callback.getSearchMediaResultPage().getExtras().getString(
                EXTRA_NEXT_PAGE_TOKEN);
        extras.putString(EXTRA_NEXT_PAGE_TOKEN, pageToken);
        return searchMediaResults;
    }

    private void indexDocumentsForSortByTime() throws Exception {
        List<MediaItem> documents = new ArrayList<>();
        long fileId = 100;
        for (int i = 1; i <= 50; i++) {
            documents.add(createMediaItem("cat", i, fileId++));
        }

        for (int i = 51; i <= 100; i++) {
            documents.add(createMediaItem("dog", i, fileId++));
        }

        mAppSearchDbManager.insertDocuments(documents);
    }

    private void indexDocumentsForSortByRelevance() throws Exception {
        List<MediaItem> documents = new ArrayList<>();
        long fileId = 200;

        // Both semantic and full-text search should match for "cat".
        StringBuilder metadata = new StringBuilder("a photo of a happy cat");
        List<EmbeddingVector> embeddingVectorList = new ArrayList<>();
        embeddingVectorList.add(getEmbeddingForText("cat"));
        for (int i = 0; i < 20; i++) {
            documents.add(createRelevanceMediaItem(fileId++, metadata.toString(),
                    new ArrayList<>(embeddingVectorList)));
            metadata.append(" cat");
        }

        // Only full-text search should match for "cat".
        metadata = new StringBuilder("cat");
        embeddingVectorList.clear();
        embeddingVectorList.add(getEmbeddingForText("unrelated"));
        for (int i = 0; i < 20; i++) {
            documents.add(createRelevanceMediaItem(fileId++, metadata.toString(),
                    new ArrayList<>(embeddingVectorList)));
            metadata.append(" bar");
        }

        // Only semantic search should match for "cat".
        metadata = new StringBuilder("a flower picture");
        embeddingVectorList.clear();
        embeddingVectorList.add(getEmbeddingForText("cat"));
        for (int i = 0; i < 10; i++) {
            documents.add(createRelevanceMediaItem(fileId++, metadata.toString(),
                    new ArrayList<>(embeddingVectorList)));
            embeddingVectorList.add(getEmbeddingForText("foo"));
        }

        // No match for "cat".
        metadata = new StringBuilder("dog");
        embeddingVectorList.clear();
        embeddingVectorList.add(getEmbeddingForText("unrelated"));
        for (int i = 0; i < 50; i++) {
            documents.add(createRelevanceMediaItem(fileId++, metadata.toString(),
                    new ArrayList<>(embeddingVectorList)));
            metadata.append(" bar");
        }

        mAppSearchDbManager.insertDocuments(documents);
    }

    @NonNull
    private MediaItem createRelevanceMediaItem(long fileId, String metadata,
            List<EmbeddingVector> embeddingVectors) {
        MediaItem item = new MediaItem(fileId, 1, 20000 + fileId, MediaStore.VOLUME_EXTERNAL);
        item.setMetadataExtracted(metadata);
        item.setEmbeddings(embeddingVectors);
        return item;
    }

    @NonNull
    private MediaItem createMediaItem(String label, int repeat, long fileId) {
        MediaItem item = new MediaItem(fileId, 1, 10000 + fileId, MediaStore.VOLUME_EXTERNAL);
        item.setMetadataExtracted("foo ".repeat(repeat) + label);

        List<EmbeddingVector> embeddings = new ArrayList<>();
        embeddings.add(getEmbeddingForText("bar"));
        embeddings.add(getEmbeddingForText("foo"));
        item.setEmbeddings(embeddings);

        return item;
    }

    static EmbeddingVector getEmbeddingForText(String text) {
        float[] vector = new float[EMBEDDING_DIMENSION];
        if (text.contains("cat")) {
            vector[0] = 1.0f; vector[1] = 0.0f;
        } else if (text.contains("dog")) {
            vector[0] = 0.0f; vector[1] = 1.0f;
        } else if (text.contains("house")) {
            vector[0] = 0.8f; vector[1] = 0.1f;
        } else { // Unrelated
            vector[0] = -1.0f; vector[1] = -1.0f;
        }

        // Add some deviation to each vector so that we get distinct embeddings.
        float deviation = (sRandom.nextFloat() * 0.1f) - 0.05f;
        vector[0] += deviation;
        deviation = (sRandom.nextFloat() * 0.1f) - 0.05f;
        vector[1] += deviation;

        return new EmbeddingVector(vector, MODEL_SIG);
    }

    private void deleteAllDocuments() throws Exception {
        SearchSpec searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(AppSearchDbManager.NAMESPACE)
                .build();
        mAppSearchDbManager.deleteDocuments("", searchSpec);
    }

    private static @NonNull List<SearchResult> retrieveAllSearchResults(
            @NonNull SearchResults searchResults) throws Exception {
        List<SearchResult> page = searchResults.getNextPageAsync().get();
        List<SearchResult> results = new ArrayList<>();
        while (!page.isEmpty()) {
            results.addAll(page);
            page = searchResults.getNextPageAsync().get();
        }
        return results;
    }
}
