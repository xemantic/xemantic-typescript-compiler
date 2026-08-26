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
 * (CHK.54b) tsc TRIES A **SPECIALIZED** OVERLOAD FIRST, WHATEVER THE DECLARATION
 * ORDER, AND WE TRIED THEM STRICTLY IN ORDER — so `f(x: string): A` declared before
 * `f(x: "a"): B` answered `A` for `f("a")` where tsc 7.0.2 answers `B`.
 *
 * That is `reorderCandidates` / `GH#1133`: a signature is "specialized" when some
 * parameter's type ANNOTATION is a literal type node, and every such signature is
 * moved ahead of every non-specialized one with the relative order inside each group
 * preserved. [Checker.resolveCallOverload] now asks [Checker.specializedFirst] for
 * that order.
 *
 * ## The test is SYNTACTIC, and reproducing it "by type shape" would be wrong
 *
 * tsc's condition is `param.type.kind === SyntaxKind.LiteralType`, so a literal
 * UNION (`x: "a" | "b"`) is a UnionType node and does **not** specialize — measured
 * against tsc 7.0.2, which leaves it in declaration order. Nor does an ALIAS to one.
 * Both are pinned below as refusals, because a rule derived from the resolved TYPE
 * would hoist them and diverge in the opposite direction.
 *
 * ## Nothing in this repo except these pins can see the rule
 *
 * The 13k-baseline corpus, all 20 `cost_gate.py` counters, the 8-profile grid,
 * `partition-equivalence`, `capture-equivalence`, `knip` and `jsonrepair` are ALL
 * byte-identical across the change — every one of them was measured. The rule is
 * carried by tsc 7.0.2 as the oracle and by this class, and by nothing else.
 */
class OverloadSpecializedOrderTest {

    /**
     * The headline row. `zzzB(x: "a")` is declared SECOND and must still be the one
     * selected, so the write probe must name `R2`. Asserted as a VALUE: a wrongly
     * selected overload is silent, so "no error" cannot see it.
     */
    @Test
    fun `a specialized overload is tried before an earlier non-specialized one`() {
        val d = diagnose("""
            interface R1 { r1: 0 }
            interface R2 { r2: 0 }
            interface Zv { zv: 0 }
            declare function zzzB(x: string): R1
            declare function zzzB(x: "a"): R2
            const zzzOut: Zv = zzzB("a")
        """)
        assert(d.map { it.code to it.message } == listOf(
            2741 to "Property 'zv' is missing in type 'R2' but required in type 'Zv'."
        ))
    }

    /** A literal-UNION parameter is not specialized, so the bare literal still wins. */
    @Test
    fun `a bare literal outranks an earlier literal union`() {
        val d = diagnose("""
            interface R1 { r1: 0 }
            interface R2 { r2: 0 }
            interface Zv { zv: 0 }
            declare function zzzR(x: "a" | "b"): R1
            declare function zzzR(x: "a"): R2
            const zzzOut: Zv = zzzR("a")
        """)
        assert(d.map { it.code to it.message } == listOf(
            2741 to "Property 'zv' is missing in type 'R2' but required in type 'Zv'."
        ))
    }

    /** The specializing annotation may sit on any parameter, not only the first. */
    @Test
    fun `a literal on the second parameter specializes the signature`() {
        val d = diagnose("""
            interface R1 { r1: 0 }
            interface R2 { r2: 0 }
            interface Zv { zv: 0 }
            declare function zzzN(x: string, y: string): R1
            declare function zzzN(x: string, y: "a"): R2
            const zzzOut: Zv = zzzN("q", "a")
        """)
        assert(d.map { it.code to it.message } == listOf(
            2741 to "Property 'zv' is missing in type 'R2' but required in type 'Zv'."
        ))
    }

    /**
     * REFUSAL. A literal UNION parameter is a UnionType node, not a LiteralType one,
     * so it does NOT specialize and declaration order decides — `R1`, not `R2`.
     * Hoisting by resolved type shape instead of by syntax fails exactly here.
     */
    @Test
    fun `a literal union parameter does not specialize`() {
        val d = diagnose("""
            interface R1 { r1: 0 }
            interface R2 { r2: 0 }
            interface Zv { zv: 0 }
            declare function zzzQ(x: string): R1
            declare function zzzQ(x: "a" | "b"): R2
            const zzzOut: Zv = zzzQ("a")
        """)
        assert(d.map { it.code to it.message } == listOf(
            2741 to "Property 'zv' is missing in type 'R1' but required in type 'Zv'."
        ))
    }

    /** REFUSAL. Neither does an ALIAS whose body is a literal union. */
    @Test
    fun `an alias to a literal union does not specialize`() {
        val d = diagnose("""
            interface R1 { r1: 0 }
            interface R2 { r2: 0 }
            interface Zv { zv: 0 }
            type ZzzEnc = "utf8" | "ascii"
            declare function zzzS(x: string): R1
            declare function zzzS(x: ZzzEnc): R2
            const zzzOut: Zv = zzzS("utf8")
        """)
        assert(d.map { it.code to it.message } == listOf(
            2741 to "Property 'zv' is missing in type 'R1' but required in type 'Zv'."
        ))
    }

    /**
     * REFUSAL. The relative order INSIDE the specialized group is preserved — two
     * equally applicable specialized overloads still resolve first-wins, so the
     * reorder is a stable partition and not a sort.
     */
    @Test
    fun `relative order inside the specialized group is preserved`() {
        val d = diagnose("""
            interface R1 { r1: 0 }
            interface R2 { r2: 0 }
            interface R3 { r3: 0 }
            interface Zv { zv: 0 }
            declare function zzzK(x: string): R3
            declare function zzzK(x: "a"): R1
            declare function zzzK(x: "a"): R2
            const zzzOut: Zv = zzzK("a")
        """)
        assert(d.map { it.code to it.message } == listOf(
            2741 to "Property 'zv' is missing in type 'R1' but required in type 'Zv'."
        ))
    }

    /**
     * CONTROL — green before and after. With no specialized signature anywhere the
     * partition is the identity and declaration order is untouched.
     */
    @Test
    fun `declaration order is untouched when nothing is specialized`() {
        val d = diagnose("""
            interface R1 { r1: 0 }
            interface R2 { r2: 0 }
            interface Zv { zv: 0 }
            declare function zzzT(x: string): R1
            declare function zzzT(x: string): R2
            const zzzOut: Zv = zzzT("a")
        """)
        assert(d.map { it.code to it.message } == listOf(
            2741 to "Property 'zv' is missing in type 'R1' but required in type 'Zv'."
        ))
    }
}
