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
 * (CHK.190) stage 1 — an import through a NAMED re-export resolves to the declaration,
 * keyed by the name the IMPORTER sees.
 *
 * Every import→export resolver used to read `targetResult.locals[name]`, which is keyed by
 * the DECLARED name (`export { a as b }` binds `a`; `export { default as D } from` binds
 * `default`), holds names the file does not export, and stops at an `ExportSpecifier`
 * alias. So a renaming clause, a default clause, a local `export { B0 as B }`, a `.js`
 * from-specifier and a chain through an import-then-export barrel all typed the importer's
 * binding `any` — silently. `NameResolver.importedExport` asks
 * `Checker.exportedSymbolsThroughStars` (importer-keyed, own exports shadow stars) and
 * `NameResolver.exportSpecifierTarget` follows a clause one hop.
 *
 * Every expectation is tsgo 7.0.2's row set for the same project (census cells under
 * `build/scratch-p18247-census/`). Importers carry a leading comment line so no
 * ImportSpecifier is byte-identical to a barrel's ((CHK.192) is fixed, but the shift keeps
 * the pins about re-exports only).
 */
class NamedReExportResolutionTest {

    private val esm =
        """{ "compilerOptions": { "strict": true, "target": "es2022", "module": "esnext", "moduleResolution": "bundler", "noEmit": true, "lib": ["es2022"], "types": [] }, "include": ["src"] }"""

    private val m = """
        export class B { p = 1; }
        export const n = 1;
        export type T = { q: number };
        export default class D { d = 1; }
    """.trimIndent() + "\n"

    /** The five probes of the census matrix: value, `new`, type, `typeof`, alias. */
    private val probes = """
        const x: string = new B().p;
        const y: string = n;
        declare const tb: B; const z: string = tb.p;
        declare const tq: typeof n; const w: string = tq;
        declare const tt: T; const u: string = tt.q;
    """.trimIndent() + "\n"

    private fun rows(files: Map<String, String>): List<String> {
        val vfs = InMemoryVfs(files.mapKeys { "/proj/src/${it.key}" } + ("/proj/tsconfig.json" to esm))
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        return result.diagnostics
            .map { "${it.fileName?.substringAfterLast('/')}:${it.line}:${it.character} TS${it.code} ${it.message}" }
            .sorted()
    }

    private fun numToString(line: Int, col: Int) =
        "a.ts:$line:$col TS2322 Type 'number' is not assignable to type 'string'."

    /** tsgo's five rows for [probes] imported as `B, n, T` on line 2 (columns for unrenamed names). */
    private val fiveRows = listOf(
        numToString(3, 7), numToString(4, 7), numToString(5, 28), numToString(6, 35), numToString(7, 28),
    )

    private fun importer(clause: String, from: String = "./r") =
        "// importer\nimport { $clause } from \"$from\";\n$probes"

    @Test
    fun `a same-name from clause resolves`() {
        val actual = rows(mapOf("m.ts" to m, "r.ts" to "export { B, n, T } from \"./m\";\n", "a.ts" to importer("B, n, T")))
        assert(actual == fiveRows)
    }

    @Test
    fun `a namespace import of a same-name from-clause barrel reads the members`() {
        // `R.B` reads the module symbol's table, whose entry is the clause's ALIAS — only
        // `resolveAlias`'s ExportSpecifier arm follows it (the named-import paths above
        // reach the declaration through `importedExport` and never see the alias).
        val a = """
            // importer
            import * as R from "./r";
            const x: string = new R.B().p;
            const y: string = R.n;
            declare const tb: R.B; const z: string = tb.p;
            declare const tq: typeof R.n; const w: string = tq;
            declare const tt: R.T; const u: string = tt.q;
        """.trimIndent() + "\n"
        val actual = rows(mapOf("m.ts" to m, "r.ts" to "export { B, n, T } from \"./m\";\n", "a.ts" to a))
        assert(actual == listOf(numToString(3, 7), numToString(4, 7), numToString(5, 30), numToString(6, 37), numToString(7, 30)))
    }

