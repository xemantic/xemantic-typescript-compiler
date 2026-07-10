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
 * Round 463: generic type-alias instantiation no longer skips FunctionType /
 * ConstructorType alias bodies (the historical B50.1 gate). Un-substituted, the
 * alias body's own type parameter stays an unbound `Type.TypeParam` that FAILS the
 * relation against a concrete source — tsc's `getModuleTransformer(...):
 * TransformerFactory<SourceFile | Bundle> { return transformModule; }` FP'd TS2322
 * ×3 (transformer.ts:83/98/100; whole-program-only, because in a small program the
 * un-substituted `T` resolves to errorType and passes vacuously). The historical FP
 * hazard (un-inferred generic-call-result sources vs the substituted concrete
 * target) is covered by the round-431 foreign-TP source gates at the emission
 * sites.
 *
 * These tests pin the invariant PAIR the substitution creates: a conforming source
 * relates to the instantiated alias, and a NON-conforming source now genuinely
 * fails against the substituted (no-longer-errorType) parameter/return types.
 */
class FnTypeAliasInstantiationTest {

    private val prelude = """
        interface Ctx { ctx: number }
        interface SourceFile { kind: 1; text: string }
        interface Bundle { kind: 2; items: string[] }
        type Transformer<T> = (node: T) => T;
        type TransformerFactory<T> = (context: Ctx) => Transformer<T>;
    """.trimIndent()

    @Test
    fun `a conforming concrete fn relates to the instantiated fn-type alias in return position`() {
        diagnose(prelude + """
            declare function transformModule(context: Ctx): (x: SourceFile | Bundle) => SourceFile | Bundle;
            function getModuleTransformer(kind: number): TransformerFactory<SourceFile | Bundle> {
                switch (kind) {
                    case 1:
                        return transformModule;
                    default:
                        return transformModule;
                }
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a conforming arrow relates to the instantiated fn-type alias in a var decl`() {
        diagnose("""
            type Mapper<T> = (x: T) => T;
            const good: Mapper<string> = (x: string) => x;
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a param-mismatched fn fails the instantiated fn-type alias`() {
        diagnose("""
            type Mapper<T> = (x: T) => T;
            const bad: Mapper<string> = (x: number) => x;
        """) should {
            have(any { it.code == 2322 })
        }
    }
}
