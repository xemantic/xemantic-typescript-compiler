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
 * (CALL.6) round 797 — the LEVEL-S sub-partition of `checkArgumentsAgainstSignature`'s
 * `argType` row: by ARGUMENT KIND, and into the arm chain.
 *
 * Round 796's exit census localised 69% of the argument-typing time and 81% of
 * the flow narrowing onto ONE `continue` — and could not say what that
 * population IS, because the exit predicate reads the PARAMETER while the cost
 * belongs to the ARGUMENT (round 759). The census below classifies the
 * argument itself and crosses that with the exit row.
 *
 * Four invariants, and the first two are what make the numbers a MEASUREMENT
 * rather than a sample:
 *
 * 1. **The kind split PARTITIONS the row it came from** — iterations, `argType`
 *    nanos, `getTypeOfExpression` nanos and the narrowing nanos AND walks all
 *    sum back exactly. A later edit that forgets a hook turns the partition
 *    into a sample, and a sample would read as "kind X is cheap" whatever the
 *    truth was.
 * 2. **The kind × exit cross-tab accounts for every iteration**, including the
 *    ones that never reach the argType block at all (`K_NONE`).
 * 3. **The classification is non-vacuous**: the fixture exercises six kinds,
 *    and the CONTEXTUAL-install column separates them the way the production
 *    `useCtx` gate does — an arrow argument against a function-typed parameter
 *    installs one, an identifier argument never does.
 * 4. **The arm chain contains its own sub-measures** and is contained by the
 *    argType row, so the row is `getTypeOfExpression` + literal + chain + a
 *    NAMED residue rather than a residual with a story attached.
 */
class ArgKindCensusTest {

    private val source = """
        interface Node { kind: number }
        interface Ident extends Node { text: string }
        function isIdent(n: Node): n is Ident { return n.kind === 1 }
        function takesIdent(i: Ident): number { return i.text.length }
        function takesNumber(n: number): number { return n }
        function takesNode(n: Node): number { return n.kind }
        function apply(f: (x: number) => number, v: number): number { return f(v) }
        function main(n: Node, o: Node, s: string): number {
            let total = 0
            total += apply((x) => x + 1, 2)
            total += takesNumber(s.length)
            total += takesNode(o)
            total += takesNumber(takesNode(n))
            total += takesNumber(1 + 2)
            total += takesNode({ kind: 7 })
            return total
        }
        // An EARLY-RETURN guard, not an `if` body: since round 785 a type-guard
        // call in an `if` condition writes its narrow into currentLocalTypes for
        // the THEN branch, so an argument inside the block arrives already
        // narrowed and no flow read happens at all (round 796 lost half a
        // session to exactly that fixture).
        function keptWalk(n: Node): number {
            if (!isIdent(n)) { return 0 }
            return takesIdent(n)
        }
        main({ kind: 1 }, { kind: 2 }, "s")
        keptWalk({ kind: 1 })
    """.trimIndent()

