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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (INC.84) — pins the decomposition of [FrontEnd.CRAWL]'s CONCURRENT half.
 *
 * [FrontEnd.CRAWL] is a wall and [FrontEnd.CRAWL_RESOLVE] its sequential half;
 * the only two rows that were inside the concurrent half — [FrontEnd.READ] and
 * [FrontEnd.PREPARSE] — are elapsed-with-suspension CPU sums across sixteen
 * workers, so on an application-shaped project they name ~2.8 ms of an 8-11 ms
 * region and the rest was attributed to nothing. The six rows this class pins
 * are that region: the batch wall, the concurrent pipeline inside it, the
 * per-file `CrawledFile` construction inside that, the single-threaded fold, the
 * re-index, and the drain loop whose `emit` runs the downstream collector.
 *
 * **What a future agent breaks and why nothing else notices.** The probe is
 * `mode == OFF` in production, so a dropped or misplaced `close` moves no
 * diagnostic, no emitted byte and no `cost_gate.py` counter — it silently dumps
 * a block's time into its parent's residue, which is exactly the residue this
 * round existed to remove. So the partition is asserted STRUCTURALLY, from the
 * open/close timestamps the probe already records (round 868: an assertion over
 * a monotonic clock's ORDERING is a fact, where a wall-clock ratio over a
 * sub-millisecond region is a coin flip).
 *
 * The first pin is the one the round is graded on: with the probe OFF every one
 * of these rows must be ZERO, and the same project must answer with the same
 * program and the same diagnostics either way.
 */
class CrawlConcurrentHalfPartitionTest {

    /**
     * ONE seed that imports a file that imports a file — so the crawl runs
     * several frontier waves and the per-wave rows are not pinned at a single
     * call, which is the only shape in which a lost boundary is visible.
     */
    private val files = mapOf(
        "/proj/tsconfig.json" to
            """{ "compilerOptions": { "strict": true }, "include": ["src/main.ts"] }""",
        "/proj/src/main.ts" to
            """
            import { fromA } from "./a"
            export const out: number = fromA + 1
            """.trimIndent(),
        "/proj/src/a.ts" to
            """
            import { fromB } from "./b"
            export const fromA: number = fromB + 1
            """.trimIndent(),
        "/proj/src/b.ts" to "export const fromB: number = 1\n",
    )

    private fun build(): ProjectCompiler.Result =
        ProjectCompiler(InMemoryVfs(files)).build("/proj", noEmit = true)

    private fun <T> withProbe(block: () -> T): T {
        val saved = FrontEnd.mode
        try {
            FrontEnd.reset()
            FrontEnd.mode = FrontEnd.ON
            return block()
        } finally {
            FrontEnd.mode = saved
        }
    }

    private val rows = intArrayOf(
        FrontEnd.CRAWL_BATCH, FrontEnd.CRAWL_PIPE, FrontEnd.CRAWL_MKFILE,
        FrontEnd.CRAWL_FOLD, FrontEnd.CRAWL_INDEX, FrontEnd.CRAWL_DRAIN,
    )

    /**
     * THE pin — INV.0's "OFF must stay behaviour-free" for this instrument, in
     * both directions: the rows record nothing, and the compile answers the same.
     */
    @Test
    fun `the concurrent-half probe is behaviour-free when it is off`() {
        val armed = withProbe { build() }
        val saved = FrontEnd.mode
        FrontEnd.reset()
        FrontEnd.mode = FrontEnd.OFF
        val bare = try { build() } finally { FrontEnd.mode = saved }

        val armedFiles = armed.programFiles.size
        val bareFiles = bare.programFiles.size
        assert(bareFiles == armedFiles)
        val armedDiags = armed.diagnostics.map { "${it.code}@${it.fileName}:${it.message}" }.sorted()
        val bareDiags = bare.diagnostics.map { "${it.code}@${it.fileName}:${it.message}" }.sorted()
        assert(bareDiags == armedDiags)

        var offNanos = 0L
        var offCalls = 0L
        for (r in rows) { offNanos += FrontEnd.nanos[r]; offCalls += FrontEnd.calls[r] }
        assert(offNanos == 0L)
        assert(offCalls == 0L)
    }

    /** Every new row is a distinct index inside [FrontEnd.N], named and ordered. */
    @Test
    fun `the six new rows are distinct named and inside the array`() {
        assert(rows.size == rows.toSet().size)
        var inRange = true
        var named = true
        for (r in rows) {
            if (r < 0 || r >= FrontEnd.N) inRange = false
            if (FrontEnd.names[r].isBlank()) named = false
        }
        assert(inRange)
        assert(named)
        assert(FrontEnd.names.size == FrontEnd.N)
    }

    /**
     * The batch rows record once per frontier WAVE, and the drain loop runs on
     * every wave including the last one — whose discoveries are empty, so it
     * opens no batch at all. That inequality is the shape of the crawl's loop and
     * an equality here would be the wrong pin.
     */
    @Test
    fun `each frontier wave records one batch and one drain`() {
        withProbe { build() }
        val batches = FrontEnd.calls[FrontEnd.CRAWL_BATCH]
        assert(batches >= 2L)
        assert(FrontEnd.calls[FrontEnd.CRAWL_PIPE] == batches)
        assert(FrontEnd.calls[FrontEnd.CRAWL_FOLD] == batches)
        assert(FrontEnd.calls[FrontEnd.CRAWL_INDEX] == batches)
        assert(FrontEnd.calls[FrontEnd.CRAWL_DRAIN] >= batches)
        // The CPU sum is folded once per crawled file, by the same single-threaded
        // collector that folds `filesRead` — so a lost carry is visible as a gap.
        assert(FrontEnd.calls[FrontEnd.CRAWL_MKFILE] == FrontEnd.filesRead)
    }

    /**
     * The three blocks inside a batch are DISJOINT sub-intervals of it, and every
     * batch is inside [FrontEnd.CRAWL] — so their sums are bounded rather than
     * merely correlated, which is what a dropped `close` breaks.
     */
    @Test
    fun `the batch blocks nest inside the batch and the batch inside the crawl`() {
        withProbe { build() }
        val batch = FrontEnd.nanos[FrontEnd.CRAWL_BATCH]
        val blocks = FrontEnd.nanos[FrontEnd.CRAWL_PIPE] +
            FrontEnd.nanos[FrontEnd.CRAWL_FOLD] +
            FrontEnd.nanos[FrontEnd.CRAWL_INDEX]
        assert(blocks <= batch)
        assert(batch + FrontEnd.nanos[FrontEnd.CRAWL_DRAIN] <= FrontEnd.nanos[FrontEnd.CRAWL])
        assert(FrontEnd.firstAt[FrontEnd.CRAWL] <= FrontEnd.firstAt[FrontEnd.CRAWL_BATCH])
        assert(FrontEnd.lastAt[FrontEnd.CRAWL_BATCH] <= FrontEnd.lastAt[FrontEnd.CRAWL])
        assert(FrontEnd.firstAt[FrontEnd.CRAWL_BATCH] <= FrontEnd.firstAt[FrontEnd.CRAWL_PIPE])
        assert(FrontEnd.firstAt[FrontEnd.CRAWL_PIPE] <= FrontEnd.firstAt[FrontEnd.CRAWL_FOLD])
        assert(FrontEnd.lastAt[FrontEnd.CRAWL_FOLD] <= FrontEnd.lastAt[FrontEnd.CRAWL_INDEX])
        assert(FrontEnd.lastAt[FrontEnd.CRAWL_INDEX] <= FrontEnd.lastAt[FrontEnd.CRAWL_BATCH])
    }
}
