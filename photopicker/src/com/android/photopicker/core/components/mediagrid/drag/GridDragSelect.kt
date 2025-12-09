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

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import androidx.paging.compose.LazyPagingItems
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.data.model.Media
import com.android.photopicker.extensions.getItemPosition
import com.android.photopicker.extensions.itemIndexAtPosition
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A [Modifier] that enables drag-to-select functionality on a grid of items.
 *
 * It detects a long press followed by a drag gesture to select a range of items. This modifier
 * handles auto-scrolling when the drag gesture nears the grid's edges and can provide haptic
 * feedback upon initiating the drag selection.
 *
 * @param config The current [PhotopickerConfiguration].
 * @param items The [LazyPagingItems] source for the grid.
 * @param state The [GridDragSelectState] to manage and observe the selection state.
 * @param windowRect Optional [Rect] defining the window bounds. If provided, auto-scroll
 *   calculations will be relative to these bounds. If null, calculations are based on the grid's
 *   viewport.
 * @param enableAutoScroll If true, the grid will automatically scroll when dragging near its edges.
 * @param autoScrollThreshold The distance (in pixels) from an edge at which auto-scrolling begins.
 * @param autoScrollOrientation The orientation of scrolling (Vertical or Horizontal).
 * @param hapticFeedback An optional [HapticFeedback] instance for haptic control. If null, the
 *   haptics will be disabled.
 * @param indexOffset An integer offset applied to item indices. This is useful if the grid includes
 *   non-selectable header items, allowing the selection logic to correctly map to the [items]
 *   collection.
 * @param selectionTransform A transform that is called on all Media items before they are added to
 *   the selection. This can be used to set metrics fields see [Media.withSelectable]
 * @return A [Modifier] chain including the [GridDragSelectElement].
 */
fun Modifier.onGridDragSelect(
    config: PhotopickerConfiguration,
    items: LazyPagingItems<MediaGridItem>,
    state: GridDragSelectState,
    windowRect: Rect? = null,
    enableAutoScroll: Boolean = true,
    autoScrollThreshold: Float,
    autoScrollOrientation: ScrollOrientation = ScrollOrientation.VERTICAL,
    hapticFeedback: HapticFeedback? = null,
    indexOffset: Int = 0,
    selectionTransform: (Media) -> Media = { it },
) =
    this then
        GridDragSelectElement(
            config,
            items,
            state,
            windowRect,
            enableAutoScroll,
            autoScrollThreshold,
            autoScrollOrientation,
            hapticFeedback,
            indexOffset,
            selectionTransform,
        )

/**
 * A [Modifier.Node] that handles drag selection gestures on a grid.
 *
 * This node detects long press followed by drag gestures to enable range selection of items in a
 * [LazyPagingItems] collection displayed in a grid. It supports auto-scrolling when the drag
 * gesture approaches the edges of the grid and can provide haptic feedback.
 *
 * @property config The [PhotopickerConfiguration] for the photopicker.
 * @property items The [LazyPagingItems] containing the grid items.
 * @property state The [GridDragSelectState] that manages the state of the drag selection.
 * @property windowRect The [Rect] representing the window's bounds, used for auto-scroll
 *   calculations. If null, auto-scroll will use the grid's viewport.
 * @property enableAutoScroll Whether to enable auto-scrolling when dragging near the edges.
 * @property autoScrollThreshold The distance from the edge (in pixels) at which auto-scrolling
 *   should start.
 * @property autoScrollOrientation The orientation of scrolling (Vertical or Horizontal) for
 *   auto-scroll.
 * @property hapticFeedback An optional [HapticFeedback] instance to perform haptics.
 * @property indexOffset An integer offset applied to item indices. This is useful if the grid
 *   includes non-selectable header items, allowing the selection logic to correctly map to the
 *   [items] collection. items.
 * @property selectionTransform A transform that is called on all Media items before they are added
 *   to the selection. This can be used to set metrics fields see [Media.withSelectable]
 */
