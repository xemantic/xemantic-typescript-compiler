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
 * (WARM.3) round 849 — pins for the lib-type re-derivation census.
 *
 * Three properties, and the second is the one that makes the other two mean
 * something: an instrument that reported zeros would satisfy "behaviour-free
 * when off" vacuously, so every pin here is written to FAIL if the probe were
 * inert.
 *
 * The census is FORK-GLOBAL state, so every test saves and restores it — never
 * assigning a guessed default back (round 619's lesson: a test that assigns the
 * default re-enables whatever an experiment had configured, for every
 * alphabetically-later class).
 */
class LibTypeCensusTest {

    /**
     * Deliberately REPEAT-heavy: the consumed side of the ratio only exists when
     * the SAME lib type is asked twice, so a fixture with one lib member access
     * measures a structurally empty population and says nothing (that is what a
     * first draft of this class did — see the KDoc on the consumed-side pin).
     * `string`, `Array` and `String`/`Number` are all embedded-lib declarations,
     * so no `@useRealLibs` is needed to reach them.
     */
    private val src = """
        interface Box { v: string }
        const b: Box = { v: "x" }
        const c: Box = { v: "y" }
        const n1: number = b.v.length
        const n2: number = c.v.length
        const p1: string[] = b.v.split(",")
        const p2: string[] = c.v.split(";")
        const u1: string = b.v.toUpperCase()
        const u2: string = c.v.toUpperCase()
        const j1: string = p1.join("-")
        const j2: string = p2.join("+")
    """.trimIndent()

    private fun <T> withCensus(block: () -> T): T {
        val wasEnabled = LibTypeCensus.enabled
        LibTypeCensus.reset()
        LibTypeCensus.enabled = true
        try {
            return block()
        } finally {
            LibTypeCensus.enabled = wasEnabled
            LibTypeCensus.reset()
        }
    }

    @Test
    fun `the census is behaviour-free - the same program answers the same diagnostics with it on`() {
        val off = diagnose(src).map { "${it.code}@${it.start}:${it.message}" }
        val on = withCensus { diagnose(src) }.map { "${it.code}@${it.start}:${it.message}" }
        assert(on == off)
    }

    @Test
    fun `the census counts LIB mints - it would be inert if the classifier never matched`() {
        val libMints = withCensus {
            diagnose(src)
            LibTypeCensus.declMintLib + LibTypeCensus.memMintLib
        }
        // `string`/`Array`/`String` all come from the embedded lib, so a run that
        // types `b.v.length` and `b.v.split(",")` MUST mint lib types. A zero here
        // means the classifier — not the workload — is broken.
        assert(libMints > 0)
    }

    @Test
    fun `the census times only the OUTERMOST mint - spans never exceed mints and depth unwinds to zero`() {
        val r = withCensus {
            diagnose(src)
            longArrayOf(
                LibTypeCensus.libOutermost + LibTypeCensus.otherOutermost,
                LibTypeCensus.declMintLib + LibTypeCensus.declMintOther +
                    LibTypeCensus.memMintLib + LibTypeCensus.memMintOther,
                LibTypeCensus.depth.toLong(),
                LibTypeCensus.boundaryCalls,
                LibTypeCensus.libNanos + LibTypeCensus.otherNanos,
            )
        }
        val spans = r[0]
        val mints = r[1]
        val depth = r[2]
        val boundaries = r[3]
        val nanos = r[4]
        // Nesting is real in this workload, so the partition is strict: fewer
        // timed spans than mints. Equality would mean the shared depth counter
        // is not seeing the recursion and every nested mint is timed twice.
        assert(spans < mints)
        assert(spans > 0)
        // Every timed span takes exactly one in-situ empty calibration pair.
        assert(boundaries == spans)
        // The `finally` unwinds even on the cycle-guard early returns.
        assert(depth == 0L)
        assert(nanos > 0)
    }

    /**
     * The pin that found the instrument's own defect. It read 0 on the first
     * build because the member-table hook sat in `resolveStructuredTypeMembersCore`,
     * whose `properties != null` guard is DUPLICATED one frame up in
     * `resolveStructuredTypeMembers` — so the wrapper absorbed every hit and the
     * hooked branch was dead. A produced-vs-consumed census keyed on a boundary
     * the caller already short-circuits measures nothing at all.
     */
    @Test
    fun `the produced-vs-consumed ratio has a non-empty consumed side`() {
        val hits = withCensus {
            diagnose(src)
            LibTypeCensus.memHitLib
        }
        // A shared lib-type cache is worth nothing if nothing ever reads a
        // derived lib type back; round 801 makes this ratio a precondition for
        // reading the timing at all.
        assert(hits > 0)
    }

    @Test
    fun `negative control - with the census off nothing is recorded`() {
        LibTypeCensus.reset()
        diagnose(src)
        val touched = LibTypeCensus.declMintLib + LibTypeCensus.declMintOther +
            LibTypeCensus.memMintLib + LibTypeCensus.memMintOther +
            LibTypeCensus.declHitLib + LibTypeCensus.memHitLib +
            LibTypeCensus.libOutermost + LibTypeCensus.otherOutermost +
            LibTypeCensus.boundaryCalls
        assert(touched == 0L)
    }
}
