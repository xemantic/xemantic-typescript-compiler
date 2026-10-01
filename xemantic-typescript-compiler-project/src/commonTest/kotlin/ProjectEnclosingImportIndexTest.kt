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

package com.xemantic.typescript.compiler.project
import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.EagerIndexCensus
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (INC.81) pinned `Checker.enclosingImportIndex`'s one-entry representation and its
 * promotion to a list when byte-identical importers share one STRUCTURAL key
 * (`ImportSpecifier` is a data class, so equal text at equal offsets is one key).
 *
 * (CHK.192) That sharing was the defect: a specifier colliding with another file's took
 * the FIRST file's statement, so two files importing the same name from DIFFERENT modules
 * at the same offsets resolved one of them through the wrong module. A specifier's own
 * statement now comes from its parent chain, and the structural index survives only as
 * the fallback for an unindexed node — so the pins are now that an ordinary project and
 * the twin fixture both resolve their imports WITHOUT building the index at all.
 */
class ProjectEnclosingImportIndexTest {

    private val options =
        """"compilerOptions": { "target": "es2020", "module": "esnext", "strict": true, "noEmit": true, "types": [] }"""

    private val lib = "export function helper(n: number): number { return n; }\n"

    /** Distinct importers: every specifier is its own key, reached from one file. */
    private fun plainVfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to """{ $options, "include": ["src/**/*.ts"] }""",
            "/proj/src/lib.ts" to lib,
            "/proj/src/a.ts" to "import { helper } from \"./lib\";\nexport const a = helper(\"x\");\n",
            "/proj/src/b.ts" to "import { helper as other } from \"./lib\";\nexport const b = other(\"y\");\n",
        ),
    )

    /**
     * TWINS: byte-identical importers, so their `helper` specifiers carry the same text
     * AND the same offsets and are one structural key with two entries.
     */
    private val twin = "import { helper } from \"./lib\";\nexport const v = helper(\"x\");\n"

    private fun twinVfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to """{ $options, "include": ["src/**/*.ts"] }""",
            "/proj/src/lib.ts" to lib,
            "/proj/src/a.ts" to twin,
            "/proj/src/b.ts" to twin,
        ),
    )

    private fun buildAndCount(vfs: InMemoryVfs): Pair<List<String>, Int> {
        val before = EagerIndexCensus.enclosingImportBuilds
        try {
            EagerIndexCensus.enclosingImportBuilds = 0
            val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
            val rows = result.diagnostics
                .filter { it.fileName?.startsWith("/proj/src/") == true }
                .map { "${it.fileName}:${it.code}" }
                .sorted()
            return rows to EagerIndexCensus.enclosingImportBuilds
        } finally {
            EagerIndexCensus.enclosingImportBuilds = before
        }
    }

    @Test
    fun `distinct importers build no structural index and both calls are checked`() {
        val (rows, builds) = buildAndCount(plainVfs())
        assert(builds == 0)
        assert(rows == listOf("/proj/src/a.ts:2345", "/proj/src/b.ts:2345"))
    }

    /**
     * Byte-identical importers share one structural key, which the parent chain makes
     * irrelevant: both files must still see `helper` as `(n: number) => number`.
     */
    @Test
    fun `byte-identical importers build no structural index and both are still checked`() {
        val (rows, builds) = buildAndCount(twinVfs())
        assert(builds == 0)
        assert(rows == listOf("/proj/src/a.ts:2345", "/proj/src/b.ts:2345"))
    }
}
