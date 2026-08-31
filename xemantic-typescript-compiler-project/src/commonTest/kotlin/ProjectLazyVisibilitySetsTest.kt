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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.EagerIndexCensus
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (INC.71) THE PER-FILE VISIBILITY SETS ARE BUILT ONLY WHEN A NAME IS RESOLVED.
 *
 * ## What they are and why the deferral pays
 *
 * `init:computePerFileVisibility` walks every program file's `locals` twice over to
 * publish `moduleOnlyGlobalNames` — the names a MODULE file declares that therefore
 * have no legitimate global meaning — and `libValueShadowNames`. Their three readers
 * are `globalsForFile`, `globalsForFileNode` and `libValueBehindTypeOnlyShadow`, and
 * **all three are name resolution**. A build whose check partition is empty resolves
 * no name at all: measured on the 2,401-file `many-small-2400-dom` fixture, **0 asks
 * on a floor build against 335,881 on a full one**, for a pass that was 5.6-7.2 ms of
 * a ~136 ms incremental floor.
 *
 * ## What the value pin has to be
 *
 * `moduleOnlyGlobalNames` is the ONLY thing standing between a module file's local
 * and every other file: `zzzModuleLocal` IS in the merged `globals`, so a build with
 * an empty set answers `leak.ts` silently instead of TS2304. That is the failure
 * direction — a name resolving to a FOREIGN module's local — and it is why the pin
 * is the leak rather than the missing name (a name no file declares is TS2304 by
 * either route, so it cannot discriminate).
 *
 * ## Why the count pin can only read 0 or 1
 *
 * Unlike the per-file scopes ((INC.70)), this is one whole-program set difference,
 * not a per-file table. So the assertion is a REGIME one — a floor build builds it
 * never, a checking build exactly once — and the eager form reads 1 for both.
 */
class ProjectLazyVisibilitySetsTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val modLocalFile = "/proj/src/modlocal.ts"
    private val leakFile = "/proj/src/leak.ts"
    private val scriptFile = "/proj/src/script.ts"
    private val userFile = "/proj/src/user.ts"

    /** A MODULE file: its top-level locals must NOT be visible to any other file. */
    private val modLocalText = "export const zzzModuleLocal = 1;\n"

    /** Reads that module local without importing it — TS2304, and only the sets say so. */
    private val leakText = "export const c = zzzModuleLocal;\n"

    /** A SCRIPT file — no import, no export — so its locals ARE global to the program. */
    private val scriptText = "declare const zzzScriptGlobal: string;\n"

    /** The positive control for the same mechanism: a name that IS legitimately global. */
    private val userText = "export const a: string = zzzScriptGlobal;\n"

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            modLocalFile to modLocalText,
            leakFile to leakText,
            scriptFile to scriptText,
            userFile to userText,
        ),
    )

    private fun rowsIn(diagnostics: List<Diagnostic>, file: String) =
        diagnostics.filter { it.fileName == file }.map { "${it.code}@${it.start}" }.sorted()

    private fun buildsDuring(block: () -> Unit): Int {
        EagerIndexCensus.perFileVisibilityBuilds = 0
        block()
        return EagerIndexCensus.perFileVisibilityBuilds
    }

    @Test
    fun `a module file's local is not visible to another file and a script file's is`() {
        val whole = ProjectCompiler(vfs()).build("/proj", noEmit = true)
        assert(whole.diagnostics.any { it.fileName == leakFile && it.code == 2304 })
        assert(rowsIn(whole.diagnostics, userFile).isEmpty())
    }

    @Test
    fun `a whole-program build builds the visibility sets exactly once`() {
        val built = buildsDuring { ProjectCompiler(vfs()).build("/proj", noEmit = true) }
        assert(built == 1)
    }

    /** THE LEVER: a build that checks nothing resolves nothing, so it builds nothing. */
    @Test
    fun `a build that checks nothing never builds the visibility sets`() {
        val built = buildsDuring {
            ProjectCompiler(vfs()).build(
                "/proj",
                noEmit = true,
                recheckOnly = setOf("/proj/src/no-such-file.ts"),
            )
        }
        assert(built == 0)
    }

    @Test
    fun `a narrowed query still builds them and still reports the leak`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        var narrowed: List<Diagnostic> = emptyList()
        val built = buildsDuring {
            narrowed = ProjectCompiler(vfs)
                .build("/proj", noEmit = true, recheckOnly = setOf(leakFile)).diagnostics
        }
        assert(built == 1)
        assert(rowsIn(whole.diagnostics, leakFile).isNotEmpty())
        assert(rowsIn(narrowed, leakFile) == rowsIn(whole.diagnostics, leakFile))
    }
}
