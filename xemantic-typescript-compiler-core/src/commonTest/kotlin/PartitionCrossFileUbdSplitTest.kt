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
 * (INC.20) `checkCrossFileUseBeforeDeclaration` SPLIT — AND THE ORDINAL INVARIANT
 * THAT MAKES THE OBVIOUS NARROWING WRONG.
 *
 * The pass is MIXED in exactly the shape (INC.20) describes: a whole-program
 * COLLECTOR that writes no diagnostic, then a per-file EMITTING loop over the
 * collected index. Only the emitter moved — the collector's whole reason to exist
 * is that the DECLARATION lives in a file the partition does not contain, so
 * gating it would starve the index of what the emission resolves through
 * (round 609).
 *
 * ## The mistake these pins are for
 *
 * The verdict is `decl.fileIdx > useFileIdx`, and both sides are ordinals of
 * `binderResults`. Re-heading the emitter on `checkedResults.withIndex()` — the
 * mechanical way to narrow a per-file loop, and what every other gated walker in
 * this checker does — RENUMBERS `useFileIdx`: under a one-file partition it
 * becomes 0, so `decl.fileIdx > 0` holds for nearly every declaration in the
 * program and the verdict becomes a function of the PARTITION, flipping toward
 * FALSE POSITIVES. The shipped narrowing is therefore a `continue` applied AFTER
 * the enumeration, exactly like the `.d.ts` and `.js` skips already in that loop.
 *
 * Ablated to `checkedResults.withIndex()`, [a use whose declaration is EARLIER
 * stays silent under a partition] goes RED and reports a TS2448 that no
 * unpartitioned build produces. The COUNT pin below does NOT discriminate that
 * arm — `checkedResults` is pre-filtered, so it visits the same one file — and is
 * recorded as such rather than claimed: it is the pin that says the narrowing
 * happened at all, i.e. it is RED against the un-narrowed parent.
 *
 * ## Why a COUNT and not a millisecond
 *
 * (INC.52): the row is ~1.5 ms and a per-pass draw on the incremental floor swings
 * ~40%, so a timed assertion here is a coin flip (round 868). The population is
 * deterministic.
 */
class PartitionCrossFileUbdSplitTest {

    /**
     * Three SCRIPT files (no import/export, so every file-level `let` is a program
     * global) whose declaration/use pairs run in BOTH directions across the
     * `binderResults` ordering:
     *
     *  - `/proj/a.ts` (index 0) USES `later`, declared at index 2 — a genuine
     *    cross-file TS2448, and the case the whole-program collector exists for;
     *  - `/proj/b.ts` (index 1) DECLARES `earlier`;
     *  - `/proj/c.ts` (index 2) USES `earlier`, declared at index 1 — i.e. the
     *    declaration is EARLIER, so this must stay silent. It is the one that
     *    flips when `useFileIdx` is renumbered.
     */
    private val program = arrayOf(
        "/proj/a.ts" to """
            later;
        """,
        "/proj/b.ts" to """
            let earlier = 1;
        """,
        "/proj/c.ts" to """
            earlier = 2;
            let later = 3;
        """,
    )

    /** The census counters are process-global, so every test SAVES AND RESTORES them. */
    private fun <T> withCensus(block: () -> T): T {
        val saved = EagerIndexCensus.crossFileUbdEmitterFiles
        EagerIndexCensus.crossFileUbdEmitterFiles = 0
        try {
            return block()
        } finally {
            EagerIndexCensus.crossFileUbdEmitterFiles = saved
        }
    }

    private fun bind(): List<BinderResult> {
        val options = CompilerOptions()
        return program.map { (name, src) ->
            Binder(options).bind(Parser(src.trimIndent(), name).parse())
        }
    }

    private fun diagnosticsFor(assigned: Set<String>?): List<Diagnostic> {
        val results = bind()
        return runWithDeepStack {
            Checker(
                CompilerOptions(),
                results,
                isMultiFileSource = true,
                assignedFileNames = assigned,
            ).getDiagnostics()
        }
    }

    // ---------------------------------------------------------------------
    // 1. THE COUNT — the emitter loop is the half that narrows
    // ---------------------------------------------------------------------

    @Test
    fun `an unpartitioned build walks every eligible file in the emitting loop`() {
        withCensus {
            diagnosticsFor(null)
            assert(EagerIndexCensus.crossFileUbdEmitterFiles == 3)
        }
    }

    @Test
    fun `a one-file partition walks exactly that file in the emitting loop`() {
        withCensus {
            diagnosticsFor(setOf("/proj/c.ts"))
            assert(EagerIndexCensus.crossFileUbdEmitterFiles == 1)
        }
    }

    /**
     * The floor of an editor query: a partition naming no file of the program
     * checks nothing, so the emitting loop has nothing to walk — while the
     * collector, which is not counted here, still ran.
     */
    @Test
    fun `a partition naming no file of the program walks nothing in the emitting loop`() {
        withCensus {
            diagnosticsFor(setOf("/proj/nowhere.ts"))
            assert(EagerIndexCensus.crossFileUbdEmitterFiles == 0)
        }
    }

    // ---------------------------------------------------------------------
    // 2. THE VALUE — the collector must still see the whole program
    // ---------------------------------------------------------------------

    /**
     * The case the collector's whole-program scope exists for: the DECLARATION is
     * in `/proj/c.ts`, which the partition does not contain, and the USE is in the
     * one file it does. Narrowing the collector as well would lose the declaration
     * and this row with it.
     */
    @Test
    fun `a declaration in a later file outside the partition still reports TS2448`() {
        val diagnostics = diagnosticsFor(setOf("/proj/a.ts"))
        val hit = diagnostics.any { it.code == 2448 && it.fileName == "/proj/a.ts" }
        assert(hit)
    }

    /** The same row on an unpartitioned build — the reference answer the partition must reproduce. */
    @Test
    fun `an unpartitioned build reports the same TS2448`() {
        val diagnostics = diagnosticsFor(null)
        val hit = diagnostics.any { it.code == 2448 && it.fileName == "/proj/a.ts" }
        assert(hit)
    }

    // ---------------------------------------------------------------------
    // 3. THE NEGATIVE CONTROL — the ordinal invariant itself
    // ---------------------------------------------------------------------

    /**
     * `/proj/c.ts` uses `earlier`, whose declaration is at `binderResults` index 1
     * against c's own index 2 — declared BEFORE the use, so there is nothing to
     * report, and an unpartitioned build reports nothing.
     *
     * This is the pin that fails when `useFileIdx` stops being a `binderResults`
     * ordinal: re-headed on `checkedResults.withIndex()` the partition makes c's
     * index 0, `1 > 0` holds, and a TS2448 appears that no unpartitioned build
     * produces. Without it, pin 2 alone is satisfied by a pass that emits
     * whenever a declaration exists anywhere.
     */
    @Test
    fun `a use whose declaration is in an earlier file stays silent under a partition`() {
        val diagnostics = diagnosticsFor(setOf("/proj/c.ts"))
        val spurious = diagnostics.any { it.code == 2448 }
        assert(!spurious)
    }

    /** The vacuity guard for the control above - it is silent unpartitioned too. */
    @Test
    fun `a use whose declaration is in an earlier file stays silent unpartitioned`() {
        val diagnostics = diagnosticsFor(null)
        val spurious = diagnostics.any { it.code == 2448 && it.fileName == "/proj/c.ts" }
        assert(!spurious)
    }
}
