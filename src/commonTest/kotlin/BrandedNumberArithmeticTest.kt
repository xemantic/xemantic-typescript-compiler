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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M1.12 (self-compile burn-down): a BRANDED number `number & { __brand }` is assignable to
 * `number` (an intersection is a subtype of each of its members), so arithmetic on it is valid.
 * The arithmetic-operand classifiers (`isNumberLikeType`/`isBigIntLikeType`/`isStringLikeType` +
 * the B283 `typeAssignableTo*Kind`) handled Union/TypeParam but not `Type.Intersection`, so
 * tsc's own `type IncrementalBuildInfoFileId = number & {…}` FP'd TS2362 on `fileId - 1`.
 *
 * Fix: an intersection is number-/bigint-/string-like iff ANY constituent is. Negative control:
 * an intersection with NO primitive member must still fire TS2362.
 */
class BrandedNumberArithmeticTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `branded number arithmetic - no TS2362`() {
        val d = diags(
            """
            type FileId = number & { __fileIdBrand: any };
            export function toIndex(id: FileId): number {
                return id - 1;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2362 || it.code == 2363 },
            "arithmetic on a branded number `number & {…}` must not fire TS2362/TS2363; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `branded number comparison - no TS2365`() {
        val d = diags(
            """
            type FileId = number & { __fileIdBrand: any };
            export function lt(a: FileId, b: FileId): boolean {
                return a < b;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2365 },
            "comparison of two branded numbers must not fire TS2365; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `branded bigint arithmetic - no TS2362`() {
        val d = diags(
            """
            type BigId = bigint & { __bigBrand: any };
            export function dec(id: BigId): bigint {
                return id - 1n;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2362 || it.code == 2363 },
            "arithmetic on a branded bigint must not fire TS2362/TS2363; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `intersection with no primitive member STILL fires TS2362 - negative control`() {
        val d = diags(
            """
            type Combo = { a: number } & { b: string };
            export function bad(x: Combo): number {
                return x - 1;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2362 },
            "an object-intersection (no number member) MUST still fire TS2362 on `x - 1`; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
