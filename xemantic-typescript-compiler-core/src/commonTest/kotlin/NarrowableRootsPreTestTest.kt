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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (NARROW.2)(f), round 855 — the pre-test that was to refuse the `any` receivers
 * the flow was never going to narrow, censused as a probe that HONOURS NOTHING,
 * and the MEASURED NEGATIVE it produced.
 *
 * Round 854 priced round 852's opening at 1.91% of a warm rebuild, of which
 * **85.6% is spent on receivers the flow never narrows** — a 1.63% CEILING for a
 * *perfect* oracle. The concrete design was a per-FILE set of the root names any
 * narrowing node could narrow ([FlowGraph.narrowableRoots]), consulted before the
 * walk. Round 854 refused to build it before censusing it, and that was right:
 * **on the compiler profile it refuses 0 of 14,117 openings.**
 *
 * **The structural reason, which is what these pins record.** Every
 * `VariableDeclaration`, `Parameter` and `BindingElement` mints a
 * `FlowAssignment`, and that node's subtree contains the declared name — so
 * **every root DECLARED in a file is in that file's narrowable set by
 * construction**, narrowed or not. The set can only ever refuse a root with no
 * declaration in the file at all (an import, a cross-file ambient). There is no
 * coarser or finer version to try: the declaration that makes a name exist is
 * itself one of the narrowing nodes the set is built from.
 *
 * **Why the tests are split the way they are.** Round 790 requires the COMPLEMENT
 * population — a refusal that DOES happen — or "zero unsound refusals" is equally
 * consistent with a dead instrument. That population is *inexpressible* through
 * `diagnose()`: it needs a root with no declaration in the file, i.e. an import,
 * and a single-file fixture's import does not resolve, so the receiver is not
 * `any` and no opening runs at all (measured: `openings=0`). So the SET is pinned
 * directly as a unit — membership and non-membership, which is exactly where the
 * design lives — and the CONSUMER is pinned by census. The refusal itself was
 * additionally verified end to end through the project CLI on a two-file project
 * (`refused=1`, falling to `0` when one `if (imported)` is added);
 * `docs/perf/narrowed-any-opening-price.md` § 4b records it.
 *
 * Everything is `PassTiming.detailed`-gated: a production compile keeps no
 * inventory and [FlowGraph.narrowableRoots] answers `null` = "unknown, refuse
 * nothing".
 */
class NarrowableRootsPreTestTest {

    // ---- the SET itself: the design, unit-pinned -------------------------

    /** Builds a graph the way the binder does, with the probe armed so the
     *  narrowing-node inventory is retained. */
    private fun rootsOf(@Language("typescript") source: String): Set<String>? {
        PassTiming.reset()
        PassTiming.enabled = true
        return try {
            FlowGraphBuilder().build(Parser(source.trimIndent(), "roots.ts").parse())
                .narrowableRoots()
        } finally {
            PassTiming.enabled = false
        }
    }

    /**
     * ROUND 856 ABLATION: this pin is a REDUNDANT GUARD, and it is kept under a
     * name that says so. Its subject `cond` is a `declare const`, i.e. a
     * `VariableDeclaration`, which mints a `FlowAssignment` whose subtree
     * contains the name — so `cond` is in the set through the ASSIGNMENT arm
     * whether or not the condition arm exists, and arm A1 (drop
     * `is FlowCondition ->`) left it GREEN. The pin that actually discriminates
     * the condition arm is the imported-name one below, whose subject has no
     * declaration in the file for the assignment arm to supply. This
     * over-determination is not a defect in the pin — it is the round-855
     * finding restated at the set level.
     */
    @Test
    fun `a condition subject is in the narrowable set - over-determined so it does NOT discriminate the FlowCondition arm`() {
        val roots = rootsOf(
            """
            declare const cond: unknown;
            export function f(): void {
                if (cond) { }
            }
            """,
        )
        assert(roots != null)
        assert("cond" in roots)
    }

