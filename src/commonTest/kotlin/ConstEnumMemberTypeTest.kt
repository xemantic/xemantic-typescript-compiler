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
 * (REL.2) round 783 — a `const` binding keeps the ENUM MEMBER type of its
 * initializer, exactly as it keeps a string/number literal.
 *
 * tsc's gate is one rule for both: `getWidenedLiteralTypeForInitializer` keeps the
 * type for a `NodeFlags.Constant` declaration and otherwise calls
 * `getWidenedLiteralType`, and it is `getWidenedLiteralType` that turns an
 * `EnumLiteral` into its whole enum. Round 781 landed the literal half and
 * deliberately kept widening the ENUM half, because keeping it exposed
 * `completions.ts:2239`; that line is in (REL.2)'s residual either way, and widening
 * is what made `scanner.ts:905` and `program.ts:1366` unfixable — both are
 * `const x = cond ? E.A : E.B` against a two-member target.
 *
 * EVERY POSITIVE PIN HERE DISCRIMINATES BY MESSAGE, and it has to: while the
 * enum -> MEMBER relation direction is still decided vacuously, a whole enum IS
 * assignable to one of its own members, so a silence-asserting pin passes on the
 * broken build too. The probe target is therefore a PRIMITIVE (`string`), which
 * rejects every enum-flavored type and NAMES the one it was given.
 */
class ConstEnumMemberTypeTest {

    private val prelude = """
        enum K { A, B, C, D }
        declare function cond(): boolean;
    """.trimIndent() + "\n"

    /**
     * DISCRIMINATES BY MESSAGE — `'K.A'` fixed, `'K'` ablated.
     */
    @Test
    fun `a const initialised from an enum member keeps the member type`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(): string {
                    const k = K.A;
                    const s: string = k;
                    return s;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 && it.message == "Type 'K.A' is not assignable to type 'string'." })
    }

    /**
     * DISCRIMINATES BY MESSAGE — `'K.A | K.B'` fixed, `'K'` ablated. This is
     * `scanner.ts:905`'s and `program.ts:1366`'s shape.
     */
    @Test
    fun `a const initialised from a conditional of two enum members keeps the member union`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(): string {
                    const k = cond() ? K.A : K.B;
                    const s: string = k;
                    return s;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 && it.message == "Type 'K.A | K.B' is not assignable to type 'string'." })
    }

    /**
     * DISCRIMINATES BY MESSAGE — a three-arm chain, the shape `scanner.ts` and
     * `program.ts` both write, with the members in DECLARATION order.
     */
    @Test
    fun `a const initialised from a nested conditional chain keeps every member`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(): string {
                    const k = cond() ? K.A : cond() ? K.B : K.C;
                    const s: string = k;
                    return s;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 && it.message == "Type 'K.A | K.B | K.C' is not assignable to type 'string'." })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — a MUTABLE binding must still widen, or
     * `let flags = E.None; flags = E.Other` stops being legal. Asserted by MESSAGE so
     * it FIRES rather than passing by silence.
     */
    @Test
    fun `a let binding still widens an enum member to its enum`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(): string {
                    let k = K.A;
                    const s: string = k;
                    return s;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2322 && it.message == "Type 'K' is not assignable to type 'string'." })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — the reason the `let` arm above exists: a
     * flags accumulator, which is how tsc's own sources initialise one.
     */
    @Test
    fun `a let flags accumulator still accepts another member`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(): number {
                    let flags = K.A;
                    flags = K.B;
                    return flags;
                }
                """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — assigning to a `const` is TS2588 and tsc adds
     * NO assignability error there (`checkReferenceExpression` returns false), so the
     * kept member must be widened back at an assignment TARGET. Round 781 built
     * `widen1ConstLiteralTypeIds` for the literal half; the enum half joins it.
     */
    @Test
    fun `assigning another member to a const enum binding does not co-emit TS2322`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(): number {
                    const k = K.A;
                    k = K.B;
                    return k;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2588 })
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — a `const` whose initializer is a plain STRING
     * literal is round 781's slice and must be untouched by this one.
     */
    @Test
    fun `a const string literal is unaffected`() {
        val diagnostics = diagnose(
            """
            const k = "a";
            const j: "a" = k;
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }
}
