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
 * Round 448 (self-compile burn-down, services): a `this.optionalProp = undefined` write inside a
 * constructor/method is LEGAL — an optional property `p?: T` has write-type `T | undefined`
 * (non-exactOptionalPropertyTypes). The string-based `this.X = value` assignment path stored only the
 * bare declared type name (via `resolveSimpleTypeName`), dropping the `?`, so it FP-fired TS2322
 * "Type 'undefined' is not assignable to type 'T'" whenever the optional property's type was a bare
 * interface/class name (services.ts SymbolObject/NodeObject constructor field resets — ×~10 → 0).
 * An explicit `| undefined` in the declared type, or an array type, already passed the lenient string
 * relation, which is why only bare-interface-typed optionals FP'd.
 */
class ThisOptionalUndefinedWriteTest {

    @Test
    fun `this optionalProp = undefined in a constructor does not FP TS2322`() {
        diagnose(
            """
            interface Decl { x: number; }
            interface Tbl { y: number; }
            class SymObj {
                declarations?: Decl[];
                valueDeclaration?: Decl;
                members?: Tbl;
                parent?: SymObj;
                constEnumOnly: boolean | undefined;
                constructor() {
                    this.declarations = undefined;
                    this.valueDeclaration = undefined;
                    this.members = undefined;
                    this.parent = undefined;
                    this.constEnumOnly = undefined;
                }
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `firewall - undefined to a NON-optional property still FP-fires TS2322`() {
        diagnose(
            """
            interface Decl { x: number; }
            class C {
                required: Decl;
                num: number;
                constructor() {
                    this.required = undefined;
                    this.num = undefined;
                }
            }
            """
        ) should {
            // both non-optional writes must still fire
            have(any { it.code == 2322 && it.message.contains("'Decl'") })
            have(any { it.code == 2322 && it.message.contains("'number'") })
        }
    }

    @Test
    fun `firewall - undefined to an optional property under exactOptionalPropertyTypes still FP-fires`() {
        diagnose(
            """
            interface Decl { x: number; }
            class C {
                opt?: Decl;
                constructor() { this.opt = undefined; }
            }
            """,
            directives = "// @strict: true\n// @exactOptionalPropertyTypes: true",
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
