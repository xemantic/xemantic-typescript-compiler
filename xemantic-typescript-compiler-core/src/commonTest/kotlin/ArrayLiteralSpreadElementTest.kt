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
 * (CHK.103) stage 1: an array literal containing a `...spread` element has a real
 * type, and its elements are checked.
 *
 * ## The defect
 *
 * `getTypeOfArrayLiteral` and `constContextTupleOfArrayLiteral` both opened with
 * `if (el is SpreadElement) return anyType`, so EVERY array literal carrying a
 * spread was `any`: `f([...xs])` went unchecked at every position, and the var-decl
 * STRING layer printed `Type 'array'` where both references print `number[]`.
 *
 * ## The mechanism
 *
 * A spread contributes its ITERATED element type (tsc's `checkArrayLiteral`,
 * checker.ts:33329-33374): an array-like source contributes its element type, a
 * tuple its element UNION, `string` contributes `string`, an iterable its iteration
 * type. In a CONST context tsc builds a tuple and INLINES a variadic slot whose type
 * is a fixed tuple, so `[...tup] as const` over `[number, string]` is
 * `readonly [number, string]`.
 *
 * ## The trap this class exists for
 *
 * Giving such a literal a type at all made ROUND 471's literal-preserving arm
 * reachable in a way it had never been, and that arm **bailed on any spread**
 * (`expr.elements.any { it is SpreadElement } -> null`). Round 471 built it for
 * tsc's own `invalidOperationsInPartialSemanticMode` (services.ts:~1560, no spread);
 * its sibling `invalidOperationsInSyntacticMode` (:1607) is the SAME shape WITH a
 * spread, so the whole literal fell back to `getTypeOfArrayLiteral`, whose elements
 * widen to their base primitive — a bare `string` unioned into
 * `readonly (keyof LanguageService)[]` and a FALSE TS2322 on 3 of the 8 profiles.
 * The `keeps its literal type` pins below are that guard; the ablation that reddens
 * them is restoring the spread bail. They do NOT discriminate against the pre-
 * (CHK.103) parent, which was silent for the other reason — the literal was `any`.
 *
 * Every expectation here was read from pristine `typescript@6.0.3` and matches it
 * byte for byte. Two reference divergences are recorded rather than pinned: tsgo
 * 7.0.2 answers TS4104 where pristine answers TS2345 for a readonly tuple argument,
 * and pristine renders a const-asserted `[1, 2]` spread `(2 | 1)[]` where tsgo
 * renders `(1 | 2)[]`.
 *
 * ## Stated residue - deliberately NOT pinned
 *
 * A literal whose ONLY elements are spreads of non-tuple array-likes normalizes to a
 * plain array in tsc and is not tuple-like, so tsc reports ONE whole-literal row;
 * this checker has no whole-literal array-to-array fallback at that reader and stays
 * SILENT rather than inventing a row at the wrong node. `takeStrArr([...nums])`,
 * `[..."abc"]` / `[...set]` at an argument, `const t: [number, number] = [...nums]`
 * and an INLINE `f([...tup] as const)` are the six such rows. Per CLAUDE.md a known-
 * open gap is recorded in the session note, never pinned as a control.
 */
class ArrayLiteralSpreadElementTest {

    private val prelude = """
        interface LS { alpha(): void; beta(): void; gamma(): void; }
        declare function takeStrArr(x: string[]): void;
        declare function takeNumArr(x: number[]): void;
        const partial: readonly (keyof LS)[] = ["alpha"];
        const mpartial: (keyof LS)[] = ["alpha"];
        const nums: number[] = [1, 2];
        const ro: readonly number[] = [1];
        const tup: [number, string] = [1, "a"];
    """.trimIndent()

    // ---- the round-471 literal keep, which the spread bail used to defeat --------

