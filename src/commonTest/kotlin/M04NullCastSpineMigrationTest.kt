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
 * (M0.4): pins for the checkNullTypeAssertionOverlap spine migration — the
 * TypeAssertionExpression callback (`emitTS2352IfNullCast` bundling the
 * nullish-cast core, the B448 object-literal overlap, and the
 * class-instance-to-sig-interface cast) plus the four
 * `inNullCastOverlapPass`-flag-gated AsExpression emitters
 * (null-as-readonly-tuple / null-as-cast / invalid-const-assertion /
 * class-instance-to-Record), and the shared cast-overlap walker's reach
 * quirks in both directions (objlit method bodies and arrow bodies ARE
 * reached; parameter DEFAULTS are NOT). All expectations verified green
 * against the pre-migration legacy pass first.
 */
class M04NullCastSpineMigrationTest {

    // ── TypeAssertionExpression callback (emitTS2352IfNullCast) ───────────

    @Test
    fun `angle-bracket null cast to interface fires TS2352`() {
        diagnose(
            """
            interface I { a: number }
            var x = <I>null;
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - angle-bracket null cast to any draws no TS2352`() {
        diagnose(
            """
            var x = <any>null;
            """
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `non-overlapping object literal cast fires TS2352`() {
        diagnose(
            """
            interface IPoint { x: number; y: number }
            var p = <IPoint>{ z: true };
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - empty object literal cast draws no TS2352`() {
        // B448: bidirectional overlap — the target side overlaps an empty
        // literal, so `<IPoint>{}` is legal.
        diagnose(
            """
            interface IPoint { x: number; y: number }
            var p = <IPoint>{};
            """
        ) should {
            have(none { it.code == 2352 })
        }
    }

    // ── flag-gated AsExpression emitters ──────────────────────────────────

    @Test
    fun `null as interface fires TS2352`() {
        diagnose(
            """
            interface I { a: number }
            var x = null as I;
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `undefined as readonly tuple fires TS2352`() {
        diagnose(
            """
            var t = undefined as readonly [number, string];
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `const assertion on a non-enum member access fires TS1355`() {
        diagnose(
            """
            const E5 = { a: 1 };
            let v = E5.a as const;
            """
        ) should {
            have(any { it.code == 1355 &&
                it.message == "A 'const' assertion can only be applied to references to enum members, or string, number, boolean, array, or object literals." })
        }
    }

    @Test
    fun `negative control - const assertion on an enum member access draws no TS1355`() {
        diagnose(
            """
            enum E { A }
            let v = E.A as const;
            """
        ) should {
            have(none { it.code == 1355 })
        }
    }

    @Test
    fun `class instance as Record of unknown fires TS2352`() {
        diagnose(
            """
            class C { m(): number { return 1; } }
            var r = new C() as Record<string, unknown>;
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - class instance as Record of any draws no TS2352`() {
        diagnose(
            """
            class C { m(): number { return 1; } }
            var r = new C() as Record<string, any>;
            """
        ) should {
            have(none { it.code == 2352 })
        }
    }

    // ── shared-walker reach quirks (both directions) ──────────────────────

    @Test
    fun `null cast inside an object-literal method body fires TS2352`() {
        diagnose(
            """
            interface I { a: number }
            const o = { m() { return <I>null; } };
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `null cast inside an arrow body fires TS2352`() {
        diagnose(
            """
            interface I { a: number }
            const f = () => <I>null;
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `null cast inside a class-expression method body fires TS2352`() {
        diagnose(
            """
            interface I { a: number }
            const K = class { m() { return <I>null; } };
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - null cast in a parameter DEFAULT draws no TS2352`() {
        // The shared cast-overlap walker never descends parameter defaults.
        diagnose(
            """
            interface I { a: number }
            function f(p = <I>null) {}
            """
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - null as interface in a parameter DEFAULT draws no TS2352`() {
        diagnose(
            """
            interface I { a: number }
            function f(p = null as I) {}
            """
        ) should {
            have(none { it.code == 2352 })
        }
    }
}
