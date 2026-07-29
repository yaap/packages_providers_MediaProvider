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

package com.android.signature.logging

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.signature.SignatureStatsLog
import com.android.signature.data.Signature
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignatureEventLoggerTest {
    private lateinit var logger: SignatureEventLogger

    @Before
    fun setup() {
        logger = SignatureEventLogger()
    }

    @Test
    fun getStatsLogSignatureType_validTypes_returnsCorrectStatsLogEnum() {
        // We use reflection to test the private mapping function
        val getStatsLogSignatureType =
            SignatureEventLogger::class.java.getDeclaredMethod(
                "getStatsLogSignatureType",
                Int::class.java,
            )
        getStatsLogSignatureType.isAccessible = true

        assertEquals(
            SignatureStatsLog.SIGNATURE_SELECTED__SIGNATURE_TYPE__SIGNATURE_TYPE_DRAW,
            getStatsLogSignatureType.invoke(logger, Signature.TYPE_DRAWN),
        )

        assertEquals(
            SignatureStatsLog.SIGNATURE_SELECTED__SIGNATURE_TYPE__SIGNATURE_TYPE_TYPE,
            getStatsLogSignatureType.invoke(logger, Signature.TYPE_TYPED),
        )

        assertEquals(
            SignatureStatsLog.SIGNATURE_SELECTED__SIGNATURE_TYPE__SIGNATURE_TYPE_UPLOAD,
            getStatsLogSignatureType.invoke(logger, Signature.TYPE_UPLOADED),
        )

        assertEquals(
            SignatureStatsLog.SIGNATURE_SELECTED__SIGNATURE_TYPE__SIGNATURE_TYPE_UNKNOWN,
            getStatsLogSignatureType.invoke(logger, -1), // Invalid type
        )
    }

    @Test
    fun getStatsLogSignatureScreen_validScreens_returnsCorrectStatsLogEnum() {
        // We use reflection to test the private mapping function
        val getStatsLogSignatureScreen =
            SignatureEventLogger::class.java.getDeclaredMethod(
                "getStatsLogSignatureScreen",
                SignatureEventLogger.Screen::class.java,
            )
        getStatsLogSignatureScreen.isAccessible = true

        assertEquals(
            SignatureStatsLog.SIGNATURES_LOAD_DURATION__SCREEN__SIGNATURE_SCREEN_PICKER,
            getStatsLogSignatureScreen.invoke(logger, SignatureEventLogger.Screen.PICKER),
        )

        assertEquals(
            SignatureStatsLog.SIGNATURES_LOAD_DURATION__SCREEN__SIGNATURE_SCREEN_SETTINGS,
            getStatsLogSignatureScreen.invoke(logger, SignatureEventLogger.Screen.SETTINGS),
        )

        assertEquals(
            SignatureStatsLog.SIGNATURES_LOAD_DURATION__SCREEN__SIGNATURE_SCREEN_UNKNOWN,
            getStatsLogSignatureScreen.invoke(logger, SignatureEventLogger.Screen.UNKNOWN),
        )
    }

    @Test
    fun getStatsLogAppErrorType_validTypes_returnsCorrectStatsLogEnum() {
        val getStatsLogAppErrorType =
            SignatureEventLogger::class.java.getDeclaredMethod(
                "getStatsLogAppErrorType",
                SignatureEventLogger.AppErrorType::class.java,
            )
        getStatsLogAppErrorType.isAccessible = true

        assertEquals(
            SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_DB_READ_FAILED,
            getStatsLogAppErrorType.invoke(logger, SignatureEventLogger.AppErrorType.DB_READ_FAILED),
        )

        assertEquals(
            SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_DB_WRITE_FAILED,
            getStatsLogAppErrorType.invoke(
                logger,
                SignatureEventLogger.AppErrorType.DB_WRITE_FAILED,
            ),
        )

        assertEquals(
            SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_PROVIDER_UUID_NOT_FOUND,
            getStatsLogAppErrorType.invoke(
                logger,
                SignatureEventLogger.AppErrorType.PROVIDER_UUID_NOT_FOUND,
            ),
        )

        assertEquals(
            SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_DB_DELETE_FAILED,
            getStatsLogAppErrorType.invoke(
                logger,
                SignatureEventLogger.AppErrorType.DB_DELETE_FAILED,
            ),
        )

        assertEquals(
            SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_UPLOAD_FAILED,
            getStatsLogAppErrorType.invoke(logger, SignatureEventLogger.AppErrorType.UPLOAD_FAILED),
        )

        assertEquals(
            SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_UNKNOWN,
            getStatsLogAppErrorType.invoke(logger, SignatureEventLogger.AppErrorType.UNKNOWN),
        )
    }
}
