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
 * (WARM.19) round 895 — the whole-source substring-scan filter.
 *
 * The one property that matters is **no false negatives**: [SourceScanFilter]
 * may only answer "absent" for a needle that really is absent, because ~50
 * `check*` pin walkers use that answer to skip their whole body and a wrong
 * "absent" deletes a diagnostic silently. The corpus suite is the broad gate;
 * these are the sharp ones.
 *
 * Each test says which failure it is the only witness for, and the class carries
 * its own positive controls — a filter that refused NOTHING would satisfy the
 * soundness tests vacuously, so `a filter refuses a needle the text does not
 * contain` and `a compile routes whole-source scans through the filter` are
 * there to make the soundness claim non-empty.
 */
class SrcScanTest {

    /**
     * A text with the shapes a source file has — repeated tokens, punctuation
     * runs, digits, a non-ASCII character — so the exhaustive sweep below is not
     * exercising one homogeneous alphabet.
     */
    private val text: String = buildString {
        for (i in 0 until 120) {
            append("export function f").append(i).append("(a: string, b: number): void {\n")
            append("    const x").append(i).append(" = a + b; // note ₁ ").append(i % 7).append('\n')
            append("}\n")
        }
    }

    @Test
    fun `a filter never refuses any substring of its own text`() {
        val f = SourceScanFilter(text)
        val k = SourceScanFilter.K
        var checked = 0
        var start = 0
        while (start + k <= text.length) {
            var len = k
            while (len <= 40 && start + len <= text.length) {
                val refused = !f.mayContain(text.substring(start, start + len))
                assert(!refused)
                checked++
                len++
            }
            start++
        }
        // the sweep must actually have swept — a zero here would make every
        // assertion above vacuous
        assert(checked > 100_000)
    }

    @Test
    fun `a filter refuses a needle the text does not contain`() {
        val f = SourceScanFilter(text)
        val absent = listOf(
            "import { 0n as foo }",
            "Shebang is only allowed on the first line",
            "StyledComponentInnerComponent<WithC>",
            "zzqqxx",
        )
        val refusedCount = absent.count { !f.mayContain(it) }
        assert(refusedCount == absent.size)
    }

    @Test
    fun `a needle shorter than the window width falls through unfiltered`() {
        val f = SourceScanFilter(text)
        assert(f.mayContain(""))
        assert(f.mayContain("z"))
        assert(f.mayContain("zq"))
        assert(f.mayContain("zqx"))
        // and one character longer is filterable again
        assert(!f.mayContain("zqxw"))
    }

    @Test
    fun `the query fold and the build fold agree window by window`() {
        // the two folds are one expression written twice; if they ever diverge
        // the filter starts refusing needles that are present, which is the one
        // failure no output diff would name
        val k = SourceScanFilter.K
        val one = text.substring(0, k)
        val f = SourceScanFilter(one)
        assert(f.mayContain(one))
        assert(SourceScanFilter.hashOf(one, 0) == SourceScanFilter.hashOf(text, 0))
    }

    @Test
    fun `an empty text refuses everything filterable`() {
        val f = SourceScanFilter("")
        assert(!f.mayContain("abcd"))
        assert(f.mayContain("abc"))
    }

    @Test
    fun `the cache hands back one filter per text and an eviction is still correct`() {
        val cache = SrcScanCache()
        val a = cache.filterFor(text)
        assert(cache.filterFor(text) === a)
        // a second, different text of the SAME length shares the length key and
        // must not be confused with the first
        val other = text.replace("export", "EXPORT")
        assert(other.length == text.length)
        val b = cache.filterFor(other)
        assert(b !== a)
        assert(!b.mayContain("export function f1("))
        assert(a.mayContain("export function f1("))
    }

    // -- the positive control -------------------------------------------------

    /**
     * `--srcScanBogus` must break the filter in the direction that MATTERS: it
     * has to refuse needles that are present, or `--verifySrcScan` reading zero
     * divergences tells us nothing about whether the verifier can see anything
     * at all (round 790).
     */
    @Test
    fun `the bogus build refuses a needle that is present`() {
        val saved = SrcScan.bogus
        try {
            SrcScan.bogus = true
            val f = SourceScanFilter(text)
            assert(!f.mayContain("export function f37(a: string, b: number)"))
        } finally {
            SrcScan.bogus = saved
        }
        // and with the control off the same needle is admitted
        assert(SourceScanFilter(text).mayContain("export function f37(a: string, b: number)"))
    }

