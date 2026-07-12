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
 * Round 488 (M5.2): [Checker.isOptionalProperty] was reordered to test the
 * declaration path FIRST (the common case) and only fall back to the
 * declaration-less tuple-member side set ([optionalTupleMemberIds]) for a symbol
 * with no declaration. These pin BOTH branches so the reorder stays correct:
 * a DECLARED `a?: T` is optional, a declaration-less optional TUPLE element is
 * optional, and a DECLARED required `a: T` is not.
 */
class IsOptionalPropertyReorderTest {

    @Test
    fun `declared optional source property is optional vs required target`() {
        // The declaration path: `a?: number` optional in source, required in target →
        // the source prop widens to `number | undefined` and fails.
        val d = diagnose(
            """
            interface Src { a?: number; }
            interface Dst { a: number; }
            declare const s: Src;
            let d: Dst = s;
            """.trimIndent(),
        ).firstOrNull { it.code == 2322 }
        assertTrue(d != null, "expected TS2322")
        assertTrue(
            d.messageChain.any { it.contains("number | undefined") },
            "chain: ${d.messageChain}",
        )
    }

    @Test
    fun `declaration-less optional tuple element is optional`() {
        // The side-set path: `[a?: number]` element symbol carries no declaration, so
        // its optionality comes from optionalTupleMemberIds — assigning it to a
        // required-length tuple must still error.
        val errs = diagnose(
            """
            function f(t: [a?: number]) { const x: [number] = t; }
            """.trimIndent(),
        ).filter { it.code == 2322 }
        assertTrue(errs.isNotEmpty(), "expected TS2322 for optional tuple element vs required")
    }

    @Test
    fun `declared required property is not optional - negative control`() {
        val errs = diagnose(
            """
            interface Src { a: number; }
            interface Dst { a: number; }
            declare const s: Src;
            let d: Dst = s;
            """.trimIndent(),
        ).filter { it.code == 2322 }
        assertTrue(errs.isEmpty(), "required→required must not error: $errs")
    }
}
