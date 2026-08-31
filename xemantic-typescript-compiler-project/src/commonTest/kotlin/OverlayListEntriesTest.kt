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

/**
 * (INC.76) `OverlayVfs.listEntries` — the override the language service was missing.
 *
 * `Vfs.listEntries`'s default body is `list(path).map { VfsEntry(it, isDirectory(it)) }`,
 * and (INC.60) added that member precisely because asking the kind per entry costs up to
 * FIVE `stat` syscalls through kotlinx-io's `metadataOrNull`. A WRAPPING Vfs that does not
 * override it inherits that body and hands the whole saving back — silently, since the
 * answers are identical. Measured over 50 directories / 2,451 entries of an
 * application-shaped project: **6.3 ms taking the kinds from the delegate's own listing
 * against 19.5 ms asking per entry** (2.6 vs 8.0 us per entry), and `OverlayVfs` is on the
 * shipped path of every `Project` build.
 *
 * ## The pins are a DIFFERENTIAL, so they need no baseline
 *
 * The override must answer exactly what the default body answers, and the default body is
 * two other public members of the same object — so each case asserts
 * `listEntries(dir) == list(dir).map { VfsEntry(it, isDirectory(it)) }`, as a set. That is
 * a property of any correct implementation rather than a transcribed expectation, which
 * matters because a divergence here is silent in the dangerous direction: a wrong kind
 * drops a file from the program or adopts a directory as a root, and (CFG.1) records that
 * this repo has no diagnostic channel that notices.
 *
 * The COST half is separate and is a count, because the answers are identical either way.
 */
class OverlayListEntriesTest {

    /** The default body of [Vfs.listEntries], written out — the differential's other arm. */
    private fun Vfs.defaultBody(path: String) =
        list(path).map { it to isDirectory(it) }.toSet()

    private fun OverlayVfs.entries(path: String) =
        listEntries(path).map { it.path to it.isDirectory }.toSet()

