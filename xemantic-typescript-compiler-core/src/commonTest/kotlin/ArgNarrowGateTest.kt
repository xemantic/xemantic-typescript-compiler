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
 * (CALL.5)(b) round 796: pins the ALREADY-RELATES pre-gate on the two
 * unconditional flow-narrowing arms of `checkArgumentsAgainstSignatureCore`.
 *
 * **What it removes.** Measured at HEAD on the compiler profile, the argument
 * check spends **503 ms in `getNarrowedTypeForReference` over 9,858 walks —
 * 33% of the whole function** — and 86% of those walks return their input
 * unchanged. The gate walks only when the UNNARROWED argument type does NOT
 * already satisfy the parameter, and that refuses **8,905 of the 9,823 walks
 * the two arms launch (91%)**: B469 1,916 of 2,455 (78%), M3.4 6,989 of 7,368
 * (94%).
 *
 * **Why it is acceptance-preserving.** Both arms exist to SUPPRESS a TS2345
 * that the wide type would have caused — the M3.4 arm substitutes only a
 * refinement that relates (`refined`), and B469 substitutes a sub-union. When
 * the wide type already relates there is no TS2345 to suppress, so the
 * emission at the bottom of the loop is silent either way. Round 764 gave this
 * exact shape to the ENUM arm and declined to generalise; round 796 re-tests
 * that debt (the round-783 rule) and the generalisation holds.
 *
 * **What it can still change, and how that was measured rather than argued.**
 * The narrowed type is read by ~11 blocks below the arm — the weak-type rule's
 * shared-property test, the `!isSimpleCheckableType` shape classification, a
 * message's display — so a refusal that WOULD have substituted is a real
 * behavioural difference even when the relation verdict cannot move.
 * `--argNarrowCensus` (alias `--verifyArgNarrowGate`) keeps the OLD behaviour
 * and counts them: **compiler 787, services 1,134, harness 1,138**, and the
 * 8-profile grid is nonetheless identical set-for-set in BOTH directions.
 * The census's positive control is FREE (round 790): the same counter over the
 * complement the gate never refuses reports 578 / 902 / 927 substitutions, so
 * no deliberately bogus flag is needed to show the instrument is alive.
 *
 * The fixtures below therefore pin BOTH populations, and deliberately include
 * a call in the risky class — refused AND would-have-substituted — because a
 * pin over an empty population is vacuously green (round 794).
 */
class ArgNarrowGateTest {

    /**
     * `Ident <: Node2`, so a `Node2`-typed reference narrowed by `isIdent`
     * relates to a `Node2` parameter BEFORE the narrow (the gate refuses) and
     * does not relate to an `Ident` parameter (the gate keeps the walk).
     * `Cat | Dog` does the same for the B469 union arm.
     */
    private val prelude = """
        interface Node2 { kind: string }
        interface Ident extends Node2 { text: string }
        declare function isIdent(n: Node2): n is Ident
        declare function takeIdent(i: Ident): void
        declare function takeNode(n: Node2): void

        interface Cat { name: string; meow(): void }
        interface Dog { name: string; bark(): void }
        declare function isCat(x: Cat | Dog): x is Cat
        declare function takeCat(c: Cat): void
        declare function takeEither(x: Cat | Dog): void
    """.trimIndent()

    /**
     * A: the M3.4 arm's KEPT population — `Node2` does not relate to `Ident`,
     * so the walk must still run and its refinement must still suppress.
     * B: the B469 arm's KEPT population, same shape over a union.
     * C: the RISKY population — refused (the raw type relates) AND the walk
     * would have substituted `Ident` for `Node2`. Silent either way; its job is
     * to make the census non-vacuous.
     * D: the B469 risky twin.
     *
     * **Every guard is an EARLY RETURN, not an `if` block, and that is
     * load-bearing.** Since round 785 a type-guard CALL in an `if` condition
     * writes its narrow into `currentLocalTypes` for the THEN branch, so inside
     * `if (isIdent(n)) { … }` the argument's raw type is ALREADY `Ident` — the
     * flow read never happens, the gate refuses trivially, and a fixture built
     * that way pins nothing (it was written that way first and reported
     * `refused == reached` with zero substitutions on both arms).
     *
     * All four must be SILENT; [controls] is the same code with the guards
     * removed, and must not be.
     */
    private val source = prelude + """

        function keptM34(n: Node2): void {
            if (!isIdent(n)) { return }
            takeIdent(n);
        }
        function keptUnion(x: Cat | Dog): void {
            if (!isCat(x)) { return }
            takeCat(x);
        }
        function riskyM34(n: Node2): void {
            if (!isIdent(n)) { return }
            takeNode(n);
        }
        function riskyUnion(x: Cat | Dog): void {
            if (!isCat(x)) { return }
            takeEither(x);
        }
    """.trimIndent()

    /** [source]'s two kept-population calls with their guards removed. */
    private val controls = prelude + """

        function unguardedM34(n: Node2): void {
            takeIdent(n);
        }
        function unguardedUnion(d: Dog): void {
            takeCat(d);
        }
    """.trimIndent()

    private fun codes(src: String): List<String> =
        diagnose(src).map { "${it.code}@${it.start}" }

