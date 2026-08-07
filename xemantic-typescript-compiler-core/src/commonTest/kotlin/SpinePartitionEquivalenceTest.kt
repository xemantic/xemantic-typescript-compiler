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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * INV.6(6a): the share-nothing partition contract (docs/parallel-caching.md
 * Phase 0). A [Checker] built with [assignedFileNames] walks the check spine
 * only for its assigned files; the SORTED UNION of complementary workers'
 * diagnostics must be IDENTICAL to the full (null-partition) run — sequential
 * equivalence is the hard requirement that keeps the corpus/listAll
 * verification method valid for the parallel driver. Any divergence here is a
 * cross-file spine-state leak (an order-dependence bug to fix, never an
 * acceptable delta).
 *
 * Built by direct `Checker(options, binderResults)` construction with
 * path-shaped file names (flat names defeat relative module resolution — the
 * documented test-fixture trap).
 */
class SpinePartitionEquivalenceTest {

    private val options = CompilerOptions(strict = true)

    private fun bind(vararg files: Pair<String, String>): List<BinderResult> =
        files.map { (name, src) -> Binder(options).bind(Parser(src.trimIndent(), name).parse()) }

    private fun diagKey(d: Diagnostic): String =
        "${d.fileName}|${d.start}|${d.length}|${d.code}|${d.message}"

    private fun sortedKeys(diags: List<Diagnostic>): List<String> =
        diags.map { diagKey(it) }.sorted()

    /** Three files, each with its own spine-emitted error, plus a cross-file import. */
    private val fixture = arrayOf(
        "/proj/a.ts" to """
            export interface Foo { x: number }
            const bad: string = 1;
        """,
        "/proj/b.ts" to """
            import { Foo } from "./a.js";
            export const f: Foo = { x: 1 };
            const alsoBad: number = "s";
        """,
        "/proj/c.ts" to """
            let used: boolean = 0;
            function g(): number { return "nope"; }
        """,
    )

    @Test
    fun `two complementary partitions merge to the full run byte-identically`() {
        val full = Checker(options, bind(*fixture), isMultiFileSource = true).getDiagnostics()
        val fullKeys = sortedKeys(full)
        assert(fullKeys.isNotEmpty())

        val w1 = Checker(
            options, bind(*fixture), isMultiFileSource = true,
            assignedFileNames = setOf("/proj/a.ts", "/proj/c.ts"),
        ).getDiagnostics()
        val w2 = Checker(
            options, bind(*fixture), isMultiFileSource = true,
            assignedFileNames = setOf("/proj/b.ts"),
        ).getDiagnostics()

        assert(sortedKeys(w1 + w2) == fullKeys)
    }

    @Test
    fun `every singleton partition sees exactly its own slice of the full run`() {
        val full = Checker(options, bind(*fixture), isMultiFileSource = true).getDiagnostics()
        for (fileName in listOf("/proj/a.ts", "/proj/b.ts", "/proj/c.ts")) {
            val expected = sortedKeys(full.filter { it.fileName == null || it.fileName == fileName })
            val worker = Checker(
                options, bind(*fixture), isMultiFileSource = true,
                assignedFileNames = setOf(fileName),
            ).getDiagnostics()
            // A singleton worker's output for ITS file must match the full run's
            // slice; fileName-null diagnostics (program-level) may be duplicated
            // across workers and are compared per-worker against the full slice.
            assert(sortedKeys(worker) == expected)
        }
    }

    @Test
    fun `a worker emits nothing for foreign files`() {
        val worker = Checker(
            options, bind(*fixture), isMultiFileSource = true,
            assignedFileNames = setOf("/proj/b.ts"),
        ).getDiagnostics()
        assert(worker.none { it.fileName != null && it.fileName != "/proj/b.ts" })
    }

    @Test
    fun `negative control - the full run still reports each file's spine error`() {
        val full = Checker(options, bind(*fixture), isMultiFileSource = true).getDiagnostics()
        for (fileName in listOf("/proj/a.ts", "/proj/b.ts", "/proj/c.ts")) {
            assert(full.any { it.fileName == fileName && it.code == 2322 })
        }
    }
}
