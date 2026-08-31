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
 * (INC.68) [PathUtil.normalize]'s allocation-free fast path answers EXACTLY what the
 * general path answers.
 *
 * **The two directions are not symmetric, and that is what these pins are shaped
 * around.** A false NEGATIVE (taking the slow path for a path that did not need it)
 * costs the old behaviour and nothing else. A false POSITIVE returns an
 * UN-normalized path to a caller that asked for a normalized one — and per (CFG.1)
 * this repo has no diagnostic channel that would notice a wrong path: it resolves to
 * a different FILE, silently. So the population that matters is the one the fast path
 * ACCEPTS, and every pin below is a value pin over it.
 *
 * The reference is [slowNormalize], a transcription of the general algorithm. It is
 * deliberately a SECOND implementation rather than a call back into `PathUtil`: a
 * differential whose two arms are one function cannot see a fast path at all.
 *
 * **The vacuity guard is the last test.** Every assertion here passes against a
 * binary whose fast path never fires, so without a pin that the fast path IS taken
 * for the shape the compiler actually feeds it, this class would grade a dead
 * mechanism green — round 902's law, and the reason [FrontEnd.pathNormalizeFast]
 * is read rather than assumed.
 */
class PathNormalizeFastPathTest {

    /**
     * The general algorithm, transcribed. Kept structurally identical to
     * `PathUtil.normalize`'s body so a future edit to one is visibly an edit to the
     * other.
     */
    private fun slowNormalize(path: String): String {
        val p = path.replace('\\', '/')
        val isAbs = p.startsWith("/")
        val out = ArrayDeque<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> {
                    if (out.isNotEmpty() && out.last() != "..") out.removeLast()
                    else if (!isAbs) out.addLast("..")
                }
                else -> out.addLast(seg)
            }
        }
        val joined = out.joinToString("/")
        return if (isAbs) "/$joined" else joined.ifEmpty { "." }
    }

    /**
     * Paths chosen to straddle the fast path's every decision: the two single-character
     * fixed points, both empty-segment forms, a `.` that IS the whole path against one
     * that is a segment, `..` in every position, a backslash, and the ordinary
     * already-normalized shapes the glob and the module resolver actually produce.
     */
    private val population = listOf(
        "", "/", ".", "..", "a", "/a",
        "/a/b/c.ts", "a/b/c.ts", "./a", "a/.", "a/./b", "/a/./b",
        "a/../b", "/a/../b", "../a", "a/..", "/..", "/../a", "a/../../b",
        "//a", "/a//b", "a//b", "a/", "/a/", "//", "///",
        "a\\b", "\\", "C:\\x\\y", "C:/x/y",
        "/a/b/..", "/a/b/../..", "/a/b/../../..", "...", "/...", "a/.../b",
        "/home/u/p/src/dir00/file0001.ts", "node_modules/@types/node/index.d.ts",
        "/a/b/.hidden", ".hidden", "/a/b/c..d.ts", "/a/b/..c", "/a/b/c..",
    )

    @Test
    fun `the fast path answers exactly what the general algorithm answers`() {
        for (p in population) {
            val actual = PathUtil.normalize(p)
            val expected = slowNormalize(p)
            val agrees = actual == expected
            assert(agrees)
        }
    }

    @Test
    fun `every path the fast path accepts is a genuine fixed point`() {
        // Whatever the predicate decides, `normalize` must be idempotent — and a
        // fast-path acceptance is precisely the claim `normalize(p) === p`, so an
        // over-eager predicate shows up here as an un-normalized answer.
        for (p in population) {
            val once = PathUtil.normalize(p)
            val twice = PathUtil.normalize(once)
            val stable = once == twice
            assert(stable)
        }
    }

    @Test
    fun `a path that needs work is still rewritten`() {
        // The negative control for the pin above: if the predicate accepted
        // everything, `normalize` would become the identity and every assertion
        // about a fixed point would pass vacuously.
        val rewritten = population.count { PathUtil.normalize(it) != it }
        val many = rewritten >= 20
        assert(many)
    }

    @Test
    fun `join still normalizes through the fast path`() {
        // `join` is the other big consumer (module resolution probes candidates
        // through it), and it reaches `normalize` with a CONCATENATION rather than
        // with an already-normalized string.
        val plain = PathUtil.join("/a/b", "c.ts") == "/a/b/c.ts"
        assert(plain)
        val dotted = PathUtil.join("/a/b", "./c.ts") == "/a/b/c.ts"
        assert(dotted)
        val up = PathUtil.join("/a/b", "../c.ts") == "/a/c.ts"
        assert(up)
        val absolute = PathUtil.join("/a/b", "/x/y.ts") == "/x/y.ts"
        assert(absolute)
    }

    @Test
    fun `the fast path is actually taken for the shapes the compiler feeds it`() {
        // (INC.68) the vacuity guard, asked of the PREDICATE rather than of a
        // process-global counter: this suite is not quiescent, so a pin comparing two
        // reads of a shared counter is a pin on the environment ((INC.67)). The
        // predicate is a pure function and answers the same thing whatever else runs.
        for (p in listOf("/a/b/c.ts", "/home/u/p/src/dir00/file0001.ts", "a/b/c.ts", "/", ".")) {
            val accepted = PathUtil.isNormalized(p)
            assert(accepted)
        }
        for (p in listOf("/a/b/../c.ts", "/a//b", "a/", "./a", "a\\b", "", "//")) {
            val refused = !PathUtil.isNormalized(p)
            assert(refused)
        }
    }

    @Test
    fun `the predicate never accepts a path the general algorithm would rewrite`() {
        // The soundness direction, over the whole population: acceptance CLAIMS
        // `normalize(p) === p`, so an acceptance whose reference answer differs is
        // the silent wrong-file failure this class exists to stop.
        for (p in population) {
            if (!PathUtil.isNormalized(p)) continue
            val isFixedPoint = slowNormalize(p) == p
            assert(isFixedPoint)
        }
    }
}