    /**
     * Run [src] under [ArgNarrowGate.CENSUS] — the OLD behaviour plus the
     * verdict — and return
     * (reachedUnion, refusedUnion, refusedChangedUnion, keptChangedUnion,
     *  reachedM34, refusedM34, refusedChangedM34, keptChangedM34).
     *
     * SAVE-AND-RESTORE, never "assign the default back": the mode is
     * fork-global and the round-619 `Inv0PassTimingTest` wipe is what happens
     * to whoever forgets.
     */
    private fun census(src: String = source): List<Long> {
        val saved = ArgNarrowGate.mode
        ArgNarrowGate.reset()
        ArgNarrowGate.mode = ArgNarrowGate.CENSUS
        try {
            diagnose(src)
            return listOf(
                ArgNarrowGate.reached[ArgNarrowGate.UNION],
                ArgNarrowGate.refused[ArgNarrowGate.UNION],
                ArgNarrowGate.refusedChanged[ArgNarrowGate.UNION],
                ArgNarrowGate.keptChanged[ArgNarrowGate.UNION],
                ArgNarrowGate.reached[ArgNarrowGate.M34],
                ArgNarrowGate.refused[ArgNarrowGate.M34],
                ArgNarrowGate.refusedChanged[ArgNarrowGate.M34],
                ArgNarrowGate.keptChanged[ArgNarrowGate.M34],
            )
        } finally {
            ArgNarrowGate.mode = saved
            ArgNarrowGate.reset()
        }
    }

    @Test
    fun `the gate is ON in production`() {
        // The whole corpus suite runs at this setting; a silent flip back to
        // OFF would make every pin below pass for the wrong reason.
        assert(ArgNarrowGate.mode == ArgNarrowGate.ON)
    }

    @Test
    fun `a narrow the parameter actually needs still runs and still suppresses`() {
        // The KEPT population. `Node2` is not assignable to `Ident` and
        // `Cat | Dog` is not assignable to `Cat`, so the gate must NOT refuse
        // these two walks — if it did, both calls would report TS2345.
        val d = diagnose(source)
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - the same two calls report once their guard is removed`() {
        // Without this the pin above is satisfied by a compiler that never
        // emits TS2345 at a reference argument at all.
        val d = diagnose(controls)
        assert(d.count { it.code == 2345 } == 2)
        assert(d.any { it.message.contains("Argument of type 'Node2' is not assignable to parameter of type 'Ident'") })
        assert(d.any { it.message.contains("Argument of type 'Dog' is not assignable to parameter of type 'Cat'") })
    }

    @Test
    fun `the gate refuses the calls whose parameter already accepts the wide type`() {
        // The population the change removes work from. Both arms must be
        // reached AND must refuse, or every equivalence pin here is vacuous.
        val c = census()
        val reachedUnion = c[0]; val refusedUnion = c[1]
        val reachedM34 = c[4]; val refusedM34 = c[5]
        assert(reachedUnion > 0L)
        assert(refusedUnion > 0L)
        assert(reachedM34 > 0L)
        assert(refusedM34 > 0L)
        // …and it must not refuse everything: the kept complement is what the
        // suppression pin above depends on.
        assert(refusedUnion < reachedUnion)
        assert(refusedM34 < reachedM34)
    }

    @Test
    fun `the fixture reaches the RISKY class - a refusal that would have substituted`() {
        // Round 794's law: verify the fixture reaches the population it pins.
        // `riskyM34` / `riskyUnion` are exactly the compiler profile's 787.
        val c = census()
        assert(c[2] > 0L)
        assert(c[6] > 0L)
    }

    @Test
    fun `the free complement control is alive - the kept population does substitute`() {
        // Round 790: the complement the gate never refuses MUST diverge, which
        // is what makes the zero-diagnostic-difference result below meaningful
        // rather than a dead instrument.
        val c = census()
        assert(c[3] + c[7] > 0L)
    }

    @Test
    fun `the gated binary emits exactly what the ungated one emitted`() {
        // CENSUS restores the pre-796 behaviour in the SAME binary — both arms
        // walk and both substitutions are honoured — so this is a direct
        // old-vs-new comparison rather than an argument, over a fixture that
        // provably contains the risky class.
        val gated = codes(source)
        val saved = ArgNarrowGate.mode
        val ungated: List<String>
        try {
            ArgNarrowGate.mode = ArgNarrowGate.CENSUS
            ungated = codes(source)
        } finally {
            ArgNarrowGate.mode = saved
        }
        assert(gated == ungated)
        assert(gated.isEmpty())
        val gatedControls = codes(controls)
        val ungatedControls: List<String>
        try {
            ArgNarrowGate.mode = ArgNarrowGate.CENSUS
            ungatedControls = codes(controls)
        } finally {
            ArgNarrowGate.mode = saved
        }
        assert(gatedControls == ungatedControls)
        assert(gatedControls.isNotEmpty())
    }

    @Test
    fun `the never-parameter arm is untouched by the gate`() {
        // Round 441's site is the THIRD narrowing arm and carries the monsters
        // (35 walks, 49 ms on the compiler profile). The gate has two arms and
        // is written not to reach it, so a `never`-parameter call must answer
        // identically at both settings whatever that answer is.
        val src = prelude + """

            declare function assertNever(x: never): void
            function exhaust(x: Cat | Dog): void {
                if (isCat(x)) { x.meow(); return; }
                assertNever(x);
            }
        """.trimIndent()
        val gated = codes(src)
        val saved = ArgNarrowGate.mode
        val ungated: List<String>
        try {
            ArgNarrowGate.mode = ArgNarrowGate.CENSUS
            ungated = codes(src)
        } finally {
            ArgNarrowGate.mode = saved
        }
        assert(gated == ungated)
    }
}
