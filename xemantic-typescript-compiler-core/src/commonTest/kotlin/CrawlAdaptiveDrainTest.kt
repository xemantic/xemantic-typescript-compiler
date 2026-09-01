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
 * (INC.85) — the crawl's ADAPTIVE drain: a file whose read is already in memory AND
 * whose parse is already cached is answered on the caller's thread; every other file
 * goes to the sixteen-way `flatMapMerge` UNCHANGED.
 *
 * ## Why the split exists
 *
 * Measured on an application-shaped project (2,401 files) at the per-keystroke query,
 * that pipeline is 4.8-6.4 ms — 82% of the crawl's concurrent half — at an effective
 * parallelism of **0.58-0.60x**: the worker CPU inside it is LESS than its own wall,
 * which is the signature of scheduling rather than work. On the UNTRUSTING arm the very
 * same pipeline runs at **7.5-8.9x**, so this may never become a policy: it is a
 * per-file classification, and a file whose read would block is still merged.
 *
 * ## What these pins are, and why both kinds are needed
 *
 * The DIFFERENTIAL pins compare the two drains over the same inputs — same program, same
 * order, same diagnostics, same cache and retention state, same counters. On their own
 * they are vacuous: an implementation that never takes the fast drain passes every one
 * of them by construction. The REGIME pins are the other half, and they are COUNTS
 * (`FrontEnd.crawlResidentFiles` / `crawlPipelineFiles`) because the row they decompose
 * is single-digit milliseconds with a per-process spread of its own, where a count moves
 * by exactly one per file (round 868 / (INC.64)).
 *
 * The COLD case is the pin that matters most: a first build must put EVERY file through
 * the pipeline, because a parse MISS is a real parse and belongs on
 * `Dispatchers.Default`, not on the crawl's own thread. Without it, "always drain
 * sequentially" would read green and a cold build of thousands of files would serialize.
 */
class CrawlAdaptiveDrainTest {

    /**
     * A [Vfs] that keeps what a build read and offers it back, exactly as `OverlayVfs`
     * does under `Project.trustFilesystem` — the ONE shape in which a read is resident.
     *
     * [retainRead] is the only mutator, which is the crawl's single-threaded-fold
     * discipline (round 825); [readTextIfResident] is read-only for the same reason.
     */
    private class RetainingVfs(
        private val delegate: InMemoryVfs,
        private val retaining: Boolean = true,
    ) : Vfs by delegate {
        private val retained = HashMap<String, String>()
        var residentServes: Long = 0
            private set

        /**
         * The wrapper trap, in the fixture: `Vfs by delegate` forwards this to an
         * [InMemoryVfs] that answers `false`, so a wrapper holding its OWN retention
         * must override it or the crawl never asks about a single one of its files.
         */
        override fun hasResidentContent(): Boolean = retained.isNotEmpty()

        override fun readTextIfResident(path: String): String? {
            val hit = retained[PathUtil.normalize(path)]
            if (hit != null) residentServes++
            return hit
        }

        override fun retainRead(path: String, text: String) {
            if (retaining) retained[PathUtil.normalize(path)] = text
        }

        /** What an editor keystroke does: new bytes, and the retention drops. */
        fun edit(path: String, text: String) {
            val k = PathUtil.normalize(path)
            retained.remove(k)
            delegate.writeText(k, text)
        }

        /**
         * What an UNSAVED editor buffer does — `OverlayVfs.put` while the filesystem
         * promise is in force, which is the only configuration whose wave is scanned at
         * all: the new text is
         * RESIDENT immediately, while the parse cache still holds the tree of the old
         * bytes. It is the one shape in which the residency gate is satisfied and the
         * parse gate must not be, and no other fixture here produces it.
         */
        fun overlay(path: String, text: String) {
            val k = PathUtil.normalize(path)
            retained[k] = text
            delegate.writeText(k, text)
        }
    }

