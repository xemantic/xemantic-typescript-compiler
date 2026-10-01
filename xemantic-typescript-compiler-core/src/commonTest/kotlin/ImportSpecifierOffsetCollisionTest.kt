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
 * (CHK.192) An `ImportSpecifier` is a data class, so two files that spell the same
 * specifier at the same offsets hold EQUAL nodes. The structural `enclosingImportIndex`
 * used to hand such a specifier the FIRST file's import statement: `b.ts`'s
 * `import { B } from "./m2"` resolved through `a.ts`'s `"./m1"` and typed `B` as m1's
 * class — a silent wrong type (ours `Type 'number'`, tsgo `Type 'string'`). A
 * specifier's own statement now comes from its parent chain (`Checker.ownTopLevelImportOf`).
 *
 * Every expectation is tsgo 7.0.2's row set for the same project, and a wrong-type pin
 * asserts the type TEXT — the row's code and position are identical in the broken arm.
 * The controls (default / namespace / `import =` imports, an offset-shifted importer)
 * never went through the index and were already right.
 *
 * The collision had also been HIDING a gap: an importer of a same-name from-clause
 * re-export (`export { X } from "m"`) resolved straight to `m` only when its specifier
 * collided with some file's `import { X } from "m"` (corpus `constEnumNoEmitReexport`).
 * `NameResolver.resolveAlias` now follows such an `ExportSpecifier` itself; a RENAMING
 * clause stays on its existing fallback ((CHK.190)).
 */
class ImportSpecifierOffsetCollisionTest {

    private val esm =
        """{ "compilerOptions": { "strict": true, "target": "es2022", "module": "esnext", "moduleResolution": "bundler", "noEmit": true, "lib": ["es2022"], "types": [] }, "include": ["src"] }"""

    private val cjs =
        """{ "compilerOptions": { "strict": true, "target": "es2022", "module": "commonjs", "noEmit": true, "lib": ["es2022"], "types": [] }, "include": ["src"] }"""

    private val m1 = "export class B { p = 1; }\n"
    private val m2 = "export class B { p = \"s\"; }\n"

    private fun rows(files: Map<String, String>, config: String = esm): List<String> {
        val vfs = InMemoryVfs(files.mapKeys { "/proj/src/${it.key}" } + ("/proj/tsconfig.json" to config))
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        return result.diagnostics
            .map { "${it.fileName?.substringAfterLast('/')}:${it.line} TS${it.code} ${it.message}" }
            .sorted()
    }

    private fun boolRow(file: String, line: Int, type: String) =
        "$file:$line TS2322 Type '$type' is not assignable to type 'boolean'."

    @Test
    fun `named imports colliding at offset zero resolve through their own statements`() {
        val actual = rows(
            mapOf(
                "m1.ts" to m1, "m2.ts" to m2,
                "a.ts" to "import { B } from \"./m1\";\nconst x: boolean = new B().p;\n",
                "b.ts" to "import { B } from \"./m2\";\nconst x: boolean = new B().p;\n",
            ),
        )
        assert(actual == listOf(boolRow("a.ts", 2, "number"), boolRow("b.ts", 2, "string")))
    }

    @Test
    fun `three colliding importers each see their own module`() {
        val actual = rows(
            mapOf(
                "m1.ts" to m1, "m2.ts" to m2, "m3.ts" to "export class B { p = true; }\n",
                "a.ts" to "import { B } from \"./m1\";\nconst x: string = new B().p;\n",
                "b.ts" to "import { B } from \"./m2\";\nconst x: boolean = new B().p;\n",
                "c.ts" to "import { B } from \"./m3\";\nconst x: number = new B().p;\n",
            ),
        )
        assert(
            actual == listOf(
                "a.ts:2 TS2322 Type 'number' is not assignable to type 'string'.",
                boolRow("b.ts", 2, "string"),
                "c.ts:2 TS2322 Type 'boolean' is not assignable to type 'number'.",
            ),
        )
    }

    @Test
    fun `a collision at a non-zero offset resolves through its own statement`() {
        val actual = rows(
            mapOf(
                "m1.ts" to m1, "m2.ts" to m2,
                "a.ts" to "// header line\nimport { B } from \"./m1\";\nconst x: boolean = new B().p;\n",
                "b.ts" to "// header line\nimport { B } from \"./m2\";\nconst x: boolean = new B().p;\n",
            ),
        )
        assert(actual == listOf(boolRow("a.ts", 3, "number"), boolRow("b.ts", 3, "string")))
    }

