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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 488 (M5.1): `checkMultiBaseInStatement`'s TS2320 same-generic-base check
 * was restructured to group bases by NAME first and run the O(source) `occOf`
 * `<`-scan only for names that recur (2+), skipping it for the common
 * distinct-base-names case. These pin the observable behavior across the
 * restructure: TS2320 fires for the SAME generic base with differing args, and
 * NOT for distinct bases (whose occOf scan is now skipped).
 */
class MultiBaseTs2320OccOfTest {

    @Test
    fun `same generic base with differing args fires TS2320`() {
        val d = diagnose(
            """
            interface A<T> { x: T; }
            interface C extends A<string>, A<number> {}
            """.trimIndent(),
        ).firstOrNull { it.code == 2320 }
        assertTrue(d != null, "expected TS2320")
        assertTrue(
            d.message.contains("A<string>") && d.message.contains("A<number>"),
            "${d.message}",
        )
    }

    @Test
    fun `distinct bases do not fire TS2320 - negative control`() {
        // The common case whose occOf scan is now skipped; no TS2320 from this path.
        val errs = diagnose(
            """
            interface P { a: number; }
            interface Q { b: string; }
            interface X extends P, Q {}
            """.trimIndent(),
        ).filter { it.code == 2320 }
        assertTrue(errs.isEmpty(), "distinct bases must not fire TS2320: $errs")
    }

    @Test
    fun `same generic base with identical args does not fire`() {
        // Recurring name but identical args → occOf runs, but no differing-arg pair.
        val errs = diagnose(
            """
            interface A<T> { x: T; }
            interface C extends A<string>, A<string> {}
            """.trimIndent(),
        ).filter { it.code == 2320 }
        assertTrue(errs.isEmpty(), "identical args must not fire TS2320: $errs")
    }
}
