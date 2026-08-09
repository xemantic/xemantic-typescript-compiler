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
 * (WARM.15) round 868 — pins [buildStarExportIndex].
 *
 * **What can go wrong, and why only these assertions can see it.** The index
 * replaces four per-visit statement scans inside the `export *` barrel walks,
 * and it is sound only while every field is a TRANSCRIPTION of the predicate it
 * replaced: the same modifier gate, the same first-wins rule, the same source
 * order, the same set of descendable edges. Every one of those is invisible
 * downstream — a wrong gate makes a barrel walk answer a declaration it should
 * not have found, or miss one, which surfaces (if at all) as an unrelated
 * diagnostic in a file no fixture here contains. So the rules are pinned AT the
 * index, one assertion per rule, on a parsed file rather than through a compile.
 *
 * Every assertion is over scalars or lists of strings, never over an AST node:
 * a power-assert diagram renders each captured subexpression, and a node's
 * `toString` is its whole subtree.
 *
 * Each pin was checked against a deliberately broken index, one mistake at a
 * time (round 807); the session note records which pin caught which.
 */
class StarExportIndexTest {

    private fun index(source: String, targets: Map<String, SourceFile> = emptyMap()): StarExportIndex {
        val file = Parser(source.trimIndent(), "/proj/src/a.ts").parse()
        return buildStarExportIndex(file) { spec, _ -> targets[spec] }
    }

    private fun stub(name: String): SourceFile = Parser("export const x = 1\n", name).parse()

    private fun paramKinds(idx: StarExportIndex, name: String): List<SyntaxKind> =
        idx.fnDecls[name].orEmpty().map { fn ->
            (fn.parameters.firstOrNull()?.type as? KeywordTypeNode)?.kind ?: SyntaxKind.Unknown
        }

    private fun annotationKind(idx: StarExportIndex, name: String): SyntaxKind =
        (idx.varDecls[name]?.type as? KeywordTypeNode)?.kind ?: SyntaxKind.Unknown

    /**
     * Overloads: ALL of a name's exported declarations, in statement order — the
     * walk returned the whole filtered list and its caller builds a transient
     * symbol from it, so losing one silently drops an overload.
     */
    @Test
    fun `exported function overloads are grouped by name in statement order`() {
        val idx = index(
            """
            export function f(x: string): number
            export function f(x: number): number
            export function f(x: boolean): number { return 1 }
            export function g(): void {}
            """
        )
        assert(idx.fnDecls["f"]?.size == 3)
        assert(
            paramKinds(idx, "f") ==
                listOf(SyntaxKind.StringKeyword, SyntaxKind.NumberKeyword, SyntaxKind.BooleanKeyword),
        )
        assert(idx.fnDecls["g"]?.size == 1)
    }

    /**
     * …and the EXPORT modifier is the gate. A local function of the same name is
     * not what `export *` re-exports, and admitting it would make a barrel walk
     * stop at the first file that merely declares the name.
     */
    @Test
    fun `a non-exported function is absent from the index`() {
        val idx = index(
            """
            function hidden(): void {}
            export function shown(): void {}
            """
        )
        assert(idx.fnDecls.keys == setOf("shown"))
    }

    /**
     * Variables are FIRST-WINS across statements AND across the declaration list
     * of one statement — the walk returned on its first match, so a last-wins
     * map answers a different declaration for a re-declared name.
     */
    @Test
    fun `the variable index is first-wins over statement and declaration order`() {
        val idx = index(
            """
            export var a: string = "1", b: string = "2"
            export var c: number = 3
            export var a: boolean = true
            export var b: boolean = false
            """
        )
        // `a` and `b` are declared twice each; the FIRST declaration wins, and
        // the first ones are the `string`-annotated pair of the first statement.
        assert(annotationKind(idx, "a") == SyntaxKind.StringKeyword)
        assert(annotationKind(idx, "b") == SyntaxKind.StringKeyword)
        assert(annotationKind(idx, "c") == SyntaxKind.NumberKeyword)
    }

    /** A non-exported variable, and a destructuring pattern, are both absent. */
    @Test
    fun `negative control - unexported and pattern-named variables are absent`() {
        val idx = index(
            """
            const local: string = "x"
            export const { p, q } = { p: 1, q: 2 }
            export const plain: string = "y"
            """
        )
        assert(idx.varDecls.keys == setOf("plain"))
    }

    /** Interfaces: the exported ones only. */
    @Test
    fun `only exported interfaces are in the interface set`() {
        val idx = index(
            """
            interface Hidden { a: string }
            export interface Shown { b: string }
            """
        )
        assert(idx.interfaceNames == setOf("Shown"))
    }

    /**
     * THE EDGE SET — the load-bearing one. A bare `export *` is descendable with
     * a null clause; an `export { … } from` is descendable WITH its clause (the
     * walk renames through it); and the three shapes the walk skipped must stay
     * skipped, because descending one of them would re-export names the module
     * does not actually expose.
     */
    @Test
    fun `only bare star and named re-exports are descendable edges`() {
        val idx = index(
            """
            export * from "./bare"
            export { a as b } from "./named"
            export * as ns from "./asns"
            export { local }
            const local = 1
            """,
            targets = mapOf(
                "./bare" to stub("/proj/src/bare.ts"),
                "./named" to stub("/proj/src/named.ts"),
                "./asns" to stub("/proj/src/asns.ts"),
            ),
        )
        val shape = idx.reExports.map { "${it.target.fileName}|${it.named?.elements?.size ?: -1}" }
        assert(shape == listOf("/proj/src/bare.ts|-1", "/proj/src/named.ts|1"))
    }

    /**
     * …and source ORDER is part of the answer: the walks return the FIRST target
     * that resolves the name, so a reordered edge list resolves a name declared
     * in two barrels to the wrong file.
     */
    @Test
    fun `re-export edges keep source order`() {
        val idx = index(
            """
            export * from "./one"
            export * from "./two"
            export * from "./three"
            """,
            targets = mapOf(
                "./one" to stub("/proj/src/one.ts"),
                "./two" to stub("/proj/src/two.ts"),
                "./three" to stub("/proj/src/three.ts"),
            ),
        )
        assert(
            idx.reExports.map { it.target.fileName } ==
                listOf("/proj/src/one.ts", "/proj/src/two.ts", "/proj/src/three.ts"),
        )
    }

    /**
     * An unresolvable specifier is not an edge — the walk's `?: continue`.
     * Pinned because the pre-resolution moved that decision from the walk to the
     * index, where a null target stored as an edge would be a descent into
     * nothing.
     */
    @Test
    fun `an unresolvable specifier contributes no edge`() {
        val idx = index(
            """
            export * from "./missing"
            export * from "./present"
            """,
            targets = mapOf("./present" to stub("/proj/src/present.ts")),
        )
        assert(idx.reExports.map { it.target.fileName } == listOf("/proj/src/present.ts"))
    }
}
