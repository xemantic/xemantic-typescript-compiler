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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * Pins the compiler's behavior for the module kinds tsgo (TypeScript 7) removed.
 *
 * The AMD, UMD, and System transforms were deleted (2026-07-02) together with the
 * outFile bundling machinery — no active corpus test reaches them (the generator
 * skips every `module: amd/umd/system` / `outFile` config, whether set via
 * directives or via a tsconfig.json embedded in the test). These tests pin the
 * invariant the corpus cannot: a removed module kind must DEGRADE GRACEFULLY —
 * no crash, output still emitted (module statements passed through
 * untransformed, never a partial wrapper), and the TS5107 deprecation
 * diagnostic still reported so the user learns the kind is dead.
 */
class RemovedModuleKindsTest {

    private val moduleSource = """
        import { helper } from "./helper";
        export const answer = helper() + 1;
    """.trimIndent()

    @Test
    fun `removed module kinds degrade gracefully`() {
        for (kind in listOf("amd", "umd", "system")) {
            val result = TypeScriptCompiler().compile("// @module: $kind\n$moduleSource", "input.ts")
            val js = result.javascript
            assert(js != null)
            assert(js.isNotBlank())
            // The transforms are gone — their wrappers must never appear again.
            assert(!js.contains("define("))
            assert(!js.contains("System.register("))
            assert(!js.contains("typeof define === \"function\" && define.amd"))
            // The config-level deprecation (TS5107, deprecated in 6.0 / removed in 7)
            // is the signal that the kind is unsupported.
            assert(result.diagnostics.any { it.code == 5107 })
        }
    }
}
