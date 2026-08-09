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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (WARM.17) round 870 — pins [buildModuleSymbolScanIndex].
 *
 * **What can go wrong, and why only assertions AT the index can see it.** The
 * list replaces the `binderResults x locals` scan inside
 * `Checker.computeTypeParamInfo`, which is a FIRST-MATCH search: the first
 * module symbol whose `exports` answers wins. So a wrong membership rule or a
 * wrong order does not crash and does not usually change a diagnostic either —
 * it changes WHICH namespace answers "how many type arguments does `X` need?",
 * and that surfaces (if at all) as a TS2314 with the wrong arity in a file no
 * fixture here contains. Pinning through a compile would therefore be pinning
 * the symptom's absence.
 *
 * Every assertion is over strings and ints, never over a `Symbol` or an AST
 * node: `assert` is power-assert-transformed and renders every captured
 * subexpression, and a node's `toString` is its whole subtree.
 *
 * The membership rule has a trap of its own that CLAUDE.md records:
 * `SymbolFlags.Module` is the UNION of `ValueModule` and `NamespaceModule`, and
 * the binder gives those to different syntax (an instantiated namespace versus
 * a type-only one). A rule written with either half alone type-checks, compiles
 * and silently loses one of them — hence two separate pins rather than one.
 *
 * Each pin was checked against a deliberately broken index, one mistake at a
 * time (round 807); the session note records which pin caught which.
 */
class ModuleSymbolScanIndexTest {

    private fun results(vararg files: Pair<String, String>): List<BinderResult> {
        val options = CompilerOptions()
        return files.map { (name, src) -> Binder(options).bind(Parser(src.trimIndent(), name).parse()) }
    }

    private fun names(results: List<BinderResult>): List<String> =
        buildModuleSymbolScanIndex(results).map { it.name }

    /**
     * The membership rule, positive half #1: an INSTANTIATED namespace carries
     * `SymbolFlags.ValueModule`, one of the two halves of `Module`.
     */
    @Test
    fun `an instantiated namespace is in the index`() {
        val idx = names(results("/proj/a.ts" to """
            namespace N { export const x = 1 }
        """))
        assert(idx == listOf("N"))
    }

    /**
     * The membership rule, positive half #2 — and the CLAUDE.md trap: a
     * namespace with no value members is `NamespaceModule`, the OTHER half of
     * `SymbolFlags.Module`. A gate written as `ValueModule` alone passes the
     * pin above and fails this one.
     */
    @Test
    fun `a type-only namespace is in the index`() {
        val idx = names(results("/proj/a.ts" to """
            namespace T { export interface I { a: string } }
        """))
        assert(idx == listOf("T"))
    }

    /**
     * The membership rule, negative control: everything that is NOT a module.
     * The scan this list replaces tested one flag over every entry of every
     * file's `locals`, so admitting one extra kind means probing a symbol whose
     * `exports` may answer for an unrelated name.
     */
    @Test
    fun `negative control - non-module symbols are absent`() {
        val idx = names(results("/proj/a.ts" to """
            const v = 1
            function f() {}
            class C {}
            interface I { a: string }
            type A = string
            enum E { X }
            namespace N { export const x = 1 }
        """))
        assert(idx == listOf("N"))
    }

    /**
     * FILE order. The scan visited `binderResults` in order and returned the
     * FIRST module symbol whose exports answered, so reordering the files
     * changes which namespace wins a name they both export — which is exactly
     * the divergence no downstream assertion can attribute.
     */
    @Test
    fun `module symbols keep binderResults file order`() {
        val idx = names(results(
            "/proj/a.ts" to "namespace A { export const x = 1 }",
            "/proj/b.ts" to "namespace B { export const x = 1 }",
            "/proj/c.ts" to "namespace C { export const x = 1 }",
        ))
        assert(idx == listOf("A", "B", "C"))
    }

    /**
     * WITHIN-file order. A `SymbolTable` is `mutableMapOf()`, i.e. insertion
     * ordered, and the scan inherited that order — so the index must too, for
     * the same first-match reason as the pin above.
     */
    @Test
    fun `module symbols keep the locals insertion order within a file`() {
        val idx = names(results("/proj/a.ts" to """
            namespace Z { export const z = 1 }
            const between = 1
            namespace M { export const m = 1 }
            namespace A { export const a = 1 }
        """))
        assert(idx == listOf("Z", "M", "A"))
    }

    /**
     * DUPLICATES are preserved. The same namespace name declared in two files
     * gives two entries, because the scan probed each file's own local symbol
     * and a de-duplicating index would drop the second one's `exports`.
     */
    @Test
    fun `a namespace declared in two files contributes two entries`() {
        val idx = names(results(
            "/proj/a.ts" to "namespace N { export const x = 1 }",
            "/proj/b.ts" to "namespace N { export const y = 1 }",
        ))
        assert(idx == listOf("N", "N"))
    }

    /**
     * MERGED declarations in ONE file are ONE symbol, and so ONE entry — the
     * complement of the pin above, and the reason it cannot be stated as
     * "one entry per declaration".
     */
    @Test
    fun `a namespace declared twice in one file contributes one entry`() {
        val idx = names(results("/proj/a.ts" to """
            namespace N { export const x = 1 }
            namespace N { export const y = 1 }
        """))
        assert(idx == listOf("N"))
    }

    /** A file with no module symbol contributes nothing, and does not disturb the order. */
    @Test
    fun `a file with no module symbols contributes nothing`() {
        val idx = names(results(
            "/proj/a.ts" to "namespace A { export const x = 1 }",
            "/proj/b.ts" to "const plain = 1",
            "/proj/c.ts" to "namespace C { export const x = 1 }",
        ))
        assert(idx == listOf("A", "C"))
    }

    /** No files at all — the empty program is the degenerate case of the loop. */
    @Test
    fun `an empty program has an empty index`() {
        assert(buildModuleSymbolScanIndex(emptyList()).isEmpty())
    }

    /**
     * The index carries the SYMBOLS, not a snapshot of their exports: the
     * `exports` table is a `var` the checker's own merging writes to, so the
     * probe has to stay live. This asserts the entry is the file's own local
     * symbol instance, which is what makes a later write to its `exports`
     * visible through the index.
     */
    @Test
    fun `the index holds the file's own local symbol instance`() {
        val results = results("/proj/a.ts" to "namespace N { export const x = 1 }")
        val idx = buildModuleSymbolScanIndex(results)
        val local = results[0].locals["N"]
        assert(idx.size == 1)
        assert(idx[0] === local)
    }
}
