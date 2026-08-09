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
 * (WARM.8) round 861 — pins the four-block partition of [FrontEnd.POST].
 *
 * Round 859 measured the post-checker tails at **143.2 ms = 1.90% of a warm
 * rebuild**, warming 1.27x — the worst ratio it found — with **no probe below
 * them**: under `--noEmit` [FrontEnd.TRANSFORM] / [FrontEnd.EMIT] /
 * [FrontEnd.DECL_EMIT] have zero calls, and nothing else in the region was
 * named. This round names it in four blocks that ABUT, from [FrontEnd.POST]'s
 * own `t()` to its `close()`.
 *
 * **The invariant a future agent breaks, and why only a real compile can see
 * it.** The blocks are exhaustive *by construction* — each block's `t()`
 * immediately follows the previous block's `close()` — so inserting work into
 * the region is safe and dropping or reordering a boundary is not. A dropped
 * `close` takes that block's `calls` to zero and dumps its time into
 * [FrontEnd.POST]'s residue; a misplaced one silently re-attributes work between
 * two blocks. Neither is visible in any output, in `cost_gate.py`, or in the
 * corpus: the probe is `mode == OFF` in production, so **the only thing that can
 * fail is an assertion on the partition itself**, taken from a compile that
 * actually crosses [TypeScriptCompiler]'s multi-file path.
 *
 * The residue is therefore asserted as a PARTITION CHECK rather than reported as
 * an unattributed remainder: with the blocks abutting, everything it can contain
 * is the four timestamp pairs themselves.
 *
 * [FrontEnd.mode] is saved and restored, never reset to a guessed default
 * (round 619), and the probe is behaviour-free either way — which the last pin
 * states by compiling the same project twice.
 */
class PostCheckerPartitionTest {

    private val files = mapOf(
        "/proj/tsconfig.json" to
            """{ "compilerOptions": { "strict": true }, "include": ["src/**/*.ts"] }""",
        "/proj/src/a.ts" to
            """
            export interface Shape { readonly kind: string }
            export function widen(s: Shape): string { return s.kind }
            """.trimIndent(),
        "/proj/src/b.ts" to
            """
            import { widen, Shape } from "./a"
            export namespace Outer { export const tag = "b" }
            const s: Shape = { kind: Outer.tag }
            export const out: string = widen(s)
            """.trimIndent(),
    )

    /** A program that DOES carry a require-only candidate, so pass 2 must run. */
    private val requireFiles = mapOf(
        "/proj/tsconfig.json" to """{ "include": ["src/**/*.ts"] }""",
        "/proj/src/a.ts" to "export const fromA: number = 1\n",
        "/proj/src/b.ts" to
            """
            declare const require: (s: string) => unknown
            const a = require('./a')
            export const fromB: number = 2
            """.trimIndent(),
    )

    private fun <T> withProbe(block: () -> T): T {
        val saved = FrontEnd.mode
        try {
            FrontEnd.reset()
            FrontEnd.mode = FrontEnd.ON
            return block()
        } finally {
            FrontEnd.mode = saved
            FrontEnd.reset()
        }
    }

    private fun build(): ProjectCompiler.Result =
        ProjectCompiler(InMemoryVfs(files)).build("/proj", noEmit = true)

    /**
     * (WARM.8)(c) — the same project compiled WITH emit. The level-3 blocks
     * below are asserted on this build and not on [build], because the orphan
     * census is gated out of a check-only compile (its only consumer reads an
     * empty map there), so a check-only fixture would pin an empty population
     * and stay green whatever the scans did.
     */
    private fun buildEmitting(): ProjectCompiler.Result =
        ProjectCompiler(InMemoryVfs(files)).build("/proj", noEmit = false)

    /** The four constants must be distinct, inside [FrontEnd.N], and each named and ordered. */
    @Test
    fun `the four post-checker blocks are declared, named and ordered`() {
        val blocks = listOf(
            FrontEnd.POST_DIAGS, FrontEnd.POST_NSEXPORTS,
            FrontEnd.POST_EMITPREP, FrontEnd.POST_OUTPUTS,
        )
        assert(blocks.toSet().size == 4)
        assert(blocks.all { it < FrontEnd.N })
        assert(blocks.all { FrontEnd.names[it].isNotEmpty() })
        assert(FrontEnd.names[FrontEnd.POST_NSEXPORTS].contains("collectCrossFileNamespaceExports"))
    }

    /**
     * THE DISCRIMINATING PIN — every block must record EXACTLY ONCE on a real
     * multi-file compile. A dropped `FrontEnd.close(...)` reads zero here, which
     * nothing else in this repo can see.
     */
    @Test
    fun `every post-checker block records exactly once on a multi-file build`() = withProbe {
        val result = build()
        assert(result.diagnostics.none { it.category == DiagnosticCategory.Error })
        assert(FrontEnd.calls[FrontEnd.POST] == 1L)
        assert(FrontEnd.calls[FrontEnd.POST_DIAGS] == 1L)
        assert(FrontEnd.calls[FrontEnd.POST_NSEXPORTS] == 1L)
        assert(FrontEnd.calls[FrontEnd.POST_EMITPREP] == 1L)
        assert(FrontEnd.calls[FrontEnd.POST_OUTPUTS] == 1L)
    }

