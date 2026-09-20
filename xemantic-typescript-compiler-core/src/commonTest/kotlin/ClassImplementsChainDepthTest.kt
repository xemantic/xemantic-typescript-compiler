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
 * (P18.150): TS2416's chain drills into the failing property instead of stopping at the
 * whole-object line.
 *
 * The sibling of (P18.149) one walker over, and the same law: the general elaboration is what
 * knows how deep a mismatch is, so a chain that names two object types and stops is short by
 * however far the cause actually lies. Two tails were truncated — a PROPERTY pair
 * (`checkClassPropertyOverrides`, which added its `Type 'A' is not assignable to type 'B'.`
 * line and then had nothing more to say for a non-function pair) and a method's RETURN pair
 * (`addSignatureElaboration`, whose only drill was for a union source).
 *
 * **The item's recorded sizing said `class C implements B` whose method return drills deeper
 * "reports no TS2416 at all", and that is WRONG** — all six shapes report, in both the
 * `string`/`number` and the `number | undefined` spellings, and the gap is purely chain DEPTH.
 * That is the second recorded sizing in one session to say "missing" where the truth is
 * "truncated", so these pins assert the FULL chain: a row-count assertion passes on the
 * truncated chain this round exists to fix.
 *
 * Every expectation was measured against tsgo 7.0.2 (`strict`, `target: es2020`) first.
 */
class ClassImplementsChainDepthTest {

    private val prelude = """
        interface Inner { size: number }
        interface InnerBad { size: string }
    """.trimIndent() + "\n"

    private fun chainOf(source: String): List<String> {
        val d = diagnose(prelude + source).filter { it.code == 2416 }
        assert(d.size == 1)
        return listOf(d[0].message) + d[0].messageChain
    }

    /** Control: a leaf property pair has nothing to drill into and must not grow a line. */
    @Test
    fun `a direct property mismatch keeps its two-line chain`() {
        assert(
            chainOf(
                """
                interface B1 { p: number }
                class C1 implements B1 { p: string = "x" }
                """.trimIndent(),
            ) == listOf(
                "Property 'p' in type 'C1' is not assignable to the same property in base type 'B1'.",
                "  Type 'string' is not assignable to type 'number'.",
            ),
        )
    }

    /** A named-interface property pair drills to the failing member. */
    @Test
    fun `a nested property mismatch drills into the failing member`() {
        assert(
            chainOf(
                """
                interface B2 { p: Inner }
                class C2 implements B2 { p: InnerBad = { size: "x" } }
                """.trimIndent(),
            ) == listOf(
                "Property 'p' in type 'C2' is not assignable to the same property in base type 'B2'.",
                "  Type 'InnerBad' is not assignable to type 'Inner'.",
                "    Types of property 'size' are incompatible.",
                "      Type 'string' is not assignable to type 'number'.",
            ),
        )
    }

    /** Control: a shallow method return was already byte-identical and must stay so. */
    @Test
    fun `a shallow method return mismatch keeps its three-line chain`() {
        assert(
            chainOf(
                """
                interface B3 { m(): number }
                class C3 implements B3 { m(): string { return "x" } }
                """.trimIndent(),
            ) == listOf(
                "Property 'm' in type 'C3' is not assignable to the same property in base type 'B3'.",
                "  Type '() => string' is not assignable to type '() => number'.",
                "    Type 'string' is not assignable to type 'number'.",
            ),
        )
    }

    /**
     * A method RETURN pair that is two objects drills on — the deepest of the shapes, and the
     * one the item named. Five lines against the three we printed.
     */
    @Test
    fun `a method return mismatch drills past the return pair`() {
        assert(
            chainOf(
                """
                interface B4 { m(): Inner }
                class C4 implements B4 { m(): InnerBad { return { size: "x" } } }
                """.trimIndent(),
            ) == listOf(
                "Property 'm' in type 'C4' is not assignable to the same property in base type 'B4'.",
                "  Type '() => InnerBad' is not assignable to type '() => Inner'.",
                "    Type 'InnerBad' is not assignable to type 'Inner'.",
                "      Types of property 'size' are incompatible.",
                "        Type 'string' is not assignable to type 'number'.",
            ),
        )
    }

    /**
     * Control: a PARAMETER mismatch is elaborated by `addSignatureElaboration` and returns
     * before the return-type block, so the drill must not reach it. This is the pin that says
     * the `chain.size == sizeBefore` gate cannot double-append.
     */
    @Test
    fun `a method parameter mismatch keeps its parameter elaboration`() {
        assert(
            chainOf(
                """
                interface B5 { m(a: number): void }
                class C5 implements B5 { m(a: string): void { } }
                """.trimIndent(),
            ) == listOf(
                "Property 'm' in type 'C5' is not assignable to the same property in base type 'B5'.",
                "  Type '(a: string) => void' is not assignable to type '(a: number) => void'.",
                "    Types of parameters 'a' and 'a' are incompatible.",
                "      Type 'number' is not assignable to type 'string'.",
            ),
        )
    }

    /** A TYPE-LITERAL property pair drills exactly as the named-interface one does. */
    @Test
    fun `a type-literal property mismatch drills into the failing member`() {
        assert(
            chainOf(
                """
                interface B6 { p: { size: number } }
                class C6 implements B6 { p: { size: string } = { size: "x" } }
                """.trimIndent(),
            ) == listOf(
                "Property 'p' in type 'C6' is not assignable to the same property in base type 'B6'.",
                "  Type '{ size: string; }' is not assignable to type '{ size: number; }'.",
                "    Types of property 'size' are incompatible.",
                "      Type 'string' is not assignable to type 'number'.",
            ),
        )
    }

    /**
     * The `number | undefined` spelling, which is the one the item's own sizing was written
     * against — it reports too, and drills one level further than the `string` spelling because
     * the leaf pair is a union. Pinned separately so a future reader cannot conclude from the
     * fixtures above that only a primitive leaf was measured.
     */
    @Test
    fun `a nullish leaf drills one level further`() {
        assert(
            chainOf(
                """
                interface B7 { m(): { size: number } }
                class C7 implements B7 { m(): { size: number | undefined } { return { size: 1 } } }
                """.trimIndent(),
            ) == listOf(
                "Property 'm' in type 'C7' is not assignable to the same property in base type 'B7'.",
                "  Type '() => { size: number | undefined; }' is not assignable to type '() => { size: number; }'.",
                "    Type '{ size: number | undefined; }' is not assignable to type '{ size: number; }'.",
                "      Types of property 'size' are incompatible.",
                "        Type 'number | undefined' is not assignable to type 'number'.",
                "          Type 'undefined' is not assignable to type 'number'.",
            ),
        )
    }

    /**
     * Negative control: the drill may only ever ADD depth to a row that already fires. A class
     * that legally implements its interface must stay silent — the drill is reached from inside
     * an emission, so this pin is what says the round did not move a verdict anywhere.
     */
    @Test
    fun `negative control - a legal implementation reports nothing`() {
        val d = diagnose(
            prelude +
                """
                interface B8 { p: Inner; m(a: number | string): number | string }
                class C8 implements B8 { p: Inner = { size: 1 }; m(a: number): number { return 1 } }
                """.trimIndent(),
        )
        assert(d.none { it.code == 2416 })
    }
}
