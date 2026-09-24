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
 * A type-PARAMETER argument satisfies a generic call's type-parameter constraint when its OWN
 * constraint does — `createNodeArray<T>()` calling `createNodeArray<U extends Node>` with `T
 * extends Node` (T's constraint `Node` ≤ `Node`). Our relation engine's TypeParam-source path
 * under-resolves, so `checkCallTypeArgConstraints` now checks the constraint chain explicitly
 * (mirroring the skip the TypeReference path `checkConstraintsForTypeArgs` already had). This
 * was 2 self-compile TS2344 FPs (parser.ts's `createNodeArray<T>()`).
 */
class GenericCallArgConstraintTest {

    @Test
    fun `type-param arg whose constraint satisfies the callee constraint - no TS2344`() {
        diagnose(
            """
            interface Base { b: number; }
            declare function g<U extends Base>(): U;
            function f<T extends Base>(): void { g<T>(); }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `type-param arg whose constraint is a SUBTYPE of the callee constraint - no TS2344`() {
        diagnose(
            """
            interface Base { b: number; }
            interface Derived extends Base { d: number; }
            declare function g<U extends Base>(): U;
            function f<T extends Derived>(): void { g<T>(); }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `type-param arg whose constraint does NOT satisfy the callee constraint still fires`() {
        // Negative control: `Other` is unrelated to `Base`, so the constraint chain does not
        // satisfy — the skip must not fire and the diagnostic must be emitted.
        //
        // (LEGACY.0b) F6a: TypeScript 7 keeps the TS2344 head here because its chain line
        // names the CONSTRAINT — `Property 'b' is missing in type 'Other' but required in type
        // 'Base'.` — so the head and chain displays differ. Ours named the PARAMETER (a recorded
        // divergence, which collapsed the pair to a TS2741 head) until (CHK.174) related a type
        // parameter through its constraint; the row is now tsgo 7.0.2's own, head and chain.
        diagnose(
            """
            interface Base { b: number; }
            interface Other { o: number; }
            declare function g<U extends Base>(): U;
            function f<T extends Other>(): void { g<T>(); }
            """,
            directives = "",
        ) should {
            have(any {
                it.code == 2344 &&
                    it.message == "Type 'T' does not satisfy the constraint 'Base'." &&
                    it.messageChain == listOf("  Property 'b' is missing in type 'Other' but required in type 'Base'.")
            })
        }
    }

    @Test
    fun `unconstrained type-param arg does NOT over-skip a real constraint - TS2344 fires`() {
        // An unconstrained T (no constraint) must not be skipped — its apparent type `{}` does
        // not satisfy `Base`, so TS2344 must fire (the skip is gated to `constraint != null`).
        diagnose(
            """
            interface Base { b: number; }
            declare function g<U extends Base>(): U;
            function f<T>(): void { g<T>(); }
            """,
            directives = "",
        ) should {
            have(any { it.code == 2344 && it.message.contains("'Base'") })
        }
    }
}
