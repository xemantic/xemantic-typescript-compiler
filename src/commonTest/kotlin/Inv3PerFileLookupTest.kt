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
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * INV.3(b) (round 501): `lookupPerFile` — THE per-file resolution primitive the
 * INV.3(c) family flips will substitute for conflated `globals[name]` consults —
 * must see exactly what a file legitimately sees:
 *
 *  - its own top-level locals, with an ImportSpecifier alias resolved through
 *    [Checker.lookupPerFile]'s general import resolution — ESM `.js` specifiers
 *    and `export *` barrels included (the round-500 measurement: conflated
 *    traffic is almost entirely barrel-imported `types.ts` names), yielding the
 *    SAME symbol instance the conflated `globals` lookup returned;
 *  - script-file globals and lib names;
 *  - and NOTHING from a foreign module file (null — the leak the merged
 *    `globals` used to provide), while an unresolvable import degrades to the
 *    alias symbol itself, never null.
 *
 * Built by direct `Checker(options, binderResults)` construction so symbol
 * IDENTITY can be asserted against the declaring file's binder locals.
 */
class Inv3PerFileLookupTest {

    private fun buildChecker(vararg files: Pair<String, String>): Pair<Checker, Map<String, BinderResult>> {
        val options = CompilerOptions()
        val results = files.map { (name, src) -> Binder(options).bind(Parser(src.trimIndent(), name).parse()) }
        val byName = results.associateBy { it.sourceFile.fileName }
        return Checker(options, results, isMultiFileSource = true) to byName
    }

    @Test
    fun `an ESM js import resolves to the declaring file's own symbol`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export interface Foo { x: number }
            """,
            "/proj/c.ts" to """
                import { Foo } from "./a.js";
                export const use: Foo = { x: 1 };
            """,
        )
        val target = results.getValue("/proj/a.ts").locals["Foo"]
        assert(target != null)
        assert(checker.lookupPerFile("/proj/c.ts", "Foo") === target)
    }

    @Test
    fun `a barrel export-star chain resolves through the js barrel`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export interface Foo { x: number }
            """,
            "/proj/barrel.ts" to """
                export * from "./a.js";
            """,
            "/proj/c.ts" to """
                import { Foo } from "./barrel.js";
                export const use: Foo = { x: 1 };
            """,
        )
        val target = results.getValue("/proj/a.ts").locals["Foo"]
        assert(target != null)
        assert(checker.lookupPerFile("/proj/c.ts", "Foo") === target)
    }

    @Test
    fun `a renamed re-export resolves to the original declaration`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export interface Foo { x: number }
            """,
            "/proj/barrel.ts" to """
                export { Foo as Bar } from "./a.js";
            """,
            "/proj/c.ts" to """
                import { Bar } from "./barrel.js";
                export const use: Bar = { x: 1 };
            """,
        )
        val target = results.getValue("/proj/a.ts").locals["Foo"]
        assert(target != null)
        assert(checker.lookupPerFile("/proj/c.ts", "Bar") === target)
    }

    @Test
    fun `own locals and script-file globals and lib names resolve`() {
        val (checker, results) = buildChecker(
            "/proj/script.ts" to """
                const scriptGlobal = 7;
            """,
            "/proj/a.ts" to """
                export interface Foo { x: number }
            """,
            "/proj/c.ts" to """
                export const own = 1;
            """,
        )
        assertSame(results.getValue("/proj/a.ts").locals["Foo"], checker.lookupPerFile("/proj/a.ts", "Foo"))
        assertSame(results.getValue("/proj/c.ts").locals["own"], checker.lookupPerFile("/proj/c.ts", "own"))
        assertSame(
            results.getValue("/proj/script.ts").locals["scriptGlobal"],
            checker.lookupPerFile("/proj/c.ts", "scriptGlobal"),
        )
        assert(checker.lookupPerFile("/proj/c.ts", "Array") != null)
    }

    @Test
    fun `negative control - a foreign module-file local has no per-file meaning`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export const leaked = 1;
            """,
            "/proj/c.ts" to """
                export const own = 2;
            """,
        )
        assertNotNull(results.getValue("/proj/a.ts").locals["leaked"], "the leak candidate is a real module local")
        assert(checker.lookupPerFile("/proj/c.ts", "leaked") == null)
        assert(checker.lookupPerFile("/proj/c.ts", "nothingAnywhere") == null)
        assert(checker.lookupPerFile("no-such-file.ts", "own") == null)
    }

    @Test
    fun `an unresolvable import degrades to the alias symbol itself`() {
        val (checker, results) = buildChecker(
            "/proj/c.ts" to """
                import { Ghost } from "./missing.js";
                export const use = Ghost;
            """,
        )
        val alias = results.getValue("/proj/c.ts").locals["Ghost"]
        assert(alias != null)
        val aliasFlagged = alias.flags.hasAny(SymbolFlags.Alias)
        assert(aliasFlagged)
        assert(checker.lookupPerFile("/proj/c.ts", "Ghost") === alias)
    }
}
