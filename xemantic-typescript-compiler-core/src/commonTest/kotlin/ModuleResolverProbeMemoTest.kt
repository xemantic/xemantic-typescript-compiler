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
 * (INC.79) The resolver's filesystem probes are memoized for the build, and SEEDED
 * with what the root-file glob already proved.
 *
 * ## The measurement
 *
 * The crawl's sequential resolve row is ~11 ms of a ~120 ms per-keystroke query on the
 * 2,401-file `many-small-2400-dom` fixture, and decomposed standalone, **4.4 ms of it
 * is 2,350 `exists` syscalls at ~1.9 us each** — every one asking whether a file exists
 * that the ROOT-FILE GLOB listed minutes of CPU earlier in the same build, off the same
 * `Vfs`. Seeded, that build makes **2,351 questions and 0 syscalls**, and the row reads
 * 5.8-6.5 ms against 10.2-12.0 before.
 *
 * ## Why it adds no assumption
 *
 * [ModuleResolver] already memoizes the whole ANSWER per `(importerDir, specifier)`
 * ((INC.65)), which is strictly stronger than memoizing the probes that answer is made
 * of, and it lives for exactly one `ProjectCompiler.build`.
 *
 * ## What the pins are for
 *
 * The seed may only say YES. A file can exist and be excluded from the program, so
 * absence from the seed means nothing and must fall through to the probe — a seed read
 * as an authoritative file list resolves a real import to nothing, and per (CFG.1) this
 * repo has no diagnostic channel that notices a wrong program.
 */
class ModuleResolverProbeMemoTest {

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/p/a/dep.ts" to "export const x = 1;\n",
            "/p/a/hidden.ts" to "export const h = 1;\n",
            "/p/a/one.ts" to "import { x } from \"./dep\";\n",
            "/p/b/dep.ts" to "export const y = 2;\n",
            "/p/a/only.ts" to "export const o = 1;\n",
            "/p/b/three.ts" to "import { y } from \"./dep\";\n",
        )
    )

    @Test
    fun `a seeded answer costs no filesystem probe`() {
        val r = ModuleResolver(vfs())
        r.seedExistingFiles(listOf("/p/a/dep.ts"))
        assert(r.resolve("./dep", "/p/a/one.ts") == "/p/a/dep.ts")
        assert(r.existsQuestions == 1)
        assert(r.existsProbes == 0)
    }

    /**
     * THE PIN THE SEED'S SOUNDNESS RESTS ON: absence from the seed says NOTHING. A file
     * the glob did not select — excluded, or simply not a root — still exists, and a
     * resolver that read the seed as an authoritative file list would answer null for
     * a perfectly good import.
     */
    @Test
    fun `a file the seed does not name still resolves`() {
        val r = ModuleResolver(vfs())
        r.seedExistingFiles(listOf("/p/a/dep.ts"))
        assert(r.resolve("./hidden", "/p/a/one.ts") == "/p/a/hidden.ts")
        assert(r.existsProbes > 0)
    }

    /**
     * And the seed carries no directory of its own: a path seeded under one directory
     * may not answer a same-named specifier asked from another. This is (INC.65)'s
     * wrong-program hazard reached through the new mechanism.
     */
    @Test
    fun `a seeded path does not answer another directory's specifier`() {
        val r = ModuleResolver(vfs())
        r.seedExistingFiles(listOf("/p/a/dep.ts"))
        assert(r.resolve("./dep", "/p/b/three.ts") == "/p/b/dep.ts")
    }

    /**
     * THE WRONG-ANSWER PIN, and it is here because an ablation found the set missing it:
     * a memo keyed by anything less than the WHOLE path — a basename, say — answers
     * "yes, that exists" for a file of the same name in another directory, and the
     * import then names a file that is not there. Every count pin stays green for such a
     * memo, and so does every other value pin in this class, because they all happen to
     * ask about names that exist on both sides.
     */
    @Test
    fun `a file existing only in another directory is not resolved here`() {
        val r = ModuleResolver(vfs())
        r.seedExistingFiles(listOf("/p/a/only.ts"))
        assert(r.resolve("./only", "/p/a/one.ts") == "/p/a/only.ts")
        assert(r.resolve("./only", "/p/b/three.ts") == null)
    }

    /** The memo may not invent existence: a specifier naming nothing still answers null. */
    @Test
    fun `a specifier naming no file is still unresolved`() {
        val r = ModuleResolver(vfs())
        r.seedExistingFiles(listOf("/p/a/dep.ts"))
        assert(r.resolve("./nope", "/p/a/one.ts") == null)
    }

    /**
     * The memo half, independent of the seed: the same absent path is probed ONCE
     * however many resolutions reach it. Two DIFFERENT specifiers are used, because
     * (INC.65)'s answer memo would otherwise serve the second one and this would
     * measure that instead.
     */
    @Test
    fun `a path probed twice reaches the filesystem once`() {
        val r = ModuleResolver(vfs())
        assert(r.resolve("./nope", "/p/a/one.ts") == null)
        val afterFirst = r.existsProbes
        assert(afterFirst > 0)
        assert(r.resolve("./nope/../nope", "/p/a/one.ts") == null)
        assert(r.existsProbes == afterFirst)
    }
}
