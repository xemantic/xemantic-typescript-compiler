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
 * (INC.21) THE WHOLE-SOURCE-SCAN FAMILY IS ONE BATCH, AND THE RECEIPT THAT SAYS
 * SO IS A **COUNT OF FILTER BUILDS**, NOT A ms.
 *
 * Round 895 put every whole-source `contains`/`indexOf` in `Checker.kt` behind a
 * per-file n-gram presence filter, and that filter is built **lazily, once per
 * source text**. So the ~19 corpus-unique pin walkers that ask
 * `srcHas(result.sourceFile.text, "<needle>")` of every file in the program do
 * not each pay for a scan — the FIRST of them in pass order pays to BUILD every
 * file's filter, and the other eighteen ride on it for nothing.
 *
 * That is why gating them PIECEMEAL banks nothing, and it has now been measured
 * twice: (INC.7) batch 2 saw `checkBaseClassImprovedMismatch` go 0.07 -> 17.89 ms
 * and mis-read it as a walker that had got slower, and (INC.20) named the
 * mechanism when `checkReverseMappedIntersectionConstraint` went 0.067 -> 19.431
 * ms — the only row outside its batch to move more than 0.2 ms. Gate one and the
 * build cost walks to the next scanner; gate the family and it has nowhere to go.
 *
 * WHY THE GATE IS SOUND FOR EVERY MEMBER. All nineteen are per-FILE emitters:
 * the loop's only cross-iteration state is `diagnostics`, every emission carries
 * the loop's own `fileName`, and the retraction — where there is one — is
 * `diagnostics.removeAll { it.fileName == fileName }`, i.e. scoped to the file
 * being walked. `Checker.getDiagnostics` already drops every row naming a file
 * outside the partition, so a narrowed build can only skip work whose output it
 * would have thrown away.
 *
 * WHAT WAS REFUSED, AND WHY IT IS THE SAME QUESTION ASKED OF THE EMISSION RATHER
 * THAN OF THE LOOP: `checkModuleAugmentationReexportDuplicates` and
 * `checkCjsExportAugmentationConflict` also loop `binderResults` and also reach a
 * whole-source scan, and both are refused, because `emitAugReexportDup` adds
 * **two** top-level diagnostics — one on the augmenting file and one on the
 * augmentation's TARGET. Narrow the loop and a partition holding only the target
 * loses its row, since the augmenting file is never reached to emit it. A
 * related-information child naming another file is fine (it is filtered with its
 * parent); a second `diagnostics.add` is not.
 *
 * The pins are counts on the (INC.17) census hook, which records a pass if and
 * only if that pass read `checkedResults`. They are RED against a binary whose
 * loops still say `binderResults`, and they are invisible to every
 * diagnostics-side gate — the corpus, `cost_gate.py` and the eight dashboard
 * profiles are all green either way, because `checkedResults` IS `binderResults`
 * whenever there is no partition.
 */
class PartitionSrcScanPassTest {

    /**
     * The nineteen passes (INC.21) narrowed. Seventeen are corpus-unique pin
     * walkers whose loop is the first statement of the function; the last two
     * carry an option guard above it and so are pinned separately, with the
     * option set that opens the guard.
     */
    private val gatedUnconditional = listOf(
        "checkReverseMappedIntersectionConstraint",
        "checkRecursiveConditionalTypesPin",
        "checkObjectLiteralExcessProperties",
        "checkExcessPropertyCheckWithUnions",
        "checkComplexRecursiveCollections",
        "checkMapUpsert",
        "checkOperationsAvailableOnPromisedType",
        "checkUnicodeIdentifierName2",
        "checkShebangError",
        "checkParseUnmatchedTypeAssertion",
        "checkBigintArbitraryIdentifierPin",
        "checkParseUnaryJsx4Pin",
        "checkEs6ExportEqualsInteropPin",
        "checkMappedTypeAsClauseLateBoundPin",
        "checkPreserveSymlinksPin",
        "checkParseImportAttributesErrorPin",
        "checkMappedTypeRecursiveInferencePin",
    )

    private val twoFiles = arrayOf(
        "/proj/a.ts" to """
            export interface Foo { x: number }
            export const one: Foo = { x: 1 };
        """,
        "/proj/b.ts" to """
            import { Foo } from "./a.js";
            export const two: Foo = { x: 2 };
        """,
    )

