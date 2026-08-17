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

import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.typescript.compiler.Vfs
import com.xemantic.kotlin.test.assert
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The behaviour contract of [Project]: it compiles lazily, caches until edited,
 * and lets an in-memory overlay decide what the compiler sees.
 *
 * Every test here asserts a SHARP signal — a specific diagnostic code appearing or
 * disappearing, or a count of backing-store touches — because the failure mode
 * this class invites is silent vacuity: an overlay that is never consulted returns
 * the on-disk truth, which for most assertions looks like a pass. The two
 * error-direction tests are deliberately mirrored (an edit that FIXES and an edit
 * that BREAKS), so no always-empty and no always-stale result can satisfy both, and
 * the module-resolution test carries its own negative control.
 */
class ProjectTest {

    /**
     * The tsconfig every fixture here shares.
     *
     * `module` is NOT decoration: the checker only reports TS2307 for a relative
     * specifier when the module kind is an ES one (or CommonJS) with no explicit
     * `moduleResolution` — the richer node16/nodenext/bundler rules are
     * deliberately not modelled — so with `module` unset every "the import is
     * unresolved" assertion below would be vacuous.
     */
    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    /** A project whose only source is `src/a.ts`, with [aSource] as its content. */
    private fun projectWith(aSource: String): InMemoryVfs = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            "/proj/src/a.ts" to aSource,
        ),
    )

    private fun open(vfs: Vfs): Project = Project.open("/proj", vfs)

    @Test
    fun `a staged project reports its type error`() {
        val project = open(projectWith("export const x: number = \"text\";\n"))
        val codes = project.diagnostics().map { it.code }
        assert(2322 in codes)
    }

    @Test
    fun `a clean project reports no errors`() {
        val project = open(projectWith("export const x: number = 1;\n"))
        val errors = project.diagnostics().filter { it.category == DiagnosticCategory.Error }
        assert(errors.isEmpty())
    }

    @Test
    fun `files lists the project sources`() {
        val project = open(projectWith("export const x: number = 1;\n"))
        assert(project.files.any { it == "/proj/src/a.ts" })
    }

    @Test
    fun `configPath resolves tsconfig json inside a project directory`() {
        val project = open(projectWith("export const x: number = 1;\n"))
        assert(project.configPath == "/proj/tsconfig.json")
    }

    @Test
    fun `open refuses a project path that does not exist`() {
        // A path that does not exist used to make the crawl walk from the root —
        // inside a long-lived host that is a wedged process, not a slow query.
        assertFailsWith<IllegalArgumentException> {
            Project.open("/nowhere", InMemoryVfs(mapOf("/proj/tsconfig.json" to config)))
        }
    }

    @Test
    fun `open does not compile`() {
        // The FIRST query compiles, so that a host can stage its unsaved buffers
        // before paying for a build of the on-disk truth it is about to discard.
        // Nothing but a read/list counter can see this: `open` returning cheaply is
        // not observable from its result.
        val counting = CountingVfs(projectWith("export const x: number = \"text\";\n"))
        val project = open(counting)
        assert(counting.touches == 0)
        // ... and the same instance does compile once asked.
        assert(2322 in project.diagnostics().map { it.code })
        assert(counting.touches > 0)
    }

    // --- per-file filtering ----------------------------------------------------

    private fun twoErroringFiles(): InMemoryVfs = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            "/proj/src/a.ts" to "export const a: number = \"text\";\n",
            "/proj/src/b.ts" to "export const b: string = 1;\n",
        ),
    )

    @Test
    fun `diagnostics are filtered per file`() {
        val project = open(twoErroringFiles())
        val whole = project.diagnostics().filter { it.code == 2322 }
        assert(whole.size == 2)
        val a = project.diagnostics("/proj/src/a.ts")
        assert(a.size == 1)
        assert(a.all { it.fileName == "/proj/src/a.ts" })
        val b = project.diagnostics("/proj/src/b.ts")
        assert(b.size == 1)
        assert(b.all { it.fileName == "/proj/src/b.ts" })
    }

    @Test
    fun `diagnostics of an unknown file are empty`() {
        val project = open(twoErroringFiles())
        assert(project.diagnostics("/proj/src/nope.ts").isEmpty())
    }

    // --- in-memory edits -------------------------------------------------------

    @Test
    fun `updateFile fixes an error`() {
        // The backing store still holds the broken text, so this can only pass if
        // the overlay is what the compiler read.
        val project = open(projectWith("export const x: number = \"text\";\n"))
        assert(2322 in project.diagnostics().map { it.code })
        project.updateFile("/proj/src/a.ts", "export const x: number = 1;\n")
        assert(2322 !in project.diagnostics().map { it.code })
    }

    @Test
    fun `updateFile introduces an error`() {
        // The mirror of the test above: an always-empty diagnostics result satisfies
        // one of the two and never both.
        val project = open(projectWith("export const x: number = 1;\n"))
        assert(2322 !in project.diagnostics().map { it.code })
        project.updateFile("/proj/src/a.ts", "export const x: number = \"text\";\n")
        assert(2322 in project.diagnostics().map { it.code })
    }

    /**
     * A project whose `src/a.ts` imports `./dep`, which is NOT in the backing store.
     *
     * The unrelated `src/other.ts` is load-bearing rather than decoration: the
     * checker suppresses TS2307 entirely for a single-file program
     * (`checkUnresolvedModules`' `isMultiFile` gate — and the real libs are bound
     * through their own path, so they do not count), which would make every
     * assertion about a missing module vacuous.
     */
    private fun importingProject(): InMemoryVfs = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            "/proj/src/a.ts" to "import { d } from \"./dep\";\nexport const x: number = d;\n",
            "/proj/src/other.ts" to "export const o: number = 1;\n",
        ),
    )

    @Test
    fun `updateFile adds a module that an existing file imports`() {
        // Pins the overlay's `exists` rule: module resolution probes candidate paths
        // with `exists` before ever reading one.
        val project = open(importingProject())
        assert(2307 in project.diagnostics().map { it.code })
        project.updateFile("/proj/src/dep.ts", "export const d: number = 1;\n")
        val codes = project.diagnostics().map { it.code }
        assert(2307 !in codes)
        assert(2322 !in codes)
        assert(project.files.any { it == "/proj/src/dep.ts" })
    }

    @Test
    fun `negative control - the import stays unresolved while the module is absent`() {
        // Without this, the test above could pass on a compiler that never reports
        // TS2307 at all — i.e. it would discriminate nothing.
        val project = open(importingProject())
        assert(2307 in project.diagnostics().map { it.code })
        // Re-queried without an edit: still unresolved, still discriminating.
        assert(2307 in project.diagnostics().map { it.code })
    }

    @Test
    fun `updateFile adds a file in a directory that exists nowhere on disk`() {
        // Pins the overlay's `isDirectory` AND `list` rules together: nothing imports
        // this file, so the only way its error can appear is the glob crawl, which
        // asks `isDirectory` before descending and discovers files through `list`
        // alone. `/proj/src/lib` does not exist in the backing store at all.
        val project = open(projectWith("export const x: number = 1;\n"))
        assert(2322 !in project.diagnostics().map { it.code })
        project.updateFile("/proj/src/lib/orphan.ts", "export const y: number = \"text\";\n")
        val diagnostics = project.diagnostics()
        assert(2322 in diagnostics.map { it.code })
        assert(diagnostics.any { it.code == 2322 && it.fileName == "/proj/src/lib/orphan.ts" })
        assert(project.files.any { it == "/proj/src/lib/orphan.ts" })
    }

    @Test
    fun `deleteFile of an imported file makes the import unresolved`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to config,
                "/proj/src/a.ts" to "import { d } from \"./dep\";\nexport const x: number = d;\n",
                "/proj/src/dep.ts" to "export const d: number = 1;\n",
                // Keeps the program multi-file once `dep.ts` is deleted — see
                // `importingProject`.
                "/proj/src/other.ts" to "export const o: number = 1;\n",
            ),
        )
        val project = open(vfs)
        assert(2307 !in project.diagnostics().map { it.code })
        project.deleteFile("/proj/src/dep.ts")
        assert(2307 in project.diagnostics().map { it.code })
        assert(project.files.none { it == "/proj/src/dep.ts" })
    }

    @Test
    fun `an edit never reaches the backing store`() {
        val backing = projectWith("export const x: number = \"text\";\n")
        val project = open(backing)
        project.updateFile("/proj/src/a.ts", "export const x: number = 1;\n")
        project.updateFile("/proj/src/new.ts", "export const n: number = 1;\n")
        project.deleteFile("/proj/src/a.ts")
        assert(project.diagnostics().none { it.category == DiagnosticCategory.Error })
        // The whole point of the class: the user's tree is untouched.
        assert(backing.readText("/proj/src/a.ts") == "export const x: number = \"text\";\n")
        assert(backing.readText("/proj/src/new.ts") == null)
    }

    // --- caching ---------------------------------------------------------------

    @Test
    fun `a second query does not touch the backing store`() {
        val counting = CountingVfs(projectWith("export const x: number = \"text\";\n"))
        val project = open(counting)
        project.diagnostics()
        val afterFirst = counting.touches
        assert(afterFirst > 0)
        val second = project.diagnostics()
        assert(counting.touches == afterFirst)
        // `files` reads the same cached build rather than starting its own.
        assert(project.files.isNotEmpty())
        assert(counting.touches == afterFirst)
        assert(second.map { it.code } == project.diagnostics().map { it.code })
    }

    @Test
    fun `an edit between two queries forces a rebuild`() {
        val counting = CountingVfs(projectWith("export const x: number = \"text\";\n"))
        val project = open(counting)
        project.diagnostics()
        val afterFirst = counting.touches
        project.updateFile("/proj/src/a.ts", "export const x: number = 1;\n")
        project.diagnostics()
        assert(counting.touches > afterFirst)
    }

    // --- lifecycle -------------------------------------------------------------

    @Test
    fun `close is idempotent`() {
        val project = open(projectWith("export const x: number = 1;\n"))
        project.close()
        project.close()
    }

    @Test
    fun `a query after close throws`() {
        val project = open(projectWith("export const x: number = 1;\n"))
        project.diagnostics()
        project.close()
        assertFailsWith<IllegalStateException> { project.diagnostics() }
        assertFailsWith<IllegalStateException> { project.diagnostics("/proj/src/a.ts") }
        assertFailsWith<IllegalStateException> { project.files }
        assertFailsWith<IllegalStateException> {
            project.updateFile("/proj/src/a.ts", "export const x: number = 2;\n")
        }
        assertFailsWith<IllegalStateException> { project.deleteFile("/proj/src/a.ts") }
    }
}
