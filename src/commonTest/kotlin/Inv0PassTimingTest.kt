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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

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
        assert(on == off)
        // The probe source draws the TS2322 both ways (sanity that parity is non-vacuous).
        assert(off.any { it.code == 2322 })

        // The enabled run recorded the dispatch and the counters.
        assert(PassTiming.passNanos.size > 100)
        assert(PassTiming.passCalls.values.all { it >= 1 })
        assert(PassTiming.getTypeOfExpressionCalls > 0)
        assert(PassTiming.getTypeOfExpressionDistinct.isNotEmpty())
        assert(PassTiming.getTypeOfExpressionDistinct.size.toLong() <= PassTiming.getTypeOfExpressionCalls)
        assert(PassTiming.typeNodeCacheable + PassTiming.typeNodeBypassed > 0)
        assert(PassTiming.typeNodeCacheHits <= PassTiming.typeNodeCacheable)
        assert(PassTiming.narrowWalks > 0)
        assert(PassTiming.checkerInitNanos > 0)
        PassTiming.reset()
    }

    @Test
    fun `negative control - a disabled run records nothing`() {
        PassTiming.enabled = false
        PassTiming.reset()
        diagnose(probeSource) should {
            have(any { it.code == 2322 })
        }
        assert(PassTiming.passNanos.isEmpty())
        assert(PassTiming.getTypeOfExpressionCalls == 0L)
        assert(PassTiming.typeNodeCacheable + PassTiming.typeNodeBypassed == 0L)
        assert(PassTiming.narrowWalks == 0L)
        assert(PassTiming.checkerInitNanos == 0L)
    }

    @Test
    fun `notePass accumulates time and call count per name`() {
        PassTiming.reset()
        PassTiming.notePass("alpha", 100)
        PassTiming.notePass("alpha", 50)
        PassTiming.notePass("beta", 7)
        assert(PassTiming.passNanos["alpha"] == 150L)
        assert(PassTiming.passCalls["alpha"] == 2)
        assert(PassTiming.passNanos["beta"] == 7L)
        assert(PassTiming.passCalls["beta"] == 1)
        PassTiming.reset()
        assert(PassTiming.passNanos.isEmpty())
        assert(PassTiming.passCalls.isEmpty())
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
                    assert(PassTiming.currentPass == "inner")
                }
                assert(PassTiming.currentPass == "outer")
                PassTiming.noteGetTypeOfExpression(3, 4)
            }
        } finally {
            PassTiming.enabled = false
        }
        assert(PassTiming.currentPass == null)
        assert(PassTiming.getTypeOfExpressionByPass["outer"] == 2L)
        assert(PassTiming.narrowWalksByPass["inner"] == 1L)
        assert(PassTiming.getTypeOfExpressionCalls == 2L)
        assert(PassTiming.getTypeOfExpressionDistinct.size == 2)
        assert("outer" in PassTiming.passNanos && "inner" in PassTiming.passNanos)
        PassTiming.reset()
    }

    @Test
    fun `pass attributes emitted diagnostics via the diagnosticsSize view`() {
        PassTiming.reset()
        PassTiming.enabled = true
        try {
            var size = 0
            PassTiming.diagnosticsSize = { size }
            pass("emitter") { size += 3 }
            pass("silent") { }
            pass("retractor") { size -= 2 } // truncation-style shrink clamps to no entry
            pass("outerEmit") {
                size += 1
                pass("innerEmit") { size += 4 }
            }
        } finally {
            PassTiming.enabled = false
        }
        assert(PassTiming.diagsByPass["emitter"] == 3)
        assert(PassTiming.diagsByPass["silent"] == null)
        assert(PassTiming.diagsByPass["retractor"] == null)
        assert(PassTiming.diagsByPass["innerEmit"] == 4)
        // Documented census semantics: a nested pass's emissions ALSO count into
        // the enclosing pass (safe in the KEEP direction for the tail triage).
        assert(PassTiming.diagsByPass["outerEmit"] == 5)
        PassTiming.reset()
        assert(PassTiming.diagsByPass.isEmpty())
        assert(PassTiming.diagnosticsSize == null)
    }

    @Test
    fun `node-kind histogram counts indexed nodes when enabled and stays empty when disabled`() {
        PassTiming.enabled = false
        PassTiming.reset()
        diagnose(probeSource)
        assert(PassTiming.nodeKindHistogram.isEmpty())
        PassTiming.reset()
        PassTiming.enabled = true
        try {
            diagnose(probeSource)
        } finally {
            PassTiming.enabled = false
        }
        val identifiers = PassTiming.nodeKindHistogram["Identifier"] ?: 0L
        assert(identifiers > 0)
        assert((PassTiming.nodeKindHistogram["IfStatement"] ?: 0L) >= 1L)
        PassTiming.reset()
    }

    @Test
    fun `pass skips a disabled pass body and runs the others`() {
        PassTiming.reset()
        // SAVE-AND-RESTORE, never clear: under a PassLab experiment run the fork-global
        // disable set is live suite-wide — a cleanup that assigns emptySet() silently
        // re-enables the lab-disabled passes for every test class that runs after this
        // one (alphabetically: the whole generated corpus), turning a batch-disable
        // experiment into a false green (this is exactly how round 619's Phase B
        // "suite green with 23 passes disabled" verdict was manufactured).
        val saved = PassTiming.disabledPasses
        PassTiming.disabledPasses = saved + "ghostDisabled"
        try {
            var disabledRan = false
            var otherRan = false
            pass("ghostDisabled") { disabledRan = true }
            pass("other") { otherRan = true }
            assert(!disabledRan)
            assert(otherRan)
        } finally {
            PassTiming.disabledPasses = saved
        }
        // With ghostDisabled no longer in the set, the same pass runs again.
        var ran = false
        pass("ghostDisabled") { ran = true }
        assert(ran)
        PassTiming.reset()
    }

    @Test
    fun `censusMode records emission deltas without the full instrumentation`() {
        PassTiming.reset()
        PassTiming.censusByPass.clear() // lab-owned, reset-immune — tests clear it directly
        PassTiming.enabled = false
        // Save-and-restore (not clear) — the same fork-global lab-state rule as
        // disabledPasses: under a census lab run, a cleanup that assigns false would
        // silently stop the census for every later test class.
        val savedCensus = PassTiming.censusMode
        PassTiming.censusMode = true
        try {
            var size = 0
            PassTiming.diagnosticsSize = { size }
            pass("lightEmitter") { size += 2 }
            pass("lightSilent") { }
            // The census BLINDNESS pin (round 620): only net-POSITIVE deltas are
            // recorded, so a wipe-and-pin pass (remove N, re-add N — net 0) and a
            // pure retractor (net < 0) are census-silent while fully load-bearing.
            // Census silence is therefore deletion evidence ONLY for pure
            // diagnostics.add passes — see the round-619 census artifact correction.
            pass("wipeAndPin") { size -= 2; size += 2 }
            pass("retractor") { size -= 1 }
        } finally {
            PassTiming.censusMode = savedCensus
        }
        assert(PassTiming.censusByPass["lightEmitter"] == 2)
        assert(PassTiming.censusByPass["lightSilent"] == null)
        assert(PassTiming.censusByPass["wipeAndPin"] == null)
        assert(PassTiming.censusByPass["retractor"] == null)
        assert(PassTiming.passNanos.isEmpty())
        assert(PassTiming.getTypeOfExpressionCalls == 0L)
        // reset() must NOT clear the census accumulator (the mid-suite wipe hazard).
        PassTiming.reset()
        assert(PassTiming.censusByPass["lightEmitter"] == 2)
        PassTiming.censusByPass.clear()
    }

    @Test
    fun `censusMode end-to-end - a compile attributes emissions to passes and stays byte-identical`() {
        PassTiming.enabled = false
        PassTiming.reset()
        PassTiming.censusByPass.clear()
        val off = diagnose(probeSource)
        val savedCensus = PassTiming.censusMode
        PassTiming.censusMode = true
        val on = try {
            diagnose(probeSource)
        } finally {
            PassTiming.censusMode = savedCensus
        }
        assert(on == off)
        assert(PassTiming.censusByPass.isNotEmpty())
        assert(PassTiming.passNanos.isEmpty())
        PassTiming.censusByPass.clear()
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
            assert(thrown)
            assert(PassTiming.currentPass == null)
            assert("boom" in PassTiming.passNanos)
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
        assert(ran)
        assert(PassTiming.passNanos.isEmpty())
    }

    @Test
    fun `dump renders the table header - rows - and counters`() {
        PassTiming.reset()
        PassTiming.notePass("checkSomething", 2_500_000) // 2.5 ms
        PassTiming.noteGetTypeOfExpression(10, 20)
        PassTiming.noteNarrowWalk()
        PassTiming.diagsByPass["checkSomething"] = 2
        PassTiming.noteNodeKind("x") // any instance — keyed by simpleName ("String")
        val out = StringBuilder()
        PassTiming.dump { out.appendLine(it) }
        val text = out.toString()
        assert("== xtsc pass timing (INV.0) ==" in text)
        assert("checkSomething" in text)
        assert("2.5" in text)
        assert("== emissions by pass" in text)
        assert("== node kinds" in text)
        assert("total 1 nodes" in text)
        assert("== counters ==" in text)
        assert("getTypeOfExpression: 1 calls" in text)
        assert("getTypeFromTypeNode:" in text)
        assert("flow-narrowing walks: 1" in text)
        PassTiming.reset()
    }
}
