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
import kotlin.test.Test

/**
 * INV.1(b) (round 493): the import-graph crawl reads+decodes each frontier's
 * files on the IO dispatcher and runs the specifier-extraction parse on
 * Default, concurrently under a bounded `flatMapMerge` — but EMISSION must
 * stay in deterministic first-discovery order (never completion order),
 * because the program file order becomes the binder's file order, which fixes
 * global symbol-id allocation (the documented ~350-test reshuffle on drift).
 *
 * These tests exercise frontiers WIDER than the concurrency bound (16), where
 * completion-order leakage would actually scramble the result, plus the two
 * unreadable-file asymmetries the sequential crawl had: an unreadable SEED
 * enters the program as "" (read unconditionally), an unreadable DISCOVERED
 * file is silently skipped (it resolved, so it is not `unresolved` either).
 */
class Inv1ConcurrentCrawlTest {

    /** index imports m00..m(n-1); each mNN imports sNN; m00 additionally imports s19. */
    private fun wideProject(n: Int, twoLevels: Boolean = false): Map<String, String> {
        val files = mutableMapOf(
            "/w/tsconfig.json" to """{ "files": ["src/index.ts"] }""",
        )
        val imports = StringBuilder()
        for (i in 0 until n) {
            val name = "m" + i.toString().padStart(2, '0')
            if (twoLevels) {
                val sub = "s" + i.toString().padStart(2, '0')
                val extra = if (i == 0) "import \"./s19\";\n" else ""
                files["/w/src/$name.ts"] = "import \"./$sub\";\n${extra}export const $name = $i;"
                files["/w/src/$sub.ts"] = "export const $sub = $i;"
            } else {
                files["/w/src/$name.ts"] = "export const $name = $i;"
            }
            imports.append("import \"./$name\";\n")
        }
        files["/w/src/index.ts"] = imports.toString() + "export const i = 1;"
        return files
    }

    @Test
    fun `a frontier wider than the concurrency bound preserves specifier order`() {
        val result = ProjectCompiler(InMemoryVfs(wideProject(30))).build("/w", noEmit = true)
        val expected = listOf("/w/src/index.ts") +
            (0 until 30).map { "/w/src/m" + it.toString().padStart(2, '0') + ".ts" }
        assert(result.programFiles == expected)
    }

    @Test
    fun `cross-frontier discovery keeps first-discovery positions at width`() {
        val result = ProjectCompiler(InMemoryVfs(wideProject(20, twoLevels = true))).build("/w", noEmit = true)
        val frontier1 = (0 until 20).map { "/w/src/m" + it.toString().padStart(2, '0') + ".ts" }
        // Frontier 2 in discovery order: m00's imports are s00 then s19 (so s19
        // lands SECOND, not where m19 would have discovered it), then s01..s18
        // (m19's own ./s19 dedups against the earlier discovery).
        val frontier2 = listOf("/w/src/s00.ts", "/w/src/s19.ts") +
            (1 until 19).map { "/w/src/s" + it.toString().padStart(2, '0') + ".ts" }
        assert(result.programFiles == listOf("/w/src/index.ts") + frontier1 + frontier2)
    }

    @Test
    fun `a deep import chain crawls in BFS order`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/d/tsconfig.json" to """{ "files": ["src/a.ts"] }""",
                "/d/src/a.ts" to """import "./b"; export const a = 1;""",
                "/d/src/b.ts" to """import "./c"; export const b = 1;""",
                "/d/src/c.ts" to """import "./d"; export const c = 1;""",
                "/d/src/d.ts" to """import "./e"; export const d = 1;""",
                "/d/src/e.ts" to "export const e = 1;",
            )
        )
        val result = ProjectCompiler(vfs).build("/d", noEmit = true)
        assert(result.programFiles == listOf("/d/src/a.ts", "/d/src/b.ts", "/d/src/c.ts", "/d/src/d.ts", "/d/src/e.ts"))
    }

    @Test
    fun `an unreadable discovered file is skipped - not unresolved - and siblings still load`() {
        val base = InMemoryVfs(
            mapOf(
                "/u/tsconfig.json" to """{ "files": ["src/index.ts"] }""",
                "/u/src/index.ts" to """
                    import "./gone";
                    import "./ok";
                    export const i = 1;
                """.trimIndent(),
                "/u/src/gone.ts" to "export const g = 1;",
                "/u/src/ok.ts" to "export const o = 1;",
            )
        )
        val vfs = UnreadableVfs(base, setOf("/u/src/gone.ts"))
        val result = ProjectCompiler(vfs).build("/u", noEmit = true)
        assert(result.programFiles == listOf("/u/src/index.ts", "/u/src/ok.ts"))
        // `./gone` RESOLVED (the file exists) — it is unreadable, not unresolved.
        assert(result.unresolved.isEmpty())
    }

    @Test
    fun `an unreadable seed still enters the program as an empty file`() {
        val base = InMemoryVfs(
            mapOf(
                "/s/tsconfig.json" to """{ "files": ["src/index.ts", "src/other.ts"] }""",
                "/s/src/index.ts" to "export const i = 1;",
                "/s/src/other.ts" to "export const o = 1;",
            )
        )
        val vfs = UnreadableVfs(base, setOf("/s/src/index.ts"))
        val result = ProjectCompiler(vfs).build("/s", noEmit = true)
        // Seeds are read unconditionally: the unreadable seed keeps its program
        // slot (as ""), unlike an unreadable DISCOVERED file (skipped entirely).
        assert(result.programFiles == listOf("/s/src/index.ts", "/s/src/other.ts"))
    }

    @Test
    fun `wide-graph builds are byte-deterministic across repeated runs`() {
        val files = wideProject(30, twoLevels = false)
        val first = ProjectCompiler(InMemoryVfs(files)).build("/w", noEmit = true)
        repeat(2) {
            val again = ProjectCompiler(InMemoryVfs(files)).build("/w", noEmit = true)
            assert(again.programFiles == first.programFiles)
            assert(again.diagnostics == first.diagnostics)
            assert(again.unresolved == first.unresolved)
        }
        assert(!first.programFiles.isEmpty())
    }
}

/**
 * A delegating [Vfs] whose [readText] fails for [unreadable] paths while
 * [exists]/[isDirectory] still see them — models a resolvable-but-unreadable
 * file (permissions, races), the case where the crawl's read is what fails.
 * Concurrent [readText] calls (the INV.1(b) batches) stay pure lookups.
 */
private class UnreadableVfs(
    private val delegate: Vfs,
    private val unreadable: Set<String>,
) : Vfs {
    override fun exists(path: String): Boolean = delegate.exists(path)
    override fun isDirectory(path: String): Boolean = delegate.isDirectory(path)
    override fun readText(path: String): String? =
        if (PathUtil.normalize(path) in unreadable) null else delegate.readText(path)
    override fun writeText(path: String, content: String) = delegate.writeText(path, content)
    override fun list(path: String): List<String> = delegate.list(path)
}
