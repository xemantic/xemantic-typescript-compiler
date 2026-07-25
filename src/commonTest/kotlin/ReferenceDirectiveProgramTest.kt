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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * M4.8 (round 680): the PARSER half — `/// <reference path>` and
 * `/// <reference types>` are recorded SEPARATELY from module specifiers.
 *
 * They used to be merged into [SourceFile.moduleSpecifiers], which is the list
 * the project crawl resolves through [ModuleResolver]. A reference PATH is a
 * file path relative to the referencing file, not a module specifier, so
 * `path="globals.d.ts"` was resolved as a BARE package and failed — and that is
 * why `@types/node` contributed only its entry file (78 → 79 program files)
 * while every `process`/`Buffer`/`NodeJS` name stayed unresolved.
 *
 * The crawl half (files actually entering the program, transitively) is covered
 * by [ReferenceDirectiveCrawlTest], which needs a real project on disk.
 */
class ReferenceDirectiveProgramTest {

    // Each helper returns a plain string list: a SourceFile must never reach a
    // power-assert subexpression, which would toString the whole AST on failure.
    private fun paths(@Language("typescript") src: String): List<String> =
        Parser(src, "/proj/t.d.ts").parse().referencedPaths

    private fun types(@Language("typescript") src: String): List<String> =
        Parser(src, "/proj/t.d.ts").parse().referencedTypes

    private fun specifiers(@Language("typescript") src: String): List<String> =
        Parser(src, "/proj/t.d.ts").parse().moduleSpecifiers

    @Test
    fun `a reference path is recorded as a path - not a module specifier`() {
        val src = "/// <reference path=\"globals.d.ts\" />\nexport {};\n"
        assert(paths(src) == listOf("globals.d.ts"))
        // It must not leak into moduleSpecifiers.
        assert(specifiers(src).isEmpty())
    }

    @Test
    fun `a reference types is recorded separately from a reference path`() {
        val src = "/// <reference types=\"node\" />\n/// <reference path=\"./a.d.ts\" />\nexport {};\n"
        assert(types(src) == listOf("node"))
        assert(paths(src) == listOf("./a.d.ts"))
    }

    @Test
    fun `many reference paths keep source order`() {
        // @types/node's index.d.ts is 64 consecutive reference lines.
        val src = (1..5).joinToString("\n") { "/// <reference path=\"f$it.d.ts\" />" } + "\nexport {};\n"
        assert(paths(src) == listOf("f1.d.ts", "f2.d.ts", "f3.d.ts", "f4.d.ts", "f5.d.ts"))
    }

    @Test
    fun `directives after a leading BLOCK comment are still recorded`() {
        // @types/node opens with a long license block comment before its
        // reference lines — the parser must see past it.
        assert(paths("/* license\n   text */\n/// <reference path=\"globals.d.ts\" />\nexport {};\n") == listOf("globals.d.ts"))
    }

    @Test
    fun `real module specifiers are still recorded normally`() {
        val src = "/// <reference path=\"a.d.ts\" />\nimport { x } from \"./b\";\nexport { x };\n"
        assert(paths(src) == listOf("a.d.ts"))
        // The import must still be a module specifier.
        assert("./b" in specifiers(src))
    }

    // ── negative controls ─────────────────────────────────────────────────

    @Test
    fun `negative control - a directive AFTER the first code token is a plain comment`() {
        assert(paths("export {};\n/// <reference path=\"late.d.ts\" />\n").isEmpty())
    }

    @Test
    fun `negative control - a lib reference is not treated as a path`() {
        val src = "/// <reference lib=\"es2015\" />\nexport {};\n"
        assert(paths(src).isEmpty())
        assert(types(src).isEmpty())
    }

    @Test
    fun `negative control - directive text inside a string literal contributes nothing`() {
        assert(paths("export const s = '/// <reference path=\"nope.d.ts\" />';\n").isEmpty())
    }

    @Test
    fun `negative control - an empty target is ignored`() {
        assert(paths("/// <reference path=\"\" />\nexport {};\n").isEmpty())
    }

    @Test
    fun `the SourceFile carries the fields even with no directives`() {
        val src = "export const a = 1;\n"
        assert(paths(src).isEmpty())
        assert(types(src).isEmpty())
    }
}
