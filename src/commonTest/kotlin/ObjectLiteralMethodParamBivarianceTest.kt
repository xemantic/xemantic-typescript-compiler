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
 * Round 454 (M3.1, self-compile burn-down): a METHOD member (method syntax) compares its
 * parameters BIVARIANTLY vs an interface target (tsc — `strictFunctionTypes` never applies to
 * method members), but our object-literal-vs-interface relation compared function-typed members
 * contravariantly. tsc's own checker.ts `const syntacticBuilderResolver:
 * SyntacticTypeNodeBuilderResolver = { canReuseTypeNodeAnnotation(…, symbol: Symbol, …) {…}, … }`
 * FP-fired TS2322 because the method's `symbol: Symbol` param is narrower than the interface's
 * `symbol: Symbol | undefined`. `propertiesRelatedTo` now retries a failed member comparison via
 * the bivariant helper (already used for TS2416/TS2430) when BOTH members are methods —
 * suppression-only, FP-safe by construction. A function-typed PROPERTY stays contravariant.
 */
class ObjectLiteralMethodParamBivarianceTest {

    private val prelude = """
        interface Sym { name: string; }
        interface WithMethod { reuse(ctx: number, symbol: Sym | undefined): boolean; }
        interface WithProperty { reuse: (ctx: number, symbol: Sym | undefined) => boolean; }
    """.trimIndent() + "\n"

    @Test
    fun `an object-literal method with a narrower param satisfies a method interface`() {
        // The method's `symbol: Sym` is narrower than the target's `symbol: Sym | undefined`;
        // method params are bivariant → no TS2322.
        diagnose(
            prelude +
            """
            export const r: WithMethod = {
                reuse(ctx: number, symbol: Sym): boolean { return true; },
            };
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a function-typed property with a narrower param stays contravariant - negative control`() {
        // The property value is an arrow (a function-typed PROPERTY, not a method), so
        // strictFunctionTypes contravariance applies → TS2322 must still fire.
        diagnose(
            prelude +
            """
            export const r: WithProperty = {
                reuse: (ctx: number, symbol: Sym) => true,
            };
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
