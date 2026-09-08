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
 * (CHK.111) the REST tuple model: `[number, ...string[]]`.
 *
 * A rest slot is stored in `tupleElementTypes` as the rest's ARRAY type (the B526 collapse
 * `getTupleType` performs), and before this round the numbered MEMBER built from that slot
 * was both typed with the array type and REQUIRED. Both halves were wrong in tsc's model —
 * `createTupleTargetType` stops synthesizing numbered properties at the first variadic
 * element, so `[number, ...string[]]` has exactly the members `0` and `length` — and each
 * produced its own family of ours-only rows:
 *
 *  - REQUIRED: `[number]` against `[number, ...string[]]` was `TS2741 Property '1' is
 *    missing`, at ALL FIVE assignability positions for a declared tuple source. (The
 *    (CHK.108) note recorded this as a single class-property false positive; measured
 *    against pristine `typescript@6.0.3` and tsgo 7.0.2, every position was affected.)
 *  - ARRAY-typed: `[number, string]` against the same target compared `string` against
 *    `string[]` and failed, so making the slot merely OPTIONAL would have closed none of
 *    that half. This is why the seam is a slot TYPE plus a non-required MARK, and not the
 *    optionality flag the queue item proposed.
 *
 * The mirror of the required rule lives on the SOURCE side: a slot at or after the source's
 * own rest slot only MAY be present, so it cannot satisfy a REQUIRED target slot.
 *
 * With the model fixed, (CHK.108)'s rest exclusion in `contextualTupleConstituent` is gone
 * and an array LITERAL is force-tupled against a rest target as well, which is where the
 * ten rows this checker used to be entirely silent about come from.
 *
 * Every expectation is TRANSCRIBED from pristine 6.0.3, which agreed with tsgo 7.0.2 on
 * every row; the message TEXT and the sub-line chain are pinned rather than the presence of
 * a row, because the pre-(CHK.111) compiler also produced rows here — the wrong ones.
 */
class RestTupleModelTest {

    private fun rows(src: String) = diagnose(src)

    private val restTupleQuiet = """
        declare const t0: [];
        declare const t1: [number];
        declare const t2: [number, string];
        declare const t3: [number, string, string];
    """.trimIndent()

    // ------------------------------------------------ a shorter PREFIX is accepted

    @Test
    fun `a shorter declared tuple satisfies a rest tuple at a variable declaration`() {
        rows("$restTupleQuiet\nexport const c: [number, ...string[]] = t1;") should {
            have(none { it.code == 2741 || it.code == 2739 || it.code == 2322 })
        }
    }

    @Test
    fun `a shorter declared tuple satisfies a rest tuple at an argument`() {
        rows(
            """
            $restTupleQuiet
            declare function f(x: [number, ...string[]]): void;
            f(t1);
            """,
        ) should {
            have(none { it.code == 2345 || it.code == 2741 })
        }
    }

