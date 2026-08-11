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
 * (SPINE.1)(m3-inert) round 887 — **the spine anchors emit exactly once, with
 * the anchor-mark truncation machinery REMOVED.**
 *
 * Round 886 replaced three `HashMap<String, HashSet<Int>>` anchor-mark tables
 * with one per-file bit-per-nodeId `ByteArray`, and then found by census that
 * not one consultation ever answered `true`. Round 887 repeated the census over
 * the whole corpus with the two controls round 886 lacked — a positive control
 * (319,777 marks read back at their own key, 0 failures) and a key control
 * (`testNoArrayForFile = 0`, so every consultation found its file's array) —
 * and the answer held: **319,777 marks, 48,868 consultations, 0 affirmative.**
 *
 * The mechanism is disjointness, not luck: `testInRangeOtherBitSet = 42,513`
 * says 87% of consultations landed on a node that WAS marked, just never with
 * the bit being asked for. The mark sites and the consultation sites never
 * overlapped in (bit x node kind) — CPA, for instance, marked 140,528 nodes
 * across seven statement kinds plus every loop/switch condition and heritage
 * expression, and was read back at one site for a `VariableStatement`. So the
 * truncation branches were unreachable by construction and the whole family
 * (marks, flag array, consultations, truncations) is gone.
 *
 * **WHAT THESE PINS ARE FOR NOW, AND THEY DO DISCRIMINATE THE REMOVAL:** if any
 * consultation HAD been affirmative, deleting it un-suppresses a legacy walker
 * that the spine had already run, and the shape emits its diagnostic TWICE.
 * Every test here asserts exactly-once at a shape one of the deleted
 * consultation sites guarded. The sharpest is the body-walker one: its gate was
 * `if (!ctaM3BodyAnchored) checkForInNumericForRedeclare(...)`, so a live gate
 * would now run that walker on top of the spine's own run.
 *
 * Built by direct `Checker(options, binderResults)` construction with
 * path-shaped file names (flat names defeat relative module resolution — the
 * documented test-fixture trap).
 */
class SpineAnchorEmitOnceTest {

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
        // Two byte-identical files: a per-file disagreement is how a shared
        // suppression state between files would show.
        val together = check("/proj/a.ts" to shapes, "/proj/b.ts" to shapes)
        val alone = check("/proj/a.ts" to shapes) + check("/proj/b.ts" to shapes)
        assert(keys(together) == keys(alone))
        assert(keys(together) == keys(together).distinct())
    }

    @Test
    fun `an anchored shape far down a long file still reports once`() {
        // 200 leading statements, i.e. every anchored node at a high nodeId -
        // the shape that used to exercise the flag array's growth path.
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

    /**
     * The SHARPEST pin on round 887's removal, and the reason it is a compile
     * test rather than an assertion about a data structure.
     *
     * `checkFunctionBody` ran the B442 for-in/numeric-for redeclare walker under
     * `if (!ctaM3BodyAnchored) …`, i.e. suppressed for a body the spine had
     * anchored — and the spine runs that same walker itself at the body Block's
     * enter. Round 887 deleted the gate on the strength of a census saying the
     * flag is never set. **If that census were wrong for this shape, the walker
     * now runs twice and every one of its three diagnostics doubles**, which is
     * exactly what the exactly-once assertion below sees.
     */
    @Test
    fun `the body-level redeclare walker reports once per function body`() {
        val diags = check(
            "/proj/b442.ts" to """
                export function walk(items: { [k: string]: number }): void {
                    for (var name in items) { name; }
                    for (var name = 0; name < 3; name++) { name; }
                }
            """,
        )
        val all = keys(diags)
        assert(all.isNotEmpty())
        assert(all == all.distinct())
        // The redeclare family itself: first-decl-wins TS2403 plus the
        // string-vs-number operand errors it makes possible.
        assert(diags.count { it.code == 2403 } == 1)
    }

    /**
     * One shape per DELETED consultation site: an `ExpressionStatement`
     * assignment, a `ReturnStatement`, a `VariableStatement` chain (the cpa
     * per-decl recording gate) and a `CallExpression` / `NewExpression` /
     * `TaggedTemplateExpression` (the three ccet truncations). Each carried a
     * `while (diagnostics.size > mark) removeAt(…)` that is now gone, so a
     * consultation that HAD been affirmative shows up here as a duplicate.
     */
    @Test
    fun `every deleted truncation site's shape reports exactly once`() {
        val diags = check(
            "/proj/sites.ts" to """
                declare function takesNumber(a: number): void;
                declare class Holder { constructor(a: number); }
                declare function tag(parts: TemplateStringsArray, a: number): string;
                declare const holder: { p: number };
                export function f(): string {
                    let assigned: number = 0;
                    assigned = "not a number";
                    const chained: string = holder.p, second: number = "also wrong";
                    takesNumber("call arg");
                    new Holder("new arg");
                    tag`${'$'}{"tagged arg"}`;
                    return holder.p;
                }
            """,
        )
        val all = keys(diags)
        assert(all.isNotEmpty())
        assert(all == all.distinct())
        // Every one of those shapes is a distinct assignability failure.
        assert(diags.count { it.code == 2322 || it.code == 2345 } >= 5)
    }
}
