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

package com.android.signature.ui.settings

import com.android.signature.data.Signature
import com.android.signature.data.SignatureDao
import com.android.signature.data.SignatureRepository
import com.android.signature.logging.SignatureEventLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private lateinit var signatureDao: SignatureDao
    private lateinit var eventLogger: SignatureEventLogger
    private lateinit var repository: SignatureRepository
    private lateinit var viewModel: SettingsViewModel
    private val signaturesFlow = MutableStateFlow<List<Signature>>(emptyList())

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        signatureDao = Mockito.mock(SignatureDao::class.java)
        eventLogger = Mockito.mock(SignatureEventLogger::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(signaturesFlow)
        repository = SignatureRepository(signatureDao)
        viewModel = SettingsViewModel(repository, eventLogger)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun signatures_initiallyEmpty() =
        runTest {
            val signatures = viewModel.signatures.first()
            assertEquals(emptyList<Signature>(), signatures)
        }

    @Test
    fun signatures_updatesFromRepository() =
        runTest {
            val signature = Signature(id = "1", type = Signature.TYPE_TYPED, textData = "Test")
            signaturesFlow.emit(listOf(signature))

            val signatures = viewModel.signatures.first()
            assertEquals(listOf(signature), signatures)
        }

    @Test
    fun deleteSignature_delegatesToRepository() =
        runTest {
            val signature = Signature(id = "1", type = Signature.TYPE_TYPED, textData = "Test")

            viewModel.setSignatureToDelete(signature)
            viewModel.deleteSignature(signature, SignatureEventLogger.Screen.SETTINGS)

            verify(signatureDao).deleteSignature(signature)
            verify(eventLogger).logSignatureDeleted(
                signature.type,
                SignatureEventLogger.Screen.SETTINGS,
            )
            assertNull(viewModel.signatureToDelete.value)
        }

    @Test
    fun setSignatureToDelete_updatesState() =
        runTest {
            assertNull(viewModel.signatureToDelete.value)

            val signature = Signature(id = "1", type = Signature.TYPE_TYPED, textData = "Test")
            viewModel.setSignatureToDelete(signature)
            assertEquals(signature, viewModel.signatureToDelete.value)

            viewModel.setSignatureToDelete(null)
            assertNull(viewModel.signatureToDelete.value)
        }
}
