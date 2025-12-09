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

package com.android.photopicker.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiType
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.kotlin.KotlinUTryExpression

/**
 * A Lint detector that enforces the handling of Java checked exceptions within Kotlin code.
 *
 * ### The Problem with Checked Exceptions in Kotlin
 *
 * Unlike Java, Kotlin does not have the concept of checked exceptions. This means the Kotlin
 * compiler does not force developers to catch or declare exceptions that a Java method might throw.
 * While this can make code cleaner, it introduces a risk: a checked exception thrown from Java code
 * can propagate up the call stack and cause an uncaught exception, leading to a runtime crash.
 *
 * ### How This Detector Works
 *
 * This detector scans Kotlin code for calls to methods (particularly Java methods) that are
 * declared to throw checked exceptions. It then verifies that these potential exceptions are
 * correctly handled.
 *
 * It reports two distinct types of issues:
 * 1. **`UncaughtCheckedException`**: This issue is reported when a method call can throw a checked
 *    exception, but the call is not enclosed in a `try-catch` block that handles that exception
 *    type (or one of its supertypes). This is the primary mechanism to prevent runtime crashes from
 *    unhandled Java exceptions.
 * 2. **`SuppressedCheckedExceptionNotDeclared`**: If a developer intentionally decides not to catch
 *    a checked exception, they can suppress the `UncaughtCheckedException` warning. However, simply
 *    suppressing the warning hides the potential issue from callers of the current Kotlin method.
 *    To maintain a clear exception contract, the enclosing Kotlin method must declare that it can
 *    throw this exception. This is done using the `@kotlin.jvm.Throws` annotation.
 *
 *    This issue is reported when `UncaughtCheckedException` is suppressed, but the enclosing method
 *    is **not** annotated with `@Throws` for the corresponding exception type. This ensures that
 *    API boundaries remain explicit about the exceptions they can propagate.
 *
 * ### Configuration
 *
 * The detector allows for some configuration via internal lists:
 * - `ALLOWED_UNCAUGHT_EXCEPTIONS`: A list of exception types (like `RuntimeException`) that are
 *   exempt from this check.
 * - `ALLOWED_UNCHECKED_METHODS`: A map of specific methods that are known to throw checked
 *   exceptions but are permitted to be called without explicit handling in certain contexts (e.g.,
 *   `kotlinx.coroutines.runBlocking`).
 */
class CheckedExceptionsDetector : Detector(), Detector.UastScanner {

