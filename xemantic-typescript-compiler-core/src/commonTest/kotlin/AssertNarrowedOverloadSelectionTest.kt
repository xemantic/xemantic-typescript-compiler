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

/**
 * Round 743: overload selection could not see an ASSERT-function narrow.
 *
 * [Checker.getTypeOfIdentifier] answers from `currentLocalTypes` and the declaration
 * tables. An `if (isFoo(x))` narrow lands in `currentLocalTypes`, so overload
 * selection saw it; an `asserts` narrow lives ONLY in the flow graph, reached by
 * `getNarrowedTypeForReference`, which every emission site opts into individually and
 * `resolveCallOverload` never did. So `Debug.assert(isKeywordOrPunctuation(kind))`
 * followed by `tokenToString(kind)` selected tsc's
 * `tokenToString(t: SyntaxKind): string | undefined` overload instead of the
 * `PunctuationOrKeywordSyntaxKind` one that returns `string` (parser.ts:2494).
 *
 * The reduction is deliberately ENUM-FREE. The profile diagnostic it comes from is
 * only VISIBLE with (REL.1)(b) applied — without member disjointness the wrong
 * overload's parameter accepted the argument anyway — but the gap itself has nothing
 * to do with enums, and stating it without one is what makes these pins bite on main.
 */
class AssertNarrowedOverloadSelectionTest {

    private val prelude = """
        declare function isStr(x: unknown): x is string
        declare namespace Debug { function assert(value: unknown): asserts value }
        declare function conv(t: string): string
        declare function conv(t: unknown): string | undefined
        declare function report(msg: string, ...args: (string | number)[]): void

    """.trimIndent()

    @Test
    fun `an asserts narrow selects the overload whose parameter it satisfies`() {
        diagnose(prelude + """
            export function g(kind: unknown) {
                Debug.assert(isStr(kind))
                report("x", conv(kind))
            }
        """.trimIndent()) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `the selected overload's return type is the narrowed one`() {
        // Stated as a MESSAGE, because the shape of the bug is a wrong TYPE and not a
        // wrong verdict: before round 743 this said `'string | undefined'`, which is
        // the un-narrowed overload's return. An assertion that merely counted
        // diagnostics would have passed on the broken build.
        val messages = diagnose(prelude + """
            export function g(kind: unknown) {
                Debug.assert(isStr(kind))
                const t: number = conv(kind)
                return t
            }
        """.trimIndent()).filter { it.code == 2322 }.map { it.message }
        assert(messages == listOf("Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `negative control - an if narrow already selected correctly`() {
        // The sibling narrowing form, which was never broken — it is recorded into
        // `currentLocalTypes` by the condition pass, so overload selection saw it all
        // along. Here to pin that the second-chance flow walk did not disturb it.
        val messages = diagnose(prelude + """
            declare function need(n: number): void
            export function g(kind: unknown) {
                if (isStr(kind)) { need(conv(kind)) }
            }
        """.trimIndent()).filter { it.code == 2345 }.map { it.message }
        assert(messages == listOf("Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `negative control - without the assertion the wider overload still wins`() {
        // The second chance may only turn a rejection into an acceptance. With nothing
        // narrowing `kind`, the `unknown` overload is the correct pick and its
        // `string | undefined` return must still be rejected.
        diagnose(prelude + """
            export function g(kind: unknown) {
                report("x", conv(kind))
            }
        """.trimIndent()) should {
            have(any { it.code == 2345 })
        }
    }
}
