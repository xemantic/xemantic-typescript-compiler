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
 * (CHK.59) AN **ENUM MEMBER** IS A WEAK-RULE SOURCE AT EVERY POSITION, NOT ONLY AT A
 * VAR DECL.
 *
 * [Checker.getTypeOfExpression] answers `any` for `E.A`, and `any` is the one thing
 * [Checker.weakSourcePropertyNames] refuses outright — so every position that types its
 * value through it (the CALL ARGUMENT, and since (CHK.58) the RETURN and the ASSIGNMENT)
 * was silent, while the var-decl walker alone carried the AST-side
 * [Checker.enumMemberWeakSource] and reported. The fix is to consult that same AST
 * classifier wherever the typed value comes back `any`.
 *
 * THE DISPLAY RULE IS (CHK.58)'S AND IT IS UNCHANGED — tsc names the enum-LITERAL type,
 * which for a **one-member** enum IS the enum type, so `typeToString` prints the enum's
 * bare name; for two or more members it prints `E.A`. The boundary is the member COUNT,
 * not the flavour (string vs numeric), and pristine's own
 * `nestedExcessPropertyChecking.errors.txt` line 17 (`Type 'E'`, over a one-member enum)
 * is the corpus row that gates it.
 *
 * Every expected value read out of tsc 7.0.2 over `build/chk59/pin/q08.ts`, `q09.ts` and
 * `q10.ts`; [Diagnostic.character] is the CLI's **1-based** column verbatim.
 */
class WeakEnumSourcePositionsTest {

    /**
     * THE HEADLINE — a multi-member enum member at a CALL ARGUMENT, the row (CHK.58)
     * recorded as open. tsc 7.0.2: `q10.ts(3,9)` naming `'ZzzQ10.A'`.
     */
    @Test
    fun `an enum member call argument reports TS2559 naming the member`() {
        val d = diagnose("""
            enum ZzzQ10 { A = "a", B = "b" }
            declare function zzzQ10g(o: { zzzA?: null; zzzF?: string }): void;
            zzzQ10g(ZzzQ10.A);
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type 'ZzzQ10.A' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 3)
        assert(d[0].character == 9)
    }

    /**
     * A NUMERIC enum member at a call argument — the flavour does not decide the
     * display. tsc 7.0.2: `q10.ts(6,9)` naming `'ZzzQ11.A'`.
     */
    @Test
    fun `a numeric enum member call argument names the member too`() {
        val d = diagnose("""
            enum ZzzQ11 { A, B }
            declare function zzzQ11g(o: { zzzA?: null; zzzF?: string }): void;
            zzzQ11g(ZzzQ11.A);
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type 'ZzzQ11.A' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 3)
        assert(d[0].character == 9)
    }

    /**
     * THE MEMBER-COUNT BOUNDARY at the argument position — a ONE-member enum renders
     * the enum's bare name. tsc 7.0.2: `q09.ts(6,9)` naming `'ZzzQ09'`.
     */
    @Test
    fun `a one member enum call argument names the enum itself`() {
        val d = diagnose("""
            enum ZzzQ09 { A = "a" }
            declare function zzzQ09g(o: { zzzA?: null; zzzF?: string }): void;
            zzzQ09g(ZzzQ09.A);
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type 'ZzzQ09' has no properties in common with type " +
            "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'.")
        assert(d[0].line == 3)
        assert(d[0].character == 9)
    }

    /**
     * THE RETURN AND ASSIGNMENT POSITIONS, multi-member enum — TS2322 before, with the
     * right display and the wrong CODE. tsc 7.0.2: `q08.ts(2,54)` at the `return`
     * keyword and `q08.ts(4,1)` at the LHS.
     */
    @Test
    fun `an enum member reports TS2559 at the return keyword and the assignment LHS`() {
        val d = diagnose("""
            enum ZzzQ08 { A = "a", B = "b" }
            function zzzQ08f(): { zzzA?: null; zzzF?: string } { return ZzzQ08.A; }
            let zzzQ08v: { zzzA?: null; zzzF?: string } = {}
            zzzQ08v = ZzzQ08.A
        """)
        assert(d.map { it.code } == listOf(2559, 2559))
        assert(d.all {
            it.message == "Type 'ZzzQ08.A' has no properties in common with type " +
                "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'."
        })
        assert(d.map { it.line } == listOf(2, 4))
        assert(d.map { it.character } == listOf(54, 1))
    }

    /**
     * THE MEMBER-COUNT BOUNDARY at the return and assignment positions. tsc 7.0.2:
     * `q09.ts(2,54)` and `q09.ts(4,1)`, both naming `'ZzzQ09'`.
     */
    @Test
    fun `a one member enum names the enum itself at the return and assignment positions`() {
        val d = diagnose("""
            enum ZzzQ09 { A = "a" }
            function zzzQ09f(): { zzzA?: null; zzzF?: string } { return ZzzQ09.A; }
            let zzzQ09v: { zzzA?: null; zzzF?: string } = {}
            zzzQ09v = ZzzQ09.A
        """)
        assert(d.map { it.code } == listOf(2559, 2559))
        assert(d.all {
            it.message == "Type 'ZzzQ09' has no properties in common with type " +
                "'{ zzzA?: null | undefined; zzzF?: string | undefined; }'."
        })
        assert(d.map { it.line } == listOf(2, 4))
        assert(d.map { it.character } == listOf(54, 1))
    }

    /**
     * CONTROL — an enum member whose enum SHARES a property name with the target is
     * silent, so the rule is still the common-property one and not "an enum member is
     * always refused". A string enum's apparent type is `String`, so the shared name
     * has to come from the wrapper: `length` is on `String` and this target declares
     * it optional. tsc 7.0.2 is silent over the whole fixture.
     */
    @Test
    fun `negative control - an enum member sharing a wrapper property is silent`() {
        val d = diagnose("""
            enum ZzzQ0C { A = "a", B = "b" }
            declare function zzzQ0Cg(o: { length?: number }): void;
            zzzQ0Cg(ZzzQ0C.A);
            let zzzQ0Cv: { length?: number } = {}
            zzzQ0Cv = ZzzQ0C.A
        """)
        assert(d.none { it.code == 2559 || it.code == 2560 })
    }
}
