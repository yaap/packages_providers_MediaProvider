/*
 * Copyright 2024 The Android Open Source Project
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
import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.os.UserHandle
import android.util.Log
import androidx.core.content.getSystemService

/**
 * Extension that removes nullability of getSystemService
 *
 * @param T The Type of the SystemService
 * @return A non null System service.
 * @throws [IllegalStateException] if the returned service is null.
 */
inline fun <reified T> Context.requireSystemService(): T {
    return checkNotNull(getSystemService()) { "A required System Service was null" }
}

/**
 * Extension that tries to get the contentResolver from the given user and package's context.
 *
 * In case, the package does not exist in the given user profile, this method falls back to creating
 * a system context for the given user and then getting the content resolver.
 *
 * If any other unexpected error occurs, this method throws an Exception.
 *
 * @param userHandle UserHandle to identify the user
 * @param packageName This is an optional parameter. The default is the current package name.
 * @throws Exception in case an unexpected error occurs.
 */
fun Context.getContentResolverForUser(
    userHandle: UserHandle,
    packageName: String = this.packageName,
): ContentResolver {
    try {
        return createPackageContextAsUser(packageName, /* flags */ 0, userHandle).contentResolver
    } catch (e: NameNotFoundException) {
        // If the Photopicker package does not exist in the given user profile,
        // [Context.createPackageContextAsUser()] throws this error. In this case, we attempt to
        // create a [Context] object for the user for system apps to get the right content resolver.

        Log.w(
            "PhotopickerContextExtension",
            "Could not find the Photopicker package in user ${userHandle.getIdentifier()}",
        )
        return createPackageContextAsUser("android", /* flags */ 0, userHandle).contentResolver
    } catch (e: Exception) {
        throw Exception(
            "Can't get the content resolver for the user profile " +
                "${userHandle.getIdentifier()}",
            e,
        )
    }
}