    companion object {
        private const val THROWS_ANNOTATION_FQN = "kotlin.jvm.Throws"

        val UNCAUGHT_CHECKED_EXCEPTION =
            Issue.create(
                id = "UncaughtCheckedException",
                briefDescription = "Checked exception is not handled.",
                explanation =
                    "The Kotlin compiler does not enforce handling of checked exceptions. " +
                        "This can lead to runtime crashes if the exception is not caught.",
                category = Category.CORRECTNESS,
                severity = Severity.ERROR,
                implementation =
                    Implementation(CheckedExceptionsDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )

        val SUPPRESSED_EXCEPTION_NOT_DECLARED =
            Issue.create(
                id = "SuppressedCheckedExceptionNotDeclared",
                briefDescription = "Suppressed checked exception must be declared with @Throws.",
                explanation =
                    "When suppressing an 'UncaughtCheckedException' warning, the enclosing method " +
                        "must be annotated with `@Throws` to declare the exception. This maintains " +
                        "the exception contract for other Kotlin callers.",
                category = Category.CORRECTNESS,
                severity = Severity.ERROR,
                implementation =
                    Implementation(CheckedExceptionsDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )

        /*
         * These are the exception types that are allowed to be un-caught. Any exception either
         * directly, or indirectly in this list (via inheritance from a member of this list) will be
         * ignored by this detector if un-caught.
         *
         * NOTE: the base Exception class should never be added to this list, or this detector will
         * fundamentally do nothing.
         */
        private val ALLOWED_UNCAUGHT_EXCEPTIONS = listOf("java.lang.RuntimeException")

        /*
         * These are a mapping of package names to methods which throw known Checked exceptions
         * which are permitted to be used, and are ignored by this detector.
         */
        private val ALLOWED_UNCHECKED_METHODS: Map<String, List<String>> =
            mapOf("kotlinx.coroutines" to listOf("runBlocking"))
    }

    /** Detector will inspect all "call expressions" in the syntax tree */
    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {
            override fun visitCallExpression(node: UCallExpression) {

                val resolvedMethod = node.resolve()
                if (resolvedMethod != null) {
                    val methodName = resolvedMethod.name
                    val packageName = context.evaluator.getPackage(resolvedMethod)?.qualifiedName

                    // Look up the allowed methods for the package, if any.
                    val allowedMethodsInPackage = packageName?.let { ALLOWED_UNCHECKED_METHODS[it] }

                    if (
                        allowedMethodsInPackage != null &&
                            allowedMethodsInPackage.contains(methodName)
                    ) {
                        return // This method is explicitly allowed, so we skip the check.
                    }
                }

                val thrownExceptions =
                    node.resolve()?.throwsTypes?.mapNotNull { it as? PsiType } ?: return

                val uncaughtExceptions =
                    thrownExceptions
                        .filter { !isAllowedUncaughtException(it) }
                        .filter { !isCaught(node.uastParent, node, it) }

                if (uncaughtExceptions.isEmpty()) return

                if (context.driver.isSuppressed(context, UNCAUGHT_CHECKED_EXCEPTION, node)) {
                    val enclosingMethod = findEnclosingMethod(node)
                    val declaredThrows = getDeclaredThrows(enclosingMethod)

                    val undeclaredExceptions =
                        uncaughtExceptions.filter { uncaught ->
                            // Check if any declared exception is a supertype of the uncaught one.
                            val isDeclared =
                                declaredThrows.any { declared ->
                                    val uncaughtClass = (uncaught as? PsiClassType)?.resolve()
                                    val declaredClass = (declared as? PsiClassType)?.resolve()

                                    if (uncaughtClass != null && declaredClass != null) {
                                        // This is the most reliable way to check for subtyping.
                                        InheritanceUtil.isInheritorOrSelf(
                                            uncaughtClass,
                                            declaredClass,
                                            true,
                                        )
                                    } else {
                                        // Fallback for non-class types or resolution failures.
                                        declared.isAssignableFrom(uncaught)
                                    }
                                }
                            !isDeclared
                        }

                    if (undeclaredExceptions.isNotEmpty()) {
                        context.report(
                            SUPPRESSED_EXCEPTION_NOT_DECLARED,
                            node,
                            context.getLocation(node),
                            "Suppressed uncaught exception(s) " +
                                "(${undeclaredExceptions.joinToString { it.canonicalText }}) " +
                                "must be declared with @Throws on the enclosing method.",
                        )
                    }
                } else {
                    context.report(
                        UNCAUGHT_CHECKED_EXCEPTION,
                        node,
                        context.getLocation(node),
                        "Uncaught checked exception(s): " +
                            uncaughtExceptions.joinToString { it.canonicalText },
                    )
                }
            }
        }
    }

    /**
     * Check if the provided [throwType] matches directly or indirectly an exception on the
     * [ALLOWED_UNCAUGHT_EXCEPTIONS] list.
     *
     * @return true if the exception is allowed to be uncaught.
     */
    private fun isAllowedUncaughtException(type: PsiType): Boolean {
        if (ALLOWED_UNCAUGHT_EXCEPTIONS.contains(type.canonicalText)) return true
        return type.superTypes.any { isAllowedUncaughtException(it) }
    }

    /**
     * Recursively traverses up the UAST from a given element to determine if an exception of a
     * specific type is caught by a surrounding try-catch block.
     *
     * @param parent The parent UElement to inspect. The traversal starts from here.
     * @param child The child UElement from which the traversal originates. This is used to ensure
     *   that the element in question is within a `try` block, not a `catch` or `finally` block.
     * @param exceptionType The [PsiType] of the exception to check for.
     * @return `true` if the exception is caught by a `try-catch` block enclosing the original
     *   element, `false` otherwise.
     */
    private fun isCaught(parent: UElement?, child: UElement, exceptionType: PsiType): Boolean {
        if (parent == null) return false

        if (parent is KotlinUTryExpression) {
            // Check if the call is inside the `try` block and not one of the `catch` blocks.
            if (parent.tryClause === child) {
                // Now check if any of the catch clauses can handle the exception.
                if (
                    parent.catchClauses.any { clause ->
                        clause.types.any { catchType -> catchType.isAssignableFrom(exceptionType) }
                    }
                ) {
                    return true
                }
            }
            // If the call is inside a `catch` block, it is not considered "caught" by this
            // try-catch statement, so we continue searching up the tree.
        }

        return isCaught(parent.uastParent, parent, exceptionType)
    }

    /**
     * Finds the enclosing method for a given UAST element.
     *
     * This method traverses up the UAST tree from the starting [element] until it finds a [UMethod]
     * or reaches the root of the tree.
     *
     * @param element The starting UAST element.
     * @return The enclosing [UMethod], or `null` if the element is not inside a method.
     */
    private fun findEnclosingMethod(element: UElement?): UMethod? {
        var current = element
        while (current != null) {
            if (current is UMethod) return current
            current = current.uastParent
        }
        return null
    }

    /**
     * Gets the declared thrown exception types from a method's `@Throws` annotation.
     *
     * This method handles both single and multiple exception classes in the annotation (e.g.,
     * `@Throws(E1::class)` and `@Throws(E1::class, E2::class)`).
     *
     * @param method The UAST method to inspect.
     * @return A list of [PsiType]s for the exception classes declared in the `@Throws` annotation,
     *   or an empty list if the annotation is not present or specifies no exceptions.
     */
    private fun getDeclaredThrows(method: UMethod?): List<PsiType> {
        val throwsAnnotation =
            method?.uAnnotations?.find { it.qualifiedName == THROWS_ANNOTATION_FQN }
                ?: return emptyList()

        // The attribute for @Throws is "exceptionClasses"
        val attributeValue =
            throwsAnnotation.findAttributeValue("exceptionClasses") ?: return emptyList()

        val expressions =
            when (attributeValue) {
                // A vararg parameter like `@Throws(E1::class, E2::class)` is a UCallExpression
                is UCallExpression -> attributeValue.valueArguments
                // A single parameter like `@Throws(E1::class)` is a UClassLiteralExpression
                is UClassLiteralExpression -> listOf(attributeValue)
                else -> return emptyList()
            }

        return expressions.mapNotNull { expr -> (expr as? UClassLiteralExpression)?.type }
    }
}
