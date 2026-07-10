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
 * Round 462: the call-arg narrow-DOWN gate (round 428b/429c/438) required the narrowed
 * type to refine the DECLARED type (`n <: declared`) — but tsc's
 * getNarrowedType(assumeTrue) legitimately narrows to a guard target that is NOT a
 * declared-type subtype (isPropertyNameLiteral's PropertyNameLiteral union contains
 * JsxNamespacedName, which does not extend Expression), so a genuine narrow was
 * rejected and the wide declared type FP'd TS2345 (tsc utilities.ts:4066
 * isSameEntityName — whole-program only; the probe showed refines=false /
 * matchesParam=true). The gate now ALSO accepts the narrowed type when it makes the
 * PARAM relation pass — the same substitute-only-when-the-relation-passes monotone
 * rule the assignment/return/var-decl gates use.
 */
class ArgNarrowParamRelationGateTest {

    @Test
    fun `guard target outside the declared hierarchy still suppresses when it matches the param`() {
        // C is unrelated to the declared A, so the old `n <: declared` refinement gate
        // rejects the narrow; the param-relation disjunct accepts it (B | C matches).
        diagnose("""
            interface A { a: string }
            interface B { a: string; b: number }
            interface C { c: number }
            declare function isBOrC(x: A): x is B | C;
            declare function useBC(x: B | C): void;
            function f(x: A) {
                if (isBOrC(x)) {
                    useBC(x);
                }
            }
        """) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a narrow matching NEITHER declared nor param does not suppress`() {
        diagnose("""
            interface A { a: string }
            interface B { a: string; b: number }
            declare function isB(x: A): x is B;
            declare function needStr(s: string): void;
            function f(x: A) {
                if (isB(x)) {
                    needStr(x);
                }
            }
        """) should {
            have(any { it.code == 2345 })
        }
    }
}
