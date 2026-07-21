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
 * (M0.4, round 624): pins for the checkObjectSpreadInvalidTypes (TS2698/TS2700)
 * spine migration — the legacy walk's annotation-map SCOPING (statement order,
 * per-block copy boundaries, param/fn-body layering) and its REACH quirks
 * (positions the legacy walker never visited must stay silent after the spine
 * visits them). All expectations verified against the pre-migration walker.
 */
class M04SpreadSpineMigrationTest {

    @Test
    fun `spread of a number-typed body local fires TS2698 via the earlier annotation`() {
        diagnose(
            """
            function f() {
                let x: number = 1
                const o = { ...x }
                return o
            }
            """
        ) should {
            have(any { it.code == 2698 })
        }
    }

    @Test
    fun `negative control - annotation AFTER the spread is not visible (statement order)`() {
        diagnose(
            """
            function f() {
                const o = { ...x }
                let x: number = 1
                return o
            }
            """
        ) should {
            have(none { it.code == 2698 })
        }
    }

    @Test
    fun `negative control - annotation recorded inside a block does not leak past it`() {
        diagnose(
            """
            function f() {
                {
                    let x: number = 1
                }
                const o = { ...x }
                return o
            }
            """
        ) should {
            have(none { it.code == 2698 })
        }
    }

    @Test
    fun `negative control - annotation in an if-branch single statement does not leak`() {
        diagnose(
            """
            function f(c: boolean) {
                if (c) var x: number
                const o = { ...x }
                return o
            }
            """
        ) should {
            have(none { it.code == 2698 })
        }
    }

    @Test
    fun `negative control - annotation in a switch clause does not leak past the switch`() {
        diagnose(
            """
            function f(k: number) {
                switch (k) {
                    case 0:
                        let x: number = 1
                        break
                }
                const o = { ...x }
                return o
            }
            """
        ) should {
            have(none { it.code == 2698 })
        }
    }

    @Test
    fun `spread of an annotated parameter fires TS2698`() {
        diagnose(
            """
            function f(x: number) {
                return { ...x }
            }
            """
        ) should {
            have(any { it.code == 2698 })
        }
    }

    @Test
    fun `spread inside an arrow expression body sees the outer annotation`() {
        diagnose(
            """
            declare const x: number
            const g = () => ({ ...x })
            """
        ) should {
            have(any { it.code == 2698 })
        }
    }

    @Test
    fun `spread of an enum-typed const fires TS2698 via the enum branch`() {
        diagnose(
            """
            enum Color { Red }
            declare const c: Color
            const o = { ...c }
            """
        ) should {
            have(any { it.code == 2698 })
        }
    }

    @Test
    fun `spread inside a namespace body sees the namespace-level annotation`() {
        diagnose(
            """
            namespace M {
                export let x: number
                const o = { ...x }
            }
            """
        ) should {
            have(any { it.code == 2698 })
        }
    }

    @Test
    fun `negative control - spreading an object type is legal`() {
        diagnose(
            """
            declare const obj: { a: number }
            const o = { ...obj }
            """
        ) should {
            have(none { it.code == 2698 })
        }
    }

    @Test
    fun `negative control - a spread inside a class-expression method body is unreached`() {
        // The legacy expression walker has NO ClassExpression arm — the spine
        // visits the position, but the reach classifier must keep it silent.
        diagnose(
            """
            declare const x: number
            const C = class {
                m() {
                    return { ...x }
                }
            }
            """
        ) should {
            have(none { it.code == 2698 })
        }
    }

    @Test
    fun `object rest of a number-typed source fires TS2700`() {
        diagnose(
            """
            declare const n: number
            const { ...r } = n
            """
        ) should {
            have(any { it.code == 2700 })
        }
    }

    @Test
    fun `negative control - object rest of an object type is legal`() {
        diagnose(
            """
            declare const p: { a: number }
            const { ...r } = p
            """
        ) should {
            have(none { it.code == 2700 })
        }
    }

    @Test
    fun `spread inside a nested function expression sees the outer annotation`() {
        diagnose(
            """
            declare const x: number
            const f = function () {
                return { ...x }
            }
            """
        ) should {
            have(any { it.code == 2698 })
        }
    }
}
