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
 * (ENGINE.2e) round 792: pins the WHOLE-FUNCTION pre-gate on
 * `checkMemberAccessMissing` — "the property already resolves on the receiver's
 * own (apparent) type, so nothing this function can say is true" — and, more
 * importantly, pins the two exclusions that make it true.
 *
 * **The claim.** Every one of the function's ~42 emissions asserts that a
 * property is ABSENT from the receiver. The gate asserts it is PRESENT. The two
 * cannot both be right, so a call the gate accepts has nothing to emit and the
 * body need not run. Measured rather than assumed: `--cmamPreGate` computes the
 * gate and HONOURS NOTHING, so the run reproduces the pre-change binary, and
 * counts how many of the calls it would skip actually emit — **0 of 83,326
 * across the compiler, services and harness profiles**, against 57/83/89
 * emissions in the kept complement. `--cmamPreGateBogus` (the gate says yes
 * everywhere) is the positive control and reports 57, so the falsifier column is
 * not a dead instrument.
 *
 * **The two exclusions are where the claim is FALSE, and they are what these
 * pins defend.** (1) A later-lib member RESOLVES and is still an error: TS2550
 * says "not at this target", never "does not exist" — so the whole
 * `LIB_MIN_TARGET` name space is excluded. (2) A class receiver's two
 * sides are not cleanly separated in our member tables — an INSTANCE type
 * resolves a STATIC member — and TS2576 ("did you mean to access the static
 * member") is precisely the diagnostic that says so. Each exclusion was found by
 * the corpus, not by inspection: without them the suite loses 1 and 6 baselines
 * respectively.
 */
class PreGateGuardTest {

    /**
     * A later-lib member the embedded lib DECLARES. The access resolves, and is
     * still an error — this is exclusion (1). Deleting the
     * `LIB_MIN_TARGET_PROPS` check makes the gate skip the call and the
     * diagnostic disappears.
     */
    private val libTargetSource = """
        declare var re: RegExp;
        re.dotAll;
    """.trimIndent()

    /**
     * A STATIC member read off an INSTANCE. Our member tables resolve it, tsc
     * reports TS2576 — this is exclusion (2). Deleting the class-symbol check
     * makes the gate skip the call and the diagnostic disappears.
     */
    private val staticSideSource = """
        class K {
            static bar: number = 1;
            foo: number = 2;
        }
        declare var k1: K;
        k1.bar;
    """.trimIndent()

    /** A property that genuinely does not exist — the gate must NOT accept. */
    private val missingSource = """
        interface Box { a: number }
        declare var b: Box;
        b.nothere;
    """.trimIndent()

    /** The same receiver, reading a property that DOES exist — the skip set. */
    private val presentSource = """
        interface Box { a: number }
        declare var b: Box;
        b.a;
    """.trimIndent()

    @Test
    fun `a later-lib member still reports the lib-target hint`() {
        // Exclusion (1). `dotAll` resolves on RegExp, so a gate keyed on "the
        // property resolves" deletes TS2550 unless the LIB_MIN_TARGET name space
        // is excluded from it.
        val d = diagnose(libTargetSource, directives = "// @strict: true\n// @target: es2016")
        assert(d.any { it.code == 2550 && it.message.contains("'dotAll'") })
    }

    @Test
    fun `a static member read off an instance still reports TS2576`() {
        // Exclusion (2). Our member tables resolve `bar` through the instance
        // type, so the gate accepts this call unless class-symbol receivers are
        // excluded — and TS2576 is the diagnostic that exists to say so.
        val d = diagnose(staticSideSource)
        assert(d.any { it.code == 2576 && it.message.contains("'bar'") })
    }

    @Test
    fun `a genuinely missing property still reports TS2339`() {
        // The fault-A detector: a gate that accepted everything would pass every
        // pin except this family.
        val d = diagnose(missingSource)
        assert(d.any { it.code == 2339 && it.message.contains("'nothere'") })
    }

    @Test
    fun `a present property is silent - and the same receiver is not`() {
        // Non-vacuity in one fixture: the silent read is the gate's population,
        // and the SAME receiver shape emits when the property is absent, so the
        // silence is not a compiler that never reports on this shape at all.
        assert(diagnose(presentSource).none { it.code == 2339 })
        assert(diagnose(missingSource).count { it.code == 2339 } == 1)
    }

    /**
     * The equivalence itself, on a fixture: with the probe on, NO call the gate
     * would skip appends a diagnostic, while the kept complement does. This is
     * the pin that would catch a future widening of the gate — a corpus baseline
     * would too, but only if some baseline happens to cover the shape.
     */
    @Test
    fun `no call the gate would skip emits anything`() {
        val src = """
            declare var re2: RegExp;
            re2.dotAll;
            class K2 { static bar: number = 1; foo: number = 2; }
            declare var k2: K2;
            k2.bar;
            interface Box2 { a: number }
            declare var b2: Box2;
            b2.nothere;
            b2.a;
        """.trimIndent()
        val savedMode = CpaSections.mode
        val savedProbe = CpaSections.preGateProbe
        CpaSections.reset()
        CpaSections.mode = CpaSections.CENSUS
        CpaSections.preGateProbe = true
        try {
            diagnose(src, directives = "// @strict: true\n// @target: es2016")
            val skipped = CpaSections.preGatePass
            val skippedEmitting = CpaSections.preGatePassEmitted
            val keptEmitting = CpaSections.preGateFailEmitted
            // THE invariant.
            assert(skippedEmitting == 0L)
            // Non-vacuity, both halves: the gate accepts something here, and the
            // emission detector is alive on this fixture.
            assert(skipped > 0L)
            assert(keptEmitting > 0L)
        } finally {
            CpaSections.preGateProbe = savedProbe
            CpaSections.mode = savedMode
            CpaSections.reset()
        }
    }

    /**
     * The probe's positive CONTROL, run in-process: with the gate answering yes
     * everywhere, the falsifier column MUST become non-zero. A zero here would
     * mean the previous pin is measuring nothing — CLAUDE.md's round-765 rule.
     */
    @Test
    fun `the bogus control makes the falsifier column fire`() {
        val savedMode = CpaSections.mode
        val savedProbe = CpaSections.preGateProbe
        val savedBogus = CpaSections.preGateBogus
        CpaSections.reset()
        CpaSections.mode = CpaSections.CENSUS
        CpaSections.preGateProbe = true
        CpaSections.preGateBogus = true
        try {
            diagnose(missingSource)
            assert(CpaSections.preGatePassEmitted > 0L)
            assert(CpaSections.preGateFail == 0L)
        } finally {
            CpaSections.preGateBogus = savedBogus
            CpaSections.preGateProbe = savedProbe
            CpaSections.mode = savedMode
            CpaSections.reset()
        }
    }

}
