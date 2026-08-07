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
 * (AUDIT.3): pins the amplified globals-probe instrument.
 *
 * The measurement it serves multiplies the thing being measured instead of
 * shrinking the instrument, which buys accuracy at the cost of one new risk:
 * the amplified read path is a DIFFERENT path from the plain one, so it could
 * return a different answer or count a different population. Both are pinned
 * here — a lookup must return exactly what the un-amplified table returns, and
 * the recorded call count must be one per LOOKUP (not one per read), because
 * the whole derivation divides by that count.
 *
 * The third invariant is the ordinary one: with [GlobalsAmp.reads] `== 0`
 * nothing is recorded at all, which is the state every run except an
 * `--globalsAmp` one is in — including every corpus test and production, where
 * this class is never even constructed.
 */
class GlobalsAmpProbeTest {

    private fun table(): InstrumentedSymbolTable {
        val t = InstrumentedSymbolTable()
        t["present"] = Symbol(SymbolFlags.Variable, "present")
        return t
    }

    private fun withReads(reads: Int, body: (InstrumentedSymbolTable) -> Unit) {
        val saved = GlobalsAmp.reads
        GlobalsAmp.reset()
        GlobalsAmp.reads = reads
        try {
            body(table())
        } finally {
            GlobalsAmp.reads = saved
            GlobalsAmp.reset()
        }
    }

    @Test
    fun `nothing is recorded while the amplifier is off`() {
        withReads(0) { t ->
            assert(t["present"] != null)
            assert(t["absent"] == null)
            assert(t.containsKey("present"))
            assert(GlobalsAmp.calls == 0L)
            assert(GlobalsAmp.nanos == 0L)
            assert(GlobalsAmp.sink == 0L)
        }
    }

    @Test
    fun `the amplified read answers exactly as the plain read does`() {
        val plainHit = table()["present"]
        withReads(8) { t ->
            val hit = t["present"]
            assert(hit != null)
            assert(hit.name == plainHit?.name)
            assert(t["absent"] == null)
            assert(t.containsKey("present"))
            assert(!t.containsKey("absent"))
        }
    }

    @Test
    fun `one lookup is recorded per lookup and the sink counts every read`() {
        withReads(8) { t ->
            t["present"]
            t["absent"]
            t.containsKey("present")
            // Three LOOKUPS, not twenty-four reads — the derivation divides the
            // measured nanos by this, so counting reads here would understate
            // the per-lookup price by exactly the amplification factor.
            assert(GlobalsAmp.calls == 3L)
            // Two of the three lookups hit, and each performed eight reads: the
            // sink is what stops the JIT eliding the loop, so a zero here would
            // mean the amplification measured nothing.
            assert(GlobalsAmp.sink == 16L)
            assert(GlobalsAmp.nanos > 0L)
        }
    }
}
