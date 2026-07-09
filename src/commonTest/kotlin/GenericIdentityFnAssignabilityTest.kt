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
 * Round 455 (M3.1, self-compile burn-down): a generic function type whose type param is inferable
 * from the target's parameters is assignable to a concrete function type — `identity<T>(x: T): T`
 * relates to `GetCanonicalFileName = (fileName: string) => string` (T := string). `signatureRelatedTo`
 * already pinned source type params from the target's param positions (17.10d) but did NOT substitute
 * those pins into the source RETURN type, so the return `T` FP-rejected against the concrete target
 * return. tsc's own core.ts `createGetCanonicalFileName` (`return useCaseSensitiveFileNames ? identity
 * : toFileNameLowerCase`) and sourcemap.ts `identitySourceMapConsumer` FP-fired TS2322.
 */
class GenericIdentityFnAssignabilityTest {

    private val prelude = """
        declare function identity<T>(x: T): T;
        declare function toLower(s: string): string;
        type GetCanonicalFileName = (fileName: string) => string;
    """.trimIndent()

    @Test
    fun `a generic identity function is assignable to a concrete function type`() {
        diagnose(
            prelude + """
            export const g: GetCanonicalFileName = identity;
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a ternary of identity and a concrete function relates to the target return type`() {
        diagnose(
            prelude + """
            export function make(useCS: boolean): GetCanonicalFileName {
                return useCS ? identity : toLower;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `the pinned instantiation still fails when the return does not relate - negative control`() {
        // `NumFn = (x: string) => number`: pinning T := string makes identity's return `string`,
        // which is NOT assignable to `number` — the relation must still fire TS2322.
        diagnose(
            prelude + """
            type NumFn = (x: string) => number;
            export const bad: NumFn = identity;
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
