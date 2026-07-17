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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * INV.4(e) g1b chunk 1 (round 557): STATEMENT-ARM reach pins for the
 * checkPropertyAccess giant (checkPropertyAccessInStatement's 22 arms + the
 * class-member walker), pre-verified on the CURRENT walker before its spine
 * migration. The prelude interface receiver (`declare const o: Rec`) makes
 * TS2339 fire wherever the walk REACHES the access — each pin isolates one
 * arm's reach or state quirk.
 */
class Inv4SpineG1PinsTest {

    private val prelude = """
        interface Rec { known: number; }
        declare const o: Rec;
    """.trimIndent()

    // ── basic reach ─────────────────────────────────────────────────────────

    @Test
    fun `top-level expression statement fires TS2339`() {
        val d = diagnose(prelude + "\no.missing;\n")
        kotlin.test.assertEquals(1, d.count { it.code == 2339 }, "expected 1 TS2339, got: $d")
    }

    @Test
    fun `negative control - the whole pass skips js files`() {
        diagnose(prelude + "\no.missing;\n", directives = "// @allowJs: true\n// @checkJs: true", fileName = "t.js") should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `var initializer return throw and export-assignment positions are reached`() {
        val d = diagnose(prelude + """
            const a = o.m1;
            function f() { return o.m2; }
            function g() { throw o.m3; }
        """)
        kotlin.test.assertEquals(3, d.count { it.code == 2339 }, "expected 3 TS2339, got: ${d.filter { it.code == 2339 }}")
    }

    @Test
    fun `if while do with and labeled arms are reached`() {
        val d = diagnose(prelude + """
            declare const c: boolean;
            if (o.m1) { o.m2; } else { o.m3; }
            while (o.m4) { break; }
            do { o.m5; } while (c);
            lbl: { o.m6; }
        """)
        kotlin.test.assertEquals(6, d.count { it.code == 2339 }, "expected 6 TS2339, got: ${d.filter { it.code == 2339 }}")
    }

    @Test
    fun `switch subject case expressions and clause bodies are reached`() {
        val d = diagnose(prelude + """
            switch (o.m1) {
                case o.m2: o.m3; break;
                default: o.m4; break;
            }
        """)
        kotlin.test.assertEquals(4, d.count { it.code == 2339 }, "expected 4 TS2339, got: ${d.filter { it.code == 2339 }}")
    }

    @Test
    fun `try catch and finally blocks are reached`() {
        val d = diagnose(prelude + """
            try { o.m1; } catch (e) { o.m2; } finally { o.m3; }
        """)
        kotlin.test.assertEquals(3, d.count { it.code == 2339 }, "expected 3 TS2339, got: ${d.filter { it.code == 2339 }}")
    }

    @Test
    fun `enum member initializers are reached`() {
        val d = diagnose(prelude + """
            enum E { A = 1, B = o.m1 }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2339 }, "expected 1 TS2339, got: $d")
    }

    @Test
    fun `namespace bodies are reached`() {
        val d = diagnose(prelude + """
            namespace NS { const a = o.m1; }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2339 }, "expected 1 TS2339, got: $d")
    }

    // ── the for-statement reach quirk ───────────────────────────────────────

    @Test
    fun `for condition and incrementor are reached but the INITIALIZER is not`() {
        // The legacy arm walks condition + incrementor + body only — a
        // property access in the for-header declarator initializer is
        // silently unchecked by this pass (bug-compat pin: the migration
        // must reproduce the non-visit, not widen it).
        val d1 = diagnose(prelude + """
            for (let i = 0; o.m1; o.m2) { o.m3; }
        """)
        kotlin.test.assertEquals(3, d1.count { it.code == 2339 }, "cond/incr/body: expected 3, got: ${d1.filter { it.code == 2339 }}")
        val d2 = diagnose(prelude + """
            for (let a = o.m1; ;) { break; }
        """)
        kotlin.test.assertEquals(0, d2.count { it.code == 2339 }, "for-init: expected 0 (unreached), got: $d2")
    }

