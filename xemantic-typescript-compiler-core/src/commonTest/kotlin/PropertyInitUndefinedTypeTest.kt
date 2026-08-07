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
 * Round 443 (self-compile burn-down): tsc's `strictPropertyInitialization` exempts a class
 * property whose declared type INCLUDES `undefined` (`getFalsyFlags(type) & TypeFlags.Undefined`)
 * — such a property starts with a legal value (undefined), so it needs no initializer / definite
 * assignment. services.ts's `SourceFileObject` declares `public nameTable: Map<...> | undefined;`
 * (and 5 siblings) exactly this way; we FP-fired TS2564 on all of them.
 */
class PropertyInitUndefinedTypeTest {

    @Test
    fun `property typed T or undefined needs no initializer - no TS2564`() {
        diagnose(
            """
            class C {
                nameTable: string | undefined;
                checkJsDirective: number | undefined;
                localJsxFactory: { a: number } | undefined;
                constructor() {}
            }
            """
        ) should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `bare undefined-typed property needs no initializer - no TS2564`() {
        diagnose(
            """
            class C {
                nothing: undefined;
                constructor() {}
            }
            """
        ) should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `negative control - a non-undefined property still fires TS2564`() {
        diagnose(
            """
            class C {
                required: string;
                also: number | null;
                constructor() {}
            }
            """
        ) should {
            // `required: string` and `also: number | null` (null is NOT undefined) both fire.
            have(any { it.code == 2564 && it.message.contains("required") })
            have(any { it.code == 2564 && it.message.contains("also") })
        }
    }
}
