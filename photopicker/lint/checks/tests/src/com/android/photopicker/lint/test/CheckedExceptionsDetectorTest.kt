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

package com.android.photopicker.lint.test

import com.android.photopicker.lint.CheckedExceptionsDetector
import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@Suppress("UnstableApiUsage")
@RunWith(JUnit4::class)
class CheckedExceptionsDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = CheckedExceptionsDetector()

    override fun getIssues(): List<Issue> =
        listOf(
            CheckedExceptionsDetector.UNCAUGHT_CHECKED_EXCEPTION,
            CheckedExceptionsDetector.SUPPRESSED_EXCEPTION_NOT_DECLARED,
        )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    private val javaFileWithException =
        java(
                """
            package com.android.photopicker.lint;
            import java.io.IOException;
            class MyJavaClass {
                public void javaMethod() throws IOException {}
            }
        """
            )
            .indented()

    private val javaFileWithMultipleExceptions =
        java(
                """
        package com.android.photopicker.lint;
        import java.io.IOException;
        import java.sql.SQLException;
        class MyJavaClassWithMultipleExceptions {
            public void javaMethod() throws IOException, SQLException {}
        }
    """
            )
            .indented()

    private val javaFileWithRuntimeException =
        java(
                """
        package com.android.photopicker.lint;
        class MyJavaClassWithRuntimeException {
            public void javaMethod() throws IllegalArgumentException {}
        }
    """
            )
            .indented()

    @Test
    fun testUncaughtException_showsError() {
        lint()
            .files(
                javaFileWithException,
                kotlin(
                        """
                    package com.android.photopicker.lint
                    class MyKotlinClass {
                        fun kotlinMethod() {
                            MyJavaClass().javaMethod()
                        }
                    }
                """
                    )
                    .indented(),
            )
            .run()
            .expectErrorCount(1)
            .expect(
                """
                src/com/android/photopicker/lint/MyKotlinClass.kt:4: Error: Uncaught checked exception(s): java.io.IOException [UncaughtCheckedException]
                        MyJavaClass().javaMethod()
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    @Test
    fun testCaughtException_isClean() {
        lint()
            .files(
                javaFileWithException,
                kotlin(
                        """
                    package com.android.photopicker.lint
                    import java.io.IOException
                    class MyKotlinClass {
                        fun kotlinMethod() {
                            try {
                                MyJavaClass().javaMethod()
                            } catch (e: IOException) {
                                // Handled
                            }
                        }
                    }
                """
                    )
                    .indented(),
            )
            .run()
            .expectClean()
    }

    @Test
    fun testRuntimeExceptionSubclass_isClean() {
        lint()
            .files(
                javaFileWithRuntimeException,
                kotlin(
                        """
                package com.android.photopicker.lint
                class MyKotlinClass {
                    fun kotlinMethod() {
                        // IllegalArgumentException is a RuntimeException,
                        // so it should be ignored by the linter.
                        MyJavaClassWithRuntimeException().javaMethod()
                    }
                }
            """
                    )
                    .indented(),
            )
            .run()
            .expectClean()
    }

    @Test
    fun testSuppressedWithoutThrows_showsNewError() {
        lint()
            .files(
                javaFileWithException,
                kotlin(
                        """
                    package com.android.photopicker.lint
                    class MyKotlinClass {
                        @Suppress("UncaughtCheckedException")
                        fun kotlinMethod() {
                            MyJavaClass().javaMethod()
                        }
                    }
                """
                    )
                    .indented(),
            )
            .run()
            .expectErrorCount(1)
            .expect(
                """
                src/com/android/photopicker/lint/MyKotlinClass.kt:5: Error: Suppressed uncaught exception(s) (java.io.IOException) must be declared with @Throws on the enclosing method. [SuppressedCheckedExceptionNotDeclared]
                        MyJavaClass().javaMethod()
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    @Test
    fun testSuppressedWithCorrectThrows_isClean() {
        lint()
            .files(
                javaFileWithException,
                kotlin(
                        """
                    package com.android.photopicker.lint
                    import java.io.IOException
                    class MyKotlinClass {
                        @Suppress("UncaughtCheckedException")
                        @Throws(IOException::class)
                        fun kotlinMethod() {
                            MyJavaClass().javaMethod()
                        }
                    }
                """
                    )
                    .indented(),
            )
            .run()
            .expectClean()
    }

    @Test
    fun testSuppressedWithMultipleThrows_isClean() {
        lint()
            .files(
                javaFileWithException, // Throws IOException
                kotlin(
                        """
                package com.android.photopicker.lint
                import java.io.IOException
                import java.sql.SQLException // A second, unrelated exception

                class MyKotlinClass {
                    @Suppress("UncaughtCheckedException")
                    @Throws(SQLException::class, IOException::class)
                    fun kotlinMethod() {
                        MyJavaClass().javaMethod()
                    }
                }
            """
                    )
                    .indented(),
            )
            .run()
            .expectClean()
    }

    @Test
    fun testNestedTryCatch_caughtByOuter_isClean() {
        lint()
            .files(
                javaFileWithException,
                kotlin(
                        """
                package com.android.photopicker.lint
                import java.io.IOException
                import java.lang.Exception

                class MyKotlinClass {
                    fun kotlinMethod() {
                        try {
                            try {
                                // This call is inside a nested try
                                MyJavaClass().javaMethod()
                            } catch (e: Exception) {
                                // Not caught here, re-throwing
                                throw e
                            }
                        } catch (e: IOException) {
                            // Caught by the outer block
                        }
                    }
                }
                """
                    )
                    .indented(),
            )
            .run()
            .expectClean()
    }

    @Test
    fun testUncaughtExceptionInLambda_showsError() {
        lint()
            .files(
                javaFileWithException,
                kotlin(
                        """
                package com.android.photopicker.lint
                class MyKotlinClass {
                    fun kotlinMethod() {
                        val items = listOf(1)
                        items.forEach {
                            // Checked exception thrown from inside a lambda
                            MyJavaClass().javaMethod()
                        }
                    }
                }
            """
                    )
                    .indented(),
            )
            .run()
            .expectErrorCount(1)
            .expect(
                """
            src/com/android/photopicker/lint/MyKotlinClass.kt:7: Error: Uncaught checked exception(s): java.io.IOException [UncaughtCheckedException]
                        MyJavaClass().javaMethod()
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~
            1 errors, 0 warnings
            """
                    .trimIndent()
            )
    }

    @Test
    fun testAllowedMethod_runBlocking_isClean() {
        lint()
            .files(
                // We must provide a stub for the coroutines library because it's not
                // available by default in the test environment. The critical parts are
                // the file path, which becomes part of the qualified name, and the
                // @Throws annotation, which makes this a checked exception call.
                kotlin(
                        "src/kotlinx/coroutines/Builders.kt",
                        """
                package kotlinx.coroutines

                import kotlin.coroutines.CoroutineContext
                import kotlin.coroutines.EmptyCoroutineContext

                // The @Throws annotation is essential for this test.
                @Throws(InterruptedException::class)
                public fun <T> runBlocking(
                    context: CoroutineContext = EmptyCoroutineContext,
                    block: suspend () -> T
                ): T {
                    // This is a dummy implementation. The body doesn't matter.
                    return null as T
                }
                """,
                    )
                    .indented(),
                // This is the file that the linter will analyze.
                kotlin(
                        """
                package com.android.photopicker.lint.test

                import kotlinx.coroutines.runBlocking

                class MyTestFile {
                    fun usesRunBlocking() {
                        // This call would normally trigger a warning, but our linter
                        // is configured to ignore it.
                        runBlocking {
                            println("Hello from runBlocking")
                        }
                    }
                }
                """
                    )
                    .indented(),
            )
            .run()
            .expectClean() // Expect no warnings.
    }

    @Test
    fun testMultipleExceptions_oneCaught_showsErrorForUncaught() {
        lint()
            .files(
                javaFileWithMultipleExceptions,
                kotlin(
                        """
                package com.android.photopicker.lint
                import java.io.IOException
                class MyKotlinClass {
                    fun kotlinMethod() {
                        try {
                            MyJavaClassWithMultipleExceptions().javaMethod()
                        } catch (e: IOException) {
                            // Only IOException is handled
                        }
                    }
                }
            """
                    )
                    .indented(),
            )
            .run()
            .expectErrorCount(1)
            .expect(
                """
            src/com/android/photopicker/lint/MyKotlinClass.kt:6: Error: Uncaught checked exception(s): java.sql.SQLException [UncaughtCheckedException]
                        MyJavaClassWithMultipleExceptions().javaMethod()
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            1 errors, 0 warnings
            """
                    .trimIndent()
            )
    }

    @Test
    fun testCaughtWithSuperClass_isClean() {
        lint()
            .files(
                javaFileWithException,
                kotlin(
                        """
                package com.android.photopicker.lint
                import java.lang.Exception // Catching the parent
                class MyKotlinClass {
                    fun kotlinMethod() {
                        try {
                            MyJavaClass().javaMethod()
                        } catch (e: Exception) {
                            // Handled by catching a superclass
                        }
                    }
                }
            """
                    )
                    .indented(),
            )
            .run()
            .expectClean()
    }

    @Test
    fun testCallInsideCatchBlock_showsError() {
        lint()
            .files(
                javaFileWithException,
                kotlin(
                        """
                package com.android.photopicker.lint
                import java.lang.Exception
                class MyKotlinClass {
                    fun kotlinMethod() {
                        try {
                            // Some other operation
                        } catch (e: Exception) {
                            // This call is not covered by this try-catch
                            MyJavaClass().javaMethod()
                        }
                    }
                }
            """
                    )
                    .indented(),
            )
            .run()
            .expectErrorCount(1)
            .expect(
                """
            src/com/android/photopicker/lint/MyKotlinClass.kt:9: Error: Uncaught checked exception(s): java.io.IOException [UncaughtCheckedException]
                        MyJavaClass().javaMethod()
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~
            1 errors, 0 warnings
            """
                    .trimIndent()
            )
    }

    @Test
    fun testSuppressedWithWrongThrows_showsNewError() {
        lint()
            .files(
                javaFileWithException,
                kotlin(
                        """
                package com.android.photopicker.lint
                import java.io.IOException
                import java.sql.SQLException // An unrelated exception
                class MyKotlinClass {
                    @Suppress("UncaughtCheckedException")
                    @Throws(SQLException::class)
                    fun kotlinMethod() {
                        MyJavaClass().javaMethod()
                    }
                }
            """
                    )
                    .indented(),
            )
            .run()
            .expectErrorCount(1)
            .expect(
                """
            src/com/android/photopicker/lint/MyKotlinClass.kt:8: Error: Suppressed uncaught exception(s) (java.io.IOException) must be declared with @Throws on the enclosing method. [SuppressedCheckedExceptionNotDeclared]
                    MyJavaClass().javaMethod()
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~
            1 errors, 0 warnings
            """
                    .trimIndent()
            )
    }
}
