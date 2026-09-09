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
 * (INV.0) step 5 — the [Relater] seam's own pins (ledger row 7).
 *
 * The extraction is a VERBATIM relocation, and its invariant — "nothing changed" —
 * is pinned by the corpus and by the round's 488-line `--passTiming` receipt far
 * better than any hand-written case could be (row 2's reasoning). What is NEW, and
 * what only a test at this level can state, is that the relation's RECURSION
 * BOOKKEEPING survived being split across two objects:
 *
 * - four counters ([Relater]'s own `relProbeDepth`, `relationUsedCycleBreak`,
 *   `maxRelationDepth`, `lastPrivateBrandMismatchName`) moved INTO the collaborator
 *   because nothing else read them;
 * - three containers stayed in `CheckerState` and are handed in AS THE OBJECTS;
 * - and `Checker.relationDepth` stayed on the checker, because
 *   `resolveGenericPropertyType` gates INV.5(d1)'s budget on `relationDepth > 0`.
 *
 * That is five storage decisions across one `try`/`finally` (B202.3's
 * finally-hygiene), and getting any of them wrong is SILENT: a stale
 * `(source.id, target.id)` pair key makes every LATER comparison of that pair
 * answer `true` through the cycle break, i.e. it deletes diagnostics in whatever
 * file is checked next. No corpus baseline, no `cost_gate.py` counter and no
 * `--listAll` diff can see it, so [Relater.recursionResidue] exists to be asserted.
 *
 * The pins that follow the residue one are POSITIVE CONTROLS — a residue of zero
 * over a program that performed no comparison would be vacuous, which is the shape
 * round 790 warns about.
 *
 * ABLATION RESULT, recorded rather than claimed. Five arms, one injected mistake
 * each: dropping the comparison-stack pop (a1), the `relationDepth--` (a2) or the
 * two target-stack pops (a3) from the `finally` each redden the residue pin and
 * NOTHING else; flipping `REL2_ENUM_TO_MEMBER` (a5) reddens its own control and
 * nothing else. Disabling the `isDeeplyNested` bail (a4) reddens NOTHING — see the
 * two renamed pins below, whose KDocs carry the mechanism.
 */
class RelaterTest {

    /**
     * A program that drives the relation hard: mutually recursive interfaces (the
     * `(source.id, target.id)` cycle break), a self-expanding generic pair (the
     * `isDeeplyNested` 5-occurrence bail on [Relater]'s two target stacks), a
     * private-brand mismatch (the side channel that moved), and two failing
     * assignments (so the comparison REJECTS rather than short-circuiting on
     * `source === target`).
     */
    private val fixture = """
        interface RecA { x: RecA; y: number }
        interface RecB { x: RecB; y: number }
        interface GenA<T> { x: GenA<() => T> }
        interface GenB<T> { x: GenB<() => T> }
        class BrandP { private p: number = 1; q: string = "" }
        class BrandQ { private p: number = 1; q: string = "" }

        declare const ra: RecA;
        declare const ga: GenA<number>;
        declare const bp: BrandP;

        const okB: RecB = ra;
        const okG: GenB<number> = ga;
        const badBrand: BrandQ = bp;
        const badPrim: number = ra;
    """.trimIndent()

    private fun checkerOver(source: String): Checker {
        val options = CompilerOptions()
        val results = listOf(Binder(options).bind(Parser(source, "t.ts").parse()))
        return Checker(options, results)
    }

    @Test
    fun `the relation's recursion state unwinds to zero after a whole-program check`() {
        val checker = checkerOver(fixture)
        val diagnostics = checker.getDiagnostics()
        // POSITIVE CONTROL first: a residue of zero over a program that never
        // compared anything would pass vacuously. The private-brand mismatch is a
        // comparison that reaches propertiesRelatedTo and REJECTS.
        val brandMismatch = diagnostics.any { it.code == 2322 }
        assert(brandMismatch)
        assert(checker.relaterRecursionResidue == 0)
    }

    @Test
    fun `negative control - the residue is zero on a program with no relation work`() {
        // The complement of the pin above: an empty program leaves the same zero, so
        // the pin's evidence is the POSITIVE CONTROL beside it, not the zero itself.
        val checker = checkerOver("export {};")
        checker.getDiagnostics()
        assert(checker.relaterRecursionResidue == 0)
    }

    /**
     * MEASURED UNDISCRIMINATED, and renamed to say so (round 813's rule). This was
     * written as a leak detector — a stale pair key makes the SECOND comparison of the
     * same `(source.id, target.id)` answer `true` through the cycle break — and arm a1,
     * which deletes exactly that pop, leaves it GREEN. The reason is a mechanism worth
     * recording: `checkTypeRelatedToCore` writes a `false` verdict into the [Relation]
     * cache, which is probed ABOVE the comparison stack, so for an IDENTICAL pair the
     * cache answers before a leaked key can be consulted. A leak is therefore only
     * observable through a pair whose verdict was NOT cacheable (one decided under a
     * cycle break), which no fixture here constructs. The residue pin above is what
     * carries that invariant; this one is a positive control that the two mechanisms
     * agree.
     */
    @Test
    fun `positive control - a repeated identical comparison is served by the relation cache`() {
        val diagnostics = diagnose(
            """
            interface CycA { x: CycA; only: number }
            interface CycB { x: CycB; only: string }
            declare const a: CycA;
            const first: CycB = a;
            const second: CycB = a;
            """.trimIndent(),
        )
        val rows = diagnostics.filter { it.code == 2322 || it.code == 2739 || it.code == 2741 }
        assert(rows.size == 2)
    }

    /**
     * ALSO MEASURED UNDISCRIMINATED, and renamed. `DeepA<T>` / `DeepB<T>` expand without
     * ever repeating a pair, so id-based cycle detection never fires and only the
     * 5-occurrence `isDeeplyNested` scan over the two target stacks was expected to end
     * it — but arm a4, which makes `countOccurrences` answer 0 and so disables that bail
     * entirely, leaves this GREEN: `maxRelationDepth` is a SECOND and sufficient bound,
     * at 100 levels. So the two guards are a round-927 pair — neither is redundant (the
     * bail is what keeps the cost bounded, the ceiling what keeps it finite) and no
     * shape here separates them.
     */
    @Test
    fun `positive control - an infinitely-expanding generic pair terminates without a depth diagnostic`() {
        val diagnostics = diagnose(
            """
            interface DeepA<T> { x: DeepA<() => T> }
            interface DeepB<T> { x: DeepB<() => T> }
            declare const da: DeepA<number>;
            const db: DeepB<number> = da;
            """.trimIndent(),
        )
        assert(diagnostics.none { it.code == 2589 })
    }

    /**
     * A control for the ONE file-private declaration this extraction had to widen to
     * `internal`: (REL.2) round 783's ablation switch, read from `Relater.kt` now that
     * `checkTypeRelatedToCore` lives there. Widening a visibility and flipping a value
     * are one keystroke apart, and the switch's OFF state is a documented behaviour
     * change (a `const` binding's enum-member type widening to its whole enum).
     */
    @Test
    fun `the REL2 enum-to-member ablation switch is still on`() {
        assert(REL2_ENUM_TO_MEMBER)
    }
}
