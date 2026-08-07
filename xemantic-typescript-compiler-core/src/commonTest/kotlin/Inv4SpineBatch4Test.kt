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
 * INV.4(b) batch 4 (round 516): four passes migrated onto the check spine —
 * the accessor-grammar family (TS1054/TS1049/TS1095 + the TS2808 accessor-pair
 * visibility rule, from the deleted `checkSetterParameterCount` walk family),
 * TS1014 rest-parameter-must-be-last (from the deleted
 * `checkRestParameterLast` family), TS1113 duplicate switch defaults (from the
 * deleted `checkMultipleDefaults` family), and TS1246 interface property
 * initializers (from the deleted `checkInterfacePropertyInitializers` walk;
 * the parser owns the common `= init` shape).
 */
class Inv4SpineBatch4Test {

    // ── TS1049: a set accessor must have exactly one parameter ──────────────

    @Test
    fun `class setter with two parameters fires TS1049`() {
        diagnose(
            """
            class C {
                set p(a: number, b: number) {}
            }
            """,
        ) should {
            have(any { it.code == 1049 })
        }
    }

    @Test
    fun `object literal setter with zero parameters fires TS1049`() {
        diagnose(
            """
            const o = {
                set p() {}
            };
            """,
        ) should {
            have(any { it.code == 1049 })
        }
    }

    @Test
    fun `negative control - setter with exactly one parameter is fine`() {
        diagnose(
            """
            class C {
                set p(v: number) {}
            }
            const o = { set q(v: number) {} };
            """,
        ) should {
            have(none { it.code == 1049 })
        }
    }

    // ── TS1054: a get accessor cannot have parameters ───────────────────────

    @Test
    fun `class getter with a parameter fires TS1054`() {
        diagnose(
            """
            class C {
                get p(x: number) { return 1; }
            }
            """,
        ) should {
            have(any { it.code == 1054 })
        }
    }

    @Test
    fun `widening - class EXPRESSION getter with a parameter fires TS1054`() {
        // The old walker checked class-declaration members and object-literal
        // properties but never class-expression getters; TS1054 is a
        // position-independent tsc grammar rule — faithful widening.
        diagnose(
            """
            const c = class {
                get p(x: number) { return 1; }
            };
            """,
        ) should {
            have(any { it.code == 1054 })
        }
    }

    @Test
    fun `negative control - parameterless getter is fine`() {
        diagnose(
            """
            class C {
                get p() { return 1; }
            }
            """,
        ) should {
            have(none { it.code == 1054 })
        }
    }

    // ── TS1095: a set accessor cannot have a return type annotation ─────────

    @Test
    fun `class setter with return type annotation fires TS1095`() {
        diagnose(
            """
            class C {
                set p(v: number): void {}
            }
            """,
        ) should {
            have(any { it.code == 1095 })
        }
    }

    @Test
    fun `widening - class EXPRESSION setter with return annotation fires TS1095`() {
        // The old walker checked TS1095 for class-declaration members only;
        // tsc's checkGrammarAccessor fires it in class expressions too. (The
        // object-literal parse path never STORES a setter return annotation,
        // so the widening cannot reach objlit setters.)
        diagnose(
            """
            const c = class {
                set p(v: number): void {}
            };
            """,
        ) should {
            have(any { it.code == 1095 })
        }
    }

    @Test
    fun `negative control - unannotated setter is fine`() {
        diagnose(
            """
            class C {
                set p(v: number) {}
            }
            """,
        ) should {
            have(none { it.code == 1095 })
        }
    }

    // ── TS2808: get accessor at least as accessible as the setter ───────────

    @Test
    fun `private getter with public setter fires TS2808 on both names`() {
        val diags = diagnose(
            """
            class C {
                private get p(): number { return 1; }
                public set p(v: number) {}
            }
            """,
        )
        val count = diags.count { it.code == 2808 }
        diags should {
            have(count == 2)
        }
    }

