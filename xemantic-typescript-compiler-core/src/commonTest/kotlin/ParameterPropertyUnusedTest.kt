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
 * (CHK.221) TS6138 "Property 'X' is declared but its value is never read." is tsgo
 * `checkUnusedClassMembers`'s constructor arm: only a parameter carrying the `private`
 * SYNTACTIC modifier, reported as `UnusedKindLocal` (so under `noUnusedLocals` alone), and any
 * non-write-only reference of the name inside the class — `this.p`, `other.p`, `this["p"]`, a
 * destructuring — keeps it. A `public` / `protected` / bare `readonly` / `override` parameter
 * property is API and is never reported, by TS6138 OR by the parameter half of TS6133
 * (`isParameterPropertyDeclaration`).
 *
 * Every expectation is tsgo 7.0.2's output for the fixture (`tools/tsgo-7.0.2/lib/tsc`,
 * `build/bench/p18276-agent/m221`), 1-based columns.
 */
class ParameterPropertyUnusedTest {

    private val unusedCodes = setOf(6133, 6138)

    private fun rows(source: String, locals: Boolean, params: Boolean): List<String> =
        diagnose(
            source,
            directives = "// @strict: true\n// @target: es2022\n// @noUnusedLocals: $locals\n// @noUnusedParameters: $params",
        ).filter { it.code in unusedCodes }
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
            .sorted()

    private val modifiers = """
        export class E {
            constructor(public a: number, private b: number, protected c: number, readonly d: number, public readonly e: number, private readonly f: number, x: number) {}
        }
        class N {
            constructor(public a: number, private b: number, protected c: number, readonly d: number, public readonly e: number, private readonly f: number, x: number) {}
        }
        export class R {
            constructor(public a: number, private b: number, protected c: number, readonly d: number, private readonly f: number) {}
            m() { return this.a + this.b + this.c + this.d + this.f; }
        }
        class Base { constructor(public o: number) {} }
        export class D extends Base {
            constructor(override o: number) { super(o); }
        }
        export function f() {
            class Inner { constructor(private z: number, public w: number) {} }
            return Inner;
        }
        export const CE = class { constructor(private y: number) {} };
        void N;
    """

    @Test
    fun `only a private parameter property is reported and nested classes and class expressions are checked`() {
        val r = rows(modifiers, locals = true, params = true)
        assert(
            r == listOf(
                "2:43 TS6138 Property 'b' is declared but its value is never read.",
                "2:139 TS6138 Property 'f' is declared but its value is never read.",
                "2:150 TS6133 'x' is declared but its value is never read.",
                "5:43 TS6138 Property 'b' is declared but its value is never read.",
                "5:139 TS6138 Property 'f' is declared but its value is never read.",
                "5:150 TS6133 'x' is declared but its value is never read.",
                "16:39 TS6138 Property 'z' is declared but its value is never read.",
                "19:47 TS6138 Property 'y' is declared but its value is never read.",
            ).sorted()
        )
    }

    @Test
    fun `TS6138 is a noUnusedLocals row and never a noUnusedParameters one`() {
        val r = rows(modifiers, locals = false, params = true)
        assert(
            r == listOf(
                "2:150 TS6133 'x' is declared but its value is never read.",
                "5:150 TS6133 'x' is declared but its value is never read.",
            ).sorted()
        )
    }

    @Test
    fun `a write-only access does not count while another instance and a compound write do`() {
        val r = rows(
            """
            export class W {
                private q = 1;
                constructor(private p: number, private r: number, private s: number, private u: number) {}
                m() { this.q = 2; this.p = 3; this.r++; return [this.s] }
                n(o: W) { return o.u; }
            }
            export class X {
                constructor(private a: number, private b: number) { console.log(a); }
                get g() { return this["b"]; }
            }
            export class Y {
                constructor(private c: number) {}
                m() { const { c } = this; return c; }
            }
            export class Z {
                constructor(private d: number) {}
                m() { return () => this.d; }
            }
            """,
            locals = true, params = false,
        )
        assert(
            r == listOf(
                "2:13 TS6133 'q' is declared but its value is never read.",
                "3:25 TS6138 Property 'p' is declared but its value is never read.",
                "8:25 TS6138 Property 'a' is declared but its value is never read.",
            ).sorted()
        )
    }
}
