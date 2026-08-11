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
 * (SPINE.1)(m3-flags) round 886 — the three spine-anchor mark tables as ONE
 * per-file bit-per-nodeId `ByteArray`.
 *
 * **THESE ARE OUTPUT-EQUIVALENCE PINS, NOT SEAM PINS — SAY SO, because round
 * 886 ABLATED the per-file keying and every one of them stayed GREEN.**
 *
 * The round's intent was a seam pin per invariant: the marks exist so the legacy
 * cta/cpa/ccet walkers can TRUNCATE emissions the spine anchored, so a leaked
 * mark should truncate a diagnostic that was never anchored (the file loses it)
 * and a lost mark should truncate nothing (it is emitted twice). Replacing the
 * per-file array with ONE PROGRAM-WIDE array — round 787's mistake, the single
 * most load-bearing invariant here — is unobservable through **every**
 * instrument this repo owns: these four pins, all 14,140 corpus tests, and a
 * `--listAll` whole-output diff over the 78-file compiler profile (0 lines).
 *
 * The census says why, and it is not a key mismatch (`noMarksForThatFile=0`,
 * i.e. every test's file HAS marks — the tables are correctly wired):
 *
 * ```
 * marks=184569  tests=15446  true=0  programWideWouldDiffer=4666
 * ```
 *
 * **Not one of the 15,446 consultations answers `true` on that profile**, so no
 * truncation ever fires and no wrong answer can reach an output. A behavioural
 * pin for the per-file invariant is therefore not expressible on any input this
 * repo has; recording that is worth more than a pin that would pass either way.
 * See `docs/perf/tsgo-portability-census.md` § 6.
 *
 * What these four DO pin, which is real and worth keeping: the anchored shapes
 * produce their diagnostics exactly once, a multi-file program agrees with the
 * per-file runs, and a nodeId past the array's initial size still marks — i.e.
 * the growth path and the no-duplicate property, which a mistake in
 * [Checker.m3FlagsForCurrent]'s sizing WOULD break.
 *
 * Built by direct `Checker(options, binderResults)` construction with
 * path-shaped file names (flat names defeat relative module resolution — the
 * documented test-fixture trap).
 */
class M3AnchorFlagsTest {

    private val options = CompilerOptions(strict = true)

    private fun bind(vararg files: Pair<String, String>): List<BinderResult> =
        files.map { (name, src) -> Binder(options).bind(Parser(src.trimIndent(), name).parse()) }

    private fun check(vararg files: Pair<String, String>): List<Diagnostic> =
        Checker(options, bind(*files), isMultiFileSource = true).getDiagnostics()

    private fun keys(diags: List<Diagnostic>): List<String> =
        diags.map { "${it.fileName}|${it.start}|${it.length}|${it.code}|${it.message}" }.sorted()

    /**
     * Statement anchors (cta), a call-argument anchor (ccet) and a property
     * access feeding an assignment (cpa) — so all three bits are set on nodes
     * of one file, which is what makes a bit-aliasing mistake observable.
     */
    private val shapes = """
        declare function takesNumber(a: number): void;
        declare const holder: { p: number };
        export const wrongInit: string = 1;
        takesNumber("not a number");
        export const fromProp: string = holder.p;
    """

    @Test
    fun `each anchored diagnostic is emitted exactly once`() {
        val diags = check("/proj/only.ts" to shapes)
        val all = keys(diags)
        assert(all.isNotEmpty())
        assert(all == all.distinct())
    }

    @Test
    fun `a multi-file program agrees with the per-file runs`() {
        // Two byte-identical files, so every anchored nodeId in b.ts is also
        // anchored in a.ts. NOTE: this does NOT discriminate the per-file
        // keying - see the class KDoc; it pins that a multi-file run agrees
        // with the per-file runs, which a sizing or lifetime mistake breaks.
        val together = check("/proj/a.ts" to shapes, "/proj/b.ts" to shapes)
        val alone = check("/proj/a.ts" to shapes) + check("/proj/b.ts" to shapes)
        assert(keys(together) == keys(alone))
        assert(keys(together) == keys(together).distinct())
    }

    @Test
    fun `a nodeId past the initial array size still marks`() {
        // The array is created at 64 entries and grows on demand; 200 leading
        // statements push every anchored node well past that boundary.
        val padding = (1..200).joinToString("\n") { "export const pad$it = $it;" }
        val deep = check("/proj/deep.ts" to (padding + "\n" + shapes.trimIndent()))
        val shallow = check("/proj/shallow.ts" to shapes)
        // Same anchored shapes, same diagnostic CODES, each emitted once.
        assert(keys(deep) == keys(deep).distinct())
        assert(deep.map { it.code }.sorted() == shallow.map { it.code }.sorted())
    }

    @Test
    fun `a file with no anchored shape stays diagnostic-free beside one that has them`() {
        val clean = "export const ok: number = 1;"
        val withNeighbour = check("/proj/dirty.ts" to shapes, "/proj/clean.ts" to clean)
        val cleanOnly = withNeighbour.filter { it.fileName == "/proj/clean.ts" }
        assert(cleanOnly.isEmpty())
    }
}
