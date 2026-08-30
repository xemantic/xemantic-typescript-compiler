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
 */

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.Cancellation
import com.xemantic.typescript.compiler.CancellationSignal
import com.xemantic.typescript.compiler.CompilationCancelledError
import kotlin.test.Test

/**
 * (INC.55) A HOST CAN ABANDON A BUILD, AND ABANDONING ONE LEAVES THE PROJECT
 * EXACTLY AS IT WAS.
 *
 * ## Why this is a capability and not a latency question
 *
 * A build runs on the compiler's own deep-stack thread and [Project] JOINS it, so
 * the caller is blocked for its whole duration and cannot abandon it from outside.
 * On the IntelliJ platform `DaemonCodeAnalyzer` restarts analysis on every write
 * action, so without cooperative cancellation an editor must either block a pooled
 * thread producing an answer it has already thrown away, or not run the analysis in
 * a highlighting pass at all. No amount of narrowing the check changes that.
 *
 * ## What each pin is for, since three of them look similar
 *
 * The VALUE pin is that an armed-but-never-firing signal changes no answer — the
 * control, without which every other pin here could pass against a build that
 * simply stopped working. The THROW pin is that a firing signal actually stops it.
 * The STATE pin is the one that would be expensive to discover in production: a
 * cancelled build must not leave a half-written cache behind, so the answer AFTER a
 * cancellation must equal the answer of a project that was never cancelled at all.
 * The ARMED pin is the install-and-restore invariant — a signal left installed
 * would cancel somebody else's later build (round 874's "a mode left armed
 * reconfigures every later test in the JVM", one layer up).
 *
 * The last pin is on the TYPE, and it is not pedantry: [CompilationCancelledError]
 * is an `Error` precisely so the checker's defensive `catch (Exception)` guards
 * cannot swallow it and let a cancelled build carry on with a missing file. That
 * reasoning lives in a KDoc, and a KDoc is not a gate.
 */
class ProjectCancellationTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    /** Deliberately several files with real work, so a build polls many times. */
    private fun files(): Map<String, String> = mapOf(
        "/proj/tsconfig.json" to config,
        "/proj/src/a.ts" to """
            export interface Shape { kind: string; size: number }
            export const first: Shape = { kind: "a", size: 1 };
        """.trimIndent(),
        "/proj/src/b.ts" to """
            import { Shape } from "./a";
            export const second: Shape = { kind: "b", size: 2 };
            export const wrong: number = "not a number";
        """.trimIndent(),
        "/proj/src/c.ts" to """
            import { Shape } from "./a";
            export function use(s: Shape): string { return s.kind; }
        """.trimIndent(),
    )

    /** Fires the moment it is polled. */
    private object Always : CancellationSignal {
        override fun isCancelled(): Boolean = true
    }

    /** Never fires — the control. */
    private object Never : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }

    /** Never fires, and COUNTS how often it was polled. */
    private class Counting : CancellationSignal {
        var polls: Int = 0
        override fun isCancelled(): Boolean { polls++; return false }
    }

    /** Fires only once it has been polled [after] times — i.e. MID-build. */
    private class FiresAfter(private val after: Int) : CancellationSignal {
        var polls: Int = 0
        override fun isCancelled(): Boolean = ++polls >= after
    }

    /**
     * ONE file of [functions] functions — the size knob for the spine-poll pin.
     *
     * It must vary ONLY size, holding the file COUNT and the shapes fixed. The first
     * version of that pin compared a 3-file fixture against a 1-file one and FAILED:
     * the `pass("…")` poll count is not constant across programs (405 against 418
     * here), so the difference in base swamped the ~12 polls the spine contributed.
     * Same program shape, more nodes, is the only comparison in which the spine is
     * the sole variable.
     */
    private fun sizedFile(functions: Int): Map<String, String> {
        val body = (0 until functions).joinToString("\n") { i ->
            "export function f$i(a: number, b: string): string { " +
                "const t$i = a + 1; return b + t$i; }"
        }
        return mapOf(
            "/proj/tsconfig.json" to config,
            "/proj/src/big.ts" to body,
        )
    }

    /** `close` in a `finally`, without repeating the try at every call site. */
    private inline fun <T> Project.use2(block: (Project) -> T): T =
        try { block(this) } finally { close() }

    private fun rows(project: Project): List<String> =
        project.diagnostics().map { "${it.fileName}:${it.code}" }.sorted()

    @Test
    fun `an armed signal that never fires changes no answer`() {
        val reference = Project.open("/proj", InMemoryVfs(files())).let {
            try { rows(it) } finally { it.close() }
        }
        val project = Project.open("/proj", InMemoryVfs(files()))
        try {
            project.cancellation = Never
            assert(rows(project) == reference)
            // The fixture must actually report something, or "the answers agree"
            // agrees about nothing.
            assert(reference.isNotEmpty())
        } finally {
            project.close()
        }
    }

    @Test
    fun `a firing signal stops the build`() {
        val project = Project.open("/proj", InMemoryVfs(files()))
        try {
            project.cancellation = Always
            var thrown = false
            try {
                project.diagnostics()
            } catch (_: CompilationCancelledError) {
                thrown = true
            }
            assert(thrown)
        } finally {
            project.close()
        }
    }

    @Test
    fun `a cancelled build leaves the project exactly as it was`() {
        val reference = Project.open("/proj", InMemoryVfs(files())).let {
            try { rows(it) } finally { it.close() }
        }
        val project = Project.open("/proj", InMemoryVfs(files()))
        try {
            project.cancellation = Always
            try {
                project.diagnostics()
            } catch (_: CompilationCancelledError) {
                // expected
            }
            // Disarm and ask again: the answer must be the one a project that was
            // never cancelled gives. A half-written cache would show up here.
            project.cancellation = null
            assert(rows(project) == reference)
        } finally {
            project.close()
        }
    }

    @Test
    fun `a cancelled build leaves no signal armed for the next one`() {
        val project = Project.open("/proj", InMemoryVfs(files()))
        try {
            project.cancellation = Always
            try {
                project.diagnostics()
            } catch (_: CompilationCancelledError) {
                // expected
            }
            assert(!Cancellation.armed)
        } finally {
            project.close()
        }
    }

    /**
     * THE PIN THAT SEPARATES THE TWO POLL SITES.
     *
     * `pass("…")` polls a number of times that depends on the PROGRAM but not on its
     * size; the spine polls once per [Cancellation.SPINE_POLL_INTERVAL] nodes. So
     * with the file count and the shapes held fixed, a poll count that grows with the
     * number of nodes can only have come from the spine walk.
     *
     * Without the spine poll a single large buffer's walk — 1.65 s on tsc's own
     * `checker.ts` — would be uncancellable, which is the case an editor most needs
     * to abandon, and EVERY other pin in this class would still pass. Measured, the
     * two arms here read ~417 and ~526.
     */
    @Test
    fun `the spine polls too - the same program with more nodes is polled more often`() {
        val small = Counting()
        val large = Counting()
        Project.open("/proj", InMemoryVfs(sizedFile(400))).use2 { p ->
            p.cancellation = small
            p.diagnostics()
        }
        Project.open("/proj", InMemoryVfs(sizedFile(4000))).use2 { p ->
            p.cancellation = large
            p.diagnostics()
        }
        assert(small.polls > 0)
        // 10x the nodes through an identically-shaped program. The margin is wide
        // enough that it cannot be a difference in which passes ran.
        assert(large.polls > small.polls + 50)
    }

    /**
     * The state pin above cancels at the FIRST poll, i.e. before the build has done
     * anything worth corrupting. This one cancels deep into the build, which is the
     * case a half-written cache would actually show up in.
     */
    @Test
    fun `a build cancelled MID-flight still leaves the project as it was`() {
        val reference = Project.open("/proj", InMemoryVfs(files())).let {
            try { rows(it) } finally { it.close() }
        }
        val project = Project.open("/proj", InMemoryVfs(files()))
        try {
            val late = FiresAfter(300)
            project.cancellation = late
            var thrown = false
            try {
                project.diagnostics()
            } catch (_: CompilationCancelledError) {
                thrown = true
            }
            // It must really have fired LATE, or this repeats the earlier pin.
            assert(thrown)
            assert(late.polls >= 300)
            project.cancellation = null
            assert(rows(project) == reference)
        } finally {
            project.close()
        }
    }

    /**
     * The reason [CompilationCancelledError] is an `Error`: a `catch (Exception)` —
     * of which the checker, the crawl and the `Vfs` carry many — must not be able to
     * swallow it and let the build carry on with a missing file.
     */
    @Test
    fun `a cancellation cannot be caught as an Exception`() {
        val error: Throwable = CompilationCancelledError()
        assert(error !is Exception)
        assert(error is Error)
    }
}
