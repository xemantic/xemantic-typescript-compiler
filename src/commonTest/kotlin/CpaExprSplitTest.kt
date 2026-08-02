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
 * (JIT.1)(c) round 805 — the behavioural gate for the four-way split of
 * `checkPropertyAccessInExpr`.
 *
 * The function was **9,062 bytecodes**, above HotSpot's 8,000-byte
 * `HugeMethodLimit`, so it was never JIT-compiled and ran in the interpreter for
 * the whole process. Its four largest `when` arms — `ArrowFunction`,
 * `ObjectLiteralExpression`, `FunctionExpression`, `ClassExpression` — are now
 * `cpaExpr*` helpers, leaving the entry at 4,728.
 *
 * **What `HugeMethodLimitTest` cannot see, and this class pins.** A size check
 * proves the parts are small. It says nothing about whether the arm is still
 * REACHED, whether the arm's scope save/restore still brackets the arm from the
 * caller's point of view, whether the arguments the arm passes DOWN are still the
 * ones it used to pass, or whether the helpers still recurse back into the entry.
 *
 * Those are the four mistakes a mechanical extraction actually makes, so each
 * gets a pin: one ARM pin per helper (the arm fires at all), and four SEAM pins:
 *
 * * the arrow's and the function expression's parameter scopes are **restored**
 *   when the helper returns — read from a LATER ARGUMENT OF THE SAME CALL,
 *   because `withCpaFrameAmbient` reinstalls `currentLocalTypes` at every
 *   per-statement anchor and therefore erases the leak at the statement
 *   boundary (round 806's ablation: the original next-statement shape stayed
 *   GREEN against a binary with the restores deleted);
 * * a function expression's body is walked with `enclosingClassType = null`, not
 *   with the enclosing class — a helper that "helpfully" threaded its own
 *   parameter through would type `this` as the enclosing class instead of `any`;
 * * the class expression's enclosing-class state is restored, so a `this` access
 *   AFTER an anonymous class still reports against the real enclosing class.
 */
class CpaExprSplitTest {

    private val prelude = """
        interface Box { v: string }
        interface Outer { o: string }
        declare function run(cb: (b: Box) => void): void;
        declare function runF(cb: (b: Box) => void): void;
        declare function two(cb: (b: Box) => void, x: string): void;
    """.trimIndent() + "\n"

    // ------------------------------------------------------------------- arms

    @Test
    fun `ArrowFunction arm - a contextually typed arrow parameter is still typed from the callee signature`() {
        val d = diagnose(prelude + "run(b => { b.nope; });")
        assert(d.any {
            it.code == 2339 && it.message == "Property 'nope' does not exist on type 'Box'."
        })
    }

    @Test
    fun `FunctionExpression arm - a contextually typed function-expression parameter is still typed`() {
        val d = diagnose(prelude + "runF(function (b) { b.nope; });")
        assert(d.any {
            it.code == 2339 && it.message == "Property 'nope' does not exist on type 'Box'."
        })
    }

    @Test
    fun `ObjectLiteral arm - a property value's arrow parameter is typed from the contextual member`() {
        val d = diagnose(
            prelude +
                """
                interface Cfg { onT: (b: Box) => void }
                declare function cfg(c: Cfg): void;
                cfg({ onT: b => { b.nope; } });
                """.trimIndent(),
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'nope' does not exist on type 'Box'."
        })
    }

    @Test
    fun `ClassExpression arm - a member body is checked against the anonymous class type`() {
        val d = diagnose(prelude + "const anon = class { m(): void { this.nope; } };")
        assert(d.any {
            it.code == 2339 &&
                it.message == "Property 'nope' does not exist on type '(Anonymous class)'."
        })
    }

    // ------------------------------------------------------------------ seams

    // Round 806, paying round 805's owed ablation B: the two restore seams below
    // were originally written as "the NEXT STATEMENT sees the outer binding
    // again", and that shape CANNOT discriminate — `withCpaFrameAmbient` (the
    // per-statement anchor install in `cpaSpineLeave`) saves and restores
    // `currentLocalTypes` around EVERY statement dispatch, so a dropped restore
    // inside an arm is invisible the moment the statement ends. Measured:
    // dropping `cpaExprArrowFunction`'s three restore lines left the original
    // pin GREEN. A leak is only observable WITHIN one statement, so both pins
    // now read the outer binding from a LATER ARGUMENT OF THE SAME CALL.

    @Test
    fun `ArrowFunction seam - the arrow's parameter scope is restored before the next argument`() {
        val d = diagnose(
            prelude +
                """
                function host(b: Outer): void {
                  two(b => { b.v; }, b.v);
                }
                """.trimIndent(),
        )
        // The arrow's `b: Box` must not survive the arm: the SECOND argument of
        // the same call sees `b` as `Outer` again, so `b.v` is an error there.
        assert(d.any {
            it.code == 2339 && it.message == "Property 'v' does not exist on type 'Outer'."
        })
    }

    @Test
    fun `FunctionExpression seam - the parameter scope is restored before the next argument`() {
        val d = diagnose(
            prelude +
                """
                function host(b: Outer): void {
                  two(function (b) { b.v; }, b.v);
                }
                """.trimIndent(),
        )
        assert(d.any {
            it.code == 2339 && it.message == "Property 'v' does not exist on type 'Outer'."
        })
    }

    @Test
    fun `FunctionExpression seam - the body is walked with no enclosing class type`() {
        val d = diagnose(
            prelude +
                """
                class Host {
                  outer: string = "";
                  m(): void {
                    runF(function (x) { this.nopeThis; });
                  }
                }
                """.trimIndent(),
        )
        // `enclosingClassType = null` is passed DOWN by this arm, so `this` inside a
        // function expression is untyped — TS2683, never a TS2339 against Host.
        assert(d.any { it.code == 2683 })
        assert(d.none { it.code == 2339 && it.message.contains("nopeThis") })
    }

    @Test
    fun `ClassExpression seam - the enclosing class type is restored after the arm returns`() {
        val d = diagnose(
            prelude +
                """
                class Host {
                  outer: string = "";
                  m(): void {
                    const k = class { im(): void { this.inner; } };
                    this.outer;
                    this.notThere;
                  }
                }
                """.trimIndent(),
        )
        assert(d.any {
            it.code == 2339 &&
                it.message == "Property 'inner' does not exist on type '(Anonymous class)'."
        })
        assert(d.any {
            it.code == 2339 && it.message == "Property 'notThere' does not exist on type 'Host'."
        })
        assert(d.none { it.code == 2339 && it.message.contains("'outer'") })
    }

    // ------------------------------------------------------------- the recursion

    @Test
    fun `all four extracted arms still recurse back into the entry`() {
        val d = diagnose(
            prelude +
                """
                interface Cfg { onT: (b: Box) => void; other: number }
                declare function cfg(c: Cfg): void;
                declare const rest: { other: number };
                cfg({
                  onT: b => {
                    const inner = function (q: Box) {
                      const anon = class { im(): void { this.deep; } };
                      q.deepFn;
                    };
                  },
                  ...rest,
                });
                """.trimIndent(),
        )
        // call -> object literal property -> arrow -> function expression -> class
        // expression: every hop crosses a helper boundary and comes back.
        assert(d.any {
            it.code == 2339 &&
                it.message == "Property 'deep' does not exist on type '(Anonymous class)'."
        })
        assert(d.any {
            it.code == 2339 && it.message == "Property 'deepFn' does not exist on type 'Box'."
        })
    }
}
