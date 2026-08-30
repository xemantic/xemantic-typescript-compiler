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
 * (INC.63): the real-lib DECLARATION tables are a pure function of the SHARED parses,
 * so they are built once per process and per lib set rather than once per [Checker].
 *
 * On a `dom` lib set the per-checker walk was **12-16 ms of a ~205 ms incremental
 * floor** — ~30k insertions into containers keyed by DATA-CLASS nodes, i.e. round 471's
 * deep `hashCode` at a scale the es2020-only fixtures could not express. The same round
 * memoized [RealLibResolver.resolve], whose `/// <reference lib=…/>` closure ran
 * [RealLibResolver.libReferenceRegex] over every included file — ~3.7 MB of lib text —
 * on every checker construction.
 *
 * **THE CLAIM EACH PIN MAKES IS A COUNT, NOT A TIME**, per CLAUDE.md round 868: a
 * millisecond assertion over a sub-10-ms region is a coin flip, and this saving sits far
 * under the run-to-run spread of a whole compile. Both counters are process-global and
 * outlive any one test, so every pin reads a DELTA.
 *
 * The VALUE half is not pinned here — it is pinned end-to-end by
 * [RealLibsTs2728FileTest], which renders `realLibDeclFile`'s answer into a TS2728
 * "declared here" file name, and by the ~13k-baseline corpus. What this class adds is
 * the half no answer can see: that the tables are SHARED rather than rebuilt.
 */
class LibDeclIndexSharingTest {

    private fun compileWithRealLibs() =
        TypeScriptCompiler().compile(
            """
            // @useRealLibs: true
            // @lib: es2020
            // @target: es2020
            const xs: number[] = [];
            const n: number = xs.length;
            """.trimIndent(),
            "t.ts",
        )

    @Test
    fun `the lib decl index is built once and served to every later checker`() {
        compileWithRealLibs() // whichever test ran first may already have built it
        val builds0 = RealLibSnapshots.declIndexBuilds
        compileWithRealLibs()
        compileWithRealLibs()
        compileWithRealLibs()
        assert(RealLibSnapshots.declIndexBuilds - builds0 == 0)
    }

    @Test
    fun `the lib set resolution is memoized across checker constructions`() {
        compileWithRealLibs()
        val resolutions0 = RealLibResolver.resolutions
        compileWithRealLibs()
        compileWithRealLibs()
        assert(RealLibResolver.resolutions - resolutions0 == 0)
    }

    @Test
    fun `a distinct lib set gets its own index rather than the first one`() {
        RealLibSnapshots.libDeclIndex(listOf("es2015"), ScriptTarget.ES2015)
        val builds0 = RealLibSnapshots.declIndexBuilds
        // es2017 pulls in strictly more files than es2015, so the two key lists differ.
        RealLibSnapshots.libDeclIndex(listOf("es2017"), ScriptTarget.ES2017)
        assert(RealLibSnapshots.declIndexBuilds - builds0 == 1)
        RealLibSnapshots.libDeclIndex(listOf("es2017"), ScriptTarget.ES2017)
        assert(RealLibSnapshots.declIndexBuilds - builds0 == 1)
    }

    /**
     * The POPULATION beside the nanos ((INC.60)'s rule): a row divided by its own
     * population is the only thing that says whether an implied per-op cost is
     * physically possible, and identical counts across the change are the receipt
     * that nothing was skipped to buy the time. `es2015` is pinned rather than `dom`
     * because it is the set every corpus fixture uses; the numbers are the shipped
     * lib sources', so they move only when the vendored libs do.
     */
    @Test
    fun `the index covers every top-level declaration and every interface member`() {
        val files = RealLibSnapshots.parsedLibFiles(listOf("es2015"), ScriptTarget.ES2015)
        val index = RealLibSnapshots.libDeclIndex(listOf("es2015"), ScriptTarget.ES2015)
        assert(files.isNotEmpty())

        var topLevel = 0
        var members = 0
        for (file in files) {
            for (stmt in file.statements) {
                topLevel++
                // Identity, not `in`: a Set<Node> compares data classes STRUCTURALLY, so
                // membership would also answer true for a structurally equal sibling.
                assert(index.decls.any { it === stmt })
                assert(index.declFile[stmt] == file.fileName)
                when (stmt) {
                    is InterfaceDeclaration -> stmt.members.forEach {
                        members++
                        assert(index.memberDecls.any { m -> m === it })
                        assert(index.declFile[it] == file.fileName)
                    }
                    is ClassDeclaration -> stmt.members.forEach { members++ }
                    else -> {}
                }
            }
        }
        assert(topLevel > 100)
        assert(members > 500)
        // A member declaration is never a top-level one, and the reverse.
        assert(index.decls.none { it in index.memberDecls })
    }

    /**
     * NEGATIVE CONTROL — the embedded-lib path builds its OWN tables and must not be
     * served the real-lib index (its nodes come from a different parse entirely).
     * Without it, a "share everything" implementation reads green on every pin above.
     */
    @Test
    fun `the embedded lib path does not touch the shared index`() {
        RealLibSnapshots.libDeclIndex(listOf("es2015"), ScriptTarget.ES2015)
        val builds0 = RealLibSnapshots.declIndexBuilds
        TypeScriptCompiler().compile("const s: string = 'a';", "t.ts")
        assert(RealLibSnapshots.declIndexBuilds - builds0 == 0)
    }
}