    private fun sources(fileCount: Int): Map<String, String> {
        val files = HashMap<String, String>()
        files["/proj/tsconfig.json"] = """
            { "compilerOptions": { "noEmit": true, "module": "esnext" }, "include": ["src/**/*"] }
        """.trimIndent()
        for (i in 0 until fileCount) {
            files["/proj/src/f$i.ts"] = "export const v$i: number = $i;\n"
        }
        return files
    }

    private class Receipt(
        val programFiles: List<String>,
        val diagnostics: List<String>,
        val hits: Long,
        val misses: Long,
        val dispatches: Long,
        val resident: Long,
        val piped: Long,
        val scans: Long,
        val skips: Long,
    )

    private fun build(vfs: Vfs): Receipt {
        FrontEnd.crawlResidentFiles = 0
        FrontEnd.crawlPipelineFiles = 0
        FrontEnd.crawlDrainScans = 0
        FrontEnd.crawlDrainSkips = 0
        val hits0 = CrawlParseCache.hits
        val misses0 = CrawlParseCache.misses
        val dispatches0 = CrawlParseCache.parseDispatches
        val r = ProjectCompiler(vfs).build("/proj", noEmit = true)
        return Receipt(
            programFiles = r.programFiles.toList(),
            diagnostics = r.diagnostics.map { "${it.code}@${it.fileName}:${it.message}" }.sorted(),
            hits = CrawlParseCache.hits - hits0,
            misses = CrawlParseCache.misses - misses0,
            dispatches = CrawlParseCache.parseDispatches - dispatches0,
            resident = FrontEnd.crawlResidentFiles,
            piped = FrontEnd.crawlPipelineFiles,
            scans = FrontEnd.crawlDrainScans,
            skips = FrontEnd.crawlDrainSkips,
        )
    }

    // ---- REGIME: which drain runs, at each shape ------------------------------

    /**
     * THE control. A first build has retained nothing and cached nothing, so every file
     * is deferred — a real parse never runs on the caller's thread. Stated at two sizes
     * because the claim is a complexity one.
     */
    @Test
    fun `a cold crawl defers every file to the concurrent pipeline`() {
        for (n in intArrayOf(5, 20)) {
            CrawlParseCache.reset()
            val cold = build(RetainingVfs(InMemoryVfs(sources(n))))
            assert(cold.resident == 0L)
            assert(cold.piped == n.toLong())
            assert(cold.dispatches == n.toLong())
        }
    }

    /** Warm: the read is resident and the parse is cached, so nothing is merged. */
    @Test
    fun `a warm crawl over a retaining Vfs takes the resident drain for every file`() {
        CrawlParseCache.reset()
        val vfs = RetainingVfs(InMemoryVfs(sources(12)))
        build(vfs)
        val warm = build(vfs)
        assert(warm.resident == 12L)
        assert(warm.piped == 0L)
        assert(warm.dispatches == 0L)
        assert(warm.hits == 12L)
    }

    /**
     * A `Vfs` whose reads BLOCK never takes the fast drain, however warm the parse cache
     * is — which is the property that keeps the 7.5-8.9x arm intact. Asserted warm, i.e.
     * at the shape where the residency signal is the ONLY thing that differs.
     */
    @Test
    fun `a Vfs that reports nothing resident always uses the pipeline`() {
        CrawlParseCache.reset()
        val vfs = InMemoryVfs(sources(12))
        build(vfs)
        val warm = build(vfs)
        assert(warm.resident == 0L)
        assert(warm.piped == 12L)
        // The parse cache is warm — so a fast drain that keyed on the cache alone, and
        // not on residency, would read `resident == 12` here.
        assert(warm.hits == 12L)
    }

