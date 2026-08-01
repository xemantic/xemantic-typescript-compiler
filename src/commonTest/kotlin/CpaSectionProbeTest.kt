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
 * (ENGINE.2) round 787: pins the two-level attribution harness for
 * `checkPropertyAccessInExpr` (level P, RECURSIVE) and
 * `checkSinglePropertyAccess` (level Q, not).
 *
 * Six invariants.
 *
 * The FIRST is that turning the probe on cannot change what the compiler says —
 * in any of its three modes. The boundaries live inside a 300-line recursive
 * walker and a leaf with several early `return`s, and both were wrapped in a
 * `try`/`finally` rather than split into a `…Core`, so a lost short-circuit
 * shows up here as a diagnostics difference between OFF and ON.
 *
 * The SECOND is that level P's recursion is accounted by round 756's hand-back
 * shape, not by `depth != 1 ⇒ return`: `beginP` returns the caller's running row
 * and `endP` reopens it. The observable consequence is that the walk descends —
 * `maxDepthP > 1` on any nested expression — while every invocation is still
 * counted exactly once.
 *
 * The THIRD is that level Q does NOT recurse. `invocationsQNested` must stay 0;
 * if a future change makes `checkSinglePropertyAccess` re-enter itself, the
 * `depth != 1 ⇒ return` shape would silently charge the nested descent to the
 * outer invocation's open row, and this pin fails instead.
 *
 * The FOURTH is that level Q's rows partition its invocations exactly: every
 * invocation exits in exactly one row, so the exits sum to the invocations.
 *
 * The FIFTH is the window. Level P is timed only inside `cpaSpineLeave`'s anchor
 * blocks, so `invocationsP + invocationsPOutside` must account for every visit —
 * and the arm census must sum to `invocationsP`, which is what makes "the walk
 * visits each node exactly once" a measurement rather than an impression.
 *
 * The SIXTH is the (file, nodeId) key. `indexSourceFile` restarts `nodeId` at 0
 * for every `SourceFile`, so a program-wide set of raw ids collapses one node per
 * file onto each id — which is exactly how round 787's first G4 reading came out
 * at 2.35x instead of 1.00. Two different files must produce two different keys
 * for the same id.
 */
class CpaSectionProbeTest {

