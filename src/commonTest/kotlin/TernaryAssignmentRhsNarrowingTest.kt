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
 * Pins the round-464 invariant: an assignment RHS that is a TERNARY proves
 * the assigned reference non-nullish iff BOTH arms do
 * ([rhsIsDefinitelyNonNullish]'s ConditionalExpression arm). tsc's
 * getTypeAtFlowNode assigns `type = flags & BranchLabel ?
 * getTypeAtFlowBranchLabel(flow) : getTypeAtFlowLoopLabel(flow)` (both arms
 * return the non-nullish FlowType alias) and later `return type;` — without
 * the arm the declared `FlowType | undefined` survived to the return and
 * FP'd TS2322 (checker.ts:29132).
 */
class TernaryAssignmentRhsNarrowingTest {

    private val prelude = """
        interface Ty { id: number; }
        interface Inc { flags: 0; type: Ty; }
        type FlowType = Ty | Inc;
        declare function branch(): FlowType;
        declare function loop(): FlowType;
        declare function maybe(): FlowType | undefined;
        declare const cond: boolean;
    """.trimIndent() + "\n"

    @Test
    fun `a ternary RHS with two non-nullish call arms proves the reference non-nullish`() {
        diagnose(prelude + """
            function f(): FlowType {
                let type: FlowType | undefined;
                if (cond) {
                    type = cond ? branch() : loop();
                }
                else {
                    type = branch();
                }
                return type;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a ternary arm returning a nullable type keeps the undefined`() {
        diagnose(prelude + """
            function f(): FlowType {
                let type: FlowType | undefined;
                if (cond) {
                    type = cond ? branch() : maybe();
                }
                else {
                    type = branch();
                }
                return type;
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `nested ternary arms all non-nullish still prove non-nullish`() {
        diagnose(prelude + """
            function f(n: number): FlowType {
                let type: FlowType | undefined;
                type = n > 0 ? branch() : n < 0 ? loop() : branch();
                return type;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }
}