    /**
     * The keystroke shape: exactly the edited file is merged and re-parsed, and every
     * other file is answered sequentially. This is what says the classification is
     * per-FILE and not per-wave.
     */
    @Test
    fun `an edited file is the only one handed to the pipeline`() {
        CrawlParseCache.reset()
        val vfs = RetainingVfs(InMemoryVfs(sources(12)))
        build(vfs)
        vfs.edit("/proj/src/f7.ts", "export const v7: number = 700;\n")
        val after = build(vfs)
        assert(after.piped == 1L)
        assert(after.resident == 11L)
        assert(after.dispatches == 1L)
        assert(after.misses == 1L)
        assert(after.hits == 11L)
    }

    /**
     * (INC.85) THE PRE-GATE, in both directions. `Vfs.hasResidentContent` is a
     * whole-store question asked ONCE per wave, so a `Vfs` that holds nothing resident
     * — a cold build, and every `Vfs` that has not opted in, which is all of them but
     * the overlay — performs NOT ONE per-file probe and takes exactly the pre-(INC.85)
     * path. Without this pin an implementation that probes unconditionally is green
     * everywhere: it answers identically, it merely pays for the answer.
     */
    @Test
    fun `the whole-store pre-gate decides once per wave in both directions`() {
        CrawlParseCache.reset()
        // Untrusting: the default `false`, so the classification never runs at all.
        val plain = InMemoryVfs(sources(12))
        build(plain)
        val plainWarm = build(plain)
        assert(plainWarm.scans == 0L)
        assert(plainWarm.skips > 0L)
        assert(plainWarm.piped == 12L)

        CrawlParseCache.reset()
        val retaining = RetainingVfs(InMemoryVfs(sources(12)))
        // COLD: the store is still empty, so the gate is false here too — which is
        // what makes a first build of thousands of files free of this mechanism.
        val cold = build(retaining)
        assert(cold.scans == 0L)
        assert(cold.skips > 0L)
        assert(cold.resident == 0L)
        // WARM: the store now holds the program, so the wave IS classified.
        val warm = build(retaining)
        assert(warm.scans > 0L)
        assert(warm.skips == 0L)
        assert(warm.resident == 12L)
    }

    /**
     * The wrapper direction, which is the one this repo has been bitten by (INC.76): a
     * `Vfs` that holds retained content and lets the defaulted `false` stand underneath
     * it silently hands the merge back its work — same answers, none of the saving.
     * Kotlin's `by` forwards the member, so the pin is that the OVERRIDE is what makes
     * a wrapper visible, and that a wrapper over an empty store still answers false.
     */
    @Test
    fun `a delegating Vfs reports its own residency and not its delegates`() {
        val backing = InMemoryVfs(sources(3))
        val wrapper = RetainingVfs(backing)
        assert(!backing.hasResidentContent())
        assert(!wrapper.hasResidentContent())
        wrapper.retainRead("/proj/src/f0.ts", "export const v0: number = 0;\n")
        // The wrapper holds it; the thing it delegates to still does not.
        assert(wrapper.hasResidentContent())
        assert(!backing.hasResidentContent())
    }

    // ---- DIFFERENTIAL: the two drains agree ----------------------------------

    /**
     * The two drains over the same sources produce the same program in the same ORDER,
     * the same diagnostics and the same cache/counter state. Order is the load-bearing
     * half: the fast files are concatenated before the merged ones, so if the input-order
     * re-index were lost the binder's file order would change and ~350 corpus baselines
     * would reshuffle (round 776).
     */
    @Test
    fun `the resident drain and the pipeline agree on program order diagnostics and counters`() {
        CrawlParseCache.reset()
        val merged = InMemoryVfs(sources(12))
        build(merged)
        val viaPipeline = build(merged)

        CrawlParseCache.reset()
        val retaining = RetainingVfs(InMemoryVfs(sources(12)))
        build(retaining)
        val viaResident = build(retaining)

        // The regime really did differ — without this the comparison below is vacuous.
        assert(viaPipeline.resident == 0L)
        assert(viaResident.piped == 0L)

        assert(viaResident.programFiles == viaPipeline.programFiles)
        assert(viaResident.diagnostics == viaPipeline.diagnostics)
        assert(viaResident.hits == viaPipeline.hits)
        assert(viaResident.misses == viaPipeline.misses)
        assert(viaResident.dispatches == viaPipeline.dispatches)
    }

