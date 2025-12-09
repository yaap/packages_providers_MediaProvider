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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinchToZoomTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val testTag = "zoomableBox"

    @Test
    fun emitsStartedChangedAndEndedEvents() {
        val events = mutableStateListOf<PinchToZoomEvent>()

        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier.testTag(testTag).fillMaxSize().pinchToZoom pinchToZoom@{
                        events.add(it)
                        return@pinchToZoom false
                    }
            )
        }

        val p0Start = Offset(100f, 100f)
        val p1Start = Offset(200f, 100f) // Initial distance 100, centroid (150,100)
        val p0End = Offset(50f, 100f)
        val p1End = Offset(250f, 100f) // Final distance 200, centroid (150,100). Zoom factor ~2.0

        composeTestRule.onNodeWithTag(testTag).performTouchInput {
            pinch(
                start0 = p0Start,
                end0 = p0End,
                start1 = p1Start,
                end1 = p1End,
                durationMillis = 200L,
            )
        }
        composeTestRule.waitForIdle()

        assertWithMessage(
                "Should have at least Started, one/more Changed, and Ended. " +
                    "Actual: ${events.size}, Events: ${events.joinToString()}"
            )
            .that(events.size)
            .isAtLeast(3)

        val startedEvent =
            events.first { it is PinchToZoomEvent.Started } as PinchToZoomEvent.Started
        assertWithMessage("Started event offset should not be null")
            .that(startedEvent.offset)
            .isNotNull()

        val changedEvents = events.filterIsInstance<PinchToZoomEvent.Changed>()
        assertWithMessage("There should be at least one Changed event")
            .that(changedEvents)
            .isNotEmpty()

        val initialChangedEvent = changedEvents.first()
        assertWithMessage("Initial Changed event offset should not be null")
            .that(initialChangedEvent.offset)
            .isNotNull()

        val lastChangedEvent = changedEvents.last()
        assertWithMessage("Last Changed event offset should not be null")
            .that(lastChangedEvent.offset)
            .isNotNull()

        assertWithMessage(
                "Zoom value should be > 1.0f for zooming in. Actual: ${lastChangedEvent.value}"
            )
            .that(lastChangedEvent.value)
            .isGreaterThan(1.0f)

        val endedEvent = events.last { it is PinchToZoomEvent.Ended }
        assertWithMessage("Last event should be Ended")
            .that(endedEvent)
            .isInstanceOf(PinchToZoomEvent.Ended::class.java)
    }

    @Test
    fun singlePointerGesture_doesNotEmitPinchEvents() {
        val events = mutableStateListOf<PinchToZoomEvent>()

        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier.testTag(testTag).fillMaxSize().pinchToZoom pinchToZoom@{
                        events.add(it)
                        return@pinchToZoom false
                    }
            )
        }

        composeTestRule.onNodeWithTag(testTag).performTouchInput {
            down(0, Offset(50f, 50f))
            advanceEventTime(50)
            moveTo(0, Offset(100f, 100f), delayMillis = 100L)
            advanceEventTime(50)
            up(0)
            advanceEventTime(50)
        }
        composeTestRule.waitForIdle()

        assertWithMessage(
                "No events should be emitted for a single pointer gesture. Found: " +
                    "${events.joinToString()}"
            )
            .that(events)
            .isEmpty()
    }

    @Test
    fun twoPointersDownAndUp_withoutMove_emitsEvents() {
        val events = mutableStateListOf<PinchToZoomEvent>()

        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier.testTag(testTag).fillMaxSize().pinchToZoom pinchToZoom@{
                        events.add(it)
                        return@pinchToZoom false
                    }
            )
        }

        val pointer1Pos = Offset(50f, 50f)
        val pointer2Pos = Offset(150f, 50f) // Centroid (100,50)

        composeTestRule.onNodeWithTag(testTag).performTouchInput {
            down(0, pointer1Pos)
            down(1, pointer2Pos)
            advanceEventTime(100) // Time for Started and initial Changed
            up(0)
            up(1)
            advanceEventTime(100) // Time for Ended
        }
        composeTestRule.waitForIdle()

        assertWithMessage(
                "Should emit Started, Changed, and Ended. Events: ${events.joinToString()}"
            )
            .that(events)
            .hasSize(3)

        val startedEvent = events[0] as PinchToZoomEvent.Started
        assertThat(startedEvent.offset).isNotNull()

        val changedEvent = events[1] as PinchToZoomEvent.Changed
        assertThat(changedEvent.offset).isNotNull()
        assertWithMessage("Zoom value for no move should be 1.0f")
            .that(changedEvent.value)
            .isEqualTo(1.0f)

        assertThat(events[2]).isInstanceOf(PinchToZoomEvent.Ended::class.java)
    }

    @Test
    fun centroidMoves_offsetChangesInChangedEvent() {
        val events = mutableStateListOf<PinchToZoomEvent>()

        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier.testTag(testTag).fillMaxSize().pinchToZoom pinchToZoom@{
                        events.add(it)
                        return@pinchToZoom false
                    }
            )
        }

        val p1Start = Offset(50f, 100f) // Initial centroid: ((50+150)/2, (100+100)/2) = (100,100)
        val p2Start = Offset(150f, 100f) // Initial distance: 100

        val p1End = Offset(75f, 150f) // Final centroid: ((75+225)/2, (150+150)/2) = (150,150)
        val p2End = Offset(225f, 150f) // Final distance: 150. Zoom factor = 1.5

        composeTestRule.onNodeWithTag(testTag).performTouchInput {
            pinch(
                start0 = p1Start,
                end0 = p1End,
                start1 = p2Start,
                end1 = p2End,
                durationMillis = 200L,
            )
            advanceEventTime(100)
        }
        composeTestRule.waitForIdle()

        assertWithMessage(
                "Should have at least Started, Changed, Ended. Actual: ${events.size}, Events: ${events.joinToString()}"
            )
            .that(events.size)
            .isAtLeast(3)

        val startedEvent =
            events.first { it is PinchToZoomEvent.Started } as PinchToZoomEvent.Started
        assertWithMessage("Started event centroid mismatch").that(startedEvent.offset).isNotNull()

        val changedEvents = events.filterIsInstance<PinchToZoomEvent.Changed>()
        assertWithMessage("Should have at least one Changed event.")
            .that(changedEvents)
            .isNotEmpty()

        val lastChangedEvent = changedEvents.last()
        assertWithMessage(
                "Moved Changed event centroid mismatch. Actual: ${lastChangedEvent.offset}"
            )
            .that(lastChangedEvent.offset)
            .isEqualTo(Offset(150f, 150f))

        assertWithMessage("Zoom factor should be > 1.0. Actual: ${lastChangedEvent.value}")
            .that(lastChangedEvent.value)
            .isGreaterThan(1.0f)

        assertThat(events.last()).isInstanceOf(PinchToZoomEvent.Ended::class.java)
    }

    @Test
    fun zoomOut_emitsCorrectEvents() {
        val events = mutableStateListOf<PinchToZoomEvent>()

        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier.testTag(testTag).fillMaxSize().pinchToZoom pinchToZoom@{
                        events.add(it)
                        return@pinchToZoom false
                    }
            )
        }

        // Initial: Centroid (150,100), Distance 200
        val p0Start = Offset(50f, 100f)
        val p1Start = Offset(250f, 100f)
        // Final: Centroid (150,100), Distance 100. Zoom factor ~0.5
        val p0End = Offset(100f, 100f)
        val p1End = Offset(200f, 100f)

        composeTestRule.onNodeWithTag(testTag).performTouchInput {
            pinch(
                start0 = p0Start,
                end0 = p0End,
                start1 = p1Start,
                end1 = p1End,
                durationMillis = 200L,
            )
        }
        composeTestRule.waitForIdle()

        assertWithMessage(
                "Should have at least Started, one/more Changed, and Ended. " +
                    "Actual: ${events.size}, Events: ${events.joinToString()}"
            )
            .that(events.size)
            .isAtLeast(3)

        val startedEvent =
            events.first { it is PinchToZoomEvent.Started } as PinchToZoomEvent.Started
        assertWithMessage("Started event offset should not be null")
            .that(startedEvent.offset)
            .isNotNull()

        val changedEvents = events.filterIsInstance<PinchToZoomEvent.Changed>()
        assertWithMessage("There should be at least one Changed event")
            .that(changedEvents)
            .isNotEmpty()

        val lastChangedEvent = changedEvents.last()
        assertWithMessage("Last Changed event offset should not be null")
            .that(lastChangedEvent.offset)
            .isNotNull()

        assertWithMessage(
                "Zoom value should be < 1.0f for zooming out. Actual: ${lastChangedEvent.value}"
            )
            .that(lastChangedEvent.value)
            .isLessThan(1.0f)

        val endedEvent = events.last { it is PinchToZoomEvent.Ended }
        assertWithMessage("Last event should be Ended")
            .that(endedEvent)
            .isInstanceOf(PinchToZoomEvent.Ended::class.java)
    }

    @Test
    fun onZoomEventReturnsTrue_terminatesEarly() {
        val events = mutableStateListOf<PinchToZoomEvent>()
        var changedEventCount = 0

        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier.testTag(testTag).fillMaxSize().pinchToZoom pinchToZoom@{
                        events.add(it)
                        if (it is PinchToZoomEvent.Changed) {
                            changedEventCount++
                            // Return true after the first Changed event
                            return@pinchToZoom true
                        }
                        return@pinchToZoom false
                    }
            )
        }

        val p0Start = Offset(100f, 100f)
        val p1Start = Offset(200f, 100f) // Initial distance 100
        val p0End = Offset(50f, 100f)
        val p1End =
            Offset(250f, 100f) // Final distance 200 (would normally cause more Changed events)

        composeTestRule.onNodeWithTag(testTag).performTouchInput {
            pinch(
                start0 = p0Start,
                end0 = p0End,
                start1 = p1Start,
                end1 = p1End,
                durationMillis = 400L, // Longer duration to ensure multiple move events could occur
            )
        }
        composeTestRule.waitForIdle()

        assertWithMessage(
                "Should have exactly Started, one Changed, and Ended. " +
                    "Actual: ${events.size}, Events: ${events.joinToString()}"
            )
            .that(events.size)
            .isEqualTo(3)

        assertWithMessage("First event should be Started")
            .that(events[0])
            .isInstanceOf(PinchToZoomEvent.Started::class.java)

        assertWithMessage("Second event should be Changed")
            .that(events[1])
            .isInstanceOf(PinchToZoomEvent.Changed::class.java)

        assertWithMessage("Only one Changed event should be received")
            .that(changedEventCount)
            .isEqualTo(1)

        assertWithMessage("Third event should be Ended")
            .that(events[2])
            .isInstanceOf(PinchToZoomEvent.Ended::class.java)
    }

    @Test
    fun onZoomEventReturnsTrueOnStart_terminatesEarlyAndEmitsEnd() {
        val events = mutableStateListOf<PinchToZoomEvent>()

        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier.testTag(testTag).fillMaxSize().pinchToZoom pinchToZoom@{
                        events.add(it)
                        // Terminate the gesture if the event is Started.
                        if (it is PinchToZoomEvent.Started) {
                            return@pinchToZoom true
                        }
                        return@pinchToZoom false
                    }
            )
        }

        // A standard pinch gesture that would normally generate multiple events.
        val p0Start = Offset(100f, 100f)
        val p1Start = Offset(200f, 100f)
        val p0End = Offset(50f, 100f)
        val p1End = Offset(250f, 100f)

        composeTestRule.onNodeWithTag(testTag).performTouchInput {
            pinch(
                start0 = p0Start,
                end0 = p0End,
                start1 = p1Start,
                end1 = p1End,
                durationMillis = 200L,
            )
        }
        composeTestRule.waitForIdle()

        assertWithMessage(
                "Should have exactly Started and Ended events. " +
                    "Actual: ${events.size}, Events: ${events.joinToString()}"
            )
            .that(events.size)
            .isEqualTo(2)

        assertWithMessage("First event should be Started")
            .that(events[0])
            .isInstanceOf(PinchToZoomEvent.Started::class.java)

        assertWithMessage("No Changed event should be emitted")
            .that(events.filterIsInstance<PinchToZoomEvent.Changed>())
            .isEmpty()

        assertWithMessage("Second event should be Ended")
            .that(events[1])
            .isInstanceOf(PinchToZoomEvent.Ended::class.java)
    }
}
