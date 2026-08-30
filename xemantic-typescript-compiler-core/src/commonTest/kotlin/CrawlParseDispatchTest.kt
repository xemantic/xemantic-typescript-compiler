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
 * (INC.64): a WARM crawl must hand `misses` files to [kotlinx.coroutines.Dispatchers.Default],
 * not `files` of them.
 *
 * `readAndScanBatch` reads on the IO dispatcher and used to hop to `Dispatchers.Default`
 * for every file — unconditionally, so that a PARSE would not run on an IO thread. On a
 * warm incremental build every parse is a [CrawlParseCache] hit, so that second hop was
 * scheduling a ~1 us map probe onto another thread, `files` times, per build. Measured
 * over 2,401 application-shaped files: the two-hop shape costs **32.1 ms** against
 * **18.5** for one hop and **14.4** for a plain sequential read, and the floor's own
 * `pre-parse (CPU sum)` row — elapsed WITH SUSPENSION, i.e. mostly the wait for a slot —
 * fell **69-81 ms -> 2.0-2.7**.
 *
 * **THE CLAIM IS A COUNT AND CANNOT BE A TIME.** The saving is single-digit milliseconds
 * of a ~170 ms floor, i.e. inside the +-40% band a single floor draw carries, so no wall
 * assertion here could be anything but a coin flip (CLAUDE.md round 868). What is
 * deterministic is how many files were dispatched, and that is what these pin.
 *
 * The COLD arm is the control that stops "never dispatch" reading green: a first build
 * misses on every file and must still dispatch every one of them, because a real parse
 * on an IO thread is the thing the hop exists to prevent.
 */
class CrawlParseDispatchTest {

    private fun project(fileCount: Int): InMemoryVfs {
        val files = HashMap<String, String>()
        files["/proj/tsconfig.json"] = """
            { "compilerOptions": { "noEmit": true, "module": "esnext" }, "include": ["src/**/*"] }
        """.trimIndent()
        for (i in 0 until fileCount) {
            files["/proj/src/f$i.ts"] = "export const v$i: number = $i;\n"
        }
        return InMemoryVfs(files)
    }

    @Test
    fun `a cold crawl dispatches a parse for every file and a warm one for none`() {
        CrawlParseCache.reset()
        val vfs = project(12)

        val cold = ProjectCompiler(vfs).build("/proj", noEmit = true)
        assert(cold.diagnostics.none { it.category == DiagnosticCategory.Error })
        val coldDispatches = CrawlParseCache.parseDispatches
        val coldMisses = CrawlParseCache.misses
        // CONTROL: a first build parses, so it must hop — every file, on the dispatcher
        // the parse belongs on. Without this a "never dispatch" implementation is green.
        assert(coldMisses == 12L)
        assert(coldDispatches == coldMisses)

        ProjectCompiler(vfs).build("/proj", noEmit = true)
        // Every file is served from the content-keyed cache, so nothing is handed off.
        assert(CrawlParseCache.hits == 12L)
        assert(CrawlParseCache.misses == coldMisses)
        assert(CrawlParseCache.parseDispatches == coldDispatches)
    }

    @Test
    fun `an edited file is the only one dispatched on the next build`() {
        CrawlParseCache.reset()
        val vfs = project(12)
        ProjectCompiler(vfs).build("/proj", noEmit = true)
        val after0 = CrawlParseCache.parseDispatches

        vfs.writeText("/proj/src/f7.ts", "export const v7: number = 700;\n")
        ProjectCompiler(vfs).build("/proj", noEmit = true)

        // The cache is keyed by CONTENT, so exactly the edited file misses — and exactly
        // it is dispatched. This is the shape an editor keystroke has.
        assert(CrawlParseCache.parseDispatches - after0 == 1L)
    }

    @Test
    fun `the dispatch count tracks the file count on a cold crawl`() {
        CrawlParseCache.reset()
        ProjectCompiler(project(5)).build("/proj", noEmit = true)
        val five = CrawlParseCache.parseDispatches
        CrawlParseCache.reset()
        ProjectCompiler(project(20)).build("/proj", noEmit = true)
        val twenty = CrawlParseCache.parseDispatches
        // Stated at two sizes because the claim is about COMPLEXITY: a warm build is
        // O(edits) where a cold one is O(files), and one size cannot say which.
        assert(five == 5L)
        assert(twenty == 20L)
    }
}
