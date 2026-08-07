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
 * Round 455 (lib gap, self-compile burn-down): the embedded `ReadonlyArray<T>.filter` (and
 * `every`) lacked the type-predicate overload that `Array<T>.filter` already carried, so
 * `readonlyArr.filter(isFoo)` returned the base `T[]` instead of the refined `S[]`. tsc's own
 * utilitiesPublic.ts `getJSDocTagsWorker(...).filter(isJSDocParameterTag)` (a `readonly
 * JSDocTag[]`) FP-fired TS2322 `'JSDocTag[]' is not assignable to 'readonly JSDocParameterTag[]'`
 * (×3). Added the `filter<S extends T>(…): S[]` and `every<S extends T>(…): this is S[]`
 * overloads to ReadonlyArray, mirroring Array.
 */
class ReadonlyArrayFilterGuardTest {

    private val prelude = """
        interface Tag { kind: number; }
        interface ParamTag extends Tag { name: string; }
        declare function isParamTag(node: Tag): node is ParamTag;
        declare const roTags: readonly Tag[];
    """.trimIndent()

    @Test
    fun `readonly array filter with a NAMED type guard refines the element type`() {
        diagnose(
            prelude + """
            export function a(): readonly ParamTag[] {
                return roTags.filter(isParamTag);
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `readonly array filter with an INLINE type guard refines the element type`() {
        diagnose(
            prelude + """
            export function b(): readonly ParamTag[] {
                return roTags.filter((t): t is ParamTag => isParamTag(t));
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `readonly array filter with a plain boolean predicate keeps the base element type - negative control`() {
        // A non-guard predicate must NOT refine — `filter(t => t.kind > 0)` stays `Tag[]`, so
        // assigning it to `readonly ParamTag[]` still fires TS2322.
        diagnose(
            prelude + """
            export function c(): readonly ParamTag[] {
                return roTags.filter(t => t.kind > 0);
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
