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
 * (CHK.58) A WEAK-TYPE MESSAGE NAMES THE ENUM **MEMBER**, EXCEPT WHERE THE ENUM HAS
 * EXACTLY ONE — AND THE COROLLARY IS THAT THE ONE BASELINE GATING THIS AGREED WITH THE
 * WRONG ANSWER.
 *
 * [Checker.enumMemberWeakSource] rendered the ENUM's name for every `E.A` source.
 * Pristine's `nestedExcessPropertyChecking.errors.txt` — the only ACTIVE baseline that
 * exercises it — says `Type 'E'`, and its `enum E { A = "A" }` declares exactly ONE
 * member, where the enum type and the member's literal type are the same type and
 * tsc's `typeToString` prints the enum's own name. Every MULTI-member enum therefore
 * diverged silently, in both flavours. Measured on tsc 7.0.2 over
 * `build/chk58/ora3/e1.ts`:
 *
 * | enum | tsc |
 * |---|---|
 * | `enum E { A = "A" }` | `Type 'E'` |
 * | `enum E { A = "A", B = "B" }` | `Type 'E.A'` |
 * | `enum E { A, B }` | `Type 'E.A'` |
 * | `enum E { A }` | `Type 'E'` |
 *
 * So this is one rule and not a special case, and the "our display is wrong" reading
 * of it had to be checked against the oracle before being believed: at the position
 * the corpus tests, the old answer was RIGHT.
 */
class WeakEnumSourceDisplayTest {

    /**
     * PRISTINE's OWN SHAPE, a ONE-member string enum — tsc 7.0.2 renders the ENUM's
     * name, and `nestedExcessPropertyChecking.errors.txt(17,5)` is the byte-exact gate.
     * Always green; it is the boundary the next two pins are the other side of.
     */
    @Test
    fun `a one-member string enum source renders the enum name`() {
        val d = diagnose("""
            enum ZzzE1 { A = "A" }
            let zzzX1: { zzzNope?: any } = ZzzE1.A;
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type 'ZzzE1' has no properties in common with type '{ zzzNope?: any; }'.")
        assert(d[0].line == 2)
        assert(d[0].character == 5)
    }

    /**
     * A TWO-member STRING enum — tsc 7.0.2: `e1.ts(4,5): … Type 'ZzzE2.A' …`, where
     * this compiler said `'ZzzE2'`.
     */
    @Test
    fun `a multi-member string enum source renders the member`() {
        val d = diagnose("""
            enum ZzzE2 { A = "A", B = "B" }
            let zzzX2: { zzzNope?: any } = ZzzE2.A;
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type 'ZzzE2.A' has no properties in common with type '{ zzzNope?: any; }'.")
        assert(d[0].line == 2)
        assert(d[0].character == 5)
    }

    /**
     * A TWO-member NUMERIC enum takes the same rule — the flavour decides the SOURCE
     * property names ([Checker.weakSourcePropertyNames] over the `String` vs `Number`
     * wrapper), never the display. tsc 7.0.2: `e1.ts(6,5): … Type 'ZzzE3.A' …`.
     */
    @Test
    fun `a multi-member numeric enum source renders the member`() {
        val d = diagnose("""
            enum ZzzE3 { A, B }
            let zzzX3: { zzzNope?: any } = ZzzE3.A;
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type 'ZzzE3.A' has no properties in common with type '{ zzzNope?: any; }'.")
        assert(d[0].line == 2)
        assert(d[0].character == 5)
    }

    /**
     * A ONE-member NUMERIC enum is the enum name again — the boundary is the member
     * COUNT and not the flavour. tsc 7.0.2: `e1.ts(8,5): … Type 'ZzzE4' …`.
     */
    @Test
    fun `a one-member numeric enum source renders the enum name`() {
        val d = diagnose("""
            enum ZzzE4 { A }
            let zzzX4: { zzzNope?: any } = ZzzE4.A;
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type 'ZzzE4' has no properties in common with type '{ zzzNope?: any; }'.")
        assert(d[0].line == 2)
        assert(d[0].character == 5)
    }

    /**
     * MEASURED RESIDUE, not coverage — an enum-member source at a CALL ARGUMENT is
     * SILENT here where tsc 7.0.2 reports `e1.ts(10,6): error TS2559: Type 'ZzzE2.A'
     * has no properties in common with type '{ zzzNope?: any; }'.` The argument walker
     * types its argument through [Checker.getTypeOfExpression], which answers `any` for
     * `E.A` and is skipped before the weak rule is reached; only the var-decl walker
     * carries the AST-side classification. This pin records the hole so a future round
     * that closes it is told by a red pin rather than by a moved baseline.
     */
    @Test
    fun `residue - an enum member argument is silent where tsc reports TS2559`() {
        val d = diagnose("""
            enum ZzzE5 { A = "A", B = "B" }
            declare function zzzF5(o: { zzzNope?: any }): void;
            zzzF5(ZzzE5.A);
        """)
        assert(d.none { it.code == 2559 || it.code == 2560 })
    }
}
