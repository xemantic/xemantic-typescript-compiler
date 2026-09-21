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
 * A NAMED import whose module's surface is an `export = <namespace-merged value>`.
 *
 * **WHY THIS IS A MISSION ITEM AND NOT A CONFORMANCE ROW.** `export =` is how the
 * `@types` ecosystem publishes CommonJS packages, so a named import of one is the
 * ordinary way real TypeScript reaches a dependency's declarations. It resolved to
 * NOTHING here, which means the binding typed `any` — silently, since `any` is legal
 * everywhere — and a wrong type does not stop at a diagnostic: the Kotlin externals
 * generator renders from resolved types, and the KIR backend picks its lowering from
 * them ((KIR.LOWER.3) measures 33x for one wrong receiver type).
 *
 * The mechanism is one missing leg in `computeImportedSymbolGeneral`: it looks in the
 * target file's `locals` and through `export *` barrels, and an `export =` target's
 * members are in neither — they live in the EXPORT TARGET's own `exports` table.
 *
 * Every expectation was measured against `tools/tsgo-7.0.2/lib/tsc` first, over one
 * fixture holding all five import forms, and a `never` target is used so tsc's literal
 * generalization ((CHK.83)) does not rewrite the type being asserted.
 *
 * **WHAT GATES IT, counted rather than assumed ((CHK.124)).** The 8-profile grid and
 * both library arms contain **ZERO** `export =` modules, so a green grid there is a
 * CONTROL. The ACTIVE corpus carries **40** cases that combine `export =` with a named
 * import, which is what makes the corpus screen a real regression gate, and these pins
 * are the gate for the new answer.
 *
 * **THE RESIDUES BELOW ARE (CHK.73), NOT THIS LEG.** `import * as ns` and
 * `import ns = require(...)` still type `any` for ANY module, `export =` or not, because
 * `getTypeOfSymbolWorker` has no `SymbolFlags.Module` arm; CLAUDE.md records that
 * refusal and its measured cost. They are pinned here so the boundary between the two
 * mechanisms is a recorded decision rather than an accident of which form a future
 * fixture happens to use.
 */
class ExportEqualsNamedImportTest {

    private val modules = """
        // @Filename: legacy.ts
        declare function legacy(n: number): string;
        declare namespace legacy { const version: string; }
        export = legacy;

        // @Filename: plain.ts
        export const version: string = "x";

        // @Filename: main.ts
    """.trimIndent() + "\n"

    private fun rows(main: String): List<String> =
        diagnose(modules + main, directives = "// @strict: true\n// @module: commonjs")
            .filter { it.code == 2322 }
            .map { it.message }

    private val stringToNever = "Type 'string' is not assignable to type 'never'."

    @Test
    fun `a named import through an export equals surface resolves its member`() {
        assert(
            rows("import { version } from \"./legacy\";\nconst a: never = version;\nexport {};") ==
                listOf(stringToNever),
        )
    }

    @Test
    fun `a renamed named import through an export equals surface resolves its member`() {
        assert(
            rows("import { version as v } from \"./legacy\";\nconst a: never = v;\nexport {};") ==
                listOf(stringToNever),
        )
    }

    @Test
    fun `control - a named import of an ordinary module still resolves`() {
        // Pre-existing and unchanged: the `locals` leg answers this one, which is what
        // isolates the new leg to the `export =` surface.
        assert(
            rows("import { version } from \"./plain\";\nconst a: never = version;\nexport {};") ==
                listOf(stringToNever),
        )
    }

    @Test
    fun `negative control - a member the export equals target does not have is not invented`() {
        // tsgo answers TS2305 `Module '"./legacy"' has no exported member 'nope'` and
        // types the binding `any`; we emit that same TS2305 today. What this pin holds is
        // that the new leg adds NO TS2322 — a leg that answered some other member, or the
        // export target itself, would show up here as a spurious row.
        assert(rows("import { nope } from \"./legacy\";\nconst a: never = nope;\nexport {};").isEmpty())
    }

    @Test
    fun `residue - a namespace import of an export equals module is still any`() {
        // (CHK.73): no `SymbolFlags.Module` arm in `getTypeOfSymbolWorker`, so `ns` has no
        // type at all. Unchanged by this leg, which resolves a MEMBER and never a module.
        assert(rows("import * as ns from \"./legacy\";\nconst a: never = ns.version;\nexport {};").isEmpty())
    }

    @Test
    fun `residue - an import-equals-require of an export equals module is still any`() {
        assert(rows("import lg = require(\"./legacy\");\nconst a: never = lg.version;\nexport {};").isEmpty())
    }

    @Test
    fun `residue - a namespace import of an ORDINARY module is also still any`() {
        // The row that shows the residue is (CHK.73) and not an `export =` question: the
        // same silence appears for a module with ordinary named exports.
        assert(rows("import * as p from \"./plain\";\nconst a: never = p.version;\nexport {};").isEmpty())
    }
}
