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
import com.xemantic.typescript.compiler.ProjectStateSnapshot
import com.xemantic.typescript.compiler.Vfs
import kotlin.test.Test

/**
 * (INC.48) The state that survives the PROCESS — `saveState` / `restoreState`.
 *
 * ## What is under test
 *
 * (INC.46) made project-wide diagnostics incremental within one process; an IDE
 * restart threw all of it away and the first query paid a whole-program build. A
 * snapshot lets the next process start from the previous one's answer, which is only
 * sound if every part of the claim it carries is checked.
 *
 * Two families, and the pins are a PAIR by construction, exactly as
 * `ProjectIncrementalDiagnosticsTest`'s are:
 *
 *  - **VALUE.** Whatever the restore does, the rows must equal what a project opened
 *    FRESH on the same text reports — graded against a second project, so nothing
 *    about the first one's state can flatter it. An implementation that restored
 *    nothing passes every value pin, which is why they are paired with:
 *  - **COST.** `incrementalAnswers` (the answer came through the gate, not through a
 *    rebuild) and reads counted at the backing [Vfs] (a second query builds nothing).
 *    Without these every pin here passes against the pre-(INC.48) behaviour, which is
 *    the whole thing under test.
 *
 * Not timed: a timed pin over a compile is a coin flip (CLAUDE.md, round 868).
 *
 * ## The seam, and why it is not cheating
 *
 * `ProjectStateSnapshot.allowUnstableBuildIdForTesting` is installed and restored per
 * test. A development tree's build id ends in `.dirty` and is correctly refused, so
 * without the seam every pin here would be vacuous locally and exercise the real path
 * only in CI — a pin that passes for opposite reasons in two environments. The seam
 * changes nothing else: the id EQUALITY check still runs, and
 * `a snapshot from a different compiler build is refused` is the pin that says so.
 */
class ProjectStateSnapshotTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val aFile = "/proj/src/a.ts"
    private val bFile = "/proj/src/b.ts"

    /** `b.ts` exports what `a.ts` depends on, so the two are genuinely coupled and a
     *  restored answer that dropped cross-file rows would show. */
    private val bText = """
        export interface Shape {
            readonly kind: string;
        }
        export function describe(s: Shape): string {
            return s.kind;
        }
        export const wrong: number = "text";
    """.trimIndent() + "\n"

    private val aText = """
        import { Shape, describe } from "./b";
        export const shape: Shape = { kind: 1 };
        export const label: string = describe(shape);
    """.trimIndent() + "\n"

    private fun files(a: String = aText, b: String = bText) = mapOf(
        "/proj/tsconfig.json" to config,
        aFile to a,
        bFile to b,
    )

    private fun rows(diagnostics: List<Diagnostic>): List<String> =
        diagnostics.map { "${it.fileName}|${it.start}|${it.code}|${it.message}" }.sorted()

    /** What a project opened FRESH on [contents] reports — the reference answer. */
    private fun freshRows(contents: Map<String, String>): List<String> {
        val project = Project.open("/proj", InMemoryVfs(contents))
        try {
            return rows(project.diagnostics())
        } finally {
            project.close()
        }
    }

    /** A snapshot of a project built on [contents] — the "previous process". */
    private fun snapshotOf(contents: Map<String, String> = files()): String {
        val project = Project.open("/proj", InMemoryVfs(contents))
        try {
            project.diagnostics()
            return project.saveState() ?: error("no state to save")
        } finally {
            project.close()
        }
    }

    /** Installs the seam for [block] and restores it, whatever happens. */
    private fun <T> withReuse(block: () -> T): T {
        val was = ProjectStateSnapshot.allowUnstableBuildIdForTesting
        ProjectStateSnapshot.allowUnstableBuildIdForTesting = true
        try {
            return block()
        } finally {
            ProjectStateSnapshot.allowUnstableBuildIdForTesting = was
        }
    }

    // ---- VALUE --------------------------------------------------------------------

    /**
     * THE CASE THE WHOLE THING EXISTS FOR: a process reopens a project nobody touched
     * and must answer what it answered before — not because the rows were trusted
     * blindly, but because the gate re-crawled and found the same program.
     */
    @Test
    fun `a restored state with no edits answers exactly what a fresh build answers`() =
        withReuse {
            val state = snapshotOf()
            val project = Project.open("/proj", InMemoryVfs(files()))
            try {
                assert(project.restoreState(state))
                assert(rows(project.diagnostics()) == freshRows(files()))
            } finally {
                project.close()
            }
        }

    /**
     * The restart-with-an-edit case: the file changed ON DISK while the process was
     * gone, so the dirty set is computed from content hashes rather than from any
     * `updateFile` this process saw.
     */
    @Test
    fun `a state restored over a body edit answers exactly what a fresh build answers`() =
        withReuse {
            val state = snapshotOf()
            val editedB = bText.replace("return s.kind;", "const k = s.kind;\n    return k;")
            val project = Project.open("/proj", InMemoryVfs(files(b = editedB)))
            try {
                assert(project.restoreState(state))
                assert(rows(project.diagnostics()) == freshRows(files(b = editedB)))
            } finally {
                project.close()
            }
        }

    /**
     * The other direction, and the one a serving implementation cannot fake: the edit
     * made while the process was down MOVES an exported signature, so the answer must
     * be the whole program's — including `a.ts`'s new row, in a file the edit did not
     * touch.
     */
    @Test
    fun `a state restored over a signature edit answers exactly what a fresh build answers`() =
        withReuse {
            val editedB = bText
                .replace(
                    "export function describe(s: Shape): string {",
                    "export function describe(s: Shape): number {",
                )
                .replace("return s.kind;", "return s.kind.length;")
            val state = snapshotOf()
            val project = Project.open("/proj", InMemoryVfs(files(b = editedB)))
            try {
                assert(project.restoreState(state))
                assert(rows(project.diagnostics()) == freshRows(files(b = editedB)))
            } finally {
                project.close()
            }
        }

    /**
     * THE STALENESS CASE NO CONTENT HASH CAN SEE: a file ADDED while the process was
     * down is in no stored hash and in no stored file list, and it changes what every
     * importer resolves. The only thing that catches it is a build that re-crawls —
     * which is why a restored state is not trusted until one has.
     */
    @Test
    fun `a file added while the process was down is not missed`() = withReuse {
        val state = snapshotOf()
        val cFile = "/proj/src/c.ts"
        val cText = "export const alsoWrong: number = \"text\";\n"
        val withC = files() + (cFile to cText)
        val project = Project.open("/proj", InMemoryVfs(withC))
        try {
            project.restoreState(state)
            val answer = rows(project.diagnostics())
            assert(answer == freshRows(withC))
            assert(answer.any { it.contains("/proj/src/c.ts") })
        } finally {
            project.close()
        }
    }

    // ---- COST ---------------------------------------------------------------------

    /**
     * The saving, as a COUNTER: the first query after a restore is answered through the
     * (INC.46) gate — one narrowed build — and not by the whole-program rebuild the
     * same query costs without a snapshot. The control below it is what makes this
     * non-vacuous.
     */
    @Test
    fun `a restored state answers its first query through the gate`() = withReuse {
        val state = snapshotOf()
        val project = Project.open("/proj", InMemoryVfs(files()))
        try {
            assert(project.restoreState(state))
            project.diagnostics()
            assert(project.incrementalAnswers == 1)
        } finally {
            project.close()
        }
    }

    /** THE CONTROL: with no state restored, the same query is a whole-program build. */
    @Test
    fun `without a restored state the same first query is a full build`() = withReuse {
        val project = Project.open("/proj", InMemoryVfs(files()))
        try {
            project.diagnostics()
            assert(project.incrementalAnswers == 0)
        } finally {
            project.close()
        }
    }

    /**
     * And the answer is RETAINED: an editor asks for project diagnostics constantly, so
     * a second query with no intervening edit must build nothing at all. Counted at the
     * backing [Vfs] by reads of a file no test here edits.
     */
    @Test
    fun `a second query after a restore builds nothing`() = withReuse {
        val state = snapshotOf()
        val counting = CountingVfs(InMemoryVfs(files()))
        val project = Project.open("/proj", counting)
        try {
            assert(project.restoreState(state))
            project.diagnostics()
            val after = counting.readsOf("/proj/tsconfig.json")
            project.diagnostics()
            assert(counting.readsOf("/proj/tsconfig.json") == after)
        } finally {
            project.close()
        }
    }

    // ---- REFUSAL ------------------------------------------------------------------

    /**
     * A different compiler may report different rows for the same text, so its state
     * may not be adopted. The seam deliberately does NOT weaken this: it only makes an
     * unstable id reusable in principle, and the EQUALITY check still runs.
     */
    @Test
    fun `a snapshot from a different compiler build is refused`() = withReuse {
        val state = snapshotOf()
        val foreign = state.replace(
            "\"buildId\":\"${ProjectStateSnapshot.compilerBuildId}\"",
            "\"buildId\":\"0000000000000000000000000000000000000000\"",
        )
        assert(foreign != state)
        val project = Project.open("/proj", InMemoryVfs(files()))
        try {
            assert(!project.restoreState(foreign))
        } finally {
            project.close()
        }
    }

    /**
     * A changed config does not date one file's rows — it changes what the program IS
     * and which options apply, so every stored row is suspect and narrowing cannot
     * repair it. This is why the `.json` inputs are hashed and not just the sources.
     */
    @Test
    fun `a changed tsconfig refuses the restore`() = withReuse {
        val state = snapshotOf()
        val otherConfig = config.replace("\"strict\": true", "\"strict\": false")
        val project = Project.open(
            "/proj",
            InMemoryVfs(files() + ("/proj/tsconfig.json" to otherConfig)),
        )
        try {
            assert(!project.restoreState(state))
        } finally {
            project.close()
        }
    }

    /** A program file that no longer exists changes what every importer resolves. */
    @Test
    fun `a removed program file refuses the restore`() = withReuse {
        val state = snapshotOf()
        val project = Project.open("/proj", InMemoryVfs(files() - bFile))
        try {
            assert(!project.restoreState(state))
        } finally {
            project.close()
        }
    }

    /**
     * A snapshot is a claim about a STARTING point. Adopting one over a project that
     * has already built would replace answers this process computed with answers it
     * did not.
     */
    @Test
    fun `a project that has already built refuses to restore`() = withReuse {
        val state = snapshotOf()
        val project = Project.open("/proj", InMemoryVfs(files()))
        try {
            project.diagnostics()
            assert(!project.restoreState(state))
        } finally {
            project.close()
        }
    }

    /** There is nothing to save before a build has established a surface. */
    @Test
    fun `saveState answers null before any query`() = withReuse {
        val project = Project.open("/proj", InMemoryVfs(files()))
        try {
            assert(project.saveState() == null)
        } finally {
            project.close()
        }
    }

    /**
     * THE SHIPPED DEFAULT IS THE STRICT ONE. Every pin above installs the seam, so
     * without this one the default is pinned by nothing and an ablation restoring the
     * old policy would read 0 RED ((INC.16)'s law).
     */
    @Test
    fun `the unstable-build-id seam is off by default`() {
        assert(!ProjectStateSnapshot.allowUnstableBuildIdForTesting)
    }
}