    // -- end to end, through a real compile -----------------------------------

    /**
     * `checkShebangError` is a pin walker whose whole body is behind one
     * whole-source gate, so its diagnostics exist if and only if the gate let it
     * through. That makes it the cheapest end-to-end witness for the filter.
     */
    private val shebangFixture = """
        // Shebang is only allowed on the first line
        #!/usr/bin/env node
    """.trimIndent()

    @Test
    fun `a gated pin diagnostic survives the filter`() {
        val d = diagnose(shebangFixture, directives = "")
        assert(d.any { it.code == 18026 })
    }

    @Test
    fun `the filter arm and the pre-895 arm agree diagnostic for diagnostic`() {
        val on = diagnose(shebangFixture, directives = "").map { "${it.code}@${it.start}:${it.message}" }
        val saved = SrcScan.off
        val off = try {
            SrcScan.off = true
            diagnose(shebangFixture, directives = "").map { "${it.code}@${it.start}:${it.message}" }
        } finally {
            SrcScan.off = saved
        }
        assert(on == off)
        assert(on.isNotEmpty())
    }

    /**
     * THE ABLATION. Breaking the filter's build — one mistake, in the one place
     * that can produce a false negative — must delete the gated diagnostic. A
     * pin that stayed green here would be blind, and the gate's soundness claim
     * would rest on nothing (round 813).
     */
    @Test
    fun `a broken filter deletes the gated diagnostic which is what makes the pin discriminate`() {
        val saved = SrcScan.bogus
        val broken = try {
            SrcScan.bogus = true
            diagnose(shebangFixture, directives = "")
        } finally {
            SrcScan.bogus = saved
        }
        assert(broken.none { it.code == 18026 })
        // control: the same fixture on the same binary with the filter intact
        assert(diagnose(shebangFixture, directives = "").any { it.code == 18026 })
    }

    // -- the census, and that the mechanism is reached at all ------------------

    @Test
    fun `a compile routes whole source scans through the filter and most are refused`() {
        val savedOn = SrcScan.on
        try {
            SrcScan.reset()
            SrcScan.on = true
            diagnose(shebangFixture, directives = "")
            val calls = SrcScan.calls
            val refused = SrcScan.refused
            val scanned = SrcScan.scanned
            // the population is non-empty — without this every ratio below is a
            // statement about nothing (round 794). ONE file reaches 47 of the
            // 149 whole-source scan sites, so 30 is a floor with room, not a
            // transcription of today's number.
            assert(calls > 30)
            assert(SrcScan.builds > 0)
            // and the claim in this test's own name: most of them are refused
            // without ever touching the text
            assert(refused * 2 > calls)
            // THE PARTITION, and it is exact: a call is either refused without
            // touching the text or it reaches the real scan. `tooShort` is a
            // SUBSET of `scanned`, not a third bucket — this assertion is what
            // established that, by failing when it was written as one.
            assert(refused + scanned == calls)
            assert(SrcScan.tooShort <= scanned)
        } finally {
            SrcScan.on = savedOn
            SrcScan.reset()
        }
    }

    @Test
    fun `the verifier sees no divergence with a sound filter and sees one with the bogus control`() {
        val savedOn = SrcScan.on
        val savedVerify = SrcScan.verify
        val savedBogus = SrcScan.bogus
        try {
            SrcScan.reset()
            SrcScan.on = true
            SrcScan.verify = true
            diagnose(shebangFixture, directives = "")
            val soundVerified = SrcScan.verified
            val soundDivergences = SrcScan.divergences
            // the verifier ran on a non-empty population …
            assert(soundVerified > 0)
            // … and found the filter sound
            assert(soundDivergences == 0L)

            SrcScan.reset()
            SrcScan.bogus = true
            diagnose(shebangFixture, directives = "")
            // the positive control: a broken filter is SEEN
            assert(SrcScan.divergences > 0)
        } finally {
            SrcScan.on = savedOn
            SrcScan.verify = savedVerify
            SrcScan.bogus = savedBogus
            SrcScan.reset()
        }
    }
}
