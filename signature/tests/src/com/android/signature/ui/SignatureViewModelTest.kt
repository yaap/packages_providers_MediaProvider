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

package com.android.signature.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import com.android.signature.data.Signature
import com.android.signature.data.SignatureDao
import com.android.signature.data.SignatureFont
import com.android.signature.data.SignatureRepository
import com.android.signature.logging.SignatureEventLogger
import com.android.signature.ui.create.PathState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class SignatureViewModelTest {
    @Mock
    private lateinit var signatureDao: SignatureDao

    @Mock
    private lateinit var eventLogger: SignatureEventLogger

    private lateinit var bitmap: Bitmap

    private lateinit var repository: SignatureRepository
    private lateinit var viewModel: SignatureViewModel
    private val signaturesFlow = MutableStateFlow<List<Signature>>(emptyList())

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        whenever(signatureDao.getAllSignatures()).thenReturn(signaturesFlow)
        repository = SignatureRepository(signatureDao)
        viewModel = SignatureViewModel(repository, eventLogger)
        // Create a real bitmap (1x1 pixel)
        bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
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

            viewModel.deleteSignature(signature, SignatureEventLogger.Screen.PICKER)

            verify(signatureDao).deleteSignature(signature)
            verify(eventLogger).logSignatureDeleted(signature.type, SignatureEventLogger.Screen.PICKER)
        }

    @Test
    fun newlyAddedSignatureIndex_calculatesCorrectly() =
        runTest {
            val sig1 = Signature(id = "id_1", type = Signature.TYPE_TYPED, textData = "Sig 1")
            val sig2 = Signature(id = "id_2", type = Signature.TYPE_TYPED, textData = "Sig 2")
            val sig3 = Signature(id = "id_3", type = Signature.TYPE_TYPED, textData = "Sig 3")

            // 1. Initially null
            assertNull(viewModel.newlyAddedSignatureIndex.first())

            // 2. Set signatures
            signaturesFlow.emit(listOf(sig1, sig2, sig3))

            // Still null because newSignatureId is null
            assertNull(viewModel.newlyAddedSignatureIndex.first())

            // 3. Set an ID that exists
            viewModel.setNewSignatureId("id_2")

            // The index of "id_2" is 1
            assertEquals(1, viewModel.newlyAddedSignatureIndex.first())

            // 4. Set an ID that does NOT exist
            viewModel.setNewSignatureId("id_missing")
            assertNull(viewModel.newlyAddedSignatureIndex.first())

            // 5. Reset to null
            viewModel.setNewSignatureId(null)
            assertNull(viewModel.newlyAddedSignatureIndex.first())
        }

    @Test
    fun setNewSignatureId_updatesState() =
        runTest {
            assertNull(viewModel.newSignatureId.value)

            viewModel.setNewSignatureId("123")
            assertEquals("123", viewModel.newSignatureId.value)

            viewModel.setNewSignatureId(null)
            assertNull(viewModel.newSignatureId.value)
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

    @Test
    fun setSelectedTabIndex_updatesState() =
        runTest {
            assertEquals(0, viewModel.selectedTabIndex.value)

            viewModel.setSelectedTabIndex(1)
            assertEquals(1, viewModel.selectedTabIndex.value)
        }

    @Test
    fun setDrawingPaths_updatesState() =
        runTest {
            assertEquals(emptyList<PathState>(), viewModel.drawingPaths.value)

            val paths =
                listOf(
                    PathState(
                        androidx.compose.ui.graphics
                            .Path(),
                        androidx.compose.ui.graphics.Color.Black,
                        5f,
                    ),
                )
            viewModel.setDrawingPaths(paths)
            assertEquals(paths, viewModel.drawingPaths.value)
        }

    @Test
    fun setTypedText_updatesState() =
        runTest {
            assertEquals("", viewModel.typedText.value)

            viewModel.setTypedText("Hello")
            assertEquals("Hello", viewModel.typedText.value)
        }

    @Test
    fun setSelectedFont_updatesState() =
        runTest {
            // Verify initial state is null
            assertNull(viewModel.selectedFont.value)

            // Create a dummy font to test setter
            val newFont =
                SignatureFont(
                    name = "Test Font",
                    composeFontFamily = FontFamily.Default,
                    androidTypeface = Typeface.DEFAULT,
                )

            viewModel.setSelectedFont(newFont)
            assertEquals(newFont, viewModel.selectedFont.value)
        }

    @Test
    fun setUploadedImage_updatesState() =
        runTest {
            assertNull(viewModel.uploadedImage.value)

            viewModel.setUploadedImage(bitmap)
            assertEquals(bitmap, viewModel.uploadedImage.value)

            viewModel.setUploadedImage(null)
            assertNull(viewModel.uploadedImage.value)
        }

    @Test
    fun clearCreateSignatureState_clearsAllState() =
        runTest {
            viewModel.setSelectedTabIndex(2)
            viewModel.setTypedText("Hello")
            viewModel.setDrawingPaths(
                listOf(
                    PathState(
                        androidx.compose.ui.graphics
                            .Path(),
                        androidx.compose.ui.graphics.Color.Black,
                        5f,
                    ),
                ),
            )
            viewModel.setSelectedFont(SignatureFont("Font", FontFamily.Default, Typeface.DEFAULT))
            viewModel.setUploadedImage(bitmap)

            viewModel.clearCreateSignatureState()

            assertEquals(0, viewModel.selectedTabIndex.value)
            assertEquals("", viewModel.typedText.value)
            assertEquals(emptyList<PathState>(), viewModel.drawingPaths.value)
            assertNull(viewModel.selectedFont.value)
            assertNull(viewModel.uploadedImage.value)
        }

    @Test
    fun getSignatureUri_typedSignature_returnsCorrectUri() {
        val signature =
            Signature(
                id = "1",
                type = Signature.TYPE_TYPED,
                textData = "Hello",
                fontName = "Serif",
            )

        val uri = viewModel.getSignatureUri(signature)

        assertEquals("content", uri.scheme)
        assertEquals("com.android.signature.provider", uri.authority)
        assertEquals("/signatures/1", uri.path)
        assertEquals(Signature.TYPE_TYPED.toString(), uri.getQueryParameter("type"))
        assertEquals("Hello", uri.getQueryParameter("text"))
        assertEquals("Serif", uri.getQueryParameter("font"))
    }

    @Test
    fun getSignatureUri_drawnSignature_returnsCorrectUri() {
        val signature =
            Signature(
                id = "2",
                type = Signature.TYPE_DRAWN,
                drawingPaths = "path_data",
            )

        val uri = viewModel.getSignatureUri(signature)

        assertEquals("content", uri.scheme)
        assertEquals("com.android.signature.provider", uri.authority)
        assertEquals("/signatures/2", uri.path)
        assertEquals(Signature.TYPE_DRAWN.toString(), uri.getQueryParameter("type"))
        assertEquals("path_data", uri.getQueryParameter("paths"))
    }

    @Test
    fun saveTypedSignature_savesToRepository() =
        runTest {
            runBlocking {
                whenever(signatureDao.getSignatureCount()).thenReturn(0)
                whenever(signatureDao.insertSignature(any())).thenReturn(Unit)
            }

            viewModel.saveTypedSignature("Test", "Font", bitmap)

            val captor = argumentCaptor<Signature>()
            verify(signatureDao).insertSignature(captor.capture())
            val savedSignature = captor.firstValue
            assertEquals(Signature.TYPE_TYPED, savedSignature.type)
            assertEquals("Test", savedSignature.textData)
            assertEquals("Font", savedSignature.fontName)

            verify(eventLogger).logSignatureSaveDuration(
                any(),
                org.mockito.kotlin.eq(Signature.TYPE_TYPED),
            )
        }

    @Test
    fun saveDrawnSignature_savesToRepository() =
        runTest {
            runBlocking {
                whenever(signatureDao.getSignatureCount()).thenReturn(0)
                whenever(signatureDao.insertSignature(any())).thenReturn(Unit)
            }

            viewModel.saveDrawnSignature(bitmap)

            val captor = argumentCaptor<Signature>()
            verify(signatureDao).insertSignature(captor.capture())
            val savedSignature = captor.firstValue
            assertEquals(Signature.TYPE_DRAWN, savedSignature.type)

            verify(eventLogger).logSignatureSaveDuration(
                any(),
                org.mockito.kotlin.eq(Signature.TYPE_DRAWN),
            )
        }

    @Test
    fun saveUploadedSignature_savesToRepositoryAndCompresses() =
        runTest {
            runBlocking {
                whenever(signatureDao.getSignatureCount()).thenReturn(0)
                whenever(signatureDao.insertSignature(any())).thenReturn(Unit)
            }

            // Use a bitmap larger than MAX_IMAGE_DIMENSION (512) to test downscaling
            val largeBitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)

            viewModel.saveUploadedSignature(largeBitmap)

            val captor = argumentCaptor<Signature>()
            verify(signatureDao).insertSignature(captor.capture())
            val savedSignature = captor.firstValue
            assertEquals(Signature.TYPE_UPLOADED, savedSignature.type)
            assertNotNull(savedSignature.imageData)

            // Verify the saved data is a valid, compressed PNG
            val decodedBitmap =
                BitmapFactory.decodeByteArray(
                    savedSignature.imageData,
                    0,
                    savedSignature.imageData!!.size,
                )
            assertNotNull(decodedBitmap)

            // The image should be scaled down to the MAX_IMAGE_DIMENSION (512)
            assertEquals(512, decodedBitmap.width)
            assertEquals(512, decodedBitmap.height)

            // For a simple bitmap, PNG compression should be effective
            val rawSize = largeBitmap.byteCount
            assertTrue(
                "Compressed size should be smaller than raw size",
                savedSignature.imageData!!.size < rawSize,
            )

            verify(eventLogger).logSignatureSaveDuration(
                any(),
                org.mockito.kotlin.eq(Signature.TYPE_UPLOADED),
            )
        }

    @Test
    fun saveSignature_limitReached_throwsException() =
        runTest {
            runBlocking {
                whenever(signatureDao.getSignatureCount()).thenReturn(5)
            }

            try {
                viewModel.saveUploadedSignature(bitmap)
                fail("Expected SignatureLimitException")
            } catch (e: SignatureLimitException) {
                // Expected
            }
        }
}
