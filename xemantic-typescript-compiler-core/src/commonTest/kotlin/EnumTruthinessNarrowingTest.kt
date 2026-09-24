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
 * (CHK.166)(a) step 1, round P18.195 — a truthiness narrow of an ENUM is decided by its members'
 * VALUES. tsgo models a literal enum as the union of its member literal types (`getDeclaredTypeOfEnum`)
 * and `getTypeFactsWorker` classifies each by value: `0`, `NaN` and `""` are falsy, any other known
 * value truthy, an opaque member (computed, or ambient non-const with no initializer) either. This
 * checker mints one member-less `Type.Object` for the whole enum, which the truthiness predicates read
 * as an object, i.e. ALWAYS truthy: the falsy branch washed `K` to `never` (a missing row wherever the
 * reference is then misused) and the truthy branch kept `K.Zero` (a false positive on legal code such as
 * `if (k) { const d: K.One | K.Two = k }`).
 *
 * `EnumSemantics.enumTruthiness` classifies a member by value and a whole enum by all of its members;
 * `Checker.splitEnumsForTruthiness` replaces a whole enum by its members ONLY when the branch removes a
 * proper subset of them, so an enum nothing is removed from still displays as `K`. BOTH DIRECTIONS are
 * pinned: an enum whose every member is truthy (`N1`, `S1`) really is `never` in the falsy branch — the
 * naive "every enum may be falsy" shape adds false positives there — and a one-member `enum O { Only }`
 * (value 0) is `never` in the truthy branch.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18195/pins` on the identical
 * text (`strict`, `target: es2020`). A row is `line:column code message` with its chain appended as
 * ` / <line>`. Out of scope (step 2): the result types of `&&` / `||` / `&&=` / `||=` and switch-case
 * comparability.
 */
class EnumTruthinessNarrowingTest {

    private val directives = """
        // @useRealLibs: true
        // @strict: true
        // @target: es2020
    """.trimIndent()

    private fun rows(source: String): List<String> =
        diagnose(directives + "\n" + source.trimIndent(), directives = "").map { d ->
            "${d.line}:${d.character} ${d.code} ${d.message}" +
                d.messageChain.joinToString("") { " / " + it.trim() }
        }.sorted()

    @Test
    fun `the falsy branch keeps the falsy member and the truthy branch the truthy ones`() {
        val actual = rows(
            """
            enum N { Zero, One, Two }
            export function a(o: { k: N }) { if (!o.k) { const d: never = o.k; } }
            export function b(o: { k: N }) { if (o.k) { const d: never = o.k; } }
            export function c(k: N | null) { if (!k) { const d: never = k; } }
            export function d(k: N | null) { if (k) { const d: never = k; } }
            export function e(k: N) { if (!k) { const s: string = k; } }
            """
        )
        val expected = listOf(
            "2:52 2322 Type 'N.Zero' is not assignable to type 'never'.",
            "3:51 2322 Type 'N.One | N.Two' is not assignable to type 'never'. / Type 'N.One' is not assignable to type 'never'.",
            "4:50 2322 Type 'N.Zero | null' is not assignable to type 'never'. / Type 'null' is not assignable to type 'never'.",
            "5:49 2322 Type 'N.One | N.Two' is not assignable to type 'never'. / Type 'N.One' is not assignable to type 'never'.",
            "6:43 2322 Type 'N' is not assignable to type 'string'.",
        ).sorted()
        assert(actual == expected)
    }

    @Test
    fun `legal code narrowed by truthiness reports nothing`() {
        val actual = rows(
            """
            enum N { Zero, One, Two }
            export function a(k: N) { if (k) { const d: N.One | N.Two = k; } }
            export function b(k: N) { if (!k) { const d: N.Zero = k; } else { const e: N.One | N.Two = k; } }
            export function c(k: N) { if (k) return; const s: N.One = k; }
            export function d(k: N) { return k ? 1 : k; }
            """
        )
        val expected = listOf("4:48 2322 Type 'N.Zero' is not assignable to type 'N.One'.")
        assert(actual == expected)
    }

    @Test
    fun `an enum whose every member is truthy is never in the falsy branch and whole in the truthy one`() {
        val actual = rows(
            """
            enum N1 { One = 1, Two }
            enum S1 { A = "a", B = "b" }
            export function a(k: N1) { if (!k) { const d: never = k; } }
            export function b(k: S1) { if (!k) { const d: never = k; } }
            export function c(k: N1) { if (k) { const d: never = k; } }
            export function d(k: S1) { if (k) { const d: never = k; } }
            """
        )
        val expected = listOf(
            "5:43 2322 Type 'N1' is not assignable to type 'never'.",
            "6:43 2322 Type 'S1' is not assignable to type 'never'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a one-member enum with value 0 is never in the truthy branch`() {
        val actual = rows(
            """
            enum O { Only }
            export function a(k: O) { if (k) { const d: never = k; } }
            export function b(k: O) { if (!k) { const d: never = k; } }
            """
        )
        val expected = listOf("3:43 2322 Type 'O' is not assignable to type 'never'.")
        assert(actual == expected)
    }

    @Test
    fun `an ambient enum's opaque members survive both branches`() {
        val actual = rows(
            """
            declare enum D { X, Y }
            export function a(k: D) { if (!k) { const d: never = k; } }
            export function b(k: D) { if (k) { const d: never = k; } }
            export function c(k: D.X) { if (!k) { const d: never = k; } }
            """
        )
        val expected = listOf(
            "2:43 2322 Type 'D' is not assignable to type 'never'.",
            "3:42 2322 Type 'D' is not assignable to type 'never'.",
            "4:45 2322 Type 'D.X' is not assignable to type 'never'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a computed member is either and a truthy one is removed from the falsy branch`() {
        val actual = rows(
            """
            enum C { A = "x".length, B = 2 }
            export function a(k: C) { if (!k) { const d: never = k; } }
            export function b(k: C) { if (k) { const d: never = k; } }
            """
        )
        val expected = listOf(
            "2:43 2322 Type 'C.A' is not assignable to type 'never'.",
            "3:42 2322 Type 'C' is not assignable to type 'never'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a loop, a nested function and a conditional expression narrow by value`() {
        val actual = rows(
            """
            enum N { Zero, One, Two }
            export function a(k: N) { while (k) { k = N.Zero; } const d: never = k; }
            export function b(k: N) { const f = () => { if (!k) { const d: never = k; } }; }
            export function c(k: N) { const r: N.One = k ? N.One : k; }
            """
        )
        val expected = listOf(
            "2:59 2322 Type 'N.Zero' is not assignable to type 'never'.",
            "3:61 2322 Type 'N.Zero' is not assignable to type 'never'.",
            "4:33 2322 Type 'N.Zero | N.One' is not assignable to type 'N.One'. / Type 'N.Zero' is not assignable to type 'N.One'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `flags, string, const and mixed enums are classified by member value`() {
        val actual = rows(
            """
            enum F { None = 0, A = 1, B = 2 }
            enum S { Empty = "", A = "a" }
            const enum CE { Zero, One }
            enum M { Zero = 0, A = "a" }
            export function a(f: F) { if (!f) { const d: never = f; } }
            export function b(f: F) { if (f & F.A) { const d: never = f; } }
            export function c(k: S) { if (!k) { const d: never = k; } }
            export function d(k: CE) { if (k) { const d: never = k; } }
            export function e(k: M) { if (!k) { const d: never = k; } }
            export function g(k: M | undefined) { if (k) { const d: never = k; } }
            """
        )
        val expected = listOf(
            "5:43 2322 Type 'F.None' is not assignable to type 'never'.",
            "6:48 2322 Type 'F' is not assignable to type 'never'.",
            "7:43 2322 Type 'S.Empty' is not assignable to type 'never'.",
            "8:43 2322 Type 'CE.One' is not assignable to type 'never'.",
            "9:43 2322 Type 'M.Zero' is not assignable to type 'never'.",
            "10:54 2322 Type 'M.A' is not assignable to type 'never'.",
        ).sorted()
        assert(actual == expected)
    }

    @Test
    fun `two enums of different truthiness in one program are classified separately`() {
        val actual = rows(
            """
            enum N1 { One = 1, Two }
            enum N { Zero, One, Two }
            export function a(k: N1) { if (!k) { const d: never = k; } }
            export function b(k: N) { if (!k) { const d: never = k; } }
            export function c(k: N1 | N) { if (!k) { const d: never = k; } }
            """
        )
        val expected = listOf(
            "4:43 2322 Type 'N.Zero' is not assignable to type 'never'.",
            "5:48 2322 Type 'N.Zero' is not assignable to type 'never'.",
        )
        assert(actual == expected)
    }
}
