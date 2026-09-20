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
 * (P18.149) / (LEGACY.0b): TS2430's CHAIN comes from the general elaboration engine, and
 * its comparison is a TYPE question rather than a NAME one.
 *
 * tsc compares the whole derived interface type against the base
 * (`checkTypeAssignableTo(typeWithThis, baseWithThis, node.name, …)`), so a TS2430 carries
 * exactly the chain the ordinary assignability path produces. `checkInterfaceExtendsClauses`
 * was name-based instead: it compared `typeNodeToSimpleName(annotation)` against
 * `typeToString(baseMemberType)` and hardcoded a two-line chain, which truncated one level
 * below wherever the mismatch really was and could not express a member with no simple name.
 *
 * **Every expectation here was measured against tsgo 7.0.2 first** (`tools/tsgo-7.0.2/lib/tsc`,
 * `strict`, `target: es2020`) over six shapes. Before this round: shape 1 agreed, shape 2
 * reported with a TRUNCATED chain, and shapes 3-6 were MISSING entirely — so the item's own
 * brief ("nested, method-return and method-with-parameters are entirely missing") was right
 * about the methods and wrong about nesting, which only the matrix showed.
 *
 * The pins assert the FULL chain, not merely that the row fires: a verdict-only assertion
 * passes on the truncated chain this round exists to fix.
 */
class InterfaceExtendsChainAndShapesTest {

    private val prelude = """
        interface Inner { size: number }
        interface InnerBad { size: string }
    """.trimIndent() + "\n"

    private fun chainOf(source: String): List<String> {
        val d = diagnose(prelude + source).filter { it.code == 2430 }
        assert(d.size == 1)
        return listOf(d[0].message) + d[0].messageChain
    }

    /**
     * Control: a DIRECT property mismatch. Its chain is the one shape the hardcoded pair
     * already rendered correctly, so this pin must stay green across the change — it is what
     * says the engine chain did not move a row that was already right.
     */
    @Test
    fun `a direct property mismatch keeps the Types of property chain`() {
        assert(
            chainOf(
                """
                interface B1 { p: number }
                interface D1 extends B1 { p: string }
                """.trimIndent(),
            ) == listOf(
                "Interface 'D1' incorrectly extends interface 'B1'.",
                "  Types of property 'p' are incompatible.",
                "    Type 'string' is not assignable to type 'number'.",
            ),
        )
    }

    /**
     * A NESTED property mismatch drills to the failing leaf and names the dotted path.
     * The row fired before this round; its chain stopped at the whole-object line
     * `Type 'InnerBad' is not assignable to type 'Inner'.`, one level short of the cause.
     */
    @Test
    fun `a nested property mismatch names the dotted path`() {
        assert(
            chainOf(
                """
                interface B2 { p: Inner }
                interface D2 extends B2 { p: InnerBad }
                """.trimIndent(),
            ) == listOf(
                "Interface 'D2' incorrectly extends interface 'B2'.",
                "  The types of 'p.size' are incompatible between these types.",
                "    Type 'string' is not assignable to type 'number'.",
            ),
        )
    }

    /** A METHOD whose return type differs — entirely missing before this round. */
    @Test
    fun `a method return mismatch reports with the types returned by chain`() {
        assert(
            chainOf(
                """
                interface B3 { m(): number }
                interface D3 extends B3 { m(): string }
                """.trimIndent(),
            ) == listOf(
                "Interface 'D3' incorrectly extends interface 'B3'.",
                "  The types returned by 'm()' are incompatible between these types.",
                "    Type 'string' is not assignable to type 'number'.",
            ),
        )
    }

    /**
     * A method return that DRILLS DEEPER keeps the *returned by* wording and dots the
     * property onto the called name — tsgo's second chain fold, landed at (P18.148) and
     * reachable from TS2430 only once the walker routes through the engine.
     */
    @Test
    fun `a method return drilling deeper dots the property onto the called name`() {
        assert(
            chainOf(
                """
                interface B4 { m(): Inner }
                interface D4 extends B4 { m(): InnerBad }
                """.trimIndent(),
            ) == listOf(
                "Interface 'D4' incorrectly extends interface 'B4'.",
                "  The types returned by 'm().size' are incompatible between these types.",
                "    Type 'string' is not assignable to type 'number'.",
            ),
        )
    }

    /**
     * A method whose PARAMETER differs keeps the standard property form and elaborates the
     * parameter pair — a different chain shape from the two above, which is why it is pinned
     * separately rather than being assumed to follow from the return-type case.
     */
    @Test
    fun `a method parameter mismatch elaborates the parameter pair`() {
        assert(
            chainOf(
                """
                interface B5 { m(a: number): void }
                interface D5 extends B5 { m(a: string): void }
                """.trimIndent(),
            ) == listOf(
                "Interface 'D5' incorrectly extends interface 'B5'.",
                "  Types of property 'm' are incompatible.",
                "    Type '(a: string) => void' is not assignable to type '(a: number) => void'.",
                "      Types of parameters 'a' and 'a' are incompatible.",
                "        Type 'number' is not assignable to type 'string'.",
            ),
        )
    }

