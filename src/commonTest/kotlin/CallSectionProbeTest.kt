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
 * (CALL.1)(a): pins the intra-function attribution harness for
 * `checkSingleCallExpressionTypes`.
 *
 * Three invariants matter. The FIRST is that turning the probe on cannot
 * change what the compiler says — the boundaries live inside a 920-line
 * production function with 18 emission sites, so a mis-placed `at` or a
 * swapped core call would show up here as a diagnostics difference between
 * `mode = OFF` and `mode = ON`.
 *
 * The SECOND is that the sections PARTITION one invocation even though the
 * function has ~20 early `return`s: the running section is closed by
 * `CallSections.end` from the wrapper's `finally`, so the first section must
 * be reached exactly as often as the function is invoked, and every later
 * section strictly no more often than the one before it. That monotonicity is
 * what makes "invocations that returned inside section X" a count rather than
 * a guess.
 *
 * The THIRD is that the in-situ overhead calibration rides the real path: the
 * empty span is opened by `begin` and closed by the core's first boundary, so
 * it must be closed exactly once per invocation. Round 733's first draft
 * calibrated at JVM startup instead and read 40 µs against a true 42 ns,
 * making every net figure negative.
 */
class CallSectionProbeTest {

    private val source = """
        interface Box { value: number; describe(): string }
        class Impl implements Box {
            value = 1
            describe(): string { return "impl" }
            static of(n: number): Impl { return new Impl() }
        }
        function twice(n: number): number { return n * 2 }
        function pick(a: string): number
        function pick(a: number): number
        function pick(a: string | number): number { return 1 }
        function main(b: Box, list: number[], flag: boolean): number {
            let total = twice(b.value)
            total += pick(1) + pick("s")
            total += list.map((n) => n + 1).filter((n) => n > 0).length
            total += Impl.of(2).value
            total += b.describe().length
            if (flag) { total += Math.max(1, 2) }
            return total
        }
        main(new Impl(), [1, 2], true)
        // The instrumented function's OWN emissions, so an ON/OFF comparison
        // of the diagnostics is not vacuous: a wrong argument count is TS2554
        // and a wrong argument type is TS2345, both emitted from inside the
        // sections this probe partitions.
        function probe(): void {
            twice(1, 2)
            twice("nope")
        }
        probe()
    """.trimIndent()

    private fun diagnosticsUnder(mode: Int): List<String> {
        // SAVE-AND-RESTORE, never "assign the default back" — the mode is
        // fork-global (the round-619 Inv0PassTimingTest lesson).
        val saved = CallSections.mode
        CallSections.reset()
        CallSections.mode = mode
        try {
            return diagnose(source).map { "${it.code}@${it.start}:${it.length} ${it.message}" }
        } finally {
            CallSections.mode = saved
        }
    }

    private fun runProbe() {
        val saved = CallSections.mode
        CallSections.reset()
        CallSections.mode = CallSections.ON
        try {
            diagnose(source)
        } finally {
            CallSections.mode = saved
        }
    }

    @Test
    fun `the section-name table is index-aligned and complete`() {
        assert(CallSections.names.size == CallSections.N)
        assert(CallSections.FIRST_NESTED == CallSections.N_TS2348_SCAN)
        assert(CallSections.OVERHEAD == CallSections.N - 1)
        assert(CallSections.OVERHEAD_FIRST == CallSections.N - 2)
        assert(CallSections.ENTRY == CallSections.N - 3)
        assert(CallSections.OVERLOADS == CallSections.FIRST_NESTED - 1)
    }

    @Test
    fun `the probe is behaviour-free when off`() {
        val off = diagnosticsUnder(CallSections.OFF)
        val on = diagnosticsUnder(CallSections.ON)
        assert(on == off)
        // Not vacuous: the fixture emits the INSTRUMENTED function's own codes,
        // so a boundary placed inside a gate would diverge here.
        assert(off.any { it.startsWith("2554@") })
        assert(off.any { it.startsWith("2345@") })
    }

    @Test
    fun `nothing is recorded while the probe is off`() {
        val saved = CallSections.mode
        CallSections.reset()
        CallSections.mode = CallSections.OFF
        try {
            diagnose(source)
        } finally {
            CallSections.mode = saved
        }
        assert(CallSections.calls.sum() == 0L)
        assert(CallSections.invocations == 0L)
    }

    @Test
    fun `the disjoint sections partition every invocation`() {
        runProbe()
        val invocations = CallSections.invocations
        assert(invocations > 10L)
        // The first section is reached by every invocation; the wrapper
        // transition and the first empty span close once per invocation, and
        // the steady-state calibration closes seven more.
        assert(CallSections.calls[CallSections.B216] == invocations)
        assert(CallSections.calls[CallSections.ENTRY] == invocations)
        assert(CallSections.calls[CallSections.OVERHEAD_FIRST] == invocations)
        assert(CallSections.calls[CallSections.OVERHEAD] == invocations * 8)
        // The prologue sub-measure spans seven partition sections, so it is
        // closed once per invocation that reaches getCalleeType.
        assert(
            CallSections.calls[CallSections.N_PROLOGUE] ==
                CallSections.calls[CallSections.CALLEE_TYPE]
        )
        // Reach counts fall monotonically along the sequential sections: an
        // invocation can only stop reaching later ones by returning.
        for (s in CallSections.B216 until CallSections.TYPE_ARGS) {
            assert(CallSections.calls[s] >= CallSections.calls[s + 1])
        }
        // The two tail branches are mutually exclusive and together account
        // for everything that did not return earlier.
        val tail = CallSections.calls[CallSections.SINGLE_SIG] +
            CallSections.calls[CallSections.OVERLOADS]
        assert(tail <= CallSections.calls[CallSections.TYPE_ARGS])
        assert(CallSections.calls[CallSections.SINGLE_SIG] > 0L)
        assert(CallSections.calls[CallSections.OVERLOADS] > 0L)
        // The exit profile is derived, so it must sum back to the invocations.
        var exits = 0L
        for (s in 0 until CallSections.FIRST_NESTED) exits += CallSections.returnedIn(s)
        assert(exits == invocations)
        CallSections.reset()
    }

    @Test
    fun `the nested sub-measures are rarer than the sections that contain them`() {
        runProbe()
        // checkArgumentsAgainstSignature is the last statement of the
        // single-signature branch, so it runs at most once per reach of it.
        assert(CallSections.calls[CallSections.N_SINGLE_ARGS] > 0L)
        assert(
            CallSections.calls[CallSections.N_SINGLE_ARGS] <=
                CallSections.calls[CallSections.SINGLE_SIG]
        )
        // The TS2793 impl probe runs once per reach of the single-sig branch.
        assert(
            CallSections.calls[CallSections.N_IMPL_RELATED] ==
                CallSections.calls[CallSections.SINGLE_SIG]
        )
        // The overload fixture reaches checkArgumentsAgainstOverloads.
        assert(CallSections.calls[CallSections.N_OVERLOAD_ARGS] > 0L)
        assert(
            CallSections.calls[CallSections.N_OVERLOAD_ARGS] <=
                CallSections.calls[CallSections.OVERLOADS]
        )
        CallSections.reset()
    }
}
