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
 * Round 442 — an OVERLOADED function passed as a callback argument is arity-incompatible with a
 * single-signature callback target only when EVERY overload requires more arguments than the
 * target provides (tsc picks a matching overload). The arg-check's `allowArityMismatch` gate used
 * `callSignatures.first()`, so `tryCast(x, isAssignmentExpression)` — where the 1st overload has 2
 * required params but the 2nd's 2nd param is OPTIONAL (minArgumentCount 1) — wrongly reported "too
 * few arguments" against the 1-param `(value: TIn) => value is TOut` target. Now uses the MIN
 * minArgumentCount across all overloads. Cleared 2 self-compile TS2345 FPs (es2015.ts's decorator
 * IIFE `tryCast(initializer, isAssignmentExpression)`).
 */
class OverloadedCallbackArityTest {

    @Test
    fun `overloaded fn arg with an optional-param overload matches a 1-arg callback - no TS2345`() {
        diagnose(
            """
            declare function tryCast<TOut extends TIn, TIn = unknown>(
                value: TIn | undefined, test: (value: TIn) => value is TOut): TOut | undefined;
            interface Node { kind: number; }
            interface AssignExpr extends Node { op: number; }
            declare function isAssign(node: Node, excludeCompound: true): node is AssignExpr;
            declare function isAssign(node: Node, excludeCompound?: false): node is AssignExpr;
            declare const init: Node | undefined;
            const x = tryCast(init, isAssign);
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `overloaded fn arg whose every overload needs 2 args fails a 1-arg callback - TS2345`() {
        // Negative control: BOTH overloads require 2 args (min minArgumentCount 2 > target 1), so
        // the arity mismatch still fires.
        diagnose(
            """
            declare function apply1(test: (value: number) => boolean): void;
            declare function needsTwo(a: number, b: number): boolean;
            declare function needsTwo(a: number, b: string): boolean;
            apply1(needsTwo);
            """,
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
