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
 * (CHK.164) step 1, round P18.196 — a truthiness narrow SPLITS `boolean`. tsgo's `boolean` IS the union
 * `false | true` (`checker.go:1002`), so `narrowTypeByTruthiness` (`flow.go`, `getAdjustedTypeWithFacts`
 * with `TypeFacts.Truthy` / `Falsy`) keeps `true` in the truthy branch and `false` in the falsy one, for a
 * bare `boolean` and for a `boolean` union member alike. This checker's `boolean` is one intrinsic, so
 * `Checker.narrowByTruthiness` kept it whole: `if (x) return; const r: string | false = x` was a FALSE
 * POSITIVE on legal code, and every narrowed display read `boolean` where tsgo reads the literal.
 * `Checker.splitBooleanForTruthiness` supplies the split (the shape of `booleanMinusLiteral`, the equality
 * arm), and `Checker.rejoinBooleanHalves` re-assembles `boolean` at a flow JOIN whose arms hold both halves —
 * without it `if (x) {}` left `x` as a `false | true` union that prints `boolean` but is elaborated
 * constituent by constituent, growing a chain line tsgo's primitive boolean never has.
 *
 * Graded at the DECLARATION reader (`const d: never = x`): a bare `boolean` at a `never` ARGUMENT probe
 * reads un-narrowed even when flow is right. EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over
 * `build/scratch-p18196/pins` on the identical text (`strict`, `target: es2020`). A row is
 * `line:column code message` with its chain appended as ` / <line>`. Out of scope: the legacy
 * `extractNullNarrowing` truthiness arm feeding the assignment / non-union return readers ((CHK.164)
 * step 2) and the `||` / `&&` result types.
 */
class BooleanTruthinessNarrowingTest {

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
    fun `a bare boolean becomes true in the truthy branch and false in the falsy one`() {
        val actual = rows(
            """
            export function a(x: boolean) { if (x) { const d: never = x; } }
            export function b(x: boolean) { if (!x) { const d: never = x; } }
            export function c(x: boolean) { if (x) return; const d: never = x; }
            export function d(x: boolean) { if (!x) return; const d: never = x; }
            export function e(x: boolean) { if (x) {} else { const d: never = x; } }
            """
        )
        val expected = listOf(
            "1:48 2322 Type 'true' is not assignable to type 'never'.",
            "2:49 2322 Type 'false' is not assignable to type 'never'.",
            "3:54 2322 Type 'false' is not assignable to type 'never'.",
            "4:55 2322 Type 'true' is not assignable to type 'never'.",
            "5:56 2322 Type 'false' is not assignable to type 'never'.",
        ).sorted()
        assert(actual == expected)
    }

    @Test
    fun `a boolean union member keeps the half the branch allows`() {
        val actual = rows(
            """
            export function a(x: boolean | string) { if (x) { const d: never = x; } }
            export function b(x: boolean | string) { if (!x) { const d: never = x; } }
            export function c(x: boolean | string) { if (x) return; const d: never = x; }
            export function d(x: boolean | undefined) { if (x) { const d: never = x; } }
            export function e(x: boolean | undefined) { if (!x) { const d: never = x; } }
            export function f(x: boolean | undefined) { if (x) return; const d: never = x; }
            export function g(o: { on: boolean | number }) { if (o.on) { const d: never = o.on; } }
            """
        )
        val expected = listOf(
            "1:57 2322 Type 'string | true' is not assignable to type 'never'. / Type 'string' is not assignable to type 'never'.",
            "2:58 2322 Type 'string | false' is not assignable to type 'never'. / Type 'string' is not assignable to type 'never'.",
            "3:63 2322 Type 'string | false' is not assignable to type 'never'. / Type 'string' is not assignable to type 'never'.",
            "4:60 2322 Type 'true' is not assignable to type 'never'.",
            "5:61 2322 Type 'false | undefined' is not assignable to type 'never'. / Type 'undefined' is not assignable to type 'never'.",
            "6:66 2322 Type 'false | undefined' is not assignable to type 'never'. / Type 'undefined' is not assignable to type 'never'.",
            "7:68 2322 Type 'number | true' is not assignable to type 'never'. / Type 'number' is not assignable to type 'never'.",
        ).sorted()
        assert(actual == expected)
    }

    @Test
    fun `legal code narrowed by truthiness reports nothing`() {
        val actual = rows(
            """
            export function a(x: boolean | string) { if (x) return; const r: string | false = x; }
            export function b(x: boolean | string) { if (!x) return; const r: string | true = x; }
            export function c(x: boolean | undefined) { if (x) return; const r: false | undefined = x; }
            export function d(x: boolean) { if (x) { const t: true = x; } else { const f: false = x; } }
            export function e(x: boolean | string) { if (x) { const r: string | true = x; } }
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `controls - an unsplit join, literal-only unions, an unnarrowed read and a compound assignment`() {
        val actual = rows(
            """
            export function a(x: boolean) { if (x) {} const d: never = x; }
            export function b(x: true | string) { if (!x) { const d: never = x; } }
            export function c(x: false | number) { if (x) { const d: never = x; } }
            export function d(x: boolean) { const d1: never = x; }
            export function e(x: boolean | string) { if (x) { const d: number = x; } }
            export function f(x: boolean, y: boolean) { let z = x; z ||= y; const d: never = z; }
            """
        )
        val expected = listOf(
            "1:49 2322 Type 'boolean' is not assignable to type 'never'.",
            "2:55 2322 Type 'string' is not assignable to type 'never'.",
            "3:55 2322 Type 'number' is not assignable to type 'never'.",
            "4:39 2322 Type 'boolean' is not assignable to type 'never'.",
            "5:57 2322 Type 'string | true' is not assignable to type 'number'. / Type 'string' is not assignable to type 'number'.",
            "6:71 2322 Type 'boolean' is not assignable to type 'never'.",
        ).sorted()
        assert(actual == expected)
    }

    @Test
    fun `a flow join of both halves is boolean again`() {
        val actual = rows(
            """
            export function a(x: boolean) { if (x === true) {} const d: never = x; }
            export function b(x: boolean | string) { if (x) {} const d: never = x; }
            export function c(x: boolean) { if (x) { return } else {} const d: never = x; }
            export function e(x: boolean | undefined) { if (!x) {} const d: never = x; }
            """
        )
        val expected = listOf(
            "1:58 2322 Type 'boolean' is not assignable to type 'never'.",
            "2:58 2322 Type 'string | boolean' is not assignable to type 'never'. / Type 'string' is not assignable to type 'never'.",
            "3:65 2322 Type 'false' is not assignable to type 'never'.",
            "4:62 2322 Type 'boolean | undefined' is not assignable to type 'never'. / Type 'undefined' is not assignable to type 'never'.",
        ).sorted()
        assert(actual == expected)
    }
}
