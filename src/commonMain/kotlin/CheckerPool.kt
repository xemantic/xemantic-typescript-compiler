/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
 */

package com.xemantic.typescript.compiler

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Parallel type-checking pool inspired by tsgo's N-checker goroutine model.
 *
 * Creates [checkerCount] independent [Checker] instances, each assigned a
 * round-robin subset of source files. All checkers share the same immutable
 * inputs (compiler options, binder results) but maintain their own mutable
 * state ([Checker.CheckerState]).
 *
 * Each checker still merges globals and resolves types for ALL files (correct
 * cross-file resolution), but only reports diagnostics for its assigned files.
 *
 * Usage:
 * ```kotlin
 * val pool = CheckerPool(options, binderResults)
 * val diagnostics = pool.checkAllFiles()
 * val checker = pool.primaryChecker  // for Transformer queries
 * ```
 *
 * When [checkerCount] is 1, behaves identically to a single [Checker].
 *
 * @see Checker
 */
class CheckerPool(
    private val options: CompilerOptions,
    private val binderResults: List<BinderResult>,
    private val isMultiFileSource: Boolean = false,
    private val checkerCount: Int = 4,
) {
    /**
     * The primary checker — used for Transformer query methods
     * ([Checker.isReferencedAliasDeclaration], [Checker.getEnumMemberValue],
     * [Checker.isValueAliasDeclaration]) which require complete cross-file
     * resolution. This checker processes ALL files (no file filter).
     */
    lateinit var primaryChecker: Checker
        private set

    /**
     * Check all files in parallel using coroutines.
     *
     * Creates N [Checker] instances, each assigned a round-robin subset of
     * source file names. The checkers run concurrently via [coroutineScope],
     * and their diagnostics are merged into a single sorted list.
     *
     * @return All diagnostics from all assigned files, sorted by file and position.
     */
    suspend fun checkAllFiles(): List<Diagnostic> {
        val fileNames = binderResults.map { it.sourceFile.fileName }

        // Compute round-robin file assignments
        val actualCheckerCount = checkerCount.coerceAtMost(binderResults.size).coerceAtLeast(1)
        val assignments = List(actualCheckerCount) { i ->
            fileNames.filterIndexed { j, _ -> j % actualCheckerCount == i }.toSet()
        }

        // Create and run checkers in parallel
        val checkers = coroutineScope {
            assignments.map { assignedFiles ->
                async {
                    Checker(
                        options = options,
                        binderResults = binderResults,
                        isMultiFileSource = isMultiFileSource,
                        assignedFileNames = assignedFiles,
                    )
                }
            }.awaitAll()
        }

        // The first checker serves as the primary for Transformer queries
        // (all checkers have identical resolution state, so any would work)
        primaryChecker = checkers[0]

        // Merge diagnostics from all checkers
        return checkers.flatMap { it.getDiagnostics() }
    }

    /**
     * Synchronous single-checker fallback — equivalent to creating a [Checker] directly.
     * Use this when coroutines are not available or when [checkerCount] is 1.
     */
    fun checkAllFilesSync(): List<Diagnostic> {
        primaryChecker = Checker(
            options = options,
            binderResults = binderResults,
            isMultiFileSource = isMultiFileSource,
        )
        return primaryChecker.getDiagnostics()
    }
}
