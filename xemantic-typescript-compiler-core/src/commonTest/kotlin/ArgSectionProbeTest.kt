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
 * (CALL.2)(a): pins the intra-function attribution harness for
 * `checkArgumentsAgainstSignature`.
 *
 * Four invariants. The FIRST is that turning the probe on cannot change what
 * the compiler says — the boundaries live inside a 1,534-line production
 * function, and three of them REWROTE an expression to name its value
 * (`isSimpleCheckableType`, the TS2345 relation gate, the M3.4 refinement
 * gate) so a sub-measure could bracket it. A short-circuit lost in one of
 * those rewrites shows up here as a diagnostics difference between OFF and ON.
 *
 * The SECOND is that the partition survives the early exits. Most sections sit
 * inside the per-ARGUMENT loop, so `calls[s]` counts loop ITERATIONS, and the
 * drop between two consecutive loop sections is exactly the number of
 * iterations that `continue`d inside the earlier one. That only holds while
 * the reach counts fall monotonically.
 *
 * The THIRD is that `mode = COARSE` — the differential calibration
 * counterpart — still partitions the same wall time with only the anchors, so
 * an ON-versus-COARSE comparison prices a boundary rather than a code path.
 *
 * The FOURTH is that the three narrowing sites route through `closeNarrow`, so
 * the per-site rows, the combined row and the cost buckets all agree. That
 * agreement is what makes "86% of the walks return the input type" a
 * measurement rather than an impression.
 */
class ArgSectionProbeTest {

    private val source = """
        interface Node { kind: number }
        interface Ident extends Node { text: string }
        function isIdent(n: Node): n is Ident { return n.kind === 1 }
        function takesIdent(i: Ident): number { return i.text.length }
        function takesNumber(n: number): number { return n }
        function widen(v: string | number): string { return String(v) }
        function main(n: Node, u: string | number, s: string): number {
            let total = 0
            if (isIdent(n)) { total += takesIdent(n) }
            total += widen(u).length
            total += takesNumber(s.length)
            total += [1, 2].map((x) => x + 1).length
            return total
        }
        main({ kind: 1 }, "a", "b")
        // (CALL.5)(b) round 796: since the already-relates pre-gate landed, a
        // narrowing walk is launched at an argument only when the UNNARROWED
        // type does not already satisfy the parameter — and `if (isIdent(n)) {
        // takesIdent(n) }` above is not that shape twice over: round 785 writes
        // the guard's narrow into `currentLocalTypes` for the THEN branch, so
        // the argument arrives already narrowed. An EARLY-RETURN guard into a
        // parameter the wide type fails is the shape that still walks, and the
        // narrowing pins below need it or they measure an empty population.
        function keptWalk(n: Node): number {
            if (!isIdent(n)) { return 0 }
            return takesIdent(n)
        }
        keptWalk({ kind: 1 })
        // The instrumented function's OWN emission, so an ON/OFF comparison of
        // the diagnostics is not vacuous: a wrong argument type is TS2345,
        // emitted from the last section this probe partitions.
        function probe(): void {
            takesNumber("nope")
        }
        probe()
    """.trimIndent()

    private fun diagnosticsUnder(mode: Int): List<String> {
        // SAVE-AND-RESTORE, never "assign the default back" — the mode is
        // fork-global (the round-619 Inv0PassTimingTest lesson).
        val saved = ArgSections.mode
        ArgSections.reset()
        ArgSections.mode = mode
        try {
            return diagnose(source).map { "${it.code}@${it.start}:${it.length} ${it.message}" }
        } finally {
            ArgSections.mode = saved
        }
    }

    private fun runProbe(mode: Int = ArgSections.ON) {
        val saved = ArgSections.mode
        ArgSections.reset()
        ArgSections.mode = mode
        try {
            diagnose(source)
        } finally {
            ArgSections.mode = saved
        }
    }

    @Test
    fun `the section-name table is index-aligned and complete`() {
        assert(ArgSections.names.size == ArgSections.N)
        assert(ArgSections.FIRST_NESTED == ArgSections.N_GET_TYPE_OF_EXPR)
        assert(ArgSections.OVERHEAD == ArgSections.N - 1)
        assert(ArgSections.OVERHEAD_FIRST == ArgSections.N - 2)
        assert(ArgSections.ENTRY == ArgSections.N - 3)
        assert(ArgSections.POST == ArgSections.FIRST_NESTED - 1)
        // COARSE keeps exactly the three partition anchors plus the wrapper
        // transition, so its partition still covers the whole function.
        assert(ArgSections.coarseAnchor[ArgSections.PRO])
        assert(ArgSections.coarseAnchor[ArgSections.L_PARAM])
        assert(ArgSections.coarseAnchor[ArgSections.POST])
        assert(!ArgSections.coarseAnchor[ArgSections.L_ARGTYPE])
        assert(!ArgSections.coarseAnchor[ArgSections.L_RELATION])
    }

