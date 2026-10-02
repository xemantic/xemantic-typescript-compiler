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
 * (CHK.208) A type reference's arity is checked against the declaration it NAMES — through the
 * referencing file's own scope (`Name` via its locals and import aliases, `ns.Name` via the
 * namespace import's exports, a barrel's `export *` followed) — and never against a same-named
 * generic of ANOTHER module that the whole-program name scan finds first in file order. zod's
 * v4 `interface ZodArray<T = …>` was arity-checked against v3's `class ZodArray<T, Card = …>`.
 *
 * In every fixture `a.ts` sorts FIRST and declares the decoys, so the pre-fix scan answers
 * with them. Expected rows are tsgo 7.0.2's (matrix `build/bench/p18269-agent/f9`, cells named
 * per test).
 */
class OwnScopeTypeArgArityTest {

    private val decoys = """
        export class ZA<T, C = 1> { t!: T; c!: C }
        export class ZB<T = 1> { t!: T }
        export class Req<T, U> { t!: T; u!: U }
    """.trimIndent()

    private fun rows(files: Map<String, String>): List<String> {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "strict": true, "target": "es2022", "module": "esnext", "moduleResolution": "bundler", "noEmit": true, "types": [] }, "include": ["*.ts"] }""",
                "/proj/a.ts" to decoys,
            ) + files.mapKeys { "/proj/${it.key}" },
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true).diagnostics.map {
            "${it.fileName?.substringAfterLast('/')} ${it.line}:${it.character} TS${it.code} ${it.message}"
        }
    }

    /** Cell `c1` — the census repro `leak2`. */
    @Test
    fun `a local all-defaults generic is not arity-checked against another module's class`() {
        val actual = rows(mapOf("t.ts" to """
            interface Box<X> { x: X }
            export interface ZA<T = string> { v: T }
            export const y: Box<ZA> = null!;
        """.trimIndent()))
        assert(actual.isEmpty())
    }

    /** Cell `c2` — `ns.ZA` / `ns.Req` resolve through the namespace import. */
    @Test
    fun `a namespace-qualified reference uses the namespace's own declaration`() {
        val actual = rows(mapOf(
            "m.ts" to "export interface ZA<T = string> { v: T }\nexport interface Req<T = 1> { t: T }",
            "t.ts" to "import * as ns from \"./m\";\nexport const y: ns.ZA = null!;\nexport const z: ns.Req = null!;",
        ))
        assert(actual.isEmpty())
    }

    /** Cell `c3` — a named import, plain and renamed type-only. */
    @Test
    fun `an imported reference uses the import's target`() {
        val actual = rows(mapOf(
            "m.ts" to "export interface ZA<T = string> { v: T }",
            "t.ts" to "import { ZA } from \"./m\";\nimport type { ZA as Q } from \"./m\";\n" +
                "export const y: ZA = null!;\nexport const q: Q = null!;",
        ))
        assert(actual.isEmpty())
    }

    /** Cell `c9` — the namespace and the named import both go through an `export *` barrel. */
    @Test
    fun `a reference through an export-star barrel uses the barrel's target`() {
        val actual = rows(mapOf(
            "m.ts" to "export interface ZA<T = string> { v: T }",
            "barrel.ts" to "export * from \"./m\";",
            "t.ts" to "import * as ns from \"./barrel\";\nimport { ZA } from \"./barrel\";\n" +
                "export const y: ns.ZA = null!;\nexport const z: ZA = null!;",
        ))
        assert(actual.isEmpty())
    }

    /**
     * Cells `c10` / `c11` — zod writes its specifiers with a `.js` extension, which the alias
     * resolver does not follow; the import declaration's own specifier resolution does.
     */
    @Test
    fun `a dot-js specifier is followed for a namespace and a named import`() {
        val actual = rows(mapOf(
            "m.ts" to "export interface ZA<T = string> { v: T; p(): number }",
            "t.ts" to "import * as ns from \"./m.js\";\nimport { ZA } from \"./m.js\";\n" +
                "import type { ZA as Q } from \"./m.js\";\n" +
                "export const f = (x: unknown) => (x as ns.ZA).p();\nexport const y: ZA = null!;\nexport const q: Q = null!;",
        ))
        assert(actual.isEmpty())
    }

    /** Cell `c12` — the same `.js` route REPORTS what the target really requires; the `ZB` row was missing before. */
    @Test
    fun `a dot-js specifier reports the target's own required arguments`() {
        val actual = rows(mapOf(
            "m.ts" to "export interface Req<T, U> { t: T; u: U }\nexport interface ZB<T> { t: T }",
            "t.ts" to "import * as ns from \"./m.js\";\nexport const y: ns.Req = null!;\nexport const z: ns.Req<1> = null!;",
            "u.ts" to "import { ZB } from \"./m.js\";\nexport const y: ZB = null!;",
        ))
        assert(actual == listOf(
            "t.ts 2:17 TS2314 Generic type 'Req<T, U>' requires 2 type argument(s).",
            "t.ts 3:17 TS2314 Generic type 'Req<T, U>' requires 2 type argument(s).",
            "u.ts 2:17 TS2314 Generic type 'ZB<T>' requires 1 type argument(s).",
        ))
    }

    /** Cell `c4` — too MANY arguments for the local declaration: its own range is reported. */
    @Test
    fun `the local declaration's own range is reported`() {
        val actual = rows(mapOf("t.ts" to "export interface ZA<T = string> { v: T }\nexport const y: ZA<1, 2> = null!;"))
        assert(actual == listOf("t.ts 2:17 TS2707 Generic type 'ZA<T>' requires between 0 and 1 type arguments."))
    }

    /** Cell `c5` — the inverse leak: a local REQUIRED parameter was hidden by a decoy with a default. */
    @Test
    fun `a local required parameter is not hidden by another module's default`() {
        val actual = rows(mapOf("t.ts" to "export interface ZB<T> { t: T }\nexport const y: ZB = null!;"))
        assert(actual == listOf("t.ts 2:17 TS2314 Generic type 'ZB<T>' requires 1 type argument(s)."))
    }

    /** Cell `c8` — a renamed import displays and counts its TARGET. */
    @Test
    fun `a renamed import is checked and displayed as its target`() {
        val actual = rows(mapOf(
            "m.ts" to "export type ZA<T = string> = { v: T };\nexport class ZB<T = 1> { t!: T }",
            "t.ts" to "import { ZA, ZB as Renamed } from \"./m\";\nexport const y: ZA = null!;\n" +
                "export const r: Renamed = null!;\nexport const s: Renamed<1, 2> = null!;",
        ))
        assert(actual == listOf("t.ts 4:17 TS2707 Generic type 'ZB<T>' requires between 0 and 1 type arguments."))
    }

    /** Cell `c6` — control: a namespace member that really requires arguments still reports. */
    @Test
    fun `control - a namespace member missing required arguments still reports TS2314`() {
        val actual = rows(mapOf(
            "m.ts" to "export interface Req<T, U> { t: T; u: U }",
            "t.ts" to "import * as ns from \"./m\";\nexport const y: ns.Req = null!;\nexport const z: ns.Req<1> = null!;",
        ))
        assert(actual == listOf(
            "t.ts 2:17 TS2314 Generic type 'Req<T, U>' requires 2 type argument(s).",
            "t.ts 3:17 TS2314 Generic type 'Req<T, U>' requires 2 type argument(s).",
        ))
    }

    /** Cell `c7` — control: a local NON-generic declaration needs no arguments (round 442's guard). */
    @Test
    fun `control - a local non-generic declaration is not arity-checked`() {
        val actual = rows(mapOf("t.ts" to "export interface ZA { v: string }\nexport const y: ZA = null!;"))
        assert(actual.isEmpty())
    }
}
