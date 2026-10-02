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
 * (CHK.202) Two tsgo rules a JavaScript file meets through the project path, neither of
 * which the corpus can gate (an `allowJs`-without-`checkJs` fixture has no `.errors.txt`,
 * and the corpus harness has no crawl). Every expectation below is tsgo 7.0.2's output
 * for the same project, row for row.
 *
 * 1. **Unchecked JavaScript** ([UncheckedJsFiles]): a `.js` file under `allowJs` with
 *    `checkJs` UNSET and no `// @ts-check` reports only tsgo's `plainJSErrors` binder /
 *    grammar rows and honours no comment directive; an EXPLICIT `checkJs: false`, or a
 *    leading `// @ts-nocheck` in any file, reports nothing.
 * 2. **`maxNodeModuleJsDepth: 0`** ([UntypedModuleImports]): a JavaScript file reached
 *    through `node_modules` never joins the program; its import is an implicit-`any`
 *    module, TS7016 under `noImplicitAny`. A nearer JS-only package loses to a farther
 *    `@types` package, as tsgo's TypeScript-first resolution pass decides.
 */
class UncheckedJsAndUntypedModuleTest {

    private val strictJs = """"strict": true, "allowJs": true, "target": "esnext", "noEmit": true, "types": []"""

    private val lib = """
        function g(a) { return a }
        module.exports = function (data) { return data.x }
        exports.h = function (q) {}
    """.trimIndent()

    private fun build(files: Map<String, String>): ProjectCompiler.Result =
        ProjectCompiler(InMemoryVfs(files)).build("/proj", noEmit = true)

    private fun rows(result: ProjectCompiler.Result): List<String> =
        result.diagnostics.map { "TS${it.code} ${it.fileName}:${it.line}:${it.character} ${it.message}" }.sorted()

    private fun jsProject(options: String, libJs: String, ts: String = "export const z = 1;") = mapOf(
        "/proj/tsconfig.json" to """{ "compilerOptions": { $strictJs, "module": "esnext"$options }, "include": ["*.js", "*.ts"] }""",
        "/proj/lib.js" to libJs,
        "/proj/t.ts" to ts,
    )

    // --- 1. unchecked JavaScript ----------------------------------------------------

    @Test
    fun `a plain JavaScript file reports no semantic row`() {
        val r = rows(build(jsProject("", lib)))
        assert(r.isEmpty())
    }

    @Test
    fun `control - checkJs true reports the same file's semantic rows`() {
        val r = rows(build(jsProject(""", "checkJs": true""", lib)))
        assert(r == listOf(
            "TS2309 /proj/lib.js:2:1 An export assignment cannot be used in a module with other exported elements.",
            "TS2339 /proj/lib.js:3:9 Property 'h' does not exist on type '(data: any) => any'.",
            "TS7006 /proj/lib.js:1:12 Parameter 'a' implicitly has an 'any' type.",
            "TS7006 /proj/lib.js:2:28 Parameter 'data' implicitly has an 'any' type.",
            "TS7006 /proj/lib.js:3:23 Parameter 'q' implicitly has an 'any' type.",
        ))
    }

    @Test
    fun `a ts-check pragma keeps a JavaScript file checked without checkJs`() {
        val r = rows(build(jsProject("", "// @ts-check\n$lib")))
        assert(r == listOf(
            "TS2309 /proj/lib.js:3:1 An export assignment cannot be used in a module with other exported elements.",
            "TS7006 /proj/lib.js:2:12 Parameter 'a' implicitly has an 'any' type.",
            "TS7006 /proj/lib.js:3:28 Parameter 'data' implicitly has an 'any' type.",
            "TS7006 /proj/lib.js:4:23 Parameter 'q' implicitly has an 'any' type.",
        ))
    }

    private val binderJs = """
        let a = 1;
        let a = 2;
        function f(p) { return p.q }
    """.trimIndent()

    @Test
    fun `a plain JavaScript file keeps its binder rows`() {
        val r = rows(build(jsProject("", binderJs)))
        assert(r == listOf(
            "TS2451 /proj/lib.js:1:5 Cannot redeclare block-scoped variable 'a'.",
            "TS2451 /proj/lib.js:2:5 Cannot redeclare block-scoped variable 'a'.",
        ))
    }

    @Test
    fun `an explicit checkJs false drops even the binder rows`() {
        val r = rows(build(jsProject(""", "checkJs": false""", binderJs)))
        assert(r.isEmpty())
    }

