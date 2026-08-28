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
 * (CHK.63)(c) A `!` IS NOT RESPECTED THROUGH PARENTHESES, NOR OVER A LOGICAL OPERATOR —
 * A SHIPPED FALSE POSITIVE, AND `(x)!` IS THE SAME EXPRESSION AS `x!`.
 *
 * The nullish-stripping arm of [Checker.getTypeOfExpression]'s `NonNullExpression` case
 * admits its operand by KIND — CallExpression / Identifier / PropertyAccess, each added
 * separately by rounds 439/456/479 — and a ParenthesizedExpression is none of them, so
 * `return (t)!` against `string | number` reported `Type 'string | undefined' …` while
 * `return t!` one line away was silent. [Checker.nonNullOperandStrips] reads the operand
 * THROUGH parentheses and adds the logical operators, whose value is one of their own
 * operands.
 *
 * WHICH READER: every pin below is the **RETURN** reader against a **UNION** target,
 * deliberately. Against a PRIMITIVE target the whole family is invisible — that is
 * (CHK.63)'s own `canUseTypeEngine` gate, which refuses a nullish union there — and the
 * union target is what makes the same defect observable on the shipped binary. The row
 * that motivated it is tsc's `server/project.ts:746`
 * (`return (info && info.getLatestVersion())!`), which is at a primitive target and
 * therefore appears only once that gate opens.
 *
 * Measured on the parent binary with these fixtures in place: the four positives report
 * TS2322 and tsc 7.0.2 reports nothing for them.
 */
class NonNullAssertionThroughParensAndLogicalTest {

    private val prelude = """
        declare function zzzGet(zzzK: string): string | undefined;
        interface ZzzBox { zzzF: string }
        declare const zzzB: ZzzBox | undefined;
        declare const zzzInfo: { zzzV(): string | undefined } | undefined;
    """.trimIndent() + "\n"

    /** POSITIVE — a parenthesized IDENTIFIER operand. The whole defect in one character pair. */
    @Test
    fun `a non-null assertion strips through parentheses`() {
        diagnose(
            prelude +
                "function zzzU2(zzzK: string): string | number {\n" +
                "  const zzzT = zzzGet(zzzK);\n" +
                "  return (zzzT)!;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /** POSITIVE — the `server/project.ts:746` shape: a `&&` under the parentheses. */
    @Test
    fun `a non-null assertion strips over a logical and`() {
        diagnose(
            prelude +
                "function zzzU1(): string | number {\n" +
                "  return (zzzInfo && zzzInfo.zzzV())!;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /** POSITIVE — the nullish-coalescing sibling. */
    @Test
    fun `a non-null assertion strips over a nullish coalescing operator`() {
        diagnose(
            prelude +
                "function zzzU3(zzzK: string): string | number {\n" +
                "  return (zzzGet(zzzK) ?? zzzGet(\"x\"))!;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /** POSITIVE — an OBJECT-ish operand, so the arm is not string-specific. */
    @Test
    fun `a non-null assertion strips over a logical and with an object operand`() {
        diagnose(
            prelude +
                "function zzzU6(): ZzzBox | number {\n" +
                "  return (zzzB && zzzB)!;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /** CONTROL — a bare CALL operand already stripped before this change (round 439). */
    @Test
    fun `negative control - a bare call operand already stripped`() {
        diagnose(
            prelude +
                "function zzzU4(zzzK: string): string | number {\n" +
                "  return zzzGet(zzzK)!;\n" +
                "}",
        ) should { have(none { it.code == 2322 }) }
    }

    /** CONTROL — with no `!` the nullish union is still a true positive on both compilers. */
    @Test
    fun `negative control - without the assertion the nullish union still reports`() {
        diagnose(
            prelude +
                "function zzzU5(zzzK: string): string | number {\n" +
                "  return zzzGet(zzzK);\n" +
                "}",
        ) should { have(any { it.code == 2322 }) }
    }

    /**
     * RESIDUE, pinned with the value WE answer rather than tsc's: a COMMA operand is
     * deliberately not admitted — a comma's value is its LAST operand, which is a
     * different rule from the logical operators' "one of my operands", and this parser
     * models the two with different nodes. tsc strips here and we still report.
     */
    @Test
    fun `residue - a comma operand does not strip and tsc disagrees`() {
        diagnose(
            prelude +
                "function zzzV1(): string | number {\n" +
                "  return (zzzGet(\"a\"), zzzGet(\"b\"))!;\n" +
                "}",
        ) should { have(any { it.code == 2322 }) }
    }
}