    private fun runProbe(mode: Int = ArgSections.ON) {
        // SAVE-AND-RESTORE, never "assign the default back" — the mode is
        // fork-global (the round-619 Inv0PassTimingTest lesson).
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
    fun `the kind-name table is index-aligned and complete`() {
        assert(ArgSections.kindNames.size == ArgSections.KINDS)
        assert(ArgSections.K_NONE == 0)
        assert(ArgSections.K_OTHER == ArgSections.KINDS - 1)
        // The two new sub-measures sit BELOW the three probe-only rows, so the
        // partition rows and the calibration counterpart are unmoved.
        assert(ArgSections.N_GATE_REL < ArgSections.ENTRY)
        assert(ArgSections.N_ARM_CHAIN < ArgSections.N_GATE_REL)
        assert(ArgSections.N_ARM_CHAIN >= ArgSections.FIRST_NESTED)
        assert(!ArgSections.coarseAnchor[ArgSections.N_ARM_CHAIN])
        assert(!ArgSections.coarseAnchor[ArgSections.N_GATE_REL])
    }

    @Test
    fun `the kind split partitions the argType row exactly`() {
        runProbe()
        var iters = 0L
        var argType = 0L
        var gtoe = 0L
        var narrow = 0L
        var narrowCalls = 0L
        for (k in 0 until ArgSections.KINDS) {
            iters += ArgSections.kindIters[k]
            argType += ArgSections.kindArgType[k]
            gtoe += ArgSections.kindGtoe[k]
            narrow += ArgSections.kindNarrow[k]
            narrowCalls += ArgSections.kindNarrowCalls[k]
        }
        assert(iters == ArgSections.calls[ArgSections.L_ARGTYPE])
        assert(argType == ArgSections.nanos[ArgSections.L_ARGTYPE])
        assert(gtoe == ArgSections.nanos[ArgSections.N_GET_TYPE_OF_EXPR])
        assert(narrow == ArgSections.nanos[ArgSections.N_NARROW])
        assert(narrowCalls == ArgSections.calls[ArgSections.N_NARROW])
        // An iteration that reached the argType block always carries a real
        // kind — K_NONE is only ever the state BEFORE the classification.
        assert(ArgSections.kindIters[ArgSections.K_NONE] == 0L)
        ArgSections.reset()
    }

    @Test
    fun `the kind by exit cross-tab accounts for every iteration`() {
        runProbe()
        var total = 0L
        for (v in ArgSections.kindExitIters) total += v
        assert(total == ArgSections.iterations)
        // The 557-equivalent population: iterations that never reached the
        // argType block leave at the loop's own gate row and nowhere else.
        var noneElsewhere = 0L
        for (s in 0 until ArgSections.N) {
            if (s == ArgSections.L_PARAM) continue
            noneElsewhere += ArgSections.kindExitIters[ArgSections.K_NONE * ArgSections.N + s]
        }
        assert(noneElsewhere == 0L)
        // The round's own finding, pinned on a fixture: the exit that holds 39%
        // of the compiler profile's iterations — the 196-line
        // function-vs-function block — is reached by IDENTIFIER arguments, not
        // by the arrows and callbacks round 796 hypothesised. (On the compiler
        // profile: Identifier 7,575 + PropertyAccess 5,032 of 15,640, against
        // 527 arrows and function expressions combined.)
        assert(
            ArgSections.kindExitIters[
                ArgSections.K_IDENT * ArgSections.N + ArgSections.L_NOTSIMPLE
            ] > 0L
        )
        // Every arrow iteration is accounted for by exactly one exit row, so
        // the arrow population is a MEASUREMENT and not a sample — which is
        // what makes "arrows are 3.4% of that exit" quotable.
        var arrowExits = 0L
        for (s in 0 until ArgSections.N) {
            arrowExits += ArgSections.kindExitIters[ArgSections.K_ARROW * ArgSections.N + s]
        }
        assert(arrowExits == ArgSections.kindIters[ArgSections.K_ARROW])
        ArgSections.reset()
    }

    @Test
    fun `the classification separates the kinds the fixture contains`() {
        runProbe()
        assert(ArgSections.kindIters[ArgSections.K_ARROW] > 0L)
        assert(ArgSections.kindIters[ArgSections.K_IDENT] > 0L)
        assert(ArgSections.kindIters[ArgSections.K_LITERAL] > 0L)
        assert(ArgSections.kindIters[ArgSections.K_PROP_ACCESS] > 0L)
        assert(ArgSections.kindIters[ArgSections.K_CALL] > 0L)
        assert(ArgSections.kindIters[ArgSections.K_OPERATOR] > 0L)
        assert(ArgSections.kindIters[ArgSections.K_OBJ_LIT] > 0L)
        // The CONTEXTUAL-install column is the production `useCtx` gate:
        // arrow / function-expression / object-literal arguments against a
        // `Type.Object` parameter install one, and nothing else ever does.
        assert(ArgSections.kindCtx[ArgSections.K_ARROW] > 0L)
        assert(ArgSections.kindCtx[ArgSections.K_IDENT] == 0L)
        assert(ArgSections.kindCtx[ArgSections.K_LITERAL] == 0L)
        assert(ArgSections.kindCtx[ArgSections.K_CALL] == 0L)
        // A narrowing walk is charged to the kind of the argument that launched
        // it — `keptWalk`'s early-return-guarded identifier is the one walk the
        // (CALL.5)(b) gate must not refuse.
        assert(ArgSections.kindNarrowCalls[ArgSections.K_IDENT] > 0L)
        ArgSections.reset()
    }

    @Test
    fun `the arm chain sits inside the argType row and contains its own measures`() {
        runProbe()
        // The chain closes once per iteration that computed an argument type.
        assert(
            ArgSections.calls[ArgSections.N_ARM_CHAIN] ==
                ArgSections.calls[ArgSections.L_ARGTYPE]
        )
        assert(
            ArgSections.nanos[ArgSections.N_ARM_CHAIN] <=
                ArgSections.nanos[ArgSections.L_ARGTYPE]
        )
        // Everything the chain is made of is nested INSIDE it.
        assert(
            ArgSections.nanos[ArgSections.N_NARROW] +
                ArgSections.nanos[ArgSections.N_ARGTYPE_REL] +
                ArgSections.nanos[ArgSections.N_GATE_REL] <=
                ArgSections.nanos[ArgSections.N_ARM_CHAIN]
        )
        // The pre-gate relation runs once per visit to either gated arm, so it
        // is rarer than the chain and — with the gate live — at least as
        // frequent as the walks those arms still launch.
        assert(ArgSections.calls[ArgSections.N_GATE_REL] > 0L)
        assert(
            ArgSections.calls[ArgSections.N_GATE_REL] <
                ArgSections.calls[ArgSections.N_ARM_CHAIN]
        )
        ArgSections.reset()
    }

    @Test
    fun `nothing is recorded under COARSE or when the probe is off`() {
        runProbe(ArgSections.COARSE)
        assert(ArgSections.kindIters.sum() == 0L)
        assert(ArgSections.kindExitIters.sum() == 0L)
        assert(ArgSections.calls[ArgSections.N_ARM_CHAIN] == 0L)
        assert(ArgSections.calls[ArgSections.N_GATE_REL] == 0L)
        runProbe(ArgSections.OFF)
        assert(ArgSections.kindIters.sum() == 0L)
        assert(ArgSections.kindArgType.sum() == 0L)
        assert(ArgSections.kindExitIters.sum() == 0L)
        assert(ArgSections.calls[ArgSections.N_ARM_CHAIN] == 0L)
        ArgSections.reset()
    }
}
