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
 * (INC.23) THE CENSUS MUST BE INERT WHEN UNSET, and the pin is a COUNT — round
 * 876's law, that a probe which quietly records on a production build moves no
 * diagnostic and no emitted byte, so only a counter sees it.
 *
 * The second pin is the one round 900 makes necessary: a guard cannot protect its
 * own ARGUMENT, so a hook written `census(expensiveThing())` does the work on every
 * compile with the census off. Both hooks here are inside `if (on) { … }` BLOCKS,
 * and the receipt is that a default compile leaves all three tables empty.
 */
class SymTypeOrderCensusTest {

    /** Off is the shipped configuration, with no install anywhere. */
    @Test
    fun `the census is off by default`() {
        assert(!SymTypeOrderCensus.on)
        assert(SymTypeOrderCensus.rowFilter == "any")
    }

    /**
     * THE REFUTED ARM'S DEFAULT, WITH NO INSTALL — (INC.16) arm a1's lesson, and
     * the one pin in this file that must not touch the flag it asserts.
     *
     * [SymTypeOrderGate.refuseTruncatedWrites] was (INC.23)'s candidate fourth
     * dimension for round 778's `symbolTypes` write gate: do not persist a
     * resolution that a member-resolution cycle guard cut short. Measured over the
     * whole capture-channel sweep it changes NOTHING — the same 78 lost-to-`any`
     * rows and a byte-identical narrow digest — because the re-resolution re-enters
     * the same guard. Off, `getTypeOfSymbol`'s write is exactly round 778's verdict,
     * which is what makes the flag provably inert rather than merely disabled.
     */
    @Test
    fun `the truncation write gate is off by default`() {
        assert(!SymTypeOrderGate.refuseTruncatedWrites)
    }

    /** A default compile records nothing at all. */
    @Test
    fun `a default compile leaves the census empty`() {
        SymTypeOrderCensus.reset()
        val diagnostics = diagnose(
            """
            interface Shape { a: number }
            declare const s: Shape;
            const bad: string = s.a;
            """,
        )
        assert(diagnostics.any { it.code == 2322 })
        assert(SymTypeOrderCensus.firstResolve.isEmpty())
        assert(SymTypeOrderCensus.resolves.isEmpty())
        assert(SymTypeOrderCensus.memberRows.isEmpty())
    }

    /**
     * POSITIVE CONTROL: armed, the WRITER ledger fills — without it the pin above
     * reads zero both when the hook is inert and when it is absent, which is
     * CLAUDE.md's round-790 defect ("a verifier reads 0 both when the skip is sound
     * and when the instrument is dead").
     *
     * The global is SAVED AND RESTORED: `SymTypeOrderCensus` is fork-global state
     * and a test that assigns the default back would re-arm it for every
     * alphabetically later class.
     */
    @Test
    fun `positive control - armed, the writer ledger records resolutions`() {
        val saved = SymTypeOrderCensus.on
        try {
            SymTypeOrderCensus.reset()
            SymTypeOrderCensus.on = true
            diagnose(
                """
                interface Shape { a: number }
                declare const s: Shape;
                const bad: string = s.a;
                """,
            )
        } finally {
            SymTypeOrderCensus.on = saved
        }
        assert(SymTypeOrderCensus.firstResolve.isNotEmpty())
        assert(SymTypeOrderCensus.firstResolve.values.any { "ambient=empty" in it })
        // The two dimensions round 778's gate does not read are recorded too.
        assert(SymTypeOrderCensus.firstResolve.values.any { "depth=sym" in it })
        assert(SymTypeOrderCensus.firstResolve.values.any { "truncated=false" in it })
        SymTypeOrderCensus.reset()
        assert(!SymTypeOrderCensus.on)
    }
}