    @Test
    fun `negative control - uniformly private accessor pair is fine`() {
        diagnose(
            """
            class C {
                private get p(): number { return 1; }
                private set p(v: number) {}
            }
            """,
        ) should {
            have(none { it.code == 2808 })
        }
    }

    @Test
    fun `negative control - class expression accessor pairs stay unchecked`() {
        // Deliberate non-widening: the old walker never checked class
        // expressions for TS2808 — the ClassDeclaration gate preserves that.
        diagnose(
            """
            const c = class {
                private get p(): number { return 1; }
                public set p(v: number) {}
            };
            """,
        ) should {
            have(none { it.code == 2808 })
        }
    }

    // ── TS1014: a rest parameter must be last ────────────────────────────────

    @Test
    fun `rest parameter before another parameter fires TS1014`() {
        diagnose(
            """
            function f(...rest: any[], last: number) {}
            """,
        ) should {
            have(any { it.code == 1014 })
        }
    }

    @Test
    fun `rest-not-last in an arrow inside a call argument fires TS1014`() {
        diagnose(
            """
            declare function run(cb: Function): void;
            run((...rest: any[], last: number) => {});
            """,
        ) should {
            have(any { it.code == 1014 })
        }
    }

    @Test
    fun `widening - rest-not-last in a function TYPE fires TS1014`() {
        // The old walk never descended into type positions; tsc's
        // checkGrammarParameterList runs for every signature declaration —
        // faithful widening.
        diagnose(
            """
            type F = (...rest: any[], last: number) => void;
            """,
        ) should {
            have(any { it.code == 1014 })
        }
    }

    @Test
    fun `negative control - trailing rest parameter is fine`() {
        diagnose(
            """
            function f(first: number, ...rest: any[]) {}
            const g = (a: string, ...rest: number[]) => {};
            """,
        ) should {
            have(none { it.code == 1014 })
        }
    }

    // ── TS1113: duplicate default clauses ────────────────────────────────────

    @Test
    fun `two default clauses fire TS1113 once`() {
        val diags = diagnose(
            """
            declare const n: number;
            switch (n) {
                default: break;
                default: break;
            }
            """,
        )
        val count = diags.count { it.code == 1113 }
        diags should {
            have(count == 1)
        }
    }

    @Test
    fun `three default clauses still fire TS1113 once`() {
        // The old walker's errorEmitted latch: one diagnostic per switch.
        val diags = diagnose(
            """
            declare const n: number;
            switch (n) {
                default: break;
                default: break;
                default: break;
            }
            """,
        )
        val count = diags.count { it.code == 1113 }
        diags should {
            have(count == 1)
        }
    }

    @Test
    fun `widening - duplicate defaults inside a parameter default fire TS1113`() {
        // The old statement walk never visited parameter INITIALIZERS.
        diagnose(
            """
            declare const n: number;
            function f(x = (() => {
                switch (n) {
                    default: return 1;
                    default: return 2;
                }
            })()) {}
            """,
        ) should {
            have(any { it.code == 1113 })
        }
    }

    @Test
    fun `negative control - single default clause is fine`() {
        diagnose(
            """
            declare const n: number;
            switch (n) {
                case 1: break;
                default: break;
            }
            """,
        ) should {
            have(none { it.code == 1113 })
        }
    }

    // ── TS1246: interface property initializers ──────────────────────────────

    @Test
    fun `interface property initializer fires TS1246`() {
        // The parser owns this shape (it consumes the initializer and reports
        // at its first char); pinned so the walker deletion cannot lose it.
        diagnose(
            """
            interface Foo {
                bar: number = 5;
            }
            """,
        ) should {
            have(any { it.code == 1246 })
        }
    }

    @Test
    fun `negative control - interface property without initializer is fine`() {
        diagnose(
            """
            interface Foo {
                bar: number;
            }
            """,
        ) should {
            have(none { it.code == 1246 })
        }
    }
}
