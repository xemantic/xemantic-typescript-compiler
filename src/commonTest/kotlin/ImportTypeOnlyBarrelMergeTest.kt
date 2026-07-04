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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A named import of a TYPE-ONLY export (an interface / type alias, possibly reached through an
 * `export *` barrel) declaration-merges with a local VALUE-only declaration (function / value
 * const) of the same name — the import provides the type, the local provides the value — so NO
 * TS2440 "Import declaration conflicts with local declaration". This is exactly what tsc's own
 * `src/compiler` does: `import { Node, Identifier, ... } from "./_namespaces/ts.js"` (the barrel
 * re-exports `export interface Node` from types.ts) plus local `function Node`/`function
 * Identifier` AST-constructor helpers.
 *
 * Fixed via `importedNameIsTypeOnlyThroughBarrel` (a conservative `export *`-following resolver;
 * `isExportedNameTypeOnly` only inspects DIRECT exports). FN-safe: a VALUE import, or a local
 * with a TYPE side (class/enum/interface/namespace), still fires TS2440.
 */
class ImportTypeOnlyBarrelMergeTest {

    private fun build(files: Map<String, String>): ProjectCompiler.Result {
        val vfs = InMemoryVfs(
            files + ("/proj/tsconfig.json" to
                """{ "compilerOptions": { "outDir": "./dist" }, "include": ["src/**/*.ts"] }""")
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
    }

    private fun ts2440(result: ProjectCompiler.Result): List<String> =
        result.diagnostics.filter { it.code == 2440 }.map { it.message }

    @Test
    fun `type-only import through a barrel merges with a local function - no TS2440`() {
        val result = build(
            mapOf(
                "/proj/src/types.ts" to "export interface Node { kind: number; }",
                "/proj/src/barrel.ts" to """export * from "./types.js";""",
                "/proj/src/utilities.ts" to """
                    import { Node } from "./barrel.js";
                    function Node(this: unknown, kind: number): void {}
                    export const nodeCtor = Node;
                    export function make(): Node { return { kind: 1 }; }
                """.trimIndent(),
            )
        )
        assertTrue(
            ts2440(result).isEmpty(),
            "type-only barrel import + local function must NOT fire TS2440; got TS2440: " + ts2440(result),
        )
    }

    @Test
    fun `type-only import through a barrel merges with a local value const - no TS2440`() {
        // Mirrors checker.ts `const SymbolLinks = class implements SymbolLinks {}` — the const is
        // value-only (a class EXPRESSION creates no type binding), so it merges with the type import.
        val result = build(
            mapOf(
                "/proj/src/types.ts" to "export interface SymbolLinks { flags: number; }",
                "/proj/src/barrel.ts" to """export * from "./types.js";""",
                "/proj/src/checker.ts" to """
                    import { SymbolLinks } from "./barrel.js";
                    const SymbolLinks = class { flags = 0; };
                    export const mk = (): SymbolLinks => new SymbolLinks();
                """.trimIndent(),
            )
        )
        assertTrue(
            ts2440(result).isEmpty(),
            "type-only barrel import + local value const must NOT fire TS2440; got: " + ts2440(result),
        )
    }

    @Test
    fun `multi-hop barrel type-only resolution - no TS2440`() {
        val result = build(
            mapOf(
                "/proj/src/types.ts" to "export interface Sig { n: number; }",
                "/proj/src/mid.ts" to """export * from "./types.js";""",
                "/proj/src/barrel.ts" to """export * from "./mid.js";""",
                "/proj/src/u.ts" to """
                    import { Sig } from "./barrel.js";
                    function Sig(): void {}
                    export const s = Sig;
                    export function f(): Sig { return { n: 1 }; }
                """.trimIndent(),
            )
        )
        assertTrue(
            ts2440(result).isEmpty(),
            "multi-hop barrel type-only import + local function must NOT fire TS2440; got: " + ts2440(result),
        )
    }

    @Test
    fun `a VALUE import through a barrel still conflicts with a local function - TS2440 fires`() {
        // Negative control: the source exports `Widget` as a FUNCTION (a value). Importing it and
        // ALSO declaring a local `function Widget` is a real value-vs-value conflict.
        val result = build(
            mapOf(
                "/proj/src/values.ts" to "export function Widget(): void {}",
                "/proj/src/barrel.ts" to """export * from "./values.js";""",
                "/proj/src/u.ts" to """
                    import { Widget } from "./barrel.js";
                    function Widget(): void {}
                    export const w = Widget;
                """.trimIndent(),
            )
        )
        assertTrue(
            ts2440(result).any { it.contains("'Widget'") },
            "a value import + a local function of the same name must still fire TS2440; got: " + ts2440(result),
        )
    }

    @Test
    fun `a type-only import + a local CLASS still conflicts on the type side - TS2440 fires`() {
        // Negative control: a local `class Node` has a TYPE side that DOES conflict with the
        // imported interface, so the merge does not apply — the suppression is gated to
        // value-only locals (function / value const), which a class is not.
        val result = build(
            mapOf(
                "/proj/src/types.ts" to "export interface Node { kind: number; }",
                "/proj/src/barrel.ts" to """export * from "./types.js";""",
                "/proj/src/u.ts" to """
                    import { Node } from "./barrel.js";
                    class Node { kind = 0; }
                    export const n = new Node();
                """.trimIndent(),
            )
        )
        assertTrue(
            ts2440(result).any { it.contains("'Node'") },
            "a type-only import + a local class (type side conflicts) must still fire TS2440; got: " + ts2440(result),
        )
    }
}
