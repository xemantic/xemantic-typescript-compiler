/*
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xemantic.typescript.compiler

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test harness that runs the xemantic TypeScript compiler against the official
 * [TypeScript compiler test suite](https://github.com/microsoft/TypeScript/tree/main/tests/cases/compiler).
 *
 * ### Running the tests
 *
 * The test suite requires the TypeScript repository to be cloned first:
 * ```
 * ./gradlew cloneTypeScriptRepo jvmTest
 * ```
 *
 * Or run both together (the `jvmTest` task depends on `cloneTypeScriptRepo`):
 * ```
 * ./gradlew jvmTest
 * ```
 *
 * The TypeScript repository is cloned via a sparse shallow checkout into
 * `typescript-repo/` at the project root (gitignored). Only the test cases and
 * baseline reference files are fetched to keep the clone small.
 *
 * ### Test status
 *
 * All tests are currently **skipped** because the compiler is not yet implemented.
 * As the compiler implementation progresses, individual tests will transition
 * from _skipped_ → _passing_. Unexpected failures (non-`NotImplementedError` errors)
 * surface as genuine test failures.
 */
class TypeScriptCompilerTestHarness {

    @TestFactory
    fun officialTypeScriptCompilerTests(): List<DynamicTest> {
        val typeScriptRepoPath = System.getProperty(TYPESCRIPT_REPO_DIR_PROPERTY)
            ?: return listOf(
                skippedTest(
                    "TypeScript repository not configured",
                    "System property '$TYPESCRIPT_REPO_DIR_PROPERTY' is not set. " +
                        "Run: ./gradlew cloneTypeScriptRepo jvmTest",
                )
            )

        val typeScriptRepoDir = File(typeScriptRepoPath)
        val testsDir = typeScriptRepoDir.resolve(COMPILER_TESTS_PATH)
        val baselinesDir = typeScriptRepoDir.resolve(BASELINES_PATH)

        if (!testsDir.exists()) {
            return listOf(
                skippedTest(
                    "TypeScript test cases not found",
                    "Expected directory: $testsDir. Run: ./gradlew cloneTypeScriptRepo",
                )
            )
        }

        val compiler = TypeScriptCompiler()

        return testsDir.walkTopDown()
            .filter { it.isFile && it.extension == "ts" }
            .sorted()
            .map { testFile ->
                DynamicTest.dynamicTest(testFile.nameWithoutExtension) {
                    runCompilerTest(compiler, testFile, baselinesDir)
                }
            }
            .toList()
    }

    private fun runCompilerTest(
        compiler: TypeScriptCompiler,
        testFile: File,
        baselinesDir: File,
    ) {
        val source = testFile.readText()

        val result = runCatching {
            compiler.compile(source, testFile.name)
        }

        result.onFailure { cause ->
            when (cause) {
                is NotImplementedError ->
                    Assumptions.assumeTrue(false, "Compiler not yet implemented: ${testFile.name}")
                else -> throw cause
            }
        }

        val compilationResult = result.getOrThrow()
        val testName = testFile.nameWithoutExtension

        // Compare JavaScript output against baseline if one exists.
        // A missing .js baseline means the test is expected to produce only errors.
        val jsBaseline = baselinesDir.resolve("$testName.js")
        if (jsBaseline.exists()) {
            val expected = jsBaseline.readText().normalizeLineEndings().trim()
            val actual = (compilationResult.javascript ?: "").normalizeLineEndings().trim()
            assertEquals(
                expected = expected,
                actual = actual,
                message = "JavaScript output mismatch for ${testFile.name}",
            )
        }

        // If an .errors.txt baseline exists, the test is expected to fail with diagnostics.
        val errorsBaseline = baselinesDir.resolve("$testName.errors.txt")
        if (errorsBaseline.exists()) {
            assertTrue(
                compilationResult.hasErrors,
                "Expected compilation errors for ${testFile.name} but got none",
            )
        }
    }

    private fun skippedTest(name: String, reason: String): DynamicTest =
        DynamicTest.dynamicTest(name) {
            Assumptions.assumeTrue(false, reason)
        }

    private fun String.normalizeLineEndings(): String =
        replace("\r\n", "\n").replace("\r", "\n")

    private companion object {
        const val TYPESCRIPT_REPO_DIR_PROPERTY = "typescript.repo.dir"
        const val COMPILER_TESTS_PATH = "tests/cases/compiler"
        const val BASELINES_PATH = "tests/baselines/reference"
    }

}
