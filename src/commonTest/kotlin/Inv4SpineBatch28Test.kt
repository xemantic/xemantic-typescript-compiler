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
 * INV.4(d) walker 8 (round 537): the implicit-returns pass
 * checkImplicitReturns (TS7030/TS2355/TS2366/TS2378/TS7023 + the arrow
 * concise-body TS2322) migrated onto the check spine — the per-file driver
 * and the recursion walkers (walkForImplicitReturns /
 * walkStmtForImplicitReturns / walkExprForImplicitReturns) are DELETED;
 * reach becomes a memoized 4-state ancestor classifier, and the five anchor
 * functions (check{Function,Method,GetAccessor,FuncExpr,Arrow}ForImplicitReturn,
 * minus their trailing body recursion) dispatch at the fn-like/member
 * enters, each wrapped in a per-dispatch ambient install
 * (implicitReturnFlowGraph + currentCheckFileName + the PRE-SPINE resting
 * currentFileLocals/currentFunctionParams).
 *
 * All pins verified against the OLD (slot-moved) walker first — a pure
 * reach-preserving migration. The sharpest bug-compat pins: GENERATOR
 * bodies never descend (the anchors early-return before their trailing
 * recursion); class-DECLARATION Constructor/SetAccessor bodies and
 * PropertyDeclaration initializers are unreached while class-EXPRESSION
 * property initializers ARE reached; object-literal SetAccessor bodies are
 * unreached; arrow CONCISE (expression) bodies never descend; and
 * return/throw expressions, if/while conditions, and for headers are
 * unreached in statement position.
 */
class Inv4SpineBatch28Test {

    // ── core emissions ──────────────────────────────────────────────────────

    @Test
    fun `function lacking ending return with non-nullable return type fires TS2366`() {
        val d = diagnose("""
            declare const c: boolean;
            function f(): number {
                if (c) { return 1; }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2366 }, "expected 1 TS2366, got: $d")
    }

    @Test
    fun `function with explicit return type and no returns fires TS2355`() {
        val d = diagnose("""
            function f(): number {
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2355 }, "expected 1 TS2355, got: $d")
    }

    @Test
    fun `un-annotated mixed-path function fires TS7030 under noImplicitReturns`() {
        val d = diagnose("""
            declare const c: boolean;
            function f() {
                if (c) { return 1; }
            }
        """, directives = "// @noImplicitReturns: true")
        kotlin.test.assertEquals(1, d.count { it.code == 7030 }, "expected 1 TS7030, got: $d")
    }

    @Test
    fun `getter without any return fires TS2378`() {
        val d = diagnose("""
            class C {
                get a(): number { }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2378 }, "expected 1 TS2378, got: $d")
    }

    @Test
    fun `own-member getter cycle fires TS7023`() {
        val d = diagnose("""
            class C {
                get x() { return this.x; }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 7023 }, "expected 1 TS7023, got: $d")
    }

    @Test
    fun `indirect self-reference in return fires TS7023`() {
        val d = diagnose("""
            function fib() {
                return [fib][0];
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 7023 }, "expected 1 TS7023, got: $d")
    }

    @Test
    fun `arrow concise body against declared return type fires TS2322`() {
        val d = diagnose("""
            const f = (): number => 'foo';
        """)
        d should { have(any { it.code == 2322 }) }
    }

    // ── anchor contexts ─────────────────────────────────────────────────────

    @Test
    fun `emissions fire in every reached anchor context`() {
        val d = diagnose("""
            declare const c: boolean;
            function outer() {
                function nested(): number { }
            }
            class K {
                m(): number { }
                get g(): number { }
            }
            const ce = class {
                m(): number { }
            };
            const o = {
                m(): number { },
                get g(): number { },
            };
            const fe = function (): number { };
            const ar = (): number => { };
            namespace NS {
                export function nf(): number { }
            }
            call((): number => { });
            declare function call(x: any): void;
        """)
        // nested, K.m, ce.m, o.m, fe, ar, NS.nf, call-arg arrow → TS2355; the
        // two getters (K.g, o.g) fire BOTH TS2378 and TS2355 → 10 + 2.
        kotlin.test.assertEquals(10, d.count { it.code == 2355 }, "expected 10 TS2355, got: ${d.filter { it.code == 2355 }}")
        kotlin.test.assertEquals(2, d.count { it.code == 2378 }, "expected 2 TS2378, got: ${d.filter { it.code == 2378 }}")
    }

    @Test
    fun `class-EXPRESSION property initializer arrows are reached`() {
        val d = diagnose("""
            const ce = class {
                p = (): number => { };
            };
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2355 }, "expected 1 TS2355, got: $d")
    }

    // ── reach quirks (negative controls, legacy walker geometry) ────────────

    @Test
    fun `negative control - generator bodies never descend`() {
        diagnose("""
            function* g() {
                const inner = function (): number { };
            }
        """) should {
            have(none { it.code == 2355 })
        }
    }

    @Test
    fun `negative control - class-declaration constructor bodies are unreached`() {
        diagnose("""
            class C {
                constructor() {
                    const inner = function (): number { };
                }
            }
        """) should {
            have(none { it.code == 2355 })
        }
    }

    @Test
    fun `negative control - setter bodies are unreached in class and object literal`() {
        diagnose("""
            class C {
                set s(v: number) {
                    const inner = function (): number { };
                }
            }
            const o = {
                set s(v: number) {
                    const inner2 = function (): number { };
                },
            };
        """) should {
            have(none { it.code == 2355 })
        }
    }

    @Test
    fun `negative control - class-DECLARATION property initializers are unreached`() {
        diagnose("""
            class C {
                p = (): number => { };
            }
        """) should {
            have(none { it.code == 2355 })
        }
    }

    @Test
    fun `negative control - arrow concise bodies never descend`() {
        diagnose("""
            const f = () => (function (): number { });
            const g = (): any => (function (): number { });
        """) should {
            have(none { it.code == 2355 })
        }
    }

    @Test
    fun `negative control - return statement expressions are unreached`() {
        diagnose("""
            function outer() {
                return function (): number { };
            }
        """) should {
            have(none { it.code == 2355 })
        }
    }

    @Test
    fun `negative control - throw expressions and conditions are unreached`() {
        diagnose("""
            declare const c: boolean;
            function outer() {
                if ((function (): number { })) { }
                while ((function (): number { })) { break; }
            }
            function thrower() {
                throw (function (): number { });
            }
        """) should {
            have(none { it.code == 2355 })
        }
    }

    @Test
    fun `negative control - for-header initializers are unreached`() {
        diagnose("""
            function outer() {
                for (const f = (): number => { }; ;) { break; }
            }
        """) should {
            have(none { it.code == 2355 })
        }
    }

    @Test
    fun `loop and clause bodies are reached`() {
        val d = diagnose("""
            declare const c: boolean;
            declare const n: number;
            function outer() {
                while (c) {
                    const a = function (): number { };
                    break;
                }
                switch (n) {
                    default:
                        const b = function (): number { };
                }
                try {
                    const t = function (): number { };
                } catch (e) {
                    const t2 = function (): number { };
                }
            }
        """)
        kotlin.test.assertEquals(4, d.count { it.code == 2355 }, "expected 4 TS2355, got: $d")
    }

    @Test
    fun `async arrow with value returns fires TS7030 under noImplicitReturns`() {
        val d = diagnose("""
            declare const c: boolean;
            const f = async () => {
                if (c) { return 1; }
            };
        """, directives = "// @noImplicitReturns: true\n// @strict: true")
        d should { have(any { it.code == 7030 }) }
    }
}
