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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * INV.0 (round 491): the opt-in pass-time instrumentation must be strictly
 * OBSERVATIONAL — enabling it may never change what the compiler emits, and a
 * disabled run must leave every counter untouched. These pin the invariant the
 * corpus suite cannot see (the suite always runs with the flag off):
 *
 *  - byte-parity: the SAME source compiled with instrumentation on and off
 *    yields IDENTICAL diagnostics (data-class equality, so position + message +
 *    chain all participate);
 *  - the enabled run actually records: named passes, expression-type
 *    invocations (with distinct ≤ calls), node-type cache classification, and
 *    flow-narrowing walk launches;
 *  - the disabled run records nothing;
 *  - [pass] attributes counters to the named pass and restores the previous
 *    attribution on exit (including nested and throwing bodies).
 */
class Inv0PassTimingTest {

    /** Exercises expression typing, flow narrowing (a guard on `b.value`),
     *  annotation resolution, and a genuine TS2322 so parity covers a
     *  diagnostic-emitting path, not just a clean compile. */
    private val probeSource = """
        interface Box { value: number | undefined; }
        function pick(b: Box): number {
            if (b.value !== undefined) {
                return b.value;
            }
            return 0;
        }
        const wrong: string = 42;
        export const keep = pick({ value: 1 });
    """

    @Test
    fun `instrumentation on and off produce identical diagnostics and the on-run records`() {
        PassTiming.enabled = false
        val off = diagnose(probeSource)
        PassTiming.reset()
        PassTiming.enabled = true
        val on = try {
            diagnose(probeSource)
        } finally {
            PassTiming.enabled = false
        }
        assertEquals(off, on, "diagnostics must be byte-identical with instrumentation on")
        // The probe source draws the TS2322 both ways (sanity that parity is non-vacuous).
        assertTrue(off.any { it.code == 2322 }, "probe must emit TS2322")

        // The enabled run recorded the dispatch and the counters.
        assertTrue(PassTiming.passNanos.size > 100, "expected the ~513-pass dispatch to record")
        assertTrue(PassTiming.passCalls.values.all { it >= 1 })
        assertTrue(PassTiming.getTypeOfExpressionCalls > 0)
        assertTrue(PassTiming.getTypeOfExpressionDistinct.isNotEmpty())
        assertTrue(
            PassTiming.getTypeOfExpressionDistinct.size.toLong() <= PassTiming.getTypeOfExpressionCalls,
            "distinct nodes can never exceed invocations",
        )
        assertTrue(
            PassTiming.typeNodeCacheable + PassTiming.typeNodeBypassed > 0,
            "annotation resolutions must be classified",
        )
        assertTrue(PassTiming.typeNodeCacheHits <= PassTiming.typeNodeCacheable)
        assertTrue(PassTiming.narrowWalks > 0, "the guard read must launch a narrowing walk")
        assertTrue(PassTiming.checkerInitNanos > 0)
        PassTiming.reset()
    }

    @Test
    fun `negative control - a disabled run records nothing`() {
        PassTiming.enabled = false
        PassTiming.reset()
        diagnose(probeSource) should {
            have(any { it.code == 2322 })
        }
        assertTrue(PassTiming.passNanos.isEmpty())
        assertEquals(0L, PassTiming.getTypeOfExpressionCalls)
        assertEquals(0L, PassTiming.typeNodeCacheable + PassTiming.typeNodeBypassed)
        assertEquals(0L, PassTiming.narrowWalks)
        assertEquals(0L, PassTiming.checkerInitNanos)
    }

    @Test
    fun `notePass accumulates time and call count per name`() {
        PassTiming.reset()
        PassTiming.notePass("alpha", 100)
        PassTiming.notePass("alpha", 50)
        PassTiming.notePass("beta", 7)
        assertEquals(150L, PassTiming.passNanos["alpha"])
        assertEquals(2, PassTiming.passCalls["alpha"])
        assertEquals(7L, PassTiming.passNanos["beta"])
        assertEquals(1, PassTiming.passCalls["beta"])
        PassTiming.reset()
        assertTrue(PassTiming.passNanos.isEmpty())
        assertTrue(PassTiming.passCalls.isEmpty())
    }

    @Test
    fun `pass attributes counters to its name and restores the previous attribution`() {
        PassTiming.reset()
        PassTiming.enabled = true
        try {
            pass("outer") {
                PassTiming.noteGetTypeOfExpression(1, 2)
                pass("inner") {
                    PassTiming.noteNarrowWalk()
                    assertEquals("inner", PassTiming.currentPass)
                }
                assertEquals("outer", PassTiming.currentPass)
                PassTiming.noteGetTypeOfExpression(3, 4)
            }
        } finally {
            PassTiming.enabled = false
        }
        assertEquals(null, PassTiming.currentPass, "attribution must be restored after the pass")
        assertEquals(2L, PassTiming.getTypeOfExpressionByPass["outer"])
        assertEquals(1L, PassTiming.narrowWalksByPass["inner"])
        assertEquals(2L, PassTiming.getTypeOfExpressionCalls)
        assertEquals(2, PassTiming.getTypeOfExpressionDistinct.size)
        assertTrue("outer" in PassTiming.passNanos && "inner" in PassTiming.passNanos)
        PassTiming.reset()
    }

    @Test
    fun `pass restores attribution when the body throws`() {
        PassTiming.reset()
        PassTiming.enabled = true
        try {
            var thrown = false
            try {
                pass("boom") { throw IllegalStateException("x") }
            } catch (_: IllegalStateException) {
                thrown = true
            }
            assertTrue(thrown)
            assertEquals(null, PassTiming.currentPass)
            assertTrue("boom" in PassTiming.passNanos, "time is recorded even on a throwing body")
        } finally {
            PassTiming.enabled = false
        }
        PassTiming.reset()
    }

    @Test
    fun `pass with instrumentation off runs the body without recording`() {
        PassTiming.reset()
        PassTiming.enabled = false
        var ran = false
        pass("ghost") { ran = true }
        assertTrue(ran)
        assertTrue(PassTiming.passNanos.isEmpty())
    }

    @Test
    fun `dump renders the table header, rows, and counters`() {
        PassTiming.reset()
        PassTiming.notePass("checkSomething", 2_500_000) // 2.5 ms
        PassTiming.noteGetTypeOfExpression(10, 20)
        PassTiming.noteNarrowWalk()
        val out = StringBuilder()
        PassTiming.dump { out.appendLine(it) }
        val text = out.toString()
        assertTrue("== xtsc pass timing (INV.0) ==" in text)
        assertTrue("checkSomething" in text)
        assertTrue("2.5" in text, "millisecond rendering with one decimal")
        assertTrue("== counters ==" in text)
        assertTrue("getTypeOfExpression: 1 calls" in text)
        assertTrue("getTypeFromTypeNode:" in text)
        assertTrue("flow-narrowing walks: 1" in text)
        PassTiming.reset()
    }
}
