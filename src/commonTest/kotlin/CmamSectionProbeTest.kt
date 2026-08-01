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
 * (ENGINE.2c) round 789: pins level R — the partition of `checkMemberAccessMissing`,
 * level Q's engine row and the largest single leaf in the compile (2,292 ms gross
 * over 66,747 calls at 34.3 µs each, re-measured at HEAD).
 *
 * What each pin protects, in the order the round needed them.
 *
 * The FIRST is that the probe cannot change what the compiler says, in any mode.
 * Level R's boundaries sit in a ~1,965-line function with ~20 early `return`s, and
 * three of its blocks were RESTRUCTURED to make the sub-measures possible (the
 * gate condition and the two suppression verdicts were lifted into `val`s so a
 * timestamp can close before the `return`). A lost short-circuit or an inverted
 * verdict shows up here as a diagnostics difference between OFF and ON.
 *
 * The SECOND is that level R does not recurse. `invocationsRNested` must stay 0,
 * or the `depth != 1 ⇒ return` shape would charge a nested descent to the outer
 * invocation's open row.
 *
 * The THIRD is that the rows PARTITION the invocations: every call exits in
 * exactly one row, so the exit census sums to the invocation count. That census
 * is the round's central instrument — it is what shows that 94.6% of the calls
 * which launch a flow walk go on to exit in the receiver-type branch, long before
 * any emission site.
 *
 * The FOURTH is that the walker-restricted census is a SUBSET of the full one,
 * row by row. If `rWalked` ever leaked across invocations (it is reset in
 * `beginR`, not in `endR`), a row would report more walkers than exits and every
 * population derived from it would be inflated.
 *
 * The FIFTH is the block-1 funnel the round's arithmetic rests on: the retry walk
 * runs only where the plain walk did not suppress, so its call count is at most
 * the plain walk's, and the DIFFERENCE is exactly the number of suppressions the
 * plain walk made. That subtraction is how "488 ms bought 28 suppressions" was
 * obtained — without this invariant it would be an inference rather than a count.
 *
 * The SIXTH is the calibration counterpart: under COARSE every interior boundary
 * must be inert, so all of level R's time lands in the wrapper row and every
 * other row is empty. That is what makes the ON-vs-COARSE difference a measure of
 * the boundaries themselves rather than of two different spans.
 */
class CmamSectionProbeTest {

    private val source = """
        interface Base { kind: string }
        interface Sub extends Base { extra: number }
        declare function isSub(x: Base): x is Sub
        declare function sink(v: unknown): void
        // The receiver's RAW type lacks `extra`, so the round-489 pre-gate lets the
        // flow walk run — and the guard narrows Base DOWN to Sub, which has it, so
        // the call exits inside the flow-suppression row.
        function suppressed(b: Base): number {
            if (isSub(b)) { return b.extra }
            return 0
        }
        // Here the walk runs and does NOT suppress, so the loop-entry retry runs
        // too and the function falls through to its emission — which is what keeps
        // the behaviour-free pin non-vacuous.
        function emitted(b: Base): void {
            if (b.kind === "x") { sink(b.nothere) }
        }
        // A receiver whose property IS on the raw type: the pre-gate answers before
        // any walk, so this call contributes to the block-1 entries and not to the
        // walks.
        function resolved(b: Base): string {
            return b.kind
        }
    """.trimIndent()

    private fun diagnosticsUnder(mode: Int): List<String> {
        // SAVE-AND-RESTORE, never "assign the default back" — the mode is
        // fork-global (the round-619 Inv0PassTimingTest lesson).
        val saved = CpaSections.mode
        CpaSections.reset()
        CpaSections.mode = mode
        try {
            return diagnose(source).map { "${it.code}@${it.start}:${it.length} ${it.message}" }
        } finally {
            CpaSections.mode = saved
            CpaSections.reset()
        }
    }

    private fun runProbe(mode: Int = CpaSections.ON) {
        val saved = CpaSections.mode
        CpaSections.reset()
        CpaSections.mode = mode
        try {
            diagnose(source)
        } finally {
            CpaSections.mode = saved
        }
    }

