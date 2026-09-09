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
 * (INV.0) step 6a — the [MemberResolver] seam's own pins (ledger row 8).
 *
 * As with [RelaterTest], the extraction is a VERBATIM relocation whose invariant —
 * "nothing changed" — is pinned by the corpus and the round's `--passTiming` receipt
 * far better than any hand-written case (ledger row 2's reasoning). What is NEW is
 * the B202.1 CYCLE BREAK's bookkeeping, which the move splits across two objects:
 * `mrProbeDepth` moved in and is owned, while `memberResolutionInProgress` stays in
 * `CheckerState` and is handed in as the object.
 *
 * That set is `add`ed on entry and removed in a `finally`, and a dropped `finally`
 * is SILENT in the worst way: a stale type id makes every LATER resolution of that
 * type return member-LESS through the cycle break, so the type answers as though it
 * declared nothing — a lost or wrong diagnostic in whatever file is checked next,
 * with no counter, no baseline and no `--listAll` row to show it. Hence
 * [MemberResolver.resolutionResidue].
 *
 * ABLATION RESULT, recorded rather than claimed — see the round note.
 */
class MemberResolverTest {

    /**
     * A program that forces real member-table work: an interface with members read
     * through a reference instantiation (so `resolveReferenceMembers` runs), a
     * generic whose member type is its own type parameter (round 778's
     * context-gated `symbolTypes` write, reached from inside a table build), and a
     * failing assignment so the tables are actually CONSUMED by a comparison rather
     * than built and dropped.
     */
    private val fixture = """
        interface Pt { x: number; y: number }
        interface Box<T> { item: T; all: T[] }
        declare const p: Pt;
        declare const bn: Box<number>;

        const okPt: Pt = p;
        const okBox: Box<number> = bn;
        const bad: Box<string> = bn;
    """.trimIndent()

    private fun checkerOver(source: String): Checker {
        val options = CompilerOptions()
        val results = listOf(Binder(options).bind(Parser(source, "t.ts").parse()))
        return Checker(options, results)
    }

    @Test
    fun `the member-resolution in-progress set unwinds to zero after a whole-program check`() {
        val checker = checkerOver(fixture)
        val diagnostics = checker.getDiagnostics()
        // POSITIVE CONTROL first: a residue of zero over a program whose member tables
        // were never built would pass vacuously (round 790). The failing assignment is
        // decided by comparing two RESOLVED tables.
        assert(diagnostics.any { it.code == 2322 })
        assert(checker.memberResolverResidue == 0)
    }

    @Test
    fun `negative control - the residue is zero on a program that builds no member table`() {
        val checker = checkerOver("export {};")
        checker.getDiagnostics()
        assert(checker.memberResolverResidue == 0)
    }

    @Test
    fun `mutually recursive heritage breaks the cycle instead of overflowing`() {
        // B202.1: `resolveStructuredTypeMembers` -> `resolveInterfaceMembers` ->
        // `resolveReferenceMembers` re-enters for a type whose table is not yet
        // planted, so the `properties != null` guard cannot see it. The in-progress
        // set is what ends it; without the break this is a StackOverflowError that
        // the callers swallow, and the compile answers nothing at all.
        val diagnostics = diagnose(
            """
            interface CycA extends CycB { a: number }
            interface CycB extends CycA { b: number }
            declare const ca: CycA;
            const use: number = ca.a;
            """.trimIndent(),
        )
        // The shape is an ERROR in both references; what this pin states is that the
        // compile TERMINATES and reports it rather than dying inside resolution.
        assert(diagnostics.any { it.code == 2310 || it.code == 2320 || it.code == 24 })
        assert(diagnostics.none { it.code == 2589 })
    }

    @Test
    fun `the residue stays zero even when a resolution cycle was actually broken`() {
        // The pin above proves the cycle break FIRES; this one proves it still
        // unwinds. An arm that drops the `finally`'s remove leaves the id behind
        // exactly on this path, because the cyclic type is the one that re-enters.
        val checker = checkerOver(
            """
            interface CycA extends CycB { a: number }
            interface CycB extends CycA { b: number }
            declare const ca: CycA;
            const use: number = ca.a;
            """.trimIndent(),
        )
        checker.getDiagnostics()
        assert(checker.memberResolverResidue == 0)
    }
}
