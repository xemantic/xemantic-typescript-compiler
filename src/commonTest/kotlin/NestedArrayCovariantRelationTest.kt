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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 446: `Array<X>` nested inside `Array<Y>` (`{ actions: ActionInfo[] }[]` vs
 * `readonly ApplicableRefactorInfo[]`) FP'd TS2322: the outer Array pushed
 * `globalArrayType.id` on the comparison stack, so the inner Array's same target id
 * counted as a `isReentry` → the covariant element shortcut was deferred to structural
 * comparison of the two Array interfaces → `concat`'s contravariant element param
 * spuriously failed. Array/ReadonlyArray are covariant containers, so they must always
 * use the element shortcut (gated to TP-free target args so an unbound-TP inference gap
 * like `flatten<T>(…: T[][])` is not turned into an FP).
 */
class NestedArrayCovariantRelationTest {

    private val prelude = """
        interface ActionInfo { name: string; description: string; kind?: string; notApplicableReason?: string; }
        interface RefactorInfo { name: string; description: string; inlineable?: boolean; actions: ActionInfo[]; }
    """.trimIndent() + "\n"

    @Test
    fun `array of objects with a nested array property matches a readonly interface array`() {
        diagnose(
            prelude +
            """
            function f(): readonly RefactorInfo[] {
                return [{ name: "a", description: "d", actions: [{ name: "x", description: "y", kind: "k" }] }];
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `nested array element provides an optional property`() {
        // The exact minimal trigger: nested action provides the optional `kind`.
        diagnose(
            """
            interface A { name: string; kind?: string; }
            interface R { name: string; actions: A[]; }
            function f(): R[] { return [{ name: "a", actions: [{ name: "x", kind: "k" }] }]; }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `array-of-array element covariance is checked (nested container)`() {
        diagnose(
            """
            interface Inner { x: number; }
            interface Outer { items: Inner[]; }
            function f(): Outer[] { return [{ items: [{ x: 1 }] }]; }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - nested array element with a wrong property type still fires`() {
        diagnose(
            """
            interface A { name: string; kind: string; }
            interface R { name: string; actions: A[]; }
            function f(): R[] { return [{ name: "a", actions: [{ name: "x", kind: 42 }] }]; }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - nested array element missing a required property still fires`() {
        // The inner array-literal drill reports the missing required member as TS2741.
        diagnose(
            """
            interface A { name: string; required: string; }
            interface R { name: string; actions: A[]; }
            function f(): R[] { return [{ name: "a", actions: [{ name: "x" }] }]; }
            """
        ) should {
            have(any { it.code == 2741 })
        }
    }
}
