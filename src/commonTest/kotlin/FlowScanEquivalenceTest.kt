/*
 *  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 *  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 *  xemantic-typescript-compiler - a conformant TypeScript compiler and type
 *  checker that runs on JVM, native, and WebAssembly
 *  Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, version 3 of the License.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public
 *  License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 *  As a special exception, this file contains Helper Code covered by the
 *  xemantic-typescript-compiler Output Exception; additional permissions
 *  are granted as described in the file LICENSE-EXCEPTION.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (FRONT.2) round 801 — the B464 reassignment scanner, the one measured
 * concentration inside `Binder.bind`.
 *
 * **Why this is worth pinning at all.** Step 1's re-derived map showed the
 * ~400 tail passes are FLAT (largest 75 ms) while `bind` — 1,549 ms, 6.0% of
 * the compile — had never been opened. Partitioned, it is
 * `bindStatements` 31 / `bindLexicalScopes` 483 / `FlowGraphBuilder.build`
 * 1,020 ms, and inside the last one a single B464 collector,
 * `collectReassignedNamesInRange`, holds **302-541 ms over 2,014 closures**.
 * Its census says why: the round-433 scan cache is working (1,220 scans, not
 * 2,014) but those scans still re-read **6,256,904 characters** — 63% of the
 * program's source text — and the legacy body allocated a `substring` for
 * EVERY identifier occurrence in that text while keeping only assignment
 * targets.
 *
 * **What the change is.** The `substring` moves below the guard that decides
 * whether the name is kept, and the neighbour reads stop going through
 * `getOrNull` (whose `Char?` boxes on the JVM). Nothing else moves; the
 * verdict logic is character-for-character the legacy one.
 *
 * **How these pins discriminate.** A pure refactor has no complement
 * population where the two implementations are *supposed* to differ, so round
 * 790's free control has no analogue and a deliberately broken arm is needed
 * instead. [FlowScan.bogus] drops the `%=` form from the fast scanner. The
 * `%=`-shaped pins below therefore FAIL against it while the others hold —
 * which is what shows they can see the scanner at all rather than merely
 * being quiet. The two equivalence pins run BOTH scanners in-process over one
 * source and compare, which is strictly stronger than remembering what the old
 * code said.
 */
class FlowScanEquivalenceTest {

    private val shape = """
        interface Holder { get(): number; }
    """.trimIndent()

