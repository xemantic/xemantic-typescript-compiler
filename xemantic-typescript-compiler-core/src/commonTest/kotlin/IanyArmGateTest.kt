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
 * (IANY.1) round 799: pins the ARM PRE-GATE on `spineIanyEdgeEnter`.
 *
 * **What it is.** That dispatch is a chain of 19 sequential `is` checks ending
 * in `else -> {}`, run for every node that has a parent. **249,471 of the
 * 451,292 parent edges whose child has a subtree match none of them**
 * (`--ianySections`, compiler profile), so the chain is pure consultation there.
 * `spineIanyEdgeHasArm(kindId)` answers the same question with one M0.2
 * tableswitch, which halves that row (120/108 → 61/61 ms) for a handler-total
 * Δ of ~55 ms.
 *
 * **Why it is a no-op by construction**: every arm of the `when (p)` is a
 * concrete node class, each stamped with exactly the kind the gate lists
 * (`NodeKindIdTest` pins that correspondence), so a parent kind the gate refuses
 * would have reached `else -> {}` anyway.
 *
 * **The failure mode these pins exist for** is the one that makes the gate
 * dangerous rather than the one that makes it wrong in theory: **a kind DROPPED
 * from (or never added to) `spineIanyEdgeHasArm` silently disables its arm** —
 * the same trap as `ccetPrologueMayFire` (round 793), whose tell is a baseline
 * losing a diagnostic with no change anywhere near the arm. The equivalence test
 * below catches exactly that, because production and `armGateOff` then disagree.
 */
class IanyArmGateTest {

    private val prelude = """
        type Fn = (x: string) => void;
        declare function each(cb: Fn): void;
        declare function run(cb: any): void;
        declare const cond: boolean;
        declare const other: Fn;
    """.trimIndent() + "\n"

    /**
     * The round's claim in its sharpest form: run a fixture that reaches EVERY
     * armed parent kind with the gate on and with [IanySections.armGateOff] —
     * the pre-799 19-arm chain, in this same binary — and require the diagnostic
     * sets to be equal.
     *
     * The fixture is deliberately non-vacuous (the last two declarations emit),
     * so the comparison can never pass by both arms being empty.
     */
    @Test
    fun `the arm pre-gate is diagnostic-equivalent to the full is-chain on every armed parent kind`() {
        val source = prelude + """
            function fnDecl(mk: (cb: Fn) => void) { mk(q => { q.length; }); }
            const fnExpr = function (mk: (cb: Fn) => void) { mk(q => { q.length; }); };
            const arrow = (mk: (cb: Fn) => void) => { mk(q => { q.length; }); };
            class C {
              readonly prop: Fn = z => { z.length; };
              constructor(mk: (cb: Fn) => void) { mk(q => { q.length; }); }
              method(mk: (cb: Fn) => void) { mk(q => { q.length; }); }
              get g(): Fn { return z => { z.length; }; }
              set s(v: Fn) { each(z => { z.length; }); }
            }
            namespace N { export const inNs: Fn = z => { z.length; }; }
            const viaVarDecl: Fn = z => { z.length; };
            const viaParen: Fn = (z => { z.length; });
            const viaCond: Fn = cond ? z => { z.length; } : other;
            const viaObj: { p: Fn } = { p: z => { z.length; } };
            const viaArray: { p: Fn }[] = [{ p: z => { z.length; } }];
            let viaAssign: Fn;
            viaAssign = z => { z.length; };
            each(z => { z.length; });
            new C((cb) => { cb("s"); });
            function viaReturn(): Fn { return z => { z.length; }; }
            run(y => y);
            declare function untyped(p): void;
        """.trimIndent()
        val saved = IanySections.armGateOff
        try {
            IanySections.armGateOff = true
            val pre = diagnose(source).map { "${it.code}@${it.start}" }
            IanySections.armGateOff = false
            val gated = diagnose(source).map { "${it.code}@${it.start}" }
            assert(gated == pre)
            assert(pre.isNotEmpty())
        } finally {
            IanySections.armGateOff = saved
        }
    }

    @Test
    fun `a CallExpression argument still receives its contextual parameter type`() {
        val d = diagnose(prelude + "each(z => { z.length; });")
        // The CALL_EXPRESSION arm is what defines the argument's `typed` state;
        // without it `z` would be an implicit any.
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `a VariableDeclaration initializer still receives its declared annotation`() {
        val d = diagnose(prelude + "const f: Fn = z => { z.length; };")
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `an enclosing function body still pushes the scope its nested callee resolves through`() {
        val d = diagnose(
            prelude + "function outer(mk: (cb: Fn) => void) { mk(q => { q.length; }); }"
        )
        // `mk` is a PARAMETER, so it is in no file-level table: the callee
        // resolves only through the implicit-any SCOPE pushed by the
        // FunctionDeclaration body edge. Lose that arm and `q` reports TS7006.
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `negative control - an un-annotated declaration parameter is still reported`() {
        val d = diagnose(prelude + "declare function untyped(p): void;")
        // Shows the fixtures above are compared against a checker that does emit
        // TS7006 — a silent build would satisfy every assertion here.
        assert(d.count { it.code == 7006 } == 1)
    }
}
