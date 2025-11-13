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

package com.android.photopicker.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ApplyWhenTest {

    data class TestSubject(var value: Int, var name: String = "initial")

    // Tests for applyWhen
    @Test
    fun applyWhenExecutesBlockAndReturnsItsResultWhenConditionIsTrue() {
        val subject = TestSubject(10)
        val condition = true
        var blockExecuted = false

        val result =
            subject.applyWhen(condition) {
                blockExecuted = true
                this.value = 20
                this.name = "modified_by_block"
                this // block returns the modified receiver
            }

        assertThat(blockExecuted).isTrue()
        assertThat(subject.value).isEqualTo(20)
        assertThat(subject.name).isEqualTo("modified_by_block")
        assertThat(result).isSameInstanceAs(subject)
        assertThat(result.value).isEqualTo(20)
        assertThat(result.name).isEqualTo("modified_by_block")
    }

    @Test
    fun applyWhenBlockConditionTrue() {
        val subject = TestSubject(10, "original")
        val condition = true
        var blockExecuted = false

        val result =
            subject.applyWhen(condition) {
                blockExecuted = true
                TestSubject(this.value * 2, "new_object_from_block")
            }

        assertThat(blockExecuted).isTrue()
        assertThat(subject.value).isEqualTo(10) // Original subject unchanged
        assertThat(subject.name).isEqualTo("original")
        assertThat(result).isNotSameInstanceAs(subject)
        assertThat(result.value).isEqualTo(20)
        assertThat(result.name).isEqualTo("new_object_from_block")
    }

    @Test
    fun applyWhenBlockConditionFalse() {
        val subject = TestSubject(10, "initial")
        val condition = false
        var blockExecuted = false

        val result =
            subject.applyWhen(condition) {
                blockExecuted = true
                this.value = 20
                this.name = "modified_by_block_false_cond"
                this
            }

        assertThat(blockExecuted).isFalse()
        assertThat(subject.value).isEqualTo(10)
        assertThat(subject.name).isEqualTo("initial")
        assertThat(result).isSameInstanceAs(subject)
        assertThat(result.value).isEqualTo(10)
        assertThat(result.name).isEqualTo("initial")
    }

    // Tests for applyChoice
    @Test
    fun applyChoiceExecutesTrueBlockAndReturnsResultWhenConditionTrue() {
        val subject = TestSubject(10)
        val condition = true
        var trueBlockExecuted = false
        var falseBlockExecuted = false

        val result =
            subject.applyChoice(
                condition = condition,
                trueBlock = {
                    trueBlockExecuted = true
                    this.value = 100
                    this.name = "from_true_block"
                    this
                },
                falseBlock = {
                    falseBlockExecuted = true
                    this.value = 200
                    this.name = "from_false_block"
                    this
                },
            )

        assertThat(trueBlockExecuted).isTrue()
        assertThat(falseBlockExecuted).isFalse()
        assertThat(subject.value).isEqualTo(100)
        assertThat(subject.name).isEqualTo("from_true_block")
        assertThat(result).isSameInstanceAs(subject)
        assertThat(result.value).isEqualTo(100)
        assertThat(result.name).isEqualTo("from_true_block")
    }

    @Test
    fun applyChoiceExecutesTrueBlockAndReturnsResultWhenConditionTrueReturnsNewObject() {
        val subject = TestSubject(10, "original")
        val condition = true
        var trueBlockExecuted = false
        var falseBlockExecuted = false

        val result =
            subject.applyChoice(
                condition = condition,
                trueBlock = {
                    trueBlockExecuted = true
                    TestSubject(this.value * 2, "new_object_from_true_block")
                },
                falseBlock = {
                    falseBlockExecuted = true
                    this
                },
            )

        assertThat(trueBlockExecuted).isTrue()
        assertThat(falseBlockExecuted).isFalse()
        assertThat(subject.value).isEqualTo(10) // Original subject unchanged
        assertThat(subject.name).isEqualTo("original")
        assertThat(result).isNotSameInstanceAs(subject)
        assertThat(result.value).isEqualTo(20)
        assertThat(result.name).isEqualTo("new_object_from_true_block")
    }

    @Test
    fun applyChoiceExecutesFalseBlockAndReturnsResultWhenConditionIsFalse() {
        val subject = TestSubject(10)
        val condition = false
        var trueBlockExecuted = false
        var falseBlockExecuted = false

        val result =
            subject.applyChoice(
                condition = condition,
                trueBlock = {
                    trueBlockExecuted = true
                    this.value = 100
                    this.name = "from_true_block"
                    this
                },
                falseBlock = {
                    falseBlockExecuted = true
                    this.value = 200
                    this.name = "from_false_block"
                    this
                },
            )

        assertThat(trueBlockExecuted).isFalse()
        assertThat(falseBlockExecuted).isTrue()
        assertThat(subject.value).isEqualTo(200)
        assertThat(subject.name).isEqualTo("from_false_block")
        assertThat(result).isSameInstanceAs(subject)
        assertThat(result.value).isEqualTo(200)
        assertThat(result.name).isEqualTo("from_false_block")
    }

    @Test
    fun applyChoiceExecutesFalseBlockAndReturnsResultWhenConditionFalseReturnsNewObject() {
        val subject = TestSubject(10, "original")
        val condition = false
        var trueBlockExecuted = false
        var falseBlockExecuted = false

        val result =
            subject.applyChoice(
                condition = condition,
                trueBlock = {
                    trueBlockExecuted = true
                    this
                },
                falseBlock = {
                    falseBlockExecuted = true
                    TestSubject(this.value * 3, "new_object_from_false_block")
                },
            )

        assertThat(trueBlockExecuted).isFalse()
        assertThat(falseBlockExecuted).isTrue()
        assertThat(subject.value).isEqualTo(10) // Original subject unchanged
        assertThat(subject.name).isEqualTo("original")
        assertThat(result).isNotSameInstanceAs(subject)
        assertThat(result.value).isEqualTo(30)
        assertThat(result.name).isEqualTo("new_object_from_false_block")
    }
}
