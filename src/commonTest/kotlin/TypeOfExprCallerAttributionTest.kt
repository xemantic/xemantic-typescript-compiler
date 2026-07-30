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
 * (TYPE.1) round 737: pins the caller attribution of `getTypeOfExpression`.
 *
 * The attribution answers one question the aggregate x2.7 recompute factor
 * cannot — do several handlers independently type the SAME node? — so its
 * bookkeeping has to hold three invariants, none of which the corpus suite
 * can see (the suite always runs with the probe off).
 *
 * 1. **Behaviour-free.** The mode adds a stack walk and two map writes inside
 *    the already-instrumented entry point; enabling it may never change what
 *    the compiler emits.
 * 2. **The depth discipline.** Exactly the OUTERMOST calls are attributed to a
 *    freshly walked caller, nested ones inherit it, and the depth counter
 *    returns to zero — so `outermost <= calls` and the recursion can never
 *    inflate a caller's own recompute factor.
 * 3. **The node key partitions.** Every recorded call lands on a node whose
 *    origin mask is non-empty and whose call count is at least one, and the
 *    per-site call counts sum to the recorded total.
 */
class TypeOfExprCallerAttributionTest {

    /** Types the same expressions from several handlers: a property access
     *  under a narrowing guard, a call argument, an assignment RHS and an
     *  arithmetic operand — plus a genuine TS2322, so the parity assertion
     *  covers a diagnostic-emitting path rather than a clean compile. */
    private val probeSource = """
        interface Box { value: number | undefined; label: string }
        function take(n: number): number { return n }
        function pick(b: Box): number {
            if (b.value !== undefined) {
                return take(b.value) + b.value * 2
            }
            return b.label.length
        }
        const wrong: string = 42
        export const keep = pick({ value: 1, label: "a" })
    """

    private fun runAttributed(): List<Diagnostic> {
        PassTiming.reset()
        PassTiming.enabled = true
        PassTiming.callerAttr = true
        return try {
            diagnose(probeSource)
        } finally {
            PassTiming.callerAttr = false
            PassTiming.enabled = false
        }
    }

    @Test
    fun `the caller attribution is behaviour-free`() {
        PassTiming.enabled = false
        PassTiming.callerAttr = false
        val off = diagnose(probeSource)
        val on = runAttributed()
        assert(on == off)
        // Non-vacuous: the fixture emits a real diagnostic both ways.
        assert(off.any { it.code == 2322 })
    }

    @Test
    fun `nothing is attributed while the mode is off`() {
        PassTiming.reset()
        PassTiming.enabled = true
        PassTiming.callerAttr = false
        try {
            diagnose(probeSource)
        } finally {
            PassTiming.enabled = false
        }
        // The ordinary INV.0 counters still ran, so this is not a vacuous pass.
        assert(PassTiming.getTypeOfExpressionCalls > 0L)
        assert(PassTiming.exprNodeAttrs.isEmpty())
        assert(PassTiming.callerSiteNames.isEmpty())
        assert(PassTiming.callerSiteCalls.sum() == 0L)
        assert(PassTiming.redundantOutermostCalls == 0L)
        assert(PassTiming.perfectMemoCalls == 0L)
    }

    @Test
    fun `the perfect-cache ceiling dominates the single-visit prize`() {
        runAttributed()
        val calls = PassTiming.callerSiteCalls.sum()
        val served = PassTiming.perfectMemoCalls
        val redundantOutermost = PassTiming.redundantOutermostCalls
        // A served subtree ROOT is a repeat of a node already typed at any
        // depth, so it is a strict superset of the outermost-only repeats and a
        // strict subset of all calls.
        assert(served >= redundantOutermost)
        assert(served < calls)
        assert(served > 0L)
        // Counted once per subtree: the number of served roots can never exceed
        // the number of nodes that were typed more than once.
        val repeatedNodes = PassTiming.exprNodeAttrs.values.count { it.calls > 1 }.toLong()
        val totalRepeatCalls = PassTiming.exprNodeAttrs.values.sumOf { (it.calls - 1).toLong() }
        assert(repeatedNodes > 0L)
        assert(served <= totalRepeatCalls)
    }

    @Test
    fun `the attributed run records callers and outermost calls and node origins`() {
        runAttributed()
        val calls = PassTiming.callerSiteCalls.sum()
        val outermost = PassTiming.callerSiteOutermost.sum()
        assert(calls > 0L)
        assert(calls == PassTiming.getTypeOfExpressionCalls)
        // The depth discipline: an outermost call is a strict subset of calls,
        // and the fixture nests (a call argument that is a property access), so
        // the inequality is strict.
        assert(outermost < calls)
        assert(outermost > 0L)
        // At least one caller was interned. On a platform without a stack
        // walker this is the single "(unattributed)" bucket, which is why the
        // bound is 1 rather than a JVM-specific count.
        assert(PassTiming.callerSiteNames.isNotEmpty())
        assert(PassTiming.callerSiteNames.size <= PassTiming.MAX_CALLER_SITES)
        // Every recorded node carries a non-empty origin mask and at least one
        // call, and no node claims more outermost typings than calls. Reduced
        // to scalars first: a power-assert diagram renders every captured
        // subexpression, and the node table has thousands of entries.
        val nodes = PassTiming.exprNodeAttrs.values
        val nodeCount = nodes.size
        val maskless = nodes.count { it.m0 or it.m1 or it.m2 or it.m3 == 0L }
        val callless = nodes.count { it.calls < 1 }
        val overOutermost = nodes.count { it.outermost > it.calls }
        val nodeCalls = nodes.sumOf { it.calls.toLong() }
        val nodeOutermost = nodes.sumOf { it.outermost.toLong() }
        assert(nodeCount > 0)
        assert(maskless == 0)
        assert(callless == 0)
        assert(overOutermost == 0)
        assert(nodeCalls == calls)
        assert(nodeOutermost == outermost)
        // distinct <= calls, the same shape the INV.0 counter pins.
        assert(nodeCount.toLong() <= calls)
    }

    @Test
    fun `the redundancy accounting is a subset of the outermost typings`() {
        runAttributed()
        val outermost = PassTiming.callerSiteOutermost.sum()
        val redundant = PassTiming.redundantOutermostCalls
        assert(redundant < outermost)
        // Every redundant typing is a repeat, so the count equals the total
        // outermost typings minus the nodes that were outermost-typed at all.
        val everOutermost = PassTiming.exprNodeAttrs.values.count { it.outermost > 0 }.toLong()
        assert(redundant == outermost - everOutermost)
        // The pair histogram accounts for exactly those repeats.
        assert(PassTiming.redundantPairCalls.values.sum() == redundant)
        assert(PassTiming.redundantPairNanos.size == PassTiming.redundantPairCalls.size)
    }

    @Test
    fun `the site table stays dense and the overflow bucket is reserved`() {
        runAttributed()
        // Site ids are dense: every id below the interned count has a name, and
        // no call was recorded above the interned range except in the reserved
        // overflow bucket.
        val n = PassTiming.callerSiteNames.size
        val distinctNames = PassTiming.callerSiteNames.toSet().size
        assert(n >= 1)
        assert(distinctNames == n)
        var beyond = 0L
        for (s in n until PassTiming.MAX_CALLER_SITES - 1) beyond += PassTiming.callerSiteCalls[s]
        assert(beyond == 0L)
    }
}
