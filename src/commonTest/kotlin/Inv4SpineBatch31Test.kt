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
 * INV.4(d) walker 11 (round 540): the null/undefined-literal usage pass
 * checkNullUndefinedUsage (TS18050 + the for-of empty-`[]` TS2488 shape)
 * migrated onto the check spine — the recursion walkers
 * (checkNullUndefinedInStatements/-InStatement/-InExpr) are deleted; reach
 * is a memoized DEPTH-carrying classifier (the legacy checkDepth ≤ 200
 * statement-frame cap reproduced: depth = ancestor STATEMENT count on the
 * reached path), and the anchors dispatch at
 * BinaryExpression/PropertyAccess/ElementAccess/ForOfStatement enters.
 *
 * All pins verified against the OLD (slot-moved) walker first. The sharpest
 * bug-compat pins: `new` expressions, `as`/angle-bracket casts,
 * await/void/typeof/delete operands, tagged templates, class EXPRESSIONS,
 * and object-literal methods/accessors are never descended — a TS18050
 * shape inside any of them is silent.
 */
class Inv4SpineBatch31Test {

    // ── core emissions ──────────────────────────────────────────────────────

    @Test
    fun `null and undefined literals in arithmetic fire TS18050 under strict`() {
        val d = diagnose("""
            const a = null + 1;
            const b = 1 * undefined;
        """)
        kotlin.test.assertEquals(2, d.count { it.code == 18050 }, "expected 2 TS18050, got: ${d.filter { it.code == 18050 }}")
    }

    @Test
    fun `null property access and element access fire TS18050`() {
        val d = diagnose("""
            const a = null.foo;
            const b = undefined[0];
        """)
        kotlin.test.assertEquals(2, d.count { it.code == 18050 }, "expected 2 TS18050, got: $d")
    }

    @Test
    fun `null and undefined operands of the in operator fire TS18050 in all modes`() {
        val d = diagnose("""
            declare const o: object;
            const a = null in o;
            const b = "" in null;
        """, directives = "// @strict: false")
        kotlin.test.assertEquals(2, d.count { it.code == 18050 }, "expected 2 TS18050, got: $d")
    }

    @Test
    fun `for-of over a bare null iterable fires TS18050`() {
        val d = diagnose("""
            function f() {
                for (const q of null) { }
            }
        """)
        d should { have(any { it.code == 18050 }) }
    }

    @Test
    fun `emissions fire in nested statement and expression contexts`() {
        val d = diagnose("""
            declare const c: boolean;
            function f() {
                if (c) { const a = null.x; }
                while (c) { const b = undefined.y; break; }
                const t = `v${'$'}{null.z}`;
            }
            class K {
                m() { const a = null.k; }
                get g() { const a = null.g2; return 1; }
            }
            const arrow = () => null.a2;
            const fe = function () { return undefined.f2; };
        """)
        kotlin.test.assertEquals(7, d.count { it.code == 18050 }, "expected 7 TS18050, got: ${d.filter { it.code == 18050 }}")
    }

    @Test
    fun `switch subject and case expressions are walked`() {
        val d = diagnose("""
            declare const n: any;
            function f() {
                switch (null.s) {
                    case null.c: break;
                }
            }
        """)
        kotlin.test.assertEquals(2, d.count { it.code == 18050 }, "expected 2 TS18050, got: $d")
    }

    @Test
    fun `for headers are walked`() {
        val d = diagnose("""
            function f() {
                for (let i = 0; null.c; i++) { break; }
            }
        """)
        d should { have(any { it.code == 18050 }) }
    }

    // ── reach quirks (negative controls) ────────────────────────────────────

    @Test
    fun `negative control - new-expression callee and arguments are unreached`() {
        diagnose("""
            declare const C: any;
            const x = new C(null.a);
        """) should {
            have(none { it.code == 18050 })
        }
    }

    @Test
    fun `negative control - as-casts and nonnull operands are unreached`() {
        diagnose("""
            const a = (null.x as any);
            const b = (undefined.y)!;
        """) should {
            have(none { it.code == 18050 })
        }
    }

    @Test
    fun `negative control - await void typeof delete spread operands are unreached`() {
        diagnose("""
            async function f() {
                await null.a;
                void null.b;
                typeof null.c;
                delete null.d;
                const arr = [1, ...null.e];
            }
        """) should {
            have(none { it.code == 18050 })
        }
    }

    @Test
    fun `negative control - class expressions and object-literal methods are unreached`() {
        diagnose("""
            const ce = class {
                m() { return null.a; }
                p = null.b;
            };
            const o = {
                m() { return null.c; },
                get g() { return null.d; },
            };
        """) should {
            have(none { it.code == 18050 })
        }
    }

    @Test
    fun `object-literal property values ARE reached`() {
        val d = diagnose("""
            const o = { p: null.a, ...undefined.b };
        """)
        kotlin.test.assertEquals(2, d.count { it.code == 18050 }, "expected 2 TS18050, got: $d")
    }
}
