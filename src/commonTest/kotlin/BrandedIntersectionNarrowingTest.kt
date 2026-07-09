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
 * Round 456: the assignment-RHS AND return-path flow-narrowing gates now accept a
 * `Type.Intersection` target (a BRANDED string/number like `Path = string &
 * {__pathBrand}` / `IncrementalBuildInfoFileId = number & {__…Brand}`), mirroring
 * the round-410/438 Interface/Reference/Object/Union gates. FP-safe by construction:
 * the narrowed type is substituted only when it makes the relation pass. Fixes tsc's
 * resolutionCache.ts (`fileOrDirectoryPath = updatedPath` after `if (!updatedPath)
 * return`) and builder.ts (`return fileId` after `if (fileId === undefined) { fileId
 * = … }`).
 */
class BrandedIntersectionNarrowingTest {

    private val prelude = "type Path = string & { __pathBrand: any };\n"

    @Test
    fun `assign a guard-narrowed const to a branded-intersection target`() {
        diagnose(
            prelude + """
            declare function removeIgnoredPath(p: Path): Path | undefined;
            function f(fileOrDirectoryPath: Path): boolean {
                const updatedPath = removeIgnoredPath(fileOrDirectoryPath);
                if (!updatedPath) return false;
                fileOrDirectoryPath = updatedPath;
                return true;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `return a branch-assigned reference against a branded-intersection return type`() {
        diagnose(
            """
            type FileId = number & { __brand: any };
            declare const cache: { get(k: string): FileId | undefined };
            function toFileId(path: string): FileId {
                let fileId = cache.get(path);
                if (fileId === undefined) {
                    fileId = 1 as FileId;
                }
                return fileId;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a genuinely nullable value still fails a branded target`() {
        // No guard: `updatedPath` stays `Path | undefined` at the assignment → TS2322.
        diagnose(
            prelude + """
            declare function removeIgnoredPath(p: Path): Path | undefined;
            function f(fileOrDirectoryPath: Path): void {
                const updatedPath = removeIgnoredPath(fileOrDirectoryPath);
                fileOrDirectoryPath = updatedPath;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
