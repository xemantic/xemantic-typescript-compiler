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
 * (CALL.4): pins the intra-`applyConditionNarrowing` attribution harness.
 *
 * The measurement it exists for is a split of the dispatcher's cost by leaf,
 * and that split is only trustworthy if two things hold.
 *
 * The FIRST is that turning the probe on cannot change what the compiler says.
 * Eleven leaf calls inside the dispatcher were rewritten to go through a
 * bracketing wrapper, three of them inside `return` expressions and two inside
 * a `while` loop that scans an optional chain — a lost short-circuit or a
 * swapped argument in any of those shows up here as a diagnostics difference
 * between OFF and each of the three probe modes.
 *
 * The SECOND is that `narrowArmOf` — the arm census — is a faithful mirror of
 * the dispatcher's own `when`. It is a separate function, so it can drift, and
 * a drift would silently re-label every arm in a published table. A fixture
 * that lights every arm at once cannot detect a SWAP between two arms; so each
 * shape below is compiled ALONE and asserted to light its own arm and to leave
 * the arms it could plausibly be confused with at zero.
 */
class NarrowSectionProbeTest {

    /** Condition-free on purpose: a prelude `if` would pollute the arm census. */
    private val prelude = """
        class Dog { bark(): void {} }
        class Cat { meow(): void {} }
        interface Sq { kind: "sq"; side: number }
        interface Ci { kind: "ci"; r: number }
        type Shape = Sq | Ci
        function isSq(s: Shape): s is Sq { return true }
        function nextDog(): Dog | null { return null }
        declare const arr: (string | null)[]

    """.trimIndent() + "\n"

    private fun source(body: String) = prelude +
        "function probe(v: string | null, n: number, a: Dog | Cat, s: Shape, " +
        "o: { p: string | null }) {\n" + body + "\n}\nprobe(null, 1, new Dog(), " +
        "{ kind: \"sq\", side: 1 }, { p: null })\n"

    /**
     * Run [body] under [mode] and return a copy of the arm census. SAVE-AND-
     * RESTORE, never "assign the default back" — the mode is fork-global (the
     * round-619 `Inv0PassTimingTest` lesson).
     */
    private fun armsFor(body: String, mode: Int = NarrowSections.ON): LongArray {
        val saved = NarrowSections.mode
        NarrowSections.reset()
        NarrowSections.mode = mode
        try {
            diagnose(source(body))
            return NarrowSections.armAll.copyOf()
        } finally {
            NarrowSections.mode = saved
            NarrowSections.reset()
        }
    }

    private fun diagnosticsUnder(body: String, mode: Int): List<String> {
        val saved = NarrowSections.mode
        NarrowSections.reset()
        NarrowSections.mode = mode
        try {
            return diagnose(source(body)).map { "${it.code}@${it.start}:${it.length}" }
        } finally {
            NarrowSections.mode = saved
            NarrowSections.reset()
        }
    }

    /** Every arm, plus a narrowing-dependent emission so an ON/OFF diff can fail. */
    private val everyArm = """
            if ((v)) { v.length }
            if (v!) { }
            if (!v) { } else { v.length }
            if (v || n > 1) { }
            if (v && n > 1) { v.length }
            if (v ?? "x") { }
            if (s.kind === "sq") { }
            if (v !== null) { v.length }
            let d: Dog | null = null
            while (d = nextDog()) { d.bark() }
            if (a instanceof Dog) { a.bark() }
            if ("side" in s) { }
            if (isSq(s)) { s.r }
            if (o.p) { o.p.length }
            if (arr[0]) { }
    """.trimIndent()

    @Test
    fun `the arm and row name tables are index-aligned and complete`() {
        assert(NarrowSections.armNames.size == NarrowSections.NA)
        assert(NarrowSections.cNames.size == NarrowSections.NC)
        assert(NarrowSections.names.size == NarrowSections.N)
        assert(NarrowSections.A_WRAPPER == 0)
        assert(NarrowSections.A_OTHER == NarrowSections.NA - 1)
        assert(NarrowSections.C_REFPATH == NarrowSections.NC - 1)
    }

    @Test
    fun `the probe is behaviour-free in all three modes`() {
        val off = diagnosticsUnder(everyArm, NarrowSections.OFF)
        assert(diagnosticsUnder(everyArm, NarrowSections.ON) == off)
        assert(diagnosticsUnder(everyArm, NarrowSections.COARSE) == off)
        assert(diagnosticsUnder(everyArm, NarrowSections.DEEP) == off)
        // Not vacuous: `if (isSq(s)) { s.r }` is TS2339 only because the call
        // predicate narrowed `Shape` to `Sq` — the very leaf this probe times.
        assert(off.any { it.startsWith("2339@") })
    }

