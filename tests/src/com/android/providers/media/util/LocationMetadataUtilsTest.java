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

package com.android.providers.media.util;

import static android.provider.MediaStore.Images.ImageColumns.LATITUDE;
import static android.provider.MediaStore.Images.ImageColumns.LONGITUDE;

import static com.android.providers.media.scan.MediaScannerTest.stage;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.os.Environment;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.MediaStore.Files.FileColumns;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.providers.media.R;
import com.android.providers.media.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

@RunWith(AndroidJUnit4.class)
public class LocationMetadataUtilsTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private File mTestDir;
    private static final String TEST_IMAGE_WITH_LOCATION = "test_image_with_location.jpg";
    private static final String TEST_IMAGE_WITHOUT_LOCATION = "test_image_without_location.jpg";
    private static final double TEST_LATITUDE = 37.7749;
    private static final double TEST_LONGITUDE = -122.4194;

    private SQLiteDatabase mDb;
    private static final String DATABASE_FILE = "test_location_metadata_utils.db";
    private File mTestImageWithLocation;
    private File mTestImageWithoutLocation;
    private Context mContext;

    @Before
    public void setUp() throws IOException {
        mContext = InstrumentationRegistry.getTargetContext();

        File downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        mTestDir = new File(downloadsDir, "test_" + System.nanoTime());
        mTestDir.mkdirs();
        FileUtils.deleteContents(mTestDir);

        mTestImageWithLocation = createImageFileWithExif(TEST_IMAGE_WITH_LOCATION, TEST_LATITUDE,
                TEST_LONGITUDE);
        mTestImageWithoutLocation = createImageFileWithExif(TEST_IMAGE_WITHOUT_LOCATION, null,
                null);

        mContext.deleteDatabase(DATABASE_FILE);
        mDb = Objects.requireNonNull(
                mContext.openOrCreateDatabase(DATABASE_FILE, Context.MODE_PRIVATE, null));
        mDb.execSQL("DROP TABLE IF EXISTS files;");
        mDb.execSQL("CREATE TABLE files (" + FileColumns._ID + " INTEGER PRIMARY KEY, "
                + FileColumns.DATA + " TEXT, " + FileColumns.MEDIA_TYPE + " INTEGER, "
                + LATITUDE + " DOUBLE, " + LONGITUDE + " DOUBLE )");
    }

    @After
    public void tearDown() {
        if (mTestDir != null) {
            FileUtils.deleteContents(mTestDir);
        }

        if (mDb != null) {
            mDb.close();
        }
        mContext.deleteDatabase(DATABASE_FILE);
    }

    @Test
    @EnableFlags(Flags.FLAG_INDEX_MEDIA_LATITUDE_LONGITUDE)
    public void testUpdateLocationMetadataColumnsInSingleBatch() {
        insertFile(1, mTestImageWithLocation.getAbsolutePath(), /* mediaType */ 1, /* latitude */
                null, /* longitude */ null);
        insertFile(2, mTestImageWithoutLocation.getAbsolutePath(), /* mediaType */ 1, /* latitude */
                null, /* longitude */ null);
        insertFile(3, /* path */ "/dev/null", /* mediaType */ 1, /* latitude */ 1.0, /* longitude */
                1.0); // Already has location

        long lastRowId = 3;
        long lastRowUpdated = LocationMetadataUtils.updateLocationMetadataColumns(mDb, 0, lastRowId,
                new CancellationSignal());

        // updateLocationMetadataColumns will return the last row ID updated with location metadata
        // in a single batch.
        assertEquals(2, lastRowUpdated);
        assertLocation(/* rowId */ 1, TEST_LATITUDE, TEST_LONGITUDE);
        assertLocation(/* rowId */ 2, /* expectedLatitude */ 0, /* expectedLongitude */ 0);
        assertLocation(/* rowId */ 3, /* expectedLatitude */ 1.0, /* expectedLongitude */ 1.0);
    }

    private File createImageFileWithExif(String filename, Double latitude, Double longitude)
            throws IOException {
        File file = new File(mTestDir, filename);
        stage(R.raw.test_image_no_location_exif, file);

        if (latitude != null && longitude != null) {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            assertThat(exif.getLatLong()).isNull();
            exif.setLatLong(latitude, longitude);
            exif.saveAttributes();
        }

        return file;
    }

    private void insertFile(long id, String path, int mediaType, Double latitude,
            Double longitude) {
        ContentValues values = new ContentValues();
        values.put(FileColumns._ID, id);
        values.put(FileColumns.DATA, path);
        values.put(FileColumns.MEDIA_TYPE, mediaType);
        if (latitude != null) {
            values.put(LATITUDE, latitude);
        }
        if (longitude != null) {
            values.put(LONGITUDE, longitude);
        }
        mDb.insert("files", null, values);
    }

    private void assertLocation(long id, double expectedLatitude, double expectedLongitude) {
        try (Cursor c = mDb.query("files", new String[]{LATITUDE, LONGITUDE},
                FileColumns._ID + " = " + id, null, null, null, null)) {
            assertThat(c.moveToFirst()).isTrue();
            assertEquals(c.getDouble(0), expectedLatitude, 0.0001);
            assertEquals(c.getDouble(1), expectedLongitude, 0.0001);
        }
    }
}
