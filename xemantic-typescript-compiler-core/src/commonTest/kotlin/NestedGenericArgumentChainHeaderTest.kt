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
 * (P18.135) — TypeScript 7's per-level relation header on a NESTED generic instantiation.
 *
 * Every expectation below was measured against `tools/tsgo-7.0.2/lib/tsc` on 2026-09-19 over
 * the same source. The rule is the EMITTING half of the one condition whose suppressing half
 * [RelationHeadSuppression] already owned: tsgo reaches a generic instantiation's arguments
 * through `typeArgumentsRelatedTo`, and the failing argument pair's own `isRelatedToEx` frame
 * ends in `reportErrorResults` -> `reportRelationError`, which pushes
 *
 *     Type '<srcArg>' is not assignable to type '<tgtArg>'.
 *
 * unless the entry already on the chain is a missing-property sentence about THAT VERY PAIR
 * (`chainArgsMatch(nil, generalizedSourceType, targetType)`, `relater.go` ~4809).
 *
 * So the ladder is one header per nesting level and NONE at the innermost one, which makes
 * the DEPTH-1 case the sharpest control in this class: both compilers already agreed there
 * before this round, and a rule that emitted unconditionally would move it.
 *
 * VARIANCE IS NOT THE AXIS and was measured not to be: a covariant `{ get(): T }` wrapper
 * reads exactly as the invariant `ZzzInv` below, and a contravariant `{ set(v: T): void }`
 * one is silent in both compilers for these arguments. What decides the header is only that
 * the recursion descended through a type ARGUMENT rather than through a property, which is
 * why `ZzzX1`/`ZzzX2` (plain property nesting, collapsed to a dotted path) is untouched.
 */
class NestedGenericArgumentChainHeaderTest {

    private val prelude = """
        interface ZzzSmall { a: string }
        interface ZzzBig { a: string; b: number }
        interface ZzzInv<T> { get(): T; set(v: T): void }
    """.trimIndent() + "\n"

    // ------------------------------------------------------------------ controls

