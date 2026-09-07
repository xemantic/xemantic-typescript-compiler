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
 * (CHK.103) stage 2: an ARRAY-LIKE argument is decidable against an ARRAY-LIKE
 * parameter, and a NOT-TUPLE-LIKE array literal gets one whole-literal row.
 *
 * ## What the item said, and what was measured
 *
 * The item described its residue as a spread question — "a literal whose only
 * elements are spreads of non-tuple array-likes is still unchecked" — and named a
 * whole-literal array-to-array fallback at the argument reader as the seam. Measured,
 * FIVE of its six rows carry no spread at all: `takeStrArr(nums)` with
 * `nums: number[]` against `(x: string[])` was silent here and is reported by BOTH
 * tsgo 7.0.2 and pristine `typescript@6.0.3`, and so are `Bar[]` -> `Foo[]`,
 * `C2[]` -> `C1[]`, `number[][]` -> `string[][]`, `[number, string]` -> `string[]`
 * and `[string, number]` -> `[number, string]`. The gap is the ARGUMENT reader's FP
 * firewall ([caasNonSimpleParamChecks]): a non-primitive parameter needs some `allow*`
 * gate to claim it, and no gate claimed an array.
 *
 * The LICENCE is the DECLARATION position, exactly as (CHK.83)'s was: the identical
 * pairs go through `canUseTypeEngine`'s Object-vs-Object branch there and match both
 * references row for row — message AND elaboration chain — including every pair that
 * must stay SILENT. The decidability question is therefore asked one level down, of
 * the ELEMENT pair, by `canUseTypeEngine` itself, so the argument rule cannot drift
 * from the position that licenses it.
 *
 * ## The two shapes are exclusive, and that is one predicate
 *
 * tsc's `elaborateArrayLiteral` reports a literal argument ELEMENT-WISE while the
 * literal's tupleized form is a TUPLE, and reports ONE whole-literal row otherwise.
 * `arrayLiteralIsTupleLike` is that test; stage 1 used it to decide which elements to
 * elaborate and stage 2 uses it to decide whether the whole-literal row may fire, at
 * the argument reader AND at the two var-decl readers.
 *
 * ## The one ours-only row the grid found, and its cause
 *
 * Opening the gate added exactly ONE row on all eight profiles:
 * `transformers/destructuring.ts:602`, where `Debug.assertEachNode(elements,
 * isArrayBindingElement)` narrows `elements` by an `asserts nodes is readonly U[]`
 * signature and `factory.createArrayBindingPattern(elements)` then takes the narrowed
 * type. `narrowByAssertCall`'s type-parameter recovery only understood a BARE `U`
 * target, and an array OF a type parameter RESOLVES (to `ReadonlyArray<U>`), so it was
 * neither `errorType` nor `anyType` and the recovery's own gate never opened for it.
 * The fix infers U from the sibling type-guard argument exactly as the bare form does
 * and re-wraps the answer; the argument gate then consults the flow through a
 * SUPPRESSION-ONLY second chance taken on the rejecting path only.
 *
 * Every expectation here was read from pristine `typescript@6.0.3`. Recorded reference
 * divergence, not pinned: for a readonly tuple argument tsgo 7.0.2 answers TS4104
 * where pristine answers TS2345, and pristine outranks it per CLAUDE.md.
 *
 * ## Stated residue - deliberately NOT pinned
 *
 * A PLAIN array literal with the wrong element COUNT against a tuple target
 * (`const t: [number, number] = [1]` / `= [1, 2, 3]`) is silent here and reported by
 * both references, which force-tuple the literal and print `[number]` /
 * `[number, number, number]` as the source. That is a different mechanism — a
 * contextual tuple type for a literal — and pre-dates both stages. FORM: a union
 * ELEMENT renders parenthesized (`(ABC)[]` for `ABC[]`).
 */
class ArrayLikeArgumentAssignabilityTest {

    private val prelude = """
        interface Foo { a: number }
        interface Bar { a: string }
        interface Wide { a: number; b: string }
        class C1 { x: number = 1 }
        class C2 { y: number = 2 }
        declare function takeStrArr(x: string[]): void;
        declare function takeNumArr(x: number[]): void;
        declare function takeStrArrArr(x: string[][]): void;
        declare function takeFooArr(x: Foo[]): void;
        declare function takeWideArr(x: Wide[]): void;
        declare function takeC1Arr(x: C1[]): void;
        declare function takeRoStr(x: readonly string[]): void;
        declare function takeTup(x: [number, string]): void;
        declare function takeAnyArr(x: any[]): void;
        declare function takeUnkArr(x: unknown[]): void;
        declare function takeGen<T>(x: T[]): void;
        const nums: number[] = [1, 2];
        const strs: string[] = ["a"];
        const nn: number[][] = [];
        const bars: Bar[] = [];
        const wides: Wide[] = [];
        const foos: Foo[] = [];
        const c2s: C2[] = [];
        const tup: [number, string] = [1, "a"];
        const tup2: [string, number] = ["a", 1];
        const anys: any[] = [];
    """.trimIndent()

