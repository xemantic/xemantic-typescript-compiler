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
 * (CHK.108) an array literal with the wrong element COUNT against a TUPLE target.
 *
 * `const t: [number, number] = [1]` was SILENT here and is reported by pristine
 * `typescript@6.0.3` and by tsgo 7.0.2 alike, because both type a literal under a tuple
 * contextual type as a TUPLE of its elements (tsc `checkArrayLiteral`'s `inTupleContext`)
 * while this checker typed every array literal as `Array<union of elements>` — a source in
 * which the element count is simply not expressible.
 *
 * Every expectation below is TRANSCRIBED from pristine 6.0.3, which agreed with tsgo 7.0.2
 * on all of them; the message TEXT and the arity sub-line are pinned, not merely the
 * presence of a row, because the pre-(CHK.108) compiler ALSO produced a row for a genuine
 * tuple source — the wrong one (`Property '1' is missing in type '[number]'`, TS2741) — so
 * a pin asserting only that something fired cannot separate a fix from the defect.
 */
class ArrayLiteralTupleArityTest {

    private fun rows(src: String) = diagnose(src)

    // ---------------------------------------------------------------- too few

    @Test
    fun `too few elements at a variable declaration`() {
        rows("const t: [number, number] = [1];") should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[number]' is not assignable to type '[number, number]'." &&
                    it.messageChain == listOf("  Source has 1 element(s) but target requires 2.")
            })
        }
    }

    @Test
    fun `too many elements at a variable declaration`() {
        rows("const t: [number, number] = [1, 2, 3];") should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[number, number, number]' is not assignable to type '[number, number]'." &&
                    it.messageChain == listOf("  Source has 3 element(s) but target allows only 2.")
            })
        }
    }

    @Test
    fun `an empty literal against a required tuple`() {
        rows("const t: [number, number] = [];") should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[]' is not assignable to type '[number, number]'." &&
                    it.messageChain == listOf("  Source has 0 element(s) but target requires 2.")
            })
        }
    }

    // ------------------------------------------------------- the four positions

    @Test
    fun `too few elements in argument position is TS2345`() {
        rows(
            """
            declare function take(t: [number, number]): void;
            take([1]);
            """,
        ) should {
            have(any {
                it.code == 2345 &&
                    it.message ==
                    "Argument of type '[number]' is not assignable to parameter of type '[number, number]'." &&
                    it.messageChain == listOf("  Source has 1 element(s) but target requires 2.")
            })
        }
    }

    @Test
    fun `too few elements in return position`() {
        rows("function ret(): [number, number] { return [1]; }") should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[number]' is not assignable to type '[number, number]'." &&
                    it.messageChain == listOf("  Source has 1 element(s) but target requires 2.")
            })
        }
    }

    @Test
    fun `too few elements in assignment position`() {
        rows(
            """
            let o: [number, number] = [1, 2];
            o = [1];
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[number]' is not assignable to type '[number, number]'." &&
                    it.messageChain == listOf("  Source has 1 element(s) but target requires 2.")
            })
        }
    }

    @Test
    fun `too few elements against a union target names the tuple constituent`() {
        rows("const u: [number, number] | string = [1];") should {
            have(any {
                it.code == 2322 &&
                    it.messageChain == listOf(
                        "  Type '[number]' is not assignable to type '[number, number]'.",
                        "    Source has 1 element(s) but target requires 2.",
                    )
            })
        }
    }

    // ------------------------------------------------------------ target shapes

    @Test
    fun `too many elements against an optional-slot tuple`() {
        rows("const t: [number, number?] = [1, 2, 3];") should {
            have(any {
                it.code == 2322 &&
                    it.messageChain == listOf("  Source has 3 element(s) but target allows only 2.")
            })
        }
    }

    @Test
    fun `too few elements against a readonly tuple`() {
        rows("const t: readonly [number, number] = [1];") should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[number]' is not assignable to type 'readonly [number, number]'." &&
                    it.messageChain == listOf("  Source has 1 element(s) but target requires 2.")
            })
        }
    }

    @Test
    fun `a nested literal reports at the inner literal with its own arity line`() {
        val ds = rows("const t: [[number, number], number] = [[1], 2];")
        ds should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[number]' is not assignable to type '[number, number]'." &&
                    it.messageChain == listOf("  Source has 1 element(s) but target requires 2.")
            })
        }
        // ONE row, not an outer whole-literal one beside it - the two shapes are
        // exclusive in tsc and the outer arities MATCH here anyway.
        val arityRows = ds.count { it.code == 2322 }
        assert(arityRows == 1)
    }

    // ------------------------------------------- a genuine tuple source (stage 1)

    /**
     * (CHK.108) the arity ladder's THIRD rung, which no array-LITERAL shape can reach: the
     * builder in [contextualTupleOfArrayLiteral] only ever produces all-fixed slots, so a
     * literal's `minLength` equals its arity and rung 2 catches every "too many" before
     * rung 3 sees it. It is reachable from a declared tuple source, which is the half of
     * this fix that repairs the ELABORATION rather than the source type — before it these
     * two read `Type at position 1 …` and `Types of property 'length' are incompatible`,
     * neither of which either reference prints.
     *
     * The type DISPLAYS diverged in FORM when this was written (`[number, string[]]` for
     * `[number, ...string[]]`); (CHK.111) closed the rest half of that and pins it in
     * [RestTupleModelTest]. The dropped optional markers are still a pre-existing gap and
     * are deliberately not asserted here.
     */
    @Test
    fun `a rest-tuple source against a fixed tuple names the target's requirement`() {
        rows(
            """
            declare const restT: [number, ...string[]];
            export const c: [number, number] = restT;
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.messageChain == listOf("  Target requires 2 element(s) but source may have fewer.")
            })
        }
    }

    @Test
    fun `an optional-bearing tuple source longer than its target names the target's limit`() {
        rows(
            """
            declare const optT: [number, string?, string?];
            export const c: [number, string?] = optT;
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.messageChain == listOf("  Target allows only 2 element(s) but source may have more.")
            })
        }
    }

    /**
     * (CHK.108): a declared tuple source too SHORT for its target was `TS2741 Property '1'
     * is missing in type '[number]'` here — a missing-MEMBER shape neither reference
     * prints for a tuple. This is the pin for the [collectMissingProperties] /
     * [getMissingRequiredPropertySymbol] refusals.
     */
    @Test
    fun `a short tuple source is TS2322 with the arity line and not a missing member`() {
        val ds = rows(
            """
            declare const t1: [number];
            export const c: [number, number] = t1;
            """,
        )
        ds should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[number]' is not assignable to type '[number, number]'." &&
                    it.messageChain == listOf("  Source has 1 element(s) but target requires 2.")
            })
            have(none { it.code == 2741 || it.code == 2739 || it.code == 2740 })
        }
    }

    @Test
    fun `an empty tuple source is TS2322 with the arity line and not a missing-members list`() {
        rows(
            """
            declare const t0: [];
            export const c: [number, number] = t0;
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[]' is not assignable to type '[number, number]'." &&
                    it.messageChain == listOf("  Source has 0 element(s) but target requires 2.")
            })
            have(none { it.code == 2741 || it.code == 2739 || it.code == 2740 })
        }
    }

    // ---------------------------------------------------------- negative controls

    @Test
    fun `negative control - the exact element count is silent`() {
        rows("const t: [number, number] = [1, 2];") should {
            have(none { it.code == 2322 || it.code == 2741 || it.code == 2739 })
        }
    }

    @Test
    fun `negative control - an omitted optional slot is silent`() {
        rows("const t: [number, number?] = [1];") should {
            have(none { it.code == 2322 || it.code == 2741 || it.code == 2739 })
        }
    }

    @Test
    fun `negative control - a supplied optional slot is silent`() {
        rows("const t: [number, number?] = [1, 2];") should {
            have(none { it.code == 2322 || it.code == 2741 || it.code == 2739 })
        }
    }

    @Test
    fun `negative control - a rest tuple accepts its prefix and its rest elements`() {
        rows(
            """
            const a: [number, ...string[]] = [1];
            const b: [number, ...string[]] = [1, "x", "y"];
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 2741 || it.code == 2739 })
        }
    }

    /**
     * (CHK.108) the pin for the REST exclusion in [contextualTupleConstituent] — RESOLVED by
     * (CHK.111), which removed the exclusion; this stays as the silence it always asserted.
     *
     * The (CHK.108) measurement recorded the exclusion as a TRADE: dropping it gained five
     * correct rows and introduced one false positive, `TS2741 Property '1' is missing in type
     * '[number]'` at exactly this shape. (CHK.111) re-measured against pristine 6.0.3 and the
     * false-positive half was LARGER than recorded — every one of the five positions produced
     * it for a DECLARED tuple source, not the class property alone — and its cause was the
     * rest MODEL: the rest slot was a REQUIRED member typed with the rest's ARRAY type. With
     * both halves fixed the exclusion is gone and the gained rows are ten, not five; see
     * [RestTupleModelTest].
     */
    @Test
    fun `negative control - a rest tuple class property accepts its prefix`() {
        rows("class K { p: [number, ...string[]] = [1]; }") should {
            have(none { it.code == 2741 || it.code == 2739 || it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a plain array target is silent at any length`() {
        rows(
            """
            const a: number[] = [1, 2, 3];
            const b: number[] = [];
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 2741 || it.code == 2739 })
        }
    }

    /**
     * (CHK.108): an element-TYPE mismatch at the RIGHT arity stays element-wise - tsc's
     * `elaborateArrayLiteral` reports at the element and SUPPRESSES the whole-literal row,
     * and the two shapes are exclusive. Pinned as an exact count so a whole-literal row
     * appearing beside the element one reddens.
     */
    @Test
    fun `an element type mismatch at the right arity stays element-wise`() {
        val ds = rows("const t: [number, string] = [1, 2];")
        ds should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        val rowCount = ds.count { it.code == 2322 }
        assert(rowCount == 1)
    }

    /**
     * (CHK.108): a literal whose EVERY element is a variadic spread is not tuple-like, keeps
     * the `Array<union>` type it has always had, and gets (CHK.103) stage 2's WHOLE-literal
     * row — a different arity sub-line, and the one both references print for it. The pin
     * exists because [contextualTupleOfArrayLiteral] REFUSES a spread element: without that
     * refusal the tuple it built would carry a slot typed from the `SpreadElement` node.
     */
    @Test
    fun `negative control - a spread-only literal keeps its whole-literal array row`() {
        rows(
            """
            declare const nums: number[];
            const t: [number, number] = [...nums];
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'number[]' is not assignable to type '[number, number]'." &&
                    it.messageChain == listOf("  Target requires 2 element(s) but source may have fewer.")
            })
        }
    }

    /**
     * (CHK.108): a LITERAL-carrying slot contextually types its element, so the source
     * displays `[1]` and not `[number]` — (CHK.103)'s rule, reached here through the new
     * tuple builder rather than through [checkArrayLiteralElementsAgainstTuple].
     */
    @Test
    fun `a literal-carrying slot keeps the element literal in the source display`() {
        rows("const t: [1, 2] = [1];") should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[1]' is not assignable to type '[1, 2]'." &&
                    it.messageChain == listOf("  Source has 1 element(s) but target requires 2.")
            })
        }
    }

    @Test
    fun `an element type mismatch and a surplus element report only the element row`() {
        val ds = rows(
            """
            declare function take(t: [number, number]): void;
            take([1, "x", 3]);
            """,
        )
        val outer = ds.count { it.code == 2345 }
        assert(outer == 0)
    }
}