    @Test
    fun `a namespace import of a js-specifier from-clause barrel reads the members`() {
        // The clause's own `.js` leg: the module-symbol read reaches the alias, and only
        // `exportSpecifierTarget` resolves `./m.js` to `m.ts` from there.
        val a = "// importer\nimport * as R from \"./r\";\nconst x: string = new R.B().p;\nconst y: string = R.n;\n"
        val actual = rows(mapOf("m.ts" to m, "r.ts" to "export { B, n } from \"./m.js\";\n", "a.ts" to a))
        assert(actual == listOf(numToString(3, 7), numToString(4, 7)))
    }

    @Test
    fun `a js-specifier clause resolves without the crawl's module resolutions`() {
        // The same shape through the in-memory harness, which has no crawl: there the
        // clause's own `.js` leg is the only thing that maps `./m.js` to `m.ts`
        // (under `ProjectCompiler` the crawl's answer covers it too).
        val actual = diagnose(
            """
            // @Filename: /proj/m.ts
            export class B { p = 1; }
            export const n = 1;
            // @Filename: /proj/r.ts
            export { B, n } from "./m.js";
            // @Filename: /proj/a.ts
            import * as R from "./r";
            const x: string = new R.B().p;
            const y: string = R.n;
            """,
            directives = "// @strict: true\n// @target: es2022\n// @module: esnext\n// @moduleResolution: bundler",
        ).map { "${it.fileName?.substringAfterLast('/')}:${it.line} TS${it.code}" }.sorted()
        assert(actual == listOf("a.ts:2 TS2322", "a.ts:3 TS2322"))
    }

    @Test
    fun `a default clause re-export resolves for a default import`() {
        val actual = rows(
            mapOf(
                "m.ts" to m, "r.ts" to "export { default } from \"./m\";\n",
                "a.ts" to "// importer\nimport D from \"./r\";\nconst x: string = new D().d;\ndeclare const td: D; const z: string = td.d;\n",
            ),
        )
        assert(actual == listOf(numToString(3, 7), numToString(4, 28)))
    }

    @Test
    fun `a default clause renamed to a name resolves for a named import`() {
        val actual = rows(
            mapOf(
                "m.ts" to m, "r.ts" to "export { default as D } from \"./m\";\n",
                "a.ts" to "// importer\nimport { D } from \"./r\";\nconst x: string = new D().d;\ndeclare const td: D; const z: string = td.d;\n",
            ),
        )
        assert(actual == listOf(numToString(3, 7), numToString(4, 28)))
    }

    @Test
    fun `an import-then-export renaming barrel resolves through the importer-visible names`() {
        val actual = rows(
            mapOf(
                "m.ts" to m,
                "r.ts" to "import { B, n, T } from \"./m\";\nexport { B as B2, n as n2, T as T2 };\n",
                "a.ts" to "// importer\nimport { B2, n2, T2 } from \"./r\";\n" + """
                    const x: string = new B2().p;
                    const y: string = n2;
                    declare const tb: B2; const z: string = tb.p;
                    declare const tq: typeof n2; const w: string = tq;
                    declare const tt: T2; const u: string = tt.q;
                """.trimIndent() + "\n",
            ),
        )
        assert(actual == listOf(numToString(3, 7), numToString(4, 7), numToString(5, 29), numToString(6, 36), numToString(7, 29)))
    }

    @Test
    fun `a local renaming clause resolves to the declaration it renames`() {
        val r = "class B0 { p = 1; }\nconst n0 = 1;\ntype T0 = { q: number };\nexport { B0 as B, n0 as n, T0 as T };\n"
        val actual = rows(mapOf("r.ts" to r, "a.ts" to importer("B, n, T")))
        assert(actual == fiveRows)
    }

