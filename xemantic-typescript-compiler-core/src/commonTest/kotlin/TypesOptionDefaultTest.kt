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
 * (CHK.209) M3 — the `types` default of TypeScript 7 (tsgo 7.0.2,
 * `module.GetAutomaticTypeDirectiveNames`): an UNSET `types` includes NO type package
 * (TypeScript 6 scanned every type root), an explicit list is taken as written, and only
 * a `"*"` entry enumerates the type roots — directories only, never a dot-prefixed one,
 * never a `"typings": null` stub, never descending into a scope directory — spliced in at
 * its own position. `typeRoots` alone includes nothing.
 *
 * Every expected row is tsgo 7.0.2's, measured on the matrix in
 * `build/bench/p18269-agent/m3` (cells named in each KDoc). A diagnostic that names the
 * global is the observable for inclusion, and the program FILE LIST the other half.
 */
class TypesOptionDefaultTest {

    private fun project(tsconfig: String, index: String, extra: Map<String, String> = emptyMap()) = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to tsconfig,
            "/proj/t.ts" to index,
            "/proj/node_modules/@types/foo/index.d.ts" to "declare var fooGlobal: number;",
            "/proj/node_modules/@types/bar/index.d.ts" to
                "export declare const bar: number; declare global { var barGlobal: number }",
            "/proj/node_modules/@types/.hidden/index.d.ts" to "declare var hiddenGlobal: number;",
            "/proj/typings/baz/index.d.ts" to "declare var bazGlobal: number;",
        ) + extra,
    )

    private fun config(options: String) =
        """{ "compilerOptions": { "strict": true, "target": "es2022", "module": "esnext", "moduleResolution": "bundler", "noEmit": true$options }, "include": ["*.ts"] }"""

    private val globals = "export const a = fooGlobal; export const b = bazGlobal;"

    private fun build(options: String, index: String = globals, extra: Map<String, String> = emptyMap()) =
        ProjectCompiler(project(config(options), index, extra)).build("/proj", noEmit = true)

    private fun rows(result: ProjectCompiler.Result): List<String> = result.diagnostics.map {
        "${it.fileName?.substringAfterLast('/') ?: ""} ${it.line}:${it.character} TS${it.code} ${it.message}"
    }

    private fun typeFiles(result: ProjectCompiler.Result): List<String> =
        result.programFiles.filter { "/node_modules/" in it || "/typings/" in it }.sorted()

    /** Cell `unset` — tsgo: TS2304 x2, program = `t.ts`. */
    @Test
    fun `an unset types includes no type package`() {
        val result = build("")
        val actual = rows(result)
        assert(actual == listOf(
            "t.ts 1:18 TS2304 Cannot find name 'fooGlobal'.",
            "t.ts 1:46 TS2304 Cannot find name 'bazGlobal'.",
        ))
        assert(typeFiles(result).isEmpty())
    }

    /** Cell `roots` — `typeRoots` without `types` includes nothing either. */
    @Test
    fun `typeRoots alone includes no type package`() {
        val result = build(""","typeRoots": ["./typings"]""")
        val actual = rows(result)
        assert(actual == listOf(
            "t.ts 1:18 TS2304 Cannot find name 'fooGlobal'.",
            "t.ts 1:46 TS2304 Cannot find name 'bazGlobal'.",
        ))
        assert(typeFiles(result).isEmpty())
    }

    /** Cell `star` — tsgo includes `bar` and `foo` and suggests `barGlobal`. */
    @Test
    fun `a star entry enumerates the default type roots`() {
        val result = build(""","types": ["*"]""")
        val actual = rows(result)
        assert(actual == listOf("t.ts 1:46 TS2552 Cannot find name 'bazGlobal'. Did you mean 'barGlobal'?"))
        assert(typeFiles(result) == listOf(
            "/proj/node_modules/@types/bar/index.d.ts",
            "/proj/node_modules/@types/foo/index.d.ts",
        ))
    }

    /** Cell `starfoo` — an explicit name beside the wildcard is de-duplicated, no TS2688. */
    @Test
    fun `a star entry beside an explicit name is de-duplicated`() {
        val result = build(""","types": ["foo", "*"]""")
        val actual = rows(result)
        assert(actual == listOf("t.ts 1:46 TS2552 Cannot find name 'bazGlobal'. Did you mean 'barGlobal'?"))
        assert(typeFiles(result).size == 2)
    }

    /** Cell `rootsstar` — the wildcard looks in `typeRoots`, and nowhere else; no TS2688 for `*`. */
    @Test
    fun `a star entry enumerates typeRoots when they are set`() {
        val result = build(""","typeRoots": ["./typings"], "types": ["*"]""")
        val actual = rows(result)
        assert(actual == listOf("t.ts 1:18 TS2304 Cannot find name 'fooGlobal'."))
        assert(typeFiles(result) == listOf("/proj/typings/baz/index.d.ts"))
    }

    /** Cell `hidden` — a dot-prefixed directory is never a wildcard match. */
    @Test
    fun `a star entry skips a dot-prefixed directory`() {
        val result = build(""","types": ["*"]""", "export const a = hiddenGlobal;")
        val actual = rows(result)
        assert(actual == listOf("t.ts 1:18 TS2304 Cannot find name 'hiddenGlobal'."))
    }

    /** tsgo skips a DefinitelyTyped "not needed" stub (`"typings": null`). */
    @Test
    fun `a star entry skips a typings-null stub`() {
        val result = build(
            ""","types": ["*"]""", "export const a = stubGlobal;",
            mapOf(
                "/proj/node_modules/@types/stub/package.json" to """{ "typings": null }""",
                "/proj/node_modules/@types/stub/index.d.ts" to "declare var stubGlobal: number;",
            ),
        )
        val actual = rows(result)
        assert(actual == listOf("t.ts 1:18 TS2304 Cannot find name 'stubGlobal'."))
        assert("/proj/node_modules/@types/stub/index.d.ts" !in result.programFiles)
    }

    /**
     * tsgo hands a scope directory's bare name to resolution, which fails: TS2688 for
     * `@myscope` (tsgo then stops before semantic rows; we do not short-circuit, a known
     * (LEGACY.1)(g) divergence, so only the TS2688 row is asserted).
     */
    @Test
    fun `a star entry does not descend into a scope directory`() {
        val result = build(
            ""","types": ["*"]""", "export const a = scopedThing;",
            mapOf("/proj/node_modules/@types/@myscope/thing/index.d.ts" to "declare var scopedThing: number;"),
        )
        val ts2688 = result.diagnostics.filter { it.code == 2688 }.map { it.message }
        assert(ts2688 == listOf("Cannot find type definition file for '@myscope'."))
        assert("/proj/node_modules/@types/@myscope/thing/index.d.ts" !in result.programFiles)
    }

    /** Cell `list` — an explicit list is taken as written. */
    @Test
    fun `an explicit types list includes exactly its names`() {
        val result = build(""","types": ["foo"]""")
        val actual = rows(result)
        assert(actual == listOf("t.ts 1:46 TS2304 Cannot find name 'bazGlobal'."))
        assert(typeFiles(result) == listOf("/proj/node_modules/@types/foo/index.d.ts"))
    }

    /** Cell `refdir` — a `/// <reference types>` still pulls its package in under an unset `types`. */
    @Test
    fun `control - a reference-types directive includes its package with types unset`() {
        val result = build("", "/// <reference types=\"foo\" />\nexport const a = fooGlobal;")
        assert(result.diagnostics.isEmpty())
        assert(typeFiles(result) == listOf("/proj/node_modules/@types/foo/index.d.ts"))
    }

    /** Cell `imp` — an import resolves through `@types` and brings ITS globals, but no other package's. */
    @Test
    fun `control - an import of an at-types-only package includes that package alone`() {
        val result = build("", """import { bar } from "bar"; export const a = bar + barGlobal + fooGlobal;""")
        val actual = rows(result)
        assert(actual == listOf("t.ts 1:63 TS2304 Cannot find name 'fooGlobal'."))
        assert(typeFiles(result) == listOf("/proj/node_modules/@types/bar/index.d.ts"))
    }

    /** Cell `missing` — an explicit name that resolves nowhere is TS2688, as before. */
    @Test
    fun `control - an unresolvable explicit name reports TS2688`() {
        val result = build(""","types": ["nope"]""", "export const a = 1;")
        val actual = rows(result)
        assert(actual == listOf(" null:null TS2688 Cannot find type definition file for 'nope'."))
    }
}
