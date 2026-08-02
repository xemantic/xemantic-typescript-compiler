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
 * (IANY.1) round 798: pins the UNOBSERVABLE-CONTEXT gate on
 * `spineIanyEnterNode` — the implicit-any contextual-typing walker, and the
 * last of round 732's six biggest spine handlers to be opened.
 *
 * **What it skips.** `spineIanyCtx` has no reader outside this handler family,
 * and every reader sits at a node INSIDE the subtree the state was defined for.
 * So a state defined for a CHILDLESS child (`forEachChild` visits nothing for
 * `IDENTIFIER` / `STRING_LITERAL_NODE` / `NUMERIC_LITERAL_NODE`) can never be
 * read, and neither can a CALL's own `kind = 1` state when every argument is
 * childless. Measured on the compiler profile: **340 ms over 100,745 childless
 * children of a CALL/NEW parent (3,375 ns each — the arm computes
 * `calleeParamGivesNoContext`, round 737's largest single `getTypeOfExpression`
 * origin at 71,998 calls), 78 ms over 293,815 childless children of any other
 * parent, and 56 ms over 27,213 calls whose arguments are all childless.**
 *
 * **The two gates are COUPLED and the coupling is the interesting invariant.**
 * Skipping the callee resolution is only sound because every argument edge that
 * could read the resulting `typed` flag is itself skipped — which is exactly
 * the "all arguments childless" condition. The call's FRAME is still pushed
 * either way, because it shadows an enclosing `kind = 0` state for the CALLEE's
 * subtree, and an IIFE reads that (`iife arrow parameter…` below).
 *
 * **The excluded arms.** The seven function-likes and `ModuleDeclaration` push
 * an implicit-any SCOPE / a namespace symbol rather than only defining a state,
 * and an arrow's EXPRESSION body is precisely a childless child of one of them
 * — hence the `spineIanyScopePushParent` exclusion, pinned here by the
 * expression-bodied arrow whose own parameter must still be reported exactly
 * once.
 */
class IanyGateTest {

    @Test
    fun `an arrow ARGUMENT still receives its contextual parameter type`() {
        val d = diagnose(
            """
            declare function each(cb: (x: string) => void): void;
            each(x => { x.length; });
            """.trimIndent()
        )
        // The argument has a subtree, so the gate never touches this edge and
        // the parameter is contextually typed — no TS7006 anywhere.
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `an UNCONTEXTUALISED arrow argument still reports its implicit any`() {
        val d = diagnose(
            """
            declare function run(cb: any): void;
            run(y => y);
            """.trimIndent()
        )
        // `cb: any` makes calleeParamGivesNoContext true, so the argument keeps
        // NO contextual type and TS7006 must fire for `y`. This is the pin that
        // fails if the gate is widened to arguments that have a subtree.
        assert(d.count { it.code == 7006 } == 1)
    }

    @Test
    fun `a call whose arguments are all childless stays silent`() {
        val d = diagnose(
            """
            declare function pair(a: string, b: number): void;
            pair("x", 1);
            """.trimIndent()
        )
        // Every argument is childless, so BOTH gates fire: no callee resolution
        // and no argument edge. Nothing may change.
        assert(d.isEmpty())
    }

    /**
     * The sharpest form of the round's claim: run the SAME source with the gate
     * on and with `IanySections.gateOff` — the pre-798 code path, in this same
     * binary — and require the diagnostics to be equal. The fixture is chosen
     * for the shapes the gate's soundness argument rests on: an IIFE (whose
     * CALLEE subtree reads the frame the gate still pushes), a call whose
     * arguments are all childless, a nested call inside a contextually typed
     * arrow, and an un-annotated declaration so the comparison is never
     * vacuous.
     *
     * WHAT THIS PIN CORRECTED (round 798): its first form asserted that the
     * IIFE's `v` gets a TS7006 — it does not, on a WORKING binary, because
     * round 694's IIFE-argument contextual typing supplies the parameter type.
     * The pin was measuring an assumption about the fixture rather than the
     * gate, so it was restated as the equivalence it was written to defend
     * (round 797's law: verify the fixture reaches the population).
     */
    @Test
    fun `the gate is diagnostic-equivalent to the pre-change path on the iife and call shapes`() {
        val source = """
            declare function take(p): void;
            declare function each(cb: (x: string) => void): void;
            const n = ((v) => v)("s");
            take("s");
            each(q => { each(r => { r.length; }); q.length; });
            const twice = ((a, b) => a)("s", 1);
        """.trimIndent()
        val saved = IanySections.gateOff
        try {
            IanySections.gateOff = true
            val pre = diagnose(source).map { "${it.code}@${it.start}" }
            IanySections.gateOff = false
            val gated = diagnose(source).map { "${it.code}@${it.start}" }
            assert(gated == pre)
            assert(pre.isNotEmpty())
        } finally {
            IanySections.gateOff = saved
        }
    }

    @Test
    fun `an expression-bodied arrow still pushes its implicit-any scope`() {
        val d = diagnose(
            """
            const id = (a) => a;
            """.trimIndent()
        )
        // The arrow's BODY is a bare identifier — a childless child of a
        // scope-pushing parent, the case `spineIanyScopePushParent` excludes.
        // `a` is reported exactly once.
        assert(d.count { it.code == 7006 } == 1)
    }

    @Test
    fun `a skipped initializer edge cannot leak its declarator annotation`() {
        val d = diagnose(
            """
            type Fn = (x: string) => void;
            declare const missing: Fn;
            const first: Fn = missing;
            const second: Fn = z => { z.length; };
            """.trimIndent()
        )
        // `first`'s initializer is a bare identifier, so its edge is skipped and
        // the `spineIanyPendingAnnDecl` stash is left set. The consumer's test
        // is an IDENTITY against its own declarator, so `second` must still be
        // contextually typed.
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `an object-literal argument still contextually types its method`() {
        val d = diagnose(
            """
            declare function mount(o: { run(a: string): void }): void;
            mount({ run(a) { a.length; } });
            """.trimIndent()
        )
        // The argument has a subtree, so neither gate fires and the method
        // parameter keeps its contextual type.
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `negative control - an un-annotated parameter is still reported at its own declaration`() {
        val d = diagnose(
            """
            declare function take(p): void;
            take("s");
            """.trimIndent()
        )
        // The call is fully gated; the TS7006 that survives belongs to the
        // DECLARATION, not to the call, so this fires on both arms — it is here
        // to show the fixture reaches the population at all.
        assert(d.count { it.code == 7006 } == 1)
    }
}
