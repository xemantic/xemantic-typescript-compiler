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
import com.xemantic.typescript.compiler.FrontEnd
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * (WARM.10) round 863 — pins `BenchMain`'s EMIT mode and the [FrontEnd] row it
 * exists to make measurable.
 *
 * Until this round every warm number in `docs/perf` was CHECK-ONLY, because
 * `noEmit = true` was a literal in three places in [main]. Round 738's gate
 * means that skips `Transformer.transform` and `Emitter.emit` entirely — so the
 * whole-program `jsxRuntime` pragma scan at the top of the transformer was
 * invisible to `rows`, to `frontend`, to `cost_gate.py` and to the
 * `--noEmit --listAll` 8-profile grid **at once**. That is the same structural
 * blindness round 862 recorded from the other side, and the fix for it is an
 * instrument, not an argument.
 *
 * The flag's vocabulary is CLOSED on purpose: an unrecognised 5th argument
 * fails loudly rather than defaulting, because the damage it does is not a
 * crash but a run that silently measures the other mode.
 */
class BenchEmitModeTest {

    private fun <T> withSavedMode(block: () -> T): T {
        val saved = FrontEnd.mode
        try {
            return block()
        } finally {
            FrontEnd.mode = saved
            FrontEnd.reset()
        }
    }

    @Test
    fun `an omitted 5th argument keeps the harness check-only`() {
        assert(!parseEmitFlag(null))
        assert(!parseEmitFlag(""))
    }

    @Test
    fun `emit is opt-in by name and case-insensitive`() {
        assert(parseEmitFlag("emit"))
        assert(parseEmitFlag("EMIT"))
        assert(parseEmitFlag("true"))
        assert(parseEmitFlag("1"))
        assert(parseEmitFlag("on"))
    }

    @Test
    fun `noEmit is an explicit spelling of the default`() {
        assert(!parseEmitFlag("noEmit"))
        assert(!parseEmitFlag("off"))
        assert(!parseEmitFlag("false"))
        assert(!parseEmitFlag("0"))
    }

    /**
     * THE DISCRIMINATING PIN of the pair. A typo must not silently select
     * check-only: that would be a measurement of the wrong compile with nothing
     * in the output to say so.
     */
    @Test
    fun `negative control - an unknown 5th argument is refused rather than defaulted`() {
        val e = assertFailsWith<IllegalStateException> { parseEmitFlag("emitt") }
        val msg = e.message ?: ""
        assert("emitt" in msg)
    }

    /**
     * The row must exist, must sit INSIDE the transform row, and must be part of
     * the printed order — a probe constant with no display order records nanos
     * that no reader ever sees.
     */
    @Test
    fun `the jsxRuntime pragma scan has its own FrontEnd row inside the transform block`() =
        withSavedMode {
            FrontEnd.mode = FrontEnd.ON
            FrontEnd.reset()
            assert(FrontEnd.TR_JSXPRAGMA < FrontEnd.N)
            val t0 = FrontEnd.t()
            FrontEnd.close(FrontEnd.TR_JSXPRAGMA, t0)
            FrontEnd.close(FrontEnd.TRANSFORM, t0)
            val text = FrontEnd.report()
            assert("of which the jsxRuntime pragma scan" in text)
            assert("of which Transformer.transform" in text)
        }

    /**
     * The CENSUS, which is what separates "this row is big" from "this row is
     * big and buys nothing" (round 758). A pragma count of zero over millions of
     * characters is the finding; the counters must therefore be recorded, not
     * inferred.
     */
    @Test
    fun `the pragma census records the characters scanned and the pragmas found`() =
        withSavedMode {
            FrontEnd.mode = FrontEnd.ON
            FrontEnd.reset()
            FrontEnd.addJsxPragmaCensus(chars = 9_977_097L, hits = 0L)
            FrontEnd.addJsxPragmaCensus(chars = 3L, hits = 2L)
            assert(FrontEnd.jsxPragmaFiles == 2L)
            assert(FrontEnd.jsxPragmaChars == 9_977_100L)
            assert(FrontEnd.jsxPragmaHits == 2L)
            assert("jsxRuntime pragma census: files 2 (9977100 chars), pragmas found 2" in FrontEnd.report())
        }

    /** Off the probe, the census entry point is a no-op — as every other one is. */
    @Test
    fun `negative control - off the probe the pragma census records nothing`() = withSavedMode {
        FrontEnd.mode = FrontEnd.OFF
        FrontEnd.reset()
        FrontEnd.addJsxPragmaCensus(chars = 100L, hits = 1L)
        assert(FrontEnd.jsxPragmaFiles == 0L)
        assert(FrontEnd.jsxPragmaChars == 0L)
        assert(FrontEnd.jsxPragmaHits == 0L)
    }
}
