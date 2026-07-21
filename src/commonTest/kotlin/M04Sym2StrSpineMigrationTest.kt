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
 * (M0.4, round 628): pins for the checkSymbolToStringConversions
 * (TS2469/TS2731) spine migration — the legacy walk's downward
 * (symbolNames, tpNames) context (whole-list locals PREPASS per body:
 * a nested-block `var` is visible to a sibling anchor; accumulate-only
 * sets: an inner string-typed param does NOT shadow an outer symbol
 * name), the type-alias fixpoint, class-TP threading (methods/ctors see
 * the class TP; a class PROPERTY initializer is blind to it — the legacy
 * outer-tpNames quirk), the operator anchors (binary `+` left-else-right,
 * `+=` right-only, unary `+`, template spans), and the reach quirks
 * (typeof operands, tagged templates, and objlit METHOD bodies stay
 * unreached; case-clause EXPRESSIONS and bare for-initializer
 * EXPRESSIONS ARE reached — unlike the fp/ai classifiers). All
 * expectations verified green against the pre-migration legacy walker
 * first.
 */
class M04Sym2StrSpineMigrationTest {

    @Test
    fun `template interpolation of a symbol-typed const fires TS2731`() {
        diagnose(
            """
            declare const s: symbol
            const t = `x${'$'}{s}y`
            """
        ) should {
            have(any { it.code == 2731 })
        }
    }

    @Test
    fun `negative control - string-typed interpolation draws no TS2731`() {
        diagnose(
            """
            declare const s: string
            const t = `x${'$'}{s}y`
            """
        ) should {
            have(none { it.code == 2731 })
        }
    }

    @Test
    fun `symbol on the left of plus fires TS2469`() {
        diagnose(
            """
            declare const s: symbol
            const r = s + ""
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `symbol on the right of plus fires TS2469`() {
        diagnose(
            """
            declare const s: symbol
            const r = "" + s
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `symbol on both sides of plus fires once only`() {
        val diags = diagnose(
            """
            declare const s: symbol
            const r = s + s
            """
        )
        val count2469 = diags.count { it.code == 2469 }
        diags should {
            have(count2469 == 1)
        }
    }

    @Test
    fun `plus-equals with symbol on the right fires TS2469`() {
        diagnose(
            """
            declare const s: symbol
            let x = ""
            x += s
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `legacy quirk - symbol on the left of plus-equals draws no TS2469`() {
        diagnose(
            """
            declare let s: symbol
            s += 1
            """
        ) should {
            have(none { it.code == 2469 })
        }
    }

    @Test
    fun `unary plus on a symbol fires TS2469`() {
        diagnose(
            """
            declare const s: symbol
            const n = +s
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `union annotation containing symbol fires TS2469`() {
        diagnose(
            """
            declare const s: symbol | string
            const r = s + ""
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `symbol-constrained type parameter param fires TS2469`() {
        diagnose(
            """
            function f<T extends symbol>(x: T) {
                return x + ""
            }
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `symbol param is visible inside a nested arrow body`() {
        diagnose(
            """
            function f<T extends symbol>(x: T) {
                return () => x + ""
            }
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `type alias chain resolving to symbol fires TS2469`() {
        diagnose(
            """
            type A = B
            type B = symbol
            declare const s: A
            const r = s + ""
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `legacy prepass - a nested-block var is visible to a sibling anchor`() {
        diagnose(
            """
            function f(flag: boolean) {
                if (flag) { var s: symbol }
                return "" + s
            }
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `legacy quirk - an inner string param does not shadow an outer symbol name`() {
        diagnose(
            """
            function f(s: symbol) {
                return (s: string) => s + ""
            }
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `class method sees the class type parameter constraint`() {
        diagnose(
            """
            class C<T extends symbol> {
                m(x: T) { return x + "" }
            }
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `constructor body sees the class type parameter constraint`() {
        diagnose(
            """
            class C<T extends symbol> {
                constructor(x: T) { const r = x + "" }
            }
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `legacy quirk - a class property initializer is blind to the class type parameter`() {
        diagnose(
            """
            class C<T extends symbol> {
                p = (x: T) => x + ""
            }
            """
        ) should {
            have(none { it.code == 2469 })
        }
    }

    @Test
    fun `typeof operand is unreached`() {
        diagnose(
            """
            declare const s: symbol
            const t = typeof (s + "")
            """
        ) should {
            have(none { it.code == 2469 })
        }
    }

    @Test
    fun `tagged template span is unreached`() {
        diagnose(
            """
            declare const s: symbol
            declare function tag(...args: any[]): string
            const t = tag`x${'$'}{s}`
            """
        ) should {
            have(none { it.code == 2731 })
        }
    }

    @Test
    fun `object literal method body is unreached`() {
        diagnose(
            """
            declare const s: symbol
            const o = { m() { return s + "" } }
            """
        ) should {
            have(none { it.code == 2469 })
        }
    }

    @Test
    fun `object literal property value is reached`() {
        diagnose(
            """
            declare const s: symbol
            const o = { p: s + "" }
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `case clause expression is reached`() {
        diagnose(
            """
            declare const s: symbol
            declare const x: string
            switch (x) {
                case s + "": break
            }
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `bare for-initializer expression is reached`() {
        diagnose(
            """
            declare let s: symbol
            for (s + ""; false;) {}
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `namespace-local symbol var fires inside the namespace body`() {
        diagnose(
            """
            namespace N {
                declare const s: symbol
                export const r = s + ""
            }
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }

    @Test
    fun `new expression arguments are reached`() {
        diagnose(
            """
            declare const s: symbol
            class Foo { constructor(x: string) {} }
            new Foo(s + "")
            """
        ) should {
            have(any { it.code == 2469 })
        }
    }
}
