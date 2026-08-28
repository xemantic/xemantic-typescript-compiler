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
 * (ENGINE.2f) round 794: pins the SUBSTITUTION that replaces the round-424 UNION
 * loop-entry retry inside `checkMemberAccessMissing`'s `R_OT_UNION` row — 32 ms
 * over 916 of its 1,859 calls on the compiler profile.
 *
 * **Why a substitution and not a skip.** The retry's first test is the IDENTITY
 * `loopNarrowed !== rawForNarrowing`. A loop-free repeat whose flow path crosses
 * a two-antecedent `FlowBranchLabel` unions `[declared, declared]`, and
 * `getUnionType` does not intern, so it mints a FRESH equal union — the block
 * therefore genuinely runs, and on the compiler profile it suppresses 61 times
 * from exactly that state. Skipping the retry would lose those 61; substituting
 * the plain walk's own result keeps both the identity relationship (fresh vs the
 * declared instance) and the member set, which is everything the consumer reads.
 *
 * **Why the substitution is equivalent.** `narrowTypeFromFlow` and
 * `narrowTypeFromFlowFollowLoopEntry` are line-by-line mirrors differing in the
 * `FlowLoopLabel` arm alone (see [LoopEntryRetryGateTest]), so when the round-790
 * bracket says the plain walk really ran and arrived at no loop label and
 * truncated nowhere, the mirror makes the identical traversal and answers what
 * the plain walk answered. Measured, not asserted: `--verifyUnionRetry` re-walks
 * and HONOURS the re-walk (so the run reproduces the pre-change binary) while
 * comparing the two candidates at `Type`-INSTANCE, union MEMBER-ID-SET and
 * suppression-VERDICT granularity — compiler 916 / services 1,191 /
 * harness 1,313, **0 diffs of any kind across all 3,420**.
 *
 * The zero is not a dead instrument: `--verifyUnionRetryAll` runs the same
 * comparison over the COMPLEMENT (the loop-CROSSING calls the substitution never
 * serves) and reports 149 instance-diffs, 101 member-diffs and 118 VERDICT-diffs
 * on the compiler profile. That is round 790's law — the complement is a
 * positive control that costs nothing, so no deliberately bogus baseline is
 * needed.
 */
class UnionRetrySubstitutionTest {

    /**
     * Five loop shapes whose reads are legal ONLY through the loop-entry retry —
     * the plain walk washes back to the declared union at the `FlowLoopLabel` —
     * plus two negative controls, one loop-crossing and one loop-free, so a
     * compiler that simply never reports TS2339 here cannot satisfy the pins.
     */
    private val source = """
        interface Cat { name: string; meow(): void }
        interface Dog { name: string; bark(): void }
        declare function isCat(x: Cat | Dog): x is Cat
        declare function sink(v: unknown): void

        // A: a guard, then a `while` whose body reads a Cat-only member.
        function inWhile(x: Cat | Dog, n: number): void {
            if (isCat(x)) {
                while (n > 0) { sink(x.meow); n--; }
            }
        }
        // B: the same through an early-return guard and a `for` head.
        function inFor(x: Cat | Dog, n: number): void {
            if (!isCat(x)) return;
            for (let i = 0; i < n; i++) { sink(x.meow); }
        }
        // C: `do`/`while` — the back edge is reached from the other side.
        function inDoWhile(x: Cat | Dog, n: number): void {
            if (!isCat(x)) return;
            do { sink(x.meow); n--; } while (n > 0);
        }
        // D: two nested loops — the read is two `FlowLoopLabel`s deep.
        function inNested(x: Cat | Dog, n: number): void {
            if (!isCat(x)) return;
            for (let i = 0; i < n; i++) { for (let j = 0; j < n; j++) { sink(x.meow); } }
        }
        // E: `for-of`, whose loop label is built by a different binder arm.
        function inForOf(x: Cat | Dog, xs: number[]): void {
            if (!isCat(x)) return;
            for (const s of xs) { sink(x.meow); sink(s); }
        }
        // F: LOOP-CROSSING negative control — no guard, so the read is an error
        // that neither walk may suppress.
        function inLoopUnguarded(x: Cat | Dog, n: number): void {
            while (n > 0) { sink(x.notThere); n--; }
        }
        // G: LOOP-FREE negative control — the substitution must not suppress a
        // true positive on the population it serves. A bare expression STATEMENT,
        // deliberately: in an argument or a `return` an earlier pass has already
        // walked this reference, so the round-664 inter-walk memo serves the walk,
        // `narrowWalkLaunches` does not move and the bracket answers "unknown" —
        // which is the conservative arm and would leave this fixture's substituted
        // population EMPTY.
        function noLoopUnguarded(x: Cat | Dog): void {
            x.alsoNotThere;
        }
        // H: the second loop-free call — reached, served by the substitution, and
        // silent, so the population is not made only of erroring accesses.
        function noLoopPresent(x: Cat | Dog): void {
            x.name;
        }
    """.trimIndent()

