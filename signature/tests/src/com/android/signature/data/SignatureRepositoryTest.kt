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

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SignatureRepositoryTest {

    private lateinit var signatureDao: SignatureDao
    private lateinit var repository: SignatureRepository

    @Before
    fun setup() {
        signatureDao = Mockito.mock(SignatureDao::class.java)
        repository = SignatureRepository(signatureDao)
    }

    @Test
    fun getAllSignatures_delegatesToDao() = runTest {
        val signatures = listOf(Signature(type = Signature.TYPE_TYPED, textData = "Test"))
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(signatures))

        val result = repository.getAllSignatures().toList()

        assertEquals(1, result.size)
        assertEquals(signatures, result[0])
        verify(signatureDao).getAllSignatures()
    }

    @Test
    fun saveSignature_delegatesToDao() = runTest {
        val signature = Signature(type = Signature.TYPE_TYPED, textData = "Test")

        repository.saveSignature(signature)

        verify(signatureDao).insertSignature(signature)
    }

    @Test
    fun deleteSignature_delegatesToDao() = runTest {
        val signature = Signature(type = Signature.TYPE_TYPED, textData = "Test")

        repository.deleteSignature(signature)

        verify(signatureDao).deleteSignature(signature)
    }

    @Test
    fun getSignatureCount_delegatesToDao() = runTest {
        whenever(signatureDao.getSignatureCount()).thenReturn(5)

        val count = repository.getSignatureCount()

        assertEquals(5, count)
        verify(signatureDao).getSignatureCount()
    }
}
