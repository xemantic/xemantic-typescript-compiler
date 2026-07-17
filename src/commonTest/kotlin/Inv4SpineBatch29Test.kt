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
 * INV.4(d) walker 9 (round 538): the const-assignment pass
 * checkConstAssignment (TS2588/TS2628/TS2629/TS2630/TS2708 + the TS2540
 * readonly-write checks, TS2357 inc/dec targets, and the scanRegExpFull
 * regex family riding the same walker) migrated onto the check spine.
 *
 * All pins verified against the OLD (slot-moved) walker first — a pure
 * reach/state-preserving migration. The sharpest bug-compat pins: the
 * per-list constNames map is STATEMENT-ORDERED (an assignment BEFORE the
 * const in the same list is not flagged) with ASYMMETRIC spawn rules —
 * FunctionDeclaration / function-expression / arrow-BLOCK / IIFE-arrow-BLOCK
 * bodies get a FRESH EMPTY map (an outer const reassigned inside is NOT
 * flagged), while Block / switch-clause / try / ModuleBlock / class-MEMBER
 * bodies inherit a COPY (it IS flagged); an arrow EXPRESSION body shares
 * the live map (flagged). The B510 class-this context: an own readonly
 * data write is legal ONLY ctor-direct, IIFE-arrows are TRANSPARENT to
 * ctor-directness, non-invoked arrows are not, and function expressions
 * null the class entirely.
 */
class Inv4SpineBatch29Test {

    // ── core emissions ──────────────────────────────────────────────────────

    @Test
    fun `assignments to const fire TS2588 in all operator forms`() {
        val d = diagnose("""
            function f() {
                const x = 1;
                x = 2;
                x += 1;
                x++;
                --x;
            }
        """)
        kotlin.test.assertEquals(4, d.count { it.code == 2588 }, "expected 4 TS2588, got: $d")
    }

    @Test
    fun `class enum function and namespace reassignments fire their codes`() {
        val d = diagnose("""
            class K {}
            enum E { A }
            function fn() {}
            namespace NS { export const v = 1; }
            K = null as any;
            E = null as any;
            fn = null as any;
            NS = null as any;
        """)
        d should { have(any { it.code == 2629 }) }
        d should { have(any { it.code == 2628 }) }
        d should { have(any { it.code == 2630 }) }
        d should { have(any { it.code == 2708 }) }
    }