    @Test
    fun `nothing is recorded while the probe is off`() {
        val saved = NarrowSections.mode
        NarrowSections.reset()
        NarrowSections.mode = NarrowSections.OFF
        try {
            diagnose(source(everyArm))
        } finally {
            NarrowSections.mode = saved
        }
        assert(NarrowSections.armAll.sum() == 0L)
        assert(NarrowSections.acnInvocations == 0L)
        assert(NarrowSections.condCalls == 0L)
        assert(NarrowSections.cCallsNarrow.sum() == 0L)
        assert(NarrowSections.cCallsIdent.sum() == 0L)
        assert(NarrowSections.calls.sum() == 0L)
        NarrowSections.reset()
    }

    @Test
    fun `the arm census counts every invocation exactly once`() {
        val saved = NarrowSections.mode
        NarrowSections.reset()
        NarrowSections.mode = NarrowSections.ON
        try {
            diagnose(source(everyArm))
            assert(NarrowSections.armAll.sum() == NarrowSections.acnInvocations)
            // The outermost calls are a SUBSET of the invocations: every arm
            // that recurses adds more, and only the FlowCondition entry point
            // is bracketed.
            assert(NarrowSections.acnInvocations >= NarrowSections.condCalls)
            assert(NarrowSections.condCalls > NarrowSections.condIdentity)
            // The fold conserves: a narrowing outermost call contributes its
            // scratch to the narrowing columns and nothing else.
            assert(NarrowSections.acnInvNarrow > 0L)
            assert(NarrowSections.acnInvIdent > 0L)
            assert(NarrowSections.cCallsNarrow[NarrowSections.C_CALLPRED] > 0L)
        } finally {
            NarrowSections.mode = saved
            NarrowSections.reset()
        }
    }

    @Test
    fun `every arm is reachable`() {
        val arms = armsFor(everyArm)
        for (a in 0 until NarrowSections.NA) assert(arms[a] > 0L)
    }

    // -- the drift detector: one shape at a time, confusable arms must be zero --

    @Test
    fun `instanceof lights only the instanceof arm`() {
        val arms = armsFor("if (a instanceof Dog) { a.bark() }")
        assert(arms[NarrowSections.A_INSTOF] > 0L)
        assert(arms[NarrowSections.A_IN] == 0L)
        assert(arms[NarrowSections.A_EQUALITY] == 0L)
        assert(arms[NarrowSections.A_CALL] == 0L)
        assert(arms[NarrowSections.A_BIN_OTHER] == 0L)
    }

    @Test
    fun `the in operator lights only the in arm`() {
        // The body MUST read a narrowable reference: the arm census only sees a
        // condition that a flow walk actually passes through.
        val arms = armsFor("""if ("side" in s) { s.side }""")
        assert(arms[NarrowSections.A_IN] > 0L)
        assert(arms[NarrowSections.A_INSTOF] == 0L)
        assert(arms[NarrowSections.A_EQUALITY] == 0L)
        assert(arms[NarrowSections.A_BIN_OTHER] == 0L)
    }

    @Test
    fun `a predicate call lights only the call arm`() {
        val arms = armsFor("if (isSq(s)) { s.side }")
        assert(arms[NarrowSections.A_CALL] > 0L)
        assert(arms[NarrowSections.A_IDENT] == 0L)
        assert(arms[NarrowSections.A_PROPACCESS] == 0L)
        assert(arms[NarrowSections.A_EQUALITY] == 0L)
    }

    @Test
    fun `an equality test lights only the equality arm`() {
        val arms = armsFor("""if (s.kind === "sq") { s.side }""")
        assert(arms[NarrowSections.A_EQUALITY] > 0L)
        // narrowByEquality is a LEAF: the dispatcher never descends into the
        // property access on its left.
        assert(arms[NarrowSections.A_PROPACCESS] == 0L)
        assert(arms[NarrowSections.A_BIN_OTHER] == 0L)
        assert(arms[NarrowSections.A_CALL] == 0L)
    }

    /**
     * `n` is not a union, so nothing walks for it; the arm is reached by a
     * DIFFERENT reference walking past the condition — which is the only way
     * this arm is ever reached, since it narrows nothing by construction.
     */
    @Test
    fun `a non-narrowing binary operator lights the other-operator arm`() {
        val arms = armsFor("if (n > 1) { v.length }")
        assert(arms[NarrowSections.A_BIN_OTHER] > 0L)
        assert(arms[NarrowSections.A_EQUALITY] == 0L)
        assert(arms[NarrowSections.A_LOGICAL] == 0L)
        assert(arms[NarrowSections.A_ASSIGN] == 0L)
    }

