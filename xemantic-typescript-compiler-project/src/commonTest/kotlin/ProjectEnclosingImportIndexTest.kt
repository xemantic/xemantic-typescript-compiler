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
 * (INC.81) `Checker.enclosingImportIndex` stores ONE entry per key directly and promotes
 * to a list only when a second statement claims the same key — and this pins the
 * promotion, which no real project reaches.
 *
 * ## Why the promotion needs a fixture rather than a corpus
 *
 * The index is keyed STRUCTURALLY: `ImportSpecifier` is a data class whose components
 * include `pos` and `end`, so two files reach the same key exactly when they spell the
 * same import at the same offsets. Censused on a 2,401-file project the build inserts
 * **9,401 specifiers under 9,401 distinct keys and not one key is reached from two
 * files** — which is what made the per-key `MutableList` pure waste, and equally what
 * means nothing in this repo exercises the multi-entry path. Two byte-identical files
 * do.
 *
 * ## What the pins are
 *
 * `enclosingImportMultiFileKeys` is the regime: 0 for the ordinary shape (so the
 * one-entry representation really is what a project uses) and non-zero for the twin
 * fixture (so the promotion really is reached). The VALUE half is that both files still
 * resolve their import — the index feeds `resolveAlias`, so a lost or mis-promoted entry
 * degrades an imported callee to `any` and DELETES a diagnostic, which is the silent
 * direction.
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
        EagerIndexCensus.enclosingImportMultiFileKeys = -1
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val rows = result.diagnostics
            .filter { it.fileName?.startsWith("/proj/src/") == true }
            .map { "${it.fileName}:${it.code}" }
            .sorted()
        return rows to EagerIndexCensus.enclosingImportMultiFileKeys
    }

    @Test
    fun `distinct importers reach no key twice and both calls are checked`() {
        val (rows, multi) = buildAndCount(plainVfs())
        assert(multi == 0)
        assert(rows == listOf("/proj/src/a.ts:2345", "/proj/src/b.ts:2345"))
    }

    /**
     * THE PROMOTION PIN. Byte-identical importers share one key, so the index must hold
     * BOTH entries — and both files must still see `helper` as `(n: number) => number`,
     * which is the half a mis-promotion loses silently.
     */
    @Test
    fun `byte-identical importers share a key and both are still checked`() {
        val (rows, multi) = buildAndCount(twinVfs())
        assert(multi > 0)
        assert(rows == listOf("/proj/src/a.ts:2345", "/proj/src/b.ts:2345"))
    }
}
