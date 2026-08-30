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
 * (INC.60) The root-file glob asks the filesystem **once per DIRECTORY**, never
 * once per ENTRY.
 *
 * The row this pins was the third-largest of the incremental FLOOR — the per-keystroke
 * cost an editor pays — and 60-70% of it was one call: `vfs.isDirectory(entry)`, asked
 * for every entry the directory listing had just returned. On a real filesystem that is
 * a second visit to a directory the listing has already read, and kotlinx-io's
 * `metadataOrNull` answers the one boolean with up to five `stat` syscalls (see
 * `systemListEntries`). Measured on a 2,401-file project: **18-21 ms of a 29 ms walk,
 * 7.3-8.6 us per entry.**
 *
 * **Why this is a CALL-SHAPE pin and not a timing one.** A millisecond assertion over a
 * sub-second region is a coin flip (round 868), and the saving is a property of the
 * platform's filesystem rather than of this code — on the in-memory [Vfs] every listing
 * is free, so nothing here would move. What IS deterministic is *which questions the
 * walk asks*, and that is exactly what changed.
 *
 * **Why the counting [Vfs] overrides `listEntries`.** [Vfs.listEntries]'s default is
 * literally `list` + `isDirectory` per entry, so a Vfs that does not override it asks
 * the old questions BY DESIGN and this pin would be vacuous against any binary. The
 * counter therefore mirrors what [SystemVfs] does — answer the kind from the listing —
 * which is the contract the fix depends on. `SystemVfsListEntriesTest` pins the other
 * half: that the real implementation's answer agrees with `list` + `isDirectory`.
 */
class RootGlobListingTest {

    /** [InMemoryVfs] with the kind answered by the LISTING, as [SystemVfs] does. */
    private class ListingCountVfs(files: Map<String, String>) : Vfs {
        private val backing = InMemoryVfs(files)

        /** `isDirectory` asked about something that is plainly a source FILE. */
        var sourceFileProbes = 0
        var listEntriesCalls = 0
        var listCalls = 0

        override fun exists(path: String) = backing.exists(path)
        override fun readText(path: String) = backing.readText(path)
        override fun writeText(path: String, content: String) = backing.writeText(path, content)

        override fun isDirectory(path: String): Boolean {
            if (path.endsWith(".ts")) sourceFileProbes++
            return backing.isDirectory(path)
        }

        override fun list(path: String): List<String> {
            listCalls++
            return backing.list(path)
        }

        override fun listEntries(path: String): List<VfsEntry> {
            listEntriesCalls++
            // The kind comes from the listing, exactly as SystemVfs's does — so any
            // `isDirectory` this test counts is one the WALK asked for itself.
            return backing.list(path).map { VfsEntry(it, backing.isDirectory(it)) }
        }
    }

    private fun project(sources: Int): Map<String, String> = buildMap {
        put(
            "/proj/tsconfig.json",
            """{ "compilerOptions": { "strict": true }, "include": ["src/**/*"] }""",
        )
        // No imports: the crawl then resolves nothing, so every question the build
        // asks about a `.ts` path comes from the glob and from nowhere else.
        for (i in 0 until sources) put("/proj/src/m$i.ts", "export const v$i = $i;\n")
    }

    @Test
    fun `the glob never probes a listed entry a second time`() {
        val vfs = ListingCountVfs(project(sources = 40))
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)

        // Control: the walk really ran and really found the roots — a pin asserting
        // an absence is worthless beside a build that found nothing.
        assert(result.rootFiles.size == 40)
        assert(vfs.listEntriesCalls > 0)

        assert(vfs.sourceFileProbes == 0)
    }

    @Test
    fun `the probe count does not grow with the number of files in a directory`() {
        val small = ListingCountVfs(project(sources = 10))
        val large = ListingCountVfs(project(sources = 100))
        ProjectCompiler(small).build("/proj", noEmit = true)
        ProjectCompiler(large).build("/proj", noEmit = true)

        // (INC.57)'s idiom: a COUNT at two program sizes is what states a complexity
        // claim. The pre-fix walk asked one `isDirectory` per entry, so this read
        // 10 against 100.
        assert(small.sourceFileProbes == large.sourceFileProbes)
        assert(large.sourceFileProbes == 0)
    }

    @Test
    fun `the roots are the same set the listing plus a probe would have found`() {
        val files = project(sources = 12)
        val counting = ListingCountVfs(files)
        val plain = InMemoryVfs(files)

        // `plain` does NOT override listEntries, so it takes the interface default —
        // `list` + `isDirectory`, i.e. literally the pre-fix question sequence. The
        // two builds must agree on the roots AND on their ORDER, which is round 776's
        // invariant: program order fixes symbol-id allocation.
        val a = ProjectCompiler(counting).build("/proj", noEmit = true).rootFiles
        val b = ProjectCompiler(plain).build("/proj", noEmit = true).rootFiles
        assert(a == b)
        assert(a.size == 12)
    }
}
