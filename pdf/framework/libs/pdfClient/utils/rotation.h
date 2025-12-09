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

#ifndef MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_ROTATION_H_
#define MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_ROTATION_H_

namespace pdfClient_utils {

// Enum class for rotation in 90 degree increments.
enum class Rotation {
    None = 0,
    Clockwise_90 = 1,
    Clockwise_180 = 2,
    AntiClockwise_90 = 3,
};

inline bool isValidRotation(Rotation rotation) {
    switch (rotation) {
        case Rotation::None:
        case Rotation::Clockwise_90:
        case Rotation::Clockwise_180:
        case Rotation::AntiClockwise_90:
            return true;
    }
    return false;
}

}  // namespace pdfClient_utils

#endif  // MEDIAPROVIDER_PDF_JNI_PDFCLIENT_UTILS_ROTATION_H_