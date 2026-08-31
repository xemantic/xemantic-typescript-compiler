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
 * (INC.82): the crawl's per-file resolution map is HOISTED out of the per-specifier
 * loop, and it must stay scoped to the file that owns it.
 *
 * The crawl asks `ModuleResolver` once per SPECIFIER while the importer — hence its
 * directory, and hence its entry in `moduleResolutions` — is fixed once per FILE. Both
 * are now hoisted. The directory half is pinned by [ModuleResolutionMemoTest]; this
 * pins the half that a future edit is most likely to get wrong, because the obvious
 * simplification is to lift the `var` one loop further out.
 *
 * ## Why this needs a whole build rather than a resolver pin
 *
 * `moduleResolutions` is not on `ProjectCompiler.Result` — it reaches the checker as
 * `ParsedSource.moduleResolutions` and is read there as
 * `moduleResolutions[contextFile]?.get(spec)`, which is (CHK.30): a BARE specifier
 * cannot be resolved by re-deriving it from the program's file names, so a package
 * import types as `any` without it. So a map written under the wrong importer is
 * silent in every other channel and shows up here as a **lost diagnostic** — the
 * direction nothing in this repo prints.
 *
 * ## Why the shapes are what they are
 *
 * The two importers live in DIFFERENT directories and import DIFFERENT packages, so a
 * map shared across the frontier's files puts `pkg-b`'s answer under `/proj/src/a.ts`
 * and leaves `/proj/src/nested/b.ts` with nothing to find. Each importer then makes a
 * deliberately wrong assignment off its package's return type, so the pin asserts a
 * diagnostic that EXISTS rather than an absence — an absence pin would pass against a
 * binary that resolved nothing at all.
 */
class CrawlPerFileResolutionScopeTest {

    private fun project() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to """
                { "compilerOptions": { "strict": true, "module": "esnext" }, "include": ["src/**/*.ts"] }
            """.trimIndent(),
            "/proj/src/a.ts" to """
                import { numberOfA } from "pkg-a";
                export const badA: string = numberOfA();
            """.trimIndent(),
            "/proj/src/nested/b.ts" to """
                import { numberOfB } from "pkg-b";
                export const badB: string = numberOfB();
            """.trimIndent(),
            "/proj/node_modules/pkg-a/package.json" to """{ "name": "pkg-a", "types": "index.d.ts" }""",
            "/proj/node_modules/pkg-a/index.d.ts" to "export declare function numberOfA(): number;\n",
            "/proj/node_modules/pkg-b/package.json" to """{ "name": "pkg-b", "types": "index.d.ts" }""",
            "/proj/node_modules/pkg-b/index.d.ts" to "export declare function numberOfB(): number;\n",
        )
    )

    /**
     * THE PIN. Both importers keep their own resolutions, so both package return types
     * are real and both wrong assignments report. A per-frontier map (the `var` lifted
     * one loop out) loses the second file's entry entirely.
     */
    @Test
    fun `each importer keeps its own module resolutions`() {
        val result = ProjectCompiler(project()).build("/proj", noEmit = true)
        val files = result.diagnostics.filter { it.code == 2322 }.mapNotNull { it.fileName }.toSet()
        assert("/proj/src/a.ts" in files)
        assert("/proj/src/nested/b.ts" in files)
    }

    /**
     * CONTROL that the pin above is not vacuous in the other direction: the packages
     * are genuinely in the program and genuinely resolved, so the crawl found them
     * rather than the diagnostics coming from somewhere else.
     */
    @Test
    fun `both package declarations are in the program and nothing is unresolved`() {
        val result = ProjectCompiler(project()).build("/proj", noEmit = true)
        assert("/proj/node_modules/pkg-a/index.d.ts" in result.programFiles)
        assert("/proj/node_modules/pkg-b/index.d.ts" in result.programFiles)
        assert(result.unresolved.isEmpty())
    }
}
