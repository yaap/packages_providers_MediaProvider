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

package com.android.photopicker.core.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.photopicker.core.banners.Banner

/**
 * A container that animates its size to show a banner, if one is provided.
 *
 * @param banner The banner to display. If null, the box will be empty.
 * @param modifier The modifier to be applied to the inner [Banner] composable.
 * @param onDismiss A lambda that is invoked when the user dismisses the banner.
 */
@Composable
fun AnimatedBanner(
    banner: Banner?,
    modifier: Modifier = Modifier,
    onDismiss: (Banner) -> Unit = {},
) {
    Box(modifier = Modifier.animateContentSize()) {
        banner?.let { Banner(banner = it, modifier = modifier, onDismiss = { onDismiss(it) }) }
    }
}
