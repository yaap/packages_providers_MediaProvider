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

/**
 * Represents the count of items for a specific year and month.
 *
 * @property year The year.
 * @property month The month.
 * @property itemCount The count of items for the given year and month.
 */
data class ItemsPerMonth(val year: Int, val month: Int, val itemCount: Int)