    // ---- rule A: an array-like argument against an array-like parameter ----------

    @Test
    fun `a number array argument against a string array parameter reports`() {
        val d = diagnose(prelude + "\n\ntakeStrArr(nums);\n")
        assert(d.count { it.code == 2345 } == 1)
        assert(
            d.first { it.code == 2345 }.message ==
                "Argument of type 'number[]' is not assignable to parameter of type 'string[]'."
        )
    }

    @Test
    fun `negative control - a matching array argument is silent`() {
        diagnose(prelude + "\n\ntakeStrArr(strs);\n") should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `an interface-element array argument reports both ways round`() {
        val d = diagnose(prelude + "\n\ntakeFooArr(bars);\ntakeWideArr(foos);\n")
        assert(d.count { it.code == 2345 } == 2)
        assert(
            d.first { it.code == 2345 }.message ==
                "Argument of type 'Bar[]' is not assignable to parameter of type 'Foo[]'."
        )
    }

    @Test
    fun `negative control - a wider element type satisfies a narrower array parameter`() {
        diagnose(prelude + "\n\ntakeFooArr(wides);\n") should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a class-element array argument reports`() {
        val d = diagnose(prelude + "\n\ntakeC1Arr(c2s);\n")
        assert(d.count { it.code == 2345 } == 1)
        assert(
            d.first { it.code == 2345 }.message ==
                "Argument of type 'C2[]' is not assignable to parameter of type 'C1[]'."
        )
    }

    @Test
    fun `a nested array argument reports`() {
        val d = diagnose(prelude + "\n\ntakeStrArrArr(nn);\n")
        assert(d.count { it.code == 2345 } == 1)
        assert(
            d.first { it.code == 2345 }.message ==
                "Argument of type 'number[][]' is not assignable to parameter of type 'string[][]'."
        )
    }

    @Test
    fun `a tuple argument against an array parameter reports`() {
        val d = diagnose(prelude + "\n\ntakeStrArr(tup);\n")
        assert(d.count { it.code == 2345 } == 1)
        assert(
            d.first { it.code == 2345 }.message ==
                "Argument of type '[number, string]' is not assignable to parameter of type 'string[]'."
        )
    }

    @Test
    fun `a tuple argument against a mismatched tuple parameter reports`() {
        val d = diagnose(prelude + "\n\ntakeTup(tup2);\n")
        assert(d.count { it.code == 2345 } == 1)
        assert(
            d.first { it.code == 2345 }.message ==
                "Argument of type '[string, number]' is not assignable to parameter of type '[number, string]'."
        )
    }

    @Test
    fun `negative control - a matching tuple argument is silent`() {
        diagnose(prelude + "\n\ntakeTup(tup);\n") should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a mutable array satisfies a readonly array parameter`() {
        diagnose(prelude + "\n\ntakeRoStr(strs);\n") should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an any element is refused in both directions`() {
        diagnose(prelude + "\n\ntakeAnyArr(nums);\ntakeStrArr(anys);\n") should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an unknown element parameter accepts`() {
        diagnose(prelude + "\n\ntakeUnkArr(nums);\n") should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a generic array parameter is refused`() {
        diagnose(prelude + "\n\ntakeGen(nums);\n") should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a rest parameter compares against its element, not the array`() {
        diagnose(
            """
            declare function rest(...xs: string[]): void;
            rest("a", "b");
            """.trimIndent()
        ) should {
            have(none { it.code == 2345 })
        }
    }

    /**
     * A REST position's `paramType` is the ARRAY (`string[][]`) while the argument is one
     * ELEMENT of it (`string[]`), so the gate must not compare the two. Both references
     * report this call against the ELEMENT (`Argument of type 'number[]' is not assignable
     * to parameter of type 'string[]'.`) and `checkRestArgsAgainstArrayElementType` owns
     * that verdict here; what this pins is that the gate does not invent the WRONG-target
     * row, which is what it emits with the rest guard dropped. The element-level row is a
     * stated false negative of the rest walker, recorded in the session note rather than
     * pinned.
     */
    @Test
    fun `a rest parameter whose element is itself an array never compares against the array`() {
        val d = diagnose(
            """
            declare function restArr(...xs: string[][]): void;
            const nums: number[] = [1, 2];
            restArr(nums);
            """.trimIndent()
        )
        assert(d.none { it.code == 2345 && it.message.contains("string[][]") })
    }

    @Test
    fun `a wrong-arity call reports TS2554 and nothing per argument`() {
        val d = diagnose(prelude + "\n\ntakeStrArr(nums, 1);\n")
        assert(d.count { it.code == 2554 } == 1)
        assert(d.count { it.code == 2345 } == 0)
    }

    // ---- rule A2: a tuple-like literal keeps its element-wise rows ---------------

    @Test
    fun `a tuple-like array literal argument reports per element and not as a whole`() {
        val d = diagnose(prelude + "\n\ntakeStrArr([1, 2]);\n")
        assert(d.count { it.code == 2322 } == 2)
        assert(d.count { it.code == 2345 } == 0)
        assert(d.first { it.code == 2322 }.message == "Type 'number' is not assignable to type 'string'.")
    }

    @Test
    fun `a spread-only array literal argument reports once as a whole`() {
        val d = diagnose(prelude + "\n\ntakeStrArr([...nums]);\n")
        assert(d.count { it.code == 2345 } == 1)
        assert(d.count { it.code == 2322 } == 0)
        assert(
            d.first { it.code == 2345 }.message ==
                "Argument of type 'number[]' is not assignable to parameter of type 'string[]'."
        )
    }

    @Test
    fun `a spread beside a literal element stays element-wise`() {
        val d = diagnose(prelude + "\n\ntakeStrArr([...nums, 3]);\n")
        assert(d.count { it.code == 2322 } == 2)
        assert(d.count { it.code == 2345 } == 0)
    }

    // ---- rule B: the whole-literal row at a declaration --------------------------

    @Test
    fun `a spread-only literal against a tuple annotation reports the whole literal`() {
        val d = diagnose(prelude + "\n\nconst t2: [number, number] = [...nums];\n")
        assert(d.count { it.code == 2322 } == 1)
        assert(
            d.first { it.code == 2322 }.message ==
                "Type 'number[]' is not assignable to type '[number, number]'."
        )
        assert(
            d.first { it.code == 2322 }.messageChain ==
                listOf("  Target requires 2 element(s) but source may have fewer.")
        )
    }

    @Test
    fun `a spread-only literal against an array annotation reports the whole literal`() {
        val d = diagnose(prelude + "\n\nconst s1: string[] = [...nums];\n")
        assert(d.count { it.code == 2322 } == 1)
        assert(
            d.first { it.code == 2322 }.message ==
                "Type 'number[]' is not assignable to type 'string[]'."
        )
    }

    @Test
    fun `negative control - a spread-only literal that matches its annotation is silent`() {
        diagnose(prelude + "\n\nconst s2: number[] = [...nums];\n") should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a tuple-like literal against an array annotation keeps its per-element rows`() {
        val d = diagnose(prelude + "\n\nconst s3: string[] = [1, 2];\n")
        assert(d.count { it.code == 2322 } == 2)
        assert(d.all { it.message == "Type 'number' is not assignable to type 'string'." })
    }

    // ---- rule C: an `asserts x is readonly U[]` narrows the array itself ---------

    private val assertPrelude = """
        interface A { a: number }
        interface B { b: number }
        interface C { c: number }
        type ABC = A | B | C;
        type AB = A | B;
        declare function isAB(n: ABC): n is AB;
        declare function assertEachRo<T, U extends T>(nodes: readonly T[], test: (n: T) => n is U): asserts nodes is readonly U[];
        declare function assertEachMut<T, U extends T>(nodes: T[], test: (n: T) => n is U): asserts nodes is U[];
        declare function takeAB(x: readonly AB[]): void;
        declare function takeABMut(x: AB[]): void;
    """.trimIndent()

    @Test
    fun `a readonly asserts-each narrows the array so the next call is silent`() {
        diagnose(
            assertPrelude + """

            function f(elements: ABC[]) {
              assertEachRo(elements, isAB);
              takeAB(elements);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a mutable asserts-each narrows the array so the next call is silent`() {
        diagnose(
            assertPrelude + """

            function g(elements: ABC[]) {
              assertEachMut(elements, isAB);
              takeABMut(elements);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - without the assertion the same call reports`() {
        val d = diagnose(
            assertPrelude + """

            function h(elements: ABC[]) {
              takeAB(elements);
            }
            """
        )
        assert(d.count { it.code == 2345 } == 1)
    }
}
