/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.photopicker.data.model

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.photopicker.core.glide.GlideLoadable
import com.android.photopicker.core.glide.ParcelableGlideLoadable
import com.android.photopicker.core.glide.Resolution
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.signature.ObjectKey

sealed class Icon() : Parcelable {
    companion object {
        operator fun invoke(uri: Uri, mediaSource: MediaSource): GlideIcon {
            return GlideIcon(uri, mediaSource)
        }

        operator fun invoke(imageVector: ImageVector): VectorIcon {
            return VectorIcon(imageVector)
        }
    }
}

/**
 * An icon is a simple object which points to a media resource can be loaded by [Glide] because it
 * implements the [GlideLoadable] interface.
 */
data class GlideIcon(val uri: Uri, val mediaSource: MediaSource) : Icon(), ParcelableGlideLoadable {
    override fun getSignature(resolution: Resolution): ObjectKey {
        return ObjectKey("${uri}_$resolution")
    }

    override fun getLoadableUri(): Uri {
        return uri
    }

    override fun getDataSource(): DataSource {
        return when (mediaSource) {
            MediaSource.LOCAL -> DataSource.LOCAL
            MediaSource.REMOTE -> DataSource.REMOTE
        }
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeString(uri.toString())
        out.writeString(mediaSource.name)
    }

    companion object CREATOR : Parcelable.Creator<GlideIcon> {

        override fun createFromParcel(parcel: Parcel): GlideIcon {
            return GlideIcon(
                uri = Uri.parse(parcel.readString() ?: ""),
                mediaSource = MediaSource.valueOf(parcel.readString() ?: "LOCAL"),
            )
        }

        override fun newArray(size: Int): Array<GlideIcon?> {
            return arrayOfNulls(size)
        }
    }
}

/** An icon that is represented by a static material3 [ImageVector]. */
data class VectorIcon(val imageVector: ImageVector) : Icon() {

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeString(imageVector.name)
    }

    companion object CREATOR : Parcelable.Creator<VectorIcon> {

        override fun createFromParcel(parcel: Parcel): VectorIcon? {
            val imageName = parcel.readString()
            return when (imageName) {
                Icons.Outlined.FolderCopy.name -> VectorIcon(Icons.Outlined.FolderCopy)
                Icons.Outlined.SdCard.name -> VectorIcon(Icons.Outlined.SdCard)
                else -> return null
            }
        }

        override fun newArray(size: Int): Array<VectorIcon?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * A composable for a badge with a circular background and a centered [VectorIcon]
 *
 * @param icon The [VectorIcon] for the badge.
 * @param boxModifier The modifier to be applied to the outer box.
 * @param iconModifier The modifier to be applied to the centered icon.
 */
@Composable
fun VectorIconBadge(
    icon: VectorIcon,
    boxModifier: Modifier,
    iconModifier: Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier =
            boxModifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon.imageVector,
            contentDescription = contentDescription,
            modifier = iconModifier,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