class GridDragSelectNode(
    var config: PhotopickerConfiguration,
    var items: LazyPagingItems<MediaGridItem>,
    var state: GridDragSelectState,
    var windowRect: Rect?,
    var enableAutoScroll: Boolean,
    var autoScrollThreshold: Float,
    var autoScrollOrientation: ScrollOrientation,
    var hapticFeedback: HapticFeedback?,
    var indexOffset: Int,
    var selectionTransform: (Media) -> Media,
) : GlobalPositionAwareModifierNode, PointerInputModifierNode, DelegatingNode() {

    /**
     * Stores the [LayoutCoordinates] of the composable this modifier is attached to. This is
     * initialized lazily via [onGloballyPositioned] because the coordinates are only available
     * after layout. It's an [AtomicReference] to ensure thread-safe updates if
     * [onGloballyPositioned] were to be called from different threads, though typically it's called
     * on the main thread.
     */
    lateinit var currentCoordinates: AtomicReference<LayoutCoordinates>

    /**
     * Handles pointer input events to detect drag gestures after a long press. This is the core
     * logic for initiating and processing the drag-to-select gesture.
     */
    val pointerInputEventHandler: PointerInputEventHandler = PointerInputEventHandler {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                // Attempt to find the item index at the drag start position.
                state.gridState.itemIndexAtPosition(offset)?.minus(indexOffset)?.let { startIndex ->
                    val item =
                        try {
                            items.peek(startIndex)
                        } catch (_: Exception) {
                            // Prevent crashes if the item cannot be accessed for any reason,
                            // and just return null.
                            null
                        }
                    item?.let {
                        when (it) {
                            is MediaGridItem.MediaItem -> {
                                // Start the drag operation.
                                coroutineScope.launch {
                                    state.startDrag(startIndex) {
                                        // Perform haptic feedback if enabled.
                                        hapticFeedback?.performHapticFeedback(
                                            HapticFeedbackType.LongPress
                                        )
                                        // Add the initially selected item.
                                        runBlocking {
                                            state.selection.add(selectionTransform(it.media))
                                        }
                                    }
                                }
                            }
                            // Do nothing for non-media items (e.g., headers, placeholders).
                            else -> {}
                        }
                    }
                }
            },
            onDragCancel = state::stopDrag, // Stop drag on cancellation.
            onDragEnd = state::stopDrag, // Stop drag on gesture end.
            onDrag = { change, _ ->
                state.whenDragging { // Execute only if a drag is in progress.

                    // Calculate auto-scroll speed while the selection isn't full.
                    autoScrollSpeed.value =
                        if (runBlocking { selection.size() } < config.selectionLimit) {
                            val localWindowRect = windowRect
                            if (localWindowRect != null) {
                                gridState.calculateScrollSpeed(
                                    change = change,
                                    scrollThreshold = autoScrollThreshold,
                                    scrollOrientation = autoScrollOrientation,
                                    currentCoordinates = currentCoordinates.get(),
                                    windowRect = localWindowRect,
                                )
                            } else {
                                gridState.calculateScrollSpeed(
                                    change,
                                    autoScrollThreshold,
                                    autoScrollOrientation,
                                )
                            }
                        } else {
                            0f // Disable auto-scroll if selection is full.
                        }

                    // Determine the current item index under the drag pointer.
                    val currentItemIndex =
                        gridState
                            .getItemPosition(change.position)
                            ?.minus(indexOffset)
                            // Ensure no negative index access
                            ?.coerceIn(0, Int.MAX_VALUE)
                            ?: return@whenDragging // Exit if no item found

                    val previousItemIndex = dragState.current
                    val initialItemIndex = dragState.initial

                    // If the pointer hasn't moved to a new item index, do nothing.
                    if (currentItemIndex == previousItemIndex) return@whenDragging

                    // Determine the initial drag direction if it hasn't been set yet.
                    if (direction.value == DragDirection.UNSET) {
                        direction.value =
                            when {
                                currentItemIndex > initialItemIndex -> DragDirection.POSITIVE
                                currentItemIndex < initialItemIndex -> DragDirection.NEGATIVE
                                else -> DragDirection.UNSET // Started and ended on the same item
                            }
                    }

                    // Calculate the range of items based on the initial and current drag
                    // indices.
                    val targetSelectionStart = minOf(initialItemIndex, currentItemIndex)
                    val targetSelectionEnd = maxOf(initialItemIndex, currentItemIndex)
                    val targetMedia = items.getMediaSlice(targetSelectionStart, targetSelectionEnd)

                    // Calculate the range of items based on the initial and previous drag
                    // indices.
                    val previousSelectionStart = minOf(initialItemIndex, previousItemIndex)
                    val previousSelectionEnd = maxOf(initialItemIndex, previousItemIndex)
                    val previousMedia =
                        items.getMediaSlice(previousSelectionStart, previousSelectionEnd)

                    // Determine which items need to be added or removed.
                    val itemsToAdd = targetMedia
                    val itemsToRemove = previousMedia - targetMedia

                    // Block while modifying the selection, the selection API requires mutexes which
                    // suspends while waiting.
                    runBlocking {
                        // Update the selection state.
                        for (item in itemsToAdd) {
                            selection.add(selectionTransform(item))
                        }
                        selection.removeAll(itemsToRemove)
                    }

                    // Check if the drag direction needs resetting (crossed back over the
                    // initial item).
                    if (
                        (direction.value == DragDirection.POSITIVE &&
                            currentItemIndex < initialItemIndex) ||
                            (direction.value == DragDirection.NEGATIVE &&
                                currentItemIndex > initialItemIndex)
                    ) {
                        direction.value = DragDirection.UNSET
                    } else if (currentItemIndex == initialItemIndex) {
                        // Reset if we land back exactly on the initial item
                        direction.value = DragDirection.UNSET
                    }

                    // Update the current drag position in the state.
                    updateDrag(current = currentItemIndex)
                }
            },
        )
    }

    /** Delegates pointer input handling to [SuspendingPointerInputModifierNode]. */
    val delegateNode = delegate(SuspendingPointerInputModifierNode(pointerInputEventHandler))

    /**
     * Called when the node is attached to a composable. If auto-scroll is enabled, it launches a
     * coroutine to continuously scroll the grid based on [GridDragSelectState.autoScrollSpeed].
     */
    override fun onAttach() {
        if (enableAutoScroll) {
            coroutineScope.launch {
                while (isActive) { // Loop while the coroutine is active.
                    if (state.autoScrollSpeed.value != 0f) {
                        // Scroll the grid by the calculated auto-scroll speed.
                        state.gridState.scrollBy(state.autoScrollSpeed.value)
                    }
                    delay(10) // Delay to control scroll frequency.
                }
            }
        }
    }

    /**
     * Called when the global position of the composable changes. Updates [currentCoordinates] with
     * the new [LayoutCoordinates].
     *
     * @param coordinates The new [LayoutCoordinates] of the composable.
     */
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        // Initialize or update the currentCoordinates.
        if (::currentCoordinates.isInitialized) {
            currentCoordinates.set(coordinates)
        } else {
            currentCoordinates = AtomicReference(coordinates)
        }
    }

    /**
     * Intercepts pointer events and delegates them to [delegateNode]. This is part of the
     * [PointerInputModifierNode] interface implementation.
     *
     * @param pointerEvent The [PointerEvent] that occurred.
     * @param pass The [PointerEventPass] for this event.
     * @param bounds The [IntSize] bounds of the input.
     */
    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        delegateNode.onPointerEvent(pointerEvent, pass, bounds)
    }

    /**
     * Called when pointer input is cancelled, delegating to [delegateNode]. This is part of the
     * [PointerInputModifierNode] interface implementation.
     */
    override fun onCancelPointerInput() {
        delegateNode.onCancelPointerInput()
    }
}

