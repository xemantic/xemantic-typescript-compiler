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
 * (TYPE.2)(a): pins the two-level attribution harness for
 * `spineCtaM3StatementAnchor` (level A) and `checkVarDeclAssignability`
 * (level B).
 *
 * Five invariants.
 *
 * The FIRST is that turning the probe on cannot change what the compiler says.
 * The boundaries live inside a 1,466-line production function with roughly
 * forty early `return`s, and four of them REWROTE an expression to name its
 * value so a sub-measure could bracket it — the source-type computation, the
 * two narrowing calls, the narrowing's confirming relation and the
 * assignability relation itself. A short-circuit lost in one of those rewrites
 * shows up here as a diagnostics difference between OFF and ON.
 *
 * The SECOND is that BOTH levels partition their function independently: level
 * A opens on the handler (so the eligibility gate and its parent-chain climbs
 * are a row rather than an unmeasured remainder) and level B opens on the
 * function, nested inside level A's `A_VDECL` row.
 *
 * The THIRD is the exit profile: level B's `finally` records which row the
 * invocation left in, so the exits must sum to the invocations exactly. That
 * identity is what turns "86% of the invocations are unannotated declarations"
 * into a measurement rather than an impression.
 *
 * The FOURTH is that `mode = COARSE` — the differential calibration
 * counterpart — still partitions the same wall time with only the anchors, so
 * an ON-versus-COARSE comparison prices a boundary rather than a code path.
 *
 * The FIFTH is that a nested sub-measure is never reached more often than the
 * partition row that contains it.
 */
class CtaSectionProbeTest {

    private val source = """
        interface Weak { nope?: string }
        interface Shape { kind: number; text: string }
        function make(): Shape { return { kind: 1, text: "a" } }
        function outer(flag: boolean): number {
            // an UNANNOTATED declaration — the population that turned out to be
            // 86% of the function's invocations.
            const inferred = make()
            // an ANNOTATED declaration whose initializer relates.
            const shaped: Shape = make()
            // a declaration whose initializer is a function body the handler
            // also walks (walkFunctionBodiesInExpr).
            const fn = (v: number) => v + inferred.kind
            let total = fn(shaped.kind)
            if (flag) { total = total + 1 }
            return total
        }
        outer(true)
        // The instrumented function's OWN emission, so an ON/OFF comparison of
        // the diagnostics is not vacuous: a var-decl mismatch is TS2322,
        // emitted from the elaboration section this probe partitions.
        const bad: number = "nope"
        const weak: Weak = { other: 1 }
    """.trimIndent()

    private fun diagnosticsUnder(mode: Int): List<String> {
        // SAVE-AND-RESTORE, never "assign the default back" — the mode is
        // fork-global (the round-619 Inv0PassTimingTest lesson).
        val saved = CtaSections.mode
        CtaSections.reset()
        CtaSections.mode = mode
        try {
            return diagnose(source).map { "${it.code}@${it.start}:${it.length} ${it.message}" }
        } finally {
            CtaSections.mode = saved
            CtaSections.reset()
        }
    }

    private fun runProbe(mode: Int = CtaSections.ON) {
        val saved = CtaSections.mode
        CtaSections.reset()
        CtaSections.mode = mode
        try {
            diagnose(source)
        } finally {
            CtaSections.mode = saved
        }
    }

    @Test
    fun `the section-name table is index-aligned and complete`() {
        assert(CtaSections.names.size == CtaSections.N)
        assert(CtaSections.FIRST_NESTED == CtaSections.N_GET_TYPE_OF_EXPR)
        assert(CtaSections.OVERHEAD == CtaSections.N - 1)
        assert(CtaSections.OVERHEAD_FIRST == CtaSections.N - 2)
        assert(CtaSections.B_ENTRY == CtaSections.N - 3)
        assert(CtaSections.A_ENTRY == CtaSections.N - 4)
        // The two partitions are contiguous and adjacent.
        assert(CtaSections.A_GATE == 0)
        assert(CtaSections.B_BINDPAT == CtaSections.A_RESTORE + 1)
        assert(CtaSections.B_TAIL == CtaSections.FIRST_NESTED - 1)
        // COARSE keeps one anchor per level plus both wrapper transitions, so
        // each partition still covers the whole of its function.
        assert(CtaSections.coarseAnchor[CtaSections.A_GATE])
        assert(CtaSections.coarseAnchor[CtaSections.A_VDECL])
        assert(CtaSections.coarseAnchor[CtaSections.B_BINDPAT])
        assert(!CtaSections.coarseAnchor[CtaSections.B_UNANNOT])
        assert(!CtaSections.coarseAnchor[CtaSections.B_RELATION])
    }