    /**
     * (CHK.69) The loop-crossing CONTROL population, which [source] no longer
     * supplies. Since (CHK.69) a `FlowLoopLabel` whose body cannot affect the
     * reference is answered by FOLLOWING ITS ENTRY, so on A-E the plain walk and
     * the mirror now agree and the control's `instanceDiff > 0` read ZERO — a dead
     * instrument wearing the same face as a passing gate. A loop that ASSIGNS the
     * guarded reference still washes to the declared union in the plain walk while
     * the mirror follows `antecedents[0]` regardless, which is the divergence.
     */
    private val loopCrossing = """
        interface Cat { name: string; meow(): void }
        interface Dog { name: string; bark(): void }
        declare function isCat(x: Cat | Dog): x is Cat
        declare function mk(): Cat | Dog
        declare function sink(v: unknown): void

        function assignsWhile(x: Cat | Dog, n: number): void {
            if (isCat(x)) { while (n > 0) { sink(x.meow); x = mk(); n--; } }
        }
        function assignsFor(x: Cat | Dog, n: number): void {
            if (!isCat(x)) return;
            for (let i = 0; i < n; i++) { sink(x.meow); x = mk(); }
        }
        function assignsDoWhile(x: Cat | Dog, n: number): void {
            if (!isCat(x)) return;
            do { sink(x.meow); x = mk(); n--; } while (n > 0);
        }
        function assignsForOf(x: Cat | Dog, xs: number[]): void {
            if (!isCat(x)) return;
            for (const s of xs) { sink(x.meow); x = mk(); sink(s); }
        }
        function noLoopUnguarded(x: Cat | Dog): void {
            x.alsoNotThere;
        }
    """.trimIndent()

    /** [source] with A-E's guards removed — the positive control for A-E. */
    private val unguarded = """
        interface Cat { name: string; meow(): void }
        interface Dog { name: string; bark(): void }
        declare function sink(v: unknown): void

        function inWhile(x: Cat | Dog, n: number): void {
            while (n > 0) { sink(x.meow); n--; }
        }
        function inFor(x: Cat | Dog, n: number): void {
            for (let i = 0; i < n; i++) { sink(x.meow); }
        }
        function inDoWhile(x: Cat | Dog, n: number): void {
            do { sink(x.meow); n--; } while (n > 0);
        }
        function inNested(x: Cat | Dog, n: number): void {
            for (let i = 0; i < n; i++) { for (let j = 0; j < n; j++) { sink(x.meow); } }
        }
        function inForOf(x: Cat | Dog, xs: number[]): void {
            for (const s of xs) { sink(x.meow); sink(s); }
        }
    """.trimIndent()

    private fun codes(src: String): List<String> =
        diagnose(src).map { "${it.code}@${it.start}" }

    /**
     * Run [src] with the verifier configured, returning
     * (reached, loopFree, verified, instanceDiff, memberDiff, verdictDiff).
     * SAVE-AND-RESTORE, never "assign the default back" — these are fork-global
     * (the round-619 `Inv0PassTimingTest` lesson).
     */
    private fun verify(all: Boolean, src: String = source): List<Long> {
        val savedOne = CpaSections.verifyUnionRetry
        val savedAll = CpaSections.verifyUnionRetryAll
        val savedMode = CpaSections.mode
        CpaSections.reset()
        CpaSections.verifyUnionRetry = true
        CpaSections.verifyUnionRetryAll = all
        try {
            diagnose(src)
            return listOf(
                CpaSections.unionRetryCalls,
                CpaSections.unionRetryLoopFree,
                CpaSections.unionRetryVerified,
                CpaSections.unionRetryVerifyInstanceDiff,
                CpaSections.unionRetryVerifyMemberDiff,
                CpaSections.unionRetryVerifyVerdictDiff,
            )
        } finally {
            CpaSections.verifyUnionRetry = savedOne
            CpaSections.verifyUnionRetryAll = savedAll
            CpaSections.mode = savedMode
            CpaSections.reset()
        }
    }

