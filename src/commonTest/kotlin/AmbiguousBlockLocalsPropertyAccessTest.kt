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
 * Round 462: [applyAmbiguousBlockScopedLocals] (round 460 — a name with ≥2
 * block-scoped declarations in ONE function body registers anyType, because the
 * flat first-decl-wins currentLocalTypes cannot know which block's binding a later
 * read refers to) now also runs in the PROPERTY-ACCESS pass's three function-body
 * entry sites (FunctionDeclaration / ArrowFunction / FunctionExpression), not just
 * the assignability pass.
 *
 * tsc-source shape: moduleNameResolver.ts loadModuleFromTargetExportOrImport has
 * `const result = nodeModuleNameResolverWorker(...)` (ResolvedModuleWithFailed-
 * LookupLocations) in one block and `const result = <recursive SearchResult call>`
 * in another — `result.value` (line 2823) FP'd TS2339 on the FIRST block's type.
 */
class AmbiguousBlockLocalsPropertyAccessTest {

    @Test
    fun `two same-named block-scoped locals in one body do not cross-poison a property access`() {
        diagnose("""
            interface Loaded { path: string }
            interface SearchRes { value: Loaded | undefined }
            interface Wide { resolvedModule: Loaded | undefined }
            declare function resolveWide(): Wide;
            function load(n: number): SearchRes | undefined {
                if (n === 0) {
                    const result = resolveWide();
                    if (result.resolvedModule) return { value: result.resolvedModule };
                    return undefined;
                }
                for (let i = 0; i < n; i++) {
                    const result = load(n - 1);
                    if (result) {
                        if (result.value) {
                            return result;
                        }
                    }
                }
                return undefined;
            }
        """) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a single-declaration local still resolves and a bad member fires`() {
        diagnose("""
            interface Wide { resolvedModule: string | undefined }
            declare function resolveWide(): Wide;
            function f() {
                const result = resolveWide();
                return result.nonExistent;
            }
        """) should {
            have(any { it.code == 2339 })
        }
    }
}
