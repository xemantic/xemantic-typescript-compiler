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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Round 488 (M5.2): [Checker.getUnionType] gained tiny-input fast paths (size 1
 * and size 2, no nested-union members) that skip the general path's intermediate
 * list/set allocations. The fast path MUST be byte-identical to the general path,
 * so these pin its observable output — the sort-by-flags-value ordering, identity
 * dedup, and the 3+-member fall-through — through the SOURCE side of an
 * assignability error, where an INFERRED array-element union genuinely routes
 * through `getUnionType` (the private function is unreachable from a test, and the
 * TARGET side of a mismatch renders the annotation syntactically instead).
 */
class GetUnionTypeFastPathTest {

    // The SOURCE display of the TS2322 for `let <t>: <bad> = arr[0];`.
    private fun sourceDisplay(@Language("typescript") arrayLiteral: String, targetType: String): String? =
        diagnose("const arr = $arrayLiteral; let t: $targetType = arr[0];")
            .firstOrNull { it.code == 2322 }
            ?.message
            ?.substringAfter("Type '")
            ?.substringBefore("' is not assignable")

    @Test
    fun `2-element union sorts by flags value independent of element order`() {
        // string flag (1<<2) < number flag (1<<3) → "string | number" both ways;
        // this is the size-2 fast path's stable sort-by-flags-value.
        assert(sourceDisplay("[1, \"x\"]", "boolean") == "string | number")
        assert(sourceDisplay("[\"x\", 1]", "boolean") == "string | number")
    }

    @Test
    fun `identical members dedupe to a single type`() {
        // Every element widens to `number` → the union collapses to plain `number`
        // (no `|`), exercising the size-2 fast path's id-dedup / size-1 collapse.
        assert(sourceDisplay("[1, 2]", "boolean") == "number")
        assert(sourceDisplay("[1, 2, 3]", "boolean") == "number")
    }

    @Test
    fun `three-plus member unions fall through to the general path`() {
        // boolean-literal `true` sorts last (flag 1<<4); a size-3 input skips the
        // fast path entirely and must still sort + render identically.
        assert(sourceDisplay("[true, \"x\", 1]", "symbol") == "string | number | true")
    }

    @Test
    fun `nullish-coalescing result routes a 2-element union through getUnionType`() {
        // `x ?? 5` is typed NonNullable<string> | number = `string | number` via
        // combineBinaryTypes → getUnionType (a non-array-literal caller of the
        // size-2 fast path).
        val msg = diagnose(
            """
            declare const x: string | undefined;
            const y = x ?? 5;
            let b: boolean = y;
            """.trimIndent(),
        ).firstOrNull { it.code == 2322 }?.message
        assert(msg != null && msg.contains("Type 'string | number'"))
    }
}
