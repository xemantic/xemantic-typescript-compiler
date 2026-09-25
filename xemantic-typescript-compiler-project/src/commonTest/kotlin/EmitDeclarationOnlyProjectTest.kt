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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (CHK.172) A PROJECT built with `declaration` + `emitDeclarationOnly` is type-checked exactly as
 * a plain build is — the twin of core's `EmitDeclarationOnlyChecksTest`, through `ProjectCompiler`
 * with a real tsconfig, a `.d.ts` import and a package import.
 *
 * Before the round such a project reported NOTHING: the multi-file `emitDeclarationOnly` branch
 * built the checker in a whitelist mode that skipped `checkSpine`, dropped every `.d.ts` input
 * from the program, parsed each `package.json` as TypeScript and passed no module resolutions
 * (measured: this fixture read `0 error(s)` on the pre-round binary). A library that bundles with
 * esbuild and runs the compiler only for `.d.ts` output is exactly this configuration.
 *
 * EVERY ROW IS tsgo 7.0.2's over the identical tree on disk (`build/scratch-p18203/pin/proj`),
 * which reports the same six rows with and without `emitDeclarationOnly`. The `rootDir` is set
 * only because tsgo refuses (TS5011) to infer it.
 *
 * Every standing gate is blind here: the corpus harness materialises no directory, no
 * `node_modules` and no `package.json`, and the eight dashboard profiles never set
 * `emitDeclarationOnly`.
 */
class EmitDeclarationOnlyProjectTest {

    private fun config(edo: Boolean) =
        """{ "compilerOptions": { "target": "es2022", "module": "esnext",""" +
            """ "moduleResolution": "bundler", "strict": true, "declaration": true,""" +
            (if (edo) """ "emitDeclarationOnly": true,""" else "") +
            """ "outDir": "out", "rootDir": "src" }, "include": ["src/**/*.ts"] }"""

    private val source = """
        import d, { g } from "./lib";
        import { f, type N } from "pkg";
        declare function pn(n: number): void;
        pn("x");
        const n: number = "s";
        export const v: number = d;
        g("no");
        export const s: number = f(1);
        export const k: N = { kind: "one" };
    """.trimIndent() + "\n"

    private fun files(edo: Boolean) = mapOf(
        "/proj/tsconfig.json" to config(edo),
        "/proj/node_modules/pkg/package.json" to
            """{ "name": "pkg", "version": "1.0.0", "types": "lib/index.d.ts" }""",
        "/proj/node_modules/pkg/lib/index.d.ts" to
            "export interface N { kind: number }\nexport declare function f(x: number): string;\n",
        "/proj/src/lib.d.ts" to
            "declare const x: string;\nexport default x;\nexport declare function g(n: number): void;\n",
        "/proj/src/a.ts" to source,
    )

    private fun build(edo: Boolean): ProjectCompiler.Result =
        ProjectCompiler(InMemoryVfs(files(edo))).build("/proj")

    private fun rows(result: ProjectCompiler.Result): List<String> =
        result.diagnostics.map { d ->
            "${d.fileName?.removePrefix("/proj/")} ${d.line}:${d.character} ${d.code} ${d.message}"
        }.sorted()

    private val arg = "Argument of type 'string' is not assignable to parameter of type 'number'."
    private val assign = "Type 'string' is not assignable to type 'number'."

    private val tsgoRows = listOf(
        "src/a.ts 4:4 2345 $arg",
        "src/a.ts 5:7 2322 $assign",
        "src/a.ts 6:14 2322 $assign",
        "src/a.ts 7:3 2345 $arg",
        "src/a.ts 8:14 2322 $assign",
        "src/a.ts 9:23 2322 $assign",
    )

    @Test
    fun `emitDeclarationOnly project reports tsgo's rows`() {
        val r = rows(build(edo = true))
        assert(r == tsgoRows)
    }

    @Test
    fun `control - the same project without emitDeclarationOnly reports the same rows`() {
        val r = rows(build(edo = false))
        assert(r == tsgoRows)
    }

    @Test
    fun `the dts input and the package declaration are in the program and no package json is parsed`() {
        val result = build(edo = true)
        val program = result.programFiles.map { it.removePrefix("/proj/") }.sorted()
        assert(program == listOf("node_modules/pkg/lib/index.d.ts", "src/a.ts", "src/lib.d.ts"))
        assert(result.diagnostics.none { it.fileName?.endsWith(".json") == true })
    }

    @Test
    fun `emit channel - nothing is written under emitDeclarationOnly`() {
        val written = build(edo = true).written
        assert(written.isEmpty())
    }

    @Test
    fun `emit channel control - a plain build writes the JavaScript`() {
        val written = build(edo = false).written.map { it.first.removePrefix("/proj/") }
        assert(written == listOf("out/a.js"))
    }
}
