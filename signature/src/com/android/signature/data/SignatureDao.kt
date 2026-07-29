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

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SignatureDao {
    @Query("SELECT * FROM signatures ORDER BY createdAt DESC")
    fun getAllSignatures(): Flow<List<Signature>>

    // This synchronous method is needed for the ContentProvider.
    @Query("SELECT * FROM signatures WHERE id = :id")
    fun getSignatureById(id: Long): Signature?

    @Query("SELECT * FROM signatures WHERE id = :id")
    suspend fun getSignatureById(id: String): Signature?

    @Query("SELECT COUNT(*) FROM signatures")
    suspend fun getSignatureCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignature(signature: Signature)

    /**
     * Deletes a signature from the database.
     *
     * @param signature The signature object to delete.
     */
    @Delete
    suspend fun deleteSignature(signature: Signature)
}
