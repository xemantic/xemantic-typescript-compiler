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
 * (INC.70) A BUILD CONSTRUCTS THE PER-FILE SCOPES IT READS AND NO OTHERS.
 *
 * ## What was wrong
 *
 * `init:buildPerFileScopes` allocated two maps per program file, copied that file's
 * own top-level locals into one of them and precomputed a `LayeredSymbolTable`'s
 * shadow list — for EVERY file, on EVERY build, whether or not a single name was
 * ever resolved in it. Measured on the 2,401-file `many-small-2400-dom` fixture it
 * is **3.3 ms of a ~120 ms incremental floor**, i.e. work an editor pays on every
 * keystroke to answer a question about one file.
 *
 * ## Why the count is the assertion and what makes it a MEASUREMENT
 *
 * (INC.16)'s law: whether deferring a per-file table pays is decided by WHO FORCES
 * it, and that is a population to be measured, not read off the source. Driven
 * through `ProjectCompiler`, the population is exact — `binderResults.size` on a
 * whole-program build, **zero** on a build whose partition names no program file,
 * and strictly between the two on an ordinary narrowed query. The eager form reads
 * `binderResults.size` for all three, which is the ablation this pin names.
 *
 * ## Why the value half is not implied by it
 *
 * A build that skipped a scope it DOES need passes every count assertion and
 * silently resolves a name against the wrong table — and the failure direction is
 * the quiet one: `perFileScopeOf` answering null means "scopes unbuilt", which the
 * consumers treat as "fall back to the merged `globals`", so a missing scope is a
 * name that resolves to a FOREIGN module's local rather than an error anyone would
 * see. Hence the script-file-global fixture below: `zzzScriptGlobal` is visible to
 * `user.ts` only through the shared base of its per-file scope, and `zzzMissingName`
 * must still be TS2304.
 */
class ProjectLazyPerFileScopeTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val globalsFile = "/proj/src/globals.ts"
    private val userFile = "/proj/src/user.ts"
    private val badFile = "/proj/src/bad.ts"
    private val modLocalFile = "/proj/src/modlocal.ts"
    private val leakFile = "/proj/src/leak.ts"
    private val bystanderFile = "/proj/src/bystander.ts"

    /** A SCRIPT file — no import and no export — so its locals are global to the program. */
    private val globalsText = "declare const zzzScriptGlobal: string;\n"

    /** Resolves `zzzScriptGlobal` only through the script-file layer of its per-file scope. */
    private val userText = "export const a: string = zzzScriptGlobal;\n"

    /**
     * The negative control for the same mechanism: names no file declares. THREE of
     * them, and that is deliberate — one name per file would make "scopes built" and
     * "files asked about" the same number, so an implementation that rebuilt the scope
     * on every ask would satisfy the whole-build count pin by coincidence (measured:
     * ablation b3 read 0 RED against a one-name fixture).
     */
    private val badText =
        "export const b = zzzMissingName1 + zzzMissingName2 + zzzMissingName3;\n"

    /** A MODULE file: its top-level locals must NOT be visible to any other file. */
    private val modLocalText = "export const zzzModuleLocal = 1;\n"

    /**
     * THE pin that separates a per-file scope from the merged `globals`. `leak.ts`
     * does not import `zzzModuleLocal`, so the answer is TS2304 — and the ONLY thing
     * that says so is [modLocalFile]'s per-file visibility model, since the name IS
     * in the merged `globals`. A build that answered "scopes unbuilt" for this file
     * would fall back to `globals` and report NOTHING, which is the silent direction.
     */
    private val leakText = "export const c = zzzModuleLocal;\n"

    private val bystanderText = "export function untouched(n: number): number { return n + 1; }\n"

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            globalsFile to globalsText,
            userFile to userText,
            badFile to badText,
            modLocalFile to modLocalText,
            leakFile to leakText,
            bystanderFile to bystanderText,
        ),
    )

    private fun rowsIn(diagnostics: List<Diagnostic>, file: String) =
        diagnostics.filter { it.fileName == file }.map { "${it.code}@${it.start}" }.sorted()

    /**
     * Runs [block] and returns how many per-file scopes it built. The census is
     * process-global and unconditional — there is no mode to arm, which is
     * (INC.16)'s point: a pin that installs the mode it wants leaves the shipped
     * default pinned by nothing.
     */
    private fun scopesBuiltDuring(block: () -> Unit): Int {
        EagerIndexCensus.perFileScopeBuilds = 0
        block()
        return EagerIndexCensus.perFileScopeBuilds
    }

    @Test
    fun `the whole-program build resolves the script global and reports the missing name`() {
        val whole = ProjectCompiler(vfs()).build("/proj", noEmit = true)
        assert(rowsIn(whole.diagnostics, userFile).isEmpty())
        assert(whole.diagnostics.any { it.fileName == badFile && it.code == 2304 })
        assert(rowsIn(whole.diagnostics, bystanderFile).isEmpty())
    }

    @Test
    fun `a whole-program build constructs one per-file scope for every program file`() {
        val vfs = vfs()
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val built = scopesBuiltDuring { ProjectCompiler(vfs).build("/proj", noEmit = true) }
        assert(built == result.programFiles.size)
    }

    /**
     * THE LEVER. A build whose partition names no program file checks nothing, so it
     * may read no per-file scope at all — and on the eager form this is the whole
     * `binderResults.size`. It is the incremental FLOOR, i.e. what an editor pays
     * before it has asked anything.
     */
    @Test
    fun `a build that checks nothing constructs no per-file scope at all`() {
        val built = scopesBuiltDuring {
            ProjectCompiler(vfs()).build(
                "/proj",
                noEmit = true,
                recheckOnly = setOf("/proj/src/no-such-file.ts"),
            )
        }
        assert(built == 0)
    }

    @Test
    fun `a narrowed query constructs strictly fewer per-file scopes than the whole build`() {
        val vfs = vfs()
        val whole = scopesBuiltDuring { ProjectCompiler(vfs).build("/proj", noEmit = true) }
        val narrowed = scopesBuiltDuring {
            ProjectCompiler(vfs).build("/proj", noEmit = true, recheckOnly = setOf(userFile))
        }
        assert(whole >= 6)
        assert(narrowed < whole)
    }

    @Test
    fun `a partition of the user file still resolves the script global`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(userFile))
        assert(rowsIn(whole.diagnostics, userFile).isEmpty())
        assert(rowsIn(narrowed.diagnostics, userFile) == rowsIn(whole.diagnostics, userFile))
    }

    @Test
    fun `a module file's local does not leak into another file - whole program`() {
        val whole = ProjectCompiler(vfs()).build("/proj", noEmit = true)
        assert(whole.diagnostics.any { it.fileName == leakFile && it.code == 2304 })
    }

    @Test
    fun `a module file's local does not leak into another file - narrowed to that file`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(leakFile))
        assert(rowsIn(whole.diagnostics, leakFile).isNotEmpty())
        assert(rowsIn(narrowed.diagnostics, leakFile) == rowsIn(whole.diagnostics, leakFile))
    }

    @Test
    fun `a partition of the bad file still reports the missing name`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(badFile))
        assert(rowsIn(whole.diagnostics, badFile).isNotEmpty())
        assert(rowsIn(narrowed.diagnostics, badFile) == rowsIn(whole.diagnostics, badFile))
    }
}
