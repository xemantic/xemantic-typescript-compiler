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

import com.xemantic.kotlin.test.sameAs
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/** Root of the sparse-cloned TypeScript repository (relative to the test working directory). */
internal const val typeScriptRepoDir = "typescript-repo"

/** Directory containing the official TypeScript compiler test cases. */
internal val typeScriptCasesDir = "$typeScriptRepoDir/tests/cases/compiler"

/** Directory containing the TypeScript baseline reference files. */
internal val typeScriptBaselineDir = "$typeScriptRepoDir/tests/baselines/reference"

/** Reads the full text content of this [Path] using the system filesystem. */
internal fun Path.readText(): String =
    SystemFileSystem.source(this).buffered().readString()

/**
 * Asserts that this [Path]'s text content is the same as [expected] [Path]'s content.
 *
 * On failure, throws an [AssertionError] with a unified diff of the two file contents,
 * matching the output format of [com.xemantic.kotlin.test.sameAs].
 */
infix fun Path.sameAs(expected: Path) {
    readText() sameAs expected.readText()
}

/**
 * Asserts that this string is the same as the text content of the [expected] [Path].
 *
 * On failure, throws an [AssertionError] with a unified diff, matching the output format
 * of [com.xemantic.kotlin.test.sameAs]. Useful for comparing compiled output strings
 * directly against baseline files.
 */
infix fun String?.sameAs(expected: Path) {
    this sameAs expected.readText()
}