    @Test
    fun `the probe is behaviour-free when off`() {
        val off = diagnosticsUnder(ArgSections.OFF)
        val on = diagnosticsUnder(ArgSections.ON)
        val coarse = diagnosticsUnder(ArgSections.COARSE)
        assert(on == off)
        assert(coarse == off)
        // Not vacuous: the fixture emits the INSTRUMENTED function's own code,
        // so a lost short-circuit in a bracketed gate would diverge here.
        assert(off.any { it.startsWith("2345@") })
    }

    @Test
    fun `nothing is recorded while the probe is off`() {
        val saved = ArgSections.mode
        ArgSections.reset()
        ArgSections.mode = ArgSections.OFF
        try {
            diagnose(source)
        } finally {
            ArgSections.mode = saved
        }
        assert(ArgSections.calls.sum() == 0L)
        assert(ArgSections.invocations == 0L)
        assert(ArgSections.iterations == 0L)
        assert(ArgSections.narrowBucketCalls.sum() == 0L)
    }

    @Test
    fun `the disjoint sections partition every invocation`() {
        runProbe()
        val invocations = ArgSections.invocations
        assert(invocations > 5L)
        // Every invocation reaches the prologue, the wrapper transition and the
        // first empty span once; the steady-state calibration closes eight more.
        assert(ArgSections.calls[ArgSections.PRO] == invocations)
        assert(ArgSections.calls[ArgSections.ENTRY] == invocations)
        assert(ArgSections.calls[ArgSections.OVERHEAD_FIRST] == invocations)
        assert(ArgSections.calls[ArgSections.OVERHEAD] == invocations * 8)
        // The pre-loop sections are sequential and unconditional.
        assert(ArgSections.calls[ArgSections.INFER] <= invocations)
        assert(ArgSections.calls[ArgSections.PRO2] <= ArgSections.calls[ArgSections.INFER])
        // The loop sections are reached per ARGUMENT, not per invocation, and
        // fall monotonically — that monotonicity is what makes `leftIn` a count.
        assert(ArgSections.calls[ArgSections.L_PARAM] == ArgSections.iterations)
        for (s in ArgSections.L_PARAM until ArgSections.L_RELATION) {
            assert(ArgSections.calls[s] >= ArgSections.calls[s + 1])
        }
        assert(ArgSections.calls[ArgSections.L_RELATION] > 0L)
        // Every iteration is accounted for by exactly one exit section.
        var exits = 0L
        for (s in ArgSections.L_PARAM..ArgSections.L_RELATION) exits += ArgSections.leftIn(s)
        assert(exits == ArgSections.iterations)
        ArgSections.reset()
    }

    @Test
    fun `the nested sub-measures are rarer than the sections that contain them`() {
        runProbe()
        // The argument's type is computed once per iteration that got past the
        // spread/any gates, so both live inside L_ARGTYPE's reach.
        assert(ArgSections.calls[ArgSections.N_GET_TYPE_OF_EXPR] > 0L)
        assert(
            ArgSections.calls[ArgSections.N_GET_TYPE_OF_EXPR] ==
                ArgSections.calls[ArgSections.L_ARGTYPE]
        )
        assert(
            ArgSections.calls[ArgSections.N_LITERAL] ==
                ArgSections.calls[ArgSections.L_ARGTYPE]
        )
        // The relation gate runs once per iteration that reaches it.
        assert(
            ArgSections.calls[ArgSections.N_REL_CALL] ==
                ArgSections.calls[ArgSections.L_RELATION]
        )
        assert(
            ArgSections.calls[ArgSections.N_ISSIMPLE] ==
                ArgSections.calls[ArgSections.L_NOTSIMPLE]
        )
        // The loop span closes once per invocation that left the loop normally.
        assert(ArgSections.calls[ArgSections.N_LOOP] <= ArgSections.invocations)
        ArgSections.reset()
    }

    @Test
    fun `the narrowing sites agree with the combined row and the buckets`() {
        runProbe()
        val perSite = ArgSections.calls[ArgSections.N_NARROW_UNION] +
            ArgSections.calls[ArgSections.N_NARROW_NEVER] +
            ArgSections.calls[ArgSections.N_NARROW_M34]
        // (CALL.5)(b) round 796 RESTATED this pin rather than deleting it. It
        // used to be satisfied by the fixture's `widen(u)` and `if (isIdent(n))`
        // calls; the already-relates pre-gate refuses BOTH (the union parameter
        // accepts the un-narrowed union, and the `if`-body argument is already
        // narrowed by round 785), so `perSite` went to 0 and the pin failed by
        // construction. `keptWalk` restores a walk the gate must NOT refuse, so
        // `> 0` now asserts something stronger than it did: that the gate has a
        // live complement.
        assert(perSite > 0L)
        assert(ArgSections.calls[ArgSections.N_NARROW] == perSite)
        assert(ArgSections.narrowBucketCalls.sum() == perSite)
        // Identity walks are a SUBSET, and the fixture's `keptWalk(n)` guarded
        // argument gives both outcomes room.
        assert(ArgSections.calls[ArgSections.N_NARROW_IDENTITY] <= perSite)
        // A narrowing walk is nested inside the argType computation.
        assert(perSite <= ArgSections.calls[ArgSections.L_ARGTYPE])
        ArgSections.reset()
    }