/**
 * A [ModifierNodeElement] that creates and updates a [GridDragSelectNode]. This class is
 * responsible for instantiating and providing the necessary parameters to the [GridDragSelectNode].
 *
 * @property config The current [PhotopickerConfiguration].
 * @property items The [LazyPagingItems] containing the grid items.
 * @property state The [GridDragSelectState] that manages the state of the drag selection.
 * @property windowRect The [Rect] representing the window's bounds for auto-scroll.
 * @property enableAutoScroll Whether auto-scrolling is enabled.
 * @property autoScrollThreshold The threshold for triggering auto-scroll.
 * @property autoScrollOrientation The orientation of scrolling (Vertical or Horizontal) for
 *   auto-scroll.
 * @property hapticFeedback The [HapticFeedback] instance.
 * @property indexOffset An integer offset applied to item indices. This is useful if the grid
 *   includes non-selectable header items, allowing the selection logic to correctly map to the
 *   [items] collection. items.
 * @property selectionTransform A transform that is called on all Media items before they are added
 *   to the selection. This can be used to set metrics fields see [Media.withSelectable]
 */
data class GridDragSelectElement(
    val config: PhotopickerConfiguration,
    val items: LazyPagingItems<MediaGridItem>,
    val state: GridDragSelectState,
    val windowRect: Rect?,
    val enableAutoScroll: Boolean,
    val autoScrollThreshold: Float,
    val autoScrollOrientation: ScrollOrientation,
    val hapticFeedback: HapticFeedback?,
    val indexOffset: Int,
    val selectionTransform: (Media) -> Media,
) : ModifierNodeElement<GridDragSelectNode>() {

    /**
     * Creates a new instance of [GridDragSelectNode].
     *
     * @return The created [GridDragSelectNode].
     */
    override fun create(): GridDragSelectNode {
        return GridDragSelectNode(
            config,
            items,
            state,
            windowRect,
            enableAutoScroll,
            autoScrollThreshold,
            autoScrollOrientation,
            hapticFeedback,
            indexOffset,
            selectionTransform,
        )
    }

    /**
     * Updates an existing [GridDragSelectNode] with new parameters. This is called when the inputs
     * to the modifier change.
     *
     * @param node The [GridDragSelectNode] to update.
     */
    override fun update(node: GridDragSelectNode) {
        node.config = config
        node.items = items
        node.state = state
        node.windowRect = windowRect
        node.enableAutoScroll = enableAutoScroll
        node.autoScrollThreshold = autoScrollThreshold
        node.autoScrollOrientation = autoScrollOrientation
        node.hapticFeedback = hapticFeedback
        node.indexOffset = indexOffset
        node.selectionTransform = selectionTransform
    }
}