    // ── loop-variable typing state ──────────────────────────────────────────

    @Test
    fun `for-in loop variable types as string in the body`() {
        val d = diagnose(prelude + """
            declare const obj: object;
            for (const k in obj) { k.missing; }
        """)
        d should { have(any { it.code == 2339 && "'string'" in it.message }) }
    }

    @Test
    fun `for-of over an array types the loop variable as the element type`() {
        val d = diagnose(prelude + """
            declare const arr: Rec[];
            for (const e of arr) { e.missing; }
        """)
        d should { have(any { it.code == 2339 && "'Rec'" in it.message }) }
    }

    // ── class arms ──────────────────────────────────────────────────────────

    @Test
    fun `this accesses in methods getters and property initializers resolve the class type`() {
        val d = diagnose("""
            class K {
                known: number = 1;
                p = this.m1;
                m() { this.m2; }
                get g() { this.m3; return 1; }
                set s(v: number) { this.m4; }
                constructor() { this.m5; }
            }
        """)
        kotlin.test.assertEquals(5, d.count { it.code == 2339 }, "expected 5 TS2339, got: ${d.filter { it.code == 2339 }}")
    }

    @Test
    fun `negative control - a function declaration nested in a method loses the class this`() {
        // The FunctionDeclaration arm passes enclosingClassType = null — a
        // `this.x` inside it is NOT checked against the class (tsc would
        // flag the `this` itself differently; this pass stays silent).
        diagnose("""
            class K {
                known: number = 1;
                m() {
                    function inner() {
                        return (this as any as K) && this.missing;
                    }
                    return inner;
                }
            }
        """) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `namespace-nested class this-access resolves through the namespace stack`() {
        val d = diagnose("""
            namespace M {
                export class K {
                    known: number = 1;
                    m() { this.missing; }
                }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2339 }, "expected 1 TS2339, got: $d")
    }

    @Test
    fun `block-scoped class this-access resolves through the lexical scope tables`() {
        // B83.5/INV.2(d): a class inside a while body is not conventionally
        // bound; the lexical-scope consumer restores this.X checking.
        val d = diagnose("""
            while (0) {
                class B {
                    methodB() { this.methodA; }
                }
            }
        """)
        // the near-miss member name draws the TS2551 spelling variant
        kotlin.test.assertEquals(1, d.count { it.code == 2551 }, "expected 1 TS2551, got: $d")
    }

    @Test
    fun `a this-typed method parameter overrides the enclosing class for member access`() {
        val d = diagnose("""
            class A { known: number = 1; }
            class B {
                other: string = "";
                foo(this: A) { this.other; }
            }
        """)
        d should { have(any { it.code == 2339 && "'other'" in it.message }) }
    }

    @Test
    fun `heritage clause expressions are reached`() {
        val d = diagnose(prelude + """
            declare const base: { K: new () => object };
            class C extends (o.missingBase as any) { }
        """)
        d should { have(any { it.code == 2339 }) }
    }

    // ── state discipline ────────────────────────────────────────────────────

    @Test
    fun `call-initialized locals flow their concrete type to later receivers`() {
        // B136: `const a = mk()` records the call's concrete return type so a
        // later `a.missing` resolves the receiver.
        val d = diagnose(prelude + """
            declare function mk(): Rec;
            function f() {
                const a = mk();
                a.missing;
            }
        """)
        d should { have(any { it.code == 2339 && "'Rec'" in it.message }) }
    }

    @Test
    fun `negative control - function bodies restore local-type scope on exit`() {
        // The fn-body scope copies must not leak: a body-local recording of
        // `a` inside f must not affect the outer `a` receiver after f.
        diagnose(prelude + """
            declare const a: Rec;
            function f() {
                const a: { other: number } = { other: 1 };
                return a.other;
            }
            const ok = a.known;
        """) should {
            have(none { it.code == 2339 })
        }
    }
}
