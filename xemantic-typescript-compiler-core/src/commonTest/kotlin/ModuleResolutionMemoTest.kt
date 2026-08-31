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
 * (INC.65): module resolution is memoized per BUILD, keyed by the importer's
 * DIRECTORY and the specifier.
 *
 * The crawl resolves every specifier of every file, sequentially, probing the
 * filesystem per candidate extension. Split out for the first time this round, that
 * is **20.6-28.6 ms of a 44-60 ms crawl wall** on 2,401 application-shaped files —
 * about half the row — against **13.9-20.0** with the memo. The population it serves
 * is real: the same fixture makes **4,701 resolutions over 2,351 distinct
 * `(directory, specifier)` pairs**, a duplication factor of exactly 2.0, and a
 * codebase with shared barrels has more.
 *
 * ## Why the directory is exact rather than approximate
 *
 * [ModuleResolver.resolve] reads `importerPath` exactly once, to take its `dirname`,
 * and never again: every branch below that line is a function of `importerDir` and
 * the specifier alone. So the key is not a heuristic, and the pin that matters is the
 * one below asserting the directory is IN it — a memo keyed by the specifier alone
 * passes every count assertion here and silently resolves `./sibling` in one
 * directory to another directory's file, which is a wrong PROGRAM and, per (CFG.1),
 * something this repo has no diagnostic channel to notice.
 *
 * ## Why it needs no invalidation
 *
 * A [ModuleResolver] is constructed once per `ProjectCompiler.build`, so the memo's
 * lifetime is one build — and the crawl already documents that it assumes a `Vfs`
 * static for its duration. It is deliberately NOT process-global: a cross-build cache
 * cannot see an ADDED file, which is (INC.48)'s hazard.
 */
class ModuleResolutionMemoTest {

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/p/a/dep.ts" to "export const x = 1;\n",
            "/p/a/one.ts" to "import { x } from \"./dep\";\n",
            "/p/a/two.ts" to "import { x } from \"./dep\";\n",
            "/p/b/dep.ts" to "export const y = 2;\n",
            "/p/b/three.ts" to "import { y } from \"./dep\";\n",
        )
    )

    @Test
    fun `two files in one directory asking the same specifier resolve it once`() {
        val r = ModuleResolver(vfs())
        val first = r.resolve("./dep", "/p/a/one.ts")
        val second = r.resolve("./dep", "/p/a/two.ts")
        assert(first == "/p/a/dep.ts")
        assert(second == first)
        assert(r.resolveCalls == 2)
        assert(r.computedResolutions == 1)
    }

    /**
     * THE PIN THE WHOLE DESIGN RESTS ON. The same specifier TEXT from a different
     * directory is a different question and must be computed again — a memo keyed by
     * the specifier alone answers `/p/a/dep.ts` here, which is a wrong program with no
     * diagnostic anywhere.
     */
    @Test
    fun `the same specifier from another directory is a different question`() {
        val r = ModuleResolver(vfs())
        assert(r.resolve("./dep", "/p/a/one.ts") == "/p/a/dep.ts")
        assert(r.resolve("./dep", "/p/b/three.ts") == "/p/b/dep.ts")
        assert(r.computedResolutions == 2)
    }

    /**
     * An UNRESOLVED specifier is a real answer and must be memoized too — otherwise
     * the filesystem is re-probed for every one of them, which is the population a
     * project mid-edit has most of.
     */
    @Test
    fun `an unresolved specifier is memoized rather than re-probed`() {
        val r = ModuleResolver(vfs())
        assert(r.resolve("./nope", "/p/a/one.ts") == null)
        assert(r.resolve("./nope", "/p/a/two.ts") == null)
        assert(r.resolveCalls == 2)
        assert(r.computedResolutions == 1)
    }

    /**
     * (INC.82) The wrapper and the directory-taking entry point are ONE function.
     *
     * [ModuleResolver.resolve] now reads its `importerPath` for nothing but the
     * `dirname`, which is what licenses the crawl to hoist that call out of its
     * per-specifier loop. This pins the equality across the shapes the crawl actually
     * produces — a nested importer, a root-level one, a bare specifier, a `..`-crossing
     * one and an unresolvable one — because an implementation that consulted the path
     * for anything else would agree on some of them and not all.
     */
    @Test
    fun `resolveFrom answers exactly what resolve answers for the same directory`() {
        val specs = listOf("./dep", "../b/dep", "./nope", "dep", "/p/a/dep.ts", "./dep.js")
        val importers = listOf("/p/a/one.ts", "/p/b/three.ts")
        for (importer in importers) {
            val dir = PathUtil.dirname(importer)
            for (spec in specs) {
                val viaPath = ModuleResolver(vfs()).resolve(spec, importer)
                val viaDir = ModuleResolver(vfs()).resolveFrom(spec, dir)
                assert(viaDir == viaPath)
            }
        }
    }

    /**
     * …and they share ONE memo, which is the point of the wrapper rather than an
     * incidental property: a caller that hoists the `dirname` must not lose the answers
     * a caller that did not has already paid for.
     */
    @Test
    fun `the two entry points share one memo`() {
        val r = ModuleResolver(vfs())
        assert(r.resolve("./dep", "/p/a/one.ts") == "/p/a/dep.ts")
        assert(r.resolveFrom("./dep", "/p/a") == "/p/a/dep.ts")
        assert(r.resolveCalls == 2)
        assert(r.computedResolutions == 1)
    }

    /**
     * (INC.82) THE SENTINEL MUST NOT ESCAPE. A memoized `null` is stored as a marker so
     * that a served answer costs one probe instead of a `containsKey` + `get` pair; an
     * implementation returning the stored value directly hands its caller a string that
     * is not a path, and every downstream reader would treat the import as RESOLVED —
     * to a file that does not exist. Asked three times, so the answer is served from the
     * memo twice and through both entry points.
     */
    @Test
    fun `a memoized unresolved answer is null and never the marker`() {
        val r = ModuleResolver(vfs())
        assert(r.resolve("./nope", "/p/a/one.ts") == null)
        assert(r.resolveFrom("./nope", "/p/a") == null)
        assert(r.resolve("./nope", "/p/a/two.ts") == null)
        assert(r.computedResolutions == 1)
    }

    /**
     * CONTROL: the memo is per INSTANCE, and an instance is per build. A second
     * resolver recomputes — which is what makes an added file visible to the next
     * build, and is why this is not a process-global cache.
     */
    @Test
    fun `a second resolver does not inherit the first one's answers`() {
        val backing = vfs()
        val first = ModuleResolver(backing)
        first.resolve("./dep", "/p/a/one.ts")
        val second = ModuleResolver(backing)
        assert(second.computedResolutions == 0)
        assert(second.resolve("./dep", "/p/a/one.ts") == "/p/a/dep.ts")
        assert(second.computedResolutions == 1)
    }
}
