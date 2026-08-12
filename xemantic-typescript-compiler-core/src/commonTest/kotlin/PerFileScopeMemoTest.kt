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
 * (WARM.23) round 896 — candidate (2a): `perFileScope` is probed ONCE per name
 * lookup instead of twice, behind a one-entry reference-compared memo.
 *
 * INV.3(b)/(d) is the most safety-critical resolution in this file — a wrong
 * answer here silently resolves a name to a FOREIGN module file's local, which
 * is precisely the leak the per-file model exists to close — so the pins are
 * about the two ways this change could produce one:
 *
 *  * the memo answering for the WRONG file (its key is compared by reference, so
 *    an alternation between two files must still answer each file's own scope);
 *  * the memo becoming the ORACLE rather than a front cache — an equal-but-not-
 *    identical path string must still resolve, because a map keyed by identity
 *    would answer null and the caller would read that as "not visible here".
 *
 * Built by direct `Checker(options, binderResults)` construction, with
 * path-shaped file names — a flat corpus-style name silently defeats
 * directory-relative module-specifier resolution (CLAUDE.md).
 */
class PerFileScopeMemoTest {

    private fun buildChecker(vararg files: Pair<String, String>): Pair<Checker, Map<String, BinderResult>> {
        val options = CompilerOptions()
        val results = files.map { (name, src) -> Binder(options).bind(Parser(src.trimIndent(), name).parse()) }
        val byName = results.associateBy { it.sourceFile.fileName }
        return Checker(options, results, isMultiFileSource = true) to byName
    }

    /** A path EQUAL to the literal but not the same instance — the memo-miss path. */
    private fun freshPath(vararg parts: String): String {
        val b = StringBuilder()
        for (p in parts) b.append(p)
        val s = b.toString()
        // Guard the guard: a compiler that interned this would make the pin vacuous.
        assert(!(s === "/proj/c.ts"))
        return s
    }

    @Test
    fun `a freshly built equal path resolves what the interned literal resolves`() {
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
        // Warm the memo with the interned literal, then ask with a distinct
        // instance holding the same characters.
        assert(checker.lookupPerFile("/proj/c.ts", "Foo") === target)
        assert(checker.lookupPerFile(freshPath("/proj/", "c.ts"), "Foo") === target)
    }

    @Test
    fun `alternating between two files answers each file's own scope`() {
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export interface Foo { x: number }
                export const onlyInA = 1;
            """,
            "/proj/b.ts" to """
                export interface Bar { y: number }
                export const onlyInB = 2;
            """,
        )
        val fooInA = results.getValue("/proj/a.ts").locals["Foo"]
        val barInB = results.getValue("/proj/b.ts").locals["Bar"]
        assert(fooInA != null)
        assert(barInB != null)
        // Alternate so every read is a memo MISS on the previous key: a memo that
        // ignored its key would serve a's table for b and vice versa.
        repeat(3) {
            assert(checker.lookupPerFile("/proj/a.ts", "Foo") === fooInA)
            assert(checker.lookupPerFile("/proj/b.ts", "Bar") === barInB)
            // …and neither file may see the other's module-local name.
            assert(checker.lookupPerFile("/proj/a.ts", "Bar") == null)
            assert(checker.lookupPerFile("/proj/b.ts", "Foo") == null)
        }
    }

    @Test
    fun `globalsForFile answers null for a module-only name the file cannot see`() {
        // The single-probe restructuring must keep RETURNING once the file has a
        // scope: falling through to the merged `globals` for a name the file
        // cannot see is exactly the INV.3(d) leak.
        val (checker, results) = buildChecker(
            "/proj/a.ts" to """
                export interface Foo { x: number }
            """,
            "/proj/b.ts" to """
                export interface Bar { y: number }
            """,
        )
        val fooInA = results.getValue("/proj/a.ts").locals["Foo"]
        assert(fooInA != null)
        assert(checker.globalsForFile("/proj/a.ts", "Foo") === fooInA)
        assert(checker.globalsForFile("/proj/b.ts", "Foo") == null)
    }

    @Test
    fun `globalsForFile and lookupPerFile agree for every module-only name`() {
        val (checker, _) = buildChecker(
            "/proj/a.ts" to """
                export interface Foo { x: number }
                export type Alias = Foo;
            """,
            "/proj/c.ts" to """
                import { Foo, Alias } from "./a.js";
                export const use: Alias = { x: 1 };
            """,
        )
        var agreed = 0
        for (file in listOf("/proj/a.ts", "/proj/c.ts")) {
            for (name in listOf("Foo", "Alias", "use", "nowhere")) {
                val viaGlobalsForFile = checker.globalsForFile(file, name)
                val viaLookup = checker.lookupPerFile(file, name)
                // For a module-only name the two are the SAME resolution since
                // round 896 — globalsForFile hands its already-resolved table to
                // the very body lookupPerFile runs.
                if (viaLookup != null) assert(viaGlobalsForFile === viaLookup)
                agreed++
            }
        }
        assert(agreed == 8)
    }
}
