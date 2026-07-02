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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic, filesystem-independent validation of the whole-project build
 * driver: tsconfig loading + `extends` (workstream 2), glob include/exclude
 * (workstream 2), node / nodenext module resolution incl. `.js`→`.ts` and
 * `node_modules` `exports` (workstream 3), end-to-end emit through the shared
 * compilation core (workstream 1), all over an [InMemoryVfs].
 */
class ProjectBuildTest {

    private fun sampleProject() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to """
                {
                  "extends": "./base.json",
                  "compilerOptions": { "module": "nodenext", "outDir": "./dist" },
                  "include": ["src/**/*.ts"],
                  "exclude": ["**/*.test.ts"]
                }
            """.trimIndent(),
            "/proj/base.json" to """
                { "compilerOptions": { "target": "es2020", "strict": true } }
            """.trimIndent(),
            "/proj/src/index.ts" to """
                import { add } from "./math.js";
                import { x } from "dep";
                export const r: number = add(1, x);
            """.trimIndent(),
            "/proj/src/math.ts" to "export function add(a: number, b: number): number { return a + b; }",
            "/proj/src/ignore.test.ts" to "export const bad: string = 123;",
            "/proj/node_modules/dep/package.json" to """
                { "name": "dep", "exports": { ".": { "types": "./types/index.d.ts", "import": "./index.js" } } }
            """.trimIndent(),
            "/proj/node_modules/dep/types/index.d.ts" to "export declare const x: number;",
        )
    )

    @Test
    fun loadsTsConfigWithExtendsChain() {
        val cfg = TsConfigLoader(sampleProject()).load("/proj/tsconfig.json")
        assertEquals(ScriptTarget.ES2020, cfg.options.target, "target inherited from base.json")
        assertTrue(cfg.options.strict, "strict inherited from base.json")
        assertEquals("/proj/dist", cfg.options.outDir, "outDir resolved to absolute")
        assertEquals(listOf("src/**/*.ts"), cfg.include)
        assertEquals(listOf("**/*.test.ts"), cfg.exclude)
    }

    @Test
    fun resolvesRelativeJsToTsAndNodeModulesExports() {
        val vfs = sampleProject()
        val resolver = ModuleResolver(vfs)
        // `./math.js` denotes the sibling `math.ts`.
        assertEquals("/proj/src/math.ts", resolver.resolve("./math.js", "/proj/src/index.ts"))
        // bare "dep" resolves through package.json exports "types" condition.
        assertEquals(
            "/proj/node_modules/dep/types/index.d.ts",
            resolver.resolve("dep", "/proj/src/index.ts"),
        )
        // a missing module resolves to null.
        assertNull(resolver.resolve("./nope.js", "/proj/src/index.ts"))
    }

    @Test
    fun buildsProgramHonoringGlobsAndImportGraph() {
        val result = ProjectCompiler(sampleProject()).build("/proj", noEmit = true)
        val roots = result.rootFiles.toSet()
        assertContains(roots, "/proj/src/index.ts")
        assertContains(roots, "/proj/src/math.ts")
        assertTrue("/proj/src/ignore.test.ts" !in roots, "exclude must drop *.test.ts")
        // The dependency's declaration file is pulled into the program via the import graph.
        assertContains(result.programFiles.toSet(), "/proj/node_modules/dep/types/index.d.ts")
        // Everything resolved — no dangling relative/bare specifiers.
        assertEquals(emptyList(), result.unresolved)
    }

    @Test
    fun emitsJsOutputsToOutDir() {
        val vfs = sampleProject()
        val result = ProjectCompiler(vfs).build("/proj", noEmit = false)
        // index.ts and math.ts emit under dist/ (declaration file under node_modules does not).
        val written = result.written.map { it.first }.toSet()
        assertContains(written, "/proj/dist/index.js")
        assertContains(written, "/proj/dist/math.js")
        val mathJs = vfs.readText("/proj/dist/math.js")
        assertTrue(mathJs != null && mathJs.contains("function add"), "emitted JS retains the add function: $mathJs")
        // node_modules outputs are never written.
        assertTrue(written.none { it.contains("/node_modules/") })
    }

    /** Nested source dirs + same-basename files in two directories (output-layout regressions). */
    private fun nestedProject() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to """
                {
                  "compilerOptions": { "module": "commonjs", "rootDir": "./src", "outDir": "./dist" },
                  "include": ["src/**/*.ts"]
                }
            """.trimIndent(),
            "/proj/src/index.ts" to "export const rootMarker = 1;",
            "/proj/src/helpers/util.ts" to "export const utilMarker = 2;",
            "/proj/src/locales/index.ts" to "export const localeMarker = 3;",
        ),
    )

    @Test
    fun preservesSourceSubdirectoriesUnderOutDir() {
        val vfs = nestedProject()
        val result = ProjectCompiler(vfs).build("/proj")
        val written = result.written.map { it.first }.toSet()
        assertContains(written, "/proj/dist/index.js")
        assertContains(written, "/proj/dist/helpers/util.js")
        val utilJs = vfs.readText("/proj/dist/helpers/util.js")
        assertTrue(utilJs != null && utilJs.contains("utilMarker"), "nested output holds its module: $utilJs")
        assertNull(vfs.readText("/proj/dist/util.js"), "nested output must not also land flattened at the outDir root")
    }

    @Test
    fun sameBasenameFilesInDifferentDirectoriesBothEmit() {
        val vfs = nestedProject()
        val result = ProjectCompiler(vfs).build("/proj")
        assertEquals(3, result.written.size, "all three inputs must emit: ${result.written}")
        val rootIndex = vfs.readText("/proj/dist/index.js")
        val localeIndex = vfs.readText("/proj/dist/locales/index.js")
        assertTrue(rootIndex != null && rootIndex.contains("rootMarker"), "root index.js written: $rootIndex")
        assertTrue(
            localeIndex != null && localeIndex.contains("localeMarker"),
            "locales/index.js written separately, not overwritten by a basename collision: $localeIndex",
        )
    }

    @Test
    fun writtenOutputsEndWithExactlyOneTrailingNewline() {
        val vfs = nestedProject()
        val result = ProjectCompiler(vfs).build("/proj")
        assertTrue(result.written.isNotEmpty())
        for ((path, _) in result.written) {
            val text = vfs.readText(path)
            assertTrue(
                text != null && text.endsWith("\n") && !text.endsWith("\n\n"),
                "$path must end with exactly one newline, got tail: ${text?.takeLast(3)}",
            )
        }
    }

    @Test
    fun reportsMalformedTsConfig() {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to "{ this is not valid json",
                "/proj/src/index.ts" to "export const x = 1;",
            ),
        )
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        assertTrue(
            result.diagnostics.any { it.code == 5014 },
            "malformed tsconfig should report TS5014: ${result.diagnostics.map { it.code }}",
        )
    }

    @Test
    fun reportsMissingExtends() {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to """{ "extends": "./nope.json", "include": ["src/**/*.ts"] }""",
                "/proj/src/index.ts" to "export const x = 1;",
            ),
        )
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        assertTrue(
            result.diagnostics.any { it.code == 6053 },
            "missing extends target should report TS6053: ${result.diagnostics.map { it.code }}",
        )
    }

    @Test
    fun reportsMissingTsConfig() {
        // Point at a directory with no tsconfig.json.
        val vfs = InMemoryVfs(mapOf("/proj/src/index.ts" to "export const x = 1;"))
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        assertTrue(
            result.diagnostics.any { it.code == 5083 },
            "missing tsconfig should report TS5083: ${result.diagnostics.map { it.code }}",
        )
    }
}

