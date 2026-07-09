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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 459: an array literal ASSIGNED to a tuple-annotated function-body local
 * must be matched AST-side against the declared tuple NODE — the relation engine
 * skips array→tuple and `getTupleType` collapses a variadic rest slot, so the
 * resolved-Type comparison FP-fires TS2322.
 *
 * tsc's own sources trip this: checker.ts `lastSkippedInfo = [source, target]`
 * (`[Type, Type] | undefined`), checker.ts `relatedInfo = [info]`
 * (`[X, ...X[]] | undefined`), esnextAnd2015.ts `importRequireStatements =
 * [importStatement, requireStatement]` (`[ImportDeclaration, VariableStatement]`).
 *
 * The plumbing: body locals are B83.5-unbound, so the assignment walk records the
 * annotation NODE in `currentLocalDeclTypeNodes` (alongside `currentLocalTypes`)
 * and feeds it to the round-446 `arrayLiteralSatisfiesTupleTarget` helper.
 * Suppression-only: wrong arity / wrong element type still fires.
 */
class ArrayLiteralTupleAssignmentTest {

    private val prelude = """
        interface Ty { id: number; }
        interface Info { code: number; }

    """.trimIndent()

    @Test
    fun `fixed two-element tuple target accepts a matching pair - no TS2322`() {
        // checker.ts lastSkippedInfo = [source, target]
        diagnose(prelude + """
            function f(source: Ty, target: Ty) {
                let lastSkippedInfo: [Ty, Ty] | undefined;
                lastSkippedInfo = [source, target];
                return lastSkippedInfo;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `variadic rest tuple target accepts a single matching element - no TS2322`() {
        // checker.ts relatedInfo = [info] vs [X, ...X[]]
        diagnose(prelude + """
            function f(info: Info) {
                let relatedInfo: [Info, ...Info[]] | undefined;
                relatedInfo = [info];
                return relatedInfo;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `heterogeneous fixed tuple target accepts matching elements - no TS2322`() {
        // esnextAnd2015.ts importRequireStatements = [importStatement, requireStatement]
        diagnose(prelude + """
            function f(a: Ty, b: Info) {
                let pair: [Ty, Info] | undefined;
                pair = [a, b];
                return pair;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - too few elements still fires TS2322`() {
        diagnose(prelude + """
            function f(source: Ty) {
                let pair: [Ty, Ty] | undefined;
                pair = [source];
                return pair;
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - wrong element type still fires TS2322`() {
        diagnose(prelude + """
            function f(source: Ty) {
                let pair: [Ty, Ty] | undefined;
                pair = [source, "nope"];
                return pair;
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - empty literal against a rest tuple with a required head still fires TS2322`() {
        diagnose(prelude + """
            function f() {
                let xs: [Ty, ...Ty[]] | undefined;
                xs = [];
                return xs;
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }
}
