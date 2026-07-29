/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.photopicker.core.selection

import android.util.Log
import android.widget.photopicker.PhotoPickerSelectionParams
import androidx.annotation.GuardedBy
import com.android.photopicker.core.configuration.PhotopickerConfiguration
import com.android.photopicker.core.selection.SelectionModifiedResult.FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED
import com.android.photopicker.core.selection.SelectionModifiedResult.FAILURE_SELECTION_LIMIT_EXCEEDED
import com.android.photopicker.core.selection.SelectionModifiedResult.SUCCESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A class which manages a ordered selection of data objects during a Photopicker session.
 *
 * [Selection] is the source of truth for the current selection state at all times. Features and
 * elements should not try to guess at selection state or maintain their own state related to
 * selection logic.
 *
 * To that end, Selection exposes its data in multiple ways, however UI elements should generally
 * collect and observe the provided flow since the APIs are subject to a [Mutex] and can suspend
 * until the lock can be acquired.
 *
 * Additionally, there is a flow exposed for classes that are interested in observing the selection
 * state as it changes over time. Since it is expected that UI elements will be listening to this
 * state, and changes to selection state will cause recomposition, it is highly recommended to use
 * the bulk APIs when updating more than one element in the selection to avoid multiple state
 * emissions since selection will emit immediately after a change is complete.
 *
 * Snapshot can be used to take a frozen state of the selection, as needed.
 *
 * @param T The type of object this selection holds.
 * @property scope A [CoroutineScope] that the flow is shared and updated in.
 * @property initialSelection A collection to include initial selection value.
 * @property configuration a collectable [StateFlow] of configuration changes.
 * @property preSelectedMedia: a collectable [StateFlow] of pre-selected media.
 * @property getItemSizeInBytes: a lambda to extract the size of the item.
 * @property isItemDisabled: a lambda to evaluate if an item can be added to the selection.
 */