/**
 * Retrieves a set of [Media] objects from a slice of a [LazyPagingItems] list of [MediaGridItem]
 * objects.
 *
 * This function iterates through the [LazyPagingItems] list within the specified range `[fromIndex,
 * toIndex]` (inclusive). For each [MediaGridItem] within this range, if the item is a
 * [MediaGridItem.MediaItem] and its index is not in the [excludes] set, the associated [Media]
 * object is extracted and added to a [Set]. Other types of [MediaGridItem] are ignored.
 *
 * @param fromIndex The starting index (inclusive) of the slice within the [LazyPagingItems] list.
 * @param toIndex The ending index (inclusive) of the slice within the [LazyPagingItems] list.
 * @return A [Set] containing the [Media] objects extracted from the [MediaGridItem.MediaItem]
 *   elements within the specified range of the [LazyPagingItems] list, excluding specified indices.
 *   The set is empty if no eligible [MediaGridItem.MediaItem] elements are found within the range,
 *   or if the range is invalid.
 */
private fun LazyPagingItems<MediaGridItem>.getMediaSlice(fromIndex: Int, toIndex: Int): Set<Media> {
    val targets = mutableSetOf<Media>()
    // Ensure fromIndex is not greater than toIndex
    val start = minOf(fromIndex, toIndex)
    val end = maxOf(fromIndex, toIndex)

    for (pos in start..end) {
        val item =
            try {
                this.peek(pos) // Don't trigger paging loads by getting the item
            } catch (_: Exception) {
                // Prevent crashes when the item cannot be accessed at the requested index, and
                // instead do nothing.
                null
            }
        item?.let {
            when (it) {
                is MediaGridItem.MediaItem -> {
                    targets.add(it.media) // Add media if the item is a MediaItem
                }
                else -> {} // Ignore other item types (e.g., headers)
            }
        }
    }
    return targets
}

