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
 * (TYPE.2)(a): pins the two-level attribution harness for
 * `spineCtaM3StatementAnchor` (level A) and `checkVarDeclAssignability`
 * (level B).
 *
 * Five invariants.
 *
 * The FIRST is that turning the probe on cannot change what the compiler says.
 * The boundaries live inside a 1,466-line production function with roughly
 * forty early `return`s, and four of them REWROTE an expression to name its
 * value so a sub-measure could bracket it — the source-type computation, the
 * two narrowing calls, the narrowing's confirming relation and the
 * assignability relation itself. A short-circuit lost in one of those rewrites
 * shows up here as a diagnostics difference between OFF and ON.
 *
 * The SECOND is that BOTH levels partition their function independently: level
 * A opens on the handler (so the eligibility gate and its parent-chain climbs
 * are a row rather than an unmeasured remainder) and level B opens on the
 * function, nested inside level A's `A_VDECL` row.
 *
 * The THIRD is the exit profile: level B's `finally` records which row the
 * invocation left in, so the exits must sum to the invocations exactly. That
 * identity is what turns "86% of the invocations are unannotated declarations"
 * into a measurement rather than an impression.
 *
 * The FOURTH is that `mode = COARSE` — the differential calibration
 * counterpart — still partitions the same wall time with only the anchors, so
 * an ON-versus-COARSE comparison prices a boundary rather than a code path.
 *
 * The FIFTH is that a nested sub-measure is never reached more often than the
 * partition row that contains it.
 */
class CtaSectionProbeTest {

    private val source = """
        interface Weak { nope?: string }
        interface Shape { kind: number; text: string }
        function make(): Shape { return { kind: 1, text: "a" } }
        function outer(flag: boolean): number {
            // an UNANNOTATED declaration — the population that turned out to be
            // 86% of the function's invocations.
            const inferred = make()
            // an ANNOTATED declaration whose initializer relates.
            const shaped: Shape = make()
            // a declaration whose initializer is a function body the handler
            // also walks (walkFunctionBodiesInExpr).
            const fn = (v: number) => v + inferred.kind
            let total = fn(shaped.kind)
            if (flag) { total = total + 1 }
            return total
        }
        outer(true)
        // The instrumented function's OWN emission, so an ON/OFF comparison of
        // the diagnostics is not vacuous: a var-decl mismatch is TS2322,
        // emitted from the elaboration section this probe partitions.
        const bad: number = "nope"
        const weak: Weak = { other: 1 }
    """.trimIndent()

    private fun diagnosticsUnder(mode: Int): List<String> {
        // SAVE-AND-RESTORE, never "assign the default back" — the mode is
        // fork-global (the round-619 Inv0PassTimingTest lesson).
        val saved = CtaSections.mode
        CtaSections.reset()
        CtaSections.mode = mode
        try {
            return diagnose(source).map { "${it.code}@${it.start}:${it.length} ${it.message}" }
        } finally {
            CtaSections.mode = saved
            CtaSections.reset()
        }
    }

    private fun runProbe(mode: Int = CtaSections.ON) {
        val saved = CtaSections.mode
        CtaSections.reset()
        CtaSections.mode = mode
        try {
            diagnose(source)
        } finally {
            CtaSections.mode = saved
        }
    }

    @Test
    fun `the section-name table is index-aligned and complete`() {
        assert(CtaSections.names.size == CtaSections.N)
        assert(CtaSections.FIRST_NESTED == CtaSections.N_GET_TYPE_OF_EXPR)
        assert(CtaSections.OVERHEAD == CtaSections.N - 1)
        assert(CtaSections.OVERHEAD_FIRST == CtaSections.N - 2)
        assert(CtaSections.B_ENTRY == CtaSections.N - 3)
        assert(CtaSections.A_ENTRY == CtaSections.N - 4)
        // The two partitions are contiguous and adjacent.
        assert(CtaSections.A_GATE == 0)
        assert(CtaSections.B_BINDPAT == CtaSections.A_RESTORE + 1)
        assert(CtaSections.B_TAIL == CtaSections.FIRST_NESTED - 1)
        // COARSE keeps one anchor per level plus both wrapper transitions, so
        // each partition still covers the whole of its function.
        assert(CtaSections.coarseAnchor[CtaSections.A_GATE])
        assert(CtaSections.coarseAnchor[CtaSections.A_VDECL])
        assert(CtaSections.coarseAnchor[CtaSections.B_BINDPAT])
        assert(!CtaSections.coarseAnchor[CtaSections.B_UNANNOT])
        assert(!CtaSections.coarseAnchor[CtaSections.B_RELATION])
    }