    @Test
    fun `the probe is behaviour-free when off`() {
        val off = diagnosticsUnder(CtaSections.OFF)
        val on = diagnosticsUnder(CtaSections.ON)
        val coarse = diagnosticsUnder(CtaSections.COARSE)
        assert(on == off)
        assert(coarse == off)
        // Not vacuous: the fixture emits the INSTRUMENTED function's own code,
        // so a lost short-circuit in a bracketed gate would diverge here.
        assert(off.any { it.startsWith("2322@") })
    }

    @Test
    fun `nothing is recorded while the probe is off`() {
        val saved = CtaSections.mode
        CtaSections.reset()
        CtaSections.mode = CtaSections.OFF
        try {
            diagnose(source)
        } finally {
            CtaSections.mode = saved
        }
        assert(CtaSections.calls.sum() == 0L)
        assert(CtaSections.invocationsA == 0L)
        assert(CtaSections.invocationsB == 0L)
        assert(CtaSections.declarations == 0L)
        assert(CtaSections.vdBucketCalls.sum() == 0L)
        assert(CtaSections.stmtKind.sum() == 0L)
    }

    @Test
    fun `level A opens on the handler so the eligibility gate is a row`() {
        runProbe()
        val invocations = CtaSections.invocationsA
        // The handler is consulted about EVERY node, so its invocation count is
        // far larger than the number of statements that anchor.
        assert(invocations > CtaSections.stmtKind.sum())
        assert(CtaSections.calls[CtaSections.A_ENTRY] == invocations)
        // The gate row is reached at least once per invocation: once on entry,
        // and once more whenever an anchor handed the clock back.
        assert(CtaSections.calls[CtaSections.A_GATE] >= invocations)
        // Every anchored statement installs the ambient exactly once.
        val anchored = CtaSections.anchorMode.sum()
        assert(anchored > 0L)
        assert(CtaSections.calls[CtaSections.A_SETUP] == anchored)
        assert(CtaSections.calls[CtaSections.A_RESTORE] == anchored)
        assert(CtaSections.stmtKind.sum() == anchored)
        // The fixture anchors declarations, an assignment and a return.
        assert(CtaSections.declarations > 0L)
        assert(CtaSections.calls[CtaSections.A_VDECL] == CtaSections.declarations)
        CtaSections.reset()
    }

    @Test
    fun `level B partitions every invocation and the exits sum to it`() {
        runProbe()
        val invocations = CtaSections.invocationsB
        assert(invocations > 0L)
        assert(CtaSections.calls[CtaSections.B_ENTRY] == invocations)
        assert(CtaSections.calls[CtaSections.B_BINDPAT] == invocations)
        // Level B is reached only from level A in this fixture.
        assert(CtaSections.invocationsBFromA == invocations)
        // Every invocation left in exactly one row.
        var exits = 0L
        for (s in CtaSections.B_BINDPAT..CtaSections.B_TAIL) exits += CtaSections.exitIn[s]
        assert(exits == invocations)
        // The cost buckets cover the same population.
        assert(CtaSections.vdBucketCalls.sum() == invocations)
        // The sections are sequential: reach falls monotonically from the
        // annotation split onward, which is what makes the exit profile a
        // partition rather than an overlay.
        for (s in CtaSections.B_RECORD until CtaSections.B_TAIL) {
            assert(CtaSections.calls[s] >= CtaSections.calls[s + 1])
        }
        CtaSections.reset()
    }

    @Test
    fun `the nested sub-measures are rarer than the rows that contain them`() {
        runProbe()
        assert(CtaSections.calls[CtaSections.N_TYPE_NODE] == CtaSections.calls[CtaSections.B_TARGET])
        assert(CtaSections.calls[CtaSections.N_CANUSE] == CtaSections.calls[CtaSections.B_RELATION])
        assert(CtaSections.calls[CtaSections.N_REL_CALL] <= CtaSections.calls[CtaSections.B_RELATION])
        assert(CtaSections.calls[CtaSections.N_GET_TYPE_OF_EXPR] <= CtaSections.calls[CtaSections.B_SRCTYPE])
        assert(CtaSections.calls[CtaSections.N_NARROW_CALL] <= CtaSections.calls[CtaSections.B_NARROW])
        // A walk that returned its input is a subset of the walks taken.
        assert(CtaSections.calls[CtaSections.N_NARROW_IDENTITY] <= CtaSections.calls[CtaSections.N_NARROW_CALL])
        CtaSections.reset()
    }

