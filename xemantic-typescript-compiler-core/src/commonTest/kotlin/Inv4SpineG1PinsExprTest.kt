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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * INV.4(e) g1b chunk 2 (round 558): EXPRESSION-ARM reach pins for the
 * checkPropertyAccess giant (checkPropertyAccessInExpr's 26 arms),
 * pre-verified on the CURRENT walker before its spine migration —
 * companion to [Inv4SpineG1PinsTest]'s statement-arm chunk.
 */
class Inv4SpineG1PinsExprTest {

    private val prelude = """
        interface Rec { known: number; }
        declare const o: Rec;
    """.trimIndent()

    // ── recursion arms ──────────────────────────────────────────────────────

    @Test
    fun `binary left-spine chains check every operand`() {
        val d = diagnose(prelude + """
            const r = o.m1 + o.m2 + o.m3;
        """)
        assert(d.count { it.code == 2339 } == 3)
    }

    @Test
    fun `conditional expressions check all three positions`() {
        val d = diagnose(prelude + """
            const r = o.m1 ? o.m2 : o.m3;
        """)
        assert(d.count { it.code == 2339 } == 3)
    }

    @Test
    fun `wrapper operand arms are reached`() {
        // paren / as / non-null / prefix / postfix-receiver / typeof / void /
        // delete-receiver / await / spread / satisfies positions.
        val d = diagnose(prelude + """
            const a = (o.m1);
            const b = o.m2 as any;
            const c = o.m3!;
            const e = !o.m4;
            const f = typeof o.m5;
            const g = void o.m6;
            async function h() { return await o.m7; }
            const arr = [...(o.m8 as any as unknown[])];
        """)
        assert(d.count { it.code == 2339 } == 8)
    }

    @Test
    fun `call and new expressions check callee receivers and arguments`() {
        val d = diagnose(prelude + """
            declare function fn(x: unknown): void;
            declare const C: new (x: unknown) => object;
            fn(o.m1);
            new C(o.m2);
        """)
        assert(d.count { it.code == 2339 } == 2)
    }

    @Test
    fun `element access checks receiver and argument expressions`() {
        val d = diagnose(prelude + """
            declare const m: { [k: string]: number };
            const r = m[o.m1 as any as string] + (o.m2 as any as number[])[0];
        """)
        assert(d.count { it.code == 2339 } == 2)
    }

    @Test
    fun `array literals object literal values and template spans are reached`() {
        val d = diagnose(prelude + """
            const a = [o.m1];
            const b = { p: o.m2 };
            const t = `x${'$'}{o.m3}`;
        """)
        assert(d.count { it.code == 2339 } == 3)
    }

    @Test
    fun `yield operands are reached`() {
        val d = diagnose(prelude + """
            function* g() { yield o.m1; }
        """)
        assert(d.count { it.code == 2339 } == 1)
    }

    // ── reach quirks (bug-compat) ───────────────────────────────────────────

    @Test
    fun `tagged template spans are UNREACHED - only the tag is walked`() {
        // The TaggedTemplateExpression arm walks expr.tag only; a property
        // access inside the template's ${} spans is silently unchecked by
        // this pass (the untagged template twin above fires).
        diagnose(prelude + """
            declare function tag(strings: TemplateStringsArray, ...values: unknown[]): string;
            const t = tag`x${'$'}{o.missing}`;
        """) should {
            have(none { it.code == 2339 })
        }
    }

    // ── function-like scope discipline ──────────────────────────────────────

    @Test
    fun `arrow params are contextually typed from a single-signature callee`() {
        val d = diagnose(prelude + """
            declare function cb(f: (r: Rec) => void): void;
            cb(r => { r.missing; });
        """)
        d should { have(any { it.code == 2339 && "'Rec'" in it.message }) }
    }

    @Test
    fun `function-expression unannotated params shadow outer bindings to any`() {
        // The FunctionExpression arm REMOVES an un-annotated param's name
        // from currentLocalTypes — the inner `s` must not type from the
        // outer `s: Rec` (so no TS2339); the annotated twin still fires.
        // the outer binding must be a FUNCTION-local (currentLocalTypes-
        // tracked) — file-level declares resolve through a different path.
        val d1 = diagnose(prelude + """
            function outer() {
                const s: Rec = { known: 1 };
                const f = function (s) { return (s as any) && s.missing; };
                return f;
            }
        """, directives = "// @strict: false")
        assert(d1.count { it.code == 2339 } == 0)
        val d2 = diagnose(prelude + """
            const f = function (s: Rec) { return s.missing; };
        """)
        assert(d2.count { it.code == 2339 } == 1)
    }

    @Test
    fun `object literal member context reaches nested arrow params`() {
        val d = diagnose(prelude + """
            declare function on(handlers: { click: (r: Rec) => void }): void;
            on({ click: r => { r.missing; } });
        """)
        d should { have(any { it.code == 2339 && "'Rec'" in it.message }) }
    }

    @Test
    fun `class expression methods check this against the anonymous class type`() {
        val d = diagnose("""
            const c = class {
                known: number = 1;
                m() { this.missing; }
            };
        """)
        d should { have(any { it.code == 2339 && "(Anonymous class)" in it.message }) }
    }

    @Test
    fun `negative control - a function expression in a class method loses the class this`() {
        // The FunctionExpression arm walks its body with enclosingClassType
        // = null (this rebinds), mirroring the FunctionDeclaration quirk.
        diagnose("""
            class K {
                known: number = 1;
                m() {
                    const f = function () { return (this as any as K) && this.missing; };
                    return f;
                }
            }
        """) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `arrow bodies KEEP the enclosing class this`() {
        // Unlike fn-exprs, the ArrowFunction arm passes enclosingClassType
        // through — `this.x` in an arrow inside a method checks the class.
        val d = diagnose("""
            class K {
                known: number = 1;
                m() {
                    const f = () => this.missing;
                    return f;
                }
            }
        """)
        assert(d.count { it.code == 2339 } == 1)
    }
}