    @Test
    fun `the level-R name table is index-aligned and complete`() {
        assert(CpaSections.rNames.size == CpaSections.NR)
        assert(CpaSections.nNames.size == CpaSections.NN)
        // The partition opens on its wrapper transition, so no work can be charged
        // to a row that has not been entered yet, and it closes on the emission
        // tail — which on the compiler profile is never reached at all.
        assert(CpaSections.R_ENTRY == 0)
        assert(CpaSections.R_EMIT == CpaSections.NR - 1)
    }

    @Test
    fun `the probe is behaviour-free when off`() {
        val off = diagnosticsUnder(CpaSections.OFF)
        assert(off.isNotEmpty())
        assert(diagnosticsUnder(CpaSections.ON) == off)
        assert(diagnosticsUnder(CpaSections.COARSE) == off)
        assert(diagnosticsUnder(CpaSections.CENSUS) == off)
    }

    @Test
    fun `the instrumented function still emits its own diagnostic`() {
        // Without this the behaviour-free pin above would compare two lists that
        // never reached the instrumented tail.
        val on = diagnosticsUnder(CpaSections.ON)
        assert(on.any { it.startsWith("2339@") })
    }

    @Test
    fun `level R does not recurse and its exits sum to its invocations`() {
        runProbe()
        assert(CpaSections.invocationsR > 0)
        assert(CpaSections.invocationsRNested == 0L)
        var exits = 0L
        for (s in 0 until CpaSections.NR) exits += CpaSections.rExitIn[s]
        assert(exits == CpaSections.invocationsR)
    }

    @Test
    fun `the walker census is a row-wise subset of the exit census`() {
        runProbe()
        var walkers = 0L
        for (s in 0 until CpaSections.NR) {
            assert(CpaSections.rExitWalk[s] <= CpaSections.rExitIn[s])
            walkers += CpaSections.rExitWalk[s]
        }
        assert(walkers <= CpaSections.invocationsR)
        // The fixture is built so the walk actually runs — a zero here would make
        // the subset pin vacuous.
        assert(walkers > 0)
    }

    @Test
    fun `the flow-suppression row both fires and suppresses`() {
        runProbe()
        assert(CpaSections.rExitIn[CpaSections.R_FLOW] > 0)
        assert(CpaSections.rExitWalk[CpaSections.R_FLOW] > 0)
    }

    @Test
    fun `the block-1 funnel narrows at every stage`() {
        runProbe()
        val entries = CpaSections.nCalls[CpaSections.N_F1_RAW]
        val gate = CpaSections.nCalls[CpaSections.N_F1_GATE]
        val plain = CpaSections.nCalls[CpaSections.N_F1_WALK]
        val retry = CpaSections.nCalls[CpaSections.N_F1_WALK2]
        assert(entries > 0)
        // The raw type and the pre-gate are evaluated together, once per entry.
        assert(gate == entries)
        // Only the calls that pass the pre-gate walk, and only the walks that did
        // not already suppress are retried — so the difference IS the plain walk's
        // suppression count.
        assert(plain <= entries)
        assert(retry <= plain)
        assert(plain > 0)
    }

    @Test
    fun `COARSE keeps the span and drops every interior boundary`() {
        runProbe(CpaSections.COARSE)
        assert(CpaSections.invocationsR > 0)
        assert(CpaSections.rNanos[CpaSections.R_ENTRY] > 0)
        // Every other row must be empty: that is what makes the ON-vs-COARSE
        // difference a measure of the boundaries and not of two different spans.
        var interior = 0L
        for (s in 1 until CpaSections.NR) interior += CpaSections.rNanos[s]
        assert(interior == 0L)
        assert(CpaSections.rExitIn[CpaSections.R_ENTRY] == CpaSections.invocationsR)
    }

    @Test
    fun `CENSUS reads no level-R timestamps but still counts invocations`() {
        runProbe(CpaSections.CENSUS)
        assert(CpaSections.invocationsR > 0)
        var rows = 0L
        for (s in 0 until CpaSections.NR) rows += CpaSections.rNanos[s]
        assert(rows == 0L)
    }
}
