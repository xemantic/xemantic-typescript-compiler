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
 * (INC.18) THE PARTITION GATE, IN THE SUITE — and its own SENSITIVITY beside it.
 *
 * `recheckOnly` narrows the checker to `assignedFileNames` (the INV.6 view), and
 * its contract is sequential equivalence: a partition naming one file must report,
 * for that file, exactly what the full build reports for it. That is a
 * DIFFERENTIAL, so it sees only a defect that makes the two arms DISAGREE — and
 * its resolution is bounded by how many of the checker's ~416 `init` passes get to
 * contribute a row to the comparison.
 *
 * **On tsc's own 78 sources that bound is ONE.** Measured while censusing (INC.17):
 * the full build's 46 diagnostics are netted by `checkSpine` alone, and 5 of the 78
 * files carry any row at all, so 73 of the per-file comparisons are empty against
 * empty — and all eight dashboard profiles are that same codebase. A defect that
 * silently produced nothing from the other 415 passes under a narrowed checker
 * would be invisible to `scripts/partition-equivalence.sh`, which is exactly why
 * (INC.17) refused to land a re-entrant checker on it.
 *
 * This is the miniature of `scripts/partition-gate.sh`'s sensitivity arm: a
 * multi-file program whose rows come from MANY dedicated walkers, swept
 * file-by-file. The two claims are pinned separately, because a passing
 * equivalence sweep over a program that emits nothing is the failure this exists
 * to prevent:
 *
 *  * **equivalence** — every file's narrowed rows equal its full rows;
 *  * **sensitivity** — the receipt, and it is a COUNT: how many files carry a row,
 *    and how many DISTINCT passes net one, read off [PassTiming.diagNetByPass].
 *
 * `diagNetByPass` rather than [PassTiming.diagsByPass] deliberately: the latter
 * clamps to positive deltas, so a pass that RETRACTS (there are 73 `removeAll`,
 * 5 `removeAt` and 2 `clear` sites in `Checker.kt`) is invisible in it.
 */
class PartitionSensitivityTest {

    /**
     * One program, one file per walker family. Kept in this shape — many small
     * modules rather than one big file — because the quantity under test is
     * per-FILE agreement, and a single-file program has no partition to speak of.
     *
     * Every file is a module, so nothing collides across the program and each
     * file's rows are its own.
     */
    private val source = """
        // @strict: true
        // @target: es2020
        // @Filename: missingImpl.ts
        export function bodiless(a: number): void;
        export function alsoBodiless(): number;
        export {};
        // @Filename: conflictMarkers.ts
        export const before = 1;
        <<<<<<< HEAD
        export const mine = 1;
        =======
        export const theirs = 2;
        >>>>>>> other
        // @Filename: heritage.ts
        export interface Shape { area(): number; }
        export class NotAShape implements Shape {}
        export class Base { m(a: string): void {} }
        export class Derived extends Base { constructor() { this.m("x"); super(); } }
        // @Filename: circular.ts
        export interface Rec1 extends Rec2 {}
        export interface Rec2 extends Rec1 {}
        export class Circ1 extends Circ2 {}
        export class Circ2 extends Circ1 {}
        // @Filename: enums.ts
        export enum Fwd { A = B, B = 1 }
        export enum WithThis { X = 1, Y = this.X }
        // @Filename: labels.ts
        export function labelled() {
            outer: for (const a of []) {
                outer: for (const b of []) {}
            }
        }
        // @Filename: assignability.ts
        export const wrong: number = "not a number";
        export const alsoWrong: string = 1;
        // @Filename: unresolved.ts
        import { nothing } from "./no-such-module";
        export { nothing };
    """.trimIndent()

    private val fileNames = listOf(
        "missingImpl.ts", "conflictMarkers.ts", "heritage.ts", "circular.ts",
        "enums.ts", "labels.ts", "assignability.ts", "unresolved.ts",
    )

    private fun build(recheckOnly: Set<String>? = null): List<Diagnostic> {
        val parsed = parseMultiFileSource(source, "input.ts")
        return TypeScriptCompiler().compileParsed(
            parsed,
            parsed.options,
            "input.ts",
            recheckOnly = recheckOnly,
        ).diagnostics
    }

    private fun rowsOf(diagnostics: List<Diagnostic>): Map<String, List<String>> =
        diagnostics.groupBy { it.fileName ?: "" }
            .mapValues { (_, ds) ->
                ds.map { "${it.code}|${it.start}|${it.length}|${it.message}" }.sorted()
            }

    @Test
    fun `every file's narrowed rows equal its full rows`() {
        val fullRows = rowsOf(build())
        for (file in fileNames) {
            val expected = fullRows[file] ?: emptyList()
            val actual = rowsOf(build(setOf(file)))[file] ?: emptyList()
            // Rendered as one string so a failure names the FILE and both row sets.
            assert("$file $actual" == "$file $expected")
        }
    }

    /**
     * THE VACUITY CONTROL. Without it the sweep above is satisfied by a program on
     * which nothing emits — which is precisely the state the tsc profile is in for
     * 73 of its 78 files, and the reason that gate could not see a starved replay.
     */
    @Test
    fun `most files carry a row so the sweep is not empty against empty`() {
        val fullRows = rowsOf(build())
        val carrying = fileNames.count { !fullRows[it].isNullOrEmpty() }
        assert(carrying >= 7)
    }

    /**
     * THE RECEIPT, and it is a COUNT: how many DISTINCT passes net a diagnostic.
     * On tsc's own sources this number is 1; the floor here is what makes this
     * fixture an instrument rather than a second copy of that vacuity.
     */
    @Test
    fun `many distinct passes net a diagnostic - which is what the sweep compares`() {
        PassTiming.reset()
        PassTiming.enabled = true
        val diagnostics = try {
            build()
        } finally {
            PassTiming.enabled = false
        }
        val netting = PassTiming.diagNetByPass.filterValues { it != 0 }
        // The instrument's own positive control: a hook that had stopped firing
        // reads zero, which is indistinguishable from a program that emits nothing.
        assert(diagnostics.isNotEmpty())
        assert(netting.values.sum() != 0)
        assert(netting.size >= 8)
        PassTiming.reset()
    }
}
