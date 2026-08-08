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

package com.xemantic.typescript.compiler.bench

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.ArgSections
import com.xemantic.typescript.compiler.CallSections
import com.xemantic.typescript.compiler.CpaSections
import com.xemantic.typescript.compiler.CtaSections
import kotlin.test.Test

/**
 * (WARM.5) round 851 — pins the ORDER in which `BenchMain` arms, dumps and
 * disarms a probe tier.
 *
 * Round 850 found that every `*coarse` table it took printed `mode: ON`: the
 * harness cleared each probe's `mode` immediately after the instrumented
 * rebuild and *before* dumping, and every one of these reports labels its arm
 * from its own `mode`. The data was unaffected, but a label is what a reader
 * classifies an arm by, and both arms of a differential are otherwise
 * distinguishable only by counting rows.
 *
 * The fix is a REORDER, which is exactly the kind of change that silently comes
 * back. [tierReport] is the seam: it must produce the text while the probe is
 * still armed, and it must not disarm on the way out — which is what the two
 * assertions per tier below say, one for each direction.
 *
 * These pins arm no compile, so they cost nothing and touch no checker state
 * beyond the probe modes, which they save and restore (round 619 — restore what
 * was there, never a guessed default).
 */
class BenchTierReportTest {

    private fun <T> withSavedModes(block: () -> T): T {
        val cta = CtaSections.mode
        val cpa = CpaSections.mode
        val arg = ArgSections.mode
        val call = CallSections.mode
        try {
            return block()
        } finally {
            CtaSections.mode = cta
            CpaSections.mode = cpa
            ArgSections.mode = arg
            CallSections.mode = call
        }
    }

    /**
     * The arm's own LABEL, which is the string round 850 could not trust.
     * Deliberately a parse of the label line and not a `"COARSE" in text`
     * containment: three of the four reports also DISCUSS the ON-vs-COARSE
     * differential in prose, so containment reports every ON arm as coarse.
     */
    private fun labelOf(text: String): String =
        Regex("""mode[:=] ?([A-Z]+)""").find(text)?.groupValues?.get(1) ?: "<no label>"

    /** Every `*coarse` tier must label itself COARSE, and its ON twin ON. */
    @Test
    fun `a coarse tier reports itself as COARSE and its ON twin as ON`() = withSavedModes {
        for (base in listOf("cta", "cpa", "arg", "call")) {
            tierBegin(base)
            val onLabel = labelOf(tierReport(base))
            tierStop()
            tierBegin(base + "coarse")
            val coarseLabel = labelOf(tierReport(base + "coarse"))
            tierStop()
            assert(onLabel == "ON")
            assert(coarseLabel == "COARSE")
        }
    }

    /** …and the report must not be what disarms the probe. */
    @Test
    fun `tierReport leaves the probe armed and tierStop is what disarms it`() = withSavedModes {
        tierBegin("callcoarse")
        assert(CallSections.mode == CallSections.COARSE)
        tierReport("callcoarse")
        assert(CallSections.mode == CallSections.COARSE)
        tierStop()
        assert(CallSections.mode == CallSections.OFF)
        assert(CtaSections.mode == CtaSections.OFF)
        assert(CpaSections.mode == CpaSections.OFF)
        assert(ArgSections.mode == ArgSections.OFF)
    }

    /**
     * Round 849's `call`/`callcoarse` omission is what (WARM.5) had to add, so
     * the tier table is pinned as a whole: a probe with no tier name cannot be
     * taken warm at all, which is precisely why the largest warm handler went
     * un-partitioned for three rounds.
     */
    @Test
    fun `every section probe has both an ON and a COARSE tier name`() {
        for (base in listOf("cta", "cpa", "arg", "call")) {
            assert(base in TIERS)
            assert(base + "coarse" in TIERS)
        }
    }
}
