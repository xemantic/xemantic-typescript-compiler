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
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * (INC.56) [Project.reloadFile] — the THIRD kind of change a host reports.
 *
 * `updateFile` is for a change the host can describe and `deleteFile` for a file it
 * knows is gone; this one is for a change it CANNOT describe — a VCS checkout, an
 * external tool, an editor buffer reverted to what is on disk — and it says one
 * thing: forget everything about this path, so what the backing store says is the
 * truth again. `docs/language-service.md` § 5 and § 5a document it as first-class
 * beside the other two, and the IntelliJ plugin is told to route every VFS event
 * it cannot describe here.
 *
 * ## Why it needed a pin class of its own
 *
 * Until this class it appeared in the suite only as a STEP INSIDE two
 * `ProjectTrustedFilesystemTest` cases, i.e. as scaffolding for a different
 * mechanism's assertions — so its own three promises (drop the overlay TEXT, drop
 * the TOMBSTONE, drop what [Project.trustFilesystem] RETAINED) were pinned by
 * nothing that names them, and only the third was even exercised. Each of the three
 * is a separate map inside [OverlayVfs], and dropping two of them while missing the
 * other is a silent wrong answer: a host would hand a path back to the filesystem
 * and keep being told about text nobody has on disk any more.
 *
 * ## What makes each pin sharp
 *
 * Every value pin here drives a real DIAGNOSTIC difference between the overlay and
 * the backing store — the file on disk is clean and the overlaid buffer carries a
 * TS2322 — because "the answer did not change" is satisfied by a project that
 * answered nothing at all ((INC.40)'s a3 ablation stayed green against a language
 * service reporting no errors). The two structural promises that no value can show —
 * that the retention was dropped, and that the project was marked dirty
 * unconditionally — are pinned as COUNTS of what reached the backing store, which is
 * the honest instrument for a build (a timed assertion over a compile is a coin
 * flip, round 868).
 *
 * [OverlayVfs.revert], the implementation half, is pinned directly in
 * [OverlayVfsTest] — a broken rule there names itself instead of surfacing as a
 * missing diagnostic three layers up.
 */
class ProjectReloadFileTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val aFile = "/proj/src/a.ts"
    private val bFile = "/proj/src/b.ts"
    private val cFile = "/proj/src/c.ts"

    private val aText = "import { b } from './b';\nexport const a: number = b;\n"
    private val bText = "export const b: number = 1;\n"
    private val cText = "export const c: number = 2;\n"

    /** What an unsaved buffer puts over [cFile] — clean on disk, TS2322 in the editor. */
    private val cBroken = "export const c: string = 1;\n"

    private fun store() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            aFile to aText,
            bFile to bText,
            cFile to cText,
        ),
    )

    private fun codesIn(project: Project) = project.diagnostics().map { it.code }.sorted()

    /**
     * The whole program's file list, forced.
     *
     * Reading [Project.files] builds when the project is dirty, so this doubles as the
     * "make the next build happen" step for the count pins below.
     */
    private fun fileSet(project: Project): Set<String> = project.files.toSet()

    // ---- promise 1: the overlay TEXT is dropped ------------------------------

    @Test
    fun `reloading drops an overlaid buffer so the backing store is the truth again`() {
        val project = Project.open("/proj", store())
        assert(codesIn(project).isEmpty())
        project.updateFile(cFile, cBroken)
        assert(codesIn(project) == listOf(2322))
        project.reloadFile(cFile)
        assert(codesIn(project).isEmpty())
    }

    /**
     * The same in the other direction, so neither arm can be satisfied by a project
     * that simply answers nothing: here DISK is the broken one and the buffer repairs
     * it, and reloading must bring the error back.
     */
    @Test
    fun `reloading brings back an error the buffer was hiding`() {
        val store = store()
        store.writeText(cFile, cBroken)
        val project = Project.open("/proj", store)
        assert(codesIn(project) == listOf(2322))
        project.updateFile(cFile, cText)
        assert(codesIn(project).isEmpty())
        project.reloadFile(cFile)
        assert(codesIn(project) == listOf(2322))
    }

    // ---- promise 2: the TOMBSTONE is dropped ---------------------------------

    @Test
    fun `reloading drops a tombstone so the deleted file rejoins the program`() {
        val project = Project.open("/proj", store())
        assert(bFile in fileSet(project))
        assert(codesIn(project).isEmpty())
        project.deleteFile(bFile)
        assert(bFile !in fileSet(project))
        assert(codesIn(project).isNotEmpty())
        project.reloadFile(bFile)
        assert(bFile in fileSet(project))
        assert(codesIn(project).isEmpty())
    }

    /**
     * An overlay-ADDED file has no backing store to fall back to, so reloading it is
     * the one case where "what is on disk is the truth again" means the file goes
     * away — the mirror of [deleteFile] rather than an undo of it.
     */
    @Test
    fun `reloading a file that exists only in the overlay removes it from the program`() {
        val project = Project.open("/proj", store())
        val added = "/proj/src/added.ts"
        project.updateFile(added, "export const added: number = 3;\n")
        assert(added in fileSet(project))
        project.reloadFile(added)
        assert(added !in fileSet(project))
        assert(codesIn(project).isEmpty())
    }

    // ---- promise 3: what trustFilesystem RETAINED is dropped -----------------

    /**
     * The retention is invisible in any answer — it holds the very bytes the backing
     * store holds — so the only instrument that can see it dropped is a count of what
     * reaches that store. The first assertion is the control: without the reload the
     * retained file is NOT read again, which is the whole point of the promise.
     */
    @Test
    fun `reloading drops what the filesystem promise retained`() {
        val counting = CountingVfs(store())
        val project = Project.open("/proj", counting).also { it.trustFilesystem = true }
        fileSet(project)
        val cAfterFirst = counting.readsOf(cFile)
        assert(cAfterFirst > 0)
        // A rebuild driven by an unrelated edit answers c from the retention.
        project.updateFile(aFile, "$aText// touched\n")
        fileSet(project)
        assert(counting.readsOf(cFile) == cAfterFirst)
        // Reporting c is what makes the next build go and read it.
        project.reloadFile(cFile)
        fileSet(project)
        assert(counting.readsOf(cFile) > cAfterFirst)
    }

    /**
     * And the retention is dropped for THAT PATH ONLY — reloading is not
     * [Project.trustFilesystem] `= false`, which drops the lot. A reload that cleared
     * the whole retention would keep every value pin here green and quietly hand back
     * the entire saving the promise exists to buy.
     */
    @Test
    fun `reloading one path leaves every other path retained`() {
        val counting = CountingVfs(store())
        val project = Project.open("/proj", counting).also { it.trustFilesystem = true }
        fileSet(project)
        val bAfterFirst = counting.readsOf(bFile)
        assert(bAfterFirst > 0)
        project.reloadFile(cFile)
        fileSet(project)
        assert(counting.readsOf(bFile) == bAfterFirst)
    }

    // ---- the no-op case, and the dirtying that is not a no-op ----------------

    @Test
    fun `reloading a path that was never overlaid changes no answer`() {
        val project = Project.open("/proj", store())
        val codesBefore = codesIn(project)
        val filesBefore = fileSet(project)
        project.reloadFile(bFile)
        assert(codesIn(project) == codesBefore)
        assert(fileSet(project) == filesBefore)
        // …and a path this project has never heard of is harmless too.
        project.reloadFile("/proj/src/never-existed.ts")
        assert(codesIn(project) == codesBefore)
        assert(fileSet(project) == filesBefore)
    }

    /**
     * (INC.56) It marks the project dirty UNCONDITIONALLY, exactly as [Project.updateFile]
     * does and for the same reason: whether a reported change took effect must not
     * depend on a comparison the caller cannot see. So the pin above is a statement
     * about the ANSWER, never about the work — the build really does happen again.
     */
    @Test
    fun `reloading marks the project dirty exactly as an edit does`() {
        val counting = CountingVfs(store())
        val project = Project.open("/proj", counting)
        project.diagnostics()
        val afterFirst = counting.touches
        assert(afterFirst > 0)
        // The control: with nothing reported, a second query is served from the cache.
        project.diagnostics()
        assert(counting.touches == afterFirst)
        project.reloadFile(bFile)
        project.diagnostics()
        assert(counting.touches > afterFirst)
    }

    // ---- lifecycle -----------------------------------------------------------

    /** A closed project refuses to reload, like every other member. */
    @Test
    fun `a closed project refuses to reload a file`() {
        val project = Project.open("/proj", store())
        project.diagnostics()
        project.close()
        assertFailsWith<IllegalStateException> { project.reloadFile(bFile) }
    }
}
