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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test
import kotlin.test.fail

/**
 * Local corner-case pins for the M0.3(viii) structure bundle (round 623):
 *
 *  1. Parser line-start computation is LAZY (computed on the first diagnostic
 *     needing a line/col, not per parse) and unboxed — the invariant pinned is
 *     that diagnostic line/character stay correct across all three line-ending
 *     conventions (`\n`, `\r\n`, lone `\r`), including that `\r\n` counts as
 *     ONE line break (a regression here shifts every CRLF file's positions —
 *     the bench project's sources are CRLF per the CLAUDE.md gotcha).
 *
 *  2. [Checker.fileDeclaresNonGenericType] is memoized + index-served — the
 *     invariant is the round-442 verdict unchanged in both directions: a
 *     file's OWN non-generic interface shadows a cross-file generic same-name
 *     (no TS2314 on a bare reference), while a same-file GENERIC declaration
 *     keeps its real arity (TS2314 fires).
 *
 *  3. ccetSpineEnter's kindId dispatch — smoke pins over arms whose frames
 *     feed call-type checking (the corpus + 8-profile listAll byte-identity
 *     are the broad gate; these pin the shapes locally).
 */
class M03StructureBundleTest {

    // -- 1. line-start laziness/unboxing: positions across line endings -------

    /** Direct compile (not [diagnose]) because trimIndent would NORMALIZE the
     *  \r\n / \r endings this test exists to exercise. */
    private fun positionsOf(newline: String): Pair<Int, Int> {
        val source = listOf(
            "let a = 1;",
            "let b = ;",
            "let c = 2;",
        ).joinToString(newline, postfix = newline)
        val diags = TypeScriptCompiler().compile(source, "t.ts").diagnostics
        val d = diags.firstOrNull { it.line != null }
            ?: fail("expected at least one positioned diagnostic, got: $diags")
        return (d.line ?: -1) to (d.character ?: -1)
    }

    @Test
    fun `diagnostic line and character are identical across LF - CRLF - and lone CR line endings`() {
        val lf = positionsOf("\n")
        val crlf = positionsOf("\r\n")
        val cr = positionsOf("\r")
        // The error is on source line 2 in every convention — \r\n must count
        // as ONE break (double-counting would report line 3+).
        assert(lf.first == 2)
        assert(crlf == lf)
        assert(cr == lf)
    }

    @Test
    fun `negative control - empty and single-line sources parse without positions crashing`() {
        // Empty source: nothing to diagnose, lazily NO line table is ever built.
        val empty = TypeScriptCompiler().compile("", "t.ts").diagnostics
        empty should { have(none { it.category == DiagnosticCategory.Error }) }
        // A no-trailing-newline error on line 1 still positions correctly.
        val d = TypeScriptCompiler().compile("let b = ;", "t.ts").diagnostics
            .firstOrNull { it.line != null } ?: fail("expected a positioned diagnostic")
        assert(d.line == 1)
    }

    // -- 2. fileDeclaresNonGenericType memo: the round-442 verdict ------------

    @Test
    fun `own-file non-generic interface shadows a cross-file generic type - no TS2314`() {
        val diags = diagnose(
            """
            // @Filename: types.ts
            export type Transformer<T> = (x: T) => T;
            export const keep = 1;
            // @Filename: use.ts
            interface Transformer { go(): void; }
            let t: Transformer;
            let u: Transformer;
            """,
        )
        diags should { have(none { it.code == 2314 }) }
    }

    @Test
    fun `negative control - a same-file generic type keeps its real arity - TS2314 fires`() {
        val diags = diagnose(
            """
            type G<T> = T[];
            let g: G;
            """,
        )
        diags should { have(any { it.code == 2314 }) }
    }

    // -- 3. ccetSpineEnter kindId dispatch: per-arm smoke pins ----------------

    @Test
    fun `method frame types this - instance member access checks clean`() {
        val diags = diagnose(
            """
            class C {
                x: number = 1;
                m(): number { return this.x; }
            }
            new C().m();
            """,
        )
        diags should { have(none { it.category == DiagnosticCategory.Error }) }
    }

    @Test
    fun `contextual fn-type annotation types an arrow initializer's params - B246 arm`() {
        val diags = diagnose(
            """
            type F = (s: string) => number;
            const f: F = (s) => s.length;
            f("x");
            """,
        )
        diags should { have(none { it.category == DiagnosticCategory.Error }) }
    }

    @Test
    fun `negative control - a bad argument to the contextually typed fn still fires TS2345`() {
        val diags = diagnose(
            """
            type F = (s: string) => number;
            const f: F = (s) => s.length;
            f(1);
            """,
        )
        diags should { have(any { it.code == 2345 }) }
    }

    @Test
    fun `static generic method mints its own TP scope - B74_5 arm`() {
        val diags = diagnose(
            """
            class D<T> {
                v: T | undefined;
                static s<U>(u: U): U { return u; }
            }
            D.s(1);
            """,
        )
        diags should { have(none { it.category == DiagnosticCategory.Error }) }
    }
}
