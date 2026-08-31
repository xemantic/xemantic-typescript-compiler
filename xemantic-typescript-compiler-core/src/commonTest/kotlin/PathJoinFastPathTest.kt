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
 * (INC.80) `PathUtil.join`'s segment-arithmetic fast path — a DIFFERENTIAL against the
 * general body, which stays the definition of what a join answers.
 *
 * ## The measurement
 *
 * On the 2,401-file `many-small-2400-dom` fixture the crawl's specifier resolution
 * spends **3.4-4.1 ms over 4,701 calls (731-880 ns each)** joining an importer's
 * directory to a specifier. A specifier carrying a `..` is exactly the case
 * `PathUtil.isNormalized` must refuse, so (INC.68)'s fast path cannot help it and the
 * general body allocates a `split` list, a `String` per segment, an `ArrayDeque` and a
 * `joinToString` builder. Counting the leading `..`, dropping that many segments off the
 * base and concatenating costs **131-136 ns** — priced as a probe arm, and verified
 * against the general body on all 4,701 of that project's real pairs, BEFORE it was
 * built.
 *
 * ## Why a differential, and why here
 *
 * A wrong join names a DIFFERENT FILE, and (CFG.1) records that this repo has no
 * diagnostic channel that notices a wrong program — the corpus harness materialises no
 * directory at all. So the pin is not a transcribed expectation but the property any
 * correct implementation has: for every base and every part,
 * `join(base, part)` must equal what the general body answers.
 */
class PathJoinFastPathTest {

    /** The general body, written out — the differential's other arm. */
    private fun reference(base: String, part: String): String =
        if (part.startsWith("/")) PathUtil.normalize(part) else PathUtil.normalize("$base/$part")

    /**
     * Bases on the boundaries the fast path introduces: the root, one segment (where
     * popping reaches the root), several segments, and four bases it must REFUSE —
     * relative, backslashed, doubled separator, and one carrying its own `.`/`..`.
     */
    private val bases = listOf(
        "/", "/a", "/a/b", "/a/b/c", "/a/b/c/d",
        "a/b", "/a\\b", "/a//b", "/a/./b", "/a/../b", "", "/a/",
    )

    /**
     * Parts covering the prefix shapes the fast path decodes and every shape it must hand
     * back: a `.`/`..` in the MIDDLE, a bare `.`/`..`, a trailing separator, a doubled
     * separator, a backslash, an absolute part, and the empty string.
     */
    private val parts = listOf(
        "dep", "./dep", "../dep", "../../dep", "../../../dep", "../../../../../dep",
        "a/b", "./a/b", "../a/b", "../../a/b", "a/../b", "./a/../b", "a/./b",
        ".", "..", "./", "../", "a/", "a//b", "a\\b", "", "/abs/x", "./.", "..a", "a..",
    )

    @Test
    fun `every joined answer is the answer the general body gives`() {
        val divergences = mutableListOf<String>()
        for (base in bases) {
            for (part in parts) {
                if (PathUtil.join(base, part) != reference(base, part)) {
                    divergences.add("$base + $part")
                }
            }
        }
        assert(divergences.isEmpty())
    }

    /**
     * The REGIME pin: an always-falling-back implementation passes the differential by
     * construction, so this asserts the shapes a module specifier actually has DO take
     * the arithmetic — and it is a count, because the answers are identical either way.
     */
    @Test
    fun `a module specifier shape is answered by arithmetic`() {
        val before = FrontEnd.pathJoinFast
        assert(PathUtil.join("/p/src/layer03", "../layer02/m2_16") == "/p/src/layer02/m2_16")
        assert(PathUtil.join("/p/src/layer03", "./sibling") == "/p/src/layer03/sibling")
        assert(PathUtil.join("/p/src/layer03", "plain/x") == "/p/src/layer03/plain/x")
        assert(FrontEnd.pathJoinFast - before == 3L)
    }

    /**
     * And its mirror: a part whose middle is constrained, or a base that is not a
     * normalized absolute path, must be handed to the general body. Each of these has a
     * witness in the differential above.
     */
    @Test
    fun `a constrained shape is handed to the general body`() {
        val before = FrontEnd.pathJoinFast
        assert(PathUtil.join("/p/src", "a/../b") == "/p/src/b")
        assert(PathUtil.join("/p/src", "..") == "/p")
        assert(PathUtil.join("/p//src", "x") == "/p/src/x")
        assert(PathUtil.join("rel/dir", "x") == "rel/dir/x")
        assert(FrontEnd.pathJoinFast - before == 0L)
    }

    /**
     * The boundary the fast path deliberately declines: popping the LAST segment of an
     * absolute base reaches the root, where segment arithmetic is one off-by-one away
     * from answering the empty string. The general body answers it, correctly.
     */
    @Test
    fun `a pop that reaches the root is answered by the general body`() {
        assert(PathUtil.join("/a", "../b") == "/b")
        assert(PathUtil.join("/a/b", "../../c") == "/c")
        assert(PathUtil.join("/a", "../b") == reference("/a", "../b"))
        assert(PathUtil.join("/a/b", "../../c") == reference("/a/b", "../../c"))
    }
}
