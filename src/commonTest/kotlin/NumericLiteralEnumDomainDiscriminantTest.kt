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
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 459: an ENUM-typed discriminant compared with a NUMERIC literal narrows
 * by the enum's TYPE-LEVEL domain (the union of member literal values) — tsc's
 * `FlowType = Type | IncompleteType` idiom: `flowType.flags === 0` drops the
 * `Type` member because `TypeFlags` has NO 0-valued member, keeping
 * `IncompleteType` (whose `flags` is the literal type 0), so `flowType.type`
 * type-checks (checker.ts getTypeFromFlowType / isIncomplete).
 *
 * Runtime bitflag COMBINATIONS are outside the declared domain — tsc does not
 * model them either, so narrowing by the member-value domain is faithful.
 */
class NumericLiteralEnumDomainDiscriminantTest {

    @Test
    fun `literal 0 vs an enum with no 0-valued member narrows to the literal-discriminant member - no TS2339`() {
        diagnose("""
            const enum TypeFlags { Any = 1, Unknown = 2, String = 4 }
            interface Ty { flags: TypeFlags; id: number; }
            interface IncompleteType { flags: 0; type: Ty; }
            type FlowType = Ty | IncompleteType;
            function getTypeFromFlowType(flowType: FlowType) {
                return flowType.flags === 0 ? flowType.type : flowType as Ty;
            }
        """.trimIndent()) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative branch keeps the enum member - no TS2339 on the enum side`() {
        diagnose("""
            const enum TypeFlags { Any = 1, Unknown = 2 }
            interface Ty { flags: TypeFlags; id: number; }
            interface IncompleteType { flags: 0; type: Ty; }
            function f(flowType: Ty | IncompleteType) {
                if (flowType.flags !== 0) {
                    return flowType.id;
                }
                return flowType.type.id;
            }
        """.trimIndent()) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - an enum WITH a 0-valued member does not narrow, access still fires`() {
        // WithZero.Zero == 0, so `x.flags === 0` cannot exclude T2 —
        // `.type` on the surviving union must keep firing.
        diagnose("""
            const enum WithZero { Zero = 0, One = 1 }
            interface T2 { flags: WithZero; id: number; }
            interface I2 { flags: 0; type: T2; }
            function neg(x: T2 | I2) {
                return x.flags === 0 ? x.type : undefined;
            }
        """.trimIndent()) should {
            have(any { it.code == 2339 })
        }
    }
}
