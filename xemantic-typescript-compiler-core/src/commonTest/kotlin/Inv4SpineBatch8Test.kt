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
 * INV.4(b) batch 8 (round 518): the parameter-initializer walker family
 * migrated onto the check spine — TS1015 question-mark-with-initializer (from
 * the deleted `checkOptionalParamWithInitializer` walk family), TS2463
 * optional binding-pattern parameter (from the deleted
 * `checkOptionalBindingPatternParams` walk family), the
 * TS2523/TS2524/TS2372/TS2502/TS18048 parameter-initializer-content family
 * (from the deleted `checkParamInitializerForbidden` walk family), TS2371
 * initializer-in-non-implementation (from the deleted
 * `checkParameterInitializerInNonImpl` walk family), and TS1052/TS1053
 * set-accessor parameter grammar (from the deleted
 * `checkSetAccessorInitializer` / `checkSetAccessorRestParameter` walks).
 */
class Inv4SpineBatch8Test {

    // ── TS1015: parameter cannot have question mark and initializer ─────────

    @Test
    fun `typed optional param with initializer fires TS1015`() {
        diagnose(
            """
            function f(a?: number = 1) {}
            """,
        ) should {
            have(any { it.code == 1015 })
        }
    }

    @Test
    fun `negative control - untyped optional param with initializer in a declaration stays silent`() {
        // The old walker's requireType gate: in function/method/constructor
        // DECLARATIONS TS1015 fires only with a type annotation or a parameter
        // property modifier — preserved by the migration.
        diagnose(
            """
            function f(a? = 1) {}
            """,
        ) should {
            have(none { it.code == 1015 })
        }
    }

    @Test
    fun `untyped optional param with initializer in an arrow fires TS1015`() {
        diagnose(
            """
            const f = (a? = 1) => a;
            """,
        ) should {
            have(any { it.code == 1015 })
        }
    }

    @Test
    fun `parameter property with question mark and initializer fires TS1015`() {
        diagnose(
            """
            class C {
                constructor(public a? = 1) {}
            }
            """,
        ) should {
            have(any { it.code == 1015 })
        }
    }

    @Test
    fun `object-literal method optional param with initializer fires TS1015`() {
        diagnose(
            """
            const o = {
                m(a?: number = 1) { return a; },
            };
            """,
        ) should {
            have(any { it.code == 1015 })
        }
    }

    @Test
    fun `widening - arrow in a class property initializer fires TS1015`() {
        // The old walk's ClassDeclaration branch never descended property
        // initializers; the spine visits every Parameter (position-independent
        // tsc grammar rule — checkGrammarParameterList).
        diagnose(
            """
            class C {
                p = (a?: number = 1) => a;
            }
            """,
        ) should {
            have(any { it.code == 1015 })
        }
    }

    @Test
    fun `negative control - optional param without initializer is fine`() {
        diagnose(
            """
            function f(a?: number) {}
            const g = (b?: string) => b;
            """,
        ) should {
            have(none { it.code == 1015 })
        }
    }

    // ── TS2463: binding-pattern parameter cannot be optional in an impl ─────

    @Test
    fun `optional binding-pattern param in an implementation fires TS2463`() {
        diagnose(
            """
            function f([x]?: [number]) {}
            """,
        ) should {
            have(any { it.code == 2463 })
        }
    }

    @Test
    fun `negative control - optional binding-pattern param in an overload signature is fine`() {
        diagnose(
            """
            declare function h([x]?: [number]): void;
            """,
        ) should {
            have(none { it.code == 2463 })
        }
    }

    @Test
    fun `optional binding-pattern param in an arrow fires TS2463`() {
        diagnose(
            """
            const k = ([x]?: [number]) => 0;
            """,
        ) should {
            have(any { it.code == 2463 })
        }
    }

    @Test
    fun `widening - arrow nested in a parameter default fires TS2463`() {
        // The old walk never descended parameter DEFAULT VALUES; the spine
        // visits the nested arrow's Parameter directly.
        diagnose(
            """
            function f(cb = ([x]?: [number]) => 0) {}
            """,
        ) should {
            have(any { it.code == 2463 })
        }
    }

    // ── TS2523/TS2524/TS2372/TS2502/TS18048: initializer content family ─────

    @Test
    fun `await in an async function's parameter initializer fires TS2524`() {
        diagnose(
            """
            declare const p: Promise<number>;
            async function f(a = await p) {}
            """,
        ) should {
            have(any { it.code == 2524 })
        }
    }