    @Test
    fun `the probe is behaviour-free when off`() {
        val off = diagnosticsUnder(CtaSections.OFF)
        val on = diagnosticsUnder(CtaSections.ON)
        val coarse = diagnosticsUnder(CtaSections.COARSE)
        assert(on == off)
        assert(coarse == off)
        // Not vacuous: the fixture emits the INSTRUMENTED function's own code,
        // so a lost short-circuit in a bracketed gate would diverge here.
        assert(off.any { it.startsWith("2322@") })
    }

    @Test
    fun `nothing is recorded while the probe is off`() {
        val saved = CtaSections.mode
        CtaSections.reset()
        CtaSections.mode = CtaSections.OFF
        try {
            diagnose(source)
        } finally {
            CtaSections.mode = saved
        }
        assert(CtaSections.calls.sum() == 0L)
        assert(CtaSections.invocationsA == 0L)
        assert(CtaSections.invocationsB == 0L)
        assert(CtaSections.declarations == 0L)
        assert(CtaSections.vdBucketCalls.sum() == 0L)
        assert(CtaSections.stmtKind.sum() == 0L)
    }

    @Test
    fun `level A opens on the handler so the eligibility gate is a row`() {
        runProbe()
        val invocations = CtaSections.invocationsA
        // The handler is consulted about EVERY node, so its invocation count is
        // far larger than the number of statements that anchor.
        assert(invocations > CtaSections.stmtKind.sum())
        assert(CtaSections.calls[CtaSections.A_ENTRY] == invocations)
        // The gate row is reached at least once per invocation: once on entry,
        // and once more whenever an anchor handed the clock back.
        assert(CtaSections.calls[CtaSections.A_GATE] >= invocations)
        // Every anchored statement installs the ambient exactly once.
        val anchored = CtaSections.anchorMode.sum()
        assert(anchored > 0L)
        assert(CtaSections.calls[CtaSections.A_SETUP] == anchored)
        assert(CtaSections.calls[CtaSections.A_RESTORE] == anchored)
        assert(CtaSections.stmtKind.sum() == anchored)
        // The fixture anchors declarations, an assignment and a return.
        assert(CtaSections.declarations > 0L)
        assert(CtaSections.calls[CtaSections.A_VDECL] == CtaSections.declarations)
        CtaSections.reset()
    }

    @Test
    fun `level B partitions every invocation and the exits sum to it`() {
        runProbe()
        val invocations = CtaSections.invocationsB
        assert(invocations > 0L)
        assert(CtaSections.calls[CtaSections.B_ENTRY] == invocations)
        assert(CtaSections.calls[CtaSections.B_BINDPAT] == invocations)
        // Level B is reached only from level A in this fixture.
        assert(CtaSections.invocationsBFromA == invocations)
        // Every invocation left in exactly one row.
        var exits = 0L
        for (s in CtaSections.B_BINDPAT..CtaSections.B_TAIL) exits += CtaSections.exitIn[s]
        assert(exits == invocations)
        // The cost buckets cover the same population.
        assert(CtaSections.vdBucketCalls.sum() == invocations)
        // The sections are sequential: reach falls monotonically from the
        // annotation split onward, which is what makes the exit profile a
        // partition rather than an overlay.
        for (s in CtaSections.B_RECORD until CtaSections.B_TAIL) {
            assert(CtaSections.calls[s] >= CtaSections.calls[s + 1])
        }
        CtaSections.reset()
    }

