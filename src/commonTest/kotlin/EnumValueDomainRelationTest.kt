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
 * (REL.1)(c) round 745: the relation judges a numeric LITERAL against an enum by
 * VALUE, which is what let `checkEnumLiteralAssignments` (B203) be retired.
 *
 * tsc's rule, from `isSimpleTypeRelatedTo`: the wide `number` is assignable to any
 * numeric enum or enum member (the bit-flag compatibility rule), but a numeric
 * literal is assignable only to a member whose value it EQUALS — or to any member
 * of an enum whose value domain is not fully known, which is tsc's *computed* enum.
 *
 * EVERY POSITIVE PIN HERE IS WRITTEN INSIDE A FUNCTION BODY ON PURPOSE. The retired
 * walker scanned only TOP-LEVEL `var x: E = <lit>` declarations and top-level
 * `x = <lit>` assignments, so a top-level pin would have passed on the pre-745 build
 * too and measured nothing. The corpus test `enumAssignmentCompat5` keeps the
 * top-level form; these state the general rule the relation now owns.
 */
class EnumValueDomainRelationTest {

    private val enums = """
        enum E { A, B, C }
        enum Computed { A = 1 << 1, B = 1 << 2, C = 1 << 3 }
        enum Str { A = "a", B = "b" }
        declare let n: number
    """.trimIndent() + "\n"

    @Test
    fun `a numeric literal outside an enum member's value is not assignable to that member`() {
        diagnose(
            enums +
                """
                function f(): void {
                    let a: E.A = 2
                }
                """,
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a numeric literal outside an enum's member values is not assignable to the enum`() {
        diagnose(
            enums +
                """
                function f(): void {
                    let e: E = 4
                }
                """,
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a numeric literal is not assignable to a member of a bit-shifted enum it does not equal`() {
        diagnose(
            enums +
                """
                function f(): void {
                    let c: Computed.A = 1
                }
                """,
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a numeric literal is not assignable to a string-valued enum`() {
        diagnose(
            enums +
                """
                function f(): void {
                    let s: Str = 4
                }
                """,
        ) should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a negated numeric literal outside the domain is not assignable`() {
        diagnose(
            enums +
                """
                function f(): void {
                    let e: E = -1
                }
                """,
        ) should { have(any { it.code == 2322 }) }
    }

    // ---------------------------------------------------------------------
    // The FP firewall. Each of these is a shape a value-BLIND tightening — or a
    // domain read as complete when it is not — would reject.
    // ---------------------------------------------------------------------

    @Test
    fun `negative control - a numeric literal equal to the member's value is assignable`() {
        diagnose(
            enums +
                """
                function f(): void {
                    let a: E.A = 0
                    let b: E.B = 1
                    let c: Computed.B = 4
                }
                """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - the wide number stays assignable to an enum and to a member`() {
        diagnose(
            enums +
                """
                function f(): void {
                    let e: E = n
                    let a: E.A = n
                    let c: Computed.A = n
                }
                """,
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - a bit-flag combination types as number and stays assignable`() {
        diagnose(
            """
            enum Flags { None = 0, X = 1, Y = 2 }
            function f(): void {
                let a: Flags = Flags.X | Flags.Y
                let b: Flags = 0
                let c: Flags = Flags.None
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * tsc declines to auto-number an initializer-less member of an AMBIENT non-const
     * enum — its members have no value, so the whole enum accepts any number. We DO
     * auto-number them (the Transformer needs a value to emit), which is exactly why
     * `enumValueDomainIsComplete` has to answer this question separately. The retired
     * walker had no ambient notion at all and reported all three of these.
     */
    @Test
    fun `negative control - an ambient enum without initializers accepts any number`() {
        diagnose(
            """
            declare enum D { X, Y }
            declare namespace NS { enum Q { X, Y } }
            function f(): void {
                let a: D = 7
                let b: NS.Q = 9
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * The two shapes that keep the ambient rule from swallowing everything: an
     * ambient CONST enum IS auto-numbered by tsc, and an ambient enum with explicit
     * initializers has a domain regardless.
     */
    @Test
    fun `an ambient const enum and an ambient enum with initializers keep their domain`() {
        val diagnostics = diagnose(
            """
            declare const enum C { X, Y }
            declare enum I { X = 0, Y = 1 }
            function f(): void {
                let a: C = 7
                let b: I = 7
            }
            """,
        )
        assert(diagnostics.count { it.code == 2322 } == 2)
    }

    @Test
    fun `negative control - an enum member is still assignable to its own enum`() {
        diagnose(
            enums +
                """
                function f(): void {
                    let e: E = E.B
                    let c: Computed = Computed.C
                }
                """,
        ) should { have(none { it.code == 2322 }) }
    }
}