    /**
     * …and they must PARTITION it. The blocks abut, so the only thing the
     * residue can hold is the four timestamp pairs; a boundary moved outside the
     * region, or a block left unclosed, breaks this and nothing else.
     */
    @Test
    fun `the four blocks partition the post-checker region`() = withProbe {
        build()
        val post = FrontEnd.nanos[FrontEnd.POST]
        val sum = FrontEnd.nanos[FrontEnd.POST_DIAGS] + FrontEnd.nanos[FrontEnd.POST_NSEXPORTS] +
            FrontEnd.nanos[FrontEnd.POST_EMITPREP] + FrontEnd.nanos[FrontEnd.POST_OUTPUTS]
        // Non-vacuity: the region must have been entered and be non-trivial, or
        // "the parts sum to the whole" would hold for two zeros.
        assert(post > 0L)
        assert(sum > 0L)
        // No block may exceed the region that contains it.
        assert(sum <= post)
        // The residue is four timestamp pairs — generously bounded, because this
        // pin is about a MISPLACED boundary (which loses a whole block's worth),
        // not about nanosecond accounting.
        val residue = post - sum
        assert(residue * 4 < post)
    }

    /**
     * LEVEL 2 — [FrontEnd.POST_OUTPUTS] carries 98% of the region, so it is
     * split again, and its four blocks abut across it exactly as level 1's do
     * across [FrontEnd.POST]. Same failure mode, same instrument, one level
     * down.
     */
    @Test
    fun `the output block is itself partitioned into four`() = withProbe {
        build()
        val outputs = FrontEnd.nanos[FrontEnd.POST_OUTPUTS]
        val sum = FrontEnd.nanos[FrontEnd.POST_DEPS] + FrontEnd.nanos[FrontEnd.POST_TOPO] +
            FrontEnd.nanos[FrontEnd.POST_ORPHANS] + FrontEnd.nanos[FrontEnd.POST_ASSEMBLE]
        assert(FrontEnd.calls[FrontEnd.POST_DEPS] == 1L)
        assert(FrontEnd.calls[FrontEnd.POST_TOPO] == 1L)
        assert(FrontEnd.calls[FrontEnd.POST_ORPHANS] == 1L)
        assert(FrontEnd.calls[FrontEnd.POST_ASSEMBLE] == 1L)
        assert(outputs > 0L)
        assert(sum > 0L)
        assert(sum <= outputs)
        val residue = outputs - sum
        assert(residue * 4 < outputs)
    }

    /**
     * LEVEL 3 — [FrontEnd.POST_ORPHANS] is 97.6% of [FrontEnd.POST_OUTPUTS] and
     * round 861 § 12.6 recorded in as many words that it "was not
     * sub-partitioned, so nothing here says which of its scans costs the
     * 130 ms". These three blocks are the three per-FILE scans of its one loop,
     * so unlike every level above them they record once per PROGRAM FILE, and
     * the census counts the same population independently.
     */
    @Test
    fun `the orphan block is partitioned into its three per-file scans`() = withProbe {
        buildEmitting()
        val orphans = FrontEnd.nanos[FrontEnd.POST_ORPHANS]
        val declReq = FrontEnd.calls[FrontEnd.ORPH_DECLREQ]
        // Pass 1 runs once per program file, and both its blocks are inside that
        // loop — a boundary dropped from either reads a different count.
        assert(declReq > 0L)
        assert(FrontEnd.calls[FrontEnd.ORPH_NSWALK] == declReq)
        // …and the census, which is the population, counts the same files.
        assert(FrontEnd.orphanFiles == declReq)
        assert(FrontEnd.orphanChars > 0L)
        // Neither fixture file declares `require`, so the probe accepts none of
        // them — the reading that says the scan is skippable, and a non-vacuous
        // assertion because the counter is bumped on every file either way.
        assert(FrontEnd.orphanDeclReqHits == 0L)
        // …hence PASS 2 never runs, which is (WARM.8)(c)'s saving in its
        // smallest observable form: `staticallyReferenced` is purely
        // subtractive, so with no candidate there is nothing to subtract from.
        assert(FrontEnd.calls[FrontEnd.ORPH_IMPORTTYPE] == 0L)
        val sum = FrontEnd.nanos[FrontEnd.ORPH_IMPORTTYPE] +
            FrontEnd.nanos[FrontEnd.ORPH_DECLREQ] + FrontEnd.nanos[FrontEnd.ORPH_NSWALK]
        assert(orphans > 0L)
        assert(sum > 0L)
        assert(sum <= orphans)
    }

    /**
     * …and the complement: a program that DOES carry a require-only candidate
     * must run pass 2 over every file. Without this the pin above would be
     * satisfied by a pass 2 that had simply been deleted.
     */
    @Test
    fun `pass 2 runs once per file when a candidate exists`() = withProbe {
        ProjectCompiler(InMemoryVfs(requireFiles)).build("/proj", noEmit = false)
        val declReq = FrontEnd.calls[FrontEnd.ORPH_DECLREQ]
        assert(declReq > 0L)
        assert(FrontEnd.orphanDeclReqHits == 1L)
        assert(FrontEnd.calls[FrontEnd.ORPH_IMPORTTYPE] == declReq)
    }

    /**
     * NEGATIVE CONTROL — the probe is behaviour-free, so the same project must
     * answer identically with it off. Round 859's `frontend` tier and every row
     * above depend on this and nothing else asserts it for [FrontEnd.POST].
     */
    @Test
    fun `negative control - the probe changes nothing about the compile`() {
        val armed = withProbe { build() }
        val saved = FrontEnd.mode
        FrontEnd.mode = FrontEnd.OFF
        val bare = try { build() } finally { FrontEnd.mode = saved }
        assert(bare.diagnostics.size == armed.diagnostics.size)
        assert(bare.programFiles.size == armed.programFiles.size)
        assert(FrontEnd.calls[FrontEnd.POST_DIAGS] == 0L)
    }
}
