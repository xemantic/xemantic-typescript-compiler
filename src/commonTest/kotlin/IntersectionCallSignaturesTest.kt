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
 * Round 471: an INTERSECTION's call signatures are the concatenation of its
 * constituents' (tsc getSignaturesOfStructuredType) — getCallSignaturesOfType
 * previously returned empty for a `Fn & Fn` union member, so the union-callee
 * callability check FP'd TS2349 "No constituent ... is callable" (tsc
 * textChanges.ts `token.getStart(...)` / callHierarchy.ts, where the
 * augmentation merge yields intersection-shaped members).
 */
class IntersectionCallSignaturesTest {

    private val prelude = """
        type GetStart = (includeComment?: boolean) => number;
    """.trimIndent()

    @Test
    fun `a union of fn-intersections is callable`() {
        diagnose(
            prelude + """
            declare const u: (GetStart & GetStart) | (GetStart & GetStart);
            const n: number = u(true);
            """
        ) should {
            have(none { it.code == 2349 })
        }
    }

    @Test
    fun `a bare fn-intersection is callable`() {
        diagnose(
            prelude + """
            declare const f: GetStart & ((x?: boolean) => number);
            const n: number = f();
            """
        ) should {
            have(none { it.code == 2349 })
        }
    }

    @Test
    fun `negative control - a union mixing a fn-intersection and a plain object still fires`() {
        diagnose(
            prelude + """
            declare const bad: (GetStart & GetStart) | { x: number };
            function callBad() { return bad(); }
            """
        ) should {
            have(any { it.code == 2349 })
        }
    }
}