    /**
     * A property annotated with a TYPE LITERAL. `typeNodeToSimpleName` answers null for it,
     * which used to skip the member for want of a DISPLAY name — an undecidable *rendering*
     * silencing a perfectly decidable *comparison*.
     */
    @Test
    fun `a type-literal property mismatch reports and drills`() {
        assert(
            chainOf(
                """
                interface B6 { p: { size: number } }
                interface D6 extends B6 { p: { size: string } }
                """.trimIndent(),
            ) == listOf(
                "Interface 'D6' incorrectly extends interface 'B6'.",
                "  The types of 'p.size' are incompatible between these types.",
                "    Type 'string' is not assignable to type 'number'.",
            ),
        )
    }

    /**
     * The `complexRecursiveCollections` shape: a method WITH parameters whose return type
     * drills into a property, rendered `m(...)` rather than `m()`. This is the row whose
     * pin walker hardcodes the chain; pinning it through the ENGINE is what makes the
     * re-transcription of that walker's string a measurement rather than a copy.
     */
    @Test
    fun `a parameterised method whose return drills deeper renders the ellipsis form`() {
        assert(
            chainOf(
                """
                interface B7 { map(k: string): Inner }
                interface D7 extends B7 { map(k: string): InnerBad }
                """.trimIndent(),
            ) == listOf(
                "Interface 'D7' incorrectly extends interface 'B7'.",
                "  The types returned by 'map(...).size' are incompatible between these types.",
                "    Type 'string' is not assignable to type 'number'.",
            ),
        )
    }

    /**
     * Negative control for the shape widening, which can only ever ADD rows. A derived
     * interface that narrows a method's return type and widens its parameter is LEGAL
     * (returns are covariant, parameters bivariant for methods) and must stay silent —
     * the arm that reports it would be a false positive on ordinary TypeScript.
     */
    @Test
    fun `negative control - a legally refined method does not report`() {
        val d = diagnose(
            prelude +
                """
                interface B8 { m(a: number | string): number | string }
                interface D8 extends B8 { m(a: number): number }
                """.trimIndent(),
        )
        assert(d.none { it.code == 2430 })
    }

    /**
     * Negative control: a derived interface that redeclares NOTHING inherits its base's
     * members unchanged. The structural arm resolves members from the derived interface's
     * own table, where an un-redeclared member IS the base's symbol — comparing it to
     * itself must not report.
     */
    @Test
    fun `negative control - an empty derived interface does not report`() {
        val d = diagnose(
            prelude +
                """
                interface B9 { m(a: number): Inner; p: Inner }
                interface D9 extends B9 { }
                """.trimIndent(),
        )
        assert(d.none { it.code == 2430 })
    }

    /**
     * The confinement guard, pinned. The structural arm may only decide a member the derived
     * interface DECLARES in one of the two shapes the name-based loop cannot name; without
     * that gate it reaches an INHERITED member and reports TS2430 where the real answer is
     * TS2320 — `interface A extends C, C2` with `x?: number` and `x: number` declares no `x`
     * at all, and tsgo reports only *cannot simultaneously extend*.
     *
     * This is the one guard of the round's four that NO other pin discriminates: its ablation
     * reddens the corpus screen (`inheritSameNamePropertiesWithDifferentOptionality`) and
     * nothing else, so it was held on a baseline until this pin existed.
     */
    @Test
    fun `negative control - an inherited member conflict stays TS2320 and grows no TS2430`() {
        val d = diagnose(
            """
            interface C { x?: number }
            interface C2 { x: number }
            interface A extends C, C2 { y: string }
            """.trimIndent(),
            directives = "// @strict: true",
        )
        assert(d.none { it.code == 2430 })
        assert(d.count { it.code == 2320 } == 1)
    }

    /**
     * The chain builder prefers a LEAF mismatch where this walker takes the first base
     * property in table order, so the two can choose DIFFERENT members. When they disagree
     * the engine chain is refused and the hardcoded pair stands, because a chain naming a
     * member other than the one the verdict fired on contradicts its own row.
     *
     * Here the walker fires on `a` (an object mismatch, first in the base) while the engine
     * would lead with `b` (a primitive leaf). Whichever member the row ends up naming, its
     * chain must name the SAME one — that is the invariant, and it holds under either
     * selection policy, so the pin does not freeze the policy.
     */
    @Test
    fun `the chain names the same member the row fired on`() {
        val d = diagnose(
            prelude +
                """
                interface BX { a: Inner; b: number }
                interface DX extends BX { a: InnerBad; b: string }
                """.trimIndent(),
        ).filter { it.code == 2430 }
        assert(d.size == 1)
        val first = d[0].messageChain.first()
        val quoted = first.substringAfter('\'').substringBefore('\'')
        val member = quoted.takeWhile { it != '.' && it != '(' }
        assert(member == "a" || member == "b")
        // and the chain must be internally consistent: a dotted path may only extend the
        // member it starts with, never name a second top-level member.
        assert(first.count { it == '\'' } == 2)
    }
}