    /**
     * The walked reference is `v`, not `d`: a walk for `d` itself never reaches
     * the condition at all — the fast-forward loop breaks at the `FlowAssignment`
     * the same expression produced, and `narrowByAssignmentRhs` answers instead.
     */
    @Test
    fun `a truthy assignment lights the assignment arm`() {
        val arms = armsFor("let d: Dog | null = null\nif (d = nextDog()) { v.length }")
        assert(arms[NarrowSections.A_ASSIGN] > 0L)
        assert(arms[NarrowSections.A_EQUALITY] == 0L)
        assert(arms[NarrowSections.A_BIN_OTHER] == 0L)
    }

    @Test
    fun `a logical condition lights the logical arm and descends into both operands`() {
        val arms = armsFor("if (v && n > 1) { v.length }")
        assert(arms[NarrowSections.A_LOGICAL] > 0L)
        assert(arms[NarrowSections.A_IDENT] > 0L)
        assert(arms[NarrowSections.A_BIN_OTHER] > 0L)
        assert(arms[NarrowSections.A_EQUALITY] == 0L)
    }

    @Test
    fun `a prefix bang lights the prefix arm and descends into its operand`() {
        val arms = armsFor("if (!v) { } else { v.length }")
        assert(arms[NarrowSections.A_PREFIX] > 0L)
        assert(arms[NarrowSections.A_IDENT] > 0L)
        assert(arms[NarrowSections.A_LOGICAL] == 0L)
        assert(arms[NarrowSections.A_WRAPPER] == 0L)
    }

    @Test
    fun `a parenthesised condition lights the wrapper arm and descends`() {
        val arms = armsFor("if ((v)) { v.length }")
        assert(arms[NarrowSections.A_WRAPPER] > 0L)
        assert(arms[NarrowSections.A_IDENT] > 0L)
        assert(arms[NarrowSections.A_PREFIX] == 0L)
    }

    @Test
    fun `a property access lights the property-access arm`() {
        val arms = armsFor("if (o.p) { o.p.length }")
        assert(arms[NarrowSections.A_PROPACCESS] > 0L)
        assert(arms[NarrowSections.A_IDENT] == 0L)
        assert(arms[NarrowSections.A_CALL] == 0L)
        assert(arms[NarrowSections.A_EQUALITY] == 0L)
    }

    @Test
    fun `an element access lights the other-kind arm`() {
        val arms = armsFor("if (arr[0]) { arr[0].length }")
        assert(arms[NarrowSections.A_OTHER] > 0L)
        assert(arms[NarrowSections.A_PROPACCESS] == 0L)
        assert(arms[NarrowSections.A_IDENT] == 0L)
    }

    /**
     * The (CALL.4) headline: 80% of a genuinely-narrowing call is
     * `narrowByCallPredicate`. The pin is structural rather than numeric — a
     * predicate call must attribute to the C_CALLPRED row and to nothing else
     * that resolves — so that a future refactor moving the leaf out of the
     * dispatcher makes this fail rather than silently re-attribute.
     */
    @Test
    fun `a predicate call attributes to the call-predicate row`() {
        val saved = NarrowSections.mode
        NarrowSections.reset()
        NarrowSections.mode = NarrowSections.ON
        try {
            diagnose(source("if (isSq(s)) { s.side }"))
            assert(NarrowSections.cCallsNarrow[NarrowSections.C_CALLPRED] > 0L)
            assert(NarrowSections.cCallsNarrow[NarrowSections.C_EQ] == 0L)
            assert(NarrowSections.cCallsNarrow[NarrowSections.C_INSTOF] == 0L)
            assert(NarrowSections.cCallsIdent[NarrowSections.C_EQ] == 0L)
            assert(NarrowSections.cCallsIdent[NarrowSections.C_ALIAS] == 0L)
        } finally {
            NarrowSections.mode = saved
            NarrowSections.reset()
        }
    }

    /**
     * The alias-inline path is why round 736's rejected "does this condition
     * mention the name" pre-test is not merely in-band but UNSOUND: the
     * condition here mentions only `isFrag`, and the narrowing it produces for
     * `s` is real.
     */
    @Test
    fun `an aliased condition narrows though it never mentions the reference`() {
        val diagnostics = diagnose(source("const isFrag = isSq(s)\nif (isFrag) { s.r }"))
        assert(diagnostics.any { it.code == 2339 })
        val saved = NarrowSections.mode
        NarrowSections.reset()
        NarrowSections.mode = NarrowSections.ON
        try {
            diagnose(source("const isFrag = isSq(s)\nif (isFrag) { s.side }"))
            assert(NarrowSections.cCallsNarrow[NarrowSections.C_ALIAS] > 0L)
        } finally {
            NarrowSections.mode = saved
            NarrowSections.reset()
        }
    }
}
