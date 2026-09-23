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
 * (CHK.141)(b) round P18.177 — a CONTEXTUAL `this:` parameter must TYPE `this`
 * inside a function expression, at EVERY position that supplies one.
 *
 * Before this round only an EXPLICIT `this:` written on the function expression
 * typed `this`; a contextual one was applied nowhere, so a member assignment, a
 * variable annotation, a call argument and an object-literal property value were
 * all SILENT where tsgo 7.0.2 reports. The cause was not the rule but its GATE:
 * `applyPulledContextualParamTypes` returned early when the function's own
 * PARAMETER list held nothing pullable, one line above the `this` write — so a
 * function expression with no parameters at all, which is the shape that needs
 * the rule most, never reached it. tsgo's `assignContextualParameterTypes`
 * (checker.go:10325) gates its `this` half on `context.thisParameter` alone and
 * runs it BEFORE the parameter loop.
 *
 * Every pin asserts a VALUE — a deliberate mis-assignment off `this` whose
 * TS2322 NAMES the type `this` was given — because a silence cannot tell "typed"
 * from `any`, and that is exactly the distinction this family is about. Every
 * expectation below is tsgo 7.0.2's own row, measured.
 */
class ContextualThisParameterTypeTest {

    private val act = "interface Act { tag: number; }\n"

    private fun rows(source: String): List<Diagnostic> = diagnose(source)

    private val namesNumber = "Type 'number' is not assignable to type 'string'."

    @Test
    fun `a function expression assigned to a member takes its this from the member's type`() {
        val d = rows(
            act + """
            interface Holder { w: (this: Act) => void; }
            declare const h: Holder;
            h.w = function () { const p: string = this.tag; };
            """.trimIndent()
        )
        val named = d.count { it.code == 2322 && it.message == namesNumber }
        val implicitThis = d.count { it.code == 2683 }
        assert(named == 1)
        assert(implicitThis == 0)
    }

    @Test
    fun `a variable's FunctionType annotation supplies the this type`() {
        val d = rows(
            act + """
            const w: (this: Act) => void = function () { const p: string = this.tag; };
            """.trimIndent()
        )
        val named = d.count { it.code == 2322 && it.message == namesNumber }
        val implicitThis = d.count { it.code == 2683 }
        assert(named == 1)
        assert(implicitThis == 0)
    }

    @Test
    fun `a call argument's parameter type supplies the this type`() {
        val d = rows(
            act + """
            declare function take(cb: (this: Act) => void): void;
            take(function () { const p: string = this.tag; });
            """.trimIndent()
        )
        val named = d.count { it.code == 2322 && it.message == namesNumber }
        val implicitThis = d.count { it.code == 2683 }
        assert(named == 1)
        assert(implicitThis == 0)
    }

    @Test
    fun `an object-literal property value takes its this from the annotated member`() {
        val d = rows(
            act + """
            const o: { m: (this: Act) => void } = {
              m: function () { const p: string = this.tag; },
            };
            """.trimIndent()
        )
        val named = d.count { it.code == 2322 && it.message == namesNumber }
        val implicitThis = d.count { it.code == 2683 }
        assert(named == 1)
        assert(implicitThis == 0)
    }

    @Test
    fun `a contextual this reaches a function expression that declares parameters too`() {
        val d = rows(
            act + """
            const w: (this: Act, s: string) => void = function (s) {
              const p: string = this.tag;
            };
            """.trimIndent()
        )
        val named = d.count { it.code == 2322 && it.message == namesNumber }
        assert(named == 1)
    }

    @Test
    fun `an explicit this annotation on the function expression still types this`() {
        val d = rows(
            act + """
            const w: (this: Act) => void = function (this: Act) {
              const p: string = this.tag;
            };
            """.trimIndent()
        )
        val named = d.count { it.code == 2322 && it.message == namesNumber }
        val implicitThis = d.count { it.code == 2683 }
        assert(named == 1)
        assert(implicitThis == 0)
    }

    @Test
    fun `an explicit this annotation OUTRANKS the contextual one`() {
        // tsgo's own skip condition is `parameter == nil ||
        // parameter.ValueDeclaration.Type() == nil` — an annotated own `this:`
        // wins. The two types differ in the MEMBER type, so the elaboration says
        // which one was used: 'string' is the explicit one, 'number' the
        // contextual one.
        val d = rows(
            act + """
            const w: (this: Act) => void = function (this: { tag: string }) {
              const p: boolean = this.tag;
            };
            """.trimIndent()
        )
        val fromExplicit = d.count {
            it.code == 2322 && it.message == "Type 'string' is not assignable to type 'boolean'."
        }
        val fromContextual = d.count {
            it.code == 2322 && it.message == "Type 'number' is not assignable to type 'boolean'."
        }
        assert(fromExplicit == 1)
        assert(fromContextual == 0)
    }

    @Test
    fun `an ARROW does not take a contextual this`() {
        // tsgo's rule is that only a function EXPRESSION has a `this` of its own; an
        // arrow inherits the enclosing one. At module top level there is no enclosing
        // `this`, so tsgo reports TS7041 plus TS7017 and says NOTHING about `Act` —
        // an arrow that had taken the annotation's `this: Act` would additionally
        // report the member row below, which is what this pin refuses.
        //
        // THE SHAPE IS LOAD-BEARING: the same assertion written over an arrow inside a
        // CLASS METHOD reads 0 RED under the ablation that drops the guard, because
        // `currentClassForThis` decides that case and the write is never consulted.
        // Measured: with the guard dropped this fixture grows
        // `Type 'number' is not assignable to type 'string'.`, which tsgo 7.0.2 does
        // not emit.
        val d = rows(
            act + """
            const v: (this: Act) => void = () => { const q: string = this.tag; };
            """.trimIndent()
        )
        val tookTheContextualThis = d.count {
            it.code == 2322 && it.message == namesNumber
        }
        assert(tookTheContextualThis == 0)
        assert(d.any { it.code == 7041 })
    }

    @Test
    fun `an arrow inside a class method keeps the class this - control - does not discriminate the arrow guard`() {
        // A correct behavioural assertion and a MEASURED-NON-DISCRIMINATING one: the
        // class `this` is installed by a different mechanism and wins whether or not
        // the contextual-`this` rule refuses arrows, so the ablation that drops the
        // `fn is FunctionExpression` guard leaves this GREEN.
        val d = rows(
            act + """
            declare function take(cb: (this: Act) => void): void;
            class C {
              tag: string = "";
              m(): void {
                take(() => { const p: number = this.tag; });
              }
            }
            """.trimIndent()
        )
        val fromClass = d.count {
            it.code == 2322 && it.message == "Type 'string' is not assignable to type 'number'."
        }
        assert(fromClass == 1)
    }

    @Test
    fun `negative control - a function expression with no contextual this still reports TS2683`() {
        val d = rows(
            """
            function outer() {
              const g = function () { return this; };
              return g;
            }
            """.trimIndent()
        )
        assert(d.count { it.code == 2683 } == 1)
    }
}
