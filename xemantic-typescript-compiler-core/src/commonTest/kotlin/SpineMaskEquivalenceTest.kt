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
 * (WARM.13b) round 888 — the per-kind spine ENTER skip mask.
 *
 * `spineEnterNode` used to consult all 46 handlers at every one of the ~857k
 * nodes of a compiler-profile run. Round 867 measured the population a per-kind
 * table would stop consulting at **32,006,965 slots x 2.286 ns = 73.2 ms**, and
 * measured the bitmask skeleton that replaces it — one array load plus one
 * register-resident bit test per handler — at ~15% of that. This is that
 * skeleton: `spineEnterMask[kindId]`, bit `h` set = handler `h` is skipped.
 *
 * ## Why these pins are shaped this way
 *
 * The mask's ONLY failure mode is a LOST diagnostic, and a lost diagnostic is
 * silent — round 792's class, visible to the corpus and to nothing else. Its
 * soundness rests on one claim per closed handler: **the handler's own
 * top-level gate rejects every kind outside its declared closure**, so calling
 * it there could not have done anything. That claim is SYNTACTIC and is
 * re-derived from today's `Checker.kt` by `scripts/spine_closure_audit.py`
 * (round 888 ran it over all 46: 6 OPEN, 40 audited, every closure a superset
 * of its handler's gate). These pins guard the three things the audit cannot:
 *
 *  1. that the mask is the exact COMPLEMENT of the dispatch table, so the two
 *     can never drift (a hand-edited mask is the obvious way to break this);
 *  2. that an OPEN handler is never skipped — the six handlers whose reach is
 *     parent-keyed, frame-identity-keyed or nodeId-registry-keyed and which
 *     round 732 proved cannot be closed by the node's own kind;
 *  3. that ON and OFF produce the SAME diagnostics on shapes that exercise the
 *     closed handlers — with `--spineMaskOff` restoring the pre-888 prologue in
 *     the SAME binary (round 795), so the two arms differ only in the mask.
 *
 * **And that the population is non-empty**, which is what stops (3) from being
 * vacuous: round 849's law says a green differential from a blind instrument
 * reads exactly like a real negative, so a pin asserts the mask actually skips
 * a large number of handlers at the kinds these fixtures are made of.
 *
 * One thing the mask deliberately DOES change: `spineCtaM3StatementAnchor` is a
 * `CtaSections` probe wrapper around its `...Core`, so under `--ctaSections` the
 * A_GATE row's boundary count falls at kinds the mask skips. That is an
 * instrument reading, not a compiler decision, and `CtaSections.mode` is OFF in
 * production — but it is the reason a section table taken with the mask on is
 * not comparable to a pre-888 one (round 793's law, one region over).
 */
class SpineMaskEquivalenceTest {

    private val enterOpen = listOf(0, 1, 2, 6, 7, 9)

    @Test
    fun `the skip mask is the exact complement of the enter dispatch table`() {
        var checked = 0
        for (kind in 0 until SpineDispatch.KINDS) {
            val run = SpineDispatch.enterTable[kind].toSet()
            val mask = SpineDispatch.enterSkipMask[kind]
            for (h in 0 until SpineDispatch.enterCount) {
                val skipped = (mask and (1L shl h)) != 0L
                assert(skipped == (h !in run))
                checked++
            }
        }
        assert(checked == SpineDispatch.KINDS * SpineDispatch.enterCount)
    }

    @Test
    fun `no bit above the handler count is ever set`() {
        for (kind in 0 until SpineDispatch.KINDS) {
            assert(SpineDispatch.enterSkipMask[kind] ushr SpineDispatch.enterCount == 0L)
        }
    }

    @Test
    fun `an OPEN handler is never skipped at any node kind`() {
        for (h in enterOpen) {
            assert(SpineDispatch.enterClosure[h] == null)
            for (kind in 0 until SpineDispatch.KINDS) {
                assert(SpineDispatch.enterSkipMask[kind] and (1L shl h) == 0L)
            }
        }
    }

    @Test
    fun `the six OPEN handlers are exactly the ones with a null closure`() {
        val open = (0 until SpineDispatch.enterCount)
            .filter { SpineDispatch.enterClosure[it] == null }
        assert(open == enterOpen)
    }

    /**
     * The population pin — without it every equivalence pin below could pass on
     * a mask that skips nothing at all (round 849).
     */
    @Test
    fun `the mask skips most handlers at the commonest node kinds`() {
        val ident = SpineDispatch.enterSkipMask[NodeKind.IDENTIFIER].countOneBits()
        val block = SpineDispatch.enterSkipMask[NodeKind.BLOCK].countOneBits()
        val call = SpineDispatch.enterSkipMask[NodeKind.CALL_EXPRESSION].countOneBits()
        assert(ident >= 30)
        assert(block >= 25)
        assert(call >= 30)
        // the mean over all kinds is the 37.35 slots/node round 867 amplified
        val total = (0 until SpineDispatch.KINDS)
            .sumOf { SpineDispatch.enterSkipMask[it].countOneBits() }
        assert(total >= 138 * 30)
    }

    // ── ON vs OFF, over shapes that reach the closed handlers ───────────────

    private fun bothArms(source: String): Pair<List<Diagnostic>, List<Diagnostic>> {
        val saved = SpineMask.off
        try {
            SpineMask.off = false
            val on = diagnose(source)
            SpineMask.off = true
            val off = diagnose(source)
            return on to off
        } finally {
            SpineMask.off = saved
        }
    }

    private fun assertSameDiagnostics(source: String, expectAtLeast: Int) {
        val (on, off) = bothArms(source)
        val onKeys = on.map { "${it.code}@${it.start}:${it.message}" }.sorted()
        val offKeys = off.map { "${it.code}@${it.start}:${it.message}" }.sorted()
        assert(onKeys == offKeys)
        assert(onKeys.size >= expectAtLeast)
    }

    @Test
    fun `masked and unmasked agree on a program exercising the closed handlers`() {
        assertSameDiagnostics(
            """
            enum E { A, B }
            const enum CE { X = 1 }
            interface Shape { kind: string }
            class Base { protected p = 1; constructor(public q: number) {} }
            class Derived extends Base {
                private r = 2
                get g(): number { return this.r }
                set s(v: number) { this.r = v }
                m(a: number, b = 3, ...rest: string[]): void {
                    let u: number
                    const c = a + b
                    if (c > 0) { u = c } else { u = 0 }
                    for (const x of rest) { console.log(x, u) }
                    switch (c) { case 1: break; default: break }
                    try { throw new Error("x") } catch (e) { }
                    const o = { ...{ a: 1 }, b: 2 }
                    const t = <number>c
                    const y = c as number
                    delete (o as any).b
                    const z = c === 1 ? 1 : 2
                    while (z > 0) { break }
                    do { break } while (z > 0)
                    const f = (n: number) => n + 1
                    f(z), f(t)
                    let w = null
                    w!.toString()
                }
            }
            function* gen(): Generator<number> { yield 1 }
            type Alias<T> = T[]
            namespace NS { export const v = 1 }
            declare module "m" { export const w: number }
            const arr: Alias<number> = [1, 2, 3]
            const idx = arr[0]
            const tpl = `x${'$'}{idx}y`
            let i = 0
            i++
            --i
            const re = /ab+c/g
            const shape: Shape = { kind: "a" }
            export { shape, arr, tpl, re, gen, NS, E, CE, Derived }
            """.trimIndent(),
            expectAtLeast = 0,
        )
    }

    @Test
    fun `masked and unmasked agree where the closed handlers actually emit`() {
        assertSameDiagnostics(
            """
            const c = 1
            c = 2
            let n: number = "s"
            function f(a) { return a }
            const o = {}
            o.missing
            enum E { A = 1 }
            const e: E.A = E.A
            class C { private x = 1 }
            new C().x
            let u: number
            u.toFixed(2)
            if (1) { }
            delete c
            """.trimIndent(),
            expectAtLeast = 5,
        )
    }

    @Test
    fun `masked and unmasked agree on the statement-gated handlers`() {
        assertSameDiagnostics(
            """
            function outer(): number {
                let a: number
                for (let i = 0; i < 3; i++) { a = i }
                for (const k in { x: 1 }) { console.log(k) }
                {
                    const b = a
                    if (b) { const d = b; console.log(d) }
                }
                label: for (;;) { break label }
                with ({}) { }
                return a
            }
            outer()
            """.trimIndent(),
            expectAtLeast = 1,
        )
    }

    @Test
    fun `negative control - the OFF arm really does restore the unmasked prologue`() {
        // If `--spineMaskOff` were inert, every equivalence pin above would be
        // comparing a binary against ITSELF and could never fail. The mask array
        // the checker reads must differ between the arms.
        val saved = SpineMask.off
        try {
            SpineMask.off = false
            val on = SpineMask.enterMask()
            SpineMask.off = true
            val off = SpineMask.enterMask()
            assert(on !== off)
            assert(off.all { it == 0L })
            assert(on.any { it != 0L })
        } finally {
            SpineMask.off = saved
        }
    }
}