    private fun store() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to "{}",
            "/proj/src/a.ts" to "export const a = 1;\n",
            "/proj/src/nested/b.ts" to "export const b = 2;\n",
        ),
    )

    @Test
    fun `it answers what asking the kind per entry answers - plain`() {
        val overlay = OverlayVfs(store())
        assert(overlay.entries("/proj") == overlay.defaultBody("/proj"))
        assert(overlay.entries("/proj/src") == overlay.defaultBody("/proj/src"))
    }

    @Test
    fun `an overlay-added file in an existing directory`() {
        val overlay = OverlayVfs(store())
        overlay.put("/proj/src/c.ts", "export const c = 3;\n")
        assert(overlay.entries("/proj/src") == overlay.defaultBody("/proj/src"))
        assert("/proj/src/c.ts" to false in overlay.entries("/proj/src"))
    }

    /** A directory that exists NOWHERE but in the overlay must report as a directory. */
    @Test
    fun `an overlay-added file in a directory that exists only in the overlay`() {
        val overlay = OverlayVfs(store())
        overlay.put("/proj/src/fresh/d.ts", "export const d = 4;\n")
        assert(overlay.entries("/proj/src") == overlay.defaultBody("/proj/src"))
        assert("/proj/src/fresh" to true in overlay.entries("/proj/src"))
        assert(overlay.entries("/proj/src/fresh") == overlay.defaultBody("/proj/src/fresh"))
    }

    /** A tombstone removes the entry, exactly as it does from [Vfs.list]. */
    @Test
    fun `a tombstoned file is not listed`() {
        val overlay = OverlayVfs(store())
        overlay.remove("/proj/src/a.ts")
        assert(overlay.entries("/proj/src") == overlay.defaultBody("/proj/src"))
        assert(overlay.entries("/proj/src").none { it.first == "/proj/src/a.ts" })
    }

    /**
     * The case only the overlay can produce: an on-disk FILE the overlay has put children
     * under. `isDirectory` answers true for it (`hasOverlayChildren`), so this must too —
     * the one asymmetry that a "take the delegate's kind" override gets wrong if written
     * the obvious way.
     */
    @Test
    fun `an on-disk file the overlay has given children reports as a directory`() {
        val overlay = OverlayVfs(store())
        overlay.put("/proj/src/a.ts/inner.ts", "export const inner = 5;\n")
        assert(overlay.isDirectory("/proj/src/a.ts"))
        assert(overlay.entries("/proj/src") == overlay.defaultBody("/proj/src"))
        assert("/proj/src/a.ts" to true in overlay.entries("/proj/src"))
    }

    @Test
    fun `a directory with nothing in it and a path that is not one`() {
        val overlay = OverlayVfs(store())
        assert(overlay.entries("/proj/src/a.ts") == overlay.defaultBody("/proj/src/a.ts"))
        assert(overlay.entries("/no/such/dir") == overlay.defaultBody("/no/such/dir"))
    }

    // ---- the cost half, which the answers cannot show ------------------------

    /**
     * THE LEVER. The kinds come from the delegate's OWN listing, so nothing asks it
     * `isDirectory` per entry — which is the whole of (INC.60)'s saving and is invisible
     * in every answer above.
     */
    @Test
    fun `it takes the kinds from the delegate's listing, not by asking per entry`() {
        val counting = CountingVfs(store())
        val overlay = OverlayVfs(counting)
        val entries = overlay.listEntries("/proj/src")
        assert(entries.isNotEmpty())
        assert(counting.listEntriesCalls == 1)
        assert(counting.isDirectoryCalls == 0)
    }

    /** The control that gives that zero its meaning: the default body asks per entry. */
    @Test
    fun `negative control - the default body asks the delegate per entry`() {
        val counting = CountingVfs(store())
        val overlay = OverlayVfs(counting)
        val asked = overlay.defaultBody("/proj/src")
        assert(asked.isNotEmpty())
        assert(counting.isDirectoryCalls >= asked.size)
    }

    /**
     * And a whole build's kind questions do not GROW WITH THE PROGRAM.
     *
     * Stated as a complexity claim at two program sizes rather than as `== 0`, because a
     * build legitimately asks about specific PATHS — resolving the project argument to a
     * `tsconfig.json`, and module resolution probing directory candidates — and that
     * handful is not what (INC.60) was about. What must not happen is one question per
     * ENTRY, and only two sizes can say so: the count is a constant here and is
     * proportional to the entry count under the default body (the control below).
     */
    @Test
    fun `a project build's kind questions do not grow with the program`() {
        fun kindQuestions(files: Int): Pair<Int, Int> {
            val counting = CountingVfs(fixture(files))
            val project = Project.open("/proj", counting)
            assert(project.files.size >= files)
            return counting.isDirectoryCalls to counting.listEntriesCalls
        }
        val (smallKinds, smallLists) = kindQuestions(4)
        val (bigKinds, bigLists) = kindQuestions(64)
        assert(smallLists > 0)
        assert(bigLists == smallLists)
        assert(bigKinds == smallKinds)
    }

    /**
     * The control that gives the equality above its meaning: asking per entry, the same
     * two sizes differ by the entry count.
     */
    @Test
    fun `negative control - asking per entry grows with the program`() {
        fun asked(files: Int): Int {
            val counting = CountingVfs(fixture(files))
            OverlayVfs(counting).defaultBody("/proj/src")
            return counting.isDirectoryCalls
        }
        assert(asked(64) - asked(4) >= 60)
    }

    /** A one-directory project of [files] source files, plus its config. */
    private fun fixture(files: Int) = InMemoryVfs(
        buildMap {
            put("/proj/tsconfig.json", """{ "include": ["src/**/*.ts"] }""")
            for (i in 0 until files) put("/proj/src/f$i.ts", "export const f$i = $i;\n")
        },
    )
}