    @Test
    fun `a literal element beside a spread keeps its literal type under a readonly annotation`() {
        diagnose(
            prelude + """

            const k1: readonly (keyof LS)[] = [...partial, "beta"];
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a literal element beside a spread keeps its literal type under a mutable annotation`() {
        diagnose(
            prelude + """

            const k2: (keyof LS)[] = [...mpartial, "beta"];
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - the same annotation with no spread was already silent`() {
        diagnose(
            prelude + """

            const k3: readonly (keyof LS)[] = ["beta"];
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    // ---- the contributed element type, read out of the var-decl message ----------

    @Test
    fun `a spread of an array contributes its element type and no longer prints Type array`() {
        val d = diagnose(
            prelude + """

            const w1: string = [...nums];
            """
        )
        d should {
            have(any { it.code == 2322 && it.message == "Type 'number[]' is not assignable to type 'string'." })
            have(none { it.message.contains("Type 'array'") })
        }
    }

    @Test
    fun `a spread of a readonly array contributes its element type`() {
        diagnose(
            prelude + """

            const w2: string = [...ro];
            """
        ) should {
            have(any { it.code == 2322 && it.message == "Type 'number[]' is not assignable to type 'string'." })
        }
    }

    @Test
    fun `a spread of a string contributes string`() {
        diagnose(
            prelude + """

            const w3: number = [..."abc"];
            """
        ) should {
            have(any { it.code == 2322 && it.message == "Type 'string[]' is not assignable to type 'number'." })
        }
    }

    // ---- element-wise elaboration at an argument ---------------------------------

    @Test
    fun `a tuple spread contributes its element union at an argument`() {
        diagnose(
            prelude + """

            takeStrArr([...tup]);
            """
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'string | number' is not assignable to type 'string'."
            })
        }
    }

    @Test
    fun `a spread and a literal element are each elaborated at an argument`() {
        val d = diagnose(
            prelude + """

            takeStrArr([...nums, 3]);
            """
        )
        d should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        val rows = d.count {
            it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'."
        }
        assert(rows == 2)
    }

    // ---- the const context builds a tuple ----------------------------------------

    @Test
    fun `a const-asserted tuple spread is a readonly tuple of the same slots`() {
        diagnose(
            prelude + """

            const ct = [...tup] as const;
            takeNumArr(ct);
            """
        ) should {
            have(any {
                it.code == 2345 &&
                    it.message == "Argument of type 'readonly [number, string]' is not assignable " +
                    "to parameter of type 'number[]'."
            })
        }
    }

    @Test
    fun `a const-asserted spread of a literal tuple keeps its literal slots`() {
        diagnose(
            prelude + """

            const lt = [1, 2] as const;
            const ct2 = [...lt] as const;
            takeStrArr(ct2);
            """
        ) should {
            have(any {
                it.code == 2345 &&
                    it.message == "Argument of type 'readonly [1, 2]' is not assignable " +
                    "to parameter of type 'string[]'."
            })
        }
    }

    // ---- the refusals stay refusals ----------------------------------------------

    @Test
    fun `a spread of an any-typed value refuses and reports nothing`() {
        diagnose(
            prelude + """

            declare const anyv: any;
            takeStrArr([...anyv]);
            """
        ) should {
            have(none { it.code == 2322 })
            have(none { it.code == 2345 })
        }
    }

    /**
     * The refusal is a SOUNDNESS pin, not a countdown: a UNION source distributes in
     * tsc (`number[] | string[]` contributes `string | number`) and the member ORDER
     * of a distributed union is not this checker's ((INC.27)'s interning), so
     * [arrayLiteralSpreadElementType] answers null and the literal keeps the `any` it
     * has always had — the var-decl string layer then prints its `Type 'array'`
     * fallback. What must never happen is picking ONE constituent, which would be a
     * wrong type rather than a missing one; that is what this asserts, and it stays
     * true when the gap closes to pristine's `(string | number)[]`.
     */
    @Test
    fun `a spread of a union refuses rather than guessing one constituent`() {
        diagnose(
            prelude + """

            declare const u: number[] | string[];
            const w4: string = [...u];
            """
        ) should {
            have(none { it.message.contains("Type 'number[]'") })
            have(none { it.message.contains("Type 'string[]'") })
        }
    }
}
