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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Pins the compiler's behavior for the module kinds tsgo (TypeScript 7) removed.
 *
 * The UMD and System transforms were deleted (2026-07-02) — no active corpus test
 * reaches them (the test generator skips every `module: umd/system` config, and no
 * tsconfig-in-test sets them). These tests pin the two invariants the corpus cannot:
 *
 * 1. A `module: umd`/`module: system` config must DEGRADE GRACEFULLY — no crash,
 *    output still emitted (module statements passed through untransformed), and the
 *    TS5107 deprecation diagnostic still reported. A dispatch hole that throws, or a
 *    silent emit of a partial wrapper, would pass the corpus suite unnoticed.
 *
 * 2. The AMD transform STAYS — it is corpus-pinned by `tsconfigMapOptionsAreCaseInsensitive`
 *    (a tsconfig-in-test `"module": "AmD"` with real module files that bypasses the
 *    generator's directive-based tsgo skip). A future over-removal must fail here,
 *    with a message pointing at the pinning test, not just in the corpus diff.
 */
class RemovedModuleKindsTest {

    private val moduleSource = """
        import { helper } from "./helper";
        export const answer = helper() + 1;
    """.trimIndent()

    private fun compileWithModule(kind: String): CompilationResult =
        TypeScriptCompiler().compile("// @module: $kind\n$moduleSource", "input.ts")

    /** Removed kinds fall through the module-transform dispatch: no crash, output emitted. */
    @Test fun umdAndSystemDegradeGracefullyAfterTransformRemoval() {
        for (kind in listOf("umd", "system")) {
            val result = compileWithModule(kind)
            val js = assertNotNull(result.javascript, "module: $kind emitted no output")
            assertTrue(js.isNotBlank(), "module: $kind emitted blank output")
            // The transforms are gone — their wrappers must never appear again.
            assertFalse(js.contains("System.register("), "module: $kind emitted a System wrapper")
            assertFalse(js.contains("typeof define === \"function\" && define.amd"),
                "module: $kind emitted a UMD wrapper")
            // The config-level deprecation (TS5107, deprecated in 6.0 / removed in 7)
            // must still be reported — it is what tells the user the kind is dead.
            assertTrue(result.diagnostics.any { it.code == 5107 },
                "module: $kind lost its TS5107 deprecation diagnostic")
        }
    }

    /** AMD single-file emit is corpus-pinned (tsconfigMapOptionsAreCaseInsensitive) — keep it. */
    @Test fun amdModuleTransformIsStillPresent() {
        val js = assertNotNull(
            compileWithModule("amd").javascript,
            "module: amd emitted no output",
        )
        assertTrue(
            js.contains("define("),
            "AMD define() wrapper missing — the AMD transform is corpus-pinned by " +
                "tsconfigMapOptionsAreCaseInsensitive (tsconfig-in-test \"module\": \"AmD\") " +
                "and must not be removed while that test is in the suite; got:\n$js",
        )
    }
}
