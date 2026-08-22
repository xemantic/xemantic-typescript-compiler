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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.FrontEnd
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (INC.9) A NARROWED QUERY BUILDS THE FLOW GRAPHS IT READS AND NO OTHERS.
 *
 * The unit-level fact — that `BinderResult` defers its graph — is pinned in the core
 * module's `LazyFlowGraphTest`. This is the fact that makes it a LEVER: driven
 * through `ProjectCompiler`, a `recheckOnly` build must build strictly fewer graphs
 * than the whole-program build of the same program, and must still report the same
 * rows for the file it was asked about.
 *
 * Both halves are needed and neither implies the other. A build that skipped the
 * graphs it DOES need would pass the count assertion and fail the rows one; an eager
 * build passes the rows assertion and fails the count one, which is exactly the
 * ablation that verifies this pin (restore `Binder.bind`'s eager
 * `FlowGraphBuilder().build(sourceFile)` and the two counts become equal).
 *
 * The instrument is `FrontEnd.flowGraphsBuilt`, incremented once per
 * `FlowGraphBuilder.build`. It is probe-gated, so the probe is switched ON here
 * deliberately and restored in a `finally` — a mode left armed reconfigures every
 * later test in the JVM (round 874). Turning the probe on cannot itself build a
 * graph: the counter is incremented from INSIDE `build`, never from an argument
 * evaluated at the call site (round 900).
 */
class ProjectLazyFlowGraphTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val guardedFile = "/proj/src/guarded.ts"
    private val plainFile = "/proj/src/plain.ts"
    private val bystanderFile = "/proj/src/bystander.ts"

    /**
     * An EARLY-RETURN guard with an ASSIGNMENT probe: the narrowing can only come
     * from a flow walk, and an assignment to an incompatible type is the only shape
     * that reports the narrowed type here — a property read on an un-narrowed
     * `string | number` emits nothing at all (measured), so a read-shaped fixture
     * would pin nothing. This file's own graph must be built when it is the file
     * being asked about.
     */
    private val guardedText = """
        export function f(x: string | number): void {
            if (typeof x === "number") return;
            const s: string = x;
        }
    """.trimIndent() + "\n"

    /** The same assignment with NO guard — TS2322, the row the equivalence half compares. */
    private val plainText = """
        export function g(x: string | number): void {
            const s: string = x;
        }
    """.trimIndent() + "\n"

    private val bystanderText = """
        export function untouched(n: number): number {
            return n + 1;
        }
    """.trimIndent() + "\n"

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            guardedFile to guardedText,
            plainFile to plainText,
            bystanderFile to bystanderText,
        ),
    )

    private fun rowsIn(diagnostics: List<Diagnostic>, file: String) =
        diagnostics.filter { it.fileName == file }.map { "${it.code}@${it.start}" }.sorted()

    /**
     * Runs [block] with the `--frontEnd` probe armed and returns how many flow graphs
     * were built while it ran.
     */
    private fun graphsBuiltDuring(block: () -> Unit): Long {
        val saved = FrontEnd.mode
        return try {
            FrontEnd.reset()
            FrontEnd.mode = FrontEnd.ON
            block()
            FrontEnd.flowGraphsBuilt
        } finally {
            FrontEnd.mode = saved
            FrontEnd.reset()
        }
    }

    @Test
    fun `the whole-program build reports the un-guarded assignment and not the guarded one`() {
        val whole = ProjectCompiler(vfs()).build("/proj", noEmit = true)
        assert(rowsIn(whole.diagnostics, plainFile).isNotEmpty())
        assert(rowsIn(whole.diagnostics, guardedFile).isEmpty())
        assert(rowsIn(whole.diagnostics, bystanderFile).isEmpty())
    }

    @Test
    fun `a partition builds strictly fewer flow graphs than the whole-program build`() {
        val vfs = vfs()
        val whole = graphsBuiltDuring { ProjectCompiler(vfs).build("/proj", noEmit = true) }
        val narrowed = graphsBuiltDuring {
            ProjectCompiler(vfs).build("/proj", noEmit = true, recheckOnly = setOf(guardedFile))
        }
        assert(whole >= 3)
        assert(narrowed < whole)
    }

    /**
     * And the file the partition WAS asked about still gets its graph — the narrowing
     * above is a flow narrowing, so a partition that built none would report TS2339
     * here. Stated as a count so the assertion fails on the count rather than on a
     * downstream diagnostic that could be silenced for some other reason.
     */
    @Test
    fun `the asked file's own graph is still built`() {
        val narrowed = graphsBuiltDuring {
            ProjectCompiler(vfs()).build("/proj", noEmit = true, recheckOnly = setOf(guardedFile))
        }
        assert(narrowed >= 1)
    }

    @Test
    fun `a partition of the guarded file keeps its flow narrowing`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(guardedFile))
        assert(rowsIn(whole.diagnostics, guardedFile).isEmpty())
        assert(rowsIn(narrowed.diagnostics, guardedFile) == rowsIn(whole.diagnostics, guardedFile))
    }

    @Test
    fun `a partition of the un-guarded file keeps its row`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(plainFile))
        assert(rowsIn(whole.diagnostics, plainFile).isNotEmpty())
        assert(rowsIn(narrowed.diagnostics, plainFile) == rowsIn(whole.diagnostics, plainFile))
    }
}