    /**
     * ROUND 856 ABLATION: green under all four arms, because none of them
     * ablates the `FlowSwitchClause` / `FlowCall` arms this pin is about — its
     * discrimination is UNTESTED rather than disproven. Arm A2 is indirect
     * evidence in its favour: `sw` and `ac` are parameters, so with the
     * assignment arm deleted the only remaining route into the set is the
     * switch/call arms, and the pin held.
     */
    @Test
    fun `a name occurring only in a switch expression or an assert call is in the set too`() {
        val roots = rootsOf(
            """
            declare function assertIt(v: unknown): asserts v;
            export function f(sw: string, ac: unknown): void {
                switch (sw) { case "a": break; }
                assertIt(ac);
            }
            """,
        )
        assert(roots != null)
        assert("sw" in roots)
        assert("ac" in roots)
    }

    @Test
    fun `THE FINDING - a name that is merely DECLARED is in the set - its declaration is a FlowAssignment`() {
        // `unused` is narrowed nowhere and referenced nowhere else, so a perfect
        // oracle would exclude it. The name-keyed set cannot: `const unused = 1`
        // mints a FlowAssignment whose subtree contains `unused`. This is why the
        // profile yield is 0%.
        val roots = rootsOf(
            """
            export function f(param: number): number {
                const unused = 1;
                return param;
            }
            """,
        )
        assert(roots != null)
        assert("unused" in roots)
        assert("param" in roots)
    }

    @Test
    fun `POSITIVE CONTROL - an IMPORTED name is NOT in the set`() {
        // The complement population. Without a name the set genuinely excludes,
        // every "never refuses a narrower" reading below would be vacuous.
        val roots = rootsOf(
            """
            import { imported } from "./dep";
            export function f(param: number): number {
                imported.zzzq;
                return param;
            }
            """,
        )
        assert(roots != null)
        assert("imported" !in roots)
        // ... and the same file's declared root IS in it, so the exclusion is a
        // property of `imported`, not of a set that came back empty.
        assert("param" in roots)
    }

    @Test
    fun `POSITIVE CONTROL - the same imported name JOINS the set once a condition mentions it`() {
        // Discriminates the SET, not merely "imports are special": one added
        // `if (imported)` puts the root into a FlowCondition's subtree.
        val roots = rootsOf(
            """
            import { imported } from "./dep";
            export function f(param: number): number {
                if (imported) { return 1; }
                return param;
            }
            """,
        )
        assert(roots != null)
        assert("imported" in roots)
    }

    @Test
    fun `negative control - off the probe the graph carries no inventory at all`() {
        PassTiming.reset()
        PassTiming.enabled = false
        val roots = FlowGraphBuilder()
            .build(Parser("export function f(a: number): number { if (a) { return 1; } return 0; }", "off.ts").parse())
            .narrowableRoots()
        // `null` = "unknown", which every caller must read as "refuse nothing" —
        // an EMPTY set would mean "refuse everything" and is the one answer a
        // production build must never give.
        assert(roots == null)
    }

    // ---- the CONSUMER: the census, on the populations a fixture can reach --

    private val prelude = """
        // @useRealLibs: true
        // @strict: false
        // @target: es2015
        declare function isError(v: any): v is Error;
    """.trimIndent() + "\n"

    /** Compiles with the tier-3 counters armed, leaving them readable. The
     *  save-and-restore is not optional: `PassTiming.enabled` is fork-global and
     *  a test that assigns the default back re-enables it for every
     *  alphabetically later class (the round-619 false green). */
    private fun census(@Language("typescript") source: String): List<Diagnostic> {
        PassTiming.reset()
        PassTiming.enabled = true
        return try {
            diagnose(prelude + source.trimIndent(), directives = "")
        } finally {
            PassTiming.enabled = false
        }
    }

