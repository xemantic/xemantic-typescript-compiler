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
 * Self-compile burn-down: the real lib's `Object.entries` is generic
 * (`entries<T>(o: { [s: string]: T } | ArrayLike<T>): [string, T][]`), so tsc's own
 * `jsTyping.ts` writes `Object.entries<string>(result.config)`. The embedded lib's
 * `entries` had no type parameter, so an explicit type argument FP-fired TS2558
 * "Expected 0 type arguments, but got 1." The embedded declaration now carries `<T>`.
 */
class ObjectEntriesTypeArgumentTest {

    @Test
    fun `Object entries with an explicit type argument - no TS2558`() {
        diagnose(
            """
            export function f(o: { [s: string]: string }) {
                return Object.entries<string>(o);
            }
            """,
        ) should {
            have(none { it.code == 2558 })
        }
    }

    @Test
    fun `Object entries without a type argument still works - regression control`() {
        diagnose(
            """
            export function f(o: { a: number }) {
                return Object.entries(o);
            }
            """,
        ) should {
            have(none { it.code == 2558 })
        }
    }
}
