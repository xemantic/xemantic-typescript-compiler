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
 * (INV.0) step 4a — the [NameResolver] seam's own pins (ledger row 4).
 *
 * The extraction itself is a VERBATIM relocation, and its invariant — "nothing
 * changed" — is pinned far better by the corpus and by the eleven named gate
 * classes the queue item lists (`Inv3MergeRetireTest`, `Inv3PerFileLookupTest`,
 * `ProjectPackageTypeResolutionTest`, `ModuleAugmentation*Test`, …) than any
 * hand-written case could be. What is NEW, and what only a test at this level
 * can state, is that the MODULE-SPECIFIER LADDER is a function of the program's
 * file set and the options ALONE: it is exercised here through a [NameResolver]
 * built with an EMPTY `globals`, empty `moduleResolutions` and an empty symbol-
 * target store, so a future edit that reaches for checker scope state from the
 * specifier ladder fails here rather than silently deepening the ambient row.
 *
 * `createTypeMapper` (ledger row 3) got the same treatment for the same reason.
 *
 * The sharpest pin is [an unresolvable specifier answers null on the SECOND ask
 * too]: `resolveModuleSpecifier` memoizes a MISS as the `UNRESOLVED_MODULE_SPEC`
 * sentinel and maps it back to null on read (round 483's single-lookup form), so
 * an edit that "simplifies" the sentinel away returns the sentinel STRING as a
 * resolved file name — from the second call onward only, which is why a
 * single-ask pin cannot see it.
 */
class NameResolverTest {

    /**
     * A resolver over a real program whose scope inputs are deliberately EMPTY —
     * the specifier ladder must not read any of them.
     */
    private fun specifierResolver(vararg files: String): NameResolver {
        val options = CompilerOptions()
        val results = files.map { name ->
            Binder(options).bind(Parser("export const x = 1;\n", name).parse())
        }
        val fileResults = results.associateBy { it.sourceFile.fileName }
        val checker = Checker(options, results, isMultiFileSource = true)
        // NAMED arguments deliberately: the parameter list carries several
        // same-typed containers, so a positional permutation would type-check
        // (CLAUDE.md — the `cpcScanFiles` gotcha).
        return NameResolver(
            checker = checker,
            options = options,
            binderResults = results,
            fileResults = fileResults,
            globals = mutableMapOf(),
            moduleResolutions = emptyMap(),
            moduleImportAliasNames = emptySet(),
            symbolTargets = IntKeyMap(),
        )
    }

    @Test
    fun `a relative specifier resolves against the importing file's own directory`() {
        val r = specifierResolver("/proj/dir/a.ts", "/proj/dir/b.ts")
        assert(r.resolveModuleSpecifierRelative("./a", "/proj/dir/b.ts") == "/proj/dir/a.ts")
    }

    @Test
    fun `a dot-dot segment is normalised against the importing file's directory`() {
        val r = specifierResolver("/proj/a.ts", "/proj/dir/b.ts")
        assert(r.resolveModuleSpecifierRelative("../a", "/proj/dir/b.ts") == "/proj/a.ts")
    }

    @Test
    fun `the js-aware resolver strips an ESM js extension and the base resolver does not`() {
        val r = specifierResolver("/proj/dir/a.ts", "/proj/dir/b.ts")
        assert(r.resolveModuleSpecifierRelativeJsAware("./a.js", "/proj/dir/b.ts") == "/proj/dir/a.ts")
        assert(r.resolveModuleSpecifierRelative("./a.js", "/proj/dir/b.ts") == null)
    }

    @Test
    fun `an unresolvable specifier answers null on the second ask too`() {
        val r = specifierResolver("/proj/dir/a.ts", "/proj/dir/b.ts")
        val first = r.resolveModuleSpecifier("./nope")
        val second = r.resolveModuleSpecifier("./nope")
        assert(first == null)
        assert(second == null)
    }

    @Test
    fun `a resolvable specifier answers the same file name on the second ask`() {
        // A FLAT layout, which is the shape the context-free resolver can decide
        // (see the division-of-labour pin below).
        val r = specifierResolver("/a.ts", "/b.ts")
        val first = r.resolveModuleSpecifier("./a")
        val second = r.resolveModuleSpecifier("./a")
        assert(first == "/a.ts")
        assert(second == first)
    }

    /**
     * Why the ladder has two rungs: [NameResolver.resolveModuleSpecifier] is a
     * function of the SPECIFIER alone (round 432 memoizes it on exactly that
     * key), so it cannot place `./a` inside the importing file's directory —
     * only [NameResolver.resolveModuleSpecifierRelative], which is given the
     * importer, can. Collapsing the two would silently resolve a nested
     * relative import to nothing.
     */
    @Test
    fun `the context-free resolver cannot place a nested relative specifier and the relative one can`() {
        val r = specifierResolver("/proj/dir/a.ts", "/proj/dir/b.ts")
        assert(r.resolveModuleSpecifier("./a") == null)
        assert(r.resolveModuleSpecifierRelative("./a", "/proj/dir/b.ts") == "/proj/dir/a.ts")
    }

    @Test
    fun `negative control - a specifier naming no program file resolves to null`() {
        val r = specifierResolver("/proj/dir/a.ts", "/proj/dir/b.ts")
        assert(r.resolveModuleSpecifierRelative("./absent", "/proj/dir/b.ts") == null)
    }
}