    @Test
    fun `a plain JavaScript file honours no comment directive`() {
        val js = """
            // @ts-expect-error
            const ok = 1;
            // @ts-ignore
            let b = 1; let b = 2;
            export {}
        """.trimIndent()
        val r = rows(build(jsProject("", js)))
        assert(r == listOf(
            "TS2451 /proj/lib.js:4:16 Cannot redeclare block-scoped variable 'b'.",
            "TS2451 /proj/lib.js:4:5 Cannot redeclare block-scoped variable 'b'.",
        ))
    }

    @Test
    fun `TypeScript-only syntax stays reported in a plain and in a ts-nocheck JavaScript file`() {
        val plain = rows(build(jsProject("", "function f(x: number) { return x }\nenum E {}\n")))
        assert(plain == listOf(
            "TS8006 /proj/lib.js:2:6 'enum' declarations can only be used in TypeScript files.",
            "TS8010 /proj/lib.js:1:15 Type annotations can only be used in TypeScript files.",
        ))
        val noCheck = rows(build(jsProject("", "// @ts-nocheck\nfunction f(x: number) { return x }\nlet q = 1; let q = 2;\n")))
        assert(noCheck == listOf("TS8010 /proj/lib.js:2:15 Type annotations can only be used in TypeScript files."))
    }

