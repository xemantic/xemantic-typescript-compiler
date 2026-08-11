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
 * (PERF.HW.b): under `--workers N` the compilation core must NOT bind the
 * program on its sequential prefix.
 *
 * Every worker binds the whole program for itself — it has to, because `Checker`
 * init mutates the symbols it is given, so one bind cannot be shared by N
 * checkers — and the sequential `BinderResult`s are read by the SEQUENTIAL
 * branch alone. Computing them anyway put a whole redundant whole-program
 * `Binder.bind` on the one term worker count cannot buy back.
 *
 * The reason this needs a pin at all is that the defect is INVISIBLE to every
 * other instrument in this repo: a redundant bind whose results are dropped
 * changes no diagnostic, no emitted byte and no `cost_gate.py` counter — only
 * the wall clock, where it sits under the run-to-run spread of a parallel
 * compile. So the pin is [FrontEnd.sequentialFileBinds], an exact count written
 * only from the caller thread, and NOT a timing ratio (round 868: an assertion
 * over a timed region is a coin flip; one over a recorded count is a fact).
 *
 * The two halves are deliberately different claims. The count is what
 * DISCRIMINATES — restore the sequential bind and it reads 2 instead of 0. The
 * diagnostic equality is the safety net that says the skip is behaviour-free;
 * it passes on a binary with the redundant bind too, and is recorded here as
 * a control rather than as coverage.
 */
class ParallelSequentialBindSkipTest {

    private val fileA = "/p/a.ts"
    private val fileB = "/p/b.ts"

    private val sourceA = """
        export interface Shape {
            readonly kind: string;
        }
        export const one: number = 1;
    """.trimIndent()

    /** Carries a cross-file error, so the compared diagnostics are non-empty. */
    private val sourceB = """
        import { Shape } from "./a";
        export const wrong: number = "not a number";
        export function kindOf(s: Shape): string {
            return s.kind;
        }
    """.trimIndent()

    private class Run(val binds: Long, val diagnostics: List<String>)

    private fun compileWith(workers: Int): Run {
        val options = CompilerOptions()
        val parsed = ParsedSource(
            options,
            listOf(SourceFileEntry(fileA, sourceA), SourceFileEntry(fileB, sourceB)),
            hasExplicitFilenames = true,
        )
        // Fork-global mode state: save and restore, never assign the default back
        // (a test that wipes lab/mode state re-arms it for every later class).
        val saved = ParallelCheckMode.workers
        try {
            ParallelCheckMode.workers = workers
            val result = TypeScriptCompiler().compileParsed(parsed, options, fileA)
            return Run(
                FrontEnd.sequentialFileBinds,
                result.diagnostics
                    .map { "${it.fileName}|${it.start}|${it.code}|${it.message}" }
                    .sorted(),
            )
        } finally {
            ParallelCheckMode.workers = saved
        }
    }

    @Test
    fun `a sequential compile binds every program file once on the caller thread`() {
        val sequential = compileWith(1)
        assert(sequential.binds == 2L)
    }

    @Test
    fun `a parallel compile performs no sequential bind at all`() {
        val parallel = compileWith(4)
        assert(parallel.binds == 0L)
    }

    /**
     * (PERF.HW.d): the per-worker census must cover every worker, or the
     * `slowest/mean` it exists to report is computed over a partial population
     * and understates the imbalance — silently, since a zero entry looks like a
     * fast worker rather than a missing one.
     */
    @Test
    fun `the per-worker census records one live entry per worker`() {
        compileWith(4)
        assert(FrontEnd.workerNanos.size == 4)
        assert(FrontEnd.workerFiles.size == 4)
        assert(FrontEnd.workerChars.size == 4)
        assert(FrontEnd.workerNanos.all { it > 0 })
    }

    /**
     * The census's file counts must PARTITION the program — the same claim
     * `BalancedFilePartitionTest` makes about the partition itself, made again
     * where it is observed, so a census that double-counted or dropped a worker
     * could not report a plausible-looking balance.
     */
    @Test
    fun `the census file counts sum to the program`() {
        compileWith(4)
        assert(FrontEnd.workerFiles.sum() == 2L)
    }

    /**
     * (PERF.HW.g): the `mergeSingleSymbol` census must be REACHED.
     *
     * Its whole purpose is to size the population blocking a shared bind, and a
     * census that reads zero because its hook sits behind a guard the caller
     * already short-circuits is indistinguishable from a real negative — round
     * 849 lost a round to exactly that, and this round's own first run put the
     * adopted-id set after the `init` block, where it was null. So the pin is
     * that the counters MOVE, and that the adopted-vs-mutated split is
     * consistent: a mutation can only reach an adopted symbol if something was
     * adopted first.
     */
    @Test
    fun `the merge census is reached and its two branches are consistent`() {
        val saved = MergeCensus.enabled
        try {
            MergeCensus.enabled = true
            FrontEnd.reset()
            compileWith(1)
            assert(FrontEnd.mergeAdopts > 0)
            assert(FrontEnd.mergeMutatesAdopted <= FrontEnd.mergeMutates)
            assert(FrontEnd.mergeMutatesAdopted <= FrontEnd.mergeAdopts)
        } finally {
            MergeCensus.enabled = saved
        }
    }

    /** Off is off: the counters must not move when the census is not armed. */
    @Test
    fun `negative control - the census is silent when it is not armed`() {
        val saved = MergeCensus.enabled
        try {
            MergeCensus.enabled = false
            FrontEnd.reset()
            compileWith(1)
            assert(FrontEnd.mergeAdopts == 0L)
            assert(FrontEnd.mergeMutates == 0L)
        } finally {
            MergeCensus.enabled = saved
        }
    }

    @Test
    fun `the skip is behaviour-free - both worker counts report the same diagnostics`() {
        val sequential = compileWith(1)
        val parallel = compileWith(4)
        // Guards the comparison against being vacuous: a fixture that produced no
        // diagnostics would satisfy the equality no matter what the workers did.
        assert(sequential.diagnostics.isNotEmpty())
        assert(parallel.diagnostics == sequential.diagnostics)
    }
}
