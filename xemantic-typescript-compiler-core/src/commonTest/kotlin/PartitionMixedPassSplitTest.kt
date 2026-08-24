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
 * (INC.20) A MIXED PASS IS SPLIT AT ITS OWN LOOPS, AND A PER-FILE RETRACTION IS
 * A PER-FILE OPERATION.
 *
 * (INC.17) split `checkSubsequentVarTypes` because its two halves have OPPOSITE
 * partition behaviour and a census read their SUM. The same shape occurs
 * *within* a single pass: a program-wide COLLECTION loop followed by a per-file
 * EMITTING loop over the collected index —
 *
 *     for (result in binderResults) collectTopLevelClassDeclarations(...)   // STAYS
 *     if (index.isEmpty()) return
 *     for (result in checkedResults) ...emit for THIS file...               // NARROWS
 *
 * — where narrowing the emitting half is sound and narrowing the collecting half
 * would starve the index of the very declarations the emission resolves through.
 * `ProjectMixedPassSplitTest` is the arm that would see that mistake: it puts the
 * base declaration in a file the partition does NOT contain.
 *
 * The other half of (INC.20)'s second sub-batch is the RETRACTORS. A retraction
 * keyed on `it.fileName == fileName` — or a `diagnostics[i] = d.copy(...)`
 * rewrite guarded by `d.fileName != fileName` — is a per-FILE operation, and
 * `Checker.getDiagnostics` drops every row naming a file outside the partition
 * anyway, so a narrowed build can only ever retract rows it would also have
 * reported. `checkPreEmitCountMismatchPins` is in fact IMPROVED by the gate: its
 * synthesized marker carries `fileName = null` and therefore SURVIVES the
 * partition filter, so a program-wide loop could emit a global marker describing
 * a file the caller never asked about.
 *
 * The pins are counts on the (INC.17) census hook, which records a pass if and
 * only if that pass read `checkedResults` — RED against a binary where these
 * loops still say `binderResults`, and invisible to every diagnostics-side gate.
 */
class PartitionMixedPassSplitTest {

    /**
     * The four passes (INC.20)'s second sub-batch narrowed, largest floor row
     * first. The first two are MIXED and only their EMITTING loop moved; the last
     * two are per-file retractors/rewriters whose single loop moved whole.
     */
    private val gated = listOf(
        "checkBaseClassImprovedMismatch",
        "checkCircularClassBaseViaDefaultTypeArg",
        "checkPreEmitCountMismatchPins",
        "checkCircularGenericCallbackVariables",
    )

    private val twoFiles = arrayOf(
        "/proj/a.ts" to """
            export interface Foo { x: number }
            export class Base<C, T = C["x"]> { v?: T }
            export function makeIt<T>(cb: () => T): T { return cb(); }
        """,
        "/proj/b.ts" to """
            import { Foo, Base, makeIt } from "./a.js";
            export class Derived extends Base<Derived> { x = 1 }
            export const two: Foo = { x: 2 };
            export const looped = makeIt(() => looped);
        """,
    )

    private fun readsFor(assigned: Set<String>?): Map<String, Long> {
        val options = CompilerOptions()
        val results = twoFiles.map { (name, src) ->
            Binder(options).bind(Parser(src.trimIndent(), name).parse())
        }
        val savedEnabled = PassTiming.enabled
        try {
            PassTiming.reset()
            PassTiming.detail = false
            PassTiming.spineDetail = false
            PassTiming.enabled = true
            Checker(
                options,
                results,
                isMultiFileSource = true,
                assignedFileNames = assigned,
            ).getDiagnostics()
            return LinkedHashMap(PassTiming.partitionReadsByPass)
        } finally {
            PassTiming.enabled = savedEnabled
            PassTiming.reset()
        }
    }

    @Test
    fun `every mixed or retracting pass gated by INC 20 reads the partition view`() {
        val reads = readsFor(setOf("/proj/b.ts"))
        val missing = gated.filter { it !in reads }
        assert(missing.isEmpty())
    }

    /**
     * The negative control that keeps the assertion above from passing on a binary
     * that gated everything: the program-wide index builders must still be absent.
     *
     * (INC.21) REMOVED `checkSubsequentVarTypesPerFile` FROM THIS LIST, and said so
     * rather than editing it quietly. It was here because (INC.17) deliberately left
     * that pass on `binderResults` so its re-entrant replay would never re-enter it,
     * and (INC.20) declined to reverse a decision it had not been asked to take. The
     * replay is EXPERIMENTAL and (INC.19) refused it as a default path, so nothing
     * shipped reaches it, while the pass is 10.9 ms of an incremental floor that
     * every editor query pays; the pass is now gated and
     * `PartitionCensusHookTest` asserts its PRESENCE.
     */
    @Test
    fun `negative control - the passes INC 20 refused are still not recorded`() {
        val reads = readsFor(setOf("/proj/b.ts"))
        // (INC.25) REMOVED `init:buildFileLocalTypeMaps` from this list. It was
        // refused for a first-touch ORDER cost that (INC.23) reduced to one member
        // name and (INC.25) then fixed in `getKeyofType`; the pass is now
        // partition-scoped by default, and `FltmScopeArmTest` pins that as a COUNT.
        // `init:computeAllEnumValues` keeps the control non-vacuous — it builds a
        // cross-file accumulator the partition's own files are read out of.
        val wrongly = listOf(
            "init:computeAllEnumValues",
        ).filter { it in reads }
        assert(wrongly.isEmpty())
    }

    @Test
    fun `an unpartitioned build reads the same sites`() {
        val whole = readsFor(null)
        val missing = gated.filter { it !in whole }
        assert(missing.isEmpty())
    }
}