    @Test
    fun `the nested sub-measures are rarer than the rows that contain them`() {
        runProbe()
        assert(CtaSections.calls[CtaSections.N_TYPE_NODE] == CtaSections.calls[CtaSections.B_TARGET])
        assert(CtaSections.calls[CtaSections.N_CANUSE] == CtaSections.calls[CtaSections.B_RELATION])
        assert(CtaSections.calls[CtaSections.N_REL_CALL] <= CtaSections.calls[CtaSections.B_RELATION])
        assert(CtaSections.calls[CtaSections.N_GET_TYPE_OF_EXPR] <= CtaSections.calls[CtaSections.B_SRCTYPE])
        assert(CtaSections.calls[CtaSections.N_NARROW_CALL] <= CtaSections.calls[CtaSections.B_NARROW])
        // A walk that returned its input is a subset of the walks taken.
        assert(CtaSections.calls[CtaSections.N_NARROW_IDENTITY] <= CtaSections.calls[CtaSections.N_NARROW_CALL])
        CtaSections.reset()
    }

    @Test
    fun `COARSE keeps the partition while skipping the interior boundaries`() {
        runProbe(CtaSections.COARSE)
        val coarseA = CtaSections.calls[CtaSections.A_GATE] + CtaSections.calls[CtaSections.A_VDECL]
        val coarseB = CtaSections.calls[CtaSections.B_BINDPAT]
        assert(coarseA > 0L)
        assert(coarseB > 0L)
        // No interior boundary fires.
        assert(CtaSections.calls[CtaSections.A_ASSIGN] == 0L)
        assert(CtaSections.calls[CtaSections.B_UNANNOT] == 0L)
        assert(CtaSections.calls[CtaSections.N_REL_CALL] == 0L)
        val coarseTotal = CtaSections.calls.sum()
        CtaSections.reset()
        runProbe(CtaSections.ON)
        val onTotal = CtaSections.calls.sum()
        // The ON run pays strictly more boundaries for the same wall time —
        // that difference is the calibration.
        assert(onTotal > coarseTotal)
        CtaSections.reset()
    }

    // ── (ENGINE.1) level C: checkReturnAssignability ─────────────────────────

    @Test
    fun `the level-C name table is index-aligned and complete`() {
        assert(CtaSections.cNames.size == CtaSections.NC)
        assert(CtaSections.C_ENTRY == 0)
        assert(CtaSections.C_STRTAIL == CtaSections.NC - 1)
        // Source order: every row the partition walks through is between the
        // wrapper transition and the string fallback.
        assert(CtaSections.C_TARGET < CtaSections.C_SRCTYPE)
        assert(CtaSections.C_SRCTYPE < CtaSections.C_RELATION)
        assert(CtaSections.C_RELATION < CtaSections.C_ELAB)
    }

    @Test
    fun `level C partitions every invocation and the exits sum to it`() {
        runProbe()
        val invocations = CtaSections.invocationsC
        assert(invocations > 0L)
        // The partition opens on the wrapper, so every invocation is counted
        // there exactly once whatever early return it takes.
        assert(CtaSections.cCalls[CtaSections.C_ENTRY] == invocations)
        assert(CtaSections.cExitIn.sum() == invocations)
        // The fixture's annotated `return total` reaches the relation.
        assert(CtaSections.cCalls[CtaSections.C_RELATION] > 0L)
        CtaSections.reset()
    }

    @Test
    fun `level C is a single row under COARSE - its calibration counterpart`() {
        runProbe(CtaSections.COARSE)
        val invocations = CtaSections.invocationsC
        assert(invocations > 0L)
        // Exactly one boundary pair per invocation, all of it in the anchor.
        assert(CtaSections.cCalls[CtaSections.C_ENTRY] == invocations)
        assert(CtaSections.cCalls.sum() == invocations)
        assert(CtaSections.cCalls[CtaSections.C_SRCTYPE] == 0L)
        assert(CtaSections.cCalls[CtaSections.C_RELATION] == 0L)
        CtaSections.reset()
    }

    @Test
    fun `nothing is recorded for level C while the probe is off`() {
        val saved = CtaSections.mode
        CtaSections.reset()
        CtaSections.mode = CtaSections.OFF
        try {
            diagnose(source)
        } finally {
            CtaSections.mode = saved
        }
        assert(CtaSections.invocationsC == 0L)
        assert(CtaSections.cCalls.sum() == 0L)
        assert(CtaSections.cExitIn.sum() == 0L)
        CtaSections.reset()
    }
}
