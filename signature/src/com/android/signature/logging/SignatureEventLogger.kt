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

import android.os.Process
import com.android.signature.SignatureStatsLog
import com.android.signature.data.Signature
import com.android.signature.data.Signature.Companion.SignatureType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class to log Signature metrics using SignatureStatsLog.
 */
@Singleton
open class SignatureEventLogger
    @Inject
    constructor() {
        private val uid: Int = Process.myUid()

        /**
         * Converts a domain [Signature.Type] to the StatsLog enum.
         */
        private fun getStatsLogSignatureType(
            @SignatureType type: Int,
        ): Int =
            when (type) {
                Signature.TYPE_DRAWN -> SignatureStatsLog.SIGNATURE_SELECTED__SIGNATURE_TYPE__SIGNATURE_TYPE_DRAW
                Signature.TYPE_TYPED -> SignatureStatsLog.SIGNATURE_SELECTED__SIGNATURE_TYPE__SIGNATURE_TYPE_TYPE
                Signature.TYPE_UPLOADED -> SignatureStatsLog.SIGNATURE_SELECTED__SIGNATURE_TYPE__SIGNATURE_TYPE_UPLOAD
                else -> SignatureStatsLog.SIGNATURE_SELECTED__SIGNATURE_TYPE__SIGNATURE_TYPE_UNKNOWN
            }

        /**
         * Converts a screen identifier to the StatsLog enum.
         */
        private fun getStatsLogSignatureScreen(screen: Screen): Int =
            when (screen) {
                Screen.PICKER -> SignatureStatsLog.SIGNATURES_LOAD_DURATION__SCREEN__SIGNATURE_SCREEN_PICKER
                Screen.SETTINGS -> SignatureStatsLog.SIGNATURES_LOAD_DURATION__SCREEN__SIGNATURE_SCREEN_SETTINGS
                else -> SignatureStatsLog.SIGNATURES_LOAD_DURATION__SCREEN__SIGNATURE_SCREEN_UNKNOWN
            }

        open fun logSignaturePickerLaunched() {
            SignatureStatsLog.write(
                SignatureStatsLog.SIGNATURE_PICKER_LAUNCHED,
                uid,
            )
        }

        open fun logSignatureSelected(
            @SignatureType type: Int,
        ) {
            SignatureStatsLog.write(
                SignatureStatsLog.SIGNATURE_SELECTED,
                uid,
                getStatsLogSignatureType(type),
            )
        }

        open fun logSignatureCreateLaunched() {
            SignatureStatsLog.write(
                SignatureStatsLog.SIGNATURE_CREATE_LAUNCHED,
                uid,
            )
        }

        open fun logSignatureCreated(
            @SignatureType type: Int,
            dataSizeBytes: Int,
        ) {
            SignatureStatsLog.write(
                SignatureStatsLog.SIGNATURE_CREATED,
                uid,
                getStatsLogSignatureType(type),
                dataSizeBytes,
            )
        }

        open fun logSignaturesLoadDuration(
            durationMillis: Long,
            signatureCount: Int,
            screen: Screen,
        ) {
            SignatureStatsLog.write(
                SignatureStatsLog.SIGNATURES_LOAD_DURATION,
                uid,
                durationMillis,
                signatureCount,
                getStatsLogSignatureScreen(screen),
            )
        }

        open fun logSignatureSaveDuration(
            durationMillis: Long,
            @SignatureType type: Int,
        ) {
            SignatureStatsLog.write(
                SignatureStatsLog.SIGNATURE_SAVE_DURATION,
                uid,
                durationMillis,
                getStatsLogSignatureType(type),
            )
        }

        open fun logSignatureProviderOpenDuration(durationMillis: Long) {
            SignatureStatsLog.write(
                SignatureStatsLog.SIGNATURE_PROVIDER_OPEN_DURATION,
                uid,
                durationMillis,
            )
        }

        /**
         * Converts a domain [AppErrorType] to the StatsLog enum.
         */
        private fun getStatsLogAppErrorType(errorType: AppErrorType): Int =
            when (errorType) {
                AppErrorType.DB_READ_FAILED -> SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_DB_READ_FAILED
                AppErrorType.DB_WRITE_FAILED -> SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_DB_WRITE_FAILED
                AppErrorType.PROVIDER_UUID_NOT_FOUND -> SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_PROVIDER_UUID_NOT_FOUND
                AppErrorType.DB_DELETE_FAILED -> SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_DB_DELETE_FAILED
                AppErrorType.UPLOAD_FAILED -> SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_UPLOAD_FAILED
                else -> SignatureStatsLog.SIGNATURE_APP_ERROR__ERROR_TYPE__SIGNATURE_APP_ERROR_TYPE_UNKNOWN
            }

        open fun logSignatureAppError(
            errorType: AppErrorType,
            @SignatureType type: Int,
        ) {
            SignatureStatsLog.write(
                SignatureStatsLog.SIGNATURE_APP_ERROR,
                uid,
                getStatsLogAppErrorType(errorType),
                getStatsLogSignatureType(type),
            )
        }

        open fun logSignatureSettingsLaunched() {
            SignatureStatsLog.write(
                SignatureStatsLog.SIGNATURE_SETTINGS_LAUNCHED,
                uid,
            )
        }

        open fun logSignatureDeleted(
            @SignatureType type: Int,
            screen: Screen,
        ) {
            SignatureStatsLog.write(
                SignatureStatsLog.SIGNATURE_DELETED,
                uid,
                getStatsLogSignatureType(type),
                getStatsLogSignatureScreen(screen),
            )
        }

        open fun logSignatureDeleteDuration(
            durationMillis: Long,
            screen: Screen,
        ) {
            SignatureStatsLog.write(
                SignatureStatsLog.SIGNATURE_DELETE_DURATION,
                uid,
                durationMillis,
                getStatsLogSignatureScreen(screen),
            )
        }

        enum class Screen {
            UNKNOWN,
            PICKER,
            SETTINGS,
        }

        enum class AppErrorType {
            UNKNOWN,
            DB_READ_FAILED,
            DB_WRITE_FAILED,
            PROVIDER_UUID_NOT_FOUND,
            DB_DELETE_FAILED,
            UPLOAD_FAILED,
        }
    }
