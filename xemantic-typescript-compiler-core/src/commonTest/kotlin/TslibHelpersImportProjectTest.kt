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
 * (CHK.229) Under `importHelpers`, tsgo gives every external-module (and every JS) file a
 * SYNTHETIC `import "tslib"` (`fileloader.go:544`), so the helpers module is resolved from the
 * file's own directory and its declaration file joins the program. The project crawl now does
 * the same; before it, a `node_modules/tslib` nothing imported was never in the program, so a
 * complete install read TS2354 "cannot be found" (a false positive on legal code) and an
 * install missing a helper read TS2354 where tsgo names the helper (TS2343).
 *
 * Only a `-project` fixture can pin this — a `diagnose()` fixture lists tslib as a program
 * input by hand, which is exactly the step under test. Every expectation is tsgo 7.0.2's
 * output (`build/bench/p18289-agent/matrix` c3-c7, `build/bench/p18291-agent/tm` d1-d7).
 */
class TslibHelpersImportProjectTest {

    private val awaiterOnly =
        "export declare function __awaiter(thisArg: any, _arguments: any, P: Function, generator: Function): any;\n"

    private fun build(options: String, files: Map<String, String>): ProjectCompiler.Result {
        val tsconfig = """{ "compilerOptions": { "strict": true, "module": "commonjs", "importHelpers": true, $options }, "include": ["src/**/*.ts"] }"""
        return ProjectCompiler(InMemoryVfs(mapOf("/proj/tsconfig.json" to tsconfig) + files)).build("/proj", noEmit = true)
    }

    private fun rows(r: ProjectCompiler.Result): List<String> =
        r.diagnostics.map { "${it.fileName?.substringAfterLast('/')}:${it.line}:${it.character} TS${it.code} ${it.message}" }

    private val asyncModule = "export {};\nasync function f() { await 1; }\n"

    @Test
    fun `a complete tslib nothing imports satisfies the helpers import`() {
        val r = build(
            """"target": "es2016"""",
            mapOf("/proj/src/t.ts" to asyncModule, "/proj/node_modules/tslib/index.d.ts" to awaiterOnly),
        )
        assert(rows(r).isEmpty())
        assert("/proj/node_modules/tslib/index.d.ts" in r.programFiles)
    }

    @Test
    fun `a tslib missing the helper names it - TS2343`() {
        val r = build(
            """"target": "es2016"""",
            mapOf(
                "/proj/src/t.ts" to "export {};\nasync function f() { await 1; }\nasync function g() { await 2; }\n",
                "/proj/node_modules/tslib/index.d.ts" to "export {};\n",
            ),
        )
        assert(rows(r) == listOf(
            "t.ts:2:16 TS2343 This syntax requires an imported helper named '__awaiter' which does not exist in 'tslib'. Consider upgrading your version of 'tslib'.",
        ))
    }

    @Test
    fun `a private field and a decorator name their helpers`() {
        val r = build(
            """"target": "es2020", "experimentalDecorators": true""",
            mapOf(
                "/proj/src/p.ts" to "export class C {\n    #x = 1;\n    get() { return this.#x; }\n}\n",
                "/proj/src/dec.ts" to "declare function dec(t: any): any;\n@dec export class D {}\n",
                "/proj/node_modules/tslib/index.d.ts" to "export {};\n",
            ),
        )
        assert(rows(r).sorted() == listOf(
            "dec.ts:2:1 TS2343 This syntax requires an imported helper named '__decorate' which does not exist in 'tslib'. Consider upgrading your version of 'tslib'.",
            "p.ts:3:20 TS2343 This syntax requires an imported helper named '__classPrivateFieldGet' which does not exist in 'tslib'. Consider upgrading your version of 'tslib'.",
        ))
    }

    @Test
    fun `a tslib found through its package json types entry satisfies decorators and imports`() {
        val r = build(
            """"target": "es2016", "experimentalDecorators": true""",
            mapOf(
                "/proj/src/t.ts" to "declare function dec(t: any): any;\nimport * as m from \"./m\";\n@dec export class C {}\nexport async function f() { await m; }\n",
                "/proj/src/m.ts" to "export const x = 1;\n",
                "/proj/node_modules/tslib/package.json" to """{ "name": "tslib", "main": "tslib.js", "types": "tslib.d.ts" }""",
                "/proj/node_modules/tslib/tslib.js" to "\n",
                "/proj/node_modules/tslib/tslib.d.ts" to awaiterOnly +
                    "export declare function __decorate(decorators: Function[], target: any, key?: string | symbol, desc?: any): any;\n" +
                    "export declare function __importStar<T>(mod: T): T;\n",
            ),
        )
        assert(rows(r).isEmpty())
    }

    @Test
    fun `a tslib in a sibling package does not satisfy a file outside it`() {
        val r = build(
            """"target": "es2016"""",
            mapOf(
                "/proj/src/a/t.ts" to asyncModule,
                "/proj/src/b/u.ts" to asyncModule,
                "/proj/src/b/node_modules/tslib/index.d.ts" to awaiterOnly,
            ),
        )
        assert(rows(r) == listOf(
            "t.ts:2:16 TS2354 This syntax requires an imported helper but module 'tslib' cannot be found.",
        ))
    }

    @Test
    fun `negative control - no tslib anywhere is TS2354 and is not an unresolved import`() {
        val r = build(""""target": "es2016"""", mapOf("/proj/src/t.ts" to asyncModule))
        assert(rows(r) == listOf(
            "t.ts:2:16 TS2354 This syntax requires an imported helper but module 'tslib' cannot be found.",
        ))
        assert(r.unresolved.isEmpty())
    }

    @Test
    fun `negative control - a script file takes no helpers import`() {
        val r = build(
            """"target": "es2016"""",
            mapOf(
                "/proj/src/t.ts" to "async function f() { await 1; }\n",
                "/proj/node_modules/tslib/index.d.ts" to "export {};\n",
            ),
        )
        assert(rows(r).isEmpty())
        assert("/proj/node_modules/tslib/index.d.ts" !in r.programFiles)
    }
}
