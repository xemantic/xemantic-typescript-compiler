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
 * M4.8 (round 680): the CRAWL half — a `/// <reference path|types>` target must
 * ENTER the program (tsc `processReferencedFiles`), transitively.
 *
 * Before this, the crawl only ORDERED files already in the program, and only
 * under `outFile`. The practical cost was total for `@types` packages:
 * `@types/node`'s `index.d.ts` is 64 reference lines and little else, with
 * `globals.d.ts` declaring `var process` and `namespace NodeJS`. Enabling
 * `"types": ["node"]` on the tsc compiler profile moved the program from 78
 * files to **79** and left all 46 diagnostics standing; with the fix it loads
 * 146 files and the `process`/`Buffer`/`NodeJS` errors resolve.
 *
 * The parser half (recording the two kinds separately) is
 * [ReferenceDirectiveProgramTest].
 */
class ReferenceDirectiveCrawlTest {

    private val tsconfig =
        """{ "compilerOptions": { "strict": true, "module": "nodenext", "target": "es2020", "types": [] },
             "include": ["src/**/*"] }"""

    private fun build(files: Map<String, String>): ProjectCompiler.Result =
        ProjectCompiler(InMemoryVfs(mapOf("/proj/tsconfig.json" to tsconfig) + files))
            .build("/proj", noEmit = true)

    @Test
    fun `a referenced file enters the program and its declarations resolve`() {
        val r = build(
            mapOf(
                "/proj/src/globals.d.ts" to "declare var myGlobal: number;",
                "/proj/src/m.ts" to
                    "/// <reference path=\"globals.d.ts\" />\nexport const x = myGlobal + 1;\n",
            )
        )
        // The referenced file must join the program, and its declarations resolve.
        assert(r.programFiles.any { it.endsWith("globals.d.ts") })
        assert(r.diagnostics.count { it.code == 2304 } == 0)
    }

    @Test
    fun `references are followed TRANSITIVELY`() {
        // The @types/node shape: entry file is nothing but reference lines.
        val r = build(
            mapOf(
                "/proj/src/globals.d.ts" to "declare var deep: string;",
                "/proj/src/entry.d.ts" to "/// <reference path=\"globals.d.ts\" />\n",
                "/proj/src/m.ts" to
                    "/// <reference path=\"entry.d.ts\" />\nexport const x = deep.length;\n",
            )
        )
        assert(r.programFiles.any { it.endsWith("globals.d.ts") })
        assert(r.diagnostics.count { it.code == 2304 } == 0)
    }

    @Test
    fun `a relative reference path resolves against the REFERENCING file's directory`() {
        val r = build(
            mapOf(
                "/proj/src/types/g.d.ts" to "declare var nested: boolean;",
                "/proj/src/m.ts" to
                    "/// <reference path=\"./types/g.d.ts\" />\nexport const x = nested;\n",
            )
        )
        assert(r.programFiles.any { it.endsWith("g.d.ts") })
        assert(r.diagnostics.count { it.code == 2304 } == 0)
    }

    @Test
    fun `a parent-relative reference path resolves`() {
        val r = build(
            mapOf(
                "/proj/src/g.d.ts" to "declare var up: number;",
                "/proj/src/deep/m.ts" to
                    "/// <reference path=\"../g.d.ts\" />\nexport const x = up;\n",
            )
        )
        assert(r.programFiles.any { it.endsWith("g.d.ts") })
        assert(r.diagnostics.count { it.code == 2304 } == 0)
    }

    @Test
    fun `the TS6053 not-found diagnostic goes silent once the file resolves`() {
        val r = build(
            mapOf(
                "/proj/src/g.d.ts" to "declare var ok: number;",
                "/proj/src/m.ts" to "/// <reference path=\"g.d.ts\" />\nexport const x = ok;\n",
            )
        )
        assert(r.diagnostics.count { it.code == 6053 } == 0)
    }

    // ── negative controls ─────────────────────────────────────────────────

    @Test
    fun `negative control - a genuinely missing reference still reports TS6053`() {
        val r = build(
            mapOf("/proj/src/m.ts" to "/// <reference path=\"nope.d.ts\" />\nexport const x = 1;\n")
        )
        assert(r.diagnostics.count { it.code == 6053 } == 1)
    }

    @Test
    fun `negative control - a reference path is NOT resolved as a bare module`() {
        // `globals.d.ts` (no ./) must resolve as a sibling FILE. If it were still
        // treated as a package specifier it would land in `unresolved`.
        val r = build(
            mapOf(
                "/proj/src/globals.d.ts" to "declare var sib: number;",
                "/proj/src/m.ts" to "/// <reference path=\"globals.d.ts\" />\nexport const x = sib;\n",
            )
        )
        // It must not be attempted as a module specifier.
        assert(!r.unresolved.any { it.second == "globals.d.ts" })
    }

    @Test
    fun `negative control - a file with no directives loads only itself`() {
        val r = build(mapOf("/proj/src/m.ts" to "export const x = 1;\n"))
        assert(r.programFiles.size == 1)
    }

    @Test
    fun `negative control - ordinary imports are unaffected`() {
        val r = build(
            mapOf(
                "/proj/src/dep.ts" to "export const d = 1;",
                "/proj/src/m.ts" to "import { d } from \"./dep.js\";\nexport const x = d;\n",
            )
        )
        assert(r.programFiles.any { it.endsWith("dep.ts") })
        assert(r.diagnostics.count { it.code == 2304 } == 0)
    }
}