    @Test
    fun `js extension specifiers resolve on both legs of the barrel`() {
        val actual = rows(
            mapOf("m.ts" to m, "r.ts" to "export { B, n, T } from \"./m.js\";\n", "a.ts" to importer("B, n, T", "./r.js")),
        )
        assert(actual == fiveRows)
    }

    @Test
    fun `a three-barrel chain mixing a from clause a local clause and a star resolves`() {
        val actual = rows(
            mapOf(
                "m.ts" to m,
                "r2.ts" to "export * from \"./m\";\n",
                "r1.ts" to "import { B, n, T } from \"./r2\";\nexport { B, n, T };\n",
                "r.ts" to "export { B, n, T } from \"./r1\";\n",
                "a.ts" to importer("B, n, T"),
            ),
        )
        assert(actual == fiveRows)
    }

    @Test
    fun `a named re-export shadows a star export of the same name`() {
        val actual = rows(
            mapOf(
                "m.ts" to m, "m2.ts" to "export const n = \"s\";\n",
                "r.ts" to "export * from \"./m\";\nexport { n } from \"./m2\";\n",
                "a.ts" to "// importer\nimport { n, B } from \"./r\";\nconst y: boolean = n;\nconst x: string = new B().p;\n",
            ),
        )
        assert(
            actual == listOf(
                "a.ts:3:7 TS2322 Type 'string' is not assignable to type 'boolean'.",
                numToString(4, 7),
            ),
        )
    }

    @Test
    fun `an imported guard assertion and namespace through a barrel narrow and resolve`() {
        val m = """
            export function isStr(x: unknown): x is string { return typeof x === "string"; }
            export function assertNum(x: unknown): asserts x is number {}
            export namespace NS { export const v = 1; }
        """.trimIndent() + "\n"
        val a = """
            // importer
            import { isStr, assertNum, NS } from "./r";
            declare const u: unknown;
            if (isStr(u)) { const s: number = u; }
            declare const w: unknown;
            assertNum(w); const t: string = w;
            const v: string = NS.v;
        """.trimIndent() + "\n"
        val actual = rows(mapOf("m.ts" to m, "r.ts" to "export { isStr, assertNum, NS } from \"./m\";\n", "a.ts" to a))
        assert(
            actual == listOf(
                "a.ts:4:23 TS2322 Type 'string' is not assignable to type 'number'.",
                numToString(6, 21),
                numToString(7, 7),
            ),
        )
    }

    @Test
    fun `negative control - the declared name of a from-clause rename is not exported`() {
        val actual = rows(
            mapOf(
                "m.ts" to m, "r.ts" to "export { n as k } from \"./m\";\n",
                "a.ts" to "// importer\nimport { n } from \"./r\";\nconst y: string = n;\n",
            ),
        )
        assert(actual == listOf("a.ts:2:10 TS2305 Module '\"./r\"' has no exported member 'n'."))
    }

    @Test
    fun `negative control - a default import of a module that only renames its default away`() {
        val actual = rows(
            mapOf(
                "m.ts" to m, "r.ts" to "export { default as D } from \"./m\";\n",
                "a.ts" to "// importer\nimport X from \"./r\";\nconst y: string = new X().d;\n",
            ),
        )
        assert(actual.size == 1)
        assert(actual.single().startsWith("a.ts:2:8 TS1192 "))
    }

    @Test
    fun `negative control - the declared name of a local rename types nothing`() {
        val actual = rows(
            mapOf(
                "r.ts" to "const n0 = 1;\nexport { n0 as n };\n",
                "a.ts" to "// importer\nimport { n0 } from \"./r\";\nconst y: string = n0;\n",
            ),
        )
        assert(actual == listOf("a.ts:2:10 TS2460 Module '\"./r\"' declares 'n0' locally, but it is exported as 'n'."))
    }

    private val augTarget = "export interface SourceFile { kind: number; }\nexport interface Exported { e: number; }\n"

