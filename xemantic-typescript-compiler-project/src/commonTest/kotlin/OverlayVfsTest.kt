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
 * The overlay's six [com.xemantic.typescript.compiler.Vfs] answers, pinned one at a
 * time.
 *
 * [ProjectTest] pins them through a real compile, which is the test that matters
 * and also the slow and indirect one; these pin the same rules directly, so a
 * broken rule names itself instead of surfacing as a missing diagnostic three
 * layers up.
 */
class OverlayVfsTest {

    private fun backing() = InMemoryVfs(
        mapOf(
            "/proj/src/a.ts" to "a",
            "/proj/src/b.ts" to "b",
        ),
    )

    @Test
    fun `a fresh overlay is transparent`() {
        val overlay = OverlayVfs(backing())
        assert(overlay.isEmpty)
        assert(overlay.readText("/proj/src/a.ts") == "a")
        assert(overlay.exists("/proj/src/a.ts"))
        assert(overlay.isDirectory("/proj/src"))
        assert(overlay.list("/proj/src") == listOf("/proj/src/a.ts", "/proj/src/b.ts"))
        assert(!overlay.exists("/proj/src/c.ts"))
    }

    @Test
    fun `overlay text shadows the backing store`() {
        val overlay = OverlayVfs(backing())
        overlay.put("/proj/src/a.ts", "edited")
        assert(overlay.readText("/proj/src/a.ts") == "edited")
        assert(!overlay.isEmpty)
    }

    @Test
    fun `a tombstone hides a file that is really there`() {
        val overlay = OverlayVfs(backing())
        overlay.remove("/proj/src/a.ts")
        assert(overlay.readText("/proj/src/a.ts") == null)
        assert(!overlay.exists("/proj/src/a.ts"))
        assert(overlay.list("/proj/src") == listOf("/proj/src/b.ts"))
    }

    @Test
    fun `an added file exists and is listed`() {
        val overlay = OverlayVfs(backing())
        overlay.put("/proj/src/c.ts", "c")
        assert(overlay.exists("/proj/src/c.ts"))
        assert(overlay.readText("/proj/src/c.ts") == "c")
        assert(overlay.list("/proj/src") == listOf("/proj/src/a.ts", "/proj/src/b.ts", "/proj/src/c.ts"))
    }

    @Test
    fun `an added file makes its ancestor directories exist`() {
        // The crawl asks `isDirectory` before it descends, and module resolution
        // walks directory candidates — so a file added under a directory the backing
        // store has never heard of is invisible unless both answer for it.
        val overlay = OverlayVfs(backing())
        overlay.put("/proj/src/lib/deep/d.ts", "d")
        assert(overlay.isDirectory("/proj/src/lib"))
        assert(overlay.isDirectory("/proj/src/lib/deep"))
        assert(overlay.exists("/proj/src/lib"))
        assert(!overlay.isDirectory("/proj/src/lib/deep/d.ts"))
        assert(overlay.list("/proj/src") == listOf("/proj/src/a.ts", "/proj/src/b.ts", "/proj/src/lib"))
        assert(overlay.list("/proj/src/lib") == listOf("/proj/src/lib/deep"))
        assert(overlay.list("/proj/src/lib/deep") == listOf("/proj/src/lib/deep/d.ts"))
    }

    @Test
    fun `putting a tombstoned path back revives it`() {
        val overlay = OverlayVfs(backing())
        overlay.remove("/proj/src/a.ts")
        overlay.put("/proj/src/a.ts", "revived")
        assert(overlay.exists("/proj/src/a.ts"))
        assert(overlay.readText("/proj/src/a.ts") == "revived")
    }

    @Test
    fun `removing an overlay-only path leaves it absent`() {
        val overlay = OverlayVfs(backing())
        overlay.put("/proj/src/c.ts", "c")
        overlay.remove("/proj/src/c.ts")
        assert(!overlay.exists("/proj/src/c.ts"))
        assert(overlay.readText("/proj/src/c.ts") == null)
        assert(overlay.list("/proj/src") == listOf("/proj/src/a.ts", "/proj/src/b.ts"))
    }

    @Test
    fun `clear makes the overlay transparent again`() {
        val overlay = OverlayVfs(backing())
        overlay.put("/proj/src/a.ts", "edited")
        overlay.remove("/proj/src/b.ts")
        overlay.clear()
        assert(overlay.isEmpty)
        assert(overlay.readText("/proj/src/a.ts") == "a")
        assert(overlay.readText("/proj/src/b.ts") == "b")
    }

    @Test
    fun `queries are normalized before they meet overlay keys`() {
        val overlay = OverlayVfs(backing())
        overlay.put("/proj/src/c.ts", "c")
        assert(overlay.readText("/proj/src/./c.ts") == "c")
        assert(overlay.readText("/proj/src/lib/../c.ts") == "c")
        assert(overlay.exists("/proj//src/c.ts"))
    }

    @Test
    fun `a write goes to the backing store rather than the overlay`() {
        val store = backing()
        val overlay = OverlayVfs(store)
        overlay.writeText("/proj/out/x.js", "js")
        assert(store.readText("/proj/out/x.js") == "js")
        assert(overlay.isEmpty)
    }

    @Test
    fun `listing is sorted whatever order the delegate returns`() {
        // Program order decides which file first touches a shared type node - an
        // unsorted crawl makes the compiler's own cost counters a property of the
        // filesystem, so the union is sorted here as well as in the crawl.
        val unsorted = object : Vfs {
            override fun exists(path: String): Boolean = true
            override fun isDirectory(path: String): Boolean = path == "/proj/src"
            override fun readText(path: String): String? = ""
            override fun writeText(path: String, content: String) {}
            override fun list(path: String): List<String> =
                listOf("/proj/src/z.ts", "/proj/src/a.ts", "/proj/src/m.ts")
        }
        val overlay = OverlayVfs(unsorted)
        overlay.put("/proj/src/b.ts", "b")
        assert(
            overlay.list("/proj/src") == listOf(
                "/proj/src/a.ts",
                "/proj/src/b.ts",
                "/proj/src/m.ts",
                "/proj/src/z.ts",
            ),
        )
    }
}