    @Test
    fun `a shorter declared tuple satisfies a rest tuple at a return`() {
        rows(
            """
            $restTupleQuiet
            export function r(): [number, ...string[]] { return t1; }
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 2741 })
        }
    }

    @Test
    fun `a shorter declared tuple satisfies a rest tuple at an assignment`() {
        rows(
            """
            $restTupleQuiet
            declare let a: [number, ...string[]];
            a = t1;
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 2741 })
        }
    }

    @Test
    fun `a shorter declared tuple satisfies a rest tuple at a class property`() {
        rows("$restTupleQuiet\nexport class K { p: [number, ...string[]] = t1; }") should {
            have(none { it.code == 2322 || it.code == 2741 })
        }
    }

    /**
     * The half a mere OPTIONALITY flag on the rest slot cannot reach: the source DOES supply
     * slot 1, and it must be compared against the rest's ELEMENT type. With the slot typed as
     * the stored `string[]` this was an ours-only row at every position.
     */
    @Test
    fun `a same-length declared tuple satisfies a rest tuple through the rest element type`() {
        rows("$restTupleQuiet\nexport const c: [number, ...string[]] = t2;") should {
            have(none { it.code == 2322 || it.code == 2741 })
        }
    }

    @Test
    fun `a longer declared tuple satisfies a rest tuple`() {
        rows("$restTupleQuiet\nexport const c: [number, ...string[]] = t3;") should {
            have(none { it.code == 2322 || it.code == 2741 })
        }
    }

    // ------------------------------------------------ what must still REPORT

    /**
     * The arity ladder still owns a source too short for the target's MINIMUM — and the row
     * is the coarse TS2322 with the arity sub-line, never a missing NUMBERED member.
     */
    @Test
    fun `an empty declared tuple against a rest tuple reports the arity`() {
        rows("$restTupleQuiet\nexport const c: [number, ...string[]] = t0;") should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[]' is not assignable to type '[number, ...string[]]'." &&
                    it.messageChain == listOf("  Source has 0 element(s) but target requires 1.")
            })
            have(none { it.code == 2741 || it.code == 2739 })
        }
    }

    /**
     * (CHK.108) closed this shape at four positions and left the CLASS PROPERTY behind: that
     * emitter reads `propertiesRelatedTo`'s own `lastMissingPropertyName` side channel rather
     * than [collectMissingProperties], so it kept printing the missing-member shape. It is the
     * same refusal, applied one emitter further on — and it is NOT rest-specific, so the
     * non-rest control below is what says the emitter and not the model was at fault.
     */
    @Test
    fun `an empty tuple against a rest tuple class property reports the arity`() {
        rows("$restTupleQuiet\nexport class K { p: [number, ...string[]] = t0; }") should {
            have(any {
                it.code == 2322 &&
                    it.messageChain == listOf("  Source has 0 element(s) but target requires 1.")
            })
            have(none { it.code == 2741 || it.code == 2739 })
        }
    }

    @Test
    fun `an empty tuple against a fixed tuple class property reports the arity`() {
        rows("$restTupleQuiet\nexport class K { p: [number, number] = t0; }") should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[]' is not assignable to type '[number, number]'." &&
                    it.messageChain == listOf("  Source has 0 element(s) but target requires 2.")
            })
            have(none { it.code == 2741 || it.code == 2739 })
        }
    }

    /**
     * An element that IS supplied is still compared, and against the rest's ELEMENT type —
     * `string`, never the stored `string[]`. Pinning the inner sub-line is what separates the
     * fixed model from the old one, which reported the same row naming `string[]`.
     */
    @Test
    fun `a wrong element type against a rest slot reports at that position`() {
        rows("$restTupleQuiet\nexport const c: [number, ...string[]] = [1, 2];") should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[number, number]' is not assignable to type '[number, ...string[]]'." &&
                    it.messageChain == listOf(
                        "  Type at position 1 in source is not compatible with type at position 1 in target.",
                        "    Type 'number' is not assignable to type 'string'.",
                    )
            })
        }
    }

    /**
     * tsc's `generateLimitedTupleElements` skips every index a tuple target has no property
     * for — which is every index at or after the rest slot — so the WHOLE-LITERAL row owns
     * this failure and there is no bare row at the element. Pinned as an exact count.
     */
    @Test
    fun `a rest-position element mismatch is one whole-literal row and not an element row`() {
        val d = rows("$restTupleQuiet\nexport const c: [number, ...string[]] = [1, 2];")
        val n = d.count { it.code == 2322 || it.code == 2345 }
        assert(n == 1)
    }

    @Test
    fun `a rest-position element mismatch at an argument is one whole-argument row`() {
        val d = rows(
            """
            declare function f(x: [number, ...string[]]): void;
            f([1, 2]);
            """,
        )
        val n = d.count { it.code == 2322 || it.code == 2345 }
        assert(n == 1)
        d should {
            have(any {
                it.code == 2345 &&
                    it.message ==
                    "Argument of type '[number, number]' is not assignable to parameter of type '[number, ...string[]]'." &&
                    it.messageChain == listOf(
                        "  Type at position 1 in source is not compatible with type at position 1 in target.",
                        "    Type 'number' is not assignable to type 'string'.",
                    )
            })
        }
    }

    /**
     * The SOURCE-side mirror: a slot at or after the source's own rest slot only MAY be
     * present, so it cannot satisfy a REQUIRED target slot. Without the rule
     * `[number, ...string[]]` related to `[number, string, ...string[]]`, which both
     * references reject — and the sub-line is the FOURTH rung of the arity ladder, from tsc's
     * per-element loop below the three (CHK.108) transcribed.
     */
    @Test
    fun `a rest tuple does not satisfy a longer rest tuple`() {
        rows(
            """
            declare const a: [number, ...string[]];
            export const b: [number, string, ...string[]] = a;
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message ==
                    "Type '[number, ...string[]]' is not assignable to type '[number, string, ...string[]]'." &&
                    it.messageChain ==
                    listOf("  Source provides no match for required element at position 1 in target.")
            })
        }
    }

    /**
     * The VERDICT half of the rule above, separated from its MESSAGE half: the row must EXIST.
     * Without this the two are one observable — deleting the source-side rejection loses the row
     * and deleting the arity ladder's fourth rung leaves it with the wrong chain, and a single
     * pin asserting both cannot say which layer failed.
     */
    @Test
    fun `a rest tuple against a longer rest tuple is rejected at all`() {
        rows(
            """
            declare const a: [number, ...string[]];
            export const b: [number, string, ...string[]] = a;
            """,
        ) should {
            have(any { it.code == 2322 || it.code == 2741 || it.code == 2739 })
        }
    }

    @Test
    fun `a rest tuple does not satisfy a fixed tuple of greater minimum length`() {
        rows(
            """
            declare const a: [number, ...string[]];
            export const b: [number, string] = a;
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.messageChain == listOf("  Target requires 2 element(s) but source may have fewer.")
            })
        }
    }

    // ------------------------------------------------ array LITERAL sources

    /**
     * (CHK.108)'s rest exclusion in `contextualTupleConstituent` made an array literal against
     * a rest target keep its `Array<union>` type, in which the element count is not
     * expressible — so all ten rows a rest target should produce were silent. The exclusion
     * was a TRADE forced by the model, and with the model fixed it is gone.
     */
    @Test
    fun `an empty array literal against a rest tuple reports the arity`() {
        rows("export const c: [number, ...string[]] = [];") should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[]' is not assignable to type '[number, ...string[]]'." &&
                    it.messageChain == listOf("  Source has 0 element(s) but target requires 1.")
            })
        }
    }

    @Test
    fun `an empty array literal against a rest tuple class property reports the arity`() {
        rows("export class K { p: [number, ...string[]] = []; }") should {
            have(any {
                it.code == 2322 &&
                    it.messageChain == listOf("  Source has 0 element(s) but target requires 1.")
            })
        }
    }

    @Test
    fun `an empty array literal against a rest tuple argument reports the arity`() {
        rows(
            """
            declare function f(x: [number, ...string[]]): void;
            f([]);
            """,
        ) should {
            have(any {
                it.code == 2345 &&
                    it.message ==
                    "Argument of type '[]' is not assignable to parameter of type '[number, ...string[]]'." &&
                    it.messageChain == listOf("  Source has 0 element(s) but target requires 1.")
            })
        }
    }

    /**
     * A TRAILING rest also types the elements PAST its own slot, which is what keeps a literal
     * longer than the tuple's arity silent.
     */
    @Test
    fun `negative control - an array literal filling the rest is silent at any length`() {
        rows(
            """
            const a: [number, ...string[]] = [1];
            const b: [number, ...string[]] = [1, "x"];
            const c: [number, ...string[]] = [1, "x", "y", "z"];
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 2741 || it.code == 2739 || it.code == 2345 })
        }
    }

    /**
     * The trailing-rest EXTENSION, which a literal whose elements already carry the right type
     * cannot see: an element past the tuple's own arity has no slot to read at all, and typing
     * it by itself answers `string` where the slot answers `"a" | "b"`. The literal-carrying
     * rest slot is what makes the extension observable.
     */
    @Test
    fun `negative control - a literal-carrying rest slot types the elements past its own slot`() {
        rows("""const c: [number, ...("a" | "b")[]] = [1, "a", "b"];""") should {
            have(none { it.code == 2322 || it.code == 2345 || it.code == 2741 })
        }
    }

    /**
     * The tuple's NUMBER index signature is built over the rest-EXPANDED slots, so a read at a
     * non-literal index is `string | number` and not `number | string[]` — tsc's
     * `getIndexTypeOfType(t, numberType)`. Reachable only through a non-literal index; every
     * literal index is served by the numbered member instead.
     */
    @Test
    fun `a numeric-index read of a rest tuple answers the rest element union`() {
        rows(
            """
            declare const t: [number, ...string[]];
            declare const i: number;
            export const bad: null = t[i];
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'string | number' is not assignable to type 'null'."
            })
        }
    }

    // ------------------------------------------------ member reads and relations

    /**
     * The rest member is NOT recorded as optional — a rest slot is not optional in the ACCESS
     * sense — so `t[1]` is `string` and never `string | undefined`, and never the stored
     * `string[]`. The deliberate mis-assignment is what PRINTS the type.
     */
    @Test
    fun `a rest slot read answers the rest element type`() {
        rows(
            """
            declare const t: [number, ...string[]];
            export const bad: null = t[1];
            """,
        ) should {
            have(any {
                it.code == 2322 && it.message == "Type 'string' is not assignable to type 'null'."
            })
        }
    }

    @Test
    fun `a fixed slot read of a rest tuple answers its own type`() {
        rows(
            """
            declare const t: [number, ...string[]];
            export const bad: null = t[0];
            """,
        ) should {
            have(any {
                it.code == 2322 && it.message == "Type 'number' is not assignable to type 'null'."
            })
        }
    }

    /**
     * The tuple-source-to-Array rule compares the tuple's slots against the array's element,
     * and comparing the STORED `string[]` slot refused a relation both references accept.
     */
    @Test
    fun `negative control - a rest tuple relates to an array of its element union`() {
        rows(
            """
            declare const t: [number, ...string[]];
            export const a: (number | string)[] = t;
            export const b: readonly (number | string)[] = t;
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 2740 || it.code == 2739 })
        }
    }

    // ------------------------------------------------ DISPLAY

    /**
     * The FORM half of the same model bug: a rest tuple rendered `[number, string[]]`, a type
     * no TypeScript source can spell, wherever a diagnostic named one.
     */
    @Test
    fun `a rest tuple displays its rest slot with an ellipsis`() {
        rows(
            """
            declare const t: [number, ...string[]];
            export const bad: null = t;
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[number, ...string[]]' is not assignable to type 'null'."
            })
        }
    }

    @Test
    fun `a readonly rest tuple displays readonly and the ellipsis`() {
        rows(
            """
            declare const t: readonly [number, ...string[]];
            export const bad: null = t;
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'readonly [number, ...string[]]' is not assignable to type 'null'."
            })
        }
    }

    /**
     * A LEADING and a MIDDLE rest each carry the ellipsis at their own slot — the display reads
     * `tupleRestIndex` rather than assuming the rest is last.
     */
    @Test
    fun `a leading rest displays the ellipsis at its own slot`() {
        rows(
            """
            declare const t: [...string[], number];
            export const bad: null = t;
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[...string[], number]' is not assignable to type 'null'."
            })
        }
    }

    @Test
    fun `a middle rest displays the ellipsis at its own slot`() {
        rows(
            """
            declare const t: [number, ...string[], boolean];
            export const bad: null = t;
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[number, ...string[], boolean]' is not assignable to type 'null'."
            })
        }
    }

    // ------------------------------------------------ non-rest CONTROLS

    @Test
    fun `negative control - a fixed tuple still reports a short source`() {
        rows("export const c: [number, number] = [1];") should {
            have(any {
                it.code == 2322 &&
                    it.messageChain == listOf("  Source has 1 element(s) but target requires 2.")
            })
        }
    }

    @Test
    fun `negative control - a fixed tuple still displays without an ellipsis`() {
        rows(
            """
            declare const t: [number, string];
            export const bad: null = t;
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[number, string]' is not assignable to type 'null'."
            })
        }
    }
}
