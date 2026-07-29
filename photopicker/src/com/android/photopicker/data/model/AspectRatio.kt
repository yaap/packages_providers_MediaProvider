/*
 * Copyright (C) 2026 The Android Open Source Project
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

/**
 * Enum representing the supported aspect ratios in the Photopicker.
 *
 * @property ratio The Float value used to represent the width of an element relative to its height.
 */
enum class AspectRatio(val ratio: Float) {
    /** A square 1:1 aspect ratio. */
    SQUARE_1_1(1f),

    /** A portrait 9:16 aspect ratio. */
    PORTRAIT_9_16(9f / 16f),
}
