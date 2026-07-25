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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Pins the round-433 perf refactors' semantic invariants:
 *
 * 1. `collectReassignedNamesInRange` (Flow.kt) now shares ONE scan per enclosing
 *    function (cached by range end) filtered by each closure's position. Two
 *    closures in the same enclosing function — one with a captured-var
 *    reassignment after it (narrowing withheld → TS18048), one without (narrowing
 *    flows → silent) — exercise the cache-shared path with two different starts
 *    and must keep tsc's `isPastLastAssignment` semantics (the f4/f5 shapes).
 *
 * 2. `narrowTypeFromFlow`'s FlowBranchLabel case now walks each antecedent with a
 *    mark/popToMark on a shared NarrowSeen instead of copying the seen set. A
 *    diamond join UPSTREAM of the narrowing guard makes sibling antecedents share
 *    path nodes: if a sibling's additions leaked (missing pop), the second
 *    antecedent's walk would cycle-truncate to the declared type and re-add
 *    `undefined` at the join — a false TS18048.
 */
class FlowNarrowingPerfInvariantsTest {

    private fun build(@Language("typescript") source: String): ProjectCompiler.Result {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/src/main.ts" to source,
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "strict": true, "outDir": "./dist" }, "include": ["src/**/*.ts"] }""",
            )
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
    }

    private fun ts18048Lines(result: ProjectCompiler.Result): List<Int?> =
        result.diagnostics.filter { it.code == 18048 }.map { it.line }

    @Test
    fun `the shared reassign scan keeps per-closure past-last-assignment semantics`() {
        // Both closures share the enclosing function (one cached scan, two starts —
        // g's query FILTERS f's cached scan): captured PARAM `a` is reassigned
        // AFTER f → a's narrowing is withheld in f → TS18048 on `a`; `b` is never
        // reassigned after g → b's narrowing flows into g → silent. (Params, not
        // `let` locals: the emitter's declared-type recovery for captured body
        // locals is deliberately var-only — B467.)
        val result = build(
            """
            export function outer(a: string | undefined, b: string | undefined) {
              if (a && b) {
                const f = () => a.length;
                const g = () => b.length;
                a = undefined;
              }
            }
            """.trimIndent()
        )
        assert(
            result.diagnostics.filter { it.code == 18048 }.map { it.message } == listOf("'a' is possibly 'undefined'.")
        )
    }

    @Test
    fun `branch sibling walks stay isolated across shared upstream nodes`() {
        // The diamond (if/else) join sits BETWEEN the closure and the `if (x)`
        // guard: the join's antecedents share every upstream path node. Narrowing
        // must survive into the closure (no TS18048) — sibling-poisoning of the
        // seen set would truncate the second antecedent to the declared type and
        // re-add `undefined` at the join.
        val result = build(
            """
            export function outer2(x: string | undefined, c: boolean) {
              if (x) {
                if (c) { console.log(1); } else { console.log(2); }
                const f = () => x.length;
              }
            }
            """.trimIndent()
        )
        assert(ts18048Lines(result) == emptyList<Int?>())
    }

    @Test
    fun `negative control - branch isolation`() {
        // Positive control for the emitter used above: without the guard the
        // captured maybe-undefined receiver DOES fire — proving the silent case
        // in the previous test is narrowing at work, not a dead emitter.
        val result = build(
            """
            export function outer3(x: string | undefined, c: boolean) {
              if (c) { console.log(1); } else { console.log(2); }
              const f = () => x.length;
            }
            """.trimIndent()
        )
        // unguarded captured maybe-undefined receiver must fire TS18048 (emitter
        // active)
        assert(ts18048Lines(result).isNotEmpty())
    }
}