    @Test
    fun `the round-424 loop-entry shapes still suppress with the substitution live`() {
        // A-E read a member that exists only on the NARROWED constituent, from
        // inside a loop. If the substitution ever served a call whose plain walk
        // HAD crossed a loop label, these become TS2339 — which is the whole risk.
        val d = diagnose(source)
        assert(d.none { it.message.contains("'meow'") })
        assert(d.count { it.code == 2339 } == 2)
        assert(d.any { it.message.contains("'notThere'") })
        assert(d.any { it.message.contains("'alsoNotThere'") })
    }

    @Test
    fun `negative control - the same five loop reads emit once their guard is removed`() {
        // Without this the pin above would be satisfied by a compiler that never
        // reports TS2339 on a union receiver inside a loop at all.
        val d = diagnose(unguarded)
        assert(d.count { it.code == 2339 && it.message.contains("'meow'") } == 5)
    }

    @Test
    fun `the substitution emits exactly what the re-walking binary emitted`() {
        // `--verifyUnionRetry` restores the pre-change behaviour in the SAME
        // binary — the retry walks and its verdict is honoured — so this is a
        // direct old-vs-new comparison rather than an argument.
        val substituted = codes(source)
        val savedOne = CpaSections.verifyUnionRetry
        val reWalked: List<String>
        try {
            CpaSections.verifyUnionRetry = true
            reWalked = codes(source)
        } finally {
            CpaSections.verifyUnionRetry = savedOne
        }
        assert(substituted == reWalked)
        assert(substituted.isNotEmpty())
    }

    @Test
    fun `the verifier finds no divergence over the substituted population`() {
        val r = verify(all = false)
        val reached = r[0]; val loopFree = r[1]; val verified = r[2]
        val instanceDiff = r[3]; val memberDiff = r[4]; val verdictDiff = r[5]
        assert(reached > 0)
        assert(loopFree > 0)
        // Only the loop-free calls are compared when the control is off.
        assert(verified == loopFree)
        assert(instanceDiff == 0L)
        assert(memberDiff == 0L)
        assert(verdictDiff == 0L)
    }

    @Test
    fun `the control - the verifier DOES diverge over the loop-crossing complement`() {
        val r = verify(all = true, src = loopCrossing)
        val reached = r[0]; val loopFree = r[1]; val verified = r[2]
        val instanceDiff = r[3]; val memberDiff = r[4]; val verdictDiff = r[5]
        // Strictly more calls are compared than in the substituted-only run, and
        // the extra ones are exactly the calls the substitution never serves.
        assert(verified == reached)
        assert(reached > loopFree)
        // The four loop-ASSIGNING shapes: the loop-entry walker reaches a different
        // type AND suppresses where the plain walk did not. This is what proves the
        // instrument can fire. See [loopCrossing] for why [source] no longer can.
        assert(instanceDiff > 0L)
        assert(memberDiff > 0L)
        assert(verdictDiff > 0L)
    }

    @Test
    fun `every divergence lives outside the substituted population`() {
        val substitutedRun = verify(all = false, src = loopCrossing)
        val allRun = verify(all = true, src = loopCrossing)
        // The arithmetic form of the equivalence claim: the divergences fit
        // entirely inside the calls the control adds.
        assert(allRun[2] - substitutedRun[2] >= allRun[5])
        // The four loop-ASSIGNING shapes, and only those.
        assert(allRun[5] == 4L)
    }

    @Test
    fun `the counters stay inert on the production path`() {
        val savedOne = CpaSections.verifyUnionRetry
        val savedMode = CpaSections.mode
        CpaSections.reset()
        CpaSections.verifyUnionRetry = false
        CpaSections.mode = CpaSections.OFF
        try {
            diagnose(source)
            // The substitution itself is live, but nothing writes the shared probe
            // object — a worker thread must never race on it.
            assert(CpaSections.unionRetryCalls == 0L)
            assert(CpaSections.unionRetryVerified == 0L)
            assert(CpaSections.unionRetrySuppressedLoopFree == 0L)
        } finally {
            CpaSections.verifyUnionRetry = savedOne
            CpaSections.mode = savedMode
            CpaSections.reset()
        }
    }
}
