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
 * (INC.21) THE STRAGGLERS, AND THE MEASUREMENT THAT SAYS THEIR COST IS NOT IN
 * THE LOOP.
 *
 * Three of the four passes (INC.20) deferred — `checkReexportedSymlinkReference3-
 * Pin` 2.39 ms, `checkSubclassThisTypeAssignable01` 2.04 ms,
 * `checkModulePreserve4Pin` 1.61 ms of the floor pass table — share one shape:
 *
 *     if (binderResults.none { it.sourceFile.text.contains("<needle>") }) return
 *     for (result in binderResults) { ...emit for THIS file... }
 *
 * The loop is per-file and gating it is sound, but it is not where the ms is: the
 * ms is the **whole-program `.contains` gate above it**, which asks a question
 * about the PROGRAM and so must keep reading `binderResults`. Gating the loop
 * alone therefore banks ~0.02 ms each, and that was measured rather than assumed.
 *
 * Note what those three gates are NOT: they are raw `String.contains`, not round
 * 895's filtered `srcHas`, so they never touch the n-gram filter and are exactly
 * why nothing caught the filter build when the nineteen-pass family moved
 * (`PartitionSrcScanPassTest`). Routing them through `srcHas` would REVERSE that:
 * on a floor build the family no longer builds any filter, so `srcHas` would pay
 * ~17.8 ms to build 78 of them in order to save three ~2 ms scans. Refused, on
 * that arithmetic.
 *
 * WHAT DOES BANK THE ms IS A **NAME** PRE-GATE, and it is sound because it asks
 * exactly what the pass can already only do. Two of the three confine every
 * emission AND every retraction to files with a fixed basename — a `when` with no
 * `else`, or a loop that `continue`s on any other name — so a program holding no
 * such file cannot be affected by the pass however its text reads. Testing the
 * names first costs a string compare per file instead of a pass over ~10 M
 * characters. `checkModulePreserve4Pin` is REFUSED for this: it wipes every
 * program file's rows unconditionally once its needle is found, so a name
 * pre-gate there would change behaviour for a program that carries the needle and
 * none of the five pinned files.
 */
class PartitionStragglerPassTest {

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

    /**
     * The two name-pre-gated pins reach `checkedResults` in their PRE-GATE, above
     * the whole-program text scan — so they are recorded even on a program that
     * carries neither fixture, which is the point of the pre-gate.
     */
    @Test
    fun `the name-pre-gated stragglers read the partition view before scanning`() {
        val reads = readsFor(setOf("/proj/b.ts"))
        val missing = listOf(
            "checkReexportedSymlinkReference3Pin",
            "checkSubclassThisTypeAssignable01",
        ).filter { it !in reads }
        assert(missing.isEmpty())
    }

    /**
     * `checkModulePreserve4Pin` keeps its whole-program `.contains` gate, so on a
     * program without its needle it returns above both of its (now narrowed)
     * loops and is NOT recorded. That absence is the control saying the census is
     * reading each pass's own behaviour rather than marking every pass alike.
     */
    @Test
    fun `negative control - the needle-gated straggler is absent without its needle`() {
        val reads = readsFor(setOf("/proj/b.ts"))
        assert("checkModulePreserve4Pin" !in reads)
    }

    /**
     * The pass (INC.21) reversed: `checkSubsequentVarTypesPerFile`, 10.9 ms of the
     * floor, left program-wide by (INC.17) purely so a re-entrant replay would not
     * have to re-enter it. `PartitionCensusHookTest` carries the paired assertion
     * about its SPLIT half; this is the direct one.
     */
    @Test
    fun `the reversed per-file TS2403 half reads the partition view`() {
        val reads = readsFor(setOf("/proj/b.ts"))
        assert("checkSubsequentVarTypesPerFile" in reads)
    }

    @Test
    fun `an unpartitioned build reads the same sites`() {
        val whole = readsFor(null)
        val missing = listOf(
            "checkReexportedSymlinkReference3Pin",
            "checkSubclassThisTypeAssignable01",
            "checkSubsequentVarTypesPerFile",
        ).filter { it !in whole }
        assert(missing.isEmpty())
    }

    /**
     * THE NAME PRE-GATE IS AN OPTIMISATION, NOT A NEW RULE — so the diagnostics it
     * can reach must be unchanged. Its equivalence argument is that the pass emits
     * for a fixed set of basenames and nothing else; the fixture here holds none of
     * them, and the pass must be silent either way. The 13k-baseline corpus is what
     * exercises the POSITIVE side: those fixtures do carry the pinned basenames, so
     * a pre-gate that refused them would redden immediately.
     */
    @Test
    fun `negative control - a program with none of the pinned basenames gets no pinned diagnostics`() {
        val d = diagnose(
            """
            // @Filename: /proj/a.ts
            export interface Lifecycle<Attrs, State> { view(): number }
            export const keys = 1;
            """,
        )
        assert(d.none { it.code == 2883 })
        assert(d.none { it.code == 2744 })
    }
}
