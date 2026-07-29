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

package com.android.providers.media.appsearch;

import static androidx.appsearch.app.AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS;
import static androidx.appsearch.app.SearchSpec.RANKING_STRATEGY_RELEVANCE_SCORE;

import static com.android.providers.media.appsearch.AppSearchDbManager.CURRENT_SCHEMA_VERSION;
import static com.android.providers.media.appsearch.AppSearchDbManager.LATEST_SCHEMA_VERSION;
import static com.android.providers.media.appsearch.AppSearchDbManager.SHARED_PREFERENCE_NAME;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.annotation.NonNull;
import androidx.appsearch.annotation.Document;
import androidx.appsearch.app.AppSearchSession;
import androidx.appsearch.app.EmbeddingVector;
import androidx.appsearch.app.GenericDocument;
import androidx.appsearch.app.PutDocumentsRequest;
import androidx.appsearch.app.SearchResult;
import androidx.appsearch.app.SearchResults;
import androidx.appsearch.app.SearchSpec;
import androidx.appsearch.app.SetSchemaRequest;
import androidx.appsearch.platformstorage.PlatformStorage;
import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import com.android.providers.media.IsolatedContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(com.android.providers.media.flags.Flags.FLAG_ENABLE_MEDIA_SEARCH)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
public class AppSearchDbManagerTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private static final int EMBEDDING_DIMENSION = 2;
    private static final String MODEL_SIG = "model_1";
    private IsolatedContext mContext;
    private AppSearchDbManager mAppSearchDbManager;
    private boolean mAppsearchDbConnected;

    @Before
    public void setUp() throws Exception {
        mContext = new IsolatedContext(InstrumentationRegistry.getTargetContext(),
                "AppSearchDbManagerTest", /* asFuseThread */ false);

        try {
            mAppSearchDbManager = new AppSearchDbManager(mContext);
            mAppsearchDbConnected = true;
        } catch (UnsupportedOperationException ex) {
            // Required appSearch features are not supported.
            mAppsearchDbConnected = false;
        }
        assumeTrue(mAppsearchDbConnected);
        deleteAllDocuments();
    }

    @After
    public void tearDown() throws Exception {
        if (mAppsearchDbConnected) {
            deleteAllDocuments();
            mAppSearchDbManager.disconnect();
        }
    }

    @Test
    public void testPutDocuments() throws Exception {
        MediaItem image = getImageDocument();
        MediaItem video = getVideoDocument();
        indexDocuments(image, video);

        SearchSpec searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(AppSearchDbManager.NAMESPACE)
                .build();
        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments("",
                searchSpec)) {
            List<GenericDocument> results = convertSearchResultsToDocuments(searchResults);
            assertEquals(2, results.size());
        }
    }

    @Test
    public void testSearchDocuments() throws Exception {
        MediaItem image = getImageDocument();
        MediaItem video = getVideoDocument();
        indexDocuments(image, video);

        SearchSpec searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(AppSearchDbManager.NAMESPACE)
                .addFilterSchemas(MediaItem.SCHEMA_TYPE)
                .setNumericSearchEnabled(true)
                .build();

        List<GenericDocument> results;
        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments(
                "cat AND mediaType == 1", searchSpec)) {
            results = convertSearchResultsToDocuments(searchResults);
        }

        assertEquals(1, results.size());
        GenericDocument initialDocument = results.get(0);
        assertEquals(100, initialDocument.getPropertyLong(MediaItem.PROPERTY_FILE_ID));
        assertEquals(1, initialDocument.getPropertyLong(MediaItem.PROPERTY_MEDIA_TYPE));
    }

    @Test
    public void testUpdateDocuments() throws Exception {
        MediaItem image = getImageDocument();
        MediaItem video = getVideoDocument();
        indexDocuments(image, video);

        Map<Long, AppSearchDbManager.UpdateSpec> updatesByFileId = new HashMap<>();

        Map<String, Object> imageUpdates = new HashMap<>();
        imageUpdates.put(MediaItem.PROPERTY_LABELS_EXTRACTED, "new cat label");
        updatesByFileId.put(image.getFileId(),
                new AppSearchDbManager.UpdateSpec(imageUpdates));

        Map<String, Object> videoUpdates = new HashMap<>();
        videoUpdates.put(MediaItem.PROPERTY_LOCATION_EXTRACTED, "new york");
        updatesByFileId.put(video.getFileId(),
                new AppSearchDbManager.UpdateSpec(videoUpdates));

        mAppSearchDbManager.updateDocuments(updatesByFileId);

        SearchSpec searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(AppSearchDbManager.NAMESPACE)
                .setNumericSearchEnabled(true)
                .setListFilterQueryLanguageEnabled(true)
                .addFilterSchemas(MediaItem.SCHEMA_TYPE)
                .build();
        List<GenericDocument> imageResults;
        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments(
                MediaItem.PROPERTY_FILE_ID + " == " + image.getFileId(), searchSpec)) {
            imageResults = convertSearchResultsToDocuments(searchResults);
        }
        assertEquals(1, imageResults.size());
        GenericDocument updatedImage = imageResults.get(0);
        assertEquals("new cat label",
                updatedImage.getPropertyString(MediaItem.PROPERTY_LABELS_EXTRACTED));

        List<GenericDocument> videoResults;
        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments(
                MediaItem.PROPERTY_FILE_ID + " == " + video.getFileId(), searchSpec)) {
            videoResults = convertSearchResultsToDocuments(searchResults);
        }
        assertEquals(1, videoResults.size());
        GenericDocument updatedVideo = videoResults.get(0);
        assertEquals("new york",
                updatedVideo.getPropertyString(MediaItem.PROPERTY_LOCATION_EXTRACTED));
    }

    @Test
    public void testDeleteDocuments() throws Exception {
        MediaItem image = getImageDocument();
        MediaItem video = getVideoDocument();
        indexDocuments(image, video);

        deleteAllDocuments();

        SearchSpec searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(AppSearchDbManager.NAMESPACE)
                .build();
        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments("",
                searchSpec)) {
            List<GenericDocument> results = convertSearchResultsToDocuments(searchResults);
            assertEquals(0, results.size());
        }
    }

    @Test
    public void testSchemaVersioning() throws Exception {
        mAppSearchDbManager.disconnect();

        SharedPreferences sharedPreferences = mContext.getSharedPreferences(SHARED_PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        sharedPreferences.edit().clear().apply();

        int initialVersion = sharedPreferences.getInt(CURRENT_SCHEMA_VERSION, 0);
        assertEquals(0, initialVersion);

        mAppSearchDbManager = new AppSearchDbManager(mContext);

        int updatedVersion = sharedPreferences.getInt(CURRENT_SCHEMA_VERSION, 0);
        assertEquals(LATEST_SCHEMA_VERSION, updatedVersion);
    }

    @Document(name = "SchemaMigrationDoc")
    static class SchemaMigrationDocV1 {
        @Document.Id
        String mId;
        @Document.Namespace
        String mNamespace;

        @Document.StringProperty(indexingType = INDEXING_TYPE_EXACT_TERMS)
        String mArtist;

        @Document.StringProperty(indexingType = INDEXING_TYPE_EXACT_TERMS)
        String mAlbum;

        SchemaMigrationDocV1() {}

        SchemaMigrationDocV1(String namespace, String id, String artist, String album) {
            this.mNamespace = namespace;
            this.mId = id;
            this.mArtist = artist;
            this.mAlbum = album;
        }
    }

    @Document(name = "SchemaMigrationDoc")
    static class SchemaMigrationDocV2 {
        @Document.Id
        String mId;
        @Document.Namespace
        String mNamespace;

        @Document.StringProperty(indexingType = INDEXING_TYPE_EXACT_TERMS)
        String mArtist;

        @Document.StringProperty(indexingType = INDEXING_TYPE_EXACT_TERMS)
        String mAlbum;

        @Document.StringProperty(indexingType = INDEXING_TYPE_EXACT_TERMS)
        String mGenre;

        SchemaMigrationDocV2() {}

        SchemaMigrationDocV2(
                String namespace, String id, String artist, String album, String genre) {
            this.mNamespace = namespace;
            this.mId = id;
            this.mArtist = artist;
            this.mAlbum = album;
            this.mGenre = genre;
        }
    }

    @Test
    public void testSchemaMigration() throws Exception {
        final String dbName = "schema_migration_test_db";
        final String namespace = "migration_test_namespace";
        AppSearchSession testSession = PlatformStorage.createSearchSessionAsync(new
                PlatformStorage.SearchContext.Builder(mContext, dbName).build()).get();

        try {
            // Index initial documents with Schema V1
            testSession.setSchemaAsync(new SetSchemaRequest.Builder()
                    .addDocumentClasses(SchemaMigrationDocV1.class)
                    .setForceOverride(true).build()).get();

            List<SchemaMigrationDocV1> docsV1 = new ArrayList<>();
            // 3 docs with "artist1", 2 with "artist2"
            docsV1.add(new SchemaMigrationDocV1(namespace, "doc1", "artist1", "album1"));
            docsV1.add(new SchemaMigrationDocV1(namespace, "doc2", "artist1", "album2"));
            docsV1.add(new SchemaMigrationDocV1(namespace, "doc3", "artist1", "album3"));
            docsV1.add(new SchemaMigrationDocV1(namespace, "doc4", "artist2", "album4"));
            docsV1.add(new SchemaMigrationDocV1(namespace, "doc5", "artist2", "album5"));
            insertDocumentsInSession(testSession, docsV1);

            // Query on the old field, should match 3 documents.
            List<GenericDocument> resultsV1 = searchDocuments(testSession, namespace, "artist1");
            assertEquals("Should match 3 documents with artist 'artist1'", 3, resultsV1.size());

            // Migrate schema and index new documents
            testSession.setSchemaAsync(new SetSchemaRequest.Builder()
                    .addDocumentClasses(SchemaMigrationDocV2.class)
                    .setForceOverride(true).build()).get();

            List<SchemaMigrationDocV2> docsV2 = new ArrayList<>();
            docsV2.add(new SchemaMigrationDocV2(namespace, "doc6", "artist3", "album6", "genre1"));
            docsV2.add(new SchemaMigrationDocV2(namespace, "doc7", "artist4", "album7", "genre1"));
            docsV2.add(new SchemaMigrationDocV2(namespace, "doc8", "artist1", "album8", "genre1"));
            docsV2.add(new SchemaMigrationDocV2(namespace, "doc9", "artist5", "album9", "genre2"));
            docsV2.add(
                    new SchemaMigrationDocV2(namespace, "doc10", "artist6", "album10", "genre2"));
            insertDocumentsInSession(testSession, docsV2);

            // Query on the NEW field "genre". Should only match new documents.
            List<GenericDocument> genreResults = searchDocuments(testSession, namespace, "genre1");
            assertEquals("Should match 3 new documents with genre 'genre1'", 3,
                    genreResults.size());
            for (GenericDocument doc : genreResults) {
                String id = doc.getId();
                assertTrue("Only new documents should appear in new field search",
                        Integer.parseInt(id.substring(3)) > 5);
            }

            // Query on the old field "artist". Should match both old and new documents.
            // 3 from old docs ("artist1") and 1 from new docs ("artist1").
            List<GenericDocument> artistResults =
                    searchDocuments(testSession, namespace, "artist1");
            assertEquals("Should match 4 documents (3 old, 1 new) with artist 'artist1'",
                    4, artistResults.size());

            SearchSpec searchSpec1 = new SearchSpec.Builder()
                    .addFilterNamespaces(namespace)
                    .addFilterDocumentClasses(SchemaMigrationDocV2.class)
                    .build();
            try (SearchResults searchResults = testSession.search("", searchSpec1)) {
                List<GenericDocument> allDocs = convertSearchResultsToDocuments(searchResults);
                assertEquals("Should retrieve all 10 documents when filtering by class", 10,
                        allDocs.size());
            }

            SearchSpec searchSpec2 = new SearchSpec.Builder()
                    .addFilterNamespaces(namespace)
                    .addFilterDocumentClasses(SchemaMigrationDocV1.class)
                    .build();
            try (SearchResults searchResults = testSession.search("", searchSpec2)) {
                List<GenericDocument> allDocs = convertSearchResultsToDocuments(searchResults);
                assertEquals("Should retrieve all 10 documents when filtering by class", 10,
                        allDocs.size());
            }
        } finally {
            // this clears the database
            testSession.setSchemaAsync(
                    new SetSchemaRequest.Builder().setForceOverride(true).build()).get();
            testSession.close();
        }
    }

    private List<GenericDocument> searchDocuments(AppSearchSession session, String namespace,
            String query) throws Exception {
        SearchSpec searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(namespace)
                .build();
        try (SearchResults searchResults = session.search(query, searchSpec)) {
            return convertSearchResultsToDocuments(searchResults);
        }
    }

    private <T> void insertDocumentsInSession(AppSearchSession session, List<T> documents)
            throws Exception {
        PutDocumentsRequest.Builder builder = new PutDocumentsRequest.Builder();
        builder.addDocuments(documents);
        session.putAsync(builder.build()).get();
    }


    @Test
    public void testIndependentSearches() throws Exception {
        List<MediaItem> documents = new ArrayList<>();
        long fileId = 100;
        for (int i = 1; i <= 50; i++) {
            documents.add(createDocumentWithDifferentLabels("cat", i, fileId));
            fileId++;
        }

        for (int i = 1; i <= 50; i++) {
            documents.add(createDocumentWithDifferentLabels("dog", i, fileId));
            fileId++;
        }

        mAppSearchDbManager.insertDocuments(documents);

        SearchSpec searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(AppSearchDbManager.NAMESPACE)
                .setResultCountPerPage(20)
                .setRankingStrategy(RANKING_STRATEGY_RELEVANCE_SCORE)
                .build();

        // query for top 20 results for cat
        List<GenericDocument> firstCatQuery;
        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments("cat",
                searchSpec)) {
            List<SearchResult> results = searchResults.getNextPageAsync().get();
            firstCatQuery = results.stream().map(SearchResult::getGenericDocument).toList();
        }
        assertEquals(20, firstCatQuery.size());

        // again query for top 20 results for cat
        List<GenericDocument> secondCatQuery;
        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments("cat",
                searchSpec)) {
            List<SearchResult> results = searchResults.getNextPageAsync().get();
            secondCatQuery = results.stream().map(SearchResult::getGenericDocument).toList();
        }
        assertEquals(20, secondCatQuery.size());

        // assert we get same 20 results as we got earlier
        List<String> firstCatQueryIds = firstCatQuery.stream().map(GenericDocument::getId)
                .collect(Collectors.toList());
        List<String> secondCatQueryIds = secondCatQuery.stream().map(GenericDocument::getId)
                .collect(Collectors.toList());
        assertEquals(firstCatQueryIds, secondCatQueryIds);

        // query for all cat documents
        searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(AppSearchDbManager.NAMESPACE)
                .setResultCountPerPage(100)
                .build();

        // assert we get all 50 documents
        List<GenericDocument> allCatDocuments;
        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments("cat",
                searchSpec)) {
            List<SearchResult> results = searchResults.getNextPageAsync().get();
            allCatDocuments = results.stream().map(SearchResult::getGenericDocument).toList();
        }
        assertEquals(50, allCatDocuments.size());
    }

    @Test
    public void testBasicSearchOperations() throws Exception {
        MediaItem image = getImageDocument();
        MediaItem video = getVideoDocument();

        try {
            indexDocuments(image, video);

            verifyFullTextSearch();
            verifySemanticSearch();
            verifyHybridSearch();
        } finally {
            deleteAllDocuments();
        }

    }

    private void indexDocuments(MediaItem image, MediaItem video) throws Exception {
        List<MediaItem> documents = new ArrayList<>();
        documents.add(image);
        documents.add(video);
        mAppSearchDbManager.insertDocuments(documents);
    }

    private void verifyHybridSearch() throws Exception {
        List<GenericDocument> results;
        SearchSpec searchSpec;
        SearchSpec.Builder builder;
        builder = new SearchSpec.Builder();
        builder.addFilterNamespaces(AppSearchDbManager.NAMESPACE);
        builder.addFilterSchemas(MediaItem.SCHEMA_TYPE);
        builder.addEmbeddingParameters(getEmbeddingForText("cat"));
        builder.setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY);
        builder.setListFilterQueryLanguageEnabled(true);
        builder.setDefaultEmbeddingSearchMetricType(
                SearchSpec.EMBEDDING_SEARCH_METRIC_TYPE_COSINE);
        searchSpec = builder.build();

        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments("cat OR "
                + "semanticSearch(getEmbeddingParameter(0), 0, 1.0)", searchSpec)) {
            results = convertSearchResultsToDocuments(searchResults);
        }
        // First document should match from full text search.
        // Second document should match from embedding search
        assertEquals(2, results.size());
    }

    private void verifySemanticSearch() throws Exception {
        List<GenericDocument> results;
        SearchSpec.Builder builder;
        SearchSpec searchSpec;
        builder = new SearchSpec.Builder();
        builder.addFilterNamespaces(AppSearchDbManager.NAMESPACE);
        builder.addFilterSchemas(MediaItem.SCHEMA_TYPE);
        builder.addEmbeddingParameters(getEmbeddingForText("cat"));
        builder.setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY);
        builder.setListFilterQueryLanguageEnabled(true);
        builder.setDefaultEmbeddingSearchMetricType(
                SearchSpec.EMBEDDING_SEARCH_METRIC_TYPE_COSINE);
        searchSpec = builder.build();

        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments(
                "semanticSearch(getEmbeddingParameter(0), 0, 1.0)", searchSpec)) {
            results = convertSearchResultsToDocuments(searchResults);
        }
        // First document should not match
        // Second document should match from embedding search
        assertEquals(1, results.size());
    }

    private void verifyFullTextSearch() throws Exception {
        SearchSpec.Builder builder = new SearchSpec.Builder();
        builder.addFilterNamespaces(AppSearchDbManager.NAMESPACE);
        builder.addFilterSchemas(MediaItem.SCHEMA_TYPE);
        SearchSpec searchSpec = builder.build();

        List<GenericDocument> results;
        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments("cat",
                searchSpec)) {
            results = convertSearchResultsToDocuments(searchResults);
        }

        // cat should match the first document. Second document should not match.
        assertEquals(1, results.size());
        GenericDocument document = results.get(0);
        assertEquals(100, document.getPropertyLong(MediaItem.PROPERTY_FILE_ID));
    }

    /** Extracts documents from {@link SearchResults}. */
    public static @NonNull List<GenericDocument> convertSearchResultsToDocuments(
            @NonNull SearchResults searchResults)
            throws Exception {
        List<SearchResult> results = retrieveAllSearchResults(searchResults);
        List<GenericDocument> documents = new ArrayList<>(results.size());
        for (SearchResult result : results) {
            documents.add(result.getGenericDocument());
        }
        return documents;
    }

    /** Extracts all {@link SearchResult} from {@link SearchResults}. */
    public static @NonNull List<SearchResult> retrieveAllSearchResults(
            @NonNull SearchResults searchResults) throws Exception {
        List<SearchResult> page = searchResults.getNextPageAsync().get();
        List<SearchResult> results = new ArrayList<>();
        while (!page.isEmpty()) {
            results.addAll(page);
            page = searchResults.getNextPageAsync().get();
        }
        return results;
    }


    @NonNull
    private MediaItem getImageDocument() {
        MediaItem img = new MediaItem(100, 1, 10000, "external_primary");
        img.setLabelsExtracted("cat basket kitten");
        img.setLocationExtracted("london");
        img.setMetadataExtracted("dog");
        img.setEmbeddings(Collections.singletonList(getEmbeddingForText("foo")));
        return img;
    }

    @NonNull
    private MediaItem createDocumentWithDifferentLabels(String label, int repeat, long fileId) {
        MediaItem img = new MediaItem(fileId, 1, 10000, "external_primary");
        img.setMetadataExtracted("foo ".repeat(repeat) + label);
        img.setEmbeddings(Collections.singletonList(getEmbeddingForText("bar")));
        img.setLabelsExtracted("foo");
        img.setLocationExtracted("foo");
        return img;
    }

    @NonNull
    private MediaItem getVideoDocument() {
        MediaItem video = new MediaItem(101, 2, 20000, "external_primary");
        video.setLabelsExtracted("house");
        video.setLocationExtracted("bengaluru");
        video.setMetadataExtracted("dog bark puppy");
        video.setEmbeddings(Collections.singletonList(getEmbeddingForText("cat")));
        return video;
    }

    private EmbeddingVector getEmbeddingForText(String text) {
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
        return new EmbeddingVector(vector, MODEL_SIG);
    }

    private void deleteAllDocuments() throws Exception {
        SearchSpec searchSpec = new SearchSpec.Builder()
                .addFilterNamespaces(AppSearchDbManager.NAMESPACE)
                .setResultCountPerPage(1000)
                .build();

        List<Long> fileIdsToDelete = new ArrayList<>();
        try (SearchResults searchResults = mAppSearchDbManager.searchDocuments("", searchSpec)) {
            List<SearchResult> results = retrieveAllSearchResults(searchResults);
            for (SearchResult result : results) {
                fileIdsToDelete.add(result.getGenericDocument().getPropertyLong(
                        MediaItem.PROPERTY_FILE_ID));
            }
        }

        if (!fileIdsToDelete.isEmpty()) {
            mAppSearchDbManager.deleteDocumentsByFileIds(fileIdsToDelete);
        }
    }
}
