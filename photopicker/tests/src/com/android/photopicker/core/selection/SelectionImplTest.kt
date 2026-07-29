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

import android.widget.photopicker.PhotoPickerSelectionParams
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.photopicker.core.configuration.TestPhotopickerConfiguration
import com.android.photopicker.core.configuration.provideTestConfigurationFlow
import com.android.photopicker.core.selection.SelectionImplTest.Companion.ITEM_SIZE_10
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionImplTest {

    private companion object {
        const val ITEM_SIZE_10 = 10L
        const val ITEM_SIZE_20 = 20L
        const val BATCH_SIZE_LIMIT_15 = 15L
        const val BATCH_SIZE_LIMIT_20 = 20L
        const val BATCH_SIZE_LIMIT_25 = 25L
        const val NO_BATCH_SIZE_LIMIT = -1L
    }

    /** A sample data class used only for testing. */
    private data class SelectionData(val id: Int)

    private val INITIAL_SELECTION =
        buildSet<SelectionData> {
            for (i in 1..10) {
                add(SelectionData(id = i))
            }
        }

    private val testPreSelectionMediaData = MutableStateFlow(ArrayList<SelectionData>())

    /** Ensures the selection is initialized as empty when no items are provided. */
    @Test
    fun testSelectionIsEmptyByDefault() = runTest {
        val selection = createSelection(selectionLimit = 1)
        val snapshot = selection.snapshot()

        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        assertWithMessage("Snapshot was expected to be empty.").that(snapshot).isEmpty()
        assertWithMessage("Emitted flow was expected to be empty.")
            .that(selection.flow.first())
            .isEmpty()
    }

    /** Ensures the selection is initialized with the provided items. */
    @Test
    fun testSelectionIsInitialized() = runTest {
        val selection = createSelection(initialSelection = INITIAL_SELECTION)

        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val snapshot = selection.snapshot()
        val flow = selection.flow.first()

        assertWithMessage("Snapshot was expected to contain the initial selection")
            .that(snapshot)
            .isEqualTo(INITIAL_SELECTION)
        assertWithMessage("Snapshot has an unexpected size").that(snapshot).hasSize(10)

        assertWithMessage("Emitted flow was expected to contain the initial selection")
            .that(flow)
            .isEqualTo(INITIAL_SELECTION)
        assertWithMessage("Emitted flow has an unexpected size").that(flow).hasSize(10)
    }

    @Test
    fun testInitialSelectionExceedsCountLimit() = runTest {
        val selection =
            createSelection(
                selectionLimit = 5,
                initialSelection = INITIAL_SELECTION, // has 10 items
            )

        assertWithMessage("Selection should be empty if initial selection exceeds limit")
            .that(selection.snapshot())
            .isEmpty()
    }

    @Test
    fun testInitialSelectionExceedsByteLimit() = runTest {
        val selection =
            createSelection(
                maxBatchSize = BATCH_SIZE_LIMIT_25,
                itemSize = ITEM_SIZE_10,
                initialSelection =
                    setOf(SelectionData(1), SelectionData(2), SelectionData(3)), // 30 bytes
            )

        assertWithMessage("Selection should be empty if initial selection exceeds byte limit")
            .that(selection.snapshot())
            .isEmpty()
    }

    @Test
    fun testPreSelectionMediaReceived() = runTest {
        val testPreSelectionMediaData2 = MutableStateFlow(ArrayList<SelectionData>())
        val selection: Selection<SelectionData> =
            SelectionImpl(
                scope = backgroundScope,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration =
                            TestPhotopickerConfiguration.build {
                                action("")
                                selectionLimit(50)
                            },
                    ),
                preSelectedMedia = testPreSelectionMediaData2,
            )
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        advanceTimeBy(100)

        assertWithMessage("Initial snapshot state does not match expected size")
            .that(selection.snapshot())
            .hasSize(0)

        // add 2 values to preSelection
        testPreSelectionMediaData2.update { arrayListOf(SelectionData(1), SelectionData(2)) }

        assertWithMessage("Resulting snapshot does not match expected size")
            .that(selection.snapshot())
            .isEmpty()

        advanceTimeBy(100)

        assertWithMessage("Initial flow state does not match expected size")
            .that(emissions.first())
            .hasSize(0)

        // ensure that the size was incremented by 2 because of preSelected media.
        assertWithMessage("Resulting flow state does not match expected size")
            .that(emissions.last())
            .hasSize(2)
    }

    @Test
    fun testSelectionReturnsSuccess() = runTest {
        val selection = createSelection()

        assertWithMessage("Selection addition was expected to be successful: item 1")
            .that(selection.add(SelectionData(1)))
            .isEqualTo(SelectionModifiedResult.SUCCESS)
        assertWithMessage("Selection addition was expected to be successful: item 2")
            .that(selection.toggle(SelectionData(2)))
            .isEqualTo(SelectionModifiedResult.SUCCESS)
        assertWithMessage("Selection addition was expected to be successful: item 3")
            .that(selection.toggleAll(setOf(SelectionData(3))))
            .isEqualTo(SelectionModifiedResult.SUCCESS)
    }

    @Test
    fun testSelectionReturnsSelectionLimitExceededWhenFull() = runTest {
        val selection =
            createSelection(selectionLimit = 1, initialSelection = setOf(SelectionData(1)))

        assertWithMessage("Snapshot was expected to contain the initial selection")
            .that(selection.add(SelectionData(2)))
            .isEqualTo(SelectionModifiedResult.FAILURE_SELECTION_LIMIT_EXCEEDED)
    }

    /** Ensures a single item can be added to the selection. */
    @Test
    fun testSelectionCanAddSingleItem() = runTest {
        val selection = createSelection()
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val testItem = SelectionData(id = 999)
        selection.add(testItem)

        val snapshot = selection.snapshot()
        assertWithMessage("Snapshot does not contain the added item")
            .that(snapshot)
            .contains(testItem)
        assertWithMessage("Snapshot has an unexpected size").that(snapshot).hasSize(1)

        advanceTimeBy(100)

        val flow = emissions.last()
        assertWithMessage("Emitted flow value does not contain the added item.")
            .that(flow)
            .contains(testItem)
        assertWithMessage("Emitted flow has an unexpected size").that(flow).hasSize(1)
    }

    /** Ensures bulk additions. */
    @Test
    fun testSelectionCanAddMultipleItems() = runTest {
        val selection = createSelection()
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val values =
            setOf(
                SelectionData(id = 1),
                SelectionData(id = 2),
                SelectionData(id = 3),
                SelectionData(id = 4),
                SelectionData(id = 5),
                SelectionData(id = 6),
            )
        selection.addAll(values)

        advanceTimeBy(100)

        val snapshot = selection.snapshot()
        assertWithMessage("Snapshot does not contain the added items")
            .that(snapshot)
            .containsExactly(*values.toTypedArray())
        assertWithMessage("Snapshot has an unexpected size").that(snapshot).hasSize(6)

        assertWithMessage("Emitted flow does not contain the added items")
            .that(emissions.last())
            .containsExactly(*values.toTypedArray())
        assertWithMessage("Emitted flow has an unexpected size").that(emissions.last()).hasSize(6)
    }

    /** Ensures a selection can be reset. */
    @Test
    fun testSelectionCanBeCleared() = runTest {
        val selection = createSelection(initialSelection = INITIAL_SELECTION)
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        assertWithMessage("Initial snapshot state does not match expected size")
            .that(selection.snapshot())
            .hasSize(10)

        selection.clear()

        assertWithMessage("Resulting snapshot does not match expected size")
            .that(selection.snapshot())
            .isEmpty()

        advanceTimeBy(100)

        assertWithMessage("Initial flow state does not match expected size")
            .that(emissions.first())
            .hasSize(10)

        assertWithMessage("Resulting flow state does not match expected size")
            .that(emissions.last())
            .isEmpty()
    }

    /** Ensures a single item can be removed. */
    @Test
    fun testSelectionCanRemoveSingleItem() = runTest {
        val testItem = SelectionData(id = 999)
        val anotherTestItem = SelectionData(id = 1000)
        val selection = createSelection(initialSelection = setOf(testItem, anotherTestItem))
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val initialSnapshot = selection.snapshot()
        assertWithMessage("Initial Snapshot does not contain the expected item")
            .that(initialSnapshot)
            .isEqualTo(setOf(testItem, anotherTestItem))
        assertWithMessage("Initial Snapshot has an unexpected size")
            .that(initialSnapshot)
            .hasSize(2)

        selection.remove(testItem)

        val snapshot = selection.snapshot()
        assertWithMessage("Snapshot contains the removed item.")
            .that(snapshot)
            .doesNotContain(testItem)
        assertWithMessage("Snapshot has an unexpected size").that(snapshot).hasSize(1)

        advanceTimeBy(100)

        val flow = emissions.last()
        assertWithMessage("Emitted flow value contains the removed item.")
            .that(flow)
            .doesNotContain(testItem)
        assertWithMessage("Emitted flow has an unexpected size").that(flow).hasSize(1)
    }

    /** Ensures bulk removals. */
    @Test
    fun testSelectionCanRemoveMultipleItems() = runTest {
        val values =
            setOf(
                SelectionData(id = 1),
                SelectionData(id = 2),
                SelectionData(id = 3),
                SelectionData(id = 4),
                SelectionData(id = 5),
                SelectionData(id = 6),
            )

        val selection = createSelection(initialSelection = values)
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val initialSnapshot = selection.snapshot()
        assertWithMessage("Initial Snapshot has an unexpected size")
            .that(initialSnapshot)
            .hasSize(6)

        val removedValues = values.take(3)
        selection.removeAll(removedValues)

        val snapshot = selection.snapshot()
        assertWithMessage("Snapshot contains a removed item.")
            .that(snapshot)
            .containsNoneIn(removedValues.toTypedArray())
        assertWithMessage("Snapshot has an unexpected size").that(snapshot).hasSize(3)

        advanceTimeBy(100)

        val flow = emissions.last()
        assertWithMessage("Emitted flow value contains the removed item.")
            .that(flow)
            .containsNoneIn(removedValues.toTypedArray())
        assertWithMessage("Emitted flow has an unexpected size").that(flow).hasSize(3)
    }

    /** Ensures a single item can be toggled in and out of the selected set. */
    @Test
    fun testSelectionCanToggleSingleItem() = runTest {
        val selection = createSelection(initialSelection = INITIAL_SELECTION)
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val item = INITIAL_SELECTION.first()

        selection.toggle(item)

        assertWithMessage("Snapshot contained an item that should have been removed")
            .that(selection.snapshot())
            .doesNotContain(item)

        advanceTimeBy(100)
        assertWithMessage("Flow emission contained an item that should have been removed")
            .that(emissions.last())
            .doesNotContain(item)

        selection.toggle(item)

        assertWithMessage("Snapshot does not contain an item that should have been added")
            .that(selection.snapshot())
            .contains(item)

        advanceTimeBy(100)
        assertWithMessage("Flow emission does not contain an item that should have been added")
            .that(emissions.last())
            .contains(item)
    }

    /** Ensures multiple items can be toggled in and out of the selected set. */
    @Test
    fun testSelectionCanToggleMultipleItems() = runTest {
        val selection = createSelection(initialSelection = INITIAL_SELECTION)
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val items = INITIAL_SELECTION.take(3)

        selection.toggleAll(items)

        assertWithMessage("Snapshot contained an item that should have been removed")
            .that(selection.snapshot())
            .containsNoneIn(items)

        advanceTimeBy(100)
        assertWithMessage("Flow emission contained an item that should have been removed")
            .that(emissions.last())
            .containsNoneIn(items)

        selection.toggleAll(items)

        assertWithMessage("Snapshot does not contain an item that should have been added")
            .that(selection.snapshot())
            .containsAtLeastElementsIn(items)

        advanceTimeBy(100)
        assertWithMessage("Flow emission does not contain an item that should have been added")
            .that(emissions.last())
            .containsAtLeastElementsIn(items)
    }

    /** Ensures selection returns the correct position for selected items. */
    @Test
    fun testSelectionCanReturnItemPosition() = runTest {
        val values =
            listOf(
                SelectionData(id = 1),
                SelectionData(id = 2),
                SelectionData(id = 3),
                SelectionData(id = 4),
                SelectionData(id = 5),
                SelectionData(id = 6),
            )

        val selection = createSelection(initialSelection = values)

        assertWithMessage("Received unexpected position for item.")
            .that(selection.getPosition(values.get(2)))
            .isEqualTo(2)
    }

    /** Ensures selection returns -1 for items not present in the selection. */
    @Test
    fun testSelectionGetPositionForMissingItem() = runTest {
        val selection = createSelection(initialSelection = INITIAL_SELECTION)

        val missingElement = SelectionData(id = 999)

        assertWithMessage("Received unexpected position for item.")
            .that(selection.getPosition(missingElement))
            .isEqualTo(-1)
    }

    @Test
    fun testSelectionGetSize() = runTest {
        val selection = createSelection(initialSelection = INITIAL_SELECTION)

        assertWithMessage("Expected size did not match")
            .that(selection.size())
            .isEqualTo(INITIAL_SELECTION.size)

        selection.clear()

        assertWithMessage("Expected empty size did not match").that(selection.size()).isEqualTo(0)
    }

    /** Ensures toggling a new item when selection limit is 1 replaces the existing item. */
    @Test
    fun testToggleWithSelectionLimitOneReplacesExistingItem() = runTest {
        val initialItem = SelectionData(id = 1)
        val selection = createSelection(selectionLimit = 1, initialSelection = setOf(initialItem))
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val newItem = SelectionData(id = 2)

        assertWithMessage("Initial selection should contain the initial item")
            .that(selection.snapshot())
            .contains(initialItem)
        assertWithMessage("Initial selection size should be 1").that(selection.size()).isEqualTo(1)

        val result = selection.toggle(newItem)

        assertWithMessage("Toggle operation should be successful")
            .that(result)
            .isEqualTo(SelectionModifiedResult.SUCCESS)

        val snapshot = selection.snapshot()
        assertWithMessage("Snapshot should contain the new item").that(snapshot).contains(newItem)
        assertWithMessage("Snapshot should not contain the initial item")
            .that(snapshot)
            .doesNotContain(initialItem)
        assertWithMessage("Snapshot size should still be 1").that(snapshot).hasSize(1)

        advanceTimeBy(100)

        val flow = emissions.last()
        assertWithMessage("Emitted flow should contain the new item").that(flow).contains(newItem)
        assertWithMessage("Emitted flow should not contain the initial item")
            .that(flow)
            .doesNotContain(initialItem)
        assertWithMessage("Emitted flow size should still be 1").that(flow).hasSize(1)
    }

    /** Ensures adding single items respects the max selection batch size. */
    @Test
    fun testSelectionRespectsBatchSizeLimit_Add() = runTest {
        val selection = createSelection(maxBatchSize = BATCH_SIZE_LIMIT_25, itemSize = ITEM_SIZE_10)

        // 1st item (10/25 bytes) -> Success
        assertWithMessage("Adding an item within the batch limit should succeed")
            .that(selection.add(SelectionData(1)))
            .isEqualTo(SelectionModifiedResult.SUCCESS)

        // 2nd item (20/25 bytes) -> Success
        assertWithMessage("Adding an item within the batch limit should succeed")
            .that(selection.add(SelectionData(2)))
            .isEqualTo(SelectionModifiedResult.SUCCESS)

        // 3rd item (30/25 bytes) -> Fails
        assertWithMessage("Adding an item that exceeds the batch limit should fail")
            .that(selection.add(SelectionData(3)))
            .isEqualTo(SelectionModifiedResult.FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED)

        assertWithMessage("Expected selection size did not match")
            .that(selection.size())
            .isEqualTo(2)
    }

    /** Ensures bulk adding respects the max selection batch size. */
    @Test
    fun testSelectionRespectsBatchSizeLimit_AddAll() = runTest {
        val selection = createSelection(maxBatchSize = BATCH_SIZE_LIMIT_25, itemSize = ITEM_SIZE_10)

        val itemsToFail = setOf(SelectionData(1), SelectionData(2), SelectionData(3))
        assertWithMessage("Bulk adding items that collectively exceed the batch limit should fail")
            .that(selection.addAll(itemsToFail))
            .isEqualTo(SelectionModifiedResult.FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED)

        assertWithMessage("Expected selection size did not match")
            .that(selection.size())
            .isEqualTo(0)

        val itemsToSucceed = setOf(SelectionData(1), SelectionData(2))
        assertWithMessage("Bulk adding items within the batch limit should succeed")
            .that(selection.addAll(itemsToSucceed))
            .isEqualTo(SelectionModifiedResult.SUCCESS)

        assertWithMessage("Expected selection size did not match")
            .that(selection.size())
            .isEqualTo(2)
    }

    /** Ensures that when items are removed, the tracked size goes back down allowing new items. */
    @Test
    fun testSelectionSizeIsTrackedCorrectlyAcrossRemovals() = runTest {
        val selection = createSelection(maxBatchSize = BATCH_SIZE_LIMIT_20, itemSize = ITEM_SIZE_10)

        // Fill up the selection to exactly the limit (20 bytes)
        selection.add(SelectionData(1))
        selection.add(SelectionData(2))

        assertWithMessage("Adding an item that exceeds the batch limit should fail")
            .that(selection.add(SelectionData(3)))
            .isEqualTo(SelectionModifiedResult.FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED)

        // Remove an item to free up 10 bytes
        selection.remove(SelectionData(1))

        assertWithMessage("Adding an item should succeed after freeing up enough batch capacity")
            .that(selection.add(SelectionData(3)))
            .isEqualTo(SelectionModifiedResult.SUCCESS)
    }

    /**
     * Ensures toggling an item respects the batch limit, and correctly frees space when toggled
     * off.
     */
    @Test
    fun testSelectionRespectsBatchSizeLimit_Toggle() = runTest {
        val selection = createSelection(maxBatchSize = BATCH_SIZE_LIMIT_15, itemSize = ITEM_SIZE_10)

        assertWithMessage("Toggling an item on within the batch limit should succeed")
            .that(selection.toggle(SelectionData(1)))
            .isEqualTo(SelectionModifiedResult.SUCCESS)

        assertWithMessage("Toggling an item on that exceeds the batch limit should fail")
            .that(selection.toggle(SelectionData(2)))
            .isEqualTo(SelectionModifiedResult.FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED)

        assertWithMessage("Toggling an item off the selection should have succeeded")
            .that(selection.toggle(SelectionData(1)))
            .isEqualTo(SelectionModifiedResult.SUCCESS)

        assertWithMessage("Toggling an item on should succeed after capacity is freed")
            .that(selection.toggle(SelectionData(2)))
            .isEqualTo(SelectionModifiedResult.SUCCESS)
    }

    @Test
    fun testSelectionIgnoresBatchSizeLimitWhenUnset() = runTest {
        val selection = createSelection(itemSize = ITEM_SIZE_10 * 100)

        assertWithMessage("Adding item without batch limit should succeed")
            .that(selection.add(SelectionData(1)))
            .isEqualTo(SelectionModifiedResult.SUCCESS)
    }

    @Test
    fun testSelectionRespectsBatchSizeLimit_ToggleAll() = runTest {
        val selection = createSelection(maxBatchSize = BATCH_SIZE_LIMIT_25, itemSize = ITEM_SIZE_10)

        val items = setOf(SelectionData(1), SelectionData(2), SelectionData(3))
        assertWithMessage("Toggle all should fail if batch size limit is exceeded")
            .that(selection.toggleAll(items))
            .isEqualTo(SelectionModifiedResult.FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED)

        assertWithMessage("Expected selection size did not match")
            .that(selection.size())
            .isEqualTo(2)
    }

    @Test
    fun testSelectionSizeIsTrackedCorrectlyAcrossBulkRemovals() = runTest {
        val selection = createSelection(maxBatchSize = BATCH_SIZE_LIMIT_20, itemSize = ITEM_SIZE_10)

        val items = setOf(SelectionData(1), SelectionData(2))
        selection.addAll(items)

        assertWithMessage("Adding an item that exceeds the batch limit should fail")
            .that(selection.add(SelectionData(3)))
            .isEqualTo(SelectionModifiedResult.FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED)

        selection.removeAll(items)

        assertWithMessage("Adding items after freeing up space using removeAll should succeed")
            .that(selection.addAll(items))
            .isEqualTo(SelectionModifiedResult.SUCCESS)
    }

    @Test
    fun testToggleWithSelectionLimitOneRespectsBatchSizeLimit() = runTest {
        val selection =
            createSelection(
                selectionLimit = 1,
                maxBatchSize = BATCH_SIZE_LIMIT_15,
                itemSize = ITEM_SIZE_20,
            )

        assertWithMessage("Adding item in single select mode exceeding batch limit should fail")
            .that(selection.toggle(SelectionData(1)))
            .isEqualTo(SelectionModifiedResult.FAILURE_SELECTION_BATCH_SIZE_LIMIT_EXCEEDED)
    }

    @Test
    fun testPreselectionExceedsCountLimit() = runTest {
        testPreSelectionMediaData.update { ArrayList() }
        val selection = createSelection(selectionLimit = 1)
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val preSelected = listOf(SelectionData(1), SelectionData(2))
        testPreSelectionMediaData.update { ArrayList(preSelected) }
        advanceTimeBy(100)

        assertWithMessage("Initial flow state does not match expected size")
            .that(emissions.first())
            .hasSize(0)
        assertWithMessage("Resulting flow state does not match expected size")
            .that(emissions.last())
            .hasSize(0)
        // If limits are violated, no additional emissions should occur.
        assertWithMessage("Emissions count mismatch").that(emissions).hasSize(1)
    }

    @Test
    fun testPreselectionExceedsByteLimit() = runTest {
        testPreSelectionMediaData.update { ArrayList() }
        val selection = createSelection(maxBatchSize = BATCH_SIZE_LIMIT_15, itemSize = ITEM_SIZE_10)
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val preSelected = listOf(SelectionData(1), SelectionData(2)) // 20 bytes total
        testPreSelectionMediaData.update { ArrayList(preSelected) }
        advanceTimeBy(100)

        assertWithMessage("Initial flow state does not match expected size")
            .that(emissions.first())
            .hasSize(0)
        assertWithMessage("Resulting flow state does not match expected size")
            .that(emissions.last())
            .hasSize(0)
        assertWithMessage("Emissions count mismatch").that(emissions).hasSize(1)
    }

    @Test
    fun testPreselectionSkipsDisabledItems() = runTest {
        testPreSelectionMediaData.update { ArrayList() }
        // item with id = 2 will be disabled
        val selection = createSelection(isItemDisabled = { it.id == 2 })
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val preSelected = listOf(SelectionData(1), SelectionData(2), SelectionData(3))
        testPreSelectionMediaData.update { ArrayList(preSelected) }
        advanceTimeBy(100)

        assertWithMessage("Initial flow state does not match expected size")
            .that(emissions.first())
            .hasSize(0)
        assertWithMessage("Resulting flow state does not match expected size")
            .that(emissions.last())
            .hasSize(2)
        assertWithMessage("Selection state mismatch")
            .that(selection.snapshot())
            .containsExactly(SelectionData(1), SelectionData(3))
    }

    @Test
    fun testPreselectionWithinLimits() = runTest {
        testPreSelectionMediaData.update { ArrayList() }
        val selection =
            createSelection(
                selectionLimit = 2,
                maxBatchSize = ITEM_SIZE_20,
                itemSize = ITEM_SIZE_10,
            )
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val preSelected = listOf(SelectionData(1), SelectionData(2))
        testPreSelectionMediaData.update { ArrayList(preSelected) }
        advanceTimeBy(100)

        assertWithMessage("Initial flow state does not match expected size")
            .that(emissions.first())
            .hasSize(0)
        assertWithMessage("Resulting flow state does not match expected size")
            .that(emissions.last())
            .hasSize(2)
        assertWithMessage("Selection state mismatch")
            .that(selection.snapshot())
            .containsExactlyElementsIn(preSelected)
    }

    @Test
    fun testPreselectionWithInitialSelection() = runTest {
        testPreSelectionMediaData.update { ArrayList() }
        val initialItem = SelectionData(99)
        val selection = createSelection(selectionLimit = 2, initialSelection = setOf(initialItem))
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        // Pre-selected batch of 1 item fits (Total 2/2)
        val preSelected = listOf(SelectionData(1))
        testPreSelectionMediaData.update { ArrayList(preSelected) }
        advanceTimeBy(100)

        assertWithMessage("Initial flow state does not match expected size")
            .that(emissions.first())
            .hasSize(1)
        assertWithMessage("Resulting flow state does not match expected size")
            .that(emissions.last())
            .hasSize(2)
        assertWithMessage("Selection state mismatch")
            .that(selection.snapshot())
            .containsExactly(initialItem, SelectionData(1))
    }

    @Test
    fun testPreselectionWithInitialSelectionExceedsCountLimit() = runTest {
        testPreSelectionMediaData.update { ArrayList() }
        val initialItem = SelectionData(99)
        val selection = createSelection(selectionLimit = 2, initialSelection = setOf(initialItem))
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        // Pre-selected batch has 2 items. (Total 1 + 2 = 3 > 2)
        val preSelected = listOf(SelectionData(1), SelectionData(2))
        testPreSelectionMediaData.update { ArrayList(preSelected) }
        advanceTimeBy(100)

        // Initial selection was 1 item.
        assertWithMessage("Initial flow state mismatch").that(emissions.first()).hasSize(1)

        // Pre-selection batch was ignored entirely.
        assertWithMessage("Resulting flow state mismatch - should remain at 1 item")
            .that(emissions.last())
            .hasSize(1)

        assertWithMessage("Emissions count mismatch - no new emissions should have occurred")
            .that(emissions)
            .hasSize(1)
    }

    @Test
    fun testPreselectionWithInitialSelectionExceedsByteLimit() = runTest {
        testPreSelectionMediaData.update { ArrayList() }
        val initialItem = SelectionData(99)
        val selection =
            createSelection(
                maxBatchSize = BATCH_SIZE_LIMIT_25, // 25 bytes limit
                itemSize = ITEM_SIZE_10,
                initialSelection = setOf(initialItem), // uses 10 bytes
            )
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        // Pre-selected batch has 2 items (20 bytes). (Total 10 + 20 = 30 > 25)
        val preSelected = listOf(SelectionData(1), SelectionData(2))
        testPreSelectionMediaData.update { ArrayList(preSelected) }
        advanceTimeBy(100)

        assertWithMessage("Initial flow state mismatch").that(emissions.first()).hasSize(1)

        // Pre-selection batch was ignored entirely.
        assertWithMessage("Resulting flow state mismatch - should remain at 1 item")
            .that(emissions.last())
            .hasSize(1)

        assertWithMessage("Emissions count mismatch - no new emissions should have occurred")
            .that(emissions)
            .hasSize(1)
    }

    @Test
    fun testPreselectionEnabledSubsetFitsWithinLimits() = runTest {
        testPreSelectionMediaData.update { ArrayList() }
        // Selection limit is 1, but we pass 2 items where 1 is disabled.
        val selection = createSelection(selectionLimit = 1, isItemDisabled = { it.id == 2 })
        val emissions = mutableListOf<Set<SelectionData>>()
        backgroundScope.launch { selection.flow.toList(emissions) }

        val preSelected = listOf(SelectionData(1), SelectionData(2))
        testPreSelectionMediaData.update { ArrayList(preSelected) }
        advanceTimeBy(100)

        assertWithMessage("Initial flow state does not match expected size")
            .that(emissions.first())
            .hasSize(0)
        assertWithMessage("Resulting flow state does not match expected size")
            .that(emissions.last())
            .hasSize(1)
        assertWithMessage("Selection state mismatch")
            .that(selection.snapshot())
            .containsExactly(SelectionData(1))
    }

    /**
     * Helper method to create a [SelectionImpl] instance for testing.
     *
     * @param selectionLimit The maximum number of items that can be selected.
     * @param maxBatchSize The maximum total size of the selection in bytes.
     * @param itemSize The size that will be returned for every item in the selection.
     * @param initialSelection A collection of items to include in the initial selection.
     * @param isItemDisabled A lambda that returns true if the item is disabled.
     */
    private fun TestScope.createSelection(
        selectionLimit: Int = 50,
        maxBatchSize: Long = NO_BATCH_SIZE_LIMIT,
        itemSize: Long = ITEM_SIZE_10,
        initialSelection: Collection<SelectionData>? = null,
        preSelectedMedia: StateFlow<List<SelectionData>?> = testPreSelectionMediaData,
        isItemDisabled: (SelectionData) -> Boolean = { false },
    ): Selection<SelectionData> {
        val selectionParamsBuilder = PhotoPickerSelectionParams.Builder()
        val selectionParams =
            when (maxBatchSize) {
                NO_BATCH_SIZE_LIMIT -> selectionParamsBuilder.build()
                else -> selectionParamsBuilder.setMaxSelectionBatchSizeInBytes(maxBatchSize).build()
            }

        val selection =
            SelectionImpl(
                scope = backgroundScope,
                initialSelection = initialSelection,
                configuration =
                    provideTestConfigurationFlow(
                        scope = backgroundScope,
                        defaultConfiguration =
                            TestPhotopickerConfiguration.build {
                                action("")
                                selectionLimit(selectionLimit)
                                selectionParams(selectionParams)
                            },
                    ),
                preSelectedMedia = preSelectedMedia,
                getItemSizeInBytes = { itemSize },
                isItemDisabled = isItemDisabled,
            )

        // Start a collection to ensure the WhileSubscribed flow is active and receives
        // the initial selection updates.
        backgroundScope.launch { selection.flow.collect {} }

        advanceTimeBy(100)
        return selection
    }
}