    @Test
    fun `COARSE keeps the partition while skipping the interior boundaries`() {
        runProbe(CtaSections.COARSE)
        val coarseA = CtaSections.calls[CtaSections.A_GATE] + CtaSections.calls[CtaSections.A_VDECL]
        val coarseB = CtaSections.calls[CtaSections.B_BINDPAT]
        assert(coarseA > 0L)
        assert(coarseB > 0L)
        // No interior boundary fires.
        assert(CtaSections.calls[CtaSections.A_ASSIGN] == 0L)
        assert(CtaSections.calls[CtaSections.B_UNANNOT] == 0L)
        assert(CtaSections.calls[CtaSections.N_REL_CALL] == 0L)
        val coarseTotal = CtaSections.calls.sum()
        CtaSections.reset()
        runProbe(CtaSections.ON)
        val onTotal = CtaSections.calls.sum()
        // The ON run pays strictly more boundaries for the same wall time —
        // that difference is the calibration.
        assert(onTotal > coarseTotal)
        CtaSections.reset()
    }

    // ── (ENGINE.1) level C: checkReturnAssignability ─────────────────────────

    @Test
    fun `the level-C name table is index-aligned and complete`() {
        assert(CtaSections.cNames.size == CtaSections.NC)
        assert(CtaSections.C_ENTRY == 0)
        assert(CtaSections.C_STRTAIL == CtaSections.NC - 1)
        // Source order: every row the partition walks through is between the
        // wrapper transition and the string fallback.
        assert(CtaSections.C_TARGET < CtaSections.C_SRCTYPE)
        assert(CtaSections.C_SRCTYPE < CtaSections.C_RELATION)
        assert(CtaSections.C_RELATION < CtaSections.C_ELAB)
    }

    @Test
    fun `level C partitions every invocation and the exits sum to it`() {
        runProbe()
        val invocations = CtaSections.invocationsC
        assert(invocations > 0L)
        // The partition opens on the wrapper, so every invocation is counted
        // there exactly once whatever early return it takes.
        assert(CtaSections.cCalls[CtaSections.C_ENTRY] == invocations)
        assert(CtaSections.cExitIn.sum() == invocations)
        // The fixture's annotated `return total` reaches the relation.
        assert(CtaSections.cCalls[CtaSections.C_RELATION] > 0L)
        CtaSections.reset()
    }

    @Test
    fun `level C is a single row under COARSE - its calibration counterpart`() {
        runProbe(CtaSections.COARSE)
        val invocations = CtaSections.invocationsC
        assert(invocations > 0L)
        // Exactly one boundary pair per invocation, all of it in the anchor.
        assert(CtaSections.cCalls[CtaSections.C_ENTRY] == invocations)
        assert(CtaSections.cCalls.sum() == invocations)
        assert(CtaSections.cCalls[CtaSections.C_SRCTYPE] == 0L)
        assert(CtaSections.cCalls[CtaSections.C_RELATION] == 0L)
        CtaSections.reset()
    }

    @Test
    fun `nothing is recorded for level C while the probe is off`() {
        val saved = CtaSections.mode
        CtaSections.reset()
        CtaSections.mode = CtaSections.OFF
        try {
            diagnose(source)
        } finally {
            CtaSections.mode = saved
        }
        assert(CtaSections.invocationsC == 0L)
        assert(CtaSections.cCalls.sum() == 0L)
        assert(CtaSections.cExitIn.sum() == 0L)
        CtaSections.reset()
    }

    // ── (TYPE.3) round 756: level D, walkFunctionBodiesInExpr ─────────────────
    //
    // Level D is the first RECURSIVE partition in this object, so its pins carry
    // two obligations the level-A/B/C pins did not. (1) The recursion identity:
    // a nested invocation must close its caller's row and reopen it, which makes
    // `2 * visited - outermost` the exact boundary count under COARSE. (2) The
    // arm census is pinned SHAPE BY SHAPE with the confusable arm asserted ZERO
    // (round 755) — a fixture lighting every arm at once cannot detect a swap,
    // and the two arrow arms are the pair most easily confused.