/** An in-memory [Vfs] for deterministic tests (and a reference Vfs implementation). */
class InMemoryVfs(initial: Map<String, String> = emptyMap()) : Vfs {
    private val files = HashMap<String, String>()

    init {
        for ((k, v) in initial) files[PathUtil.normalize(k)] = v
    }

    private fun dirs(): Set<String> {
        val s = HashSet<String>()
        s.add("/")
        for (f in files.keys) {
            var d = PathUtil.dirname(f)
            while (d.isNotEmpty()) {
                s.add(d)
                if (d == "/") break
                val p = PathUtil.dirname(d)
                if (p == d) break
                d = p
            }
        }
        return s
    }

    override fun exists(path: String): Boolean {
        val n = PathUtil.normalize(path)
        return n in files || n in dirs()
    }

    override fun isDirectory(path: String): Boolean = PathUtil.normalize(path) in dirs()

    override fun readText(path: String): String? = files[PathUtil.normalize(path)]

    override fun writeText(path: String, content: String) {
        files[PathUtil.normalize(path)] = content
    }

    override fun list(path: String): List<String> {
        val dir = PathUtil.normalize(path)
        val prefix = if (dir == "/") "/" else "$dir/"
        val children = LinkedHashSet<String>()
        for (entry in files.keys + dirs()) {
            if (entry == dir || !entry.startsWith(prefix) || entry == prefix) continue
            val rest = entry.substring(prefix.length)
            children.add(PathUtil.normalize(prefix + rest.substringBefore('/')))
        }
        children.remove(dir)
        return children.toList()
    }
}
