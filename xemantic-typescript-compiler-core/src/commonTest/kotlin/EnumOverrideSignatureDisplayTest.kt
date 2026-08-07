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
 * (REL.1)(c) round 747: tsc's `getTypeNamesForErrorDisplay` retry reaches an enum NESTED
 * inside a rendered function type, which is what retires B463's piece B
 * (the TS2416 half of `encmCheckClassesAndOverloads`).
 *
 * Round 746 made the relation reject two DISTINCT enums, so the general class-member
 * override check already reached B463's verdict at B463's position — measured, and guarded
 * from both sides so the walker kept winning. What it could not do was PRINT it: both
 * signatures rendered as the bare `(param: E) => void`, so the message said a type was not
 * assignable to itself. The rule is one flag ([enumDisplayFullyQualified]) consulted by
 * `typeToString` at ANY depth, turned on only by [enumQualifiedRelationDisplays] — the same
 * collision retry round 746 wrote for a top-level enum pair, SPLIT rather than widened
 * because that one is fed displays which may come from annotation text.
 *
 * **PIN PLACEMENT IS LOAD-BEARING.** B463's piece B saw only TOP-LEVEL class declarations
 * whose overriding and overridden methods each had EXACTLY ONE parameter. So:
 *  - a pin on classes inside a NAMESPACE, or on a two-parameter method, measures the RULE
 *    (the walker could never have answered it — on the pre-747 build both sides print `E`);
 *  - a pin in B463's own top-level single-parameter shape measures the RETIREMENT: it must
 *    pass on the pre-747 build AND here, byte-identically, chain included.
 *
 * The retry is COLLISION-GATED, exactly as tsc's is: two enums whose simple names differ
 * keep their bare names (`enumAssignmentCompat6` pins that tsc prints
 * `DiagnosticCategory` / `DiagnosticCategory2` bare although both are namespace-nested),
 * and a non-enum pair is self-gating — re-rendering under the flag returns the same string,
 * so the caller keeps its originals.
 *
 * NON-VACUITY IS MEASURED, not argued: all six pins were run against a build of unmodified
 * `fa3308a4`. The three rule pins fail there (both sides print the bare `E`), the two
 * retirement/self-gating pins pass on BOTH builds byte-identically — and the fourth failure
 * is the interesting one. `negative control - two differently named enums keep their bare
 * names` fails on the PRE-747 build in the OTHER direction: B463 rendered every enum in its
 * chain through `EnumAsgInfo.qualifiedDisplay`, which qualifies unconditionally, so it
 * printed `(param: third.Other) => void` where tsc prints `(param: Other) => void`.
 * **Retiring piece B therefore also removes a display divergence**, as retiring B203, B425
 * and B266 each removed a false positive.
 */
class EnumOverrideSignatureDisplayTest {

    private val enums = """
        namespace first { export enum E { A = 1 } }
        namespace second { export enum E { A = 2 } }
        namespace third { export enum Other { A = 2 } }
        namespace one { export interface I { a: number } }
        namespace two { export interface I { b: string } }

    """.trimIndent()

    private fun overrideChain(source: String): List<String> {
        val diagnostics = diagnose(enums + source).filter { it.code == 2416 }
        assert(diagnostics.size == 1)
        return diagnostics[0].messageChain
    }

    // --- the RULE: shapes B463's piece B could never see ---------------------

    @Test
    fun `an override inside a namespace qualifies both enums in the rendered signature`() {
        assert(
            overrideChain(
                """
                namespace wrap {
                    export class Base { method(param: first.E) {} }
                    export class Derived extends Base { override method(param: second.E) {} }
                }
                """,
            ) == listOf(
                "  Type '(param: second.E) => void' is not assignable to type '(param: first.E) => void'.",
                "    Types of parameters 'param' and 'param' are incompatible.",
                "      Type 'first.E' is not assignable to type 'second.E'.",
            ),
        )
    }

    @Test
    fun `a two parameter override qualifies the colliding enum and leaves the rest alone`() {
        assert(
            overrideChain(
                """
                class Base { method(a: number, param: first.E) {} }
                class Derived extends Base { override method(a: number, param: second.E) {} }
                """,
            ) == listOf(
                "  Type '(a: number, param: second.E) => void' is not assignable to " +
                    "type '(a: number, param: first.E) => void'.",
                "    Types of parameters 'param' and 'param' are incompatible.",
                "      Type 'first.E' is not assignable to type 'second.E'.",
            ),
        )
    }

    @Test
    fun `an enum MEMBER nested in a signature qualifies through its enum too`() {
        assert(
            overrideChain(
                """
                namespace wrap {
                    export class Base { method(param: first.E.A) {} }
                    export class Derived extends Base { override method(param: second.E.A) {} }
                }
                """,
            ) == listOf(
                "  Type '(param: second.E.A) => void' is not assignable to type '(param: first.E.A) => void'.",
                "    Types of parameters 'param' and 'param' are incompatible.",
                "      Type 'first.E.A' is not assignable to type 'second.E.A'.",
            ),
        )
    }

    // --- the FP firewall: the retry is collision-gated -----------------------

    @Test
    fun `negative control - two differently named enums keep their bare names`() {
        assert(
            overrideChain(
                """
                class Base { method(param: first.E) {} }
                class Derived extends Base { override method(param: third.Other) {} }
                """,
            ) == listOf(
                "  Type '(param: Other) => void' is not assignable to type '(param: E) => void'.",
                "    Types of parameters 'param' and 'param' are incompatible.",
                "      Type 'E' is not assignable to type 'Other'.",
            ),
        )
    }

    @Test
    fun `negative control - a colliding INTERFACE pair is left on its bare display`() {
        assert(
            overrideChain(
                """
                class Base { method(param: one.I) {} }
                class Derived extends Base { override method(param: two.I) {} }
                """,
            ) == listOf(
                "  Type '(param: I) => void' is not assignable to type '(param: I) => void'.",
                "    Types of parameters 'param' and 'param' are incompatible.",
                "      Type 'I' is not assignable to type 'I'.",
            ),
        )
    }

    // --- the RETIREMENT: B463 piece B's own shape ---------------------------

    @Test
    fun `a top level single parameter override keeps B463s message chain and position`() {
        val source = enums + """
            class Base { method(param: first.E) {} }
            class Derived extends Base { override method(param: second.E) {} }
        """.trimIndent()
        val diagnostics = diagnose(source).filter { it.code == 2416 }
        assert(diagnostics.size == 1)
        assert(
            diagnostics[0].message ==
                "Property 'method' in type 'Derived' is not assignable to the same property in base type 'Base'.",
        )
        assert(
            diagnostics[0].messageChain == listOf(
                "  Type '(param: second.E) => void' is not assignable to type '(param: first.E) => void'.",
                "    Types of parameters 'param' and 'param' are incompatible.",
                "      Type 'first.E' is not assignable to type 'second.E'.",
            ),
        )
        val expectedStart = source.indexOf("override method") + "override ".length
        assert(diagnostics[0].start == expectedStart)
        assert(diagnostics[0].length == "method".length)
    }
}
