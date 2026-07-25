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

import com.xemantic.kotlin.test.have
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * M1.8 (round 388): TS7030/TS2366 gates aligned with tsc's exact rule
 * (checkAllCodePathsInNonVoidFunctionReturnOrThrow + checkReturnStatement):
 *
 * - TS7030 fires ONLY under `noImplicitReturns` — never under strict-only
 *   (both the mixed-return form at the annotation and the per-empty-return
 *   form; under strict an empty `return;` routes through the return-expression
 *   assignability check → TS2322 when undefined is not assignable, nothing
 *   when it is).
 * - TS2366 fires under `strictNullChecks` only when undefined is NOT
 *   assignable to the RESOLVED return type — an alias of a
 *   union-containing-undefined (`VisitResult<Node | undefined>`,
 *   enum-member `ResolutionMode`) draws nothing on implicit fall-through.
 */
class ImplicitReturnGatesTest {

    private fun assertNone(@Language("typescript") header: String, source: String, codes: Set<Int>, what: String) {
        have(
            diagnose(source, directives = header).none { it.code in codes },
            "$what must not draw ${codes.joinToString { "TS$it" }}",
        )
    }

    private fun assertHas(@Language("typescript") header: String, source: String, code: Int, what: String) {
        have(
            diagnose(source, directives = header).any { it.code == code },
            "$what must draw TS$code",
        )
    }

    @Test fun strictOnlyDirectUndefinedUnionMixedReturnsDrawsNothing() {
        assertNone(
            "// @strict: true",
            """
            declare const cond: boolean;
            function f(): number | undefined {
                if (cond) {
                    return 1;
                }
            }
            """.trimIndent(),
            setOf(7030, 2366),
            "strict-only fall-through with an undefined-including union return",
        )
    }

    @Test fun strictOnlyAliasUnionMixedReturnsDrawsNothing() {
        assertNone(
            "// @strict: true",
            """
            interface Node { kind: number; }
            type VisitResult<T> = T | readonly Node[];
            declare const cond: boolean;
            declare const n: Node;
            function f(): VisitResult<Node | undefined> {
                if (cond) {
                    return n;
                }
            }
            """.trimIndent(),
            setOf(7030, 2366),
            "strict-only fall-through with a generic-alias undefined-union return",
        )
    }

    @Test fun strictOnlyEnumMemberAliasWithUndefinedDrawsNothing() {
        assertNone(
            "// @strict: true",
            """
            enum MK { ESNext = 99, CommonJS = 1 }
            type Mode = MK.ESNext | MK.CommonJS | undefined;
            declare const cond: boolean;
            function f(): Mode {
                if (cond) {
                    return MK.ESNext;
                }
            }
            """.trimIndent(),
            setOf(7030, 2366),
            "strict-only fall-through with an enum-member alias carrying `| undefined`",
        )
    }

    @Test fun strictOnlyNonNullableMixedReturnsKeepsTs2366() {
        assertHas(
            "// @strict: true",
            """
            declare const cond: boolean;
            function f(): number {
                if (cond) {
                    return 1;
                }
            }
            """.trimIndent(),
            2366,
            "strict-only fall-through with a non-nullable return",
        )
    }

    @Test fun strictOnlyEmptyReturnAgainstNonNullableIsTs2322NotTs7030() {
        val d = diagnose(
            """
            declare const cond: boolean;
            function f(): number {
                if (cond) {
                    return 1;
                }
                return;
            }
            """.trimIndent(),
            directives = "// @strict: true",
        )
        have(
            d.any { it.code == 2322 } && d.none { it.code == 7030 },
            "strict empty return routes through return-expression assignability (TS2322), not TS7030",
        )
    }

    @Test fun strictOnlyEmptyReturnAgainstUndefinedUnionDrawsNothing() {
        assertNone(
            "// @strict: true",
            """
            declare const cond: boolean;
            function f(): number | undefined {
                if (cond) {
                    return 1;
                }
                return;
            }
            """.trimIndent(),
            setOf(7030, 2366, 2322),
            "strict empty return against an undefined-including union",
        )
    }

    @Test fun noImplicitReturnsMixedReturnsKeepsTs7030() {
        // strict OFF isolates tsc's branch 4: under strict+noImplicitReturns the
        // TS2366 branch wins first (undefined not assignable to number).
        assertHas(
            "// @strict: false\n// @noImplicitReturns: true",
            """
            declare const cond: boolean;
            function f(): number {
                if (cond) {
                    return 1;
                }
            }
            """.trimIndent(),
            7030,
            "noImplicitReturns (non-strict) fall-through with a non-nullable return",
        )
    }

    @Test fun strictPlusNoImplicitReturnsMixedReturnsIsTs2366() {
        assertHas(
            "// @strict: true\n// @noImplicitReturns: true",
            """
            declare const cond: boolean;
            function f(): number {
                if (cond) {
                    return 1;
                }
            }
            """.trimIndent(),
            2366,
            "strict + noImplicitReturns fall-through: tsc's TS2366 branch wins",
        )
    }

    @Test fun noImplicitReturnsEmptyReturnKeepsTs7030() {
        assertHas(
            "// @strict: false\n// @noImplicitReturns: true",
            """
            declare const cond: boolean;
            function f(): number {
                if (cond) {
                    return 1;
                }
                return;
            }
            """.trimIndent(),
            7030,
            "noImplicitReturns (non-strict) empty return in a non-nullable annotated fn",
        )
    }
}
