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
 * (ENGINE.2d)(b) round 791: pins the DEFERRAL of `checkMemberAccessMissing`'s
 * three flow-graph suppression blocks — 1,132 ms at HEAD, 42% of the largest
 * single leaf in the compile — from the TOP of the function to a lazy call made
 * only when the body actually appended a diagnostic.
 *
 * **The invariant that makes the deferral safe, and why it is mechanical rather
 * than a case analysis.** The three blocks did one thing: `return`, i.e. suppress
 * everything the rest of the function would emit. The rest of the function — 2,035
 * lines, 42 emissions — and every `tryEmit*`/`emit*` helper it calls mutate
 * exactly ONE piece of checker state: they APPEND to `diagnostics`. Nothing
 * retracts, nothing writes a side set, nothing installs ambient state, and
 * nothing reads `diagnostics` back. So "run the body, then remove everything it
 * appended at or after the blocks' old position" is indistinguishable, for
 * diagnostics, from "never run the body" — with no emission site enumerated, and
 * with a NEW emission site covered automatically. The floor is the blocks' old
 * position rather than the function entry, which is what keeps the one emission
 * ABOVE them (the intersection-reduction `never` TS2339) out of reach.
 *
 * **What that argument does NOT settle is cache-mutation ORDER (round 754)** —
 * the body now runs BETWEEN the two positions, and for a suppressed call it now
 * runs at all. That is measured, not argued: `--verifyDeferSuppression`
 * evaluates the predicate at BOTH positions and compares, honouring the eager
 * verdict so the run reproduces the pre-change binary exactly;
 * `--verifyDeferSuppressionBogus` is the positive control that proves the
 * comparator is alive. On the three real profiles the comparison ran 268,863
 * times with 0 type-diffs and 0 verdict-diffs.
 */
class DeferredSuppressionTest {

    /**
     * Four shapes whose TS2339 is suppressed by one of the three deferred blocks,
     * plus one that genuinely emits — so a binary that retracted everything and a
     * binary that retracted nothing both fail.
     */
    private val source = """
        interface Base { kind: string }
        interface Sub extends Base { extra: number }
        declare function isSub(x: Base): x is Sub
        declare function sink(v: unknown): void

        // Block 1, round 418: a single-type receiver narrowed DOWN to a strict
        // subtype that HAS the member.
        function plain(b: Base): void {
            if (isSub(b)) { sink(b.extra); }
        }
        // Block 1, round 425: the same read from INSIDE a loop, so the plain walk
        // washes at the FlowLoopLabel and only the loop-entry retry sees it.
        function inLoop(b: Base, n: number): void {
            if (isSub(b)) {
                while (n > 0) { sink(b.extra); n--; }
            }
        }
        // Two more loop flavours and an early-return guard — the retraction has to
        // survive every shape whose walk reaches the blocks differently.
        function inDoWhile(b: Base): void {
            if (isSub(b)) { do { sink(b.extra); } while (false); }
        }
        function inForOf(b: Base, xs: number[]): void {
            if (isSub(b)) { for (const x of xs) { sink(b.extra); } }
        }
        function earlyReturn(b: Base): void {
            if (!isSub(b)) return;
            sink(b.extra);
        }
        // The genuine emission: nothing anywhere resolves this member, so the
        // deferred predicate must decline and the diagnostic must survive.
        function real(b: Base): void {
            sink(b.nothere);
        }
        // Two accesses that REACH the function and leave it silently (an index
        // signature answers them at the bottom). They are what make the
        // population pins below non-degenerate: the whole point of the deferral
        // is that such a call no longer pays for a flow walk.
        interface Bag { [k: string]: number }
        function indexed(bag: Bag): void {
            sink(bag.anything);
            sink(bag.another);
        }
    """.trimIndent()

    /** [source] with every guard removed — the negative control. */
    private val unguarded = """
        interface Base { kind: string }
        interface Sub extends Base { extra: number }
        declare function sink(v: unknown): void

        function plain(b: Base): void {
            sink(b.extra);
        }
        function inLoop(b: Base, n: number): void {
            while (n > 0) { sink(b.extra); n--; }
        }
        function inDoWhile(b: Base): void {
            do { sink(b.extra); } while (false);
        }
        function inForOf(b: Base, xs: number[]): void {
            for (const x of xs) { sink(b.extra); }
        }
        function earlyReturn(b: Base): void {
            sink(b.extra);
        }
        function real(b: Base): void {
            sink(b.nothere);
        }
    """.trimIndent()

    /**
     * Block 3: a `this` receiver re-typed by an `asserts` call and then narrowed,
     * reading a member that exists only on the narrowed constituent.
     */
    private val thisReceiver = """
        interface Mapper { kind: "simple"; source: string }
        interface Composite { kind: "composite"; left: string }
        declare function assertMapper(v: unknown): asserts v is Mapper | Composite
        declare function sink(v: unknown): void

        class Holder {
            read(): void {
                assertMapper(this);
                if (this.kind === "simple") { sink(this.source); }
            }
        }
    """.trimIndent()

    private fun codes(src: String): List<String> =
        diagnose(src).map { "${it.code}@${it.start}" }

