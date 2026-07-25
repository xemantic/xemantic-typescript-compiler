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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * M3.4 (round 413): pins the `export *` leaf-export gate in
 * [Checker.computeExportedSymbolThroughStars]. `export * from "M"` re-exports only a
 * module's EXPORTS, never a non-re-exported IMPORT alias — but the pre-413 star search
 * returned ANY local named X, so it stopped at the FIRST starred file that merely
 * IMPORTED X (an Alias local) and never reached the file that actually DECLARES X.
 *
 * This is the true cause of the builder.ts `Debug.assert(isDefined(state))` TS18048
 * family (NOT the round-412 depth-truncation red herring): tsc's `_namespaces/ts.ts`
 * does `export * from "../core.js"` (core.ts merely IMPORTS `Debug`) BEFORE
 * `export * from "../debug.js"` (debug.ts DECLARES `export namespace Debug`), so the
 * barrel-imported `Debug` resolved to core.ts's non-exported import alias — flags=Alias,
 * not a namespace — and `Debug.assert` never resolved → its bare-assert narrowing never
 * fired.
 *
 * The tests below reproduce that exact ordering (an importer module starred BEFORE the
 * declaring module). The signal is a downstream diagnostic that is present ONLY when the
 * guard/assert failed to narrow — its ABSENCE proves the barrel namespace resolved past
 * the collision.
 */
class BarrelExportLeafGateTest {

    private fun build(files: Map<String, String>): ProjectCompiler.Result {
        val vfs = InMemoryVfs(
            files + ("/proj/tsconfig.json" to
                """{ "compilerOptions": { "strict": true, "outDir": "./dist" }, "include": ["src/**/*.ts"] }""")
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
    }

    // debug.ts: DECLARES the `Dbg` namespace with a bare-assert (Debug.assert's shape).
    private val dbgLeaf =
        """
        export namespace Dbg {
            export function assert(value: unknown): asserts value {}
        }
        """.trimIndent()

    // core.ts: merely IMPORTS `Dbg` (a non-exported Alias local) — the collision that the
    // pre-413 star search wrongly stopped at. Starred BEFORE dbg.ts in the barrel.
    private val coreImporter =
        """
        import { Dbg } from "./barrel.js";
        export function warmUp(): void { Dbg.assert(true); }
        """.trimIndent()

    // Barrel: core FIRST (non-exported `Dbg` alias), dbg SECOND (real namespace) — the
    // exact `_namespaces/ts.ts` ordering.
    private val barrel = """export * from "./core.js";
export * from "./dbg.js";"""

    private fun files(@Language("typescript") indexBody: String): Map<String, String> = mapOf(
        "/proj/src/dbg.ts" to dbgLeaf,
        "/proj/src/core.ts" to coreImporter,
        "/proj/src/barrel.ts" to barrel,
        "/proj/src/index.ts" to indexBody,
    )

    /**
     * The load-bearing case: `Dbg.assert(isString(x))` is a bare assert wrapping a type
     * guard. It narrows `x` to `string` ONLY if the barrel-imported `Dbg` resolves past
     * the core.ts import-alias collision to debug.ts's real namespace.
     */
    @Test
    fun `a barrel namespace assert narrows past the import-alias collision`() {
        val result = build(
            files(
                """
                import { Dbg } from "./barrel.js";
                declare function isString(x: string | number): x is string;
                export function f(x: string | number): void {
                    Dbg.assert(isString(x));
                    const s: string = x;
                }
                """.trimIndent()
            )
        )
        result.diagnostics should {
            have(none { it.code == 2322 })
        }
    }

    /**
     * Negative control: with NO assert, `string | number` is genuinely not assignable to
     * `string` — proves the positive above is not vacuous.
     */
    @Test
    fun `negative control - without the assert the assignment still errors`() {
        val result = build(
            files(
                """
                declare function isString(x: string | number): x is string;
                export function f(x: string | number): void {
                    const s: string = x;
                }
                """.trimIndent()
            )
        )
        result.diagnostics should {
            have(any { it.code == 2322 })
        }
    }

    /**
     * The gate must NOT over-restrict: a guard genuinely DECLARED + EXPORTED in the same
     * (first-starred) module that also imports `Dbg` must still resolve through the gate
     * (it IS in that module's exports). Proves the leaf check accepts real exports while
     * rejecting the non-exported import alias.
     */
    @Test
    fun `a real export in the collision module still resolves`() {
        val result = build(
            mapOf(
                "/proj/src/dbg.ts" to dbgLeaf,
                // core imports Dbg (non-exported alias) AND exports its own guard.
                "/proj/src/core.ts" to """
                    import { Dbg } from "./barrel.js";
                    export function isString(x: string | number): x is string {
                        Dbg.assert(true);
                        return typeof x === "string";
                    }
                """.trimIndent(),
                "/proj/src/barrel.ts" to barrel,
                "/proj/src/index.ts" to """
                    import { isString } from "./barrel.js";
                    declare function takesString(s: string): void;
                    export function f(x: string | number): void {
                        if (isString(x)) {
                            takesString(x);
                        }
                    }
                """.trimIndent(),
            )
        )
        result.diagnostics should {
            have(none { it.code == 2345 })
        }
    }
}
