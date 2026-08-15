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
 * (SPINE.1)(a): pins the intra-handler attribution harness for `cpaSpineLeave`
 * and `ccetSpineLeave`.
 *
 * Two invariants matter. The FIRST is that turning the probe on cannot change
 * what the compiler says — the splits live inside two production handlers and
 * the sub-measures wrap two ancestor climbs and both frame-ambient installs,
 * so a mis-placed `try`/`finally` or a swapped core call would show up here as
 * a diagnostics difference between `mode = OFF` and `mode = ON`.
 *
 * The SECOND is that the sections PARTITION each handler: every disjoint
 * section must be entered exactly once per node the handler is consulted
 * about, so the per-section call counts of one handler must all be equal.
 * That is what makes "section X is N ms of the handler's total" a sound claim
 * rather than a sampled guess — a section that is entered a different number
 * of times than its siblings means a `split` was skipped by an early return.
 */
class SpineSectionProbeTest {

    private val source = """
        interface Shape { readonly kind: string; area(): number }
        enum Color { Red = 1, Green, Blue }
        namespace Geo {
            export const origin = { x: 0, y: 0 }
            export function dist(a: { x: number; y: number }): number {
                return Math.sqrt(a.x * a.x + a.y * a.y)
            }
        }
        class Base implements Shape {
            readonly kind = "base"
            protected size = 1
            constructor(public label: string) {}
            area(): number { return this.size }
            get twice(): number { return this.size * 2 }
        }
        class Square extends Base {
            constructor(private side: number) { super("square") }
            area(): number { return this.side * this.side }
        }
        function main(flag: boolean, items: number[]): void {
            let total = 0
            for (const it of items) total += it
            for (const k in Geo.origin) { total += k.length }
            const sq = new Square(3)
            const arr = [1, 2, 3].map((n) => n * 2)
            switch (Color.Red as Color) {
                case Color.Red: total += Geo.dist(Geo.origin); break
                default: total--
            }
            while (total > 0) { total-- }
            if (flag) { total += sq.area() } else { total -= arr.length }
            throw new Error(`${'$'}{total}`)
        }
        main(true, [1])
        // The two migrated passes' OWN emissions, so an ON/OFF comparison of
        // the diagnostics is not vacuous: a missing member is cpa's (TS2339),
        // a wrong argument count is ccet's (TS2554).
        function probe(sq: Square): number {
            return sq.nope + Geo.dist(Geo.origin, 1)
        }
        probe(new Square(1))
    """.trimIndent()

    private fun diagnosticsUnder(mode: Int): List<String> {
        // SAVE-AND-RESTORE, never "assign the default back" — the mode is
        // fork-global (the round-619 Inv0PassTimingTest lesson).
        val saved = SpineSections.mode
        SpineSections.reset()
        SpineSections.mode = mode
        try {
            return diagnose(source).map { "${it.code}@${it.start}:${it.length} ${it.message}" }
        } finally {
            SpineSections.mode = saved
        }
    }

    @Test
    fun `the section-name table is index-aligned and complete`() {
        assert(SpineSections.names.size == SpineSections.N)
        assert(SpineSections.names[SpineSections.CPA_ANCHOR].startsWith("cpa:"))
        assert(SpineSections.names[SpineSections.CCET_POP].startsWith("ccet:"))
        assert(SpineSections.FIRST_NESTED == SpineSections.CPA_CHAINOK)
        // NONE must never be a valid index: the frame-ambient wrappers gate on
        // `sec >= 0`, so a non-leave call site records nothing.
        assert(SpineSections.NONE < 0)
    }

    @Test
    fun `the probe is behaviour-free when off`() {
        val off = diagnosticsUnder(SpineSections.OFF)
        val on = diagnosticsUnder(SpineSections.ON)
        assert(on == off)
        // Not vacuous: the fixture emits the two INSTRUMENTED passes' own
        // codes, so a broken split or a swapped climb core would diverge here.
        assert(off.any { it.contains(" 2339 ") || it.startsWith("2339@") })
        assert(off.any { it.contains(" 2554 ") || it.startsWith("2554@") })
    }

    @Test
    fun `nothing is recorded while the probe is off`() {
        val saved = SpineSections.mode
        SpineSections.reset()
        SpineSections.mode = SpineSections.OFF
        try {
            diagnose(source)
        } finally {
            SpineSections.mode = saved
        }
        val recorded = SpineSections.calls.sumOf { it.sum() } + SpineSections.hits.sum()
        assert(recorded == 0L)
    }

    @Test
    fun `the disjoint sections partition each handler`() {
        val saved = SpineSections.mode
        SpineSections.reset()
        SpineSections.mode = SpineSections.ON
        try {
            diagnose(source)
        } finally {
            SpineSections.mode = saved
        }
        // Every cpa section is entered once per node cpaSpineLeave sees, and
        // the in-situ overhead span rides the same path.
        val cpaCounts = (SpineSections.CPA_ANCHOR..SpineSections.CPA_POP)
            .map { SpineSections.calls[it].sum() }
        val cpaNodes = cpaCounts[0]
        assert(cpaNodes > 100L)
        assert(cpaCounts.all { it == cpaNodes })
        assert(SpineSections.calls[SpineSections.OVERHEAD].sum() == cpaNodes)
        val ccetCounts = (SpineSections.CCET_RESTORES..SpineSections.CCET_POP)
            .map { SpineSections.calls[it].sum() }
        assert(ccetCounts.all { it == cpaNodes })
        SpineSections.reset()
    }

