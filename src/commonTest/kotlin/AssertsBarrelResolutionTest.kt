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
 * M1.5b: assert-function narrowing must survive tsc's real import topology —
 * `import { Debug } from "./_namespaces/ts.js"` where the namespace reaches the
 * importer only through an `export * from` BARREL. M1.5's activation was measured
 * inert on the tsc self-compile because the imported alias dead-ends before the
 * declaring file's namespace symbol in the flow-callee path.
 *
 * Sharp signal: TS2345 through the B469 flow-narrowed call-arg consumer, with a
 * no-assert negative control proving the signal fires in the ProjectCompiler
 * (multi-file) context.
 */
class AssertsBarrelResolutionTest {

    private fun project(mainBody: String) = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to """
                { "compilerOptions": { "strict": true, "module": "nodenext" }, "include": ["src/**/*.ts"] }
            """.trimIndent(),
            "/proj/src/debug.ts" to """
                export namespace Debug {
                    export function assert(cond: unknown, message?: string): asserts cond {
                        if (!cond) throw new Error(message);
                    }
                }
            """.trimIndent(),
            "/proj/src/barrel.ts" to """
                export * from "./debug.js";
            """.trimIndent(),
            "/proj/src/main.ts" to mainBody.trimIndent(),
        )
    )

    /** Negative control: without the assert, the maybe-undefined arg fires TS2345. */
    @Test fun withoutAssertMaybeUndefinedArgErrors() {
        val result = ProjectCompiler(project(
            """
            import { Debug } from "./barrel.js";
            declare function takesString(s: string): void;
            export function f(x: string | undefined) {
                takesString(x);
            }
            """
        )).build("/proj", noEmit = true)
        assertTrue(
            result.diagnostics.any { it.code == 2345 },
            "negative control lost — expected TS2345 for string|undefined arg, got: " +
                result.diagnostics.joinToString { "TS${it.code}" }
        )
    }

    /** A namespace assert imported THROUGH an export-star barrel narrows after the call. */
    @Test fun barrelImportedNamespaceAssertNarrows() {
        val result = ProjectCompiler(project(
            """
            import { Debug } from "./barrel.js";
            declare function takesString(s: string): void;
            export function f(x: string | undefined) {
                Debug.assert(x !== undefined);
                takesString(x);
            }
            """
        )).build("/proj", noEmit = true)
        val hits = result.diagnostics.filter { it.code == 2345 }
        assertTrue(
            hits.isEmpty(),
            "barrel-imported Debug.assert must narrow x — got: " +
                hits.joinToString { "TS${it.code}: ${it.message}" }
        )
    }

    /** Same shape imported DIRECTLY (no barrel) — the simpler topology also narrows. */
    @Test fun directlyImportedNamespaceAssertNarrows() {
        val result = ProjectCompiler(project(
            """
            import { Debug } from "./debug.js";
            declare function takesString(s: string): void;
            export function f(x: string | undefined) {
                Debug.assert(x !== undefined);
                takesString(x);
            }
            """
        )).build("/proj", noEmit = true)
        val hits = result.diagnostics.filter { it.code == 2345 }
        assertTrue(
            hits.isEmpty(),
            "directly-imported Debug.assert must narrow x — got: " +
                hits.joinToString { "TS${it.code}: ${it.message}" }
        )
    }
}