class SelectionImpl<T>(
    val scope: CoroutineScope,
    val initialSelection: Collection<T>? = null,
    private val configuration: StateFlow<PhotopickerConfiguration>,
    private val preSelectedMedia: StateFlow<List<T>?>,
    private val getItemSizeInBytes: (T) -> Long = { 0L },
    private val isItemDisabled: (T) -> Boolean = { false },
) : Selection<T> {

    private val TAG = "SelectionImpl"
    // An internal mutex is used to enforce thread-safe access of the selection set.
    private val mutex = Mutex()
    private val _selection: LinkedHashSet<T> = LinkedHashSet<T>()
    private var _currentSelectionSizeInBytes: Long = 0L
    private val _flow: MutableStateFlow<Set<T>>
    override val flow: StateFlow<Set<T>>

    init {
        _flow = MutableStateFlow(_selection.toSet())

        scope.launch {
            if (initialSelection != null) {
                mutex.withLock {
                    if (addInitialBatchLocked(initialSelection)) {
                        updateFlow()
                    }
                }
            }
            // Observe the refresh of the stateFlow that holds the pre-selection media.
            // Note that this will always be null in case the intent action is anything other than
            // [MediaStore.ACTION_PICK_IMAGES].
            preSelectedMedia.collect { preSelected ->
                if (preSelected != null) {
                    Log.i(TAG, "Received notification for preGranted media.")
                    mutex.withLock {
                        if (addInitialBatchLocked(preSelected)) {
                            updateFlow()
                        }
                    }
                }
            }
        }

        flow = _flow.stateIn(scope, SharingStarted.WhileSubscribed(), initialValue = _flow.value)
    }

    /**
     * Add the requested item to the selection.
     *
     * If the item is already present in the selection, a duplicate will not be added, and it's
     * relative position in the selection will not be affected. Afterwards, will emit the new
     * selection into the exposed flow.
     *
     * @param item the item to add
     * @return [SelectionModifiedResult] of the outcome of the addition. Expected results:
     *   [SUCCESS], [FAILURE_SELECTION_LIMIT_EXCEEDED],
     *   [FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED].
     */
    @GuardedBy("mutex")
    override suspend fun add(item: T): SelectionModifiedResult {
        mutex.withLock {
            // If the item is already part of the set, just return success
            // This saves an unnecessary call to updateFlow for items that are already part of the
            // set.
            if (_selection.contains(item)) return SUCCESS

            val itemCanFit = ensureSelectionLimitLocked(/* size= */ 1)
            if (itemCanFit) {
                val itemSizeInBytes = getItemSizeInBytes(item)
                if (ensureSelectionBatchSizeBytesLimitLocked(itemSizeInBytes)) {
                    _selection.add(item)
                    _currentSelectionSizeInBytes += itemSizeInBytes
                    updateFlow()
                    return SUCCESS
                } else {
                    return FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED
                }
            } else {
                return FAILURE_SELECTION_LIMIT_EXCEEDED
            }
        }
    }

    /**
     * Adds all of the requested items to the selection. If one or more are already in the
     * selection, this will not add duplicate items. Afterwards, will emit the new selection into
     * the exposed flow.
     *
     * This method only succeeds if all of the items will fit in the current selection.
     *
     * @param items the item to add
     * @return [SelectionModifiedResult] of the outcome of the addition. Expected results:
     *   [SUCCESS], [FAILURE_SELECTION_LIMIT_EXCEEDED],
     *   [FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED].
     */
    @GuardedBy("mutex")
    override suspend fun addAll(items: Collection<T>): SelectionModifiedResult {
        mutex.withLock {
            // Filter out items already in the selection to calculate size and limits accurately
            val newItems = items.filterNot { _selection.contains(it) }
            if (newItems.isEmpty()) return SUCCESS

            val itemsCanFit = ensureSelectionLimitLocked(newItems.size)
            if (itemsCanFit) {
                val additionalSizeInBytes = newItems.sumOf { getItemSizeInBytes(it) }
                if (ensureSelectionBatchSizeBytesLimitLocked(additionalSizeInBytes)) {
                    _selection.addAll(newItems)
                    _currentSelectionSizeInBytes += additionalSizeInBytes
                    updateFlow()
                    return SUCCESS
                } else {
                    return FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED
                }
            } else {
                return FAILURE_SELECTION_LIMIT_EXCEEDED
            }
        }
    }

    /** Empties the current selection of objects, returning the selection to an empty state. */
    @GuardedBy("mutex")
    override suspend fun clear() {
        mutex.withLock {
            _selection.clear()
            _currentSelectionSizeInBytes = 0L
            updateFlow()
        }
    }

    /** @return Whether the selection contains the requested item. */
    @GuardedBy("mutex")
    override suspend fun contains(item: T): Boolean {
        return mutex.withLock { _selection.contains(item) }
    }

    /** @return Whether the selection currently contains all of the requested items. */
    @GuardedBy("mutex")
    override suspend fun containsAll(items: Collection<T>): Boolean {
        return mutex.withLock { _selection.containsAll(items) }
    }

    /**
     * Fetches the 0-based position of the item in the selection.
     *
     * @return The position (index) of the item in the selection list. Will return -1 if the item is
     *   not present in the selection.
     */
    @GuardedBy("mutex")
    override suspend fun getPosition(item: T): Int {
        return mutex.withLock { _selection.indexOf(item) }
    }

    /**
     * Removes the requested item from the selection. If the item is not in the selection, this has
     * no effect. Afterwards, will emit the new selection into the exposed flow.
     *
     * @return [SelectionModifiedResult] of the outcome of the removal.
     */
    @GuardedBy("mutex")
    override suspend fun remove(item: T): SelectionModifiedResult {
        mutex.withLock {
            if (_selection.remove(item)) {
                _currentSelectionSizeInBytes -= getItemSizeInBytes(item)
                updateFlow()
            }
            return SUCCESS
        }
    }

    /**
     * Removes all of the items from the selection.
     *
     * If one or more items are not present in the selection, this has no effect. Afterwards, will
     * emit the new selection into the exposed flow.
     *
     * @return [SelectionModifiedResult] of the outcome of the removal.
     */
    @GuardedBy("mutex")
    override suspend fun removeAll(items: Collection<T>): SelectionModifiedResult {
        mutex.withLock {
            // Filter to only items that are actually currently selected
            // This prevents us from subtracting the size of items that aren't in the set
            val itemsToRemove = items.filter { _selection.contains(it) }

            if (itemsToRemove.isNotEmpty()) {
                val sizeToSubtract = itemsToRemove.sumOf { getItemSizeInBytes(it) }
                _selection.removeAll(itemsToRemove.toSet())
                _currentSelectionSizeInBytes -= sizeToSubtract
                updateFlow()
            }

            return SUCCESS
        }
    }

    /**
     * Take an immutable copy of the current selection. This copy is a snapshot of the current
     * selection and is not updated if the selection changes.
     *
     * @return A frozen copy of the current selection set.
     */
    @GuardedBy("mutex")
    override suspend fun snapshot(): Set<T> {
        return mutex.withLock { _selection.toSet() }
    }

    /**
     * Returns the number of elements in this collection.
     *
     * @return The number of elements.
     */
    @GuardedBy("mutex")
    override suspend fun size(): Int {
        return mutex.withLock { _selection.size }
    }

    /**
     * Toggles the requested item in the selection.
     *
     * If the item is already in the selection, it is removed. If the selection limit is 1, the new
     * item is added in place of the existing item. If the item is not in the selection, it is
     * added. Afterwards, will emit the new selection into the exposed flow.
     *
     * @param item the item to add
     * @param onSelectionLimitExceeded optional error handler if the item cannot fit into the
     *   current selection, given the current [PhotopickerConfiguration.selectionLimit]
     * @return [SelectionModifiedResult] of the outcome of the toggle.
     */
    @GuardedBy("mutex")
    override suspend fun toggle(item: T): SelectionModifiedResult {
        mutex.withLock {
            val itemSizeInBytes = getItemSizeInBytes(item)
            when {
                _selection.contains(item) -> {
                    _selection.remove(item)
                    _currentSelectionSizeInBytes -= itemSizeInBytes
                }
                configuration.value.selectionLimit == 1 -> {
                    if (ensureSelectionBatchSizeBytesLimitLocked(itemSizeInBytes)) {
                        _selection.clear()
                        _selection.add(item)
                        // selection was cleared and then the item was added
                        _currentSelectionSizeInBytes = itemSizeInBytes
                    } else {
                        return FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED
                    }
                }
                else -> {
                    val itemCanFit = ensureSelectionLimitLocked(/* size= */ 1)
                    if (itemCanFit) {
                        if (ensureSelectionBatchSizeBytesLimitLocked(itemSizeInBytes)) {
                            _selection.add(item)
                            // item was added to existing selection set
                            _currentSelectionSizeInBytes += itemSizeInBytes
                        } else {
                            return FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED
                        }
                    } else {
                        return FAILURE_SELECTION_LIMIT_EXCEEDED
                    }
                }
            }
            updateFlow()
            return SUCCESS
        }
    }

    /**
     * Toggles all of the requested items in the selection. This is the same as calling toggle(item)
     * on each item in the set, it will act on each item in the provided list independently.
     * Afterwards, will emit the new selection into the exposed flow.
     *
     * Note: Since this toggle acts on items individually, it may fail part way through if the
     * selection becomes full.
     *
     * @param items to toggle in the selection
     * @param onSelectionLimitExceeded optional error handler if the item cannot fit into the
     *   current selection, given the current [PhotopickerConfiguration.selectionLimit]
     * @return [SelectionModifiedResult] of the outcome of the toggleAll operation.
     */
    @GuardedBy("mutex")
    override suspend fun toggleAll(items: Collection<T>): SelectionModifiedResult {
        mutex.withLock {
            for (item in items) {
                val itemSizeInBytes = getItemSizeInBytes(item)
                if (_selection.contains(item)) {
                    _selection.remove(item)
                    _currentSelectionSizeInBytes -= itemSizeInBytes
                } else {
                    val itemCanFit = ensureSelectionLimitLocked(/* size= */ 1)
                    if (itemCanFit) {
                        if (ensureSelectionBatchSizeBytesLimitLocked(itemSizeInBytes)) {
                            _selection.add(item)
                            _currentSelectionSizeInBytes += itemSizeInBytes
                        } else {
                            return FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED
                        }
                    } else {
                        return FAILURE_SELECTION_LIMIT_EXCEEDED
                    }
                }
            }
            updateFlow()
            return SUCCESS
        }
    }

    /**
     * De-selection is not supported in this implementation.
     *
     * This implementation will always return an [emptySet].
     */
    override suspend fun getDeselection(): Set<T> = emptySet()

    /**
     * Internal method to add a batch of items to the selection when [SelectionImpl] object is being
     * initialized, enforcing all selection constraints.
     *
     * Items are filtered to remove disabled items, duplicates within the batch, and items already
     * present in the selection. If the resulting set of items can all fit within the selection
     * limits, they are added to the selection set, otherwise nothing will be added to the
     * selection.
     *
     * @param items The batch of items to add.
     */
    @GuardedBy("mutex")
    private suspend fun addInitialBatchLocked(items: Collection<T>): Boolean {
        // Filter out disabled items and duplicates within the list.
        val enabledUniqueItems = items.filterNot { isItemDisabled(it) }.toSet()

        // Identify only items that are not already selected.
        val newItems = enabledUniqueItems.filterNot { _selection.contains(it) }

        var hasSelectionChanged = false

        if (newItems.isNotEmpty()) {
            val newItemsCount = newItems.size
            val newItemsBytes = newItems.sumOf { getItemSizeInBytes(it) }

            if (
                ensureSelectionLimitLocked(newItemsCount) &&
                    ensureSelectionBatchSizeBytesLimitLocked(newItemsBytes)
            ) {
                hasSelectionChanged = _selection.addAll(newItems)
                _currentSelectionSizeInBytes += newItemsBytes
            }
        }

        return hasSelectionChanged
    }

    /** Internal method that snapshots the current selection and emits it to the exposed flow. */
    private suspend fun updateFlow() {
        _flow.update { _selection.toSet() }
    }

    /**
     * Method for checking if the given [size] will fit in the current selection, considering the
     * [PhotopickerConfiguration.selectionLimit].
     *
     * IMPORTANT: This method should always be checked after acquiring the [Mutex] selection lock
     * but prior adding any items to the selection.
     *
     * @return true if the item can fit in the selection. false otherwise.
     */
    private suspend fun ensureSelectionLimitLocked(size: Int): Boolean {
        return _selection.size + size <= configuration.value.selectionLimit
    }

    /**
     * Method for checking if the given [additionalSizeInBytes] will fit in the current selection,
     * considering the [PhotoPickerSelectionParams.maxSelectionBatchSizeInBytes].
     *
     * IMPORTANT: This method should always be checked after acquiring the [Mutex] selection lock
     * but prior adding any items to the selection.
     *
     * @return true if the item can fit in the selection. false otherwise.
     */
    private suspend fun ensureSelectionBatchSizeBytesLimitLocked(
        additionalSizeInBytes: Long
    ): Boolean {
        val maxBatchSize = configuration.value.selectionParams?.maxSelectionBatchSizeInBytes ?: -1L
        if (maxBatchSize == -1L) return true

        return _currentSelectionSizeInBytes + additionalSizeInBytes <= maxBatchSize
    }
}
