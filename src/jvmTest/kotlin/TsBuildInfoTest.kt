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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * INV.7(d3): the cross-process `.xtsbuildinfo` contract — a cold start reusing
 * persisted state must produce diagnostics EQUAL to a fresh full build
 * (the (7d1) INCREMENTAL ≡ FULL discipline), and every validation failure
 * (compiler build id mismatch, dirty/unknown id, config change, corrupt file,
 * new/deleted files) must fall back to a full build, never reuse.
 */
class TsBuildInfoTest {

    private val cleanId = "test-build-0000000000000000000000000000000000000000"

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

    private fun buildInfo(dir: Path, id: String = cleanId): Pair<ProjectCompiler.Result, List<String>> {
        val log = mutableListOf<String>()
        val result = TsBuildInfo.build(SystemVfs, dir.toString(), noEmit = true, buildId = id, log = log::add)
        return result to log
    }

    private fun fullBuild(dir: Path): ProjectCompiler.Result =
        ProjectCompiler(SystemVfs).build(dir.toString(), noEmit = true)

    private fun withProject(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("xtsc-buildinfo-test")
        try {
            writeProject(dir, baseProject)
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun infoFile(dir: Path): Path = dir.resolve("tsconfig.xtsbuildinfo")

    @Test
    fun `first build persists buildinfo and an unchanged cold start rechecks zero files with equal diagnostics`() =
        withProject { dir ->
            val (first, firstLog) = buildInfo(dir)
            val firstBuildWasFull = firstLog.any { "full build" in it }
            assertTrue(firstBuildWasFull)
            assertTrue(Files.exists(infoFile(dir)), "tsconfig.xtsbuildinfo should be written next to tsconfig.json")

            val (second, secondLog) = buildInfo(dir)
            val zeroRecheck = secondLog.any { "incremental recheck of 0/" in it }
            assertTrue(zeroRecheck, "unchanged inputs must skip the whole check: $secondLog")
            assertEquals(diagKeys(fullBuild(dir).diagnostics), diagKeys(second.diagnostics))
            assertEquals(diagKeys(first.diagnostics), diagKeys(second.diagnostics))
        }

    @Test
    fun `a local edit rechecks only its closure and matches the full build`() = withProject { dir ->
        buildInfo(dir)
        // c.ts is a leaf importer: the old TS2322 goes away, a new one appears.
        edit(dir, "src/c.ts", """
            import { shift } from "./b.js";
            import { origin } from "./a.js";
            export const moved = shift(origin);
            const label: string = "now fine";
            const fresh: number = "still bad";
        """)
        val (result, log) = buildInfo(dir)
        val incremental = log.any { "incremental recheck of 1/" in it }
        assertTrue(incremental, "a leaf edit should recheck exactly 1 file: $log")
        assertEquals(diagKeys(fullBuild(dir).diagnostics), diagKeys(result.diagnostics))
        val hasFreshError = result.diagnostics.any { it.code == 2322 && it.fileName?.endsWith("c.ts") == true }
        assertTrue(hasFreshError)
    }

    @Test
    fun `an out-of-closure diagnostic is kept verbatim across the serialization round trip`() = withProject { dir ->
        buildInfo(dir)
        // Editing a.ts rechecks a/b/c (all importers) — so instead edit NOTHING
        // and verify c.ts's stored TS2322 (out of the empty partition) survives.
        val (result, _) = buildInfo(dir)
        val kept = result.diagnostics.filter { it.code == 2322 }
        assertEquals(1, kept.size)
        val line = kept.single().line
        assertEquals(4, line, "round-tripped diagnostic must keep its position fields")
    }

    @Test
    fun `a different compiler build id refuses reuse and rewrites the buildinfo`() = withProject { dir ->
        buildInfo(dir, id = "compiler-A")
        val (result, log) = buildInfo(dir, id = "compiler-B")
        val refused = log.any { "build id mismatch" in it }
        assertTrue(refused, "a stale-compiler buildinfo must never be reused: $log")
        assertEquals(diagKeys(fullBuild(dir).diagnostics), diagKeys(result.diagnostics))
        val rewritten = Files.readString(infoFile(dir))
        assertTrue("compiler-B" in rewritten)
    }

    @Test
    fun `negative control - dirty and unknown build ids never persist nor reuse`() = withProject { dir ->
        buildInfo(dir, id = "abc123.dirty")
        assertFalse(Files.exists(infoFile(dir)), "a dirty build must not persist buildinfo")
        buildInfo(dir, id = "unknown")
        assertFalse(Files.exists(infoFile(dir)), "an unknown build id must not persist buildinfo")
        assertFalse(TsBuildInfo.buildIdReusable("abc123.dirty"))
        assertFalse(TsBuildInfo.buildIdReusable("unknown"))
        assertTrue(TsBuildInfo.buildIdReusable(cleanId))
    }

    @Test
    fun `a tsconfig change is non-local and forces a full build`() = withProject { dir ->
        buildInfo(dir)
        edit(dir, "tsconfig.json", """{ "compilerOptions": { "strict": true, "noUnusedLocals": false }, "include": ["src/**/*"] }""")
        val (result, log) = buildInfo(dir)
        val fullFallback = log.any { "non-local change" in it }
        assertTrue(fullFallback, "a config edit must force a full build: $log")
        assertEquals(diagKeys(fullBuild(dir).diagnostics), diagKeys(result.diagnostics))
    }

    @Test
    fun `a NEW file invisible to stored hashes is caught by program-shape validation`() = withProject { dir ->
        buildInfo(dir)
        // No stored file changes, but the include glob now picks up a new file
        // with its own error — the empty-closure partition build must be
        // rejected by the outcome check and fall back to a full build.
        edit(dir, "src/d.ts", """
            export const wrong: number = "not a number";
        """)
        val (result, log) = buildInfo(dir)
        val shapeFallback = log.any { "program shape changed" in it }
        assertTrue(shapeFallback, "a newly-globbed file must invalidate the incremental outcome: $log")
        val newFileError = result.diagnostics.any { it.code == 2322 && it.fileName?.endsWith("d.ts") == true }
        assertTrue(newFileError)
        assertEquals(diagKeys(fullBuild(dir).diagnostics), diagKeys(result.diagnostics))
    }

    @Test
    fun `a deleted program file forces a full build`() = withProject { dir ->
        buildInfo(dir)
        Files.delete(dir.resolve("src/c.ts"))
        val (result, log) = buildInfo(dir)
        val fullFallback = log.any { "non-local change" in it }
        assertTrue(fullFallback, "a deleted file must force a full build: $log")
        assertEquals(diagKeys(fullBuild(dir).diagnostics), diagKeys(result.diagnostics))
        val staleDiagnostic = result.diagnostics.any { it.fileName?.endsWith("c.ts") == true }
        assertFalse(staleDiagnostic, "no diagnostic may survive for a deleted file")
    }

    @Test
    fun `a corrupt buildinfo file falls back to a full build and is rewritten`() = withProject { dir ->
        buildInfo(dir)
        Files.writeString(infoFile(dir), "{ not json ][")
        val (result, log) = buildInfo(dir)
        val fullFallback = log.any { "none — full build" in it }
        assertTrue(fullFallback, "corrupt buildinfo must read as absent: $log")
        assertEquals(diagKeys(fullBuild(dir).diagnostics), diagKeys(result.diagnostics))
        val rereadable = TsBuildInfo.read(SystemVfs, infoFile(dir).toString())
        assertEquals(cleanId, rereadable?.buildId, "the corrupt file must be replaced by a valid one")
    }

    @Test
    fun `content hash distinguishes different content and is stable for equal content`() {
        val a = TsBuildInfo.contentHash("export const x = 1;")
        val b = TsBuildInfo.contentHash("export const x = 2;")
        val a2 = TsBuildInfo.contentHash("export const x = 1;")
        assertEquals(a, a2)
        assertNotEquals(a, b)
        assertNotEquals(TsBuildInfo.contentHash(""), TsBuildInfo.contentHash(" "))
    }

    @Test
    fun `negative control - a bare source file build has no buildinfo path`() {
        assertEquals(null, TsBuildInfo.infoPath("/proj/foo.ts"))
        assertEquals("/proj/tsconfig.xtsbuildinfo", TsBuildInfo.infoPath("/proj/tsconfig.json"))
    }
}
