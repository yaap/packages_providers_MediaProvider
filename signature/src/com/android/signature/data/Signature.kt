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

import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.IntDef
import androidx.compose.ui.text.font.FontFamily
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Data class representing a user's signature.
 *
 * This can be a drawn signature, a typed signature, or an uploaded image.
 *
 * @property id Unique identifier for the signature.
 * @property type The type of signature, as defined in [Signature.SignatureType].
 * @property createdAt The timestamp when the signature was created.
 * @property imageData The image data of the signature, applicable to all types.
 * @property textData The text content for typed signatures.
 * @property fontName The font used for typed signatures.
 * @property drawingPaths Serialized path data for drawn signatures.
 */
@Entity(tableName = "signatures")
data class Signature(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @SignatureType val type: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val imageData: ByteArray? = null, // For all signatures
    val textData: String? = null, // For typed signatures
    val fontName: String? = null, // For typed signatures
    val drawingPaths: String? = null // For drawn signatures (serialized paths)
) : Parcelable {

    /**
     * Secondary constructor used by the Parcelable.Creator to reconstruct
     * the object from a Parcel. The properties must be read in the same
     * order they were written in writeToParcel().
     */
    private constructor(parcel: Parcel) : this(
        parcel.readString()!!,
        parcel.readInt(),
        parcel.readLong(),
        parcel.createByteArray(),
        parcel.readString(),
        parcel.readString(),
        parcel.readString()
    )

    /**
     * Flattens this object into a Parcel.
     *
     * @param parcel The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     */
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeInt(type)
        parcel.writeLong(createdAt)
        parcel.writeByteArray(imageData)
        parcel.writeString(textData)
        parcel.writeString(fontName)
        parcel.writeString(drawingPaths)
    }

    override fun describeContents(): Int {
        return 0
    }

    // The custom equals/hashCode methods are still necessary because this
    // data class contains a ByteArray.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is Signature) return false

        if (id != other.id) return false
        if (type != other.type) return false
        if (createdAt != other.createdAt) return false
        if (imageData != null) {
            if (other.imageData == null) return false
            if (!imageData.contentEquals(other.imageData)) return false
        } else if (other.imageData != null) return false
        if (textData != other.textData) return false
        if (fontName != other.fontName) return false
        if (drawingPaths != other.drawingPaths) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (textData?.hashCode() ?: 0)
        result = 31 * result + (fontName?.hashCode() ?: 0)
        result = 31 * result + (drawingPaths?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val TYPE_UNKNOWN = 0
        const val TYPE_DRAWN = 1
        const val TYPE_TYPED = 2
        const val TYPE_UPLOADED = 3

        @IntDef(TYPE_UNKNOWN, TYPE_DRAWN, TYPE_TYPED, TYPE_UPLOADED)
        @Retention(AnnotationRetention.SOURCE)
        annotation class SignatureType

        /**
         * A public static field that generates instances of your Parcelable class
         * from a Parcel.
         */
        @JvmField
        val CREATOR: Parcelable.Creator<Signature> = object : Parcelable.Creator<Signature> {
            /**
             * Creates a new instance of the Parcelable class, instantiating it
             * from the given Parcel whose data had been previously written by
             * [Parcelable.writeToParcel].
             */
            override fun createFromParcel(parcel: Parcel): Signature {
                return Signature(parcel)
            }

            /**
             * Creates a new array of the Parcelable class.
             */
            override fun newArray(size: Int): Array<Signature?> {
                return arrayOfNulls(size)
            }
        }
    }
}

/**
 * Extension property to get the Compose FontFamily for this signature.
 */
val Signature.composeFontFamily: FontFamily
    get() = fontName?.let { name ->
        SignatureFonts.defaultFonts.find { it.name == name }?.composeFontFamily
    } ?: FontFamily.Default