    @Test
    fun `a renaming specifier colliding across files resolves through its own statement`() {
        val actual = rows(
            mapOf(
                "m1.ts" to m1, "m2.ts" to m2,
                "a.ts" to "import { B as C } from \"./m1\";\nconst x: boolean = new C().p;\n",
                "b.ts" to "import { B as C } from \"./m2\";\nconst x: boolean = new C().p;\n",
            ),
        )
        assert(actual == listOf(boolRow("a.ts", 2, "number"), boolRow("b.ts", 2, "string")))
    }

    @Test
    fun `a declaration file colliding with a source file resolves through its own statement`() {
        val actual = rows(
            mapOf(
                "m1.ts" to m1, "m2.ts" to m2,
                "a.ts" to "import { B } from \"./m1\";\nconst x: boolean = new B().p;\n",
                "b.d.ts" to "import { B } from \"./m2\";\nexport declare const v: B;\n",
                "c.ts" to "// shifted\nimport { v } from \"./b\";\nconst q: boolean = v.p;\n",
            ),
        )
        assert(actual == listOf(boolRow("a.ts", 2, "number"), boolRow("c.ts", 3, "string")))
    }

    @Test
    fun `colliding function interface variable and namespace imports each see their own module`() {
        val fn = rows(
            mapOf(
                "m1.ts" to "export function f(): number { return 1; }\n",
                "m2.ts" to "export function f(): string { return \"s\"; }\n",
                "a.ts" to "import { f } from \"./m1\";\nconst x: boolean = f();\n",
                "b.ts" to "import { f } from \"./m2\";\nconst x: boolean = f();\n",
            ),
        )
        assert(fn == listOf(boolRow("a.ts", 2, "number"), boolRow("b.ts", 2, "string")))
        val iface = rows(
            mapOf(
                "m1.ts" to "export interface I { p: number }\n",
                "m2.ts" to "export interface I { p: string }\n",
                "a.ts" to "import { I } from \"./m1\";\ndeclare const i: I;\nconst x: boolean = i.p;\n",
                "b.ts" to "import { I } from \"./m2\";\ndeclare const i: I;\nconst x: boolean = i.p;\n",
            ),
        )
        assert(iface == listOf(boolRow("a.ts", 3, "number"), boolRow("b.ts", 3, "string")))
        val variable = rows(
            mapOf(
                "m1.ts" to "export const v: number = 1;\n",
                "m2.ts" to "export const v: string = \"s\";\n",
                "a.ts" to "import { v } from \"./m1\";\nconst x: boolean = v;\n",
                "b.ts" to "import { v } from \"./m2\";\nconst x: boolean = v;\n",
            ),
        )
        assert(variable == listOf(boolRow("a.ts", 2, "number"), boolRow("b.ts", 2, "string")))
        val namespace = rows(
            mapOf(
                "m1.ts" to "export namespace N { export const v: number = 1; }\n",
                "m2.ts" to "export namespace N { export const v: string = \"s\"; }\n",
                "a.ts" to "import { N } from \"./m1\";\nconst x: boolean = N.v;\n",
                "b.ts" to "import { N } from \"./m2\";\nconst x: boolean = N.v;\n",
            ),
        )
        assert(namespace == listOf(boolRow("a.ts", 2, "number"), boolRow("b.ts", 2, "string")))
    }

    @Test
    fun `a colliding imported type guard narrows to its own module's type`() {
        val actual = rows(
            mapOf(
                "m1.ts" to "export function isS(x: unknown): x is number { return true; }\n",
                "m2.ts" to "export function isS(x: unknown): x is string { return true; }\n",
                "a.ts" to "import { isS } from \"./m1\";\ndeclare const u: unknown;\nif (isS(u)) { const x: boolean = u; }\n",
                "b.ts" to "import { isS } from \"./m2\";\ndeclare const u: unknown;\nif (isS(u)) { const x: boolean = u; }\n",
            ),
        )
        assert(actual == listOf(boolRow("a.ts", 3, "number"), boolRow("b.ts", 3, "string")))
    }

