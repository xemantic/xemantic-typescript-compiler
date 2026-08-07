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
 * Round 468: `x ?? "lit"` where the literal is a member of x's nullish-stripped
 * LITERAL union absorbs the literal instead of widening it to the primitive —
 * `preferences?.organizeImportsTypeOrder ?? "last"` typed
 * `string | "last" | "inline" | "first"` and FP'd against the bare union (tsc
 * organizeImports.ts testCoalesceExports).
 */
class NullishCoalesceLiteralAbsorbTest {

    @Test
    fun `a coalesced literal member of the left's literal union is absorbed`() {
        diagnose(
            """
            type Order = "last" | "inline" | "first";
            declare function use(o: { order: Order }): void;
            export function run(pref: Order | undefined): void {
                use({ order: pref ?? "last" });
            }
            """,
        ) should {
            have(none { it.code == 2322 || it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a coalesced literal OUTSIDE the union still fires`() {
        diagnose(
            """
            type Order = "last" | "inline" | "first";
            declare function use(o: { order: Order }): void;
            export function run(pref: Order | undefined): void {
                use({ order: pref ?? "bogus" });
            }
            """,
        ) should {
            // `"bogus"` is not an Order member — the result includes it and must fire.
            have(any { it.code == 2322 || it.code == 2345 })
        }
    }
}
