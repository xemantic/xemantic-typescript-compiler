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
 * Round 744: an intersection SOURCE that carries a union constituent must
 * DISTRIBUTE — `A & (B | C)` denotes `(A & B) | (A & C)` and relates to a target
 * exactly when both distributed members do.
 *
 * We store the intersection un-distributed, and every relation rule then asks the
 * WHOLE intersection against one union member at a time: `A & (B | C)` against `B`
 * fails, because neither `A` nor `B | C` relates to `B` on its own. So an
 * intersection-over-union source related to nothing.
 *
 * It stayed invisible for as long as it did because the two sides are usually the
 * SAME interned instance, which the `source === target` fast path answers before any
 * rule runs. **It shows the moment the union constituents are written in a DIFFERENT
 * ORDER on the two sides** — which is exactly what tsc's `getNameOrArgument` does
 * (utilities.ts:4175): an `ElementAccessExpression & { argumentExpression:
 * StringLiteralLike | NumericLiteral }` member returned into
 * `MemberName | (Expression & (NumericLiteral | StringLiteralLike))`.
 *
 * That is why the order is load-bearing in the pins below and must not be "tidied":
 * a same-order test passes vacuously and measures nothing. Interning a union by
 * SORTED constituent ids would also hide the defect, and must not be done — union
 * display order is pinned to pristine tsc's source order.
 */
class IntersectionOverUnionRelationTest {

    private val prelude = """
        interface Node { readonly p: number }
        interface Expression extends Node { _e: unknown }
        interface SL extends Expression { text: string }
        interface NS extends Expression { num: number }
        interface Other extends Expression { other: boolean }

    """.trimIndent()

    @Test
    fun `an intersection over a union relates to the same intersection written in another order`() {
        diagnose(
            prelude + """
            declare const src: Expression & (SL | NS)
            export const t: Expression & (NS | SL) = src
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `an intersection over a union relates through a union target constituent`() {
        diagnose(
            prelude + """
            declare const src: Expression & (SL | NS)
            export const t: Other | (Expression & (NS | SL)) = src
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `an intersection over a union relates to a plain supertype`() {
        diagnose(
            prelude + """
            declare const src: Expression & (SL | NS)
            export const t: Expression = src
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * Negative control — distribution must not become an accept-everything: one
     * distributed member relates to the target and the other does not, so the whole
     * source does not.
     */
    @Test
    fun `negative control - a union member with no target constituent still errors`() {
        diagnose(
            prelude + """
            declare const src: Expression & (SL | NS)
            export const t: Expression & (Other | SL) = src
            """,
        ) should { have(any { it.code == 2322 }) }
    }

    /** Negative control — an unrelated target is still rejected outright. */
    @Test
    fun `negative control - an unrelated target still errors`() {
        diagnose(
            prelude + """
            declare const src: Expression & (SL | NS)
            export const t: Other = src
            """,
        ) should { have(any { it.code == 2322 }) }
    }
}