    /** Narrowing flows into a closure over a captured var with no later write. */
    @Test
    fun `a captured var with no reassignment after the closure keeps its narrowing`() {
        diagnose(
            shape + """

            function make(n: number | undefined): Holder {
                n ??= 1;
                return { get: () => n };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /** The B464 rule itself: a plain `=` after the closure withholds it. */
    @Test
    fun `a plain assignment after the closure withholds the narrowing`() {
        diagnose(
            shape + """

            function make(n: number | undefined): Holder {
                n ??= 1;
                const h = { get: () => n };
                n = undefined;
                return h;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    /**
     * The `%=` compound form — the operator the [FlowScan.bogus] control drops.
     * This pin FAILS against that control, which is what makes the family's
     * silence elsewhere meaningful.
     */
    @Test
    fun `a compound modulo-assign after the closure withholds the narrowing`() {
        diagnose(
            shape + """

            function make(n: number | undefined): Holder {
                n ??= 1;
                const h = { get: () => n };
                n %= 2;
                return h;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    /** `>>>=` — the deepest lookahead arm (`c3`), reached only via the sentinel. */
    @Test
    fun `an unsigned-right-shift-assign after the closure withholds the narrowing`() {
        diagnose(
            shape + """

            function make(n: number | undefined): Holder {
                n ??= 1;
                const h = { get: () => n };
                n >>>= 1;
                return h;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    /** A postfix `++` is a write; the `c0 == '+' && c1 == '+'` arm. */
    @Test
    fun `a postfix increment after the closure withholds the narrowing`() {
        diagnose(
            shape + """

            function make(n: number | undefined): Holder {
                n ??= 1;
                const h = { get: () => n };
                n++;
                return h;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    /**
     * `==` is NOT a write, and the arm that excludes it (`c0 == '=' && c1 !=
     * '=' && c1 != '>'`) is the ONE place the fast scanner reads a NEGATIVE
     * condition on a possibly-absent character — the exact site where the `' '`
     * sentinel has to behave like `null`.
     */
    @Test
    fun `negative control - a comparison after the closure is not a write`() {
        diagnose(
            shape + """

            function make(n: number | undefined): Holder {
                n ??= 1;
                const h = { get: () => n };
                if (n == 2) { }
                return h;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /** `=>` is not a write either — the other half of that same arm. */
    @Test
    fun `negative control - an arrow after the closure is not a write`() {
        diagnose(
            shape + """

            function make(n: number | undefined): Holder {
                n ??= 1;
                const h = { get: () => n };
                const f = (m: number) => m;
                f(1);
                return h;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /**
     * A property write `o.n = …` must NOT count as a write to the bare name
     * `n` — that is the `charAtOr(source, len, i - 1) != '.'` guard, read
     * through the sentinel at position 0 of a file as well as mid-text.
     */
    @Test
    fun `negative control - a same-named property write is not a write to the variable`() {
        diagnose(
            shape + """

            function make(n: number | undefined, o: { n: number }): Holder {
                n ??= 1;
                const h = { get: () => n };
                o.n = 5;
                return h;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    /**
     * IN-PROCESS EQUIVALENCE, which is stronger than any behaviour pin: the
     * same source is checked with the legacy scanner and with the fast one and
     * the two diagnostic sets must be identical. Restores the flag in a
     * `finally` — [FlowScan] is process-global, and the round-619
     * `PassTiming.disabledPasses` incident is what a leaked probe flag costs.
     */
    @Test
    fun `the fast and legacy scanners produce identical diagnostics`() {
        val source = shape + """

            function make(n: number | undefined, o: { n: number }): Holder {
                n ??= 1;
                const a = { get: () => n };
                const b = { get: () => n };
                o.n = 5;
                if (n == 2) { }
                n %= 2;
                n >>>= 1;
                n++;
                ++n;
                n ||= 3;
                n **= 2;
                return a.get() > b.get() ? a : b;
            }
        """
        val fast = diagnose(source).map { "${it.code}@${it.start}" }
        val legacyRun = try {
            FlowScan.legacy = true
            diagnose(source).map { "${it.code}@${it.start}" }
        } finally {
            FlowScan.legacy = false
        }
        assert(fast == legacyRun)
    }

    /**
     * The verifier is a LIVE instrument, not a dead one: with the bogus arm on,
     * running both scanners over a source containing a `%=` must REPORT a
     * divergence. Round 753's rule — an ablation is evidence only if the
     * disabled code can be shown to have run.
     */
    @Test
    fun `the verifier reports a divergence when the fast scanner is deliberately broken`() {
        val source = shape + """

            function make(n: number | undefined): Holder {
                n ??= 1;
                const h = { get: () => n };
                n %= 2;
                return h;
            }
        """
        try {
            FlowScan.reset()
            FlowScan.verify = true
            FlowScan.bogus = true
            diagnose(source)
            val comparedBogus = FlowScan.scansCompared
            val divergedBogus = FlowScan.entriesDiverged
            FlowScan.reset()
            FlowScan.bogus = false
            diagnose(source)
            val comparedClean = FlowScan.scansCompared
            val divergedClean = FlowScan.entriesDiverged
            // The instrument ran in BOTH settings ...
            assert(comparedBogus > 0)
            assert(comparedClean > 0)
            // ... and it separates them.
            assert(divergedBogus > 0)
            assert(divergedClean == 0L)
        } finally {
            FlowScan.verify = false
            FlowScan.bogus = false
            FlowScan.reset()
        }
    }

    /** Every (FRONT.2) flag is off in production, and the probe is inert. */
    @Test
    fun `the flow-scan flags are all off by default`() {
        assert(!FlowScan.legacy)
        assert(!FlowScan.verify)
        assert(!FlowScan.bogus)
        assert(!FlowScan.eagerSet)
    }

    /**
     * THE ROUND'S ACTUAL LEVER, stated as an arithmetic pin rather than as a
     * timing claim: the eager form materialises a hash set for EVERY closure
     * that has a non-empty suffix, the deferred form only for the ones
     * something actually questions. Comparing the two arms over one source is
     * exact; the first draft asserted a STRICT inequality and failed, because
     * in a small fixture the checker questions every closure — which is round
     * 788's law showing up as a red test, and the reason the round quotes the
     * profile census rather than this pin for the size of the effect.
     */
    @Test
    fun `the deferred suffix set never materializes more than the eager one`() {
        val source = shape + """

            function make(n: number | undefined, s: string | undefined): Holder {
                n ??= 1;
                const a = { get: () => n };
                const b = { get: () => n };
                const c = () => s;
                c();
                n = 2;
                return a.get() > b.get() ? a : b;
            }
        """
        try {
            FlowScan.reset()
            diagnose(source)
            val createdLazy = FlowScan.setsCreated
            val materializedLazy = FlowScan.setsMaterialized
            FlowScan.reset()
            FlowScan.eagerSet = true
            diagnose(source)
            val createdEager = FlowScan.setsCreated
            val materializedEager = FlowScan.setsMaterialized
            // The population is REACHED — a zero would make the rest vacuous
            // (round 753: an ablation is evidence only if the code ran).
            assert(createdLazy > 0)
            assert(createdLazy == createdEager)
            // The eager arm materialises every set it creates, by construction.
            assert(materializedEager == createdEager)
            // The deferred arm materialises AT MOST that many — and on a
            // fixture small enough that the checker questions every closure it
            // materialises exactly as many, which is the honest statement and
            // the reason the profile census (not a fixture) is what decides
            // whether the deferral DELETES work or merely MOVES it (round 788).
            assert(materializedLazy <= materializedEager)
        } finally {
            FlowScan.eagerSet = false
            FlowScan.reset()
        }
    }

    /** The eager and the deferred set give the same answers. */
    @Test
    fun `eager and deferred suffix sets produce identical diagnostics`() {
        val source = shape + """

            function make(n: number | undefined): Holder {
                n ??= 1;
                const a = { get: () => n };
                n %= 2;
                return a;
            }
        """
        val deferred = diagnose(source).map { "${it.code}@${it.start}" }
        val eager = try {
            FlowScan.eagerSet = true
            diagnose(source).map { "${it.code}@${it.start}" }
        } finally {
            FlowScan.eagerSet = false
        }
        assert(deferred == eager)
    }

    /**
     * An EMPTY suffix must answer `isEmpty` without materialising — that is the
     * one arithmetic short-circuit in the view, and getting it wrong is silent.
     */
    @Test
    fun `an empty suffix set answers isEmpty without materializing`() {
        val view = SuffixNameSet(arrayOf("a", "b"), 2)
        FlowScan.reset()
        assert(view.isEmpty())
        assert(FlowScan.setsMaterialized == 0L)
        assert(!view.contains("a"))
        assert(FlowScan.setsMaterialized == 1L)
        FlowScan.reset()
    }

    /** The view IS the suffix — membership, size and iteration all agree. */
    @Test
    fun `a suffix view holds exactly the tail of its name array`() {
        val view = SuffixNameSet(arrayOf("a", "b", "c", "b"), 1)
        assert(!view.contains("a"))
        assert(view.contains("b"))
        assert(view.contains("c"))
        assert(view.size == 2)
        assert(view.toList().sorted() == listOf("b", "c"))
    }
}
