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
 * (INC.17/INC.18) THE RECEIPT'S INSTRUMENT: per-pass diagnostic attribution must be
 * SIGNED.
 *
 * The partition gate's sensitivity is read as "how many DISTINCT passes net a
 * diagnostic", and that count is only as complete as the accumulator it comes from.
 * [PassTiming.diagsByPass] — the one that has always existed — records `if (d1 > d0)`,
 * so a pass whose net effect is a RETRACTION is absent from it entirely. `Checker.kt`
 * has 73 `removeAll`, 5 `removeAt` and 2 `clear` sites, and CLAUDE.md already records
 * why they matter: a pass that retracts before it emits is INVISIBLE to a count-based
 * ablation (round 749), so a receipt built on the clamped accumulator would under-read
 * exactly the family hardest to reason about.
 *
 * [PassTiming.diagNetByPass] is the signed twin. These pin the difference directly,
 * at the [pass] wrapper rather than through a compile, because the property is about
 * the accumulator and a compile would make it depend on which walker happens to
 * retract today.
 */
class PassDiagNetSignTest {

    @Test
    fun `a retracting pass is recorded signed and is absent from the clamped twin`() {
        PassTiming.reset()
        PassTiming.enabled = true
        try {
            var size = 5
            PassTiming.diagnosticsSize = { size }
            pass("adder") { size += 2 }
            pass("retractor") { size -= 3 }
            pass("noop") { }
        } finally {
            PassTiming.enabled = false
        }
        assert(PassTiming.diagNetByPass["adder"] == 2)
        assert(PassTiming.diagNetByPass["retractor"] == -3)
        // A pass that moved nothing is in NEITHER: the receipt counts passes that
        // net a row, and a row-less pass is not one of them.
        assert(PassTiming.diagNetByPass["noop"] == null)
        // The clamped twin, which is what the receipt must NOT be built on.
        assert(PassTiming.diagsByPass["adder"] == 2)
        assert(PassTiming.diagsByPass["retractor"] == null)
        PassTiming.reset()
    }

    /**
     * And it must stay OFF when the instrumentation is off — INV.0's invariant, which
     * is what lets every equivalence sweep in `scripts/` be a control rather than a
     * second variable.
     */
    @Test
    fun `a disabled run records no signed deltas`() {
        PassTiming.reset()
        PassTiming.enabled = false
        var size = 0
        PassTiming.diagnosticsSize = { size }
        pass("adderWhileOff") { size += 4 }
        assert(PassTiming.diagNetByPass.isEmpty())
        PassTiming.reset()
    }
}
