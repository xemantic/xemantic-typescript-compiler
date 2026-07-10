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
 * Round 463: TS 5.5-style INFERRED type predicates, bounded slice — a
 * single-expression discriminant arrow (`helper => !helper.scoped`) in a
 * guard-overload callback position (`filter<T, U extends T>(array, f: (x: T) =>
 * x is U): U[]`) infers `x is <matching union members>` when every element-union
 * member declares the property with a boolean-LITERAL annotation. tsc's own
 * factory/utilities.ts `getImportedHelpers` relies on it: without the inference
 * the non-guard overload returns the full `EmitHelper[]` and `helper.importName`
 * FP'd TS2339 (importName exists only on UnscopedEmitHelper).
 */
class InferredDiscriminantPredicateTest {

    private val prelude = """
        interface EmitHelperBase { readonly name: string; }
        interface ScopedEmitHelper extends EmitHelperBase { readonly scoped: true; }
        interface UnscopedEmitHelper extends EmitHelperBase {
            readonly scoped: false;
            readonly importName?: string;
        }
        type EmitHelper = ScopedEmitHelper | UnscopedEmitHelper;
        declare function filter<T, U extends T>(array: readonly T[], f: (x: T) => x is U): U[];
        declare function filter<T>(array: readonly T[], f: (x: T) => boolean): readonly T[];
        declare function getEmitHelpers(): EmitHelper[];
    """.trimIndent()

    @Test
    fun `a negated boolean-literal discriminant arrow infers the false-side members`() {
        diagnose(prelude + """
            const helpers: UnscopedEmitHelper[] = filter(getEmitHelpers(), helper => !helper.scoped);
            for (const helper of helpers) {
                const importName = helper.importName;
                if (importName) { importName.length; }
            }
        """) should {
            have(none { it.code == 2322 })
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a bare boolean-literal discriminant arrow infers the true-side members`() {
        diagnose(prelude + """
            const scoped = filter(getEmitHelpers(), helper => helper.scoped);
            for (const helper of scoped) {
                const flag: true = helper.scoped;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a non-discriminant arrow makes no predicate claim and TS2339 still fires`() {
        diagnose(prelude + """
            declare function pick(h: EmitHelperBase): boolean;
            const helpers: EmitHelper[] = getEmitHelpers();
            const some = helpers.length > 1 ? helpers : helpers;
            for (const helper of some) {
                const importName = helper.importName;
            }
        """) should {
            have(any { it.code == 2339 })
        }
    }
}
