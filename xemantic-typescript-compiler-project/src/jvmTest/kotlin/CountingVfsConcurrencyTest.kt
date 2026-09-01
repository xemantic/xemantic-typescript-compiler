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
import kotlin.test.Test

/**
 * (TEST.1) The counting wrapper under the crawl's OWN shape: many threads reading
 * many paths at once. A plain `reads++` plus a `HashMap` put loses updates here —
 * the first `CountingVfs` did, and the loss surfaced as a single `afterFirst == 0`
 * in `ProjectTrustedFilesystemTest`'s negative control, queued as order-sensitivity
 * when it was a race. JVM-only because spawning threads is not expressible in
 * common code; the wrapper it grades is the common one.
 */
class CountingVfsConcurrencyTest {

    @Test
    fun `concurrent reads of many paths are all counted - per path and in total`() {
        val paths = (0 until 64).map { "/proj/src/f$it.ts" }
        val store = InMemoryVfs(paths.associateWith { "export const x = 1;\n" })
        val counting = CountingVfs(store)
        val threads = 8
        val readsPerThread = 2_000
        val workers = (0 until threads).map { t ->
            Thread {
                for (i in 0 until readsPerThread) counting.readText(paths[(t + i) % paths.size])
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        val expectedTotal = threads * readsPerThread
        val total = counting.reads
        val perPathSum = paths.sumOf { counting.readsOf(it) }
        val everyPathSeen = paths.all { counting.readsOf(it) > 0 }
        assert(total == expectedTotal)
        assert(perPathSum == expectedTotal)
        assert(everyPathSeen)
    }

}
