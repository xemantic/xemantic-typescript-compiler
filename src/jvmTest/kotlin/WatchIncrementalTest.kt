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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * INV.7(d1): the incremental-recheck contract — an incremental (partitioned)
 * recheck of a change's reverse-dependency closure, merged with the previous
 * build's out-of-closure diagnostics, must equal a fresh FULL build
 * byte-for-byte. Any divergence is a closure/eligibility bug, never an
 * acceptable delta (the INV.6 partition-equivalence discipline).
 */
class WatchIncrementalTest {

    private fun diagKeys(diags: List<Diagnostic>): List<String> =
        diags.map { "${it.fileName}|${it.start}|${it.length}|${it.code}|${it.message}" }.sorted()

    private fun writeProject(dir: Path, files: Map<String, String>) {
        for ((rel, content) in files) {
            val p = dir.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, content.trimIndent())
        }
    }

    private val baseProject = mapOf(
        "tsconfig.json" to """{ "compilerOptions": { "strict": true }, "include": ["src/**/*"] }""",
        "src/a.ts" to """
            export interface Point { x: number; y: number }
            export const origin: Point = { x: 0, y: 0 };
        """,
        "src/b.ts" to """
            import { Point, origin } from "./a.js";
            export function shift(p: Point): Point { return { x: p.x + 1, y: p.y }; }
            export const start: Point = origin;
        """,
        "src/c.ts" to """
            import { shift } from "./b.js";
            import { origin } from "./a.js";
            export const moved = shift(origin);
            const label: string = 42;
        """,
    )

    private fun edit(dir: Path, rel: String, content: String) {
        Files.writeString(dir.resolve(rel), content.trimIndent())
    }

    /** Runs the exact watch-loop incremental protocol; returns (merged, full) diag keys. */
    private fun incrementalVsFull(dir: Path, changed: Set<String>): Pair<List<String>, List<String>> {
        val project = dir.toString()
        val prev = ProjectCompiler(SystemVfs).build(project, noEmit = true)
        val changedAbs = changed.map { dir.resolve(it).toString() }.toSet()
        assertTrue(
            WatchIncremental.incrementalEligible(changedAbs, prev) { SystemVfs.readText(it) },
            "fixture change should be incremental-eligible",
        )
        val closure = WatchIncremental.recheckClosure(changedAbs, prev.importEdges)
        val fresh = ProjectCompiler(SystemVfs).build(project, noEmit = true, recheckOnly = closure)
        assertTrue(WatchIncremental.incrementalOutcomeValid(changedAbs, prev, fresh))
        val merged = WatchIncremental.mergeDiagnostics(prev, fresh.diagnostics, closure)
        val full = ProjectCompiler(SystemVfs).build(project, noEmit = true)
        return diagKeys(merged) to diagKeys(full.diagnostics)
    }

    private fun withProject(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("xtsc-incr-test")
        try {
            writeProject(dir, baseProject)
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `editing a leaf importer rechecks only itself and matches the full build`() = withProject { dir ->
        edit(dir, "src/c.ts", """
            import { shift } from "./b.js";
            import { origin } from "./a.js";
            export const moved = shift(origin);
            const label: string = "now fine";
            const fresh: number = "still bad";
        """)
        val (merged, full) = incrementalVsFull(dir, setOf("src/c.ts"))
        assertEquals(full, merged)
        assertTrue(full.any { "still bad" in it || it.contains("TS2322") || it.contains("|2322|") })
    }

    @Test
    fun `editing a root export type rechecks the whole closure and matches the full build`() = withProject { dir ->
        // Point loses `y` — b.ts's literal and c.ts's usage both change verdicts.
        edit(dir, "src/a.ts", """
            export interface Point { x: number }
            export const origin: Point = { x: 0 };
        """)
        val (merged, full) = incrementalVsFull(dir, setOf("src/a.ts"))
        assertEquals(full, merged)
    }

    @Test
    fun `the closure follows reverse edges transitively`() {
        val edges = listOf("/p/b.ts" to "/p/a.ts", "/p/c.ts" to "/p/b.ts", "/p/d.ts" to "/p/x.ts")
        assertEquals(
            setOf("/p/a.ts", "/p/b.ts", "/p/c.ts"),
            WatchIncremental.recheckClosure(setOf("/p/a.ts"), edges),
        )
        assertEquals(
            setOf("/p/b.ts", "/p/c.ts"),
            WatchIncremental.recheckClosure(setOf("/p/b.ts"), edges),
        )
    }

    @Test
    fun `negative control - script, dts, config, new, deleted, and declare-global changes are not eligible`() = withProject { dir ->
        val prev = ProjectCompiler(SystemVfs).build(dir.toString(), noEmit = true)
        val a = dir.resolve("src/a.ts").toString()
        fun eligible(paths: Set<String>, read: (String) -> String? = { SystemVfs.readText(it) }) =
            WatchIncremental.incrementalEligible(paths, prev, read)
        assertFalse(eligible(setOf(dir.resolve("tsconfig.json").toString())))
        assertFalse(eligible(setOf(dir.resolve("src/new.ts").toString())))            // not in program
        assertFalse(eligible(setOf(a)) { null })                                      // deleted
        assertFalse(eligible(setOf(a)) { "declare global { interface X {} }" })       // global augmentation
        assertFalse(eligible(setOf("$a.d.ts")))                                       // .d.ts (also not in program)
        assertTrue(eligible(setOf(a)))                                                // the positive control
    }
}