    @Test
    fun `readonly interface property write through a typed param fires TS2540`() {
        val d = diagnose("""
            interface B { readonly c: boolean; }
            function f(x: B) {
                x.c = true;
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2540 }, "expected 1 TS2540, got: $d")
    }

    @Test
    fun `invalid increment target fires TS2357`() {
        val d = diagnose("""
            declare let a: number;
            declare let b: number;
            (a + b)++;
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2357 }, "expected 1 TS2357, got: $d")
    }

    @Test
    fun `braced unicode regex escape without u flag fires TS1538`() {
        val d = diagnose("""
            const r = /\u{41}/;
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 1538 }, "expected 1 TS1538, got: $d")
    }

    // ── statement-ordered collection ────────────────────────────────────────

    @Test
    fun `negative control - an assignment BEFORE the const in the same list is not flagged`() {
        diagnose("""
            function f() {
                x = 2;
                const x = 1;
            }
        """) should {
            have(none { it.code == 2588 })
        }
    }

    @Test
    fun `negative control - an inner let shadow removes the inherited const`() {
        diagnose("""
            declare const cond: boolean;
            function f() {
                const c = 1;
                if (cond) {
                    let c = 2;
                    c = 3;
                }
            }
        """) should {
            have(none { it.code == 2588 })
        }
    }

    // ── asymmetric spawn rules ──────────────────────────────────────────────

    @Test
    fun `negative control - a function body does NOT inherit outer consts`() {
        diagnose("""
            const c = 1;
            function f() {
                c = 2;
            }
            const g = function () {
                c = 3;
            };
            const h = () => {
                c = 4;
            };
        """) should {
            have(none { it.code == 2588 })
        }
    }

    @Test
    fun `class method bodies DO inherit outer consts`() {
        val d = diagnose("""
            const c = 1;
            class K {
                m() {
                    c = 2;
                }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2588 }, "expected 1 TS2588, got: $d")
    }

    @Test
    fun `blocks clauses try and namespace bodies inherit copies`() {
        val d = diagnose("""
            declare const n: number;
            const c = 1;
            {
                c = 2;
            }
            switch (n) {
                default:
                    c = 3;
            }
            try {
                c = 4;
            } catch (e) {
                c = 5;
            }
            namespace NS {
                c = 6;
            }
        """)
        kotlin.test.assertEquals(5, d.count { it.code == 2588 }, "expected 5 TS2588, got: $d")
    }

    @Test
    fun `arrow expression body shares the live map while an IIFE arrow block does not`() {
        val d1 = diagnose("""
            const c = 1;
            const g = () => c = 2;
        """)
        kotlin.test.assertEquals(1, d1.count { it.code == 2588 }, "expression body: expected 1 TS2588, got: $d1")
        val d2 = diagnose("""
            const c = 1;
            (() => {
                c = 2;
            })();
        """)
        kotlin.test.assertEquals(0, d2.count { it.code == 2588 }, "IIFE block body: expected 0 TS2588, got: $d2")
    }

    // ── for-header overlay ──────────────────────────────────────────────────

    @Test
    fun `for-header const is flagged in incrementor and body but the init expr sees the outer map`() {
        val d = diagnose("""
            function f() {
                for (const i = 0; i < 3; i++) {
                    i = 5;
                }
            }
        """)
        kotlin.test.assertEquals(2, d.count { it.code == 2588 }, "expected 2 TS2588 (incrementor + body), got: $d")
        val d2 = diagnose("""
            function f() {
                const j = 1;
                for (j = 2; ;) { break; }
            }
        """)
        kotlin.test.assertEquals(1, d2.count { it.code == 2588 }, "init expr vs outer const: expected 1 TS2588, got: $d2")
    }

    // ── B510 class-this readonly context ────────────────────────────────────

    @Test
    fun `own readonly data write is legal ctor-direct but TS2540 in a method`() {
        val d = diagnose("""
            class C {
                readonly p: number = 1;
                constructor() {
                    this.p = 2;
                }
                m() {
                    this.p = 3;
                }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2540 }, "expected 1 TS2540 (the method write only), got: $d")
    }

    @Test
    fun `IIFE arrows are transparent to ctor-directness but non-invoked arrows are not`() {
        val d = diagnose("""
            class C {
                readonly p: number = 1;
                constructor() {
                    (() => {
                        this.p = 5;
                    })();
                    const g = () => {
                        this.p = 6;
                    };
                }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2540 }, "expected 1 TS2540 (the captured arrow only), got: $d")
    }

    @Test
    fun `negative control - a function expression rebinds this away from the class`() {
        diagnose("""
            class C {
                readonly p: number = 1;
                m() {
                    const f = function () {
                        this.p = 4;
                    };
                }
            }
        """) should {
            have(none { it.code == 2540 })
        }
    }

    @Test
    fun `get-only accessor write fires TS2540 even in the constructor`() {
        val d = diagnose("""
            class C {
                get g(): number { return 1; }
                constructor() {
                    this.g = 2;
                }
            }
        """)
        kotlin.test.assertEquals(1, d.count { it.code == 2540 }, "expected 1 TS2540, got: $d")
    }

    @Test
    fun `negative control - static member bodies have no class-this context`() {
        diagnose("""
            class C {
                readonly p: number = 1;
                static m() {
                    this.p = 2;
                }
            }
        """) should {
            have(none { it.code == 2540 })
        }
    }

    // ── cross-file script consts ────────────────────────────────────────────

    @Test
    fun `a script-file const is flagged from another script file`() {
        val d = diagnose(
            """
            // @filename: a.ts
            const sharedC = 1;
            // @filename: b.ts
            sharedC = 2;
            """,
        )
        kotlin.test.assertEquals(1, d.count { it.code == 2588 }, "expected 1 TS2588, got: $d")
    }

    @Test
    fun `negative control - module files do not share script consts`() {
        diagnose(
            """
            // @filename: a.ts
            const sharedC = 1;
            // @filename: b.ts
            export {};
            sharedC = 2;
            """,
        ) should {
            have(none { it.code == 2588 })
        }
    }
}
