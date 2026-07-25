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
import com.xemantic.kotlin.test.have
import kotlin.test.Test

/**
 * Pins the M1.1 invariant: the named-import existence check (TS2305 family)
 * follows `export * from` re-export chains — the barrel pattern every real
 * project (including tsc's own `_namespaces/ts.ts`) imports through — while a
 * genuinely missing name STILL fires, and an unresolvable star target makes
 * the export set unknowable (no absence diagnostics — the FN-safe direction).
 */
class ExportStarImportsTest {

    private fun build(files: Map<String, String>): ProjectCompiler.Result {
        val vfs = InMemoryVfs(
            files + ("/proj/tsconfig.json" to
                """{ "compilerOptions": { "outDir": "./dist" }, "include": ["src/**/*.ts"] }""")
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
    }

    private fun ts2305Messages(result: ProjectCompiler.Result): List<String> =
        result.diagnostics.filter { it.code == 2305 }.map { it.message }

    @Test
    fun namesThroughAStarBarrelResolveAndMissingNamesStillFire() {
        val result = build(
            mapOf(
                "/proj/src/a.ts" to "export const alpha = 1;",
                "/proj/src/b.ts" to "export function beta(): void {}",
                "/proj/src/barrel.ts" to """
                    export * from "./a.js";
                    export * from "./b.js";
                    export const direct = 2;
                """.trimIndent(),
                "/proj/src/index.ts" to """
                    import { alpha, beta, direct, gamma } from "./barrel.js";
                    export const use = [alpha, beta, direct, gamma];
                """.trimIndent(),
            )
        )
        assert(ts2305Messages(result) == listOf("Module '\"./barrel.js\"' has no exported member 'gamma'."))
    }

    @Test
    fun multiHopBarrelChainsResolve() {
        val result = build(
            mapOf(
                "/proj/src/leaf.ts" to "export const deep = 1;",
                "/proj/src/mid.ts" to """export * from "./leaf.js";""",
                "/proj/src/top.ts" to """export * from "./mid.js";""",
                "/proj/src/index.ts" to """
                    import { deep, nope } from "./top.js";
                    export const use = [deep, nope];
                """.trimIndent(),
            )
        )
        assert(ts2305Messages(result) == listOf("Module '\"./top.js\"' has no exported member 'nope'."))
    }

    @Test
    fun circularStarReexportsResolveWithoutHangingAndMissingNamesStillFire() {
        val result = build(
            mapOf(
                "/proj/src/c.ts" to """
                    export * from "./d.js";
                    export const cee = 1;
                """.trimIndent(),
                "/proj/src/d.ts" to """
                    export * from "./c.js";
                    export const dee = 2;
                """.trimIndent(),
                "/proj/src/index.ts" to """
                    import { cee, dee, absent } from "./c.js";
                    export const use = [cee, dee, absent];
                """.trimIndent(),
            )
        )
        assert(ts2305Messages(result) == listOf("Module '\"./c.js\"' has no exported member 'absent'."))
    }

    @Test
    fun unresolvableStarTargetSuppressesAbsenceDiagnostics() {
        val result = build(
            mapOf(
                "/proj/src/opaque.ts" to """
                    export * from "some-unresolvable-package";
                    export const known = 1;
                """.trimIndent(),
                "/proj/src/index.ts" to """
                    import { known, mystery } from "./opaque.js";
                    export const use = [known, mystery];
                """.trimIndent(),
            )
        )
        assert(ts2305Messages(result).isEmpty())
    }

    @Test
    fun defaultAbsenceStaysDecidableThroughAnUnknowableStar() {
        // `export *` never forwards a default export, so `import { default as X }`
        // from a module with NO default is TS2305 even when the star set is
        // unknowable — only NON-default specs get the FN-safe skip.
        val result = build(
            mapOf(
                "/proj/src/opaque.ts" to """
                    export * from "some-unresolvable-package";
                    export const known = 1;
                """.trimIndent(),
                "/proj/src/index.ts" to """
                    import { default as anything, mystery } from "./opaque.js";
                    export const use = [anything, mystery];
                """.trimIndent(),
            )
        )
        assert(ts2305Messages(result) == listOf("Module '\"./opaque.js\"' has no exported member 'default'."))
    }

    @Test
    fun starAsNamespaceExportsOnlyTheNamespaceName() {
        val result = build(
            mapOf(
                "/proj/src/a.ts" to "export const alpha = 1;",
                "/proj/src/nsbarrel.ts" to """export * as wrapped from "./a.js";""",
                "/proj/src/index.ts" to """
                    import { wrapped, alpha } from "./nsbarrel.js";
                    export const use = [wrapped, alpha];
                """.trimIndent(),
            )
        )
        assert(ts2305Messages(result) == listOf("Module '\"./nsbarrel.js\"' has no exported member 'alpha'."))
    }

    @Test
    fun reExportSpecifiersAlsoFollowStars() {
        val result = build(
            mapOf(
                "/proj/src/a.ts" to "export const alpha = 1;",
                "/proj/src/barrel.ts" to """export * from "./a.js";""",
                "/proj/src/index.ts" to """
                    export { alpha, ghost } from "./barrel.js";
                """.trimIndent(),
            )
        )
        assert(ts2305Messages(result) == listOf("Module '\"./barrel.js\"' has no exported member 'ghost'."))
    }

    @Test
    fun barrelWithHundredsOfNamesStaysFast() {
        // The tsc self-compile shape: ~78 files each importing dozens of names
        // through one barrel that star-re-exports every other file. The per-file
        // memo must make this linear-ish; this completes in well under a second.
        val files = mutableMapOf<String, String>()
        val leafNames = (1..60).map { "name$it" }
        for ((i, n) in leafNames.withIndex()) {
            files["/proj/src/leaf$i.ts"] = "export const $n = $i;"
        }
        files["/proj/src/barrel.ts"] =
            leafNames.indices.joinToString("\n") { """export * from "./leaf$it.js";""" }
        for (i in leafNames.indices) {
            files["/proj/src/user$i.ts"] = """
                import { ${leafNames.joinToString(", ")} } from "./barrel.js";
                export const total$i = [${leafNames.joinToString(", ")}].length;
            """.trimIndent()
        }
        val result = build(files)
        have(
            ts2305Messages(result).isEmpty(),
            "no false absence through the wide barrel",
        )
    }
}
