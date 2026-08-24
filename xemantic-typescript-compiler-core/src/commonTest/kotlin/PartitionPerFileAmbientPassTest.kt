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
 * (INC.20) A PER-FILE AMBIENT INSTALL IS A PROPERTY OF THE FILE, NOT OF THE
 * PROGRAM — SO A PASS WHOSE ONLY CHECKER-FIELD WRITE IS ONE IS PARTITION-SCOPED.
 *
 * (INC.7) closed its technique with 83 tail walkers REFUSED, 53 of them "writes a
 * checker field inside the private closure". That criterion is sound and it is
 * COARSE: the write it usually names is
 *
 *     currentFileLocals = result.locals      // installed at the top of the
 *     currentCheckFileName = fileName        // iteration for THIS file
 *     ...                                    // ... walk this file ...
 *     currentFileLocals = null               // and reset after the loop
 *
 * which is scoped to the iteration and reset (to null, or to the value saved on
 * entry) before anything else can read it. No emission for file A can depend on
 * the checker having installed file B's ambient, because file B's install is gone
 * by the time A is walked. That is exactly (INC.20)'s question — *is the field
 * write a property of the PROGRAM or of the FILE?* — and the answer here is the
 * FILE, so the loop may narrow onto the partition view.
 *
 * THE PIN IS A COUNT, NOT A TIME, and it discriminates: the census hook records a
 * pass IF AND ONLY IF that pass read `checkedResults` ((INC.17),
 * [PartitionCensusHookTest]). Against a binary where these nine loops still say
 * `binderResults` every assertion below is RED, and no diagnostics-side gate can
 * see the difference at all — `checkedResults` IS `binderResults` on a full build,
 * so the corpus, `cost_gate.py` and the 8-profile grid are green either way.
 *
 * Every test saves and restores [PassTiming.enabled] (CLAUDE.md, round 874: a
 * fork-global probe left armed poisons every alphabetically-later class).
 */
class PartitionPerFileAmbientPassTest {

    /**
     * The nine passes (INC.20) moved onto the partition view, largest floor row
     * first. Each installs `currentFileLocals` (and usually `currentCheckFileName`)
     * per iteration, passes only per-file arguments down, retracts nothing, reads
     * `diagnostics` back nowhere and holds no accumulator across the loop.
     */
    private val gated = listOf(
        "checkTypeArgumentConstraints",
        "checkInterfaceMultiBaseConflicts",
        "checkPropertyOverride",
        "checkDerivedConstructorSuper",
        "checkClassImplementsInterface",
        "checkGenericDefaultsValidation",
        "checkObjectLiteralAccessorImpliedReturn",
        "checkBindingPatternUnknownInference",
        "checkDestructuredParamOptionalMemberArgs",
    )

    /**
     * Passes that must stay program-wide, and the reason each is REFUSED — they are
     * the negative control that stops the assertions above from passing on a binary
     * that simply gated everything.
     *
     *  * `init:computeAllEnumValues` and `init:computePerFileVisibility` build
     *    cross-file accumulators the partition's own files are read out of.
     *
     * (INC.25) REMOVED `init:buildFileLocalTypeMaps` from this list, and says so
     * rather than editing it quietly. It was refused by (INC.11) and (INC.22) for a
     * first-touch ORDER cost; (INC.23) censused that cost down to ONE member name
     * and (INC.25) fixed it in `getKeyofType`, where it was never a partition
     * defect to begin with. The pass is now partition-scoped by default and
     * `FltmScopeArmTest` pins that as a COUNT, with no mode install.
     */
    private val programWide = listOf(
        "init:computeAllEnumValues",
        "init:computePerFileVisibility",
    )

    private val twoFiles = arrayOf(
        "/proj/a.ts" to """
            export interface Foo { x: number }
            export class Impl implements Foo { x = 1 }
            export const one: Foo = { x: 1 };
        """,
        "/proj/b.ts" to """
            import { Foo } from "./a.js";
            export const two: Foo = { x: 2 };
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
    fun `every ambient-installing pass gated by INC 20 reads the partition view`() {
        val reads = readsFor(setOf("/proj/a.ts"))
        val missing = gated.filter { it !in reads }
        assert(missing.isEmpty())
    }

    @Test
    fun `each gated pass ran and read the partition at least once`() {
        val reads = readsFor(setOf("/proj/a.ts"))
        val notPositive = gated.filter { (reads[it] ?: 0L) <= 0L }
        assert(notPositive.isEmpty())
    }

    /**
     * The discriminating control in the other direction. If the census marked every
     * pass that RAN rather than every pass that READ the partition, the assertions
     * above would pass against any binary at all; these three ran on the same build
     * and must be absent.
     */
    @Test
    fun `negative control - the passes INC 20 refused are not recorded`() {
        val reads = readsFor(setOf("/proj/a.ts"))
        val wrongly = programWide.filter { it in reads }
        assert(wrongly.isEmpty())
    }

    /**
     * The classification is a property of the CODE, not of the partition: a build
     * with nothing assigned reads `checkedResults` at exactly the same sites,
     * because it IS `binderResults` there. This is what makes the gate a strict
     * no-op for every full build and for the whole corpus.
     */
    @Test
    fun `an unpartitioned build reads the same sites`() {
        val whole = readsFor(null)
        val missing = gated.filter { it !in whole }
        assert(missing.isEmpty())
    }
}
