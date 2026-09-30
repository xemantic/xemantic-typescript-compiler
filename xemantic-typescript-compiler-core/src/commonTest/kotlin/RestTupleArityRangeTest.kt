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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.181) A rest parameter annotated with a tuple LITERAL expands into call positions
 * (tsgo `getParameterCount` / `getMinArgumentCount`): its REQUIRED elements before the first
 * rest element are required arguments, a FIXED tuple is a bounded range, and a tuple with a
 * rest element of its own is `at least`. The name-based walker used to pass the element
 * count as both bounds (`Expected 2 arguments` for `...a: [string, number?]`), to report a
 * fixed tuple's too-few as `at least`, and to ignore a rest-element tuple's required head;
 * the first missing position being the rest parameter itself is TS6236, not TS6210.
 *
 * Every expectation is tsgo 7.0.2's row (cells under `build/bench/p18238-agent/m/`, one
 * line earlier here because those cells open with `export {}`); columns are 1-based.
 */
class RestTupleArityRangeTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `an optional tuple element lowers the too-many minimum`() {
        val r = rows(
            """
            function f1(...a: [string, number?]) {}
            f1("a", 1, 2);
            f1();
            """
        )
        assert(r == listOf("2:12 TS2554 Expected 1-2 arguments, but got 3.", "3:1 TS2554 Expected 1-2 arguments, but got 0."))
    }

    @Test
    fun `an all-optional tuple reads 0 as its minimum`() {
        val r = rows(
            """
            function f2(...a: [string?, number?]) {}
            f2("a", 1, 2);
            f2();
            """
        )
        assert(r == listOf("2:12 TS2554 Expected 0-2 arguments, but got 3."))
    }

    @Test
    fun `labelled readonly and generic tuples read the same range`() {
        val r = rows(
            """
            function f4(...a: [a: string, b?: number]) {}
            f4("a", 1, 2);
            function f9(...a: readonly [string, number?]) {}
            f9("a", 1, 2);
            function f7<T>(...a: [T, T?]) {}
            f7(1, 2, 3);
            """
        )
        assert(
            r == listOf(
                "2:12 TS2554 Expected 1-2 arguments, but got 3.",
                "4:12 TS2554 Expected 1-2 arguments, but got 3.",
                "6:10 TS2554 Expected 1-2 arguments, but got 3.",
            )
        )
    }

    @Test
    fun `an arrow constant and a tuple spread argument read the same range`() {
        val r = rows(
            """
            const g = (...a: [string, number?]) => {};
            g("a", 1, 2);
            function f11(...a: [string, number?]) {}
            const t: [string, number] = ["a", 1];
            f11(...t, 3);
            """
        )
        assert(r == listOf("2:11 TS2554 Expected 1-2 arguments, but got 3.", "5:11 TS2554 Expected 1-2 arguments, but got 3."))
    }

    @Test
    fun `a tuple rest after a fixed parameter is a bounded range in both directions`() {
        val d = diagnose(
            """
            function f8(x: number, ...a: [string, boolean?]) {}
            f8(1, "a", true, 4);
            f8(1);
            f8();
            """
        )
        val r = d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()
        assert(
            r == listOf(
                "2:18 TS2554 Expected 2-3 arguments, but got 4.",
                "3:1 TS2554 Expected 2-3 arguments, but got 1.",
                "4:1 TS2554 Expected 2-3 arguments, but got 0.",
            )
        )
        val noArgs = d.single { it.line == 4 }.relatedInformation.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(noArgs == listOf("1:13 TS6210 An argument for 'x' was not provided."))
    }

    @Test
    fun `an all-optional tuple after a fixed parameter is still a bounded range`() {
        val r = rows(
            """
            function f15(x: number, ...a: [string?, boolean?]) {}
            f15(1, "a", true, 4);
            f15();
            """
        )
        assert(r == listOf("2:19 TS2554 Expected 1-3 arguments, but got 4.", "3:1 TS2554 Expected 1-3 arguments, but got 0."))
    }

    @Test
    fun `a missing rest parameter is named by TS6236 over the whole parameter`() {
        val d = diagnose(
            """
            function f1(...a: [string, number?]) {}
            f1();
            """
        )
        val row = d.single()
        val related = row.relatedInformation.map { "${it.line}:${it.character}+${it.length} TS${it.code} ${it.message}" }
        assert(row.code == 2554 && row.message == "Expected 1-2 arguments, but got 0.")
        assert(related == listOf("1:13+23 TS6236 Arguments for the rest parameter 'a' were not provided."))
    }

    @Test
    fun `a tuple with a rest element requires its leading elements`() {
        val d = diagnose(
            """
            function f3(...a: [string, ...number[]]) {}
            f3("a", 1, 2, 3);
            f3();
            """
        )
        val row = d.single()
        val related = row.relatedInformation.map { "${it.line}:${it.character}+${it.length} TS${it.code} ${it.message}" }
        assert(row.line == 3 && row.character == 1 && row.code == 2555)
        assert(row.message == "Expected at least 1 arguments, but got 0.")
        assert(related == listOf("1:13+27 TS6236 Arguments for the rest parameter 'a' were not provided."))
    }

    @Test
    fun `a rest-element tuple counts only the elements before its rest`() {
        val r = rows(
            """
            function f6(...a: [string, number?, ...boolean[]]) {}
            f6("a", 1, true, false);
            f6();
            function f16(...a: [string, ...number[], boolean]) {}
            f16();
            function f13(...a: [a: string, b?: number, ...c: boolean[]]) {}
            f13();
            function f17<T extends unknown[]>(...a: [string, ...T]) {}
            f17();
            function f18(x: number, ...a: [string, ...number[]]) {}
            f18(1);
            """
        )
        assert(
            r == listOf(
                "11:1 TS2555 Expected at least 2 arguments, but got 1.",
                "3:1 TS2555 Expected at least 1 arguments, but got 0.",
                "5:1 TS2555 Expected at least 1 arguments, but got 0.",
                "7:1 TS2555 Expected at least 1 arguments, but got 0.",
                "9:1 TS2555 Expected at least 1 arguments, but got 0.",
            )
        )
    }

    @Test
    fun `the signature reader requires a rest-element tuple's head too`() {
        val r = rows(
            """
            class K20 { m(...a: [string, ...number[]]) {} }
            new K20().m();
            declare const h20: (x: number, ...a: [string, ...number[]]) => void;
            h20(1);
            h20(1, "a", 2, 3);
            """
        )
        assert(r == listOf("2:11 TS2555 Expected at least 1 arguments, but got 0.", "4:1 TS2555 Expected at least 2 arguments, but got 1."))
    }

    @Test
    fun `negative control - an optional head before a rest element requires nothing`() {
        val r = rows(
            """
            function f19(...a: [string?, ...number[]]) {}
            f19();
            f19("a", 1, 2, 3, 4);
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - an all-required tuple keeps its single count`() {
        val r = rows(
            """
            function f12(...a: [string, number]) {}
            f12("a", 1, 2);
            f12("a");
            """
        )
        assert(r == listOf("2:13 TS2554 Expected 2 arguments, but got 3.", "3:1 TS2554 Expected 2 arguments, but got 1."))
    }

    @Test
    fun `negative control - a method callee reached by the signature reader agrees`() {
        val r = rows(
            """
            class K { m(...a: [string, number?]) {} }
            new K().m("a", 1, 2);
            new K().m();
            """
        )
        assert(r == listOf("2:19 TS2554 Expected 1-2 arguments, but got 3.", "3:9 TS2554 Expected 1-2 arguments, but got 0."))
    }
}
