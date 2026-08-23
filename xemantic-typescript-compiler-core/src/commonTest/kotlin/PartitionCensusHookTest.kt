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
 * (INC.17): the PARTITION CENSUS hook — the instrument that classifies every
 * checker `init` pass as partition-INVARIANT or partition-DEPENDENT, which is
 * the count that decides whether a re-entrant checker is a classification or a
 * rewrite.
 *
 * The classification is taken at RUN TIME, from a getter on `checkedResults`
 * itself, precisely because a source analyzer over `Checker.kt` fails silently
 * and in the reassuring direction (CLAUDE.md: a Kotlin stripper that handles
 * `'x'` desynchronises on an escaped apostrophe and then reports "no hazard"
 * over an EMPTY closure; the `pass("name") { … }` sample inside a KDoc parses as
 * a real registration). A getter cannot be wrong about who read it.
 *
 * What these pins defend, and each was verified RED by ablating exactly it:
 *
 *  * the hook FIRES at all, and attributes to the INNERMOST wrapped pass rather
 *    than to one fixed name;
 *  * `checkSpine` — whose partition read does NOT go through `checkedResults`
 *    but through a direct `assignedFileNames` test — is nevertheless recorded,
 *    which is the one place the census could have silently under-reported;
 *  * a pass that iterates `binderResults` is recorded NOWHERE, i.e. the census
 *    discriminates rather than marking everything;
 *  * nothing is attributed outside a pass, which is what makes the per-pass sums
 *    a partition of the reads rather than a sample of them;
 *  * and it is OFF unless [PassTiming.enabled], so INV.0's "false must stay
 *    behaviour-free" holds for it too.
 *
 * Every test here saves and restores [PassTiming.enabled] — a test that leaves a
 * fork-global probe armed poisons every alphabetically-later class (CLAUDE.md,
 * round 874).
 */
class PartitionCensusHookTest {

    private fun censusOf(
        assigned: Set<String>?,
        vararg files: Pair<String, String>,
    ): Pair<Map<String, Long>, Long> {
        val options = CompilerOptions()
        val results = files.map { (name, src) ->
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
            return LinkedHashMap(PassTiming.partitionReadsByPass) to
                PassTiming.partitionReadsOutsidePass
        } finally {
            PassTiming.enabled = savedEnabled
            PassTiming.reset()
        }
    }

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

    @Test
    fun `the census records partition reads and attributes them to named passes`() {
        val (reads, _) = censusOf(setOf("/proj/a.ts"), *twoFiles)
        assert(reads.isNotEmpty())
        assert(reads.values.all { it > 0L })
    }

    @Test
    fun `checkSpine's direct assignedFileNames read is recorded`() {
        val (reads, _) = censusOf(setOf("/proj/a.ts"), *twoFiles)
        assert("checkSpine" in reads)
        // One read per program file, because the filter is inside the file loop.
        assert(reads["checkSpine"] == 2L)
    }

    @Test
    fun `the population is the GETTER's, not checkSpine's explicit hook alone`() {
        // The ablation that removes `notePartitionRead()` from the `checkedResults`
        // getter leaves `checkSpine`'s own direct `assignedFileNames` hook firing, so
        // every other pin here still passes and the census silently shrinks to ONE
        // row. This is the pin that sees it: the recorded set must be the ~200-row
        // population of `for (result in checkedResults)` passes.
        val (reads, _) = censusOf(setOf("/proj/a.ts"), *twoFiles)
        assert(reads.size >= 50)
        assert("checkSubsequentVarTypes" in reads)
    }

    @Test
    fun `a program-wide pass that iterates binderResults is NOT recorded`() {
        val (reads, _) = censusOf(setOf("/proj/a.ts"), *twoFiles)
        // These three run on every build and every one of them is a
        // `binderResults` loop, so a census that marked everything would fail.
        assert("init:buildFileLocalTypeMaps" !in reads)
        assert("init:computeAllEnumValues" !in reads)
        assert("init:computePerFileVisibility" !in reads)
    }

    @Test
    fun `the recorded passes are a strict subset of the passes that ran`() {
        val savedEnabled = PassTiming.enabled
        val ran: Set<String>
        val reads: Set<String>
        try {
            val options = CompilerOptions()
            val results = twoFiles.map { (name, src) ->
                Binder(options).bind(Parser(src.trimIndent(), name).parse())
            }
            PassTiming.reset()
            PassTiming.detail = false
            PassTiming.spineDetail = false
            PassTiming.enabled = true
            Checker(
                options,
                results,
                isMultiFileSource = true,
                assignedFileNames = setOf("/proj/a.ts"),
            ).getDiagnostics()
            ran = PassTiming.passNanos.keys.toSet()
            reads = PassTiming.partitionReadsByPass.keys.toSet()
        } finally {
            PassTiming.enabled = savedEnabled
            PassTiming.reset()
        }
        assert(reads.isNotEmpty())
        assert(ran.size > reads.size)
        assert(reads.all { it in ran })
    }

    @Test
    fun `no partition read is attributed outside a pass`() {
        val (_, outside) = censusOf(setOf("/proj/a.ts"), *twoFiles)
        assert(outside == 0L)
    }

    @Test
    fun `an UNPARTITIONED build reads the partition exactly as a partitioned one does`() {
        // `checkedResults === binderResults` when nothing is assigned, so the
        // classification is a property of the CODE, not of the partition — which
        // is what lets the census be taken on any build.
        val (partitioned, _) = censusOf(setOf("/proj/a.ts"), *twoFiles)
        val (whole, _) = censusOf(null, *twoFiles)
        assert(whole.keys == partitioned.keys)
    }

    @Test
    fun `negative control - the census is silent while PassTiming is off`() {
        val options = CompilerOptions()
        val results = twoFiles.map { (name, src) ->
            Binder(options).bind(Parser(src.trimIndent(), name).parse())
        }
        val savedEnabled = PassTiming.enabled
        try {
            PassTiming.reset()
            PassTiming.enabled = false
            Checker(
                options,
                results,
                isMultiFileSource = true,
                assignedFileNames = setOf("/proj/a.ts"),
            ).getDiagnostics()
            assert(PassTiming.partitionReadsByPass.isEmpty())
            assert(PassTiming.partitionReadsOutsidePass == 0L)
        } finally {
            PassTiming.enabled = savedEnabled
            PassTiming.reset()
        }
    }
}