    @Test
    fun `parameter referencing itself in its initializer fires TS2372`() {
        diagnose(
            """
            function f(x = x) {}
            """,
        ) should {
            have(any { it.code == 2372 })
        }
    }

    @Test
    fun `self-reference with undefined-including type fires TS2502 and TS18048`() {
        diagnose(
            """
            function f(x: number | undefined = x + 1) {}
            """,
        ) should {
            have(any { it.code == 2502 })
            have(any { it.code == 18048 })
            have(any { it.code == 2372 })
        }
    }

    @Test
    fun `negative control - self-reference inside a nested closure is fine`() {
        diagnose(
            """
            function f(x = () => x) {}
            """,
        ) should {
            have(none { it.code == 2372 })
        }
    }

    @Test
    fun `widening - arrow in a class property initializer fires TS2372`() {
        // The old class-member walk had no PropertyDeclaration case, so
        // function params nested in property initializers were never checked.
        diagnose(
            """
            class C {
                p = (x = x) => x;
            }
            """,
        ) should {
            have(any { it.code == 2372 })
        }
    }

    @Test
    fun `await in a binding-element default fires TS2524`() {
        diagnose(
            """
            declare const p: Promise<number>;
            async function f({ a = await p }: any) {}
            """,
        ) should {
            have(any { it.code == 2524 })
        }
    }

    // ── TS2371: initializer only allowed in an implementation ───────────────

    @Test
    fun `initializer on an ambient function's param fires TS2371`() {
        diagnose(
            """
            declare function f(a = 1): void;
            """,
        ) should {
            have(any { it.code == 2371 })
        }
    }

    @Test
    fun `initializer on an interface method's param fires TS2371`() {
        diagnose(
            """
            interface I {
                m(a = 1): void;
            }
            """,
        ) should {
            have(any { it.code == 2371 })
        }
    }

    @Test
    fun `initializer in a function-type annotation fires TS2371`() {
        diagnose(
            """
            var f: (a = 3) => number;
            """,
        ) should {
            have(any { it.code == 2371 })
        }
    }

    @Test
    fun `initializer on a bodyless class-method overload fires TS2371`() {
        diagnose(
            """
            class C {
                m(a = 1): void;
                m(a?: number): void {}
            }
            """,
        ) should {
            have(any { it.code == 2371 })
        }
    }

    @Test
    fun `negative control - initializer in an implementation is fine`() {
        diagnose(
            """
            function f(a = 1) {}
            class C {
                m(b = 2) {}
            }
            """,
        ) should {
            have(none { it.code == 2371 })
        }
    }

    @Test
    fun `widening - function type in a parameter annotation fires TS2371`() {
        // The old walk reached function TYPES only under variable annotations,
        // type aliases, and casts; tsc's rule is per containing signature
        // (checkParameter: initializer + missing body), so the spine widens
        // to every FunctionType position.
        diagnose(
            """
            function g(cb: (a = 3) => void) {}
            """,
        ) should {
            have(any { it.code == 2371 })
        }
    }

    @Test
    fun `binding-element default in an ambient function fires TS2371`() {
        diagnose(
            """
            declare function f({ a = 1 }: { a?: number }): void;
            """,
        ) should {
            have(any { it.code == 2371 })
        }
    }

    // ── TS1052/TS1053: set-accessor parameter grammar ───────────────────────

    @Test
    fun `setter parameter initializer fires TS1052`() {
        diagnose(
            """
            class C {
                set x(v = 1) {}
            }
            """,
        ) should {
            have(any { it.code == 1052 })
        }
    }

    @Test
    fun `setter rest parameter fires TS1053`() {
        diagnose(
            """
            class C {
                set x(...v: number[]) {}
            }
            """,
        ) should {
            have(any { it.code == 1053 })
        }
    }

    @Test
    fun `negative control - plain setter is fine`() {
        diagnose(
            """
            class C {
                set x(v: number) {}
            }
            """,
        ) should {
            have(none { it.code == 1052 || it.code == 1053 })
        }
    }

    @Test
    fun `widening - object-literal setter initializer fires TS1052`() {
        // The old walks reached class-DECLARATION members only; tsc's
        // checkGrammarAccessor runs for object-literal accessors too.
        diagnose(
            """
            const o = {
                set x(v = 1) {},
            };
            """,
        ) should {
            have(any { it.code == 1052 })
        }
    }

    @Test
    fun `widening - class-expression setter rest parameter fires TS1053`() {
        diagnose(
            """
            const C = class {
                set x(...v: any[]) {}
            };
            """,
        ) should {
            have(any { it.code == 1053 })
        }
    }
}
