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
 * (INC.23) THE SCOPE AXIS IS A MEASUREMENT ARM AND THE SHIPPED DEFAULT IS
 * `PROGRAM` — and the first pin here is the one that CANNOT install a mode.
 *
 * (INC.16) arm a1's lesson: a pin that sets the mode it wants and restores it
 * leaves the shipped DEFAULT pinned by nothing, so flipping the default reads as
 * 0 RED across a 15,000-test suite and looks like a redundant change. Every other
 * test in this class saves and restores [FltmDefer.scope]; the first one must not,
 * because its whole subject is what the binary does with no install at all.
 *
 * ## What the arm is for
 *
 * (INC.22) measured partition-scoping this pass at **floor 131 -> 57 ms,
 * narrowed-query median 166 -> 116 ms, ratio 29.86x -> 42.61x**, with a per-arm
 * capture DIGEST proving an unpartitioned build byte-identical — and REFUSED it,
 * because `capture-channel-equivalence`'s `narrowRendersMoreAny` goes **168 ->
 * 229**: +61 captured member types collapse to `any` under a narrowed build. That
 * is a wrong answer. The arm exists so (INC.23) can census those 61 in the binary
 * rather than reason about them; nothing ships it.
 */
class FltmScopeArmTest {

    /**
     * THE DEFAULT, WITH NO INSTALL — the pin an ablation of the shipped value must
     * redden. Deliberately asserts the enum VALUE and not "the arm is off", so a
     * future third scope cannot satisfy it by accident.
     */
    @Test
    fun `the shipped scope is the whole program and nothing installs it`() {
        assert(FltmDefer.scope == FltmDefer.Scope.PROGRAM)
    }

    /** An unset or unrecognised environment value means the shipped pass. */
    @Test
    fun `an unset or unknown scope name means the whole program`() {
        assert(FltmDefer.scopeFromName(null) == FltmDefer.Scope.PROGRAM)
        assert(FltmDefer.scopeFromName("") == FltmDefer.Scope.PROGRAM)
        assert(FltmDefer.scopeFromName("nonsense") == FltmDefer.Scope.PROGRAM)
        assert(FltmDefer.scopeFromName("program") == FltmDefer.Scope.PROGRAM)
        // The phase arm's own names must NOT silently arm this axis too — the two
        // are independent and a sweep has to be able to vary exactly one.
        assert(FltmDefer.scopeFromName("typealias") == FltmDefer.Scope.PROGRAM)
        assert(FltmDefer.scopeFromName("none") == FltmDefer.Scope.PROGRAM)
    }

    /** The one name that arms it. */
    @Test
    fun `the partition scope is reachable by exactly one name`() {
        assert(FltmDefer.scopeFromName("partition") == FltmDefer.Scope.PARTITION)
        assert(FltmDefer.scopeFromName("PARTITION") == FltmDefer.Scope.PARTITION)
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
     * THE SHIPPED BEHAVIOUR, AS A COUNT: at `PROGRAM` a partitioned build still
     * builds EVERY file's map eagerly. This is what the arm changes, so it is also
     * the control that says the count below is about the arm.
     */
    @Test
    fun `at the shipped scope a partitioned build still builds every map`() {
        check(setOf("/proj/b.ts"))
        assert(FltmDefer.eagerBuilds == 2)
        assert(FltmDefer.lazyBuilds == 0)
    }

    /**
     * THE ARM, AS A COUNT: at `PARTITION` a partition of one file builds ONE map
     * eagerly rather than the program's two. RED against a binary whose loop still
     * says `binderResults` — there the count is 2 whatever the scope is.
     */
    @Test
    fun `the arm builds only the partition's maps eagerly`() {
        withScope(FltmDefer.Scope.PARTITION) { check(setOf("/proj/b.ts")) }
        assert(FltmDefer.eagerBuilds == 1)
        assert(FltmDefer.scope == FltmDefer.Scope.PROGRAM)
    }

    /**
     * THE ARM IS A NO-OP OFF A PARTITION, which is the whole reason this axis was
     * worth building: `checkedResults` IS `binderResults` when `assignedFileNames`
     * is null, so an unpartitioned compile runs the identical list in the identical
     * order and the lazy path never fires. (INC.22) verified the same claim at
     * program scale with a capture digest over 741,818 answers.
     */
    @Test
    fun `the arm is inert on a build with no partition`() {
        withScope(FltmDefer.Scope.PARTITION) { check(null) }
        assert(FltmDefer.eagerBuilds == 2)
        assert(FltmDefer.lazyBuilds == 0)
    }

    /**
     * THE ANSWER IS UNCHANGED under the arm, not merely cheaper — the partition
     * still reports its own file's error.
     */
    @Test
    fun `the arm keeps the partition's own diagnostics`() {
        val armed = withScope(FltmDefer.Scope.PARTITION) { check(setOf("/proj/b.ts")) }
        assert(armed.any { it.code == 2322 && it.fileName == "/proj/b.ts" })
        assert(armed.none { it.fileName == "/proj/a.ts" })
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
