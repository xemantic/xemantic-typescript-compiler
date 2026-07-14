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
 * INV.4(b) batch 9 (round 518): four more passes migrated onto the check
 * spine — TS2404 for-in LHS type annotation (from the deleted
 * `checkForInLhsTypeAnnotation` walk family), TS1099 empty type-argument
 * lists on calls/new (from the deleted `checkEmptyTypeArguments` walk
 * family; the type-position TS1099 emitter elsewhere is untouched), TS2408
 * setter value-returns (from the deleted `checkSetterReturns` walk family;
 * `checkSetterBodyReturns` retained as the per-setter body scan), and the
 * TS1101/TS1300/TS2410 with-statement family (from the deleted
 * `checkWithStatements` walk family; the threaded isInWith/isInAsync flags
 * became one parent-chain walk).
 */
class Inv4SpineBatch9Test {

    // ── TS2404: for-in LHS cannot use a type annotation ─────────────────────

    @Test
    fun `for-in variable with type annotation fires TS2404`() {
        diagnose(
            """
            declare const o: any;
            for (var x: string in o) {}
            """,
        ) should {
            have(any { it.code == 2404 })
        }
    }

    @Test
    fun `negative control - plain for-in variable is fine`() {
        diagnose(
            """
            declare const o: any;
            for (var x in o) {}
            """,
        ) should {
            have(none { it.code == 2404 })
        }
    }

    @Test
    fun `widening - for-in with annotation inside an arrow body fires TS2404`() {
        // The old statement walk never descended arrow/function-expression
        // bodies; TS2404 is a position-independent tsc grammar rule.
        diagnose(
            """
            const f = (o: any) => {
                for (var x: number in o) {}
            };
            """,
        ) should {
            have(any { it.code == 2404 })
        }
    }

    // ── TS1099: type argument list cannot be empty ──────────────────────────

    @Test
    fun `empty type arguments on a call fire TS1099`() {
        diagnose(
            """
            declare function f<T>(): void;
            f<>();
            """,
        ) should {
            have(any { it.code == 1099 })
        }
    }

    @Test
    fun `empty type arguments on a new expression fire TS1099`() {
        diagnose(
            """
            declare class C<T> {}
            new C<>();
            """,
        ) should {
            have(any { it.code == 1099 })
        }
    }

    @Test
    fun `negative control - populated type arguments are fine`() {
        diagnose(
            """
            declare function f<T>(): void;
            f<number>();
            """,
        ) should {
            have(none { it.code == 1099 })
        }
    }

    @Test
    fun `widening - empty type arguments in a parameter default fire TS1099`() {
        // The old walk never descended parameter DEFAULT VALUES.
        diagnose(
            """
            declare function f<T>(): number;
            function g(a = f<>()) {}
            """,
        ) should {
            have(any { it.code == 1099 })
        }
    }

    // ── TS2408: setters cannot return a value ───────────────────────────────

    @Test
    fun `setter returning a value fires TS2408`() {
        diagnose(
            """
            class C {
                set x(v: number) { return 1; }
            }
            """,
        ) should {
            have(any { it.code == 2408 })
        }
    }

    @Test
    fun `object-literal setter returning a value fires TS2408`() {
        diagnose(
            """
            const o = {
                set x(v: number) { return 1; },
            };
            """,
        ) should {
            have(any { it.code == 2408 })
        }
    }

    @Test
    fun `negative control - bare return in a setter is fine`() {
        diagnose(
            """
            class C {
                set x(v: number) { if (v) { return; } }
            }
            """,
        ) should {
            have(none { it.code == 2408 })
        }
    }

    @Test
    fun `negative control - value return in a function nested in a setter is fine`() {
        diagnose(
            """
            class C {
                set x(v: number) {
                    function inner() { return 1; }
                    inner();
                }
            }
            """,
        ) should {
            have(none { it.code == 2408 })
        }
    }

    @Test
    fun `widening - setter in an object literal under an await operand fires TS2408`() {
        // The old expression walk had no AwaitExpression case, so setters
        // reachable only through an await operand were never checked.
        diagnose(
            """
            async function f() {
                const p = await { set x(v: number) { return 1; } };
            }
            """,
        ) should {
            have(any { it.code == 2408 })
        }
    }

    // ── TS1101/TS1300/TS2410: with statements ───────────────────────────────

    @Test
    fun `with statement fires TS1101 and TS2410`() {
        diagnose(
            """
            declare const o: any;
            with (o) {}
            """,
        ) should {
            have(any { it.code == 1101 })
            have(any { it.code == 2410 })
        }
    }

    @Test
    fun `with in an async function body also fires TS1300`() {
        diagnose(
            """
            async function f(o: any) {
                with (o) {}
            }
            """,
        ) should {
            have(any { it.code == 1300 })
            have(any { it.code == 1101 })
        }
    }

    @Test
    fun `nested with fires TS1101 twice but TS2410 only for the outermost`() {
        val diags = diagnose(
            """
            declare const o: any;
            with (o) with (o) {}
            """,
        )
        val ts1101 = diags.count { it.code == 1101 }
        val ts2410 = diags.count { it.code == 2410 }
        diags should {
            have(ts1101 == 2)
            have(ts2410 == 1)
        }
    }

    @Test
    fun `negative control - with under alwaysStrict false draws no TS1101 but still TS2410`() {
        diagnose(
            """
            declare const o: any;
            with (o) {}
            """,
            directives = "// @alwaysStrict: false",
        ) should {
            have(none { it.code == 1101 })
            have(any { it.code == 2410 })
        }
    }

    @Test
    fun `negative control - arrow body inside an async function does not fire TS1300`() {
        // The old walker reset isInAsync to FALSE at every arrow boundary
        // (tsc's AwaitContext would fire for async arrows — a signal-driven
        // widening candidate, preserved as-is by the migration).
        diagnose(
            """
            async function f(o: any) {
                const g = () => { with (o) {} };
            }
            """,
        ) should {
            have(none { it.code == 1300 })
        }
    }

    @Test
    fun `widening - with inside a class property initializer function fires TS1101`() {
        // The old class-member walk never descended property initializers.
        diagnose(
            """
            class C {
                p = function (o: any) { with (o) {} };
            }
            """,
        ) should {
            have(any { it.code == 1101 })
        }
    }
}
