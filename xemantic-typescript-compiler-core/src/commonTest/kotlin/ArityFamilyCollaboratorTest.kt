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
 * (INV.0) (P18.237) The ARITY family as it lives in `SignatureArity` after the second
 * extraction: the arity message (`formatExpectedArgs`) and emitters (TS2554 too many / too
 * few with its TS6210, TS2555, TS2556), the spread-arity view, `signatureDeclaredArity`
 * and the call-side minimum's void trim — each reached from a caller that STAYED on
 * `Checker` (the name-based walkers and the property-access reader) and from the
 * signature-based reader that moved in (P18.232).
 *
 * Every expectation is tsgo 7.0.2's row (cells under `build/bench/p18237-agent/matrix/`,
 * 1-based column). Not pinned, because it is wrong on both arms of the move: a tuple-rest
 * callee called with too MANY arguments (`...a: [string, number?]`, `tup("a", 1, 2)`)
 * reads `Expected 2 arguments` here where tsgo reads `Expected 1-2 arguments`.
 */
class ArityFamilyCollaboratorTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `a too-few call names its range and the first missing parameter`() {
        val d = diagnose(
            """
            function two(a: number, b: string) {}
            two(1);
            """
        )
        val row = d.single()
        val related = row.relatedInformation.map { "${it.code} ${it.message}" }
        assert(row.code == 2554 && row.line == 2 && row.character == 1 && row.length == 3)
        assert(row.message == "Expected 2 arguments, but got 1.")
        assert(related == listOf("6210 An argument for 'b' was not provided."))
    }

    @Test
    fun `a rest parameter makes a too-few call TS2555`() {
        val r = rows(
            """
            function rest(a: number, ...more: string[]) {}
            rest();
            """
        )
        assert(r == listOf("2:1 TS2555 Expected at least 1 arguments, but got 0."))
    }

    @Test
    fun `a surviving spread past a fixed parameter list is TS2556 alone`() {
        val r = rows(
            """
            function one(a: number) {}
            declare const xs: number[];
            one(1, 2, ...xs);
            """
        )
        assert(r == listOf("3:11 TS2556 A spread argument must either have a tuple type or be passed to a rest parameter."))
    }

    @Test
    fun `a too-many call squiggles the excess arguments with a range message`() {
        val d = diagnose(
            """
            function opt(a: number, b?: number) {}
            opt(1, 2, 3, 4);
            """
        )
        val row = d.single()
        assert(row.code == 2554 && row.line == 2 && row.character == 11 && row.length == 4)
        assert(row.message == "Expected 1-2 arguments, but got 4.")
    }

    @Test
    fun `a trailing void parameter is trimmed from the minimum`() {
        val r = rows(
            """
            function g(s: string, v: void) {}
            g("a");
            g();
            """
        )
        assert(r == listOf("3:1 TS2554 Expected 1-2 arguments, but got 0."))
    }

    @Test
    fun `a fixed tuple rest counts its required and optional elements on a too-few call`() {
        val r = rows(
            """
            function tup(...a: [string, number?]) {}
            tup();
            """
        )
        assert(r == listOf("2:1 TS2554 Expected 1-2 arguments, but got 0."))
    }

    @Test
    fun `a function-typed member reads its arity from the function type`() {
        val r = rows(
            """
            declare const host: { m: (a: number, b: number) => void };
            host.m(1);
            """
        )
        assert(r == listOf("2:6 TS2554 Expected 2 arguments, but got 1."))
    }

    @Test
    fun `a count between two overloads is TS2575`() {
        val r = rows(
            """
            function ov(a: number): void;
            function ov(a: number, b: number, c: number): void;
            function ov(a: number, b?: number, c?: number) {}
            ov(1, 2);
            """
        )
        assert(r == listOf("4:1 TS2575 No overload expects 2 arguments, but overloads do exist that expect either 1 or 3 arguments."))
    }

    @Test
    fun `negative control - calls every signature accepts report nothing`() {
        val r = rows(
            """
            function opt(a: number, b?: number) {}
            function rest(a: number, ...more: string[]) {}
            function g(s: string, v: void) {}
            declare const tupArgs: [number, number];
            opt(1); opt(1, 2); rest(1); rest(1, "a", "b"); g("a"); opt(...tupArgs);
            """
        )
        assert(r.isEmpty())
    }
}