    @Test
    fun `COARSE records only the anchors and still spans the function`() {
        runProbe(ArgSections.COARSE)
        val invocations = ArgSections.invocations
        assert(invocations > 5L)
        assert(ArgSections.calls[ArgSections.PRO] == invocations)
        assert(ArgSections.calls[ArgSections.L_PARAM] == ArgSections.iterations)
        assert(ArgSections.calls[ArgSections.POST] > 0L)
        // Every non-anchor boundary is skipped BEFORE its timestamp read —
        // that is the whole point, and it is what makes the ON-versus-COARSE
        // difference a per-boundary price.
        assert(ArgSections.calls[ArgSections.L_ARGTYPE] == 0L)
        assert(ArgSections.calls[ArgSections.L_RELATION] == 0L)
        assert(ArgSections.calls[ArgSections.INFER] == 0L)
        // Nested sub-measures never record under COARSE either.
        assert(ArgSections.calls[ArgSections.N_GET_TYPE_OF_EXPR] == 0L)
        assert(ArgSections.calls[ArgSections.N_NARROW] == 0L)
        assert(ArgSections.calls[ArgSections.N_ARGTYPE_RELATING] == 0L)
        assert(ArgSections.calls[ArgSections.N_ARGTYPE_NONRELATING] == 0L)
        assert(ArgSections.calls[ArgSections.N_GTOE_NONRELATING] == 0L)
        assert(ArgSections.calls[ArgSections.N_NARROW_NONRELATING] == 0L)
        assert(ArgSections.narrowBucketCalls.sum() == 0L)
        ArgSections.reset()
    }

    /**
     * (AUDIT.2): the exit-class split must PARTITION the argType row — every
     * argument whose type was computed lands in exactly one class, and the two
     * classes' nanos sum to the row they came from.
     *
     * This is the pin round 758 added for the `getCalleeType` outcome split,
     * for the same reason: without it a later edit could silently turn the
     * measurement into a sample, and a sample would read as "the non-relating
     * class is cheap" no matter what the truth was.
     */
    @Test
    fun `the argType exit-class split partitions every argument`() {
        runProbe()
        val rel = ArgSections.calls[ArgSections.N_ARGTYPE_RELATING]
        val non = ArgSections.calls[ArgSections.N_ARGTYPE_NONRELATING]
        // Not vacuous: the fixture exercises BOTH classes — `takesNumber(s.length)`
        // and `takesNumber("nope")` reach the relation, while the `.map((x) => x + 1)`
        // callback argument exits at the function-vs-function block.
        assert(rel > 0L)
        assert(non > 0L)
        assert(rel + non == ArgSections.calls[ArgSections.L_ARGTYPE])
        assert(
            ArgSections.nanos[ArgSections.N_ARGTYPE_RELATING] +
                ArgSections.nanos[ArgSections.N_ARGTYPE_NONRELATING] ==
                ArgSections.nanos[ArgSections.L_ARGTYPE]
        )
        // The relating class is exactly the population that opened L_RELATION.
        assert(rel == ArgSections.calls[ArgSections.L_RELATION])
        // The two MECHANISM rows partition their own sub-measures the same way,
        // which is what lets the difference between the classes be attributed
        // rather than named.
        assert(
            ArgSections.calls[ArgSections.N_GTOE_RELATING] +
                ArgSections.calls[ArgSections.N_GTOE_NONRELATING] ==
                ArgSections.calls[ArgSections.N_GET_TYPE_OF_EXPR]
        )
        assert(
            ArgSections.nanos[ArgSections.N_GTOE_RELATING] +
                ArgSections.nanos[ArgSections.N_GTOE_NONRELATING] ==
                ArgSections.nanos[ArgSections.N_GET_TYPE_OF_EXPR]
        )
        assert(
            ArgSections.calls[ArgSections.N_NARROW_RELATING] +
                ArgSections.calls[ArgSections.N_NARROW_NONRELATING] ==
                ArgSections.calls[ArgSections.N_NARROW]
        )
        assert(
            ArgSections.nanos[ArgSections.N_NARROW_RELATING] +
                ArgSections.nanos[ArgSections.N_NARROW_NONRELATING] ==
                ArgSections.nanos[ArgSections.N_NARROW]
        )
        // Nothing is left parked once the last invocation has ended.
        assert(ArgSections.pendingArgType == -1L)
        assert(ArgSections.pendingNarrowCalls == 0L)
        ArgSections.reset()
    }
}
