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
 * (ENGINE.2d)(a) round 790: pins the gate that skips the round-425 loop-entry
 * RETRY walk inside `checkMemberAccessMissing`'s block 1 — 528 ms over 21,384
 * walks at HEAD, for at most 28 suppressions.
 *
 * **The invariant that makes the skip safe.** `narrowTypeFromFlow` and
 * `narrowTypeFromFlowFollowLoopEntry` are line-by-line mirrors whose ONLY
 * behavioural difference is the `FlowLoopLabel` arm — the plain walker washes to
 * the declared type there, the mirror follows `antecedents[0]`. Every other arm,
 * the fast-forward loop, the budgets, the `seen` set and both memos are
 * identical. So a plain walk that ARRIVED at no `FlowLoopLabel` makes exactly the
 * traversal the mirror would have made, and the mirror is a pure repeat of it:
 * same nodes, same resolutions (all of them already cached by the first walk),
 * same result. Anything that makes the plain walk's own traversal unobservable —
 * it never ran (no reference path, no flow node, flow analysis disabled, or a
 * serve from the round-664 inter-walk memo), or it TRUNCATED and saw only a
 * prefix — is treated as unknown, and the retry runs.
 *
 * **Why these pins do not need an ablated binary.** `--verifyLoopRetry` keeps the
 * PRE-gate behaviour (the retry runs at every call and is honoured) and counts
 * the calls where skipping it would have changed the answer, so the old
 * implementation is its own reference — round 788's `ClosureIndexEquivalence`
 * shape. And `--verifyLoopRetryAll` extends that comparison to the population the
 * gate never skips, which is the CONTROL: it must find divergences, or the zero
 * over the skippable population would be the reading of a dead instrument
 * (CLAUDE.md's "record a deliberately BOGUS baseline" rule, obtained here without
 * a bogus baseline because the loop-crossing calls really do disagree).
 */
class LoopEntryRetryGateTest {

    /**
     * Five shapes, chosen so that every population the gate distinguishes is
     * non-empty: A and B need the retry, C is answered before it, D is skippable,
     * E crosses a loop and cannot be saved by the retry either.
     */
    private val source = """
        interface Base { kind: string }
        interface Sub extends Base { extra: number }
        declare function isSub(x: Base): x is Sub
        declare function sink(v: unknown): void

        // A: a guard, then a loop reading the narrowed member INSIDE it. The plain
        // walk washes at the FlowLoopLabel; only the retry sees the guard.
        function loopBody(b: Base, n: number): void {
            if (isSub(b)) {
                while (n > 0) { sink(b.extra); n--; }
            }
        }
        // B: the same through an early-return guard and a `for` head.
        function earlyReturn(b: Base, n: number): void {
            if (!isSub(b)) return;
            for (let i = 0; i < n; i++) { sink(b.extra); }
        }
        // C: the plain walk suppresses on its own, so the retry is never reached.
        function noLoop(b: Base): void {
            if (isSub(b)) { sink(b.extra); }
        }
        // D: the walk RUNS, does not suppress, and crosses no loop — SKIPPABLE.
        function noLoopNoSuppress(b: Base): void {
            if (b.kind == "x") { sink(b.nothere); }
        }
        // E: a loop-crossing walk that the retry cannot save either — the emission
        // that keeps the behaviour-free comparison non-vacuous.
        function inLoopNoSuppress(b: Base, n: number): void {
            while (n > 0) { sink(b.alsoNotThere); n--; }
        }
    """.trimIndent()

    /**
     * (CHK.69) The loop-crossing CONTROL population, which [source] no longer
     * supplies. Since (CHK.69) a `FlowLoopLabel` whose body cannot affect the
     * reference is answered by FOLLOWING ITS ENTRY, so the plain walk and the
     * mirror agree on A and B and the control's `typeDiff > 0` read ZERO — a
     * dead instrument wearing the same face as a passing gate. A loop that
     * ASSIGNS the guarded reference still washes to the declared type in the
     * plain walk (the assignment is on a back edge, so
     * [Checker.loopBodyMayAffectName] claims it) while the mirror follows
     * `antecedents[0]` regardless, which is the divergence the control needs.
     */
    private val loopCrossing = """
        interface Base { kind: string }
        interface Sub extends Base { extra: number }
        declare function isSub(x: Base): x is Sub
        declare function mkBase(): Base
        declare function sink(v: unknown): void

        function loopAssignsWhile(b: Base, n: number): void {
            if (isSub(b)) {
                while (n > 0) { sink(b.extra); b = mkBase(); n--; }
            }
        }
        function loopAssignsFor(b: Base, n: number): void {
            if (!isSub(b)) return;
            for (let i = 0; i < n; i++) { sink(b.extra); b = mkBase(); }
        }
    """.trimIndent()

