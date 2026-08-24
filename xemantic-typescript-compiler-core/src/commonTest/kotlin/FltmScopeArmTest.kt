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
 * (INC.25) THE SCOPE AXIS SHIPS AS `PARTITION`, AND THE FIRST PIN HERE IS THE ONE
 * THAT CANNOT INSTALL A MODE.
 *
 * (INC.16) arm a1's lesson: a pin that sets the mode it wants and restores it
 * leaves the shipped DEFAULT pinned by nothing, so flipping the default reads as
 * 0 RED across a 15,000-test suite and looks like a redundant change. Every other
 * test in this class saves and restores [FltmDefer.scope]; the first one must not,
 * because its whole subject is what the binary does with no install at all.
 *
 * ## Why it ships, and why it did not before
 *
 * (INC.22) measured partition-scoping `init:buildFileLocalTypeMaps` — **69.16 ms
 * of a 90.15 ms incremental floor pass table** — at **floor 131 -> 57 ms,
 * narrowed-query median 166 -> 116 ms, ratio 29.86x -> 42.61x**, with a per-arm
 * capture DIGEST proving an unpartitioned build byte-identical, and REFUSED it on
 * ONE observable: `capture-channel-equivalence`'s `narrowRendersMoreAny` went
 * **168 -> 229**. (INC.23) censused those 61 to **78 rows carrying exactly ONE
 * member name** — `[Symbol.unscopables]`, whose lib type is
 * `{ [K in keyof any[]]?: boolean }` — and (INC.25) found the cause was not the
 * scope at all: a `keyof` over a type whose member table is IN FLIGHT answered
 * `string`, which collapses that mapped type to `any` on a THREE-LINE project with
 * no partition and no arm. With `getKeyofType` repaired the scope is a pure win.
 *
 * `Scope.PROGRAM` survives as the pre-(INC.25) arm, so the two capture sweeps can
 * still measure against the old pass.
 */
class FltmScopeArmTest {

    /**
     * THE DEFAULT, WITH NO INSTALL — the pin an ablation of the shipped value must
     * redden. Deliberately asserts the enum VALUE and not "the arm is off", so a
     * future third scope cannot satisfy it by accident.
     */
    @Test
    fun `the shipped scope is the check partition and nothing installs it`() {
        assert(FltmDefer.scope == FltmDefer.Scope.PARTITION)
    }

    /** An unset or unrecognised environment value means the shipped pass. */
    @Test
    fun `an unset or unknown scope name means the check partition`() {
        assert(FltmDefer.scopeFromName(null) == FltmDefer.Scope.PARTITION)
        assert(FltmDefer.scopeFromName("") == FltmDefer.Scope.PARTITION)
        assert(FltmDefer.scopeFromName("nonsense") == FltmDefer.Scope.PARTITION)
        assert(FltmDefer.scopeFromName("partition") == FltmDefer.Scope.PARTITION)
        // The phase arm's own names must NOT silently move this axis either — the
        // two are independent and a sweep has to be able to vary exactly one.
        assert(FltmDefer.scopeFromName("typealias") == FltmDefer.Scope.PARTITION)
        assert(FltmDefer.scopeFromName("none") == FltmDefer.Scope.PARTITION)
    }

    /** The one name that selects the pre-(INC.25) whole-program pass. */
    @Test
    fun `the program scope is reachable by exactly one name`() {
        assert(FltmDefer.scopeFromName("program") == FltmDefer.Scope.PROGRAM)
        assert(FltmDefer.scopeFromName("PROGRAM") == FltmDefer.Scope.PROGRAM)
    }

    private val twoFiles = arrayOf(
        // The file-level DECLARATIONS are what the map stores — an un-annotated
        // `const` is in neither phase, so a fixture of bare constants gives two
        // EMPTY maps and every count below would be about nothing.
        "/proj/a.ts" to """
            export interface Shape { a: number }
            export declare const shared: Shape;
            export function helper(n: number): number { return n; }
        """,
        "/proj/b.ts" to """
            import { Shape, shared } from "./a.js";
            export declare const limit: number;
            export const local: Shape = shared;
            export const bad: string = shared.a;
        """,
    )

    private fun check(assigned: Set<String>?): List<Diagnostic> {
        val options = CompilerOptions()
        val results = twoFiles.map { (name, src) ->
            Binder(options).bind(Parser(src.trimIndent(), name).parse())
        }
        return Checker(
            options, results, isMultiFileSource = true, assignedFileNames = assigned,
        ).getDiagnostics()
    }

    private fun <T> withScope(scope: FltmDefer.Scope, body: () -> T): T {
        val saved = FltmDefer.scope
        return try {
            FltmDefer.scope = scope
            body()
        } finally {
            FltmDefer.scope = saved
        }
    }

    /**
     * THE PRE-(INC.25) BEHAVIOUR, AS A COUNT: at `PROGRAM` a partitioned build
     * still builds EVERY file's map eagerly. It is the control that says the count
     * below is about the SCOPE and not about the fixture.
     */
    @Test
    fun `at the program scope a partitioned build still builds every map`() {
        withScope(FltmDefer.Scope.PROGRAM) { check(setOf("/proj/b.ts")) }
        assert(FltmDefer.eagerBuilds == 2)
        assert(FltmDefer.lazyBuilds == 0)
    }

    /**
     * THE SHIPPED BEHAVIOUR, AS A COUNT AND WITH NO INSTALL: a partition of one
     * file builds ONE map eagerly rather than the program's two. RED against a
     * binary whose loop still says `binderResults` — there the count is 2 whatever
     * the scope is — and RED against a reverted default, which is what makes the
     * absence of a `withScope` here load-bearing.
     */
    @Test
    fun `the shipped scope builds only the partition's maps eagerly`() {
        check(setOf("/proj/b.ts"))
        assert(FltmDefer.eagerBuilds == 1)
        assert(FltmDefer.scope == FltmDefer.Scope.PARTITION)
    }

    /**
     * IT IS A NO-OP OFF A PARTITION, which is the whole reason this axis was worth
     * building: `checkedResults` IS `binderResults` when `assignedFileNames` is
     * null, so an unpartitioned compile runs the identical list in the identical
     * order and the lazy path never fires. (INC.22) verified the same claim at
     * program scale with a capture digest over 741,818 answers.
     */
    @Test
    fun `the shipped scope is inert on a build with no partition`() {
        check(null)
        assert(FltmDefer.eagerBuilds == 2)
        assert(FltmDefer.lazyBuilds == 0)
    }

    /**
     * THE ANSWER IS UNCHANGED, not merely cheaper — the partition still reports its
     * own file's error.
     */
    @Test
    fun `the shipped scope keeps the partition's own diagnostics`() {
        val narrowed = check(setOf("/proj/b.ts"))
        assert(narrowed.any { it.code == 2322 && it.fileName == "/proj/b.ts" })
        assert(narrowed.none { it.fileName == "/proj/a.ts" })
    }

    /**
     * NEGATIVE CONTROL for the pin above — the same row is reported by the whole
     * program, so the narrowed build's agreement is a measurement rather than two
     * empty lists matching. CLAUDE.md's rule for any equivalence pin: assert the
     * reference arm is NON-EMPTY.
     */
    @Test
    fun `negative control - the unpartitioned build reports the same row`() {
        assert(check(null).any { it.code == 2322 && it.fileName == "/proj/b.ts" })
    }
}