    @Test
    fun `a leading ts-nocheck silences a TypeScript file`() {
        val files = mapOf(
            "/proj/tsconfig.json" to """{ "compilerOptions": { $strictJs, "module": "esnext" }, "include": ["*.ts"] }""",
            "/proj/t.ts" to "// @ts-nocheck\nconst s: string = 1;\nexport {}\n",
            "/proj/u.ts" to "const n: string = 2;\nexport {}\n",
        )
        val r = rows(build(files))
        assert(r == listOf("TS2322 /proj/u.ts:1:7 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `a ts-nocheck after the first statement is not a directive`() {
        val r = rows(build(jsProject(""", "checkJs": true""", "function g(a) { return a }\n// @ts-nocheck\n")))
        assert(r == listOf("TS7006 /proj/lib.js:1:12 Parameter 'a' implicitly has an 'any' type."))
    }

    @Test
    fun `a plain JavaScript file still types its importers`() {
        val ts = "import { num } from \"./lib.js\";\nconst s: string = num(1);\nexport {}\n"
        val result = build(jsProject("", "export function num(x) { return 1 }\n", ts))
        assert(rows(result) == listOf("TS2322 /proj/t.ts:2:7 Type 'number' is not assignable to type 'string'."))
        assert("/proj/lib.js" in result.programFiles)
    }

    // --- 2. node_modules JavaScript -------------------------------------------------

    private fun pkgProject(
        module: String,
        ts: String,
        pkgJson: String = """{ "name": "pkg", "main": "index.js" }""",
        strict: Boolean = true,
        extra: Map<String, String> = emptyMap(),
    ) = mapOf(
        "/proj/tsconfig.json" to """{ "compilerOptions": { "strict": $strict, "allowJs": true, "target": "esnext", $module, "noEmit": true, "types": [] }, "include": ["*.ts"] }""",
        "/proj/node_modules/pkg/package.json" to pkgJson,
        "/proj/node_modules/pkg/index.js" to "module.exports = function (data, warn) { return data.x }\n",
        "/proj/t.ts" to ts,
    ) + extra

    private val node16 = """"module": "node20", "moduleResolution": "node16""""
    private val bundler = """"module": "esnext", "moduleResolution": "bundler""""
    private val untypedHead =
        "TS7016 /proj/t.ts:1:20 Could not find a declaration file for module 'pkg'. '/proj/node_modules/pkg/index.js' implicitly has an 'any' type."

    @Test
    fun `a node_modules JavaScript import is an untyped module and not a program file`() {
        val result = build(pkgProject(node16, "import f = require(\"pkg\");\nconst n: string = f(1, 2);\n"))
        assert(rows(result) == listOf(untypedHead))
        assert(result.programFiles == listOf("/proj/t.ts"))
    }

    @Test
    fun `a package id adds the npm install elaboration`() {
        val result = build(pkgProject(
            node16, "import f = require(\"pkg\");\n",
            pkgJson = """{ "name": "pkg", "version": "1.0.0", "main": "index.js" }""",
        ))
        val d = result.diagnostics.single()
        assert(d.messageChain == listOf(
            "  Try `npm i --save-dev @types/pkg` if it exists or add a new declaration (.d.ts) file containing `declare module 'pkg';`",
        ))
    }

    @Test
    fun `under bundler the untyped import is any and not a missing module`() {
        val result = build(pkgProject(bundler, "import f from \"pkg\";\nconst n: number = f.x.y;\nexport {}\n"))
        assert(rows(result) == listOf(
            "TS7016 /proj/t.ts:1:15 Could not find a declaration file for module 'pkg'. '/proj/node_modules/pkg/index.js' implicitly has an 'any' type.",
        ))
    }

    @Test
    fun `no TS7016 without noImplicitAny`() {
        val result = build(pkgProject(bundler, "import f from \"pkg\";\nconst n: number = f.x;\nexport {}\n", strict = false))
        assert(result.diagnostics.isEmpty())
    }

    @Test
    fun `no row for a side-effect import of an untyped module`() {
        val result = build(pkgProject(bundler, "import \"pkg\";\nexport {}\n"))
        assert(result.diagnostics.isEmpty())
    }

    @Test
    fun `an ambient module declaration wins over the untyped resolution`() {
        val result = build(pkgProject(
            bundler, "import { v } from \"pkg\";\nconst s: string = v;\nexport {}\n",
            extra = mapOf("/proj/amb.d.ts" to "declare module \"pkg\" { export const v: number; }\n"),
        ))
        assert(rows(result) == listOf("TS2322 /proj/t.ts:2:7 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `a farther types package beats a nearer JavaScript-only package`() {
        val files = mapOf(
            "/proj/tsconfig.json" to """{ "compilerOptions": { $strictJs, $bundler }, "include": ["*.ts"] }""",
            "/proj/node_modules/a/package.json" to """{ "name": "a", "version": "1.0.0", "types": "index.d.ts" }""",
            "/proj/node_modules/a/index.d.ts" to "import { B } from \"b\";\nexport declare function mk(): B;\n",
            "/proj/node_modules/a/node_modules/b/package.json" to """{ "name": "b", "version": "1.0.0", "main": "index.js" }""",
            "/proj/node_modules/a/node_modules/b/index.js" to "exports.x = 1\n",
            "/proj/node_modules/@types/b/package.json" to """{ "name": "@types/b", "version": "1.0.0" }""",
            "/proj/node_modules/@types/b/index.d.ts" to "export interface B { k: number }\n",
            "/proj/t.ts" to "import { mk } from \"a\";\nconst s: string = mk().k;\nexport {}\n",
        )
        val result = build(files)
        assert(rows(result) == listOf("TS2322 /proj/t.ts:2:7 Type 'number' is not assignable to type 'string'."))
        assert(result.programFiles.sorted() == listOf(
            "/proj/node_modules/@types/b/index.d.ts", "/proj/node_modules/a/index.d.ts", "/proj/t.ts",
        ))
    }

    @Test
    fun `a checkJs require of an untyped package is not a missing module`() {
        // tsgo reports TS7016 at the `require` too, which this compiler does not yet reach
        // (only import / export / import-equals statements); what is pinned is that the
        // single-segment `require` TS2307 rule no longer fires once the package's file
        // is out of the program.
        val files = mapOf(
            "/proj/tsconfig.json" to """{ "compilerOptions": { $strictJs, "checkJs": true, $bundler }, "include": ["*.js", "*.ts"] }""",
            "/proj/node_modules/pkg/package.json" to """{ "name": "pkg", "main": "index.js" }""",
            "/proj/node_modules/pkg/index.js" to "module.exports = 1\n",
            "/proj/lib.js" to "const p = require(\"pkg\");\nmodule.exports = p;\n",
            "/proj/t.ts" to "export const z = 1;\n",
        )
        val result = build(files)
        assert(result.diagnostics.none { it.code == 2307 })
        assert(result.programFiles.sorted() == listOf("/proj/lib.js", "/proj/t.ts"))
    }

    @Test
    fun `control - a relative JavaScript file outside node_modules stays in the program`() {
        val files = mapOf(
            "/proj/tsconfig.json" to """{ "compilerOptions": { $strictJs, $bundler }, "include": ["*.ts"] }""",
            "/proj/lib.js" to "export function num(p) { return 1 }\n",
            "/proj/t.ts" to "import { num } from \"./lib.js\";\nconst s: string = num(1);\nexport {}\n",
        )
        val result = build(files)
        assert(rows(result) == listOf("TS2322 /proj/t.ts:2:7 Type 'number' is not assignable to type 'string'."))
        assert("/proj/lib.js" in result.programFiles)
    }
}
