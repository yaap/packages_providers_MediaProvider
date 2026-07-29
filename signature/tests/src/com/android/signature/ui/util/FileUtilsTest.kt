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

package com.android.signature.ui.util

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.test.mock.MockContentProvider
import android.test.mock.MockContentResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class FileUtilsTest {
    private fun createTestContext(
        uri: Uri,
        cursor: Cursor?,
    ): Context {
        val realContext = ApplicationProvider.getApplicationContext<Context>()
        val mockContentResolver = MockContentResolver(realContext)
        val mockProvider =
            object : MockContentProvider(realContext) {
                override fun query(
                    queryUri: Uri,
                    projection: Array<out String>?,
                    selection: String?,
                    selectionArgs: Array<out String>?,
                    sortOrder: String?,
                ): Cursor? = cursor
            }
        mockContentResolver.addProvider(uri.authority, mockProvider)

        return object : ContextWrapper(realContext) {
            override fun getContentResolver(): ContentResolver = mockContentResolver
        }
    }

    @Test
    fun getFileSize_returnsSize() {
        val cursor = Mockito.mock(Cursor::class.java)
        val uri = Uri.parse("content://mock_authority/file")

        val context = createTestContext(uri, cursor)

        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(0)
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getLong(0)).thenReturn(1024L)

        val size = getFileSize(context, uri)

        assertEquals(1024L, size)
    }

    @Test
    fun getFileSize_cursorNull_returnsZero() {
        val uri = Uri.parse("content://mock_authority/file")

        val context = createTestContext(uri, null)

        val size = getFileSize(context, uri)

        assertEquals(0L, size)
    }

    @Test
    fun getFileSize_cursorEmpty_returnsZero() {
        val cursor = Mockito.mock(Cursor::class.java)
        val uri = Uri.parse("content://mock_authority/file")

        val context = createTestContext(uri, cursor)

        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(0)
        whenever(cursor.moveToFirst()).thenReturn(false)

        val size = getFileSize(context, uri)

        assertEquals(0L, size)
    }
}
