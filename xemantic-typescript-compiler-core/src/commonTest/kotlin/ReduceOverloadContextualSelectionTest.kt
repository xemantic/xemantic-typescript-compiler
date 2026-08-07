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
 * Round 481: tsc contextually types a callback arg by the overload that
 * arg-matching SELECTS, not blindly by the first — Array.reduce's
 * `(cb, initialValue: T)` overload must lose to `<U>(cb, initialValue: U)` when
 * the initial value is not a T, so the accumulator param types as U (tsc
 * documentsUtil's `.reduce((meta, key) => meta.set(key, …), new Map())` typed
 * `meta` as string → FP TS2339 on `.set`). The strict-select path also treats an
 * UN-INFERRED bare TypeParam param as matching any arg (tsc infers it).
 */
class ReduceOverloadContextualSelectionTest {

    @Test
    fun `a Map-accumulator reduce types the accumulator from the generic overload`() {
        diagnose(
            """
            const m = ["a", "b"].reduce(
                (meta, key) => meta.set(key, key),
                new Map<string, string>(),
            );
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a string-accumulator reduce keeps firing TS2339 on a Map member`() {
        diagnose(
            """
            const s = ["a", "b"].reduce(
                (acc, key) => acc.set(key, key),
                "",
            );
            """,
        ) should {
            have(any { it.code == 2339 && "'set'" in it.message })
        }
    }
}
