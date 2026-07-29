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

package com.android.photopicker.extensions

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * An extension function to convert a timestamp (in milliseconds) in a [LocalDateTime] using the
 * device's current system time zone.
 *
 * Timestamps are expected to be milliseconds since epoch in UTC. See
 * [CloudMediaProviderContract#MediaColumns.DATE_TAKEN_MILLIS].
 */
fun Long.toLocalDateTime(): LocalDateTime {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
}
