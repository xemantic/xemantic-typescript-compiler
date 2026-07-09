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
 * Round 452 (self-compile burn-down): a NUMERIC-literal element access on a
 * STRING-typed receiver (`str[0]`) must NOT FP-fire TS2339. Element access on a
 * primitive is TS7053 under noImplicitAny and SILENT otherwise; the
 * property-existence TS2339 path is property-access only.
 *
 * ROOT CAUSE: the B292 bail suppressed only NON-numeric string keys (`s["s"]`); a
 * numeric key `[0]` fell through to the missing-member check. A plain identifier
 * receiver `str[0]` resolved elsewhere, but an element-access-typed receiver
 * `(arr[i])[0]` (whose inner access is typed `string`) reached the missing-member
 * path — tsc's own jsTyping.ts `pathComponents[pathComponents.length - 3][0] === "@"`.
 */
class StringNumericElementAccessTest {

    @Test
    fun `numeric element access on a string-typed element access does not FP`() {
        diagnose(
            """
            declare function comps(p: string): string[];
            function isScoped(p: string): boolean {
                const c = comps(p);
                return c[c.length - 3][0] === "@";
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `numeric element access on a plain string variable does not FP`() {
        diagnose(
            """
            function first(str: string): string {
                return str[0];
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `numeric-looking string key on a string receiver does not FP`() {
        diagnose(
            """
            function f(str: string) {
                return str["0"];
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a genuinely missing property on a real object still fires`() {
        diagnose(
            """
            interface Box { value: number; }
            function g(b: Box) {
                return b.notThere;
            }
            """,
        ) should {
            have(any { it.code == 2339 })
        }
    }
}
