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
 * (CHK.190) residues — the diagnostics of a module's import / export ALIAS CHAIN, every
 * expectation tsgo 7.0.2's full row set for the same project (cells under
 * `build/bench/p18252-agent/cells`):
 *
 *  - TS2303 at every alias declaration ON a named re-export cycle, none at an alias that
 *    only leads into one ([ImportExportAliasDiagnostics.checkReExportCycles]);
 *  - TS1361 / TS1362 at a value use of a binding whose chain carries a type-only
 *    declaration, with the "was imported / exported here." note
 *    ([ImportExportAliasDiagnostics.checkTypeOnlyValueUses]);
 *  - an `export { x } from` naming an absent member takes the import's order —
 *    TS2724, then TS2614 when the target has a default export, then TS2460 / TS2459 /
 *    TS2305 (`Checker.emitAbsentNamedMember`); an IMPORT with a spelling suggestion
 *    against a default-exporting target is TS2724, not TS2614;
 *  - `import { M }` of an `export * as M from` barrel names the module object in VALUE
 *    positions too (`NameResolver.resolveAlias`'s ExportDeclaration arm).
 */
class ImportExportAliasChainDiagnosticsTest {

    private val esm =
        """{ "compilerOptions": { "strict": true, "target": "es2022", "module": "esnext", "moduleResolution": "bundler", "noEmit": true, "lib": ["es2022"], "types": [] }, "include": ["src"] }"""

    private fun build(files: Map<String, String>): List<Diagnostic> {
        val vfs = InMemoryVfs(files.mapKeys { "/proj/src/${it.key}" } + ("/proj/tsconfig.json" to esm))
        return ProjectCompiler(vfs).build("/proj", noEmit = true).diagnostics
    }

    private fun Diagnostic.row() = "${fileName?.substringAfterLast('/')}:$line:$character TS$code $message"

    private fun rows(files: Map<String, String>): List<String> = build(files).map { it.row() }.sorted()

    /** Rows with their related notes (file:line:col code message) and span length. */
    private fun fullRows(files: Map<String, String>): List<String> = build(files).map { d ->
        d.row() + " len=${d.length}" + d.relatedInformation.joinToString("") {
            " | ${it.fileName?.substringAfterLast('/')}:${it.line}:${it.character} TS${it.code} ${it.message} len=${it.length}"
        }
    }.sorted()

    private fun cycle(file: String, line: Int, col: Int, name: String) =
        "$file:$line:$col TS2303 Circular definition of import alias '$name'."

    // ---------------------------------------------------------------- TS2303

    @Test
    fun `a named re-export cycle reports both clauses and leaves the importer silent`() {
        val actual = rows(mapOf(
            "a.ts" to "// i\nimport { x } from \"./r\";\nconst y: string = x;\n",
            "r.ts" to "export { x } from \"./s\";\n",
            "s.ts" to "export { x } from \"./r\";\n",
        ))
        assert(actual == listOf(cycle("r.ts", 1, 10, "x"), cycle("s.ts", 1, 10, "x")))
    }

    @Test
    fun `a renaming cycle spans the whole specifier and names the exported name`() {
        val diags = build(mapOf(
            "r.ts" to "export { x as y } from \"./s\";\n",
            "s.ts" to "export { y as x } from \"./r\";\n",
        ))
        val actual = diags.map { it.row() + " len=${it.length}" }.sorted()
        assert(actual == listOf(cycle("r.ts", 1, 10, "y") + " len=6", cycle("s.ts", 1, 10, "x") + " len=6"))
    }

    @Test
    fun `a three-file cycle reports every member`() {
        val actual = rows(mapOf(
            "r.ts" to "export { x } from \"./s\";\n",
            "s.ts" to "export { x } from \"./t\";\n",
            "t.ts" to "export { x } from \"./r\";\n",
        ))
        assert(actual == listOf(cycle("r.ts", 1, 10, "x"), cycle("s.ts", 1, 10, "x"), cycle("t.ts", 1, 10, "x")))
    }

    @Test
    fun `an import-then-export cycle reports the imports and the local clauses`() {
        val actual = rows(mapOf(
            "r.ts" to "import { x } from \"./s\";\nexport { x };\n",
            "s.ts" to "import { x } from \"./r\";\nexport { x };\n",
        ))
        assert(actual == listOf(
            cycle("r.ts", 1, 10, "x"), cycle("r.ts", 2, 10, "x"), cycle("s.ts", 1, 10, "x"), cycle("s.ts", 2, 10, "x"),
        ))
    }

    @Test
    fun `a self re-export is a one-member cycle`() {
        val actual = rows(mapOf("r.ts" to "export { x } from \"./r\";\n"))
        assert(actual == listOf(cycle("r.ts", 1, 10, "x")))
    }

    @Test
    fun `a default import and default clauses on a cycle are each reported`() {
        val actual = rows(mapOf(
            "r.ts" to "import d from \"./s\";\nexport { d as default };\n",
            "s.ts" to "export { default } from \"./r\";\n",
        ))
        assert(actual == listOf(cycle("r.ts", 1, 8, "d"), cycle("r.ts", 2, 10, "default"), cycle("s.ts", 1, 10, "default")))
    }

    @Test
    fun `a type-only cycle is reported at both clauses`() {
        // tsgo's submoduleAccepted `circular1` (TS 6 reported only the second clause).
        val actual = rows(mapOf(
            "a.ts" to "export type { A } from './b';\n",
            "b.ts" to "export type { A } from './a';\n",
        ))
        assert(actual == listOf(cycle("a.ts", 1, 15, "A"), cycle("b.ts", 1, 15, "A")))
    }

    @Test
    fun `a type-only import-then-export cycle is reported at all four declarations`() {
        // tsgo's submoduleAccepted `circular3`.
        val actual = rows(mapOf(
            "a.ts" to "import type { A } from './b';\nexport type { A as B };\n",
            "b.ts" to "import type { B } from './a';\nexport type { B as A };\n",
        ))
        assert(actual == listOf(
            cycle("a.ts", 1, 15, "A"), cycle("a.ts", 2, 15, "B"), cycle("b.ts", 1, 15, "B"), cycle("b.ts", 2, 15, "A"),
        ))
    }

    @Test
    fun `cycles in declaration files are reported`() {
        val actual = rows(mapOf(
            "r.d.ts" to "export { x } from \"./s\";\n",
            "s.d.ts" to "export { x } from \"./r\";\n",
        ))
        assert(actual == listOf(cycle("r.d.ts", 1, 10, "x"), cycle("s.d.ts", 1, 10, "x")))
    }

    @Test
    fun `negative control - an alias leading into a cycle and a real export beside it are not reported`() {
        val actual = rows(mapOf(
            "a.ts" to "// i\nimport { x, z } from \"./s\";\nconst q: string = z;\n",
            "r.ts" to "export { x } from \"./s\";\n",
            "s.ts" to "export { x } from \"./r\";\nexport const z = 1;\n",
        ))
        assert(actual == listOf(
            "a.ts:3:7 TS2322 Type 'number' is not assignable to type 'string'.",
            cycle("r.ts", 1, 10, "x"), cycle("s.ts", 1, 10, "x"),
        ))
    }

    @Test
    fun `negative control - an acyclic chain of re-exports is not a cycle`() {
        val actual = rows(mapOf(
            "m.ts" to "export const x = 1;\n",
            "r.ts" to "export { x } from \"./m\";\n",
            "s.ts" to "export { x as y } from \"./r\";\n",
            "a.ts" to "// i\nimport { y } from \"./s\";\nconst q: string = y;\n",
        ))
        assert(actual == listOf("a.ts:3:7 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    // ------------------------------------------------------------ TS1361 / TS1362

    private val m = """
        export class B { p = 1; }
        export const n = 1;
        export type T = { q: number };
        export function f() { return 1; }
        export namespace NS { export const v = 1; }
    """.trimIndent() + "\n"

    @Test
    fun `an export type re-export used as a value is TS1362 with the exported-here note`() {
        val actual = fullRows(mapOf(
            "m.ts" to m,
            "r.ts" to "export type { B, T } from \"./m\";\n",
            "a.ts" to "// i\nimport { B, T } from \"./r\";\ndeclare const tb: B; const z: string = tb.p;\nconst x = new B();\n",
        ))
        assert(actual == listOf(
            "a.ts:3:28 TS2322 Type 'number' is not assignable to type 'string'. len=1",
            "a.ts:4:15 TS1362 'B' cannot be used as a value because it was exported using 'export type'. len=1" +
                " | r.ts:1:15 TS1377 'B' was exported here. len=1",
        ))
    }

    @Test
    fun `import type bindings used as values are TS1361 at every expression position`() {
        val a = """
            // i
            import type { B, n, T, f, NS } from "./m";
            const x = new B();
            const y = n + 1;
            f();
            NS.v;
            let k: B; let kk: typeof n; let t: T;
            function g(b = B) { return b; }
            const o = { B };
            export { B as BB };
            class C extends B {}
        """.trimIndent() + "\n"
        val actual = rows(mapOf("m.ts" to m, "a.ts" to a))
        fun r(line: Int, col: Int, name: String) =
            "a.ts:$line:$col TS1361 '$name' cannot be used as a value because it was imported using 'import type'."
        assert(actual == listOf(
            r(11, 17, "B"), r(3, 15, "B"), r(4, 11, "n"), r(5, 1, "f"), r(6, 1, "NS"), r(8, 16, "B"), r(9, 13, "B"),
        ).sorted())
    }

    @Test
    fun `the related note spans the import clause or the namespace import as tsgo does`() {
        val m6 = "export class B { p = 1; }\nexport default class D { d = 1; }\n"
        val a = "// i\nimport type D from \"./m\";\nimport type * as M from \"./m\";\nnew D();\nnew M.B();\n"
        val actual = fullRows(mapOf("m.ts" to m6, "a.ts" to a))
        assert(actual == listOf(
            "a.ts:4:5 TS1361 'D' cannot be used as a value because it was imported using 'import type'. len=1" +
                " | a.ts:2:8 TS1376 'D' was imported here. len=6",
            "a.ts:5:5 TS1361 'M' cannot be used as a value because it was imported using 'import type'. len=1" +
                " | a.ts:3:18 TS1376 'M' was imported here. len=1",
        ))
    }

    @Test
    fun `a type-only rename and a type-only namespace export are TS1362 at the clause`() {
        val actual = fullRows(mapOf(
            "m.ts" to "export class B { p = 1; }\n",
            "r.ts" to "export type { B as X } from \"./m\";\nexport type * as NSX from \"./m\";\n",
            "a.ts" to "// i\nimport { X, NSX } from \"./r\";\nnew X();\nnew NSX.B();\n",
        ))
        assert(actual == listOf(
            "a.ts:3:5 TS1362 'X' cannot be used as a value because it was exported using 'export type'. len=1" +
                " | r.ts:1:15 TS1377 'X' was exported here. len=6",
            "a.ts:4:5 TS1362 'NSX' cannot be used as a value because it was exported using 'export type'. len=3" +
                " | r.ts:2:13 TS1377 'NSX' was exported here. len=8",
        ))
    }

    @Test
    fun `a local export type clause over a plain import is TS1362`() {
        val actual = fullRows(mapOf(
            "m.ts" to m,
            "r.ts" to "import { B } from \"./m\";\nexport type { B };\n",
            "a.ts" to "// i\nimport { B } from \"./r\";\nconst x = new B();\n",
        ))
        assert(actual == listOf(
            "a.ts:3:15 TS1362 'B' cannot be used as a value because it was exported using 'export type'. len=1" +
                " | r.ts:2:15 TS1377 'B' was exported here. len=1",
        ))
    }

    @Test
    fun `an import type re-exported through two plain barrels is TS1361 at the far import`() {
        val actual = fullRows(mapOf(
            "m.ts" to m,
            "r.ts" to "import type { B } from \"./m\";\nexport { B };\n",
            "s.ts" to "export { B } from \"./r\";\n",
            "a.ts" to "// i\nimport { B } from \"./s\";\nconst x = new B();\n",
        ))
        assert(actual == listOf(
            "a.ts:3:15 TS1361 'B' cannot be used as a value because it was imported using 'import type'. len=1" +
                " | r.ts:1:15 TS1376 'B' was imported here. len=1",
        ))
    }

    @Test
    fun `negative control - type positions, typeof types, export clauses and a shadowing parameter are silent`() {
        val a = """
            // i
            import { X } from "./r";
            let k: X;
            let kk: typeof X;
            export { X as Y };
            function f(X: number) { return X; }
            interface I extends X {}
        """.trimIndent() + "\n"
        val actual = rows(mapOf(
            "m.ts" to "export class B { p = 1; }\n",
            "r.ts" to "export type { B as X } from \"./m\";\n",
            "a.ts" to a,
        ))
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - a type alias used as a value is not TS1362`() {
        // tsgo answers TS2693 there (the name has no value meaning), which this checker
        // does not report; the pin is that no TS1361 / TS1362 is invented for it.
        val actual = build(mapOf(
            "m.ts" to m,
            "r.ts" to "export type { T } from \"./m\";\n",
            "a.ts" to "// i\nimport { T } from \"./r\";\nconst tt = T;\n",
        ))
        assert(actual.none { it.code == 1361 || it.code == 1362 })
    }

    // ------------------------------------------------------------ TS2614 / TS2724

    private val withDefault = "export class B { p = 1; }\nexport const value1 = 1;\nconst hidden = 2;\nexport default class D { d = 1; }\n"
    private val noDefault = "export class B { p = 1; }\nexport const value1 = 1;\nconst hidden = 2;\nexport { hidden as hh };\n"

    @Test
    fun `an absent re-exported member of a default-exporting module is TS2614`() {
        val actual = rows(mapOf("m.ts" to withDefault, "r.ts" to "\nexport { nope } from \"./m\";\nexport { hidden as x } from \"./m\";\n"))
        assert(actual == listOf(
            "r.ts:2:10 TS2614 Module '\"./m\"' has no exported member 'nope'. Did you mean to use 'import nope from \"./m\"' instead?",
            "r.ts:3:10 TS2614 Module '\"./m\"' has no exported member 'hidden'. Did you mean to use 'import hidden from \"./m\"' instead?",
        ))
    }

    @Test
    fun `a spelling suggestion wins over the default-import hint for a re-export and an import`() {
        val actual = rows(mapOf("m.ts" to withDefault, "r.ts" to "\nexport { valu1 } from \"./m\";\nimport { valu1 as v } from \"./m\";\n"))
        val row = "TS2724 '\"./m\"' has no exported member named 'valu1'. Did you mean 'value1'?"
        assert(actual == listOf("r.ts:2:10 $row", "r.ts:3:10 $row"))
    }

    @Test
    fun `without a default export a re-export reports TS2460 TS2459 and TS2305 as an import does`() {
        val actual = rows(mapOf(
            "m.ts" to noDefault,
            "r.ts" to "\nexport { hidden } from \"./m\";\nexport { nope } from \"./m\";\n",
            "s.ts" to "export class B { p = 1; }\nconst secret = 1;\n",
            "t.ts" to "\nexport { secret } from \"./s\";\n",
        ))
        assert(actual == listOf(
            "r.ts:2:10 TS2460 Module '\"./m\"' declares 'hidden' locally, but it is exported as 'hh'.",
            "r.ts:3:10 TS2305 Module '\"./m\"' has no exported member 'nope'.",
            "t.ts:2:10 TS2459 Module '\"./s\"' declares 'secret' locally, but it is not exported.",
        ))
    }

    @Test
    fun `a spelling suggestion wins over a locally declared name for an import and a re-export`() {
        val actual = rows(mapOf(
            "m.ts" to "const hidden = 1;\nexport const hiddenX = 1;\nexport { hidden as hh };\nconst secret = 2;\nexport const secrets = 3;\n",
            "r.ts" to "\nimport { hidden, secret } from \"./m\";\nexport { hidden as h2, secret as s2 } from \"./m\";\n",
        ))
        fun r(line: Int, col: Int, name: String, s: String) =
            "r.ts:$line:$col TS2724 '\"./m\"' has no exported member named '$name'. Did you mean '$s'?"
        assert(actual == listOf(
            r(2, 10, "hidden", "hiddenX"), r(2, 18, "secret", "secrets"), r(3, 10, "hidden", "hiddenX"), r(3, 24, "secret", "secrets"),
        ))
    }

    // ------------------------------------------------------------ export * as M

    @Test
    fun `an import of an export star as barrel reads the module in value positions`() {
        val a = """
            // importer
            import { M } from "./r";
            const x: string = new M.B().p;
            const y: string = M.n;
            declare const tb: M.B; const z: string = tb.p;
        """.trimIndent() + "\n"
        val actual = rows(mapOf(
            "m.ts" to "export class B { p = 1; }\nexport const n = 1;\n",
            "r.ts" to "export * as M from \"./m\";\n",
            "a.ts" to a,
        ))
        fun r(line: Int, col: Int) = "a.ts:$line:$col TS2322 Type 'number' is not assignable to type 'string'."
        assert(actual == listOf(r(3, 7), r(4, 7), r(5, 30)))
    }

    @Test
    fun `a renamed re-export of an export star as barrel reads the module`() {
        val actual = rows(mapOf(
            "m.ts" to "export const n = 1;\n",
            "r.ts" to "export * as M from \"./m\";\n",
            "s.ts" to "export { M as MM } from \"./r\";\n",
            "a.ts" to "// i\nimport { MM } from \"./s\";\nconst q: string = MM.n;\n",
        ))
        assert(actual == listOf("a.ts:3:7 TS2322 Type 'number' is not assignable to type 'string'."))
    }
}