/**
 * Calculates the scroll speed based on the pointer's position relative to the scroll thresholds.
 *
 * This function determines how fast and in which direction the `LazyGridState` should scroll
 * automatically when a pointer input change occurs near the edges of the scrollable area. The speed
 * is proportional to how close the pointer is to the edge, within the defined threshold.
 *
 * @param change The details of the pointer input change event.
 * @param scrollThreshold The distance from the edge within which auto-scrolling should activate.
 * @param scrollOrientation The orientation of scrolling (Vertical or Horizontal).
 * @param currentCoordinates The layout coordinates of the composable receiving the pointer input.
 * @param windowRect The rectangle representing the boundaries of the window.
 * @return The calculated scroll speed. Positive values indicate scrolling down or right, negative
 *   values indicate scrolling up or left, and 0f indicates no auto-scroll.
 */
private fun LazyGridState.calculateScrollSpeed(
    change: PointerInputChange,
    scrollThreshold: Float,
    scrollOrientation: ScrollOrientation,
    currentCoordinates: LayoutCoordinates,
    windowRect: Rect,
): Float {

    return when (scrollOrientation) {
        ScrollOrientation.VERTICAL -> {
            // Distance from the top edge of the window.
            val distanceFromTop: Float = change.position.y
            // Distance from the bottom edge of the window.
            val distanceFromBottom: Float =
                windowRect.height - currentCoordinates.localToWindow(change.position).y

            when {
                // If near the bottom edge of the window, scroll down. Speed increases closer to the
                // edge.
                distanceFromBottom < scrollThreshold -> scrollThreshold - distanceFromBottom
                // If near the top edge of the window, scroll up. Speed increases closer to the
                // edge.
                distanceFromTop < scrollThreshold -> -(scrollThreshold - distanceFromTop)
                // Otherwise, no auto-scroll is needed.
                else -> 0f
            }
        }
        ScrollOrientation.HORIZONTAL -> {
            val distanceFromLeft: Float = change.position.x
            val distanceFromRight: Float = layoutInfo.viewportSize.width - change.position.x
            when {
                distanceFromLeft < scrollThreshold -> -(scrollThreshold - distanceFromLeft)
                distanceFromRight < scrollThreshold -> scrollThreshold - distanceFromRight
                else -> 0f
            }
        }
    }
}

/**
 * Calculates the scroll speed based on the pointer's position relative to the grid edges.
 *
 * @param change The pointer input change event.
 * @param scrollThreshold The distance from the edge where scrolling should start.
 * @param scrollOrientation The orientation of the scroll (Vertical or Horizontal).
 * @return The calculated scroll speed. Positive for scrolling down/right, negative for scrolling
 *   up/left.
 */
private fun LazyGridState.calculateScrollSpeed(
    change: PointerInputChange,
    scrollThreshold: Float,
    scrollOrientation: ScrollOrientation,
): Float {
    return when (scrollOrientation) {
        ScrollOrientation.VERTICAL -> {
            // Drag pointer's Y position relative to the grid composable.
            val distanceFromTop: Float = change.position.y
            // Distance from the bottom edge of the grid's viewport.
            val distanceFromBottom: Float = layoutInfo.viewportSize.height - distanceFromTop
            when {
                // If near the bottom edge of the viewport, scroll down.
                distanceFromBottom < scrollThreshold -> scrollThreshold - distanceFromBottom
                // If near the top edge of the viewport, scroll up.
                distanceFromTop < scrollThreshold -> -(scrollThreshold - distanceFromTop)
                // Otherwise, no auto-scroll.
                else -> 0f
            }
        }
        ScrollOrientation.HORIZONTAL -> {
            val distanceFromLeft: Float = change.position.x
            val distanceFromRight: Float = layoutInfo.viewportSize.width - change.position.x
            when {
                distanceFromLeft < scrollThreshold -> -(scrollThreshold - distanceFromLeft)
                distanceFromRight < scrollThreshold -> scrollThreshold - distanceFromRight
                else -> 0f
            }
        }
    }
}
