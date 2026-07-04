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
 * A type-PARAMETER argument satisfies a generic call's type-parameter constraint when its OWN
 * constraint does — `createNodeArray<T>()` calling `createNodeArray<U extends Node>` with `T
 * extends Node` (T's constraint `Node` ≤ `Node`). Our relation engine's TypeParam-source path
 * under-resolves, so `checkCallTypeArgConstraints` now checks the constraint chain explicitly
 * (mirroring the skip the TypeReference path `checkConstraintsForTypeArgs` already had). This
 * was 2 self-compile TS2344 FPs (parser.ts's `createNodeArray<T>()`).
 */
class GenericCallArgConstraintTest {

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(body.trimIndent(), "t.ts").diagnostics

    @Test
    fun `type-param arg whose constraint satisfies the callee constraint - no TS2344`() {
        val d = diags(
            """
            interface Base { b: number; }
            declare function g<U extends Base>(): U;
            function f<T extends Base>(): void { g<T>(); }
            """,
        )
        assertTrue(
            d.none { it.code == 2344 },
            "T extends Base passed to g<U extends Base> must NOT be TS2344; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `type-param arg whose constraint is a SUBTYPE of the callee constraint - no TS2344`() {
        val d = diags(
            """
            interface Base { b: number; }
            interface Derived extends Base { d: number; }
            declare function g<U extends Base>(): U;
            function f<T extends Derived>(): void { g<T>(); }
            """,
        )
        assertTrue(
            d.none { it.code == 2344 },
            "T extends Derived (⊂ Base) passed to g<U extends Base> must NOT be TS2344; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `type-param arg whose constraint does NOT satisfy the callee constraint - TS2344 fires`() {
        // Negative control: `Other` is unrelated to `Base`, so the constraint chain does not
        // satisfy — the skip must not fire and TS2344 must be emitted.
        val d = diags(
            """
            interface Base { b: number; }
            interface Other { o: number; }
            declare function g<U extends Base>(): U;
            function f<T extends Other>(): void { g<T>(); }
            """,
        )
        assertTrue(
            d.any { it.code == 2344 && it.message.contains("'Base'") },
            "T extends Other passed to g<U extends Base> must still be TS2344; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `unconstrained type-param arg does NOT over-skip a real constraint - TS2344 fires`() {
        // An unconstrained T (no constraint) must not be skipped — its apparent type `{}` does
        // not satisfy `Base`, so TS2344 must fire (the skip is gated to `constraint != null`).
        val d = diags(
            """
            interface Base { b: number; }
            declare function g<U extends Base>(): U;
            function f<T>(): void { g<T>(); }
            """,
        )
        assertTrue(
            d.any { it.code == 2344 && it.message.contains("'Base'") },
            "unconstrained T passed to g<U extends Base> must still be TS2344; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
