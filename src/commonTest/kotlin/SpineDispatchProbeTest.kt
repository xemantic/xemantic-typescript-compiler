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
 * (DISPATCH.1)(a): pins the derivation harness's invariants.
 *
 * The load-bearing one is the LAST test: the by-id dispatchers
 * ([Checker] `spineEnterHandlerById`/`spineLeaveHandlerById`) must invoke
 * exactly the same handlers, with the same guards, in the same order as the
 * production straight-line prologues — a missed or reordered arm shows up as
 * a diagnostics difference between `mode = OFF` and `mode = PROBE`. The
 * `mode = GATED` comparison additionally exercises the derived per-kind table
 * on the fixture's node kinds (the real proof is the corpus suite + the
 * profile `--listAll` runs, recorded in docs/perf/dispatch-table.md).
 */
class SpineDispatchProbeTest {

    private val source = """
        interface Shape { readonly kind: string; area(): number }
        type Maybe<T = string> = T | null
        enum Color { Red = 1, Green, Blue }
        namespace Geo {
            export const origin = { x: 0, y: 0 }
            export function dist(a: { x: number; y: number }): number {
                return Math.sqrt(a.x * a.x + a.y * a.y)
            }
        }
        abstract class Base implements Shape {
            readonly kind = "base"
            protected size = 0
            constructor(public label: string) {}
            abstract area(): number
            get twice(): number { return this.size * 2 }
            set twice(v: number) { this.size = v / 2 }
            static make(): Base | null { return null }
        }
        class Square extends Base {
            constructor(private side: number) { super("square") }
            area(): number { return this.side ** 2 }
        }
        function main(flag: boolean, items: number[], m: Maybe): void {
            let total = 0
            for (const it of items) total += it
            for (const k in Geo.origin) { total += k.length }
            if (m !== null) { total += m.length }
            const sq = new Square(3)
            const arr = [1, 2, 3].map((n) => n * 2)
            const obj = { a: 1, b: "two", ["c" + "d"]: true }
            switch (Color.Red as Color) {
                case Color.Red: total++; break
                default: total--
            }
            try {
                do { total-- } while (total > 0)
            } catch (e) {
                total = 0
            } finally {
                total = flag ? total : -total
            }
            label: while (true) { break label }
            const t = `sum=${'$'}{total} ${'$'}{sq.area()}`
            void t, arr, obj
            delete (obj as never as { a?: number }).a
        }
        main(true, [1], "x")
    """.trimIndent()

    private fun diagnosticsUnder(mode: Int): List<String> {
        // SAVE-AND-RESTORE, never "assign the default back": the mode is
        // fork-global, so a whole-suite experiment that flips the default (the
        // corpus-wide GATED verification) must survive this class running.
        val saved = SpineDispatch.mode
        SpineDispatch.reset()
        SpineDispatch.mode = mode
        try {
            return diagnose(source).map { "${it.code}@${it.start}:${it.length} ${it.message}" }
        } finally {
            SpineDispatch.mode = saved
        }
    }

    @Test
    fun `the kind-name table is index-aligned with NodeKind`() {
        assert(SpineDispatch.kindNames.size == SpineDispatch.KINDS)
        assert(SpineDispatch.kindNames[NodeKind.IDENTIFIER] == "IDENTIFIER")
        assert(SpineDispatch.kindNames[NodeKind.BINARY_EXPRESSION] == "BINARY_EXPRESSION")
        assert(SpineDispatch.kindNames[NodeKind.SOURCE_FILE] == "SOURCE_FILE")
    }

    @Test
    fun `every handler has a closure entry and every closure kind is in range`() {
        assert(SpineDispatch.enterClosure.size == SpineDispatch.enterNames.size)
        assert(SpineDispatch.leaveClosure.size == SpineDispatch.leaveNames.size)
        val bad = (SpineDispatch.enterClosure.toList() + SpineDispatch.leaveClosure.toList())
            .filterNotNull()
            .flatMap { it.toList() }
            .filter { it < 0 || it >= SpineDispatch.KINDS }
        assert(bad.isEmpty())
    }

    @Test
    fun `an OPEN handler is in every kind's table and a closed one only in its own`() {
        // ctaSpineEnter (id 0) is OPEN: a registry-keyed narrowing frame can
        // fire at any statement kind, so it must never be filtered out.
        for (k in 0 until SpineDispatch.KINDS) assert(0 in SpineDispatch.enterTable[k])
        // spineDelEnterNode's closure is exactly {DELETE_EXPRESSION}.
        val del = SpineDispatch.enterNames.indexOf("spineDelEnterNode")
        assert(del in SpineDispatch.enterTable[NodeKind.DELETE_EXPRESSION])
        assert(del !in SpineDispatch.enterTable[NodeKind.IDENTIFIER])
    }

    @Test
    fun `probe mode reproduces production diagnostics exactly`() {
        val off = diagnosticsUnder(SpineDispatch.OFF)
        val probe = diagnosticsUnder(SpineDispatch.PROBE)
        assert(probe == off)
    }

    @Test
    fun `gated mode reproduces production diagnostics exactly`() {
        val off = diagnosticsUnder(SpineDispatch.OFF)
        val gated = diagnosticsUnder(SpineDispatch.GATED)
        assert(gated == off)
    }

    @Test
    fun `the probe records the single-threaded node population it walked`() {
        val saved = SpineDispatch.mode
        SpineDispatch.reset()
        SpineDispatch.mode = SpineDispatch.PROBE
        try {
            diagnose(source)
        } finally {
            SpineDispatch.mode = saved
        }
        val nodes = SpineDispatch.kindNodes.sum()
        assert(nodes > 200)
        assert(SpineDispatch.kindNodes[NodeKind.IDENTIFIER] > 20)
        // Every handler was consulted for every node it walked.
        val consults = SpineDispatch.enterConsult[0].sum()
        assert(consults == nodes)
        SpineDispatch.reset()
    }
}
