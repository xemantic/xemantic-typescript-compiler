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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.58) A `new C()` SOURCE AT A VAR DECL — the asymmetry (CHK.57) recorded and did
 * not close.
 *
 * [Checker.topLevelWeakSource] classifies a var-decl initializer for the weak rule and
 * had branches for an `as` cast, an enum member and a primitive literal but none for a
 * `NewExpression` — so `zzzF(new C())` reported and `const v: W = new C()` did not, for
 * the same source, the same target and the same rule. tsc 7.0.2 reports both
 * (`build/chk58/ora4/y2.ts(3,23)` and `y2.ts(4,7)`, the same sentence).
 */
class WeakVarDeclNewSourceTest {

    /**
     * BOTH POSITIONS, one fixture. tsc 7.0.2 over `y2.ts`: `(3,23)` at the argument and
     * `(4,7)` at the variable NAME, `Type 'ZzzC2' has no properties in common with type
     * '{ zzzA?: null | undefined; zzzF?: string | undefined; }'.` The argument row was
     * already correct; the var-decl one is the new half, so a pin asserting only the
     * count would be half green for the wrong reason — both spans are asserted.
     */
    @Test
    fun `a new expression source reports at a var decl as it already did at an argument`() {
        val d = diagnose("""
            class ZzzC2 { zzzQ = 1 }
            declare function zzzY2f(o: { zzzA?: null; zzzF?: string }): number;
            const zzzY2r = zzzY2f(new ZzzC2());
            const zzzY2v: { zzzA?: null; zzzF?: string } = new ZzzC2();
        """)
        assert(d.map { it.code } == listOf(2559, 2559))
        assert(d.all {
            it.message == "Type 'ZzzC2' has no properties in common with type " +
                "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'."
        })
        assert(d.map { it.line to it.character }.sortedBy { it.first } == listOf(3 to 23, 4 to 7))
    }

    /**
     * A class with only PRIVATE members is still a weak-rule source — its property set
     * is enumerable and shares nothing with the target. tsc 7.0.2 over `y7.ts(6,7)`.
     * This is the pin that says the branch is about the SHAPE and not about "the class
     * has a public field".
     */
    @Test
    fun `a private-only class instance is still a source with no common property`() {
        val d = diagnose("""
            class ZzzP7 { private zzzS = 1 }
            const zzzY7w: { zzzA?: null; zzzF?: string } = new ZzzP7();
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type 'ZzzP7' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 2)
        assert(d[0].character == 7)
    }

    /**
     * MEASURED RESIDUE, not coverage — a GENERIC instantiation is silent in BOTH
     * positions, where tsc 7.0.2 reports `y7.ts(3,23)` and `y7.ts(4,7)` naming
     * `ZzzG7<number>`. [Checker.weakSourcePropertyNames] answers null for a
     * [Type.Reference] by design (its members are lazy, and a missed property would be
     * a FALSE TS2559), so the refusal is the documented conservatism rather than a
     * consequence of the new branch — and it is SYMMETRIC, which is the point: the two
     * positions now refuse the same things.
     */
    @Test
    fun `residue - a generic instantiation source is silent in both positions`() {
        val d = diagnose("""
            class ZzzG7<T> { zzzQ: T | undefined }
            declare function zzzY7f(o: { zzzA?: null; zzzF?: string }): number;
            const zzzY7r = zzzY7f(new ZzzG7<number>());
            const zzzY7v: { zzzA?: null; zzzF?: string } = new ZzzG7<number>();
        """)
        assert(d.none { it.code == 2559 || it.code == 2560 })
    }
}
