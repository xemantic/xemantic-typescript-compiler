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
 * (INC.78) The gate on [GlobMatcher]'s fast paths — a DIFFERENTIAL against its own
 * [GlobMatcher.regex], which is the definition of what a pattern accepts.
 *
 * It has to be a differential and it has to be here rather than in the corpus,
 * because (CFG.1) says a wrong root-file set is SILENT in every other instrument
 * this repo has: the corpus harness materialises no directory at all, so it has no
 * glob to get wrong, and all eight dashboard profiles scope `include` to a subtree
 * that no wildcard shape stresses. A dropped root file is a missing FILE, not a
 * missing diagnostic, and nothing prints it.
 *
 * Globs are spelled with spaces around their separators in this KDoc — a literal
 * slash-star sequence inside a block comment opens a nested comment (CLAUDE.md
 * B151), which compiles and silently swallows whatever follows.
 */
class GlobMatcherTest {

    private val supportedExt = listOf(".ts", ".tsx", ".mts", ".cts")

    /**
     * Every pattern shape the derivation distinguishes, including the ones it must
     * REFUSE to shortcut: a bare `**`, a `*` inside a segment, a `?`, two `**` runs
     * separated by literal text, and a wildcard that does not start a segment.
     */
    private val patterns = listOf(
        "/p/src",
        "/p/src/**/*",
        "/p/src/**/*.ts",
        "/p/src/**/*.d.ts",
        "/p/**/*",
        "/p/**/*.spec.ts",
        "/p/dist",
        "/p/src/**",
        "/p/src/*",
        "/p/src/*.ts",
        "/p/src/**/x/**/*.ts",
        "/p/src/f?o/**/*",
        "/p/src/a*b/**/*",
        "/p/src/one.ts",
        "/p/a+b/**/*",
    )

    /**
     * Paths chosen to sit on the boundaries the shortcuts introduce: a sibling
     * directory whose name EXTENDS the literal head (`srcx` against `src`), depth 0
     * and depth 3 under the head, a leaf that carries the tail in its middle rather
     * than at its end, a dotfile leaf, an unsupported extension, and the degenerate
     * leaf that is nothing but the tail.
     */
    private val paths = listOf(
        "/p/src/a.ts",
        "/p/src/a.tsx",
        "/p/src/a.js",
        "/p/src/a.d.ts",
        "/p/src/.ts",
        "/p/src/one.ts",
        "/p/src/b/c/d.ts",
        "/p/src/b/c/d.spec.ts",
        "/p/src/x/y.ts",
        "/p/src/b/x/y.ts",
        "/p/src/a.ts.bak",
        "/p/srcx/a.ts",
        "/p/src",
        "/p/src/",
        "/p/dist/a.ts",
        "/p/a.ts",
        "/p/src/foo/a.ts",
        "/p/src/fo/a.ts",
        "/p/src/f/o/a.ts",
        "/p/src/ab/a.ts",
        "/p/src/axxb/a.ts",
        "/p/a+b/a.ts",
        "/p/aXb/a.ts",
        "/px/src/a.ts",
    )

    @Test
    fun `every fast answer is the answer the regex gives`() {
        val divergences = mutableListOf<String>()
        for (pattern in patterns) {
            val matcher = GlobMatcher.compile(pattern, supportedExt)
            for (path in paths) {
                if (matcher.matches(path) != matcher.regex.matches(path)) {
                    divergences.add(pattern + " vs " + path)
                }
            }
        }
        assert(divergences.isEmpty())
    }

    /**
     * The differential above is worth nothing if the fast path is never taken — an
     * always-null [GlobMatcher.fastSuffixes] passes it by construction. This is the
     * REGIME pin: the shapes a real tsconfig contains take the shortcut.
     */
    @Test
    fun `the tsconfig shapes take the exact fast path`() {
        val fast = listOf("/p/src", "/p/src/**/*", "/p/src/**/*.ts", "/p/**/*.spec.ts", "/p/dist")
        for (pattern in fast) {
            val suffixes = GlobMatcher.compile(pattern, supportedExt).fastSuffixes
            assert(suffixes != null)
        }
    }

    /**
     * And the mirror: a shape whose middle is CONSTRAINED must keep the regex, or the
     * head-and-tail test would accept paths the pattern rejects. Each of these has a
     * witness in the differential above.
     */
    @Test
    fun `a constrained middle refuses the fast path`() {
        val slow = listOf(
            "/p/src/**",
            "/p/src/*",
            "/p/src/*.ts",
            "/p/src/**/x/**/*.ts",
            "/p/src/f?o/**/*",
            "/p/src/a*b/**/*",
            "/p/src/one.ts",
        )
        for (pattern in slow) {
            val suffixes = GlobMatcher.compile(pattern, supportedExt).fastSuffixes
            assert(suffixes == null)
        }
    }

    /**
     * The literal head is a NECESSARY condition and never a sufficient one, so it may
     * only refuse — this is the case that separates the two: a path under a directory
     * whose name merely extends the head.
     */
    @Test
    fun `a sibling directory extending the head is refused`() {
        val matcher = GlobMatcher.compile("/p/src/**/*", supportedExt)
        assert(!matcher.matches("/p/srcx/a.ts"))
        assert(matcher.matches("/p/src/a.ts"))
    }

    /**
     * The head and the tail must be DISJOINT: without the length guard a path shorter
     * than the two together satisfies both by overlapping, which the regex — where the
     * head and the tail are consecutive — never accepts.
     */
    @Test
    fun `the head and the tail may not overlap`() {
        val matcher = GlobMatcher.compile("/p/src/**/*.ts", supportedExt)
        // "/p/src/" is the head and ".ts" the tail; the shortest accepted path spells
        // both, and one character less spells only the head.
        assert(matcher.matches("/p/src/.ts"))
        assert(!matcher.matches("/p/src/"))
        assert(matcher.matches("/p/src/.ts") == matcher.regex.matches("/p/src/.ts"))
        assert(matcher.matches("/p/src/") == matcher.regex.matches("/p/src/"))
    }

    /**
     * An extension-less pattern accepts any supported extension, so the accepted tail
     * set is per-extension and an unsupported one is refused — the property the walk's
     * own extension filter must not be silently standing in for.
     */
    @Test
    fun `an extensionless pattern accepts exactly the supported extensions`() {
        val matcher = GlobMatcher.compile("/p/src", supportedExt)
        assert(matcher.matches("/p/src/a.ts"))
        assert(matcher.matches("/p/src/a.cts"))
        assert(!matcher.matches("/p/src/a.js"))
        assert(!matcher.matches("/p/src/a.ts.bak"))
    }
}
