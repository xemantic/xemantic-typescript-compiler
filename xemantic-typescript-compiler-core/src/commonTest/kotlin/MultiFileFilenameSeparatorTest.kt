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
 * (P18.139) A `// @Filename:` VALUE IS A PATH, AND A RUN OF `/` IN IT NAMES THE SAME FILE
 * AS ONE `/` — so the program's file name collapses it, exactly where tsgo's harness does.
 *
 * `jsDeclarationEmitExportedClassWithExtends` spells
 * `// @filename: node_modules/lit-element/development//lit-element.d.ts` in the fixture
 * ITSELF, so the doubled separator is AUTHORED, not produced by any join. tsgo's harness
 * puts every unit name through `tspath.GetNormalizedAbsolutePath`
 * (`internal/testrunner/compiler_runner.go`'s `createHarnessTestFile`) before it becomes a
 * program file name, and its error baseline then writes that name into the
 * `==== <file> (N errors) ====` header — which is the whole of that baseline's recorded
 * divergence (`submoduleAccepted/compiler/…errors.txt.diff`, a one-line hunk).
 *
 * **WHY THE COLLAPSE IS NARROW AND A FULL NORMALIZE IS NOT THE FIX.** tsgo's diff machinery
 * forgives exactly one half of its own normalization: `DiffFixupOld`
 * (`compiler_runner.go:369`) rewrites `==== ./` to `==== ` in the OLD baseline before
 * comparing, so a leading `./` never becomes a recorded divergence while a `//` does.
 * Censused over the whole corpus — 1,556 distinct `@Filename` values — a full
 * [PathUtil.normalize] would move **22** of them: nineteen leading-`./` names (whose
 * baselines keep the `./`, because that is what the fixup forgave), two Windows
 * `C:\a\b\c.ts` names from a fixture with no `.errors.txt` baseline at all, and **one**
 * `//` name. The narrow collapse is therefore the exact part of tsgo's normalization that
 * tsgo's own diff records, and the `./` pin below is what separates the two — a full
 * normalize fails it.
 *
 * NOTHING ELSE IN THIS COMPILER PRODUCES A `//`: [PathUtil.normalize] drops empty segments,
 * [PathUtil.join]'s fast path handles the root head explicitly (`PathJoinFastPathTest`),
 * and a real project's names come from the filesystem, which has no `//` component to give
 * back — which is also why this pin cannot live in the `-project` module behind a `Vfs`
 * crawl. Per (CFG.1) a wrong resolved path has no diagnostic channel here, so the whole
 * corpus being green is a CONTROL for this and not evidence; the census and these pins are.
 */
class MultiFileFilenameSeparatorTest {

    @Test
    fun `a doubled separator in a Filename directive collapses in the program file name`() {
        val parsed = parseMultiFileSource(
            """
            // @Filename: node_modules/lit-element/development//lit-element.d.ts
            export class LitElement {}
            """.trimIndent(),
            "t.ts",
        )
        assert(parsed.files.map { it.fileName } == listOf("node_modules/lit-element/development/lit-element.d.ts"))
    }

    @Test
    fun `a longer separator run collapses to one`() {
        val parsed = parseMultiFileSource(
            """
            // @Filename: a///b////c.ts
            export const x = 1;
            """.trimIndent(),
            "t.ts",
        )
        assert(parsed.files.map { it.fileName } == listOf("a/b/c.ts"))
    }

    @Test
    fun `a leading dot-slash is PRESERVED - the collapse is not a normalize`() {
        val parsed = parseMultiFileSource(
            """
            // @Filename: ./a.d.ts
            export interface A { n: number }
            // @Filename: ./node_modules/tslib/index.d.ts
            export declare function __extends(): void;
            """.trimIndent(),
            "t.ts",
        )
        assert(parsed.files.map { it.fileName } == listOf("./a.d.ts", "./node_modules/tslib/index.d.ts"))
    }

    @Test
    fun `an interior dot segment is PRESERVED - the collapse is not a normalize`() {
        val parsed = parseMultiFileSource(
            """
            // @Filename: a/./b.ts
            export const x = 1;
            """.trimIndent(),
            "t.ts",
        )
        assert(parsed.files.map { it.fileName } == listOf("a/./b.ts"))
    }

    @Test
    fun `a leading slash survives the collapse`() {
        val parsed = parseMultiFileSource(
            """
            // @Filename: /a//b.ts
            export const x = 1;
            """.trimIndent(),
            "t.ts",
        )
        assert(parsed.files.map { it.fileName } == listOf("/a/b.ts"))
    }

    @Test
    fun `a diagnostic reported in a doubly-separated file names the collapsed path`() {
        val d = diagnose(
            """
            // @Filename: pkg/dir//broken.ts
            const n: number = "s";
            """.trimIndent(),
            directives = "// @strict: true",
        )
        val rows = d.filter { it.code == 2322 }
        assert(rows.size == 1)
        assert(rows[0].fileName == "pkg/dir/broken.ts")
    }

    @Test
    fun `collapseDuplicateSeparators is identity on an already-single-separator path`() {
        assert(collapseDuplicateSeparators("node_modules/lit-element/development/lit-element.d.ts")
            == "node_modules/lit-element/development/lit-element.d.ts")
        assert(collapseDuplicateSeparators("") == "")
        assert(collapseDuplicateSeparators("/") == "/")
        assert(collapseDuplicateSeparators("a.ts") == "a.ts")
    }

    @Test
    fun `collapseDuplicateSeparators leaves a backslash alone`() {
        assert(collapseDuplicateSeparators("""C:\a\b\c.ts""") == """C:\a\b\c.ts""")
    }
}
