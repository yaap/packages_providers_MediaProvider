/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.photopicker.core.glide

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.Log
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import java.io.FileNotFoundException
import java.io.IOException

/**
 * A [DataFetcher] for resolving a drawable from a Android resource URI for Glide.
 *
 * It is designed to handle [GlideLoadable] having URIs with the following structure:
 * `android.resource://<user-id>@<package-name>/<drawable-res-id>`
 *
 * This fetcher parses user ID, package name and resource id from the URI. It then creates a context
 * specific to the package name and user ID retrieved from the URI. This package and user specific
 * context is used to retrieve the drawable resource. The drawable is badged with the
 * profile-specific badge before being returned (e.g. drawables from work profile will be badged
 * with briefcase icon).
 *
 * If any of these steps fail, the load will fail, and an exception will be passed to the callback.
 *
 * @property model The [GlideLoadable] model containing the URI to be resolved.
 * @property context The application context used for creating package contexts and accessing the
 *   PackageManager.
 */
class PhotopickerDrawableFetcher(private val model: GlideLoadable, private val context: Context) :
    DataFetcher<Drawable> {

    companion object {
        val TAG: String = "PhotopickerDrawableFetcher"
    }

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in Drawable>) {
        try {
            val uri = model.getLoadableUri()
            val packageName = uri.host
            val userHandle = UserHandle.of(getUserIdFromAuthority(uri.authority))
            val resourceId =
                uri.lastPathSegment?.toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "Resource id does not exist in input uri $uri"
                    )

            var badgedIcon: Drawable? = null

            packageName?.let {
                val contextForPackage =
                    context.createPackageContextAsUser(packageName, 0, userHandle)
                val icon = contextForPackage.getDrawable(resourceId)
                icon?.let {
                    badgedIcon =
                        context.packageManager.getUserBadgedDrawableForDensity(
                            it,
                            userHandle,
                            null,
                            -1,
                        )
                }
            }
            if (badgedIcon == null) {
                callback.onLoadFailed(FileNotFoundException("Failed to load data for $uri"))
                return
            }
            callback.onDataReady(badgedIcon)
        } catch (ex: IOException) {
            callback.onLoadFailed(ex)
        } catch (ex: Exception) {
            callback.onLoadFailed(ex)
        }
    }

    override fun cleanup() {}

    override fun cancel() {}

    override fun getDataClass(): Class<Drawable> {
        return Drawable::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.LOCAL
    }

    /**
     * Parses the user ID from the authority part of a URI. It assumes the authority is in the
     * format `<userId>@<host>`.
     *
     * In case of any error during parsing the process owner's user ID is returned.
     *
     * @param authority The authority string from the URI, which may be null.
     * @return The parsed integer user ID, or the current user's ID if parsing fails.
     */
    private fun getUserIdFromAuthority(authority: String?): Int {
        val defaultUserId = UserHandle.myUserId()
        return try {
            authority?.substringBefore('@')?.toIntOrNull() ?: defaultUserId
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing userId.", e)
            defaultUserId
        }
    }
}