    /** Run one isolated fixture with the probe at [mode] and leave the counters. */
    private fun probeFixture(
        fixture: String,
        mode: Int = CtaSections.ON,
        directives: String = "// @strict: true",
        fileName: String = "t.ts",
    ) {
        val saved = CtaSections.mode
        CtaSections.reset()
        CtaSections.mode = mode
        try {
            diagnose(fixture, directives = directives, fileName = fileName)
        } finally {
            CtaSections.mode = saved
        }
    }

    @Test
    fun `the level-D name tables are index-aligned and complete`() {
        assert(CtaSections.dNames.size == CtaSections.ND)
        assert(CtaSections.dArmNames.size == CtaSections.NDA)
        assert(CtaSections.D_ENTRY == 0)
        assert(CtaSections.D_DISPATCH == 1)
        assert(CtaSections.D_ARGCTX == CtaSections.ND - 1)
        assert(CtaSections.DA_LEAF == CtaSections.NDA - 1)
        // The sentinel must not collide with "the caller had no open row".
        assert(CtaSections.D_INACTIVE == -2)
    }

    @Test
    fun `level D visits every node of the walked expression and recurses`() {
        runProbe()
        val outermost = CtaSections.outermostD
        val visited = CtaSections.invocationsD
        assert(outermost > 0L)
        // The cross-check that makes the partition believable: level D's
        // outermost invocations are exactly level A's A_WALKFN row openings,
        // measured from opposite sides of the same call.
        assert(outermost == CtaSections.calls[CtaSections.A_WALKFN])
        // The walk descends, so it visits strictly more nodes than it is entered.
        assert(visited > outermost)
        assert(CtaSections.maxDepthD >= 2)
        // Every visited node took exactly one `when` arm.
        assert(CtaSections.dArm.sum() == visited)
        CtaSections.reset()
    }

    @Test
    fun `level D is a single row under COARSE - its calibration counterpart`() {
        runProbe(CtaSections.COARSE)
        val visited = CtaSections.invocationsD
        val outermost = CtaSections.outermostD
        assert(visited > 0L)
        // beginD charges the caller's row for every NESTED invocation and endD
        // charges this invocation's row always, so the boundary count is exact.
        assert(CtaSections.dCalls[CtaSections.D_ENTRY] == 2 * visited - outermost)
        assert(CtaSections.dCalls.sum() == CtaSections.dCalls[CtaSections.D_ENTRY])
        assert(CtaSections.dCalls[CtaSections.D_ARROW] == 0L)
        assert(CtaSections.dCalls[CtaSections.D_CTXPARAMS] == 0L)
        // The census still works under COARSE — it costs no timestamp.
        assert(CtaSections.dArm.sum() == visited)
        CtaSections.reset()
    }

    @Test
    fun `nothing is recorded for level D while the probe is off`() {
        val saved = CtaSections.mode
        CtaSections.reset()
        CtaSections.mode = CtaSections.OFF
        try {
            diagnose(source)
        } finally {
            CtaSections.mode = saved
        }
        assert(CtaSections.invocationsD == 0L)
        assert(CtaSections.invocationsDOutside == 0L)
        assert(CtaSections.dCalls.sum() == 0L)
        assert(CtaSections.dArm.sum() == 0L)
        assert(!CtaSections.inWalkFn)
        CtaSections.reset()
    }

    @Test
    fun `an arrow with a BLOCK body is descended into`() {
        probeFixture(
            """
            function host(): void {
                const g = () => { const inner = function () { const deep = 1; return deep } ; return inner }
            }
            """.trimIndent()
        )
        assert(CtaSections.dArm[CtaSections.DA_ARROW_BLOCK] > 0L)
        assert(CtaSections.dCalls[CtaSections.D_ARROW] > 0L)
        // The confusable arm must stay silent: this arrow has no expression body.
        assert(CtaSections.dArm[CtaSections.DA_ARROW_EXPR] == 0L)
        // And the walk reached the nested function expression THROUGH the block.
        assert(CtaSections.dArm[CtaSections.DA_FNEXPR] > 0L)
        CtaSections.reset()
    }