    @Test
    fun `the nested sub-measures are attributed to the sections that contain them`() {
        val saved = SpineSections.mode
        SpineSections.reset()
        SpineSections.mode = SpineSections.ON
        try {
            diagnose(source)
        } finally {
            SpineSections.mode = saved
        }
        // The climbs run only when their section's own cheap gate passed, so
        // they are strictly rarer than the per-node section count.
        val nodes = SpineSections.calls[SpineSections.CPA_ANCHOR].sum()
        val chainOk = SpineSections.calls[SpineSections.CPA_CHAINOK].sum()
        assert(chainOk in 1L..<nodes)
        assert(SpineSections.calls[SpineSections.CCET_CHAINOK].sum() in 1L..<nodes)
        // One frame-ambient install per section HIT — the leave handler's four
        // cpa work bodies and the ccet ones are the only instrumented callers.
        val cpaHits = SpineSections.hits[SpineSections.CPA_ANCHOR] +
            SpineSections.hits[SpineSections.CPA_OWNER] +
            SpineSections.hits[SpineSections.CPA_EWTA] +
            SpineSections.hits[SpineSections.CPA_PROPDECL] +
            SpineSections.hits[SpineSections.CPA_VARDECL]
        assert(SpineSections.calls[SpineSections.CPA_AMBIENT].sum() == cpaHits)
        val ccetHits = SpineSections.hits[SpineSections.CCET_CALL] +
            SpineSections.hits[SpineSections.CCET_VARDECL]
        assert(SpineSections.calls[SpineSections.CCET_AMBIENT].sum() == ccetHits)
        // The install-only measure closes TWO spans per ambient install.
        assert(SpineSections.calls[SpineSections.CPA_INSTALL].sum() == cpaHits * 2)
        assert(SpineSections.calls[SpineSections.CCET_INSTALL].sum() == ccetHits * 2)
        SpineSections.reset()
    }

    /**
     * (SPINE.1) round 908. The install census is the only thing that can say
     * whether the O(frames) rebuild inside each frame-ambient install PRODUCES
     * anything — a timing row cannot, and the round measured that row at
     * 54 ms (cpa) + 26 ms (ccet) of a warm rebuild.
     *
     * Round 849's law is why the positive control is here and not left implied:
     * a `rebuilt == 0` from a census hooked at the wrong place is
     * indistinguishable from a real zero, so the fixture carries a `namespace`
     * and the pin demands the rebuild produce entries inside it.
     */
    @Test
    fun `the install census counts one record per frame-ambient install`() {
        val saved = SpineSections.mode
        SpineSections.reset()
        SpineSections.mode = SpineSections.ON
        try {
            diagnose(source)
        } finally {
            SpineSections.mode = saved
        }
        // The install-only measure closes TWO spans per install (install and
        // restore); the census records ONE. That equality is what says the
        // census sits at the install itself and not on some other path.
        assert(SpineSections.installs[0] == SpineSections.calls[SpineSections.CPA_INSTALL].sum() / 2)
        assert(SpineSections.installs[1] == SpineSections.calls[SpineSections.CCET_INSTALL].sum() / 2)
        assert(SpineSections.installs[0] > 0L)
        assert(SpineSections.installs[1] > 0L)
        // An installing frame is itself on the stack, so the rebuild scans at
        // least one frame per install and its recorded max is at least one.
        assert(SpineSections.installFrameDepth[0] >= SpineSections.installs[0])
        assert(SpineSections.installFrameDepth[1] >= SpineSections.installs[1])
        assert(SpineSections.installFrameDepthMax[0] >= 1L)
        assert(SpineSections.installFrameDepthMax[1] >= 1L)
        // POSITIVE CONTROL: the fixture declares `namespace Geo` and calls into
        // it, so some install must happen under a namespace frame and the
        // rebuild must produce at least one entry. Without this a census that
        // never ran would read exactly like "the rebuild produces nothing".
        assert(SpineSections.installRebuilt[0] + SpineSections.installRebuilt[1] > 0L)
        SpineSections.reset()
    }

    @Test
    fun `the install census records nothing while the probe is off`() {
        val saved = SpineSections.mode
        SpineSections.reset()
        SpineSections.mode = SpineSections.OFF
        try {
            diagnose(source)
        } finally {
            SpineSections.mode = saved
        }
        val recorded = SpineSections.installs.sum() +
            SpineSections.installFrameDepth.sum() +
            SpineSections.installRebuilt.sum() +
            SpineSections.installSaved.sum() +
            SpineSections.installFrameDepthMax.sum()
        assert(recorded == 0L)
    }
}
