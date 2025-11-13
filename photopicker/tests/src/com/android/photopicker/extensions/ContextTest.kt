/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.photopicker.extensions

import android.content.ContentResolver
import android.content.Intent
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for the [Intent] extension functions */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ContextTest {

    private val context = InstrumentationRegistry.getInstrumentation().getContext()

    @Test
    fun testGetContentResolverForUnknownPackage() {
        // Ensure when package is unknown, we fallback to getting the system context to get a
        // content resolver without throwing an exception.
        val unknownPackageContext: ContentResolver =
            context.getContentResolverForUser(Process.myUserHandle(), "Unknown package name!")
        assertThat(unknownPackageContext).isNotNull()
    }
}
