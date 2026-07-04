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
 * `Object.setPrototypeOf` is a standard ES2015 `ObjectConstructor` method that was missing from
 * the embedded lib (only `getPrototypeOf` was present), so tsc's own debug.ts calls to it
 * FP'd TS2551 "Property 'setPrototypeOf' does not exist on type 'ObjectConstructor'. Did you
 * mean 'getPrototypeOf'?" (5 self-compile FPs). Added to the embedded ObjectConstructor;
 * zero corpus baseline shifts.
 */
class ObjectSetPrototypeOfTest {

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(body.trimIndent(), "t.ts").diagnostics

    @Test
    fun `Object_setPrototypeOf resolves - no TS2551 or TS2339`() {
        val d = diags(
            """
            const o = {};
            Object.setPrototypeOf(o, null);
            """,
        )
        assertTrue(
            d.none { it.code == 2551 || it.code == 2339 },
            "Object.setPrototypeOf must resolve (no TS2551/TS2339); got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `a genuinely missing ObjectConstructor member still errors (negative control)`() {
        val d = diags("Object.definitelyNotAMethod({});")
        assertTrue(
            d.any { it.code == 2339 || it.code == 2551 },
            "a non-existent Object method must still error; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}