    /**
     * The mixed wave — some files resident, some not — is the case a per-wave decision
     * would get wrong, and it is where the concatenation of the two drains is actually
     * exercised. The program order must still be discovery order.
     */
    @Test
    fun `a mixed wave answers in discovery order`() {
        CrawlParseCache.reset()
        val a = InMemoryVfs(sources(12))
        build(a)
        val allMerged = build(a)

        CrawlParseCache.reset()
        val b = RetainingVfs(InMemoryVfs(sources(12)))
        build(b)
        b.edit("/proj/src/f0.ts", "export const v0: number = 1000;\n")
        b.edit("/proj/src/f5.ts", "export const v5: number = 1005;\n")
        val mixed = build(b)

        assert(mixed.resident == 10L)
        assert(mixed.piped == 2L)
        assert(mixed.programFiles == allMerged.programFiles)
    }

    /**
     * The retention itself must still be written — from the single-threaded fold, over
     * BOTH drains' output. A fast drain that skipped the fold would leave the second
     * build cold and silently give the merge back its work.
     */
    @Test
    fun `both drains feed the single-threaded fold`() {
        CrawlParseCache.reset()
        val vfs = RetainingVfs(InMemoryVfs(sources(12)))
        build(vfs)
        // Nothing was resident on the cold build, so nothing was served from memory.
        assert(vfs.residentServes == 0L)
        build(vfs)
        // …and yet every file is served now, which is only possible if the COLD build's
        // fold retained them — over output the concurrent pipeline produced.
        assert(vfs.residentServes == 12L)
        build(vfs)
        // …and the WARM build retained too, over the resident drain's own output.
        assert(vfs.residentServes == 24L)
    }

    /**
     * THE staleness pin. An unsaved buffer is RESIDENT and its cached tree is STALE, so
     * the fast drain's parse gate — [CrawlParseCache.lookup]'s own `(content, flags)`
     * compare — is the only thing between the build and a tree of the previous bytes.
     * A fast drain that served the cached entry by PATH would answer the old program
     * with no crash, no counter movement and no failing order pin: it reports the
     * diagnostics of text the user has replaced.
     */
    @Test
    fun `an overlaid buffer is resident and still re-parsed`() {
        CrawlParseCache.reset()
        val vfs = RetainingVfs(InMemoryVfs(sources(12)))
        val clean = build(vfs)
        assert(clean.diagnostics.isEmpty())

        vfs.overlay("/proj/src/f7.ts", "export const v7: string = 7;\n")
        val edited = build(vfs)
        // The bytes are in memory, so the read never blocks — 12 classification probes
        // plus the pipeline's own probe for the one file the classification deferred.
        assert(vfs.residentServes == 13L)
        // …and the tree is stale, so exactly this file is merged and re-parsed.
        assert(edited.piped == 1L)
        assert(edited.resident == 11L)
        assert(edited.misses == 1L)
        // The VALUE, which is what a stale tree would silently get wrong.
        val reported = edited.diagnostics.filter { it.contains("2322") }
        assert(reported.size == 1)
        assert(edited.diagnostics.size == 1)
    }

    /**
     * A `Vfs` that reports a file resident but retains NOTHING keeps every build cold,
     * so the fast drain never fires — the classification needs BOTH halves and this is
     * the one that says the parse-cache half is not implied by the residency half.
     */
    @Test
    fun `residency without a cached parse still goes to the pipeline`() {
        CrawlParseCache.reset()
        CrawlParseCache.enabled = false
        try {
            val vfs = RetainingVfs(InMemoryVfs(sources(12)))
            build(vfs)
            val warm = build(vfs)
            assert(warm.resident == 0L)
            assert(warm.piped == 12L)
        } finally {
            CrawlParseCache.enabled = true
            CrawlParseCache.reset()
        }
    }
}