    /**
     * Run [src] with the verifier configured, returning
     * (evaluated, verified, typeDiff, verdictDiff, retracted). SAVE-AND-RESTORE,
     * never "assign the default back" — [CpaSections] is fork-global state (the
     * round-619 `Inv0PassTimingTest` lesson).
     */
    private fun verify(bogus: Boolean, src: String = source): List<Long> {
        val savedOne = CpaSections.verifyDeferSuppression
        val savedBogus = CpaSections.verifyDeferSuppressionBogus
        val savedMode = CpaSections.mode
        CpaSections.reset()
        CpaSections.verifyDeferSuppression = true
        CpaSections.verifyDeferSuppressionBogus = bogus
        try {
            diagnose(src)
            return listOf(
                CpaSections.deferEvaluated,
                CpaSections.deferVerified,
                CpaSections.deferVerifyTypeDiff,
                CpaSections.deferVerifyVerdictDiff,
                CpaSections.deferSuppressed,
            )
        } finally {
            CpaSections.verifyDeferSuppression = savedOne
            CpaSections.verifyDeferSuppressionBogus = savedBogus
            CpaSections.mode = savedMode
            CpaSections.reset()
        }
    }

    /** The production counters — the census mode reads no timestamp. */
    private fun census(src: String = source): List<Long> {
        val savedMode = CpaSections.mode
        CpaSections.reset()
        CpaSections.mode = CpaSections.CENSUS
        try {
            diagnose(src)
            return listOf(
                CpaSections.deferEvaluated,
                CpaSections.deferEmitted,
                CpaSections.deferSuppressed,
                CpaSections.invocationsR,
            )
        } finally {
            CpaSections.mode = savedMode
            CpaSections.reset()
        }
    }

    @Test
    fun `the three deferred blocks still suppress`() {
        // If the deferral ever lost a suppression, each of these becomes a TS2339
        // — a NEW false positive, which is the whole risk the item names.
        val d = diagnose(source)
        assert(d.none { it.message.contains("'extra'") })
        assert(d.count { it.code == 2339 } == 1)
    }

    @Test
    fun `negative control - the same reads emit once their guards are removed`() {
        // Without this the pin above is satisfied by a compiler that never reports
        // TS2339 on these shapes at all — round 790's own mis-aimed pin.
        val d = diagnose(unguarded)
        assert(d.count { it.code == 2339 && it.message.contains("'extra'") } == 5)
    }

    @Test
    fun `a genuine missing property still emits - the retraction is a filter`() {
        // The retraction removes what the predicate suppresses and nothing else;
        // a binary that emptied the range would pass every pin above and fail this.
        val d = diagnose(source)
        assert(d.any { it.code == 2339 && it.message.contains("'nothere'") })
    }

    @Test
    fun `the this-receiver block still suppresses`() {
        val d = diagnose(thisReceiver)
        assert(d.none { it.message.contains("'source'") })
    }

    @Test
    fun `the deferral emits exactly what the pre-deferral binary emitted`() {
        // Under the verifier the EAGER verdict — computed where the blocks used to
        // run, before the body — is the one honoured, so this compares the new
        // implementation against the old one inside a single binary.
        val deferred = codes(source)
        val savedOne = CpaSections.verifyDeferSuppression
        val eager: List<String>
        try {
            CpaSections.verifyDeferSuppression = true
            eager = codes(source)
        } finally {
            CpaSections.verifyDeferSuppression = savedOne
        }
        assert(deferred == eager)
        assert(deferred.isNotEmpty())
    }

    @Test
    fun `the eager and deferred evaluations agree at Type-instance granularity`() {
        val (evaluated, verified, typeDiff, verdictDiff) = verify(bogus = false)
        assert(evaluated > 0)
        assert(verified == evaluated)
        // A verdict comparison alone would miss a changed type that happens to
        // resolve the same property, so the type instances are compared too.
        assert(typeDiff == 0L)
        assert(verdictDiff == 0L)
    }

    @Test
    fun `the control - a bogus deferred evaluation DOES diverge`() {
        // Without this the zero above could be the reading of a dead instrument.
        // The verdict is the load-bearing half; on a fixture this small the
        // poisoned name reaches the SAME narrowed type, so `typeDiff` is not
        // asserted here (on the compiler profile it reads 19,864 against 0).
        val (_, verified, _, verdictDiff) = verify(bogus = true)
        assert(verified > 0)
        assert(verdictDiff > 0L)
    }

    @Test
    fun `in production the predicate runs only for a call that emitted`() {
        // The whole prize: the apparatus used to run at EVERY property access and
        // now runs only where something was appended.
        val (evaluated, emitted, retracted, invocations) = census()
        assert(evaluated == emitted)
        assert(retracted > 0L)
        assert(invocations > evaluated)
    }

    @Test
    fun `the verifier evaluates the predicate far more often than production does`() {
        // Cross-check: the two modes must disagree on the POPULATION while
        // agreeing on the output — if they agreed on both, the verifier would be
        // comparing the deferred path against itself.
        val production = census()[0]
        val verified = verify(bogus = false)[0]
        assert(verified > production)
    }

    @Test
    fun `a diagnostic from another pass at the same access is never retracted`() {
        // The retraction floor is the blocks' old position INSIDE
        // checkMemberAccessMissing, so an unrelated diagnostic emitted by any
        // other pass — before, during or after — is out of reach.
        val d = diagnose(
            """
            interface Base { kind: string }
            interface Sub extends Base { extra: number }
            declare function isSub(x: Base): x is Sub
            declare function sink(v: unknown): void

            function both(b: Base): void {
                if (isSub(b)) { sink(b.extra); }
                const n: number = "not a number";
                sink(n);
            }
            """.trimIndent()
        )
        assert(d.none { it.message.contains("'extra'") })
        assert(d.any { it.code == 2322 })
    }
}
