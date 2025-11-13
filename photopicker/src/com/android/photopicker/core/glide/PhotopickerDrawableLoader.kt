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
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoader.LoadData

class PhotopickerDrawableLoader(val context: Context) : ModelLoader<GlideLoadable, Drawable> {

    /**
     * This assembles a cache signature for storing the resulting bytes, and instantiates a worker
     * that can actually fetch the required data. The worker will be later called by Glide when the
     * load should begin.
     */
    override fun buildLoadData(
        model: GlideLoadable,
        width: Int,
        height: Int,
        options: Options,
    ): LoadData<Drawable> {
        return LoadData(
            model.getSignature(Resolution.THUMBNAIL),
            PhotopickerDrawableFetcher(model, context),
        )
    }

    /**
     * A check by Glide amongst registered ModelLoaders to resolve which loader should handle a
     * particular load.
     *
     * Since [GlideLoadable] is a custom implementation, this is the only ModelLoader that is able
     * to handle it, so if this handles check fails, the load will never start.
     *
     * @return If this model loader is able to load the requested model.
     */
    override fun handles(model: GlideLoadable): Boolean {
        // If the model is a GlideLoadable, this [ModelLoader] should try to handle it.
        return true
    }
}
