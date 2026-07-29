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

package com.android.signature.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.signature.data.Signature
import com.android.signature.data.SignatureDao
import com.android.signature.data.SignatureRepository
import com.android.signature.flags.Flags
import com.android.signature.logging.SignatureEventLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A [ContentProvider] that exposes signature images to other applications.
 *
 * This provider allows external apps to read signature data (as PNG images) via content URIs.
 * It supports `openFile` to stream the image data.
 *
 * The authority for this provider is `com.android.signature.provider`.
 * Supported URIs:
 * - `content://com.android.signature.provider/signatures/{signatureId}`: Access a specific signature by ID.
 */
class SignatureProvider : ContentProvider() {
    @VisibleForTesting
    var signatureDao: SignatureDao? = null

    @VisibleForTesting
    var eventLogger: SignatureEventLogger? = null

    private val lazySignatureDao: SignatureDao by lazy {
        signatureDao ?: run {
            val entryPoint =
                EntryPointAccessors.fromApplication(
                    context!!,
                    SignatureProviderEntryPoint::class.java,
                )
            entryPoint.signatureRepository().signatureDao
        }
    }

    private val lazyEventLogger: SignatureEventLogger by lazy {
        eventLogger ?: run {
            val entryPoint =
                EntryPointAccessors.fromApplication(
                    context!!,
                    SignatureProviderEntryPoint::class.java,
                )
            entryPoint.signatureEventLogger()
        }
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SignatureProviderEntryPoint {
        fun signatureRepository(): SignatureRepository

        fun signatureEventLogger(): SignatureEventLogger
    }

    override fun onCreate(): Boolean {
        if (!Flags.enableSignature()) {
            Log.w(TAG, "SignatureProvider is disabled by flag.")
            return false // Provider is not available
        }
        return true
    }

    override fun shutdown() {
        super.shutdown()
        scope.cancel()
    }

    override fun getType(uri: Uri): String? {
        if (!Flags.enableSignature()) {
            Log.w(TAG, "getType called when SignatureProvider is disabled")
            return null
        }
        val signatureId = getSignatureId(uri) ?: return null
        // Verify signature exists
        val signature = runBlocking { lazySignatureDao.getSignatureById(signatureId) }
        return if (signature != null) "image/png" else null
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val start = System.currentTimeMillis()
        if (!Flags.enableSignature()) {
            Log.e(TAG, "openFile called when SignatureProvider is disabled")
            throw FileNotFoundException("Provider not available")
        }
        if (mode != "r") {
            throw IllegalArgumentException("Only read mode is supported")
        }

        val signatureId = getSignatureId(uri) ?: throw FileNotFoundException("Invalid URI")
        val (pipeRead, pipeWrite) = ParcelFileDescriptor.createReliablePipe()

        scope.launch {
            try {
                // Fetch and validate data BEFORE opening the AutoCloseOutputStream.
                // If this throws, the pipe remains open, allowing closeWithError to work.
                val signature =
                    lazySignatureDao.getSignatureById(signatureId) ?: run {
                        lazyEventLogger.logSignatureAppError(
                            SignatureEventLogger.AppErrorType.DB_READ_FAILED,
                            Signature.TYPE_UNKNOWN,
                        )
                        throw IOException("Signature not found")
                    }

                val imageData =
                    signature.imageData ?: throw IOException("Signature image data is missing")

                // Write data and let the 'use' block close the stream normally upon success.
                ParcelFileDescriptor.AutoCloseOutputStream(pipeWrite).use { outputStream ->
                    outputStream.write(imageData)
                }

                val duration = System.currentTimeMillis() - start
                lazyEventLogger.logSignatureProviderOpenDuration(duration)
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to pipe", e)
                // Try to close the pipe with an error, and log if that fails too.
                runCatching { pipeWrite.closeWithError(e.message) }.onFailure { closeException ->
                    Log.e(TAG, "Error closing pipe with error", closeException)
                }
            }
        }
        return pipeRead
    }

    private fun getSignatureId(uri: Uri): String? =
        if (uriMatcher.match(uri) == SIGNATURE_ID) {
            uri.lastPathSegment
        } else {
            null
        }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (!Flags.enableSignature()) {
            Log.w(TAG, "query called when SignatureProvider is disabled")
            return null
        }
        // Not implemented
        return null
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? {
        if (!Flags.enableSignature()) {
            Log.w(TAG, "insert called when SignatureProvider is disabled")
            return null
        }
        // Not implemented
        return null
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        if (!Flags.enableSignature()) {
            Log.w(TAG, "delete called when SignatureProvider is disabled")
            return 0
        }
        // Not implemented
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        if (!Flags.enableSignature()) {
            Log.w(TAG, "update called when SignatureProvider is disabled")
            return 0
        }
        // Not implemented
        return 0
    }

    companion object {
        private const val TAG = "SignatureProvider"
        private const val AUTHORITY = "com.android.signature.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/signatures")

        private const val SIGNATURE_ID = 1
        private val uriMatcher =
            UriMatcher(UriMatcher.NO_MATCH).apply {
                addURI(AUTHORITY, "signatures/*", SIGNATURE_ID)
            }
    }
}
