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
 * Round 463: `getTypeFromMappedType`'s union-constraint key enumeration used
 * `mapNotNull`, silently DROPPING every constituent that doesn't resolve to a
 * string literal — `[K in keyof T & CompilerOptionKeys | StrictOptionName]` with
 * T un-inferred (tsc's createComputedCompilerOptions, utilities.ts:9042) resolved
 * only the StrictOptionName members, and the PARTIAL key domain manufactured an
 * excess-property TS2353 on a genuinely-valid key. A union constraint with any
 * non-enumerable constituent now bails the whole mapped type to anyType (an
 * unknowable domain can't prove excess), matching the non-literal `else` branch.
 */
class MappedTypeUnknowableKeysTest {

    @Test
    fun `an un-inferred keyof-T union constituent makes the mapped key domain unknowable`() {
        diagnose("""
            type Keys = "a" | "b" | "c";
            type StrictKeys = "s1" | "s2";
            declare function create<T extends Record<string, readonly Keys[]>>(
                options: { [K in keyof T & Keys | StrictKeys]: { dependencies: readonly Keys[]; compute: (x: number) => number } }
            ): T;
            const r = create({
                a: { dependencies: [], compute: (x: number) => x },
                s1: { dependencies: [], compute: (x: number) => x },
                s2: { dependencies: [], compute: (x: number) => x },
            });
        """) should {
            have(none { it.code == 2353 })
        }
    }

    @Test
    fun `negative control - a fully-literal mapped key domain still fires excess TS2353`() {
        diagnose("""
            type StrictKeys = "s1" | "s2";
            declare function create(
                options: { [K in StrictKeys]: number }
            ): void;
            create({ s1: 1, s2: 2, extra: 3 });
        """) should {
            have(any { it.code == 2353 })
        }
    }
}