    @Test
    fun `names a module augmentation merges into its target resolve for an importer`() {
        // An AUGMENTATION writes its declarations into the target's `locals`, which the
        // importer-keyed export table and the exported-name set never see — so the
        // "provably not exported" answer must not fire for them.
        val actual = rows(
            mapOf(
                "types.ts" to augTarget,
                "aug.ts" to "import \"./types\";\ndeclare module \"./types\" {\n  export interface Brand { b: string; }\n  interface Implicit { i: string; }\n}\nexport const marker = 1;\n",
                "use.ts" to "// importer\nimport { Brand, Implicit } from \"./types\";\ndeclare const b: Brand;\ndeclare const i: Implicit;\nconst p1: string = b;\nconst p2: string = i;\n",
            ),
        )
        assert(
            actual == listOf(
                "use.ts:5:7 TS2322 Type 'Brand' is not assignable to type 'string'.",
                "use.ts:6:7 TS2322 Type 'Implicit' is not assignable to type 'string'.",
            ),
        )
    }

    @Test
    fun `an augmentation with module syntax contributes only its exported declarations`() {
        val actual = rows(
            mapOf(
                "types.ts" to augTarget,
                "aug.ts" to "import \"./types\";\ndeclare module \"./types\" {\n  interface NotExported { q: number; }\n  export interface Exported2 { r: number; }\n  export {};\n}\nexport const marker = 1;\n",
                "use.ts" to "// importer\nimport { NotExported, Exported2 } from \"./types\";\ndeclare const b: Exported2;\nconst p: string = b;\ndeclare const n: NotExported;\nconst q: string = n;\n",
            ),
        ).filter { it.startsWith("use.ts") }
        // tsgo also reports TS2666 at `export {};` in aug.ts; the importer's rows are the pin.
        assert(
            actual == listOf(
                "use.ts:2:10 TS2724 '\"./types\"' has no exported member named 'NotExported'. Did you mean 'Exported'?",
                "use.ts:4:7 TS2322 Type 'Exported2' is not assignable to type 'string'.",
            ),
        )
    }

    @Test
    fun `a named re-export cycle terminates`() {
        // tsgo reports TS2303 at both clauses; that row is a separate gap — the pin is that
        // the chain terminates without the recursion guard's TS2589.
        val actual = rows(
            mapOf(
                "r.ts" to "export { x } from \"./s\";\n", "s.ts" to "export { x } from \"./r\";\n",
                "a.ts" to "// importer\nimport { x } from \"./r\";\nconst y: string = x;\n",
            ),
        )
        assert(actual.none { it.contains("TS2589") })
        assert(actual.none { it.startsWith("a.ts") })
    }

    @Test
    fun `negative control - a namespace-local export clause is not followed`() {
        // MODULE-level clauses only: a namespace's clause exports are a separate gap
        // (corpus `namespacesWithTypeAliasOnlyExportsMerge`, whose two files are copied
        // here — the rows need the second file declaring the same namespaces). Following
        // those clauses exposed four ours-only TS2694 rows on the chained `NS1.NS2.B`
        // reads (lines 12-15); tsgo reports none, and the two direct reads on lines
        // 10-11 are the pre-existing gap of the same family.
        val circular = "declare namespace NS1 {\n    export { NS2 };\n}\ndeclare namespace NS2 {\n    export { NS1 };\n}\nexport {};\n"
        val uses = """
            type A = string;
            type B = number;
            declare namespace NS1 {
                export { NS2, A };
            }
            declare namespace NS2 {
                export { NS1, B };
            }
            export {};
            declare const try1: NS1.A;
            declare const try2: NS2.B;
            declare const try3: NS1.NS2.B;
            declare const try4: NS2.NS1.A;
            declare const try5: NS1.NS2.NS1.A;
            declare const try6: NS2.NS1.NS2.B;
        """.trimIndent() + "\n"
        val actual = rows(mapOf("circular.ts" to circular, "circularWithUses.ts" to uses))
        val chained = actual.filter { row -> (12..15).any { row.startsWith("circularWithUses.ts:$it:") } }
        assert(chained.isEmpty())
    }
}