    /** [source] with A's and B's guards removed — the negative control. */
    private val unguarded = """
        interface Base { kind: string }
        interface Sub extends Base { extra: number }
        declare function sink(v: unknown): void

        function loopBody(b: Base, n: number): void {
            while (n > 0) { sink(b.extra); n--; }
        }
        function earlyReturn(b: Base, n: number): void {
            for (let i = 0; i < n; i++) { sink(b.extra); }
        }
    """.trimIndent()

    private fun codes(src: String): List<String> =
        diagnose(src).map { "${it.code}@${it.start}" }

    /**
     * Run [src] with the verifier configured, returning the four counters as
     * (skippable, verified, typeDiff, verdictDiff). SAVE-AND-RESTORE, never
     * "assign the default back" — these are fork-global (the round-619
     * `Inv0PassTimingTest` lesson).
     */
    private fun verify(all: Boolean, src: String = source): List<Long> {
        val savedOne = CpaSections.verifyLoopRetry
        val savedAll = CpaSections.verifyLoopRetryAll
        val savedMode = CpaSections.mode
        CpaSections.reset()
        CpaSections.verifyLoopRetry = true
        CpaSections.verifyLoopRetryAll = all
        try {
            diagnose(src)
            return listOf(
                CpaSections.retrySkippable,
                CpaSections.retryVerified,
                CpaSections.retryVerifyTypeDiff,
                CpaSections.retryVerifyVerdictDiff,
            )
        } finally {
            CpaSections.verifyLoopRetry = savedOne
            CpaSections.verifyLoopRetryAll = savedAll
            CpaSections.mode = savedMode
            CpaSections.reset()
        }
    }

    @Test
    fun `the round-425 loop-entry shapes still suppress with the gate live`() {
        // A and B read a member that exists only on the NARROWED type, from inside
        // a loop. If the gate ever skipped a retry that was needed, these become
        // TS2339 — which is the whole risk the item names.
        val d = diagnose(source)
        assert(d.count { it.code == 2339 } == 2)
        assert(d.none { it.message.contains("'extra'") })
        assert(d.any { it.message.contains("'nothere'") })
        assert(d.any { it.message.contains("'alsoNotThere'") })
    }

    @Test
    fun `negative control - the same loop reads emit once their guard is removed`() {
        // Without this the pin above would be satisfied by a compiler that never
        // reports TS2339 on these shapes at all.
        val d = diagnose(unguarded)
        assert(d.count { it.code == 2339 && it.message.contains("'extra'") } == 2)
    }

    @Test
    fun `the gate emits exactly what the pre-gate binary emitted`() {
        // `--verifyLoopRetry` restores the pre-gate behaviour in the same binary,
        // so this is a direct old-vs-new comparison rather than an argument.
        val gated = codes(source)
        val savedOne = CpaSections.verifyLoopRetry
        val preGate: List<String>
        try {
            CpaSections.verifyLoopRetry = true
            preGate = codes(source)
        } finally {
            CpaSections.verifyLoopRetry = savedOne
        }
        assert(gated == preGate)
        assert(gated.isNotEmpty())
    }

    @Test
    fun `the verifier finds no divergence over the skippable population`() {
        val (skippable, verified, typeDiff, verdictDiff) = verify(all = false)
        assert(skippable > 0)
        assert(verified == skippable)
        // The retry returned the IDENTICAL Type instance every time, so the skip
        // cannot change a downstream verdict either.
        assert(typeDiff == 0L)
        assert(verdictDiff == 0L)
    }