    @Test
    fun `a locally declared any root is never refused - the yield the profile measured as zero`() {
        census(
            """
            declare var x: any;
            x.zzzq;
            """,
        )
        val openings = PassTiming.cmamAnyOpenings
        val narrowed = PassTiming.cmamAnyNarrowed
        val refused = PassTiming.cmamAnyPreRefused
        PassTiming.reset()
        // A never-narrowed opening — the population round 854 priced at 85.6% of
        // the cost — and the pre-test keeps it anyway.
        assert(openings > 0L)
        assert(narrowed == 0L)
        assert(refused == 0L)
    }

    /**
     * ROUND 856 ABLATION: green under all four arms — a genuine soundness
     * assertion that these arms cannot exhibit. Its subject `x` reaches the set
     * through BOTH the assignment arm (`declare var x: any`) and the condition
     * arm (`if (isError(x))`), so only dropping both at once could make it
     * refuse — and a combined ablation cannot attribute (round 807). Recorded as
     * undiscriminated rather than claimed as coverage.
     */
    @Test
    fun `SOUNDNESS - an opening the flow DID narrow is never refused`() {
        val diagnostics = census(
            """
            declare var x: any;
            if (isError(x)) {
                x.zzzq;
            }
            """,
        )
        val narrowed = PassTiming.cmamAnyNarrowed
        val accepted = PassTiming.cmamAnyAccepted
        val refusedNarrowed = PassTiming.cmamAnyPreRefusedNarrowed
        val refusedAccepted = PassTiming.cmamAnyPreRefusedAccepted
        PassTiming.reset()
        diagnostics should {
            have(any { it.code == 2339 && it.message == "Property 'zzzq' does not exist on type 'Error'." })
        }
        assert(narrowed > 0L)
        assert(accepted > 0L)
        assert(refusedNarrowed == 0L)
        assert(refusedAccepted == 0L)
    }

    /**
     * ROUND 856 ABLATION: reddened uniquely by arm A3 (`preNanos = 0L`) — but
     * through its `preNanos > 0L` assertion, not through the honours-nothing
     * half its old name advertised, so the name now states the span too. The
     * walk-still-runs half is over-determined by the same-file pins above; the
     * span half is this pin's own.
     */
    @Test
    fun `the probe HONOURS NOTHING and TIMES ITSELF - the walk runs the narrow emits and the pre-test span is recorded`() {
        // A gate would have skipped the walk. The probe may only RECORD, which is
        // what makes the yield comparable to round 854's population — and what
        // makes the profile's 46 diagnostics identical to a production run's.
        census(
            """
            declare var x: any;
            if (isError(x)) {
                x.zzzq;
            }
            """,
        )
        val openings = PassTiming.cmamAnyOpenings
        val walkOnly = PassTiming.cmamAnyWalkNanos
        val preNanos = PassTiming.cmamAnyPreNanos
        PassTiming.reset()
        assert(openings > 0L)
        // The walk was launched and timed even though the pre-test had already
        // been evaluated for every one of these openings.
        assert(walkOnly > 0L)
        assert(preNanos > 0L)
    }

    /**
     * ROUND 856 ABLATION: green under all four arms by construction — none of
     * them touches the `PassTiming.enabled` gate on the consumer's counters,
     * which is what this control watches. Untested by this harness; its twin
     * above (the graph carrying no inventory) is the one arm A4 reddens.
     */
    @Test
    fun `negative control - a disabled run records no pre-test at all`() {
        PassTiming.reset()
        PassTiming.enabled = false
        val diagnostics = diagnose(
            prelude +
                """
                declare var x: any;
                if (isError(x)) {
                    x.zzzq;
                }
                """.trimIndent(),
            directives = "",
        )
        // Same emission as the armed run — the probe may not change what the
        // compiler answers.
        diagnostics should {
            have(any { it.code == 2339 && it.message == "Property 'zzzq' does not exist on type 'Error'." })
        }
        assert(PassTiming.cmamAnyPreRefused == 0L)
        assert(PassTiming.cmamAnyPreRefusedAccepted == 0L)
        assert(PassTiming.cmamAnyPreNanos == 0L)
    }
}
