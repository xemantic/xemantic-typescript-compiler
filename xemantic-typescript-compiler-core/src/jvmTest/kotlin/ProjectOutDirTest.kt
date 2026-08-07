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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.Test

/**
 * (AOT.4)(c), round 840(c) — the `outDir` override, and the property the AOT
 * trainer depends on: **a build told where to emit writes NOTHING into the
 * project**.
 *
 * Why it exists at all: `scripts/xtsc-aot train` now trains the JDK 25 AOT cache
 * with EMIT, because a cache trained under `--noEmit` carries no profile for the
 * Transformer or the Emitter — measured at −1,258 ms (−6.9%) on an emitting
 * compile of the 78-file tsc profile, 10 of 11 paired runs, with the check-only
 * path a wash (`docs/perf/aot-cache.md` § 9). An emitting training run is only
 * acceptable if its output goes somewhere throwaway; a trainer that dropped JS
 * into a user's `dist/` would be a defect no speed-up could pay for.
 *
 * [theProjectTreeIsUntouched] is therefore the discriminating pin: it fails the
 * moment the override stops being honoured, because the emitted tree reappears
 * under the project's own `outDir`. [theOverrideIsInertUnderNoEmit] pins the
 * other direction — the override must not make a type-check-only build start
 * writing files.
 */
class ProjectOutDirTest {

    private val project = mapOf(
        "tsconfig.json" to
            """{ "compilerOptions": { "strict": true, "rootDir": "src", "outDir": "dist" }, "include": ["src/**/*"] }""",
        "src/a.ts" to """
            export interface Point { x: number; y: number }
            export const origin: Point = { x: 0, y: 0 };
        """,
        "src/nested/b.ts" to """
            import { origin } from "../a.js";
            export const moved = { x: origin.x + 1, y: origin.y };
        """,
    )

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun withProject(block: (dir: Path, out: Path) -> Unit) {
        val dir = Files.createTempDirectory("xtsc-outdir-test")
        val out = Files.createTempDirectory("xtsc-outdir-target")
        try {
            for ((rel, content) in project) {
                val p = dir.resolve(rel)
                Files.createDirectories(p.parent)
                Files.writeString(p, content.trimIndent())
            }
            block(dir, out)
        } finally {
            dir.deleteRecursively()
            out.deleteRecursively()
        }
    }

    private fun relativeFiles(root: Path): List<String> =
        if (!Files.isDirectory(root)) emptyList()
        else Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }.map { root.relativize(it).toString() }.sorted().toList()
        }

    @Test
    fun `the project tree is untouched when an outDir is given`() = withProject { dir, out ->
        val result = ProjectCompiler(SystemVfs).build(dir.toString(), noEmit = false, outDir = out.toString())
        val emitted = relativeFiles(out)
        // The rootDir-relative shape is preserved, not flattened to basenames.
        assert(emitted == listOf("a.js", "nested/b.js"))
        assert(result.written.size == 2)
        assert(result.written.all { it.first.startsWith(out.toString()) })
        // …and the project's own outDir was never created.
        assert(!Files.exists(dir.resolve("dist")))
        assert(relativeFiles(dir) == listOf("src/a.ts", "src/nested/b.ts", "tsconfig.json"))
    }

    @Test
    fun `negative control - without the override the config's outDir is used`() = withProject { dir, out ->
        ProjectCompiler(SystemVfs).build(dir.toString(), noEmit = false)
        assert(relativeFiles(dir.resolve("dist")) == listOf("a.js", "nested/b.js"))
        assert(relativeFiles(out).isEmpty())
    }

    @Test
    fun `the override is inert under noEmit`() = withProject { dir, out ->
        val result = ProjectCompiler(SystemVfs).build(dir.toString(), noEmit = true, outDir = out.toString())
        assert(result.written.isEmpty())
        assert(relativeFiles(out).isEmpty())
        assert(!Files.exists(dir.resolve("dist")))
    }

    @Test
    fun `the emitted bytes do not depend on where they are written`() = withProject { dir, out ->
        ProjectCompiler(SystemVfs).build(dir.toString(), noEmit = false, outDir = out.toString())
        val redirected = relativeFiles(out).associateWith { Files.readString(out.resolve(it)) }
        ProjectCompiler(SystemVfs).build(dir.toString(), noEmit = false)
        val inPlace = relativeFiles(dir.resolve("dist")).associateWith { Files.readString(dir.resolve("dist").resolve(it)) }
        assert(redirected == inPlace)
    }
}
