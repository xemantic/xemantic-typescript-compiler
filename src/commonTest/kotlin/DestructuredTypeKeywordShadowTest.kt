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
 * A DESTRUCTURING declaration whose element name is a primitive type keyword
 * (`const { symbol } = node; symbol.foo`) binds `symbol` as a VALUE, shadowing the `symbol`
 * type keyword — so the value use must NOT fire TS2693 "'symbol' only refers to a type, but
 * is being used as a value here." The value-name hoisting in `checkTypeAsValueInStatements`
 * only added simple `Identifier` declaration names; it now also extracts binding-pattern
 * element names (via `addParamBindingNamesToValues`). This was 6 self-compile TS2693 FPs
 * (binder.ts's `const { symbol } = node; symbol.constEnumOnlyModule = …`).
 */
class DestructuredTypeKeywordShadowTest {

    @Test
    fun `object-destructured symbol shadows the type keyword - no TS2693`() {
        diagnose(
            """
            interface Node { symbol: { flags: number }; }
            function bind(node: Node): void {
                const { symbol } = node;
                symbol.flags = 1;
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `array-destructured number shadows the type keyword - no TS2693`() {
        diagnose(
            """
            function use(arr: number[]): number {
                const [ number ] = arr;
                return number + 1;
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `renamed destructure binds the LOCAL name as a value - no TS2693`() {
        diagnose(
            """
            interface Node { s: { flags: number }; }
            function bind(node: Node): void {
                const { s: symbol } = node;
                symbol.flags = 1;
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2693 })
        }
    }

    @Test
    fun `the bare symbol type keyword used as a value STILL fires TS2693 (negative control)`() {
        // No binding — `symbol` is the type keyword used in a value position, which is TS2693.
        diagnose(
            """
            function bad(): void {
                symbol.foo = 1;
            }
            """,
            directives = "",
        ) should {
            have(any { it.code == 2693 && it.message.contains("'symbol'") })
        }
    }
}
