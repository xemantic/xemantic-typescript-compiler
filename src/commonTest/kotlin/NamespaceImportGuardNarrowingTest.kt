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
 * Round 477 (tsc harness incrementalUtils): a type guard called through an
 * `import * as ns from "spec"` NAMESPACE import (`ts.isDocumentRegistryEntry(entry)`
 * where `ts` is the harness `.js` barrel) — [resolveAlias] never resolves a
 * NamespaceImport alias (the round-444 gap) and the ImportSpecifier-keyed
 * flow-only resolvers skip it, so the guard silently never narrowed.
 * [resolveNamespaceMemberFnDecl] gains an `import * as ns` branch: resolve the
 * import's target FILE and look the member up through its locals + `export *`
 * chain (memoized in nsImportMemberFnCache).
 */
class NamespaceImportGuardNarrowingTest {

    private val decls = """
        // @module: nodenext
        // @strict: true
        // @filename: documentRegistry.ts
        export interface DocumentRegistryEntry {
            sourceFile: string;
            languageServiceRefCount: number;
        }
        export type BucketEntry = DocumentRegistryEntry | Map<string, DocumentRegistryEntry>;
        export interface DocumentRegistry {
            getBuckets(): Map<string, Map<string, BucketEntry>>;
        }
        export function isDocumentRegistryEntry(entry: BucketEntry): entry is DocumentRegistryEntry {
            return !!(entry as DocumentRegistryEntry).sourceFile;
        }
        // @filename: inner.ts
        export * from "./documentRegistry.js";
        // @filename: barrel.ts
        export * from "./inner.js";
    """.trimIndent()

    @Test
    fun `a namespace-import guard narrows both branches through a two-level barrel`() {
        diagnose(
            decls + """

            // @filename: main.ts
            import * as ts from "./barrel.js";
            export function report(documentRegistry: ts.DocumentRegistry): string[] {
                const str: string[] = [];
                documentRegistry.getBuckets().forEach((bucketEntries, key) => {
                    bucketEntries.forEach((entry, path) => {
                        if (ts.isDocumentRegistryEntry(entry)) {
                            str.push(entry.sourceFile + entry.languageServiceRefCount);
                        }
                        else {
                            entry.forEach((real, kind) => str.push(kind + real.languageServiceRefCount));
                        }
                    });
                });
                return str;
            }
            """.trimIndent(),
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - without the guard the union access still fails`() {
        diagnose(
            decls + """

            // @filename: main.ts
            import * as ts from "./barrel.js";
            export function bad(documentRegistry: ts.DocumentRegistry): void {
                documentRegistry.getBuckets().forEach(bucketEntries => {
                    bucketEntries.forEach(entry => {
                        entry.sourceFile;
                    });
                });
            }
            """.trimIndent(),
        ) should {
            have(any { it.code == 2339 })
        }
    }
}
