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
import com.xemantic.typescript.compiler.Vfs
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [Project]'s position conversion: that it agrees with the compiler's own
 * diagnostics, that it reads the OVERLAY rather than the disk, and that its cache
 * is dropped by an edit.
 *
 * The first of those is the pin that makes [LineMap] trustworthy at all — it is a
 * reimplementation of `private` compiler arithmetic, so the only honest check is a
 * differential against a real diagnostic, and it is deliberately taken at a
 * position that is neither line 1 nor character 1 (a pin on the origin passes for
 * any off-by-anything). The second is the pin that would otherwise fail silently:
 * a conversion reading the backing store returns a plausible coordinate about the
 * wrong text, which is invisible to every assertion that does not stage a
 * disagreement on purpose.
 */
class ProjectPositionTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private fun projectWith(aSource: String): InMemoryVfs = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            "/proj/src/a.ts" to aSource,
        ),
    )

    private fun open(vfs: Vfs): Project = Project.open("/proj", vfs)

    // --- the differential against the compiler's own line arithmetic -----------

    /**
     * A file whose error is deliberately deep in it: the leading comment and the
     * indented function push the failing assignment off both the first line and the
     * first column.
     */
    private val erroringSource = """
        // a leading comment, so nothing interesting lives on line 1
        export function twice(n: number): number {
            return n * 2;
        }

        export const bad: number = "text";
    """.trimIndent() + "\n"

    @Test
    fun `a diagnostic's own line and character are what this map computes from its start`() {
        val project = open(projectWith(erroringSource))
        val diagnostics = project.diagnostics("/proj/src/a.ts")
            .filter { it.start != null && it.line != null && it.character != null }
        // Vacuity guard: without a diagnostic away from the origin this pin would
        // pass for a map that ignored its input entirely.
        assert(diagnostics.any { it.line!! > 1 && it.character!! > 1 })
        for (diagnostic in diagnostics) {
            val computed = project.positionAt("/proj/src/a.ts", diagnostic.start!!)
            assert(computed == TextPosition(diagnostic.line!!, diagnostic.character!!))
        }
        // ... and the inverse direction lands back on the byte the compiler named.
        val sample = diagnostics.first { it.line!! > 1 && it.character!! > 1 }
        assert(project.offsetAt("/proj/src/a.ts", sample.line!!, sample.character!!) == sample.start)
    }

    // --- the overlay ------------------------------------------------------------

    /**
     * The same offset in two texts of DIFFERENT line shapes.
     *
     * On disk, line 1 is short and offset 12 is on line 2; in the buffer, line 1 is
     * long and offset 12 is still on line 1. So a conversion reading the wrong text
     * cannot accidentally agree: the two answers differ in the LINE, not merely in
     * the column.
     */
    private val onDisk = "let a = 1;\nlet b = 2;\n"
    private val inBuffer = "let alpha = 1234567;\nlet b = 2;\n"
    private val probeOffset = 12

    @Test
    fun `a position query answers about the buffer and not about the disk`() {
        val backing = projectWith(onDisk)
        val project = open(backing)
        // Negative control FIRST: the pre-edit answer is the other one, so neither an
        // always-disk nor an always-buffer implementation satisfies both halves.
        assert(project.positionAt("/proj/src/a.ts", probeOffset) == TextPosition(2, 2))
        project.updateFile("/proj/src/a.ts", inBuffer)
        assert(project.positionAt("/proj/src/a.ts", probeOffset) == TextPosition(1, 13))
        // The disk still holds the short text, which is what makes the second answer
        // attributable to the overlay rather than to a changed fixture.
        assert(backing.readText("/proj/src/a.ts") == onDisk)
    }

    @Test
    fun `an overlay-only file has positions although it exists nowhere on disk`() {
        val project = open(projectWith(onDisk))
        assert(project.positionAt("/proj/src/new.ts", 0) == null)
        project.updateFile("/proj/src/new.ts", "let x = 1;\nlet y = 2;\n")
        assert(project.positionAt("/proj/src/new.ts", 11) == TextPosition(2, 1))
    }

    @Test
    fun `a deleted file has no positions`() {
        val project = open(projectWith(onDisk))
        assert(project.positionAt("/proj/src/a.ts", 0) == TextPosition(1, 1))
        project.deleteFile("/proj/src/a.ts")
        assert(project.positionAt("/proj/src/a.ts", 0) == null)
        assert(project.offsetAt("/proj/src/a.ts", 1, 1) == null)
    }

    // --- caching ----------------------------------------------------------------

    @Test
    fun `a position query does not compile`() {
        // One text read and no directory listing: a build lists the project tree and
        // reads every lib, so the counters separate the two without timing anything.
        val counting = CountingVfs(projectWith(onDisk))
        val project = open(counting)
        assert(project.positionAt("/proj/src/a.ts", probeOffset) == TextPosition(2, 2))
        assert(counting.lists == 0)
        assert(counting.reads == 1)
    }

    @Test
    fun `a second position query does not re-read the file`() {
        val counting = CountingVfs(projectWith(onDisk))
        val project = open(counting)
        project.positionAt("/proj/src/a.ts", 0)
        val afterFirst = counting.reads
        project.positionAt("/proj/src/a.ts", 5)
        project.offsetAt("/proj/src/a.ts", 2, 1)
        assert(counting.reads == afterFirst)
    }

    @Test
    fun `an edit drops the cached line index`() {
        val project = open(projectWith(onDisk))
        assert(project.positionAt("/proj/src/a.ts", probeOffset) == TextPosition(2, 2))
        // An offset that exists only in the longer buffer: refused while the stale
        // index is in force, accepted once it has been rebuilt. That separates "the
        // index was rebuilt" from "the answer happens to be the same", which a
        // coordinate comparison alone cannot.
        val pastDiskEnd = onDisk.length + 5
        assertFailsWith<IllegalArgumentException> {
            project.positionAt("/proj/src/a.ts", pastDiskEnd)
        }
        project.updateFile("/proj/src/a.ts", inBuffer)
        assert(project.positionAt("/proj/src/a.ts", pastDiskEnd) != null)
        assert(project.positionAt("/proj/src/a.ts", probeOffset) == TextPosition(1, 13))
    }

    @Test
    fun `editing one file leaves another file's index alone`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                "/proj/src/a.ts" to onDisk,
                "/proj/src/b.ts" to "let b1 = 1;\nlet b2 = 2;\n",
            ),
        )
        val counting = CountingVfs(vfs)
        val project = open(counting)
        project.positionAt("/proj/src/b.ts", 0)
        val afterB = counting.reads
        project.updateFile("/proj/src/a.ts", inBuffer)
        project.positionAt("/proj/src/b.ts", 12)
        assert(counting.reads == afterB)
    }

    // --- paths and lifecycle ----------------------------------------------------

    @Test
    fun `a position query accepts an unnormalized path`() {
        // Same normalize-and-absolutize discipline as `updateFile`, so a host may
        // pass whichever spelling it holds.
        val project = open(projectWith(onDisk))
        assert(project.positionAt("/proj/src/./a.ts", probeOffset) == TextPosition(2, 2))
        project.updateFile("/proj/src/nested/../a.ts", inBuffer)
        assert(project.positionAt("/proj/src/a.ts", probeOffset) == TextPosition(1, 13))
    }

    @Test
    fun `an unknown file has no positions`() {
        val project = open(projectWith(onDisk))
        assert(project.positionAt("/proj/src/nope.ts", 0) == null)
        assert(project.offsetAt("/proj/src/nope.ts", 1, 1) == null)
    }

    @Test
    fun `a position query after close throws`() {
        val project = open(projectWith(onDisk))
        project.positionAt("/proj/src/a.ts", 0)
        project.close()
        assertFailsWith<IllegalStateException> { project.positionAt("/proj/src/a.ts", 0) }
        assertFailsWith<IllegalStateException> { project.offsetAt("/proj/src/a.ts", 1, 1) }
    }
}
