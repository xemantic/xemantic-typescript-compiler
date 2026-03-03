/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
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
internal const val typeScriptCasesDir = "$typeScriptRepoDir/tests/cases/compiler"

/** Directory containing the TypeScript baseline reference files. */
internal const val typeScriptBaselineDir = "$typeScriptRepoDir/tests/baselines/reference"

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
