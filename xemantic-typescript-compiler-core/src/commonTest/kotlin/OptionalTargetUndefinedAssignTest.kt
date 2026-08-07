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
 * Round 453: `<ident> = undefined` where the target's DECLARED type includes
 * undefined is legal — an OPTIONAL parameter `x?: T` (effective type
 * `T | undefined`, tsc B85.1a) or a `T | undefined` local. The identifier-target
 * assignment check resolves the target type from the string `varTypes` map, which
 * drops the optional `?` (resolveSimpleTypeName → "T"), so the relation FP-fired
 * TS2322. tsc's own generators.ts (`leadingElement = undefined` where
 * `leadingElement?: Expression`) trips this.
 *
 * Fix: bail when the RHS is literally `undefined`, `!exactOptionalPropertyTypes`,
 * and the target's declared engine type (currentLocalTypes, or its pre-narrowing
 * form narrowedDeclaredTypes) includes undefined. FP-safe: a non-optional `x: T`
 * target keeps firing.
 */
class OptionalTargetUndefinedAssignTest {

    @Test
    fun `assigning undefined to an optional param is legal - no TS2322`() {
        diagnose(
            """
            interface Expr { kind: number }
            export function f(leadingElement?: Expr): void {
                leadingElement = undefined;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `assigning undefined to a primitive optional param is legal - no TS2322`() {
        diagnose(
            """
            export function g(x?: number): void { x = undefined; }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `assigning undefined to a T-or-undefined local is legal - no TS2322`() {
        diagnose(
            """
            export function h(): void {
                let s: string | undefined = "a";
                s = undefined;
                void s;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - assigning undefined to a non-optional param fires TS2322`() {
        diagnose(
            """
            export function neg(x: number): void { x = undefined; }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - assigning undefined to a non-optional local fires TS2322`() {
        diagnose(
            """
            export function neg(): void {
                let n: number = 1;
                n = undefined;
                void n;
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
