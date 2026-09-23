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
 * (CHK.156), round P18.188 — equality narrowing against a `true`/`false` literal splits
 * `boolean` into its two halves. tsgo 7.0.2's `boolean` IS the union `false | true`
 * (`checker.go:1002`), so the negative arm of `narrowTypeByEquality` (`flow.go`, `filterType`
 * dropping every unit-like member comparable to the value) removes one half of it, and a
 * switch `default` does the same for each cased literal. Ours is one intrinsic `boolean`, so
 * `on === true || on === false` never exhausted a `boolean | fn` (rxjs `share.ts:266`, an
 * ours-only TS2349), and a bare `boolean` parameter never reached the argument reader's
 * narrowing arm at all.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18188/pins` on the
 * identical text (prelude included; the fixture starts on line 4).
 *
 * NOT PINNED (pre-existing, unchanged): truthiness narrowing (`if (on) return; pn(on)`) still
 * keeps the whole `boolean` where tsgo answers `string | false`; an optional PARAMETER
 * `on?: boolean` displays as `boolean` where tsgo prints `boolean | undefined`.
 */
class BooleanLiteralEqualityNarrowingTest {

    private val prelude = """
        declare function pn(x: number): void;
        declare function pt(x: true): void;
        declare function pnv(x: never): void;

    """.trimIndent()

    /** Every row as `line:column code message`, sorted; the fixture starts on line 4. */
    private fun rows(source: String): List<String> =
        diagnose(prelude + source.trimIndent())
            .map { "${it.line}:${it.character} ${it.code} ${it.message}" }.sorted()

    @Test
    fun `both guards make a boolean or function union callable - rxjs share`() {
        val actual = rows(
            """
            export function h(on: boolean | ((x: number) => number)): number | undefined {
              if (on === true) { return; }
              if (on === false) { return; }
              return on(1);
            }
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }

    @Test
    fun `an or-chain of both boolean literals removes boolean from a function union`() {
        val actual = rows(
            """
            export function b(on: boolean | ((x: number) => number)) {
              if (on === true || on === false) { return; }
              pn(on);
            }
            """
        )
        val expected = listOf(
            "6:6 2345 Argument of type '(x: number) => number' is not assignable to parameter of type 'number'."
        )
        assert(actual == expected)
    }

    @Test
    fun `an or-chain of both boolean literals removes boolean from a string union`() {
        val actual = rows(
            """
            export function c(on: boolean | string) {
              if (on === true || on === false) { return; }
              pn(on);
            }
            """
        )
        val expected = listOf(
            "6:6 2345 Argument of type 'string' is not assignable to parameter of type 'number'."
        )
        assert(actual == expected)
    }

    @Test
    fun `an early return on true leaves the false half`() {
        val actual = rows(
            """
            export function d(on: boolean | string) {
              if (on === true) { return; }
              pn(on);
            }
            """
        )
        val expected = listOf(
            "6:6 2345 Argument of type 'string | false' is not assignable to parameter of type 'number'."
        )
        assert(actual == expected)
    }

    @Test
    fun `strict and loose inequality remove the compared half`() {
        val actual = rows(
            """
            export function f1(on: boolean | string) { if (on !== true) { pn(on); } }
            export function f2(on: boolean | string) { if (on == true) { return; } pn(on); }
            export function f3(on: boolean | string) { if (on != false) { pn(on); } }
            export function f4(on: boolean | string) { if (on === false) { return; } pn(on); }
            """
        )
        val expected = listOf(
            "4:66 2345 Argument of type 'string | false' is not assignable to parameter of type 'number'.",
            "5:75 2345 Argument of type 'string | false' is not assignable to parameter of type 'number'.",
            "6:66 2345 Argument of type 'string | true' is not assignable to parameter of type 'number'.",
            "7:77 2345 Argument of type 'string | true' is not assignable to parameter of type 'number'."
        )
        assert(actual == expected)
    }

    @Test
    fun `the positive branch keeps the compared literal and generalizes at a primitive target`() {
        val actual = rows(
            """
            export function f5(on: boolean | string) { if (on !== false) { return; } pn(on); }
            export function f6(on: boolean | string) { if (on === true) { pn(on); } }
            """
        )
        val expected = listOf(
            "4:77 2345 Argument of type 'boolean' is not assignable to parameter of type 'number'.",
            "5:66 2345 Argument of type 'boolean' is not assignable to parameter of type 'number'."
        )
        assert(actual == expected)
    }

    @Test
    fun `a switch default subtracts each cased boolean literal`() {
        val actual = rows(
            """
            export function h1(on: boolean | string) { switch (on) { case true: return; default: pn(on); } }
            export function h2(on: boolean | string) { switch (on) { case true: case false: return; default: pn(on); } }
            export function h3(on: boolean) { switch (on) { case true: return; default: pt(on); } }
            export function h4(on: boolean) { switch (on) { case true: return; case false: return; default: pnv(on); } }
            """
        )
        val expected = listOf(
            "4:89 2345 Argument of type 'string | false' is not assignable to parameter of type 'number'.",
            "5:101 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
            "6:80 2345 Argument of type 'false' is not assignable to parameter of type 'true'."
        )
        assert(actual == expected)
    }

    @Test
    fun `a bare boolean narrows to the other literal at an argument`() {
        val actual = rows(
            """
            export function a1(on: boolean) { if (on === true) { return; } pt(on); }
            export function a2(on: boolean) { if (on !== false) { pt(on); } }
            """
        )
        val expected = listOf(
            "4:67 2345 Argument of type 'false' is not assignable to parameter of type 'true'."
        )
        assert(actual == expected)
    }

    @Test
    fun `a bare boolean exhausted by both guards is never at an argument`() {
        val actual = rows(
            """
            export function g3(on: boolean) {
              if (on === true) { return; }
              if (on === false) { return; }
              pn(on);
            }
            export function g4(on: boolean) { if (on === true) { return; } if (on === false) { return; } pnv(on); }
            """
        )
        val expected = emptyList<String>()
        assert(actual == expected)
    }

    @Test
    fun `a bare boolean minus one literal displays that literal at a never target`() {
        val actual = rows(
            """
            export function n1(on: boolean) { if (on === true) { return; } const q: never = on; }
            export function n3(on: boolean) { if (on !== false) { const q: never = on; } }
            export function n6(on: boolean | undefined) { if (on !== true) { const q: never = on; } }
            """
        )
        val expected = listOf(
            "4:70 2322 Type 'false' is not assignable to type 'never'.",
            "5:61 2322 Type 'true' is not assignable to type 'never'.",
            "6:72 2322 Type 'false | undefined' is not assignable to type 'never'."
        )
        assert(actual == expected)
    }

    @Test
    fun `the partially narrowed boolean displays in stable order`() {
        val actual = rows(
            """
            export function x1(on: boolean | string | number) { if (on === true) { return; } const q: bigint = on; }
            export function x2(on: boolean | null) { if (on === false) { return; } const q: string = on; }
            export function x3(on: boolean | (() => void)) { if (on === true) { return; } const q: string = on; }
            """
        )
        val expected = listOf(
            "4:88 2322 Type 'string | number | false' is not assignable to type 'bigint'.",
            "5:78 2322 Type 'boolean | null' is not assignable to type 'string'.",
            "6:85 2322 Type 'false | (() => void)' is not assignable to type 'string'."
        )
        assert(actual == expected)
    }

    @Test
    fun `negative control - a literal union and a lone true member already narrowed`() {
        val actual = rows(
            """
            export function a(on: true | ((x: number) => number)) { if (on === true) { return; } return on(1); }
            export function e(on: "a" | "b" | ((x: number) => number)) { if (on === "a" || on === "b") { return; } return on(1); }
            export function k2(on: "x" | boolean) { if (on === "x") { return; } pn(on); }
            """
        )
        val expected = listOf(
            "6:72 2345 Argument of type 'boolean' is not assignable to parameter of type 'number'."
        )
        assert(actual == expected)
    }

    @Test
    fun `negative control - a boolean discriminant still selects its member`() {
        val actual = rows(
            """
            type R = { ok: true; value: number } | { ok: false; error: string };
            export function d1(r: R) { if (r.ok === true) { pn(r.value); } else { pn(r.error); } }
            export function d2(r: R) { if (r.ok === false) { const s: number = r.error; } else { const q: string = r.value; } }
            """
        )
        val expected = listOf(
            "5:74 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
            "6:56 2322 Type 'string' is not assignable to type 'number'.",
            "6:92 2322 Type 'number' is not assignable to type 'string'."
        )
        assert(actual == expected)
    }

    @Test
    fun `negative control - an optional boolean property path keeps undefined`() {
        val actual = rows(
            """
            interface O { flag?: boolean; n: number }
            export function p1(o: O) { if (o.flag === false) { return; } pn(o.flag); }
            export function p2(o: O) { if (o.flag === true) { return; } pn(o.flag); }
            export function u1(on: boolean | undefined) { if (on === true) { return; } pn(on); }
            """
        )
        val expected = listOf(
            "5:65 2345 Argument of type 'boolean | undefined' is not assignable to parameter of type 'number'.",
            "6:64 2345 Argument of type 'boolean | undefined' is not assignable to parameter of type 'number'.",
            "7:79 2345 Argument of type 'boolean | undefined' is not assignable to parameter of type 'number'."
        )
        assert(actual == expected)
    }
}
