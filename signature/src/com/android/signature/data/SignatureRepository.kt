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

import android.net.Uri
import com.android.signature.provider.SignatureProvider
import kotlinx.coroutines.flow.Flow

/**
 * Repository that provides a simplified API for data operations on signatures.
 * It abstracts the data sources from the rest of the app.
 *
 * @param signatureDao The Data Access Object for the signatures table.
 */
class SignatureRepository(val signatureDao: SignatureDao) {

    /**
     * Retrieves all signatures from the data source, ordered by creation date.
     *
     * @return A Flow emitting a list of all signatures.
     */
    fun getAllSignatures(): Flow<List<Signature>> = signatureDao.getAllSignatures()

    /**
     * Returns the number of signatures in the database.
     */
    suspend fun getSignatureCount(): Int = signatureDao.getSignatureCount()

    /**
     * Saves a new signature to the database.
     *
     * @param signature The signature object to save.
     */
    suspend fun saveSignature(signature: Signature) {
        signatureDao.insertSignature(signature)
    }

    /**
     * Constructs a content URI for a given signature that can be shared with other apps.
     * This URI is handled by the [SignatureProvider].
     *
     * @param signature The signature for which to create a URI.
     * @return The content URI for the given signature.
     */
    fun getSignatureUri(signature: Signature): Uri {
        return Uri.withAppendedPath(SignatureProvider.CONTENT_URI, signature.id)
    }

    /**
     * Deletes a signature from the database.
     *
     * @param signature The signature object to delete.
     */
    suspend fun deleteSignature(signature: Signature) {
        signatureDao.deleteSignature(signature)
    }
}
