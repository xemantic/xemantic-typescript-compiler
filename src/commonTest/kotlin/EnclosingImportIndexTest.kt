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
import kotlin.test.assertEquals

/**
 * Pins the round-432 perf refactor: the program-wide `spec in bindings.elements`
 * structural scans in `resolveAlias`'s ImportSpecifier branch and
 * `findEnclosingImport` were replaced by a prebuilt structural-keyed index
 * (`enclosingImportsOf`). The index MUST preserve the scans' semantics:
 *
 * 1. Two files with BYTE-IDENTICAL import statements produce structurally EQUAL
 *    `ImportSpecifier` nodes (same name text, same positions) — they collide on
 *    one index key. Resolution must still work for BOTH files (the shared entry
 *    list carries both statements; both point at the same target module here, so
 *    either entry resolves identically).
 *
 * 2. Imports of the SAME NAME from DIFFERENT modules at DIFFERENT offsets are
 *    structurally DISTINCT specifiers — separate index keys; each file's alias
 *    must resolve to its OWN module (no cross-file bleed through the index).
 *
 * The signal is the resolution-dependent TS2322 on `const x: number = <imported>`:
 * it fires only when the imported alias resolves to the declaring module's typed
 * symbol (an unresolved alias types as `any` and stays silent).
 */
class EnclosingImportIndexTest {

    private fun build(files: Map<String, String>): ProjectCompiler.Result {
        val vfs = InMemoryVfs(
            files + ("/proj/tsconfig.json" to
                """{ "compilerOptions": { "strict": true, "outDir": "./dist" }, "include": ["src/**/*.ts"] }""")
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
    }

    private fun ts2322Files(result: ProjectCompiler.Result): List<String> =
        result.diagnostics.filter { it.code == 2322 }.mapNotNull { it.fileName }.sorted()

    @Test
    fun structurallyIdenticalImportsInTwoFilesBothResolve() {
        // f1.ts and f2.ts are byte-identical → their ImportSpecifier nodes are
        // structurally equal (index-key collision, one entry list for both files).
        val importer = "import { A } from \"./lib\";\nconst x: number = A;\n"
        val result = build(
            mapOf(
                "/proj/src/lib.ts" to "export const A: string = \"s\";\n",
                "/proj/src/f1.ts" to importer,
                "/proj/src/f2.ts" to importer,
            )
        )
        assertEquals(
            listOf("/proj/src/f1.ts", "/proj/src/f2.ts"),
            ts2322Files(result),
            "both structurally-colliding imports must resolve (string → number = TS2322 in each file); " +
                "diagnostics: ${result.diagnostics.map { "${it.fileName}: TS${it.code} ${it.message}" }}"
        )
    }

    @Test
    fun structurallyDistinctImportsResolveThroughTheirOwnStatements() {
        // Same import statement text, but g2's leading comment shifts its node
        // positions → the ImportSpecifier nodes are structurally DISTINCT and
        // land on SEPARATE index keys. Each file must resolve through its own
        // (file, statement) entry — a broken index (e.g. one that dropped
        // non-first entries or keyed too coarsely) would silence one file's
        // resolution-dependent TS2322.
        //
        // (Same-name exports from DIFFERENT modules cannot be used as the
        // signal here: the checker-init `mergeSymbolTable(globals, …)` scope
        // conflation — Blocker #3 — masks the second file's type regardless of
        // alias resolution; verified pre-existing on clean HEAD.)
        val result = build(
            mapOf(
                "/proj/src/lib.ts" to "export const A: string = \"s\";\n",
                "/proj/src/g1.ts" to "import { A } from \"./lib\";\nconst y: number = A;\n",
                "/proj/src/g2.ts" to "// offset-shifting comment\nimport { A } from \"./lib\";\nconst z: number = A;\n",
            )
        )
        assertEquals(
            listOf("/proj/src/g1.ts", "/proj/src/g2.ts"),
            ts2322Files(result),
            "both structurally-distinct imports must resolve through their own statements; " +
                "diagnostics: ${result.diagnostics.map { "${it.fileName}: TS${it.code} ${it.message}" }}"
        )
    }
}