    private fun readsFor(
        assigned: Set<String>?,
        options: CompilerOptions = CompilerOptions(),
    ): Map<String, Long> {
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
    fun `every unconditional whole-source-scan pass reads the partition view`() {
        val reads = readsFor(setOf("/proj/b.ts"))
        val missing = gatedUnconditional.filter { it !in reads }
        assert(missing.isEmpty())
    }

    @Test
    fun `the es2022 lib-guarded scanner reads the partition view once its guard opens`() {
        // `checkSymbolAsWeakTypeArg` returns before its loop unless `lib` names
        // es2022, so a default-options fixture would pin it vacuously.
        val reads = readsFor(setOf("/proj/b.ts"), CompilerOptions(lib = listOf("es2022")))
        assert("checkSymbolAsWeakTypeArg" in reads)
    }

    @Test
    fun `negative control - the es2022 scanner is absent when its own guard is shut`() {
        // The control that makes the pin above a measurement: with no `lib` the
        // pass never reaches its loop, so its presence in the census would mean
        // the census was recording something other than this pass's own read.
        val reads = readsFor(setOf("/proj/b.ts"), CompilerOptions(lib = emptyList()))
        assert("checkSymbolAsWeakTypeArg" !in reads)
    }

    @Test
    fun `the non-strict computed-destructuring scanner reads the partition view`() {
        // `checkComputedDestructuringKeyTypes` returns above its loop under
        // `strict`/`noImplicitAny`; the default options open it.
        val reads = readsFor(setOf("/proj/b.ts"))
        assert("checkComputedDestructuringKeyTypes" in reads)
    }

    @Test
    fun `negative control - the computed-destructuring scanner is absent under strict`() {
        val reads = readsFor(setOf("/proj/b.ts"), CompilerOptions(strict = true))
        assert("checkComputedDestructuringKeyTypes" !in reads)
    }

    @Test
    fun `negative control - the two cross-file augmentation walkers are still program-wide`() {
        // Both reach a whole-source scan from a `binderResults` loop and both are
        // REFUSED: `emitAugReexportDup` adds a standalone diagnostic on the
        // augmentation TARGET, so narrowing the loop would delete a row for an
        // in-partition file whenever the AUGMENTING file is out of it.
        val reads = readsFor(setOf("/proj/b.ts"), CompilerOptions(checkJs = true))
        val wrongly = listOf(
            "checkModuleAugmentationReexportDuplicates",
            "checkCjsExportAugmentationConflict",
        ).filter { it in reads }
        assert(wrongly.isEmpty())
    }

    @Test
    fun `an unpartitioned build reads the same sites`() {
        val whole = readsFor(null)
        val missing = gatedUnconditional.filter { it !in whole }
        assert(missing.isEmpty())
    }

    /**
     * THE COST RECEIPT, AND IT IS A COUNT. `SrcScanCache.filterFor` builds one
     * filter per source TEXT and `SrcScan.builds` counts them whether or not any
     * census flag is on, so this is deterministic and needs no timing.
     *
     * A narrowed build must build strictly FEWER filters than the whole-program
     * build of the same program: the whole-program arm reaches both files'
     * texts, the narrowed arm only the partition's. That inequality is what
     * "the family moved together" means operationally, and it is RED on a binary
     * where any one of the nineteen still loops `binderResults` — that pass alone
     * re-builds every file's filter.
     */
    @Test
    fun `a narrowed build builds fewer whole-source scan filters than the whole program`() {
        val narrowed = filterBuildsFor(setOf("/proj/b.ts"))
        val whole = filterBuildsFor(null)
        assert(narrowed < whole)
    }

    private fun filterBuildsFor(assigned: Set<String>?): Long {
        val options = CompilerOptions()
        val results = twoFiles.map { (name, src) ->
            Binder(options).bind(Parser(src.trimIndent(), name).parse())
        }
        SrcScan.reset()
        Checker(
            options,
            results,
            isMultiFileSource = true,
            assignedFileNames = assigned,
        ).getDiagnostics()
        val builds = SrcScan.builds
        SrcScan.reset()
        return builds
    }
}
