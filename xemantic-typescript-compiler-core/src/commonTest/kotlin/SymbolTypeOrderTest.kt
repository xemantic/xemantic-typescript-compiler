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
 * (ORDER.1) round 778: a generic member's resolved type must not depend on WHICH
 * code touched it first.
 *
 * `getTypeOfSymbol` persisted every resolution into the global `symbolTypes`
 * cache, including resolutions made while a caller's instantiation context
 * (type-param scope / alias args / inference namespace) was installed — the exact
 * context `getTypeFromTypeNodeCore` has always refused to cache a type NODE under.
 * For a member whose annotation IS a type parameter (`interface R<T> { kind: T }`)
 * that froze one of two different answers globally: the bare parameter `T` when
 * something instantiated `R<T>` inside a generic scope first, `any` otherwise.
 *
 * Live consequence: `services/formatting/formattingScanner.ts:311` emitted
 * `TS2322: Type 'SyntaxKind' is not assignable to type 'T'` on the services,
 * server and harness profiles for exactly as long as round 776's crawl sort had
 * been in place — the sort did not create the bug, it reordered the program and
 * so changed which file won the race. The shape below is tsc's
 * `TextRangeWithKind<T extends SyntaxKind = SyntaxKind>` reduced to its
 * load-bearing ingredients: a defaulted type parameter, a member typed by it, a
 * second declaration that instantiates the interface at a type PARAMETER, and a
 * bare (defaulted) read that assigns to the member.
 */
class SymbolTypeOrderTest {

    private val prelude = """
        enum SyntaxKind { A, B, C }
        interface TextRange { pos: number; end: number; }
        interface TextRangeWithKind<T extends SyntaxKind = SyntaxKind> extends TextRange {
            kind: T;
        }
        interface Node { kind: SyntaxKind; }
    """.trimIndent() + "\n"

    /** Instantiates `TextRangeWithKind<T>` with T in scope — the poisoning touch. */
    private val maker = """
        function make<T extends SyntaxKind>(kind: T): TextRangeWithKind<T> {
            const r: TextRangeWithKind<T> = { pos: 0, end: 0, kind };
            return r;
        }
    """.trimIndent() + "\n"

    /** Reads the member through the BARE (defaulted) instantiation and writes it. */
    private val fixer = """
        function fix(token: TextRangeWithKind, container: Node): void {
            token.kind = container.kind;
        }
    """.trimIndent() + "\n"

    // --- source order within one file ------------------------------------

    @Test
    fun `a generic instantiation seen BEFORE the bare read does not poison the member type`() {
        val diagnostics = diagnose(prelude + maker + fixer)
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `a generic instantiation seen AFTER the bare read leaves the member type alone`() {
        val diagnostics = diagnose(prelude + fixer + maker)
        assert(diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `the two source orders of the same program answer identically`() {
        val before = diagnose(prelude + maker + fixer).map { it.code }.sorted()
        val after = diagnose(prelude + fixer + maker).map { it.code }.sorted()
        assert(before == after)
    }

    // --- program order across files ---------------------------------------

    private val tsconfig =
        """{ "compilerOptions": { "strict": true, "module": "nodenext", "target": "es2020",
                                  "types": [], "noEmit": true },
             "include": ["src/**/*"] }"""

    private val types = """
        export enum SyntaxKind { A, B, C }
        export interface TextRange { pos: number; end: number; }
        export interface TextRangeWithKind<T extends SyntaxKind = SyntaxKind> extends TextRange {
            kind: T;
        }
        export interface Node { kind: SyntaxKind; }
    """.trimIndent() + "\n"

    private val makerModule = """
        import { SyntaxKind, TextRangeWithKind } from "./p_types.js";
        export function make<T extends SyntaxKind>(kind: T): TextRangeWithKind<T> {
            const r: TextRangeWithKind<T> = { pos: 0, end: 0, kind };
            return r;
        }
    """.trimIndent() + "\n"

    private val fixerModule = """
        import { Node, TextRangeWithKind } from "./p_types.js";
        export function fix(token: TextRangeWithKind, container: Node): void {
            token.kind = container.kind;
        }
    """.trimIndent() + "\n"

    /**
     * Program order is the crawl's depth-first ALPHABETICAL order since round 776,
     * so the two orders are produced by renaming the two modules rather than by
     * reversing a listing (which [ProjectCrawlOrderTest] already proves cannot
     * change program order any more).
     */
    private fun codesFor(makerName: String, fixerName: String): List<Int> {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to tsconfig,
                "/proj/src/p_types.ts" to types,
                "/proj/src/$makerName" to makerModule,
                "/proj/src/$fixerName" to fixerModule,
            )
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
            .diagnostics.map { it.code }.sorted()
    }

    @Test
    fun `the generic instantiation checked FIRST in program order emits nothing`() {
        assert(codesFor("a_maker.ts", "b_fixer.ts").none { it == 2322 })
    }

    @Test
    fun `the two program orders of the same project answer identically`() {
        val makerFirst = codesFor("a_maker.ts", "b_fixer.ts")
        val fixerFirst = codesFor("b_maker.ts", "a_fixer.ts")
        assert(makerFirst == fixerFirst)
    }

    // --- regression guards: identical with and without the gate -----------

    @Test
    fun `a non-generic member of the same interface is still checked`() {
        val diagnostics = diagnose(
            prelude + maker + """
                function bad(token: TextRangeWithKind): void {
                    token.pos = "nope";
                }
            """.trimIndent() + "\n"
        )
        assert(diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `the generic member is still resolvable - no unknown-property error`() {
        val diagnostics = diagnose(prelude + maker + fixer)
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `an instantiation missing the type-parameter-typed member is still rejected`() {
        val diagnostics = diagnose(
            prelude + """
                function make2<T extends SyntaxKind>(): TextRangeWithKind<T> {
                    const r: TextRangeWithKind<T> = { pos: 0, end: 0 };
                    return r;
                }
            """.trimIndent() + "\n"
        )
        assert(diagnostics.any { it.code == 2739 || it.code == 2741 || it.code == 2322 })
    }
}