    private val source = """
        interface Inner { deep: string }
        interface Outer { inner: Inner; count: number }
        declare function pick<T>(items: T[], f: (x: T) => boolean): T
        declare function plain(n: number): number
        function use(o: Outer, list: Outer[]): string {
            // a nested property-access chain — level Q runs once per link, and
            // the walk must reach the receiver before the access.
            const s = o.inner.deep
            // a call whose argument CAN consume a contextual type (an arrow),
            // and one whose argument cannot (a plain expression) — the two
            // populations `cpaComputeArgCtxTypes` was split by.
            const found = pick(list, (item) => item.count > 0)
            const n = plain(o.count)
            // an object literal and a conditional, so more than one arm of the
            // walker is exercised.
            const bag = { a: o.inner, b: n > 0 ? o.count : found.count }
            return s + bag.a.deep
        }
        // The instrumented leaf's OWN emission, so an ON/OFF comparison of the
        // diagnostics is not vacuous: a missing member is TS2339, emitted from
        // `checkMemberAccessMissing`, the row this probe calls THE ENGINE.
        declare const bad: Inner
        const oops = bad.nope
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
    fun `the section-name tables are index-aligned and complete`() {
        assert(CpaSections.pNames.size == CpaSections.NP)
        assert(CpaSections.qNames.size == CpaSections.NQ)
        assert(CpaSections.nNames.size == CpaSections.NN)
        assert(CpaSections.pArmNames.size == CpaSections.NPA)
        // Both partitions open on their wrapper transition, so no work can be
        // charged to a row that has not been entered yet.
        assert(CpaSections.P_ENTRY == 0)
        assert(CpaSections.Q_ENTRY == 0)
        // The ENGINE is the LAST row of level Q — everything before it is the
        // dedicated-walker layer, which is what makes the split readable.
        assert(CpaSections.Q_MISSING == CpaSections.NQ - 1)
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
    fun `the instrumented leaf still emits its own diagnostic`() {
        // Without this the behaviour-free pin above would be vacuous: it would
        // compare two empty lists.
        val on = diagnosticsUnder(CpaSections.ON)
        assert(on.any { it.startsWith("2339@") })
    }

    @Test
    fun `level P partitions a recursive walk and every row is self time`() {
        runProbe()
        assert(CpaSections.invocationsP > 0)
        assert(CpaSections.outermostP > 0)
        // The walker descends — that is what makes the hand-back shape necessary
        // rather than decorative.
        assert(CpaSections.maxDepthP > 1)
        // Outermost invocations are a strict subset: nested ones exist.
        assert(CpaSections.outermostP < CpaSections.invocationsP)
        var rows = 0L
        for (s in 0 until CpaSections.NP) rows += CpaSections.pNanos[s]
        assert(rows > 0)
    }

    @Test
    fun `the arm census accounts for every in-window invocation`() {
        runProbe()
        var arms = 0L
        for (a in 0 until CpaSections.NPA) arms += CpaSections.pArm[a]
        assert(arms == CpaSections.invocationsP)
        // The fixture reaches the three arms the partition has dedicated rows for.
        assert(CpaSections.pArm[CpaSections.PA_PROPACCESS] > 0)
        assert(CpaSections.pArm[CpaSections.PA_CALL] > 0)
        assert(CpaSections.pArm[CpaSections.PA_OBJLIT] > 0)
    }

    @Test
    fun `level Q does not recurse and its exits sum to its invocations`() {
        runProbe()
        assert(CpaSections.invocationsQ > 0)
        // The whole `depth != 1 ⇒ return` shape rests on this.
        assert(CpaSections.invocationsQNested == 0L)
        var exits = 0L
        for (s in 0 until CpaSections.NQ) exits += CpaSections.qExitIn[s]
        assert(exits == CpaSections.invocationsQ)
        // Every invocation that is not short-circuited leaves in the ENGINE row;
        // the fixture has no `super`/`prototype`/const-enum shapes, so all of them do.
        assert(CpaSections.qExitIn[CpaSections.Q_MISSING] == CpaSections.invocationsQ)
    }

    @Test
    fun `level P sees every walk from the spine window and none outside it`() {
        runProbe()
        // `cpaSpineLeave` is the only caller on a well-formed program; a future
        // legacy-walker call site would show up here rather than silently
        // landing outside the partition.
        assert(CpaSections.invocationsPOutside == 0L)
    }

    @Test
    fun `CENSUS reads no timestamps but still counts`() {
        runProbe(CpaSections.CENSUS)
        assert(CpaSections.invocationsP > 0)
        assert(CpaSections.invocationsQ > 0)
        // No row may accumulate time — that is the whole point of the mode: the
        // distinct-node sets it maintains would otherwise be charged to whatever
        // row happened to be open.
        var rows = 0L
        for (s in 0 until CpaSections.NP) rows += CpaSections.pNanos[s]
        for (s in 0 until CpaSections.NQ) rows += CpaSections.qNanos[s]
        for (s in 0 until CpaSections.NN) rows += CpaSections.nNanos[s]
        assert(rows == 0L)
        // G4: the walk visits each node exactly once.
        assert(CpaSections.distinctP.size.toLong() == CpaSections.invocationsP)
        assert(CpaSections.distinctPa.size.toLong() == CpaSections.invocationsQ)
    }

    @Test
    fun `the distinct-node key separates equal nodeIds in different files`() {
        // `indexSourceFile` restarts nodeId at 0 per SourceFile, so keying a
        // program-wide set by the raw id collapses one node per file onto each
        // id and inflates any visits/distinct ratio. Round 787 read 2.35x that
        // way before checking.
        assert(CpaSections.nodeKey("/a.ts", 7) != CpaSections.nodeKey("/b.ts", 7))
        assert(CpaSections.nodeKey("/a.ts", 7) == CpaSections.nodeKey("/a.ts", 7))
        assert(CpaSections.nodeKey("/a.ts", 7) != CpaSections.nodeKey("/a.ts", 8))
    }
}