    @Test
    fun `an arrow with an EXPRESSION body is reached and never descended into`() {
        probeFixture(
            """
            function host(): void {
                const g = () => (function () { const deep = 1; return deep })
            }
            """.trimIndent()
        )
        assert(CtaSections.dArm[CtaSections.DA_ARROW_EXPR] > 0L)
        // The measured finding: the arm walks NOTHING, so the function
        // expression inside the arrow's body is never reached by this walker
        // and its body is never handed to checkFunctionBody.
        assert(CtaSections.dArm[CtaSections.DA_FNEXPR] == 0L)
        assert(CtaSections.dArm[CtaSections.DA_ARROW_BLOCK] == 0L)
        assert(CtaSections.dCalls[CtaSections.D_ARROW] == 0L)
        assert(CtaSections.dCalls[CtaSections.D_FNEXPR] == 0L)
        CtaSections.reset()
    }

    @Test
    fun `an expression-bodied arrow hides a nested body mismatch - a known gap`() {
        // The census says 874 of the 1,510 function-like nodes this walker
        // reaches on the compiler profile are expression-bodied arrows, whose
        // arm walks NOTHING. The obvious defence — "the spine anchors the inner
        // statement anyway" — is TESTED here rather than assumed, and it is
        // FALSE. Three controls first, so the gap is attributed to the arrow's
        // body shape and to nothing else.
        diagnose("const s: string = 5") should { have(any { it.code == 2322 }) }
        diagnose(
            """
            const f = function () { const s: string = 5; return s }
            """.trimIndent()
        ) should { have(any { it.code == 2322 }) }
        diagnose(
            """
            const f = () => { const s: string = 5; return s }
            """.trimIndent()
        ) should { have(any { it.code == 2322 }) }
        // THE GAP, pinned as it is today: swap the block body for an expression
        // body and the same mismatch goes silent. tsc reports it. This pin fails
        // — correctly and loudly — the day the arm learns to descend.
        diagnose(
            """
            const f = () => (function () { const s: string = 5; return s })
            """.trimIndent()
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `the callee parameter resolution runs at every call the walk reaches`() {
        probeFixture(
            """
            function h(a: number): number { return a }
            function host(): void {
                const r = h(h(h(1)))
            }
            """.trimIndent()
        )
        val calls = CtaSections.dArm[CtaSections.DA_CALL]
        assert(calls == 3L)
        // The measured shape: calleeDeclaredCtxParams is UNCONDITIONAL — it
        // resolves the callee's declared signature once per call/new the walk
        // reaches, whether or not any argument is a function expression.
        assert(CtaSections.dCalls[CtaSections.D_CTXPARAMS] == calls + CtaSections.dArm[CtaSections.DA_NEW])
        // Not one of these three calls has a function argument to contextualize.
        assert(CtaSections.dArm[CtaSections.DA_FNEXPR] == 0L)
        assert(CtaSections.dArm[CtaSections.DA_ARROW_BLOCK] == 0L)
        CtaSections.reset()
    }

    @Test
    fun `the object-literal member rows are silent on a TypeScript file`() {
        probeFixture(
            """
            function host(): void {
                const o = { m() { const v = 1; return v } }
            }
            """.trimIndent()
        )
        assert(CtaSections.dArm[CtaSections.DA_OBJLIT] > 0L)
        // B150/B585 are gated to JS-like files, so on .ts the object-literal
        // `this` type is never minted and no member body is walked.
        assert(CtaSections.dCalls[CtaSections.D_OBJLIT_THIS] == 0L)
        assert(CtaSections.dCalls[CtaSections.D_OBJLIT_MEM] == 0L)
        assert(CtaSections.dCalls[CtaSections.D_OBJLIT_CTX] == 0L)
        CtaSections.reset()
        // The control that makes the zeros a measurement rather than a dead
        // probe: the SAME shape in a .js file lights both rows.
        probeFixture(
            """
            function host() {
                const o = { m() { const v = 1; return v } }
            }
            """.trimIndent(),
            directives = "// @allowJs: true\n// @checkJs: true",
            fileName = "t.js",
        )
        assert(CtaSections.dArm[CtaSections.DA_OBJLIT] > 0L)
        assert(CtaSections.dCalls[CtaSections.D_OBJLIT_THIS] > 0L)
        assert(CtaSections.dCalls[CtaSections.D_OBJLIT_MEM] > 0L)
        CtaSections.reset()
    }
}
