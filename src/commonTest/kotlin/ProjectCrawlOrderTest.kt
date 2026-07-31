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
 * Round 776 (COST.2): the project crawl's ROOT-FILE ORDER must be a property of the
 * PROJECT, never of the filesystem.
 *
 * `ProjectCompiler.walk` used to enumerate each directory in raw `readdir` order —
 * on ext4 a hash order, on another filesystem something else entirely — and that
 * order becomes the program order, which decides which file first touches a shared
 * type node and therefore how the INV.5(c) cache classifies it. Measured on the
 * compiler profile: three orders of the SAME 78 files give `typeNode.bypassed`
 * 104,162 / 103,644 / 103,272 and `mapped.keyed` 25,583 / 25,378 / 25,688 while the
 * AST (856,962 nodes) and all 46 diagnostics stay bit-identical. That is a silent
 * ±1% swing in the COST.1 gate's counters with no code change — the gate's own
 * premise is that they are machine-independent.
 *
 * The pin is DIFFERENTIAL rather than a plain `== sorted()`: the walk emits a
 * directory's own files before descending, so the sequence is a depth-first
 * alphabetical walk and not a globally sorted list. [ReversedListingVfs] hands every
 * listing back reversed, so a crawl that inherits the filesystem's order answers
 * differently for the two views and the test fails — which it does on the
 * pre-round-776 walk.
 */
class ProjectCrawlOrderTest {

    private val tsconfig =
        """{ "compilerOptions": { "strict": true, "module": "nodenext", "target": "es2020", "types": [] },
             "include": ["src/**/*"] }"""

    /** A [Vfs] view that hands every directory listing back in the opposite order. */
    private class ReversedListingVfs(private val delegate: Vfs) : Vfs {
        override fun exists(path: String): Boolean = delegate.exists(path)
        override fun isDirectory(path: String): Boolean = delegate.isDirectory(path)
        override fun readText(path: String): String? = delegate.readText(path)
        override fun writeText(path: String, content: String) = delegate.writeText(path, content)
        override fun list(path: String): List<String> = delegate.list(path).reversed()
        override fun resolveAbsolute(path: String): String = delegate.resolveAbsolute(path)
    }

    private val sources = mapOf(
        "/proj/tsconfig.json" to tsconfig,
        "/proj/src/zebra.ts" to "export const z = 1;\n",
        "/proj/src/alpha.ts" to "export const a = 1;\n",
        "/proj/src/middle/yak.ts" to "export const y = 1;\n",
        "/proj/src/middle/bee.ts" to "export const b = 1;\n",
        "/proj/src/attic/cat.ts" to "export const c = 1;\n",
    )

    private fun rootFilesOf(vfs: Vfs): List<String> =
        ProjectCompiler(vfs).build("/proj", noEmit = true).rootFiles

    @Test
    fun `root file order is depth-first alphabetical, not the filesystem's listing order`() {
        val order = rootFilesOf(InMemoryVfs(sources))
        assert(
            order == listOf(
                "/proj/src/alpha.ts",
                "/proj/src/zebra.ts",
                "/proj/src/attic/cat.ts",
                "/proj/src/middle/bee.ts",
                "/proj/src/middle/yak.ts",
            )
        )
    }

    @Test
    fun `a filesystem that lists in the opposite order produces the same program order`() {
        val forward = rootFilesOf(InMemoryVfs(sources))
        val reversed = rootFilesOf(ReversedListingVfs(InMemoryVfs(sources)))
        assert(reversed == forward)
    }

    @Test
    fun `every file still enters the program under either listing order`() {
        val forward = rootFilesOf(InMemoryVfs(sources))
        val reversed = rootFilesOf(ReversedListingVfs(InMemoryVfs(sources)))
        assert(forward.size == 5)
        assert(reversed.toSet() == forward.toSet())
    }
}
