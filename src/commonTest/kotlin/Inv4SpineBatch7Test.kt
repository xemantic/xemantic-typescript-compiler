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
 * INV.4(b) batch 7 (round 517): four passes migrated onto the check spine —
 * TS2566 rest-element property names (from the deleted
 * `checkRestElementPropertyNames` walk family; pure-syntax, widened
 * faithfully to every ObjectBindingPattern), TS1186/TS2493/TS2322
 * rest-binding-pattern elements (from the deleted
 * `checkRestBindingPatternElements` walk family; `checkRestBindingParam` +
 * emission helpers retained as the Parameter-enter handler core), TS1183
 * implementation-in-ambient-context (from the deleted
 * `checkAmbientImplementation` walk family; `emitTS1183` retained), and
 * TS2436 ambient relative module names (from the deleted
 * `checkAmbientRelativeModuleNames` walk).
 */
class Inv4SpineBatch7Test {

    // ── TS2566: a rest element cannot have a property name ──────────────────

    @Test
    fun `rest element with property name in a destructuring declaration fires TS2566`() {
        diagnose(
            """
            declare const o: any;
            const { ...a: b } = o;
            """,
        ) should {
            have(any { it.code == 2566 })
        }
    }

    @Test
    fun `rest element with property name in a parameter pattern fires TS2566`() {
        diagnose(
            """
            function f({ ...x: y }: any) {}
            """,
        ) should {
            have(any { it.code == 2566 })
        }
    }

    @Test
    fun `rest element with property name in a NESTED pattern fires TS2566`() {
        diagnose(
            """
            declare const o: any;
            const { p: { ...a: b } } = o;
            """,
        ) should {
            have(any { it.code == 2566 })
        }
    }

    @Test
    fun `negative control - plain rest element is fine`() {
        diagnose(
            """
            declare const o: any;
            const { a, ...rest } = o;
            """,
        ) should {
            have(none { it.code == 2566 })
        }
    }

    @Test
    fun `widening - rest element with property name in a catch pattern fires TS2566`() {
        // The old walk never descended catch-clause binding patterns; the
        // spine visits every ObjectBindingPattern (pure-syntax grammar rule).
        diagnose(
            """
            try {} catch ({ ...a: b }) {}
            """,
        ) should {
            have(any { it.code == 2566 })
        }
    }

    // ── TS1186/TS2493/TS2322: rest-param binding-pattern elements ───────────

    @Test
    fun `rest element with initializer inside a rest-param array pattern fires TS1186`() {
        diagnose(
            """
            function f(...[...r = [1]]: number[]) {}
            """,
        ) should {
            have(any { it.code == 1186 })
        }
    }

    @Test
    fun `tuple out-of-bounds element in a rest-param pattern fires TS2493`() {
        diagnose(
            """
            function f(...[a, b]: [number]) {}
            """,
        ) should {
            have(any { it.code == 2493 })
        }
    }

    @Test
    fun `default not assignable to the array element type fires TS2322`() {
        diagnose(
            """
            function f(...[a = "s"]: number[]) {}
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - matching defaults and in-bounds elements are fine`() {
        diagnose(
            """
            function f(...[a = 1, b]: number[]) {}
            function g(...[a, b]: [number, string]) {}
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 2493 || it.code == 1186 })
        }
    }

    @Test
    fun `widening - object-literal method rest-param pattern is checked for TS2493`() {
        // The old expression walk had no ObjectLiteralExpression case, so
        // object-literal method parameters were never checked.
        diagnose(
            """
            const o = {
                m(...[a, b]: [number]) {},
            };
            """,
        ) should {
            have(any { it.code == 2493 })
        }
    }

    // ── TS1183: an implementation cannot be declared in ambient contexts ────

    @Test
    fun `declare function with a body fires TS1183`() {
        diagnose(
            """
            declare function f(): void { }
            """,
        ) should {
            have(any { it.code == 1183 })
        }
    }

    @Test
    fun `declare class method body fires TS1183`() {
        diagnose(
            """
            declare class C {
                m() { }
            }
            """,
        ) should {
            have(any { it.code == 1183 })
        }
    }

    @Test
    fun `function body inside a declare namespace fires TS1183`() {
        diagnose(
            """
            declare namespace M {
                function f(): void { }
            }
            """,
        ) should {
            have(any { it.code == 1183 })
        }
    }

    @Test
    fun `negative control - interface method bodies are dropped by the parse - no TS1183`() {
        // Like the TS1246 initializer shape (batch 4): the interface member
        // parse never STORES a body, so the interface arm covers only
        // body-carrying members — a faithful migration of the old walker's
        // (de-facto dormant) semantics; parse errors own the shape.
        diagnose(
            """
            interface I {
                m() { }
            }
            """,
        ) should {
            have(none { it.code == 1183 })
        }
    }

    @Test
    fun `nested function inside an ambient body emits exactly one TS1183`() {
        // The old walk never descended an AMBIENT function's body — only the
        // outer body reports; the nested function stays unreported.
        val diags = diagnose(
            """
            declare function f(): void { function g() { } }
            """,
        )
        val count = diags.count { it.code == 1183 }
        diags should {
            have(count == 1)
        }
    }

    @Test
    fun `negative control - non-ambient bodies are fine`() {
        diagnose(
            """
            function f() {
                function g() {}
            }
            class C {
                m() {}
            }
            """,
        ) should {
            have(none { it.code == 1183 })
        }
    }

    @Test
    fun `negative control - arrow body inside an ambient initializer is not ambient`() {
        // The old expression walk descended arrow/function-expression bodies
        // with ambient RESET to false — a function declared there is not
        // ambient (TS1039 owns the initializer error).
        diagnose(
            """
            declare var x = () => { function g() { } };
            """,
        ) should {
            have(none { it.code == 1183 })
        }
    }

    // ── TS2436: ambient module declaration cannot specify relative name ─────

    @Test
    fun `relative ambient module name in a script file fires TS2436`() {
        diagnose(
            """
            declare module "./foo" {}
            """,
        ) should {
            have(any { it.code == 2436 })
        }
    }

    @Test
    fun `rooted ambient module name in a script file fires TS2436`() {
        diagnose(
            """
            declare module "/abs/path" {}
            """,
        ) should {
            have(any { it.code == 2436 })
        }
    }

    @Test
    fun `negative control - bare ambient module name is fine`() {
        diagnose(
            """
            declare module "foo" {}
            """,
        ) should {
            have(none { it.code == 2436 })
        }
    }

    @Test
    fun `negative control - module files allow relative augmentation names`() {
        diagnose(
            """
            declare module "./foo" {}
            export {};
            """,
        ) should {
            have(none { it.code == 2436 })
        }
    }
}