    @Test
    fun `the control - the verifier DOES diverge over the loop-crossing population`() {
        val (skippable, verified, typeDiff, verdictDiff) = verify(all = true, src = loopCrossing)
        // Strictly more calls are compared than in the skippable-only run, and the
        // extra ones are exactly the calls the gate never skips.
        assert(verified > skippable)
        // The two loop-ASSIGNING shapes: the loop-entry walker reaches a different
        // type AND suppresses where the plain walk did not. This is what proves the
        // instrument fires. See [loopCrossing] for why [source] no longer can.
        assert(typeDiff > 0L)
        assert(verdictDiff > 0L)
    }

    @Test
    fun `the loop-crossing divergences are exactly the calls the gate keeps`() {
        val skippableRun = verify(all = false, src = loopCrossing)
        val allRun = verify(all = true, src = loopCrossing)
        // Every divergence lives OUTSIDE the skipped population — which is the
        // arithmetic form of the equivalence claim.
        assert(allRun[1] - skippableRun[1] >= allRun[2])
        assert(allRun[2] == 2L)
        assert(allRun[3] == 2L)
    }

    @Test
    fun `the counters stay inert on the production path`() {
        val savedOne = CpaSections.verifyLoopRetry
        val savedMode = CpaSections.mode
        CpaSections.reset()
        CpaSections.verifyLoopRetry = false
        CpaSections.mode = CpaSections.OFF
        try {
            diagnose(source)
            // The gate itself is live, but nothing writes the shared probe object
            // — a worker thread must never race on it.
            assert(CpaSections.retrySkippable == 0L)
            assert(CpaSections.retryVerified == 0L)
        } finally {
            CpaSections.verifyLoopRetry = savedOne
            CpaSections.mode = savedMode
            CpaSections.reset()
        }
    }

    @Test
    fun `the gate skips some retries and keeps others`() {
        val savedMode = CpaSections.mode
        CpaSections.reset()
        CpaSections.mode = CpaSections.ON
        val plain: Long
        val retry: Long
        val skippable: Long
        try {
            diagnose(source)
            plain = CpaSections.nCalls[CpaSections.N_F1_WALK]
            retry = CpaSections.nCalls[CpaSections.N_F1_WALK2]
            skippable = CpaSections.retrySkippable
        } finally {
            CpaSections.mode = savedMode
            CpaSections.reset()
        }
        assert(plain > 0)
        // Live: at least one retry was skipped ...
        assert(skippable > 0)
        // ... and at least one was kept, so the gate is a filter, not a deletion.
        assert(retry > 0)
        assert(retry < plain)
    }

    /** Every OTHER `FlowLoopLabel` flavour the parser can produce, plus a
     *  property-PATH receiver (block 1 admits those alongside identifiers). */
    private val loopFlavours = """
        interface Base { kind: string }
        interface Sub extends Base { extra: number }
        interface Holder { inner: Base }
        declare function isSub(x: Base): x is Sub
        declare function sink(v: unknown): void
        declare const items: number[]

        function doWhile(b: Base, n: number): void {
            if (isSub(b)) { do { sink(b.extra); n--; } while (n > 0); }
        }
        function forOf(b: Base): void {
            if (isSub(b)) { for (const it of items) { sink(b.extra); sink(it); } }
        }
        function nested(b: Base, n: number): void {
            if (isSub(b)) {
                while (n > 0) { for (let i = 0; i < n; i++) { sink(b.extra); } n--; }
            }
        }
        function pathReceiver(h: Holder, n: number): void {
            if (isSub(h.inner)) { while (n > 0) { sink(h.inner.extra); n--; } }
        }
    """.trimIndent()

    @Test
    fun `every other FlowLoopLabel flavour keeps its retry`() {
        // do-while / for-of / a nested loop / a property-PATH receiver. Each one
        // washes at a FlowLoopLabel, so each one must keep the retry — and each is
        // silent, both with the gate live and with the pre-gate behaviour restored.
        val gated = codes(loopFlavours)
        assert(gated.none { it.startsWith("2339@") })
        val savedOne = CpaSections.verifyLoopRetry
        val preGate: List<String>
        try {
            CpaSections.verifyLoopRetry = true
            preGate = codes(loopFlavours)
        } finally {
            CpaSections.verifyLoopRetry = savedOne
        }
        assert(gated == preGate)
    }
}
