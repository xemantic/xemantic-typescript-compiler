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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    private fun parse(src: String): SourceFile =
        Parser(src, "/proj/t.d.ts").parse()

    @Test
    fun `a reference path is recorded as a path, not a module specifier`() {
        val sf = parse("/// <reference path=\"globals.d.ts\" />\nexport {};\n")
        assertEquals(listOf("globals.d.ts"), sf.referencedPaths)
        assertTrue(sf.moduleSpecifiers.isEmpty(), "must not leak into moduleSpecifiers: ${sf.moduleSpecifiers}")
    }

    @Test
    fun `a reference types is recorded separately from a reference path`() {
        val sf = parse(
            "/// <reference types=\"node\" />\n/// <reference path=\"./a.d.ts\" />\nexport {};\n"
        )
        assertEquals(listOf("node"), sf.referencedTypes)
        assertEquals(listOf("./a.d.ts"), sf.referencedPaths)
    }

    @Test
    fun `many reference paths keep source order`() {
        // @types/node's index.d.ts is 64 consecutive reference lines.
        val src = (1..5).joinToString("\n") { "/// <reference path=\"f$it.d.ts\" />" } + "\nexport {};\n"
        assertEquals(listOf("f1.d.ts", "f2.d.ts", "f3.d.ts", "f4.d.ts", "f5.d.ts"), parse(src).referencedPaths)
    }

    @Test
    fun `directives after a leading BLOCK comment are still recorded`() {
        // @types/node opens with a long license block comment before its
        // reference lines — the parser must see past it.
        val sf = parse("/* license\n   text */\n/// <reference path=\"globals.d.ts\" />\nexport {};\n")
        assertEquals(listOf("globals.d.ts"), sf.referencedPaths)
    }

    @Test
    fun `real module specifiers are still recorded normally`() {
        val sf = parse("/// <reference path=\"a.d.ts\" />\nimport { x } from \"./b\";\nexport { x };\n")
        assertEquals(listOf("a.d.ts"), sf.referencedPaths)
        assertTrue("./b" in sf.moduleSpecifiers, "the import must still be a module specifier")
    }

    // ── negative controls ─────────────────────────────────────────────────

    @Test
    fun `negative control - a directive AFTER the first code token is a plain comment`() {
        val sf = parse("export {};\n/// <reference path=\"late.d.ts\" />\n")
        assertTrue(sf.referencedPaths.isEmpty(), "past first code token, got: ${sf.referencedPaths}")
    }

    @Test
    fun `negative control - a lib reference is not treated as a path`() {
        val sf = parse("/// <reference lib=\"es2015\" />\nexport {};\n")
        assertTrue(sf.referencedPaths.isEmpty())
        assertTrue(sf.referencedTypes.isEmpty())
    }

    @Test
    fun `negative control - directive text inside a string literal contributes nothing`() {
        val sf = parse("export const s = '/// <reference path=\"nope.d.ts\" />';\n")
        assertTrue(sf.referencedPaths.isEmpty(), "got: ${sf.referencedPaths}")
    }

    @Test
    fun `negative control - an empty target is ignored`() {
        val sf = parse("/// <reference path=\"\" />\nexport {};\n")
        assertTrue(sf.referencedPaths.isEmpty())
    }

    @Test
    fun `the SourceFile carries the fields even with no directives`() {
        val sf = parse("export const a = 1;\n")
        assertNotNull(sf.referencedPaths)
        assertNotNull(sf.referencedTypes)
        assertTrue(sf.referencedPaths.isEmpty() && sf.referencedTypes.isEmpty())
    }
}
