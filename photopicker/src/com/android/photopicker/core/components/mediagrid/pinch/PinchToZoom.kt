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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Represents events related to pinch-to-zoom gestures. */
sealed interface PinchToZoomEvent {
    /**
     * Indicates that a pinch-to-zoom gesture has started.
     *
     * @property offset The center point of the gesture at the start.
     */
    data class Started(val offset: Offset) : PinchToZoomEvent

    /**
     * Indicates that the zoom level has changed during a pinch-to-zoom gesture.
     *
     * @property value The current zoom factor.
     * @property offset The current center point of the gesture.
     */
    data class Changed(val value: Float, val offset: Offset) : PinchToZoomEvent

    /** Indicates that a pinch-to-zoom gesture has ended. */
    object Ended : PinchToZoomEvent
}

/**
 * A [Modifier] that detects pinch-to-zoom gestures and reports them via the [onZoomEvent] callback.
 *
 * This modifier utilizes [pointerInput] and [awaitEachGesture] to process touch input. It detects
 * when exactly two pointers are active on the screen to initiate a pinch gesture. During an active
 * pinch, it calculates the zoom factor and the centroid of the two pointers.
 *
 * Key behaviors:
 * - Consumes the initial pointer down event.
 * - Consumes pointer changes involving the two active pointers during the pinch.
 * - Emits [PinchToZoomEvent.Started] when two pointers first become active.
 * - Emits [PinchToZoomEvent.Changed] when the zoom factor changes (the current centroid is also
 *   provided). The [onZoomEvent] callback for this event can prematurely end the gesture tracking
 *   by returning `true`.
 * - Emits [PinchToZoomEvent.Ended] when the gesture concludes (either by pointers lifting or by the
 *   `Changed` event callback returning `true`).
 *
 * @param pass The [PointerEventPass] in which pointer events are processed. Defaults to
 *   [PointerEventPass.Main]. This affects event processing order if other pointer input modifiers
 *   are present.
 * @param onZoomEvent A lambda function invoked with [PinchToZoomEvent] updates. The lambda is
 *   expected to return a [Boolean]. Return `false` to continue tracking changes.
 *     - [PinchToZoomEvent.Started]: Signals the gesture start with the initial centroid.
 *     - [PinchToZoomEvent.Changed]: Signals that the zoom factor has changed. Also provides the
 *       current centroid. Return `true` to indicate the event was consumed and to stop the current
 *       gesture processing, which will then trigger a [PinchToZoomEvent.Ended].
 *     - [PinchToZoomEvent.Ended]: Signals the gesture's end.
 *
 * @return A [Modifier] that incorporates the pinch-to-zoom detection logic.
 */
fun Modifier.pinchToZoom(
    pass: PointerEventPass = PointerEventPass.Main,
    onZoomEvent: (PinchToZoomEvent) -> Boolean,
): Modifier {
    return this then
        pointerInput(Unit) {
            awaitEachGesture {
                // These local gesture values are reset automatically
                // when awaitEachGesture's lambda block restarts for a new gesture.
                var pinchActive = false
                var done = false
                var lastZoomValue: Float? = null

                // Wait for the first pointer touch. This consumes the first down event.
                awaitFirstDown(requireUnconsumed = true, pass = pass)

                do {
                    val event = awaitPointerEvent(pass = pass)
                    val areTwoPointersActive = event.changes.count { it.pressed } == 2
                    if (areTwoPointersActive) {

                        val currentZoom = event.calculateZoom()
                        // Calculate the centroid of the pointers
                        val currentCentroid = event.calculateCentroid()

                        if (!pinchActive) { // First time two pointers are active for this gesture
                            pinchActive = true
                            done = onZoomEvent(PinchToZoomEvent.Started(currentCentroid))
                        }
                        // Only emit Changed if the zoom factor has actually changed
                        if (currentZoom != lastZoomValue && !done) {
                            done =
                                onZoomEvent(PinchToZoomEvent.Changed(currentZoom, currentCentroid))
                            lastZoomValue = currentZoom
                        }
                        // Consume pointer changes if two pointers were involved in this event.
                        event.changes.forEach { it.consume() }
                    }
                    // If !areTwoPointersActive:
                    // - If pinch was active and now pointers are being lifted,
                    //   so don't send Changed. Continue to wait for all pointers to be up.
                    // - If pinchActive is true and all pointers go up, the loop terminates.
                } while (event.changes.any { it.pressed } && !done)

                if (pinchActive) {
                    onZoomEvent(PinchToZoomEvent.Ended)
                }
            }
        }
}
