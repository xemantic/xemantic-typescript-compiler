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
 * Pins that a [CheckedNodeSink] reaches a whole PROJECT build, and not just a
 * directly-constructed [Checker].
 *
 * That distinction is the entire point of the threading: a `Checker` built by
 * hand takes one `BinderResult` and resolves no imports, so a backend fed that
 * way sees every cross-file type as `any` — which is SILENT, because `any` is
 * assignable to everything and produces no diagnostic. Only a project build
 * crawls the import graph, and only then are the types a backend lowers the
 * types the program actually has.
 */
class CheckedSinkProjectTest {

    /** Records which FILE each expression the sink saw came from. */
    private class FileRecordingSink : CheckedNodeSink {

        val filesWithExpressions = mutableSetOf<String>()
        val typeTextsByFile = mutableMapOf<String, MutableSet<String>>()
        var declarationCount = 0

        override fun expression(node: Expression, lens: CheckedLens) {
            val file = fileOf(node) ?: return
            filesWithExpressions.add(file)
            typeTextsByFile.getOrPut(file) { mutableSetOf() }.add(lens.render(lens.typeOf(node)))
        }

        override fun declaration(node: Node, lens: CheckedLens) {
            declarationCount++
        }

        /** The owning file, by walking [NodeBase.parent] up to the [SourceFile] root. */
        private fun fileOf(node: Node): String? {
            var current: Node? = node
            while (current != null) {
                if (current is SourceFile) return current.fileName
                current = (current as NodeBase).parent
            }
            return null
        }
    }

    private fun twoFileProject() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to """
                { "compilerOptions": { "strict": true, "module": "esnext" } }
            """.trimIndent(),
            "/proj/a.ts" to """
                export interface Shape { readonly width: number }
                export function widen(shape: Shape): number { return shape.width * 2; }
            """.trimIndent(),
            "/proj/b.ts" to """
                import { widen, Shape } from "./a";
                const box: Shape = { width: 21 };
                export const doubled: number = widen(box);
            """.trimIndent(),
        )
    )

    @Test
    fun `a project build hands the sink expressions from every file`() {
        val sink = FileRecordingSink()
        val result = ProjectCompiler(twoFileProject())
            .build("/proj", noEmit = true, checkedSink = sink)
        assert(result.diagnostics.none { it.category == DiagnosticCategory.Error })
        assert("/proj/a.ts" in sink.filesWithExpressions)
        assert("/proj/b.ts" in sink.filesWithExpressions)
        assert(sink.declarationCount > 0)
    }

    /**
     * The reason the seam had to be threaded at all: the cross-file call is typed.
     *
     * `widen(box)` is a call to a function declared in the OTHER file. Without
     * import resolution `widen` would be unresolved, its call would type as `any`,
     * and the assertion below would read `any` — which is exactly the silent
     * failure a single-file harness cannot distinguish from success.
     */
    @Test
    fun `a cross-file call is typed rather than degraded to any`() {
        val sink = FileRecordingSink()
        ProjectCompiler(twoFileProject()).build("/proj", noEmit = true, checkedSink = sink)
        val typesInB = sink.typeTextsByFile["/proj/b.ts"].orEmpty()
        assert("number" in typesInB)
        assert("Shape" in typesInB)
    }

    /**
     * A partition checker walks a SUBSET of the program, so a sink attached to one
     * would collect a subset of the facts — silently, since nothing downstream can
     * tell a missing fact from a fact about a construct that is not there.
     * `Checker`'s own `require` refuses the combination; this pins that the refusal
     * happens at the API boundary, with a message naming the caller's mistake.
     */
    @Test
    fun `a sink is refused beside an incremental recheck partition`() {
        val sink = FileRecordingSink()
        var refused = false
        try {
            TypeScriptCompiler().compileParsed(
                ParsedSource(
                    CompilerOptions(),
                    listOf(SourceFileEntry("/proj/a.ts", "export const x: number = 1;")),
                    hasExplicitFilenames = true,
                ),
                CompilerOptions(),
                recheckOnly = setOf("/proj/a.ts"),
                checkedSink = sink,
            )
        } catch (_: IllegalArgumentException) {
            refused = true
        }
        assert(refused)
    }
}
