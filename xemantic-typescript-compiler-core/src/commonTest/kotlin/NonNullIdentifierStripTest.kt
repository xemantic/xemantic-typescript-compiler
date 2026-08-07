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
 * Round 456: `getTypeOfExpression(NonNullExpression)` strips nullish from a bare
 * IDENTIFIER `x!` when the union is all-CONCRETE (no un-inferred type param) — the
 * identifier sibling of round 439's `<call>()!` strip. Fixes tsc's
 * `writer = _writer!` (emitter.ts, `_writer: EmitTextWriter | undefined`) and
 * `currentFlow = preSwitchCaseFlow!` (binder.ts, `FlowNode | undefined`).
 *
 * Deliberately NOT applied to a property-access `.x!` (the object-literal-vs-interface
 * member-precision gap the round-407 note documents) nor a TP-carrying operand (the
 * generic-inference gap).
 */
class NonNullIdentifierStripTest {

    @Test
    fun `NonNull on a param identifier strips undefined in an assignment`() {
        diagnose(
            """
            interface Writer { write(): void; }
            let writer: Writer = { write() {} };
            function g(_writer: Writer | undefined) {
                writer = _writer!;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `NonNull on a module-var identifier strips undefined in an assignment`() {
        diagnose(
            """
            interface FlowNode { id: number; }
            let currentFlow: FlowNode = { id: 0 };
            let preSwitchCaseFlow: FlowNode | undefined = undefined;
            function f() {
                currentFlow = preSwitchCaseFlow!;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `NonNull on an identifier strips undefined in a return`() {
        diagnose(
            """
            interface FlowNode { id: number; }
            function f(n: FlowNode | undefined): FlowNode {
                return n!;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `pure-nullish NonNull is never - still assignable everywhere`() {
        // `undefined!` / `null!` = never — assignable to anything (B282); no regression.
        diagnose(
            """
            interface FlowNode { id: number; }
            function f(): FlowNode {
                return undefined!;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }
}
