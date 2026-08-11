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
 * The marks exist so the LEGACY cta/cpa/ccet walkers can truncate emissions the
 * spine anchors already produced, which is what makes both failure directions
 * observable as diagnostics rather than as timing:
 *
 *  - a mark that LEAKS across files truncates a diagnostic that was never
 *    anchored — the file loses it;
 *  - a mark that is LOST truncates nothing — the diagnostic is emitted twice.
 *
 * Both are exactly what the old `HashMap<String, HashSet<Int>>` could not get
 * wrong and the new array can: round 787's law says `nodeId` restarts at 0 in
 * every `SourceFile`, so per-file keying is load-bearing, and the array grows
 * on demand rather than being pre-sized, so a high nodeId must still mark.
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
    fun `marks do not leak between files sharing the same nodeIds`() {
        // Two byte-identical files: every anchored node in b.ts has a nodeId
        // that is also anchored in a.ts. A program-wide array would collapse
        // them onto each other and truncate b.ts's diagnostics away.
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
    fun `an unanchored file is unaffected by another file's marks`() {
        val clean = "export const ok: number = 1;"
        val withNeighbour = check("/proj/dirty.ts" to shapes, "/proj/clean.ts" to clean)
        val cleanOnly = withNeighbour.filter { it.fileName == "/proj/clean.ts" }
        assert(cleanOnly.isEmpty())
    }
}
