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
 * Round 463: `checkArrayLiteralElementExcessProps`' primitive-element-vs-object
 * branch skipped Null/Undefined/Void/Any element types but not NEVER — a `never`
 * source (e.g. `undefined!`, which types as `never` per B282) is assignable to
 * EVERYTHING, yet `[undefined!]` against an interface-element array target FP'd
 * "Type 'never' is not assignable to type 'Expression'." (tsc's own
 * transformers/taggedTemplate.ts `const templateArguments: Expression[] =
 * [undefined!]`).
 */
class NeverArrayElementTest {

    @Test
    fun `an undefined-bang element is never and assignable to an interface-element array`() {
        diagnose("""
            interface Expression { kind: number; expr: string; }
            const templateArguments: Expression[] = [undefined!];
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a null-bang element mixed with valid elements stays clean`() {
        diagnose("""
            interface Expr { kind: number; }
            declare const e: Expr;
            const list: Expr[] = [null!, e, undefined!];
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a genuine primitive element vs interface target still fires TS2322`() {
        diagnose("""
            interface Expr { kind: number; }
            const list: Expr[] = [42];
        """) should {
            have(any { it.code == 2322 })
        }
    }
}
