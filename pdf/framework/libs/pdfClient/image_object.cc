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

#include "image_object.h"

#include <stddef.h>
#include <stdint.h>

#include "fpdf_edit.h"
#include "logging.h"
#include "rect.h"

#define LOG_TAG "image_object"

namespace pdfClient {

BitmapFormat GetBitmapFormat(int bitmap_format) {
    switch (bitmap_format) {
        case FPDFBitmap_BGR: {
            return BitmapFormat::BGR;
        }
        case FPDFBitmap_BGRA: {
            return BitmapFormat::BGRA;
        }
        case FPDFBitmap_BGRx: {
            return BitmapFormat::BGRx;
        }
        default: {
            return BitmapFormat::Unknown;
        }
    }
}

ImageObject::ImageObject() : PageObject(Type::Image) {}

ScopedFPDFPageObject ImageObject::CreateFPDFInstance(FPDF_DOCUMENT document, FPDF_PAGE page) {
    // Create a scoped PDFium image object.
    ScopedFPDFPageObject scoped_image_object(FPDFPageObj_NewImageObj(document));
    if (!scoped_image_object) {
        LOGE("Object creation failed");
        return nullptr;
    }
    // Update attributes of PDFium image object.
    if (!UpdateFPDFInstance(scoped_image_object.get(), page)) {
        LOGE("Create update failed");
        return nullptr;
    }
    return scoped_image_object;
}

bool ImageObject::UpdateFPDFInstance(FPDF_PAGEOBJECT image_object, FPDF_PAGE page) {
    if (!image_object) {
        LOGE("Object NULL");
        return false;
    }

    // Check for Type Correctness.
    if (FPDFPageObj_GetType(image_object) != FPDF_PAGEOBJ_IMAGE) {
        LOGE("TypeCast failed");
        return false;
    }

    // Set the updated bitmap.
    if (!FPDFImageObj_SetBitmap(nullptr, 0, image_object, bitmap_.get())) {
        LOGE("SetBitmap failed");
        return false;
    }

    // Set the updated matrix.
    if (!SetDeviceToPageMatrix(image_object, page)) {
        LOGE("SetDeviceToPageMatrix failed");
        return false;
    }

    // Set the updated dimensions.
    width_ = FPDFBitmap_GetWidth(bitmap_.get());
    height_ = FPDFBitmap_GetHeight(bitmap_.get());

    // Set the updated bitmap format.
    bitmap_format_ = GetBitmapFormat(FPDFBitmap_GetFormat(bitmap_.get()));

    return true;
}

bool ImageObject::PopulateFromFPDFInstance(FPDF_PAGEOBJECT image_object, FPDF_PAGE page) {
    // Get bitmap.
    bitmap_ = ScopedFPDFBitmap(FPDFImageObj_GetBitmap(image_object));
    if (bitmap_.get() == nullptr) {
        LOGE("Bitmap not present");
        return false;
    }

    // Get matrix.
    if (!GetPageToDeviceMatrix(image_object, page)) {
        LOGE("GetPageToDeviceMatrix failed");
        return false;
    }

    // Get dimensions.
    width_ = FPDFBitmap_GetWidth(bitmap_.get());
    height_ = FPDFBitmap_GetHeight(bitmap_.get());

    // Get bitmap format.
    bitmap_format_ = GetBitmapFormat(FPDFBitmap_GetFormat(bitmap_.get()));
    if (bitmap_format_ == BitmapFormat::Unknown) {
        LOGE("Bitmap format unknown");
        return false;
    }
    return true;
}

void* ImageObject::GetBitmapBuffer() const {
    return FPDFBitmap_GetBuffer(bitmap_.get());
}

bool ImageObject::GetPageToDeviceMatrix(FPDF_PAGEOBJECT image_object, FPDF_PAGE page) {
    Matrix page_matrix;
    if (!FPDFPageObj_GetMatrix(image_object, reinterpret_cast<FS_MATRIX*>(&page_matrix))) {
        LOGE("GetPageMatrix failed!");
        return false;
    }

    // Set identity transformation for GetBounds.
    Matrix identity = {1, 0, 0, 1, 0, 0};
    FPDFPageObj_SetMatrix(image_object, reinterpret_cast<FS_MATRIX*>(&identity));

    // Get Bounds.
    Rectangle_f bounds;
    FPDFPageObj_GetBounds(image_object, &bounds.left, &bounds.bottom, &bounds.right, &bounds.top);

    // Reset the original page matrix.
    FPDFPageObj_SetMatrix(image_object, reinterpret_cast<FS_MATRIX*>(&page_matrix));

    float page_height = FPDF_GetPageHeightF(page);

    // Page to device matrix.
    device_matrix_.a = page_matrix.a;
    device_matrix_.b = (page_matrix.b != 0) ? -page_matrix.b : 0;
    device_matrix_.c = (page_matrix.c != 0) ? -page_matrix.c : 0;
    device_matrix_.d = page_matrix.d;
    device_matrix_.e = page_matrix.e + (bounds.top * page_matrix.c);
    device_matrix_.f = page_height - page_matrix.f - (bounds.top * page_matrix.d);

    return true;
}

bool ImageObject::SetDeviceToPageMatrix(FPDF_PAGEOBJECT image_object, FPDF_PAGE page) {
    // Reset Previous Transformation.
    Matrix identity = {1, 0, 0, 1, 0, 0};
    if (!FPDFPageObj_SetMatrix(image_object, reinterpret_cast<FS_MATRIX*>(&identity))) {
        LOGE("SetMatrix failed!");
        return false;
    }

    Rectangle_f bounds;
    FPDFPageObj_GetBounds(image_object, &bounds.left, &bounds.bottom, &bounds.right, &bounds.top);

    float page_height = FPDF_GetPageHeightF(page);

    FPDFPageObj_Transform(image_object, 1, 0, 0, 1, 0, -bounds.top);
    FPDFPageObj_Transform(image_object, device_matrix_.a, -device_matrix_.b, -device_matrix_.c,
                          device_matrix_.d, device_matrix_.e, -device_matrix_.f);
    FPDFPageObj_Transform(image_object, 1, 0, 0, 1, 0, page_height);

    return true;
}

ImageObject::~ImageObject() = default;

}  // namespace pdfClient