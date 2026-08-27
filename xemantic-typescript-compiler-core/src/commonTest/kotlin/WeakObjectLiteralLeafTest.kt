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
 * (CHK.59) A FRESH OBJECT LITERAL ELABORATES **INTO** THE LITERAL, SO THE WEAK ROW
 * BELONGS AT THE PROPERTY AND CARRIES THE **WIDENED** VALUE TYPE.
 *
 * Two independent defects, both on `const x: Out = { zzzIn: <leaf> }`:
 *
 *  1. **ORDER.** [Checker.tryEmitNestedWeakVarDecl] (one level, TS2322 at the var NAME
 *     with a `Types of property … are incompatible` chain) ran BEFORE
 *     [Checker.tryEmitObjectLiteralWeakLeaves] (TS2559 at the property KEY + the
 *     TS6500 related row), so a fresh literal took the wrong one of the two. tsc's
 *     `elaborateObjectLiteral` descends into a fresh literal and reports at the
 *     offending property; the nested walker's own subject is an IDENTIFIER source
 *     (pristine's `let weak: Weak & Spoiler = propertiesWrong`), which the leaf walker
 *     can never reach — so the two are ordered, not merged.
 *  2. **DISPLAY.** The leaf reports the object literal's OWN property type, i.e. the
 *     WIDENED one for a string or numeric literal and the literal itself for a boolean.
 *     Measured on tsc 7.0.2 over `build/chk59/dbg/d3.ts` and `d4.ts`: `"utf8"` renders
 *     `string`, `12` renders `number`, a template literal renders `string`, `false`
 *     renders `false`, an enum member renders `ZzzEL.A`. **The TOP-LEVEL var-decl
 *     position does NOT widen** — pristine's `nestedExcessPropertyChecking.errors.txt`
 *     line 18 reports `Type '"A"'` for `let y: { nope?: any } = "A"` — because there
 *     the fresh literal reaches the relation directly. That asymmetry is the whole
 *     reason the widening lives in the leaf walker and nowhere else, and pristine's
 *     lines 30 and 40 (`Type 'false'`) are the corpus rows that gate the boolean half.
 *
 * [Diagnostic.character] is the CLI's **1-based** column verbatim.
 */
class WeakObjectLiteralLeafTest {

    /**
     * THE HEADLINE — (CHK.58)'s item 4. tsc 7.0.2 over `build/chk59/pin/y5.ts`:
     * `(2,27): error TS2559: Type 'string' has no properties in common with type
     * '{ zzzA?: null | undefined; zzzF?: string | undefined; }'.` The parent emitted
     * TS2322 at `(2,7)`, the var NAME, naming the whole literal.
     */
    @Test
    fun `a string literal leaf against a weak property reports TS2559 at the key`() {
        val d = diagnose("""
            interface ZzzOut5 { zzzIn?: { zzzA?: null; zzzF?: string } }
            const zzzY5v: ZzzOut5 = { zzzIn: "utf8" };
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type 'string' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 2)
        assert(d[0].character == 27)
        assert(d[0].relatedInformation.map { it.code } == listOf(6500))
    }

    /**
     * A NUMERIC leaf widens too. tsc 7.0.2 over `d3.ts` line 5: `Type 'number'`.
     */
    @Test
    fun `a numeric literal leaf renders the widened type`() {
        val d = diagnose("""
            interface ZzzOut5 { zzzIn?: { zzzA?: null; zzzF?: string } }
            const zzzY5x: ZzzOut5 = { zzzIn: 12 };
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type 'number' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].character == 27)
    }

    /**
     * THE BOUNDARY — a BOOLEAN literal does NOT widen, which is what pristine's
     * `nestedExcessPropertyChecking` lines 30 and 40 pin. tsc 7.0.2 over `d3.ts` line 4:
     * `Type 'false'`. This is the pin that makes the widening a RULE rather than
     * "literals widen".
     */
    @Test
    fun `a boolean literal leaf keeps the literal type`() {
        val d = diagnose("""
            interface ZzzOut5 { zzzIn?: { zzzA?: null; zzzF?: string } }
            const zzzY5w: ZzzOut5 = { zzzIn: false };
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type 'false' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].character == 27)
    }

    /**
     * A TEMPLATE literal leaf renders `string` — the same widening, and the shape that
     * shows it is about the literal's TYPE and not about the token. tsc 7.0.2 over
     * `d4.ts` line 5.
     */
    @Test
    fun `a template literal leaf renders string`() {
        val d = diagnose("""
            interface ZzzOut6 { zzzIn?: { zzzA?: null; zzzF?: string } }
            const zzzC: ZzzOut6 = { zzzIn: `tpl` };
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type 'string' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].character == 25)
    }

    /**
     * AN ENUM MEMBER at a leaf — the same member-less-`Type.Object` refusal (CHK.59)
     * closed at every other position, and the same (CHK.58) display rule. tsc 7.0.2
     * over `d4.ts` line 4: `Type 'ZzzEL.A'`.
     */
    @Test
    fun `an enum member leaf reports TS2559 naming the member`() {
        val d = diagnose("""
            enum ZzzEL { A = "A", B = "B" }
            interface ZzzOut6 { zzzIn?: { zzzA?: null; zzzF?: string } }
            const zzzB: ZzzOut6 = { zzzIn: ZzzEL.A };
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type 'ZzzEL.A' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 3)
        assert(d[0].character == 25)
    }

    /**
     * CONTROL — the TOP-LEVEL var-decl position keeps the UNWIDENED literal, which is
     * the asymmetry this round measured and pristine's
     * `nestedExcessPropertyChecking.errors.txt` line 18 gates (`Type '"A"'`). Without
     * this pin, "widen the literal" reads as a global rule.
     */
    @Test
    fun `negative control - a top level literal source is not widened`() {
        val d = diagnose("""
            let zzzYY: { zzzNope?: any } = "A";
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type '\"A\"' has no properties in common with type " +
            "'{ zzzNope?: any; }'.")
    }

    /**
     * CONTROL — a NON-object-literal source still takes the one-level nested walker's
     * TS2322 at the var NAME with its chain, which is pristine's
     * `weakType.errors.txt` line 62 shape. The order change must not move it.
     */
    @Test
    fun `negative control - an identifier source keeps the nested TS2322 at the var name`() {
        val d = diagnose("""
            type ZzzSpoil = { zzzNope?: string }
            type ZzzWeak = { zzzA?: number; zzzProps?: { zzzB?: number } }
            declare let zzzWrong: { zzzProps: { zzzWrong: string } }
            let zzzWk: ZzzWeak & ZzzSpoil = zzzWrong
        """)
        assert(d.map { it.code } == listOf(2322))
        assert(d[0].line == 4)
        assert(d[0].character == 5)
    }
}