    @Test
    fun `an import then export barrel colliding with its importer does not resolve through itself`() {
        // a.ts and r.ts both start `import { B } from "...";` — equal specifiers. Before
        // the fix r's specifier took a.ts's statement, a resolved through ITSELF, and
        // `B` was `any`: the row below went missing.
        val actual = rows(
            mapOf(
                "m.ts" to "export class B { p = 1; }\n",
                "a.ts" to "import { B } from \"./r\";\nconst x: string = new B().p;\n",
                "r.ts" to "import { B } from \"./m\";\nexport { B };\n",
            ),
        )
        assert(actual == listOf("a.ts:2 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    private val barrelTarget = "export class B { p = 1; }\nexport const n = 1;\nexport type T = { q: number };\n"

    private val barrelImporter = """
        |// importer, offset-shifted off every barrel statement
        |import { B, n, T } from "./r";
        |const x: string = new B().p;
        |const y: string = n;
        |declare const tb: B; const z: string = tb.p;
        |declare const tq: typeof n; const w: string = tq;
        |declare const tt: T; const u: string = tt.q;
        |""".trimMargin()

    private val barrelRows = listOf(3, 4, 5, 6, 7).map {
        "a.ts:$it TS2322 Type 'number' is not assignable to type 'string'."
    }

    @Test
    fun `a same-name from-clause re-export resolves to its target module`() {
        // Before (CHK.192) the barrel's `locals[B]` was the ExportSpecifier alias and
        // nothing followed it: every name below read `any` and all five rows were missing.
        val actual = rows(
            mapOf("m.ts" to barrelTarget, "r.ts" to "export { B, n, T } from \"./m\";\n", "a.ts" to barrelImporter),
        )
        assert(actual == barrelRows)
    }

    @Test
    fun `a chain of same-name from-clause re-exports resolves to the declaring module`() {
        val actual = rows(
            mapOf(
                "m.ts" to barrelTarget,
                "r1.ts" to "export { B, n, T } from \"./m\";\n",
                "r.ts" to "export { B, n, T } from \"./r1\";\n",
                "a.ts" to barrelImporter,
            ),
        )
        assert(actual == barrelRows)
    }

    @Test
    fun `colliding from-clause re-exports each resolve through their own declaration`() {
        val actual = rows(
            mapOf(
                "m1.ts" to m1, "m2.ts" to m2,
                "r1.ts" to "export { B } from \"./m1\";\n",
                "r2.ts" to "export { B } from \"./m2\";\n",
                "a.ts" to "// a\nimport { B } from \"./r1\";\nconst x: boolean = new B().p;\n",
                "b.ts" to "// bb\n\nimport { B } from \"./r2\";\nconst x: boolean = new B().p;\n",
            ),
        )
        assert(actual == listOf(boolRow("a.ts", 3, "number"), boolRow("b.ts", 4, "string")))
    }

    @Test
    fun `negative control - a renaming from-clause re-export still resolves through the star fallback`() {
        val actual = rows(
            mapOf(
                "m.ts" to barrelTarget,
                "r.ts" to "export { B as C } from \"./m\";\n",
                "a.ts" to "// shifted\nimport { C } from \"./r\";\nconst x: string = new C().p;\n",
            ),
        )
        assert(actual == listOf("a.ts:3 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `negative control - default namespace and require imports never collided`() {
        val default = rows(
            mapOf(
                "m1.ts" to "export default class B { p = 1; }\n",
                "m2.ts" to "export default class B { p = \"s\"; }\n",
                "a.ts" to "import B from \"./m1\";\nconst x: boolean = new B().p;\n",
                "b.ts" to "import B from \"./m2\";\nconst x: boolean = new B().p;\n",
            ),
        )
        assert(default == listOf(boolRow("a.ts", 2, "number"), boolRow("b.ts", 2, "string")))
        val namespace = rows(
            mapOf(
                "m1.ts" to m1, "m2.ts" to m2,
                "a.ts" to "import * as N from \"./m1\";\nconst x: boolean = new N.B().p;\n",
                "b.ts" to "import * as N from \"./m2\";\nconst x: boolean = new N.B().p;\n",
            ),
        )
        assert(namespace == listOf(boolRow("a.ts", 2, "number"), boolRow("b.ts", 2, "string")))
        val require = rows(
            mapOf(
                "m1.ts" to "class B { p = 1; }\nexport = B;\n",
                "m2.ts" to "class B { p = \"s\"; }\nexport = B;\n",
                "a.ts" to "import B = require(\"./m1\");\nconst x: boolean = new B().p;\n",
                "b.ts" to "import B = require(\"./m2\");\nconst x: boolean = new B().p;\n",
            ),
            config = cjs,
        )
        assert(require == listOf(boolRow("a.ts", 2, "number"), boolRow("b.ts", 2, "string")))
    }

    @Test
    fun `the parent chain serves every specifier so the structural index is never built`() {
        val before = EagerIndexCensus.enclosingImportBuilds
        try {
            EagerIndexCensus.enclosingImportBuilds = 0
            rows(
                mapOf(
                    "m1.ts" to m1, "m2.ts" to m2,
                    "a.ts" to "import { B } from \"./m1\";\nconst x: boolean = new B().p;\n",
                    "b.ts" to "import { B } from \"./m2\";\nconst x: boolean = new B().p;\n",
                ),
            )
            val builds = EagerIndexCensus.enclosingImportBuilds
            assert(builds == 0)
        } finally {
            EagerIndexCensus.enclosingImportBuilds = before
        }
    }
}
