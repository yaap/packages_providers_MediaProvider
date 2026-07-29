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

package com.android.signature.ui.create

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.signature.HiltTestActivity
import com.android.signature.data.Signature
import com.android.signature.data.SignatureDao
import com.android.signature.data.SignatureRepository
import com.android.signature.test.TestUtils
import com.android.signature.ui.SignatureViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class UploadTabTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setup() {
        hiltRule.inject()
        TestUtils.wakeUpDevice()
        try {
            composeTestRule.activityRule.scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.setShowWhenLocked(true)
                it.setTurnScreenOn(true)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun uploadTab_initialState_showsPlaceholder() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CreateSignatureScreen(
                    viewModel = viewModel,
                    onSignatureCreated = {},
                    onCancel = {})
            }
        }

        // Switch to Upload tab
        composeTestRule.onNodeWithText("Upload").performClick()

        composeTestRule.onNodeWithText("No image selected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Select Image").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun uploadTab_cancel_triggersCallback() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)
        var cancelClicked = false

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CreateSignatureScreen(
                    viewModel = viewModel,
                    onSignatureCreated = {},
                    onCancel = { cancelClicked = true })
            }
        }

        // Switch to Upload tab
        composeTestRule.onNodeWithText("Upload").performClick()

        composeTestRule.onNodeWithText("Cancel").performClick()
        Assert.assertTrue(cancelClicked)
    }

    @Test
    fun uploadTab_imageSelected_showsButtons() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        // Create dummy image
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "test_image.png")
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = Uri.fromFile(file)
        val testRegistry = FakeActivityResultRegistry(uri)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object :
                    ActivityResultRegistryOwner {
                    override val activityResultRegistry = testRegistry
                }) {
                    CreateSignatureScreen(
                        viewModel = viewModel,
                        onSignatureCreated = {},
                        onCancel = {})
                }
            }
        }

        // Switch to Upload tab
        composeTestRule.onNodeWithText("Upload").performClick()

        // Select Image
        composeTestRule.onNodeWithText("Select Image").performClick()
        composeTestRule.waitForIdle()

        // Verify buttons
        composeTestRule.onNodeWithText("Back").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add").assertIsDisplayed()
    }

    @Test
    fun uploadTab_back_relaunchesPicker() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        // Create dummy image
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "test_image.png")
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = Uri.fromFile(file)

        // Registry that tracks launches
        var launchCount = 0
        val testRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                launchCount++
                dispatchResult(requestCode, uri)
            }
        }

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object :
                    ActivityResultRegistryOwner {
                    override val activityResultRegistry = testRegistry
                }) {
                    CreateSignatureScreen(
                        viewModel = viewModel,
                        onSignatureCreated = {},
                        onCancel = {})
                }
            }
        }

        // Switch to Upload tab
        composeTestRule.onNodeWithText("Upload").performClick()

        // Select Image (Launch 1)
        composeTestRule.onNodeWithText("Select Image").performClick()
        composeTestRule.waitForIdle()
        Assert.assertEquals(1, launchCount)

        // Click Back (Launch 2)
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.waitForIdle()
        Assert.assertEquals(2, launchCount)
    }

    @Test
    fun createSignatureScreen_uploadTab_savesSignature() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        runBlocking {
            whenever(signatureDao.getSignatureCount()).thenReturn(0)
        }
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        // Create a dummy image file
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "test_image.png")
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = Uri.fromFile(file)

        val testRegistry = FakeActivityResultRegistry(uri)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object :
                    ActivityResultRegistryOwner {
                    override val activityResultRegistry = testRegistry
                }) {
                    CreateSignatureScreen(
                        viewModel = viewModel,
                        onSignatureCreated = { },
                        onCancel = {})
                }
            }
        }

        // Switch to Upload tab
        composeTestRule.onNodeWithText("Upload").performClick()

        // Click Select Image
        composeTestRule.onNodeWithText("Select Image").performClick()

        // Wait for image to be loaded (ImageDecoder)
        composeTestRule.waitForIdle()

        // Click Add
        composeTestRule.onNodeWithText("Add").performClick()

        composeTestRule.waitForIdle()

        // Verify signature is saved
        runBlocking {
            val captor = argumentCaptor<Signature>()
            verify(signatureDao).insertSignature(captor.capture())
            val capturedSignature = captor.firstValue
            Assert.assertEquals(Signature.TYPE_UPLOADED, capturedSignature.type)
            assert(capturedSignature.imageData != null)
        }
    }

    @Test
    fun createSignatureScreen_uploadTab_largeFile_showsError() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        // Create a dummy large image file (> 2MB)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "large_image.png")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(2 * 1024 * 1024 + 100) // 2MB + 100 bytes
        }
        val uri = Uri.fromFile(file)

        val testRegistry = FakeActivityResultRegistry(uri)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object :
                    ActivityResultRegistryOwner {
                    override val activityResultRegistry = testRegistry
                }) {
                    CreateSignatureScreen(
                        viewModel = viewModel,
                        onSignatureCreated = { },
                        onCancel = {})
                }
            }
        }

        // Switch to Upload tab
        composeTestRule.onNodeWithText("Upload").performClick()

        // Click Select Image
        composeTestRule.onNodeWithText("Select Image").performClick()

        // Wait for image processing
        composeTestRule.waitForIdle()

        // Verify Add is NOT displayed (because bitmap is null)
        composeTestRule.onAllNodesWithText("Add").assertCountEquals(0)
    }

    @Test
    fun createSignatureScreen_uploadTab_limitReached_showsError() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        runBlocking {
            whenever(signatureDao.getSignatureCount()).thenReturn(5)
        }
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        // Create a dummy image file
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "test_image.png")
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = Uri.fromFile(file)

        val testRegistry = FakeActivityResultRegistry(uri)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object :
                    ActivityResultRegistryOwner {
                    override val activityResultRegistry = testRegistry
                }) {
                    CreateSignatureScreen(
                        viewModel = viewModel,
                        onSignatureCreated = { },
                        onCancel = {})
                }
            }
        }

        // Switch to Upload tab
        composeTestRule.onNodeWithText("Upload").performClick()

        // Click Select Image
        composeTestRule.onNodeWithText("Select Image").performClick()

        // Wait for image to be loaded
        composeTestRule.waitForIdle()

        // Click Add
        composeTestRule.onNodeWithText("Add").performClick()

        composeTestRule.waitForIdle()

        // Verify save was NOT called
        runBlocking {
            verify(signatureDao, Mockito.never()).insertSignature(any())
        }
    }

    @Test
    fun uploadTab_corruptedImage_showsError() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        // Create a corrupted image file (random bytes)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "corrupt_image.png")
        FileOutputStream(file).use { out ->
            out.write(byteArrayOf(1, 2, 3, 4, 5)) // Not a valid image
        }
        val uri = Uri.fromFile(file)

        val testRegistry = FakeActivityResultRegistry(uri)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object :
                    ActivityResultRegistryOwner {
                    override val activityResultRegistry = testRegistry
                }) {
                    CreateSignatureScreen(
                        viewModel = viewModel,
                        onSignatureCreated = { },
                        onCancel = {})
                }
            }
        }

        // Switch to Upload tab
        composeTestRule.onNodeWithText("Upload").performClick()

        // Click Select Image
        composeTestRule.onNodeWithText("Select Image").performClick()

        // Wait for image processing
        composeTestRule.waitForIdle()

        // Verify Add is NOT displayed (because bitmap is null due to error)
        composeTestRule.onAllNodesWithText("Add").assertCountEquals(0)
    }

    @Test
    fun uploadTab_nullUri_doesNothing() {
        val signatureDao = Mockito.mock(SignatureDao::class.java)
        whenever(signatureDao.getAllSignatures()).thenReturn(flowOf(emptyList()))
        val repository = SignatureRepository(signatureDao)
        val eventLogger = Mockito.mock(com.android.signature.logging.SignatureEventLogger::class.java)
        val viewModel = SignatureViewModel(repository, eventLogger)

        // Registry returns null
        val testRegistry = FakeActivityResultRegistry(null)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object :
                    ActivityResultRegistryOwner {
                    override val activityResultRegistry = testRegistry
                }) {
                    CreateSignatureScreen(
                        viewModel = viewModel,
                        onSignatureCreated = { },
                        onCancel = {})
                }
            }
        }

        // Switch to Upload tab
        composeTestRule.onNodeWithText("Upload").performClick()

        // Click Select Image
        composeTestRule.onNodeWithText("Select Image").performClick()

        // Wait for idle
        composeTestRule.waitForIdle()

        // Verify we are still in initial state (No image selected)
        composeTestRule.onNodeWithText("No image selected").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Add").assertCountEquals(0)
    }
}

class FakeActivityResultRegistry(private val result: Uri?) : ActivityResultRegistry() {
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?
    ) {
        dispatchResult(requestCode, result)
    }
}