    @Test
    fun `depth 1 - the innermost level names the argument pair so its header stays suppressed`() {
        val d = diagnose(
            prelude + """
            declare const z1: ZzzInv<ZzzSmall>;
            const c1: ZzzInv<ZzzBig> = z1;
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(d[0].code == 2322)
        assert(d[0].message == "Type 'ZzzInv<ZzzSmall>' is not assignable to type 'ZzzInv<ZzzBig>'.")
        assert(
            d[0].messageChain ==
                listOf("  Property 'b' is missing in type 'ZzzSmall' but required in type 'ZzzBig'.")
        )
    }

    @Test
    fun `plain property nesting is untouched - it collapses to a dotted path with no header`() {
        val d = diagnose(
            prelude + """
            interface ZzzW1 { p: ZzzSmall }
            interface ZzzW2 { p: ZzzBig }
            interface ZzzX1 { q: ZzzW1 }
            interface ZzzX2 { q: ZzzW2 }
            declare const z6: ZzzX1;
            const c6: ZzzX2 = z6;
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(d[0].code == 2322)
        assert(d[0].message == "Type 'ZzzX1' is not assignable to type 'ZzzX2'.")
        assert(
            d[0].messageChain == listOf(
                "  The types of 'q.p' are incompatible between these types.",
                "    Property 'b' is missing in type 'ZzzSmall' but required in type 'ZzzBig'.",
            )
        )
    }

    // ------------------------------------------------------------------- the rule

    @Test
    fun `depth 2 - the outer argument pair gets its own header`() {
        val d = diagnose(
            prelude + """
            declare const z2: ZzzInv<ZzzInv<ZzzSmall>>;
            const c2: ZzzInv<ZzzInv<ZzzBig>> = z2;
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(d[0].code == 2322)
        assert(
            d[0].message ==
                "Type 'ZzzInv<ZzzInv<ZzzSmall>>' is not assignable to type 'ZzzInv<ZzzInv<ZzzBig>>'."
        )
        assert(
            d[0].messageChain == listOf(
                "  Type 'ZzzInv<ZzzSmall>' is not assignable to type 'ZzzInv<ZzzBig>'.",
                "    Property 'b' is missing in type 'ZzzSmall' but required in type 'ZzzBig'.",
            )
        )
    }

    @Test
    fun `depth 3 - one header per level and none at the innermost`() {
        val d = diagnose(
            prelude + """
            declare const z3: ZzzInv<ZzzInv<ZzzInv<ZzzSmall>>>;
            const c3: ZzzInv<ZzzInv<ZzzInv<ZzzBig>>> = z3;
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(d[0].code == 2322)
        assert(
            d[0].message ==
                "Type 'ZzzInv<ZzzInv<ZzzInv<ZzzSmall>>>' is not assignable to " +
                "type 'ZzzInv<ZzzInv<ZzzInv<ZzzBig>>>'."
        )
        assert(
            d[0].messageChain == listOf(
                "  Type 'ZzzInv<ZzzInv<ZzzSmall>>' is not assignable to type 'ZzzInv<ZzzInv<ZzzBig>>'.",
                "    Type 'ZzzInv<ZzzSmall>' is not assignable to type 'ZzzInv<ZzzBig>'.",
                "      Property 'b' is missing in type 'ZzzSmall' but required in type 'ZzzBig'.",
            )
        )
    }

    @Test
    fun `depth 4 - the ladder does not stop at three`() {
        val d = diagnose(
            prelude + """
            declare const z8: ZzzInv<ZzzInv<ZzzInv<ZzzInv<ZzzSmall>>>>;
            const c8: ZzzInv<ZzzInv<ZzzInv<ZzzInv<ZzzBig>>>> = z8;
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(
            d[0].messageChain == listOf(
                "  Type 'ZzzInv<ZzzInv<ZzzInv<ZzzSmall>>>' is not assignable to " +
                    "type 'ZzzInv<ZzzInv<ZzzInv<ZzzBig>>>'.",
                "    Type 'ZzzInv<ZzzInv<ZzzSmall>>' is not assignable to type 'ZzzInv<ZzzInv<ZzzBig>>'.",
                "      Type 'ZzzInv<ZzzSmall>' is not assignable to type 'ZzzInv<ZzzBig>'.",
                "        Property 'b' is missing in type 'ZzzSmall' but required in type 'ZzzBig'.",
            )
        )
    }

    @Test
    fun `a two-parameter generic names the failing argument pair and not the whole instantiation`() {
        val d = diagnose(
            prelude + """
            interface ZzzPair<A, B> { x(): A; y(): B }
            declare const z4: ZzzPair<string, ZzzPair<string, ZzzSmall>>;
            const c4: ZzzPair<string, ZzzPair<string, ZzzBig>> = z4;
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(
            d[0].message ==
                "Type 'ZzzPair<string, ZzzPair<string, ZzzSmall>>' is not assignable to " +
                "type 'ZzzPair<string, ZzzPair<string, ZzzBig>>'."
        )
        assert(
            d[0].messageChain == listOf(
                "  Type 'ZzzPair<string, ZzzSmall>' is not assignable to type 'ZzzPair<string, ZzzBig>'.",
                "    Property 'b' is missing in type 'ZzzSmall' but required in type 'ZzzBig'.",
            )
        )
    }

    @Test
    fun `an array of arrays is the same rule - Array is a generic reference like any other`() {
        val d = diagnose(
            prelude + """
            declare const z5: ZzzSmall[][];
            const c5: ZzzBig[][] = z5;
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(d[0].message == "Type 'ZzzSmall[][]' is not assignable to type 'ZzzBig[][]'.")
        assert(
            d[0].messageChain == listOf(
                "  Type 'ZzzSmall[]' is not assignable to type 'ZzzBig[]'.",
                "    Property 'b' is missing in type 'ZzzSmall' but required in type 'ZzzBig'.",
            )
        )
    }

    // ------------------------------------------------------------------- residue

    @Test
    fun `a nested CALLBACK PARAMETER is a different descent and keeps today's chain`() {
        // The depth-1 function shape, which both compilers already agree on: tsgo's FIRST
        // callback-parameter level recurses straight into `compareSignaturesRelated` and
        // pushes no header at all (`relater.go:1568`), so there is nothing here for this
        // round's rule to add. The DEEPER function levels — where tsgo's `checkMode` carries
        // the Callback bit, `callbacks` goes false and the pair goes back through
        // `isRelatedToEx` — are a SEPARATE locus in `getFunctionMismatchElaboration` and are
        // deliberately out of this round's boundary; `mutuallyRecursiveCallbacks` is the one
        // corpus row of that family and its dedicated walker carries tsgo's answer by hand.
        val d = diagnose(
            """
            declare const z7: (g: (x: string) => void) => void;
            const c7: (g: (x: number) => void) => void = z7;
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(
            d[0].message ==
                "Type '(g: (x: string) => void) => void' is not assignable to " +
                "type '(g: (x: number) => void) => void'."
        )
        assert(
            d[0].messageChain == listOf(
                "  Types of parameters 'g' and 'g' are incompatible.",
                "    Types of parameters 'x' and 'x' are incompatible.",
                "      Type 'string' is not assignable to type 'number'.",
            )
        )
    }
}
