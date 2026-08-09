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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * (WARM.19) round 871 — the cross-request crawl parse cache.
 *
 * ## Why every one of these pins exists
 *
 * The cache serves a previously-parsed [SourceFile] to a later
 * `ProjectCompiler.build` in the same process — i.e. to the next `--serve`
 * daemon request. **A wrongly-keyed reuse serves an EDITED file's PREVIOUS
 * tree, and that failure is invisible to everything else this repo owns**: the
 * corpus suite never builds twice in one process, the eight dashboard profiles
 * never change between runs, `cost_gate.py` reads counters from a single
 * compile and `--listAll` diffs one output against another taken the same way.
 * So the edit pins below are not extra coverage; they are the only instrument.
 *
 * The pins split into three groups:
 *
 *  - **the key** — a hit needs the same path, the same bytes AND the same
 *    [ParserFlags]; each of the three is pinned by a lookup that must MISS;
 *  - **the effect** — the served tree really is the one the compile uses
 *    (proved with INV.1(e)'s lying-sentinel technique, the only externally
 *    visible proof of tree identity), and the cache really fires through the
 *    real driver;
 *  - **the edit** — a rebuild after a write sees the NEW content, in both
 *    directions (an error introduced, and an error removed), and the map stays
 *    bounded at one entry per path however many times a file is edited.
 */
class CrawlParseCacheTest {

    private val tsconfig = """{ "compilerOptions": { "strict": true } }"""

    @BeforeTest
    fun clean() {
        CrawlParseCache.enabled = true
        CrawlParseCache.reset()
    }

    @AfterTest
    fun restore() {
        CrawlParseCache.enabled = true
        CrawlParseCache.reset()
    }

    private fun flagsFor(fileName: String, content: String): ParserFlags =
        computeParserFlags(fileName, content, CompilerOptions())

    private fun parse(fileName: String, content: String): PreParsedFile {
        val flags = flagsFor(fileName, content)
        val parser = Parser(
            content, fileName, forceJsx = flags.forceJsx, topLevelAwait = flags.topLevelAwait,
            needsJsxFlag = flags.needsJsxFlag, noImplicitAny = flags.noImplicitAny,
        )
        val sourceFile = parser.parse()
        return PreParsedFile(content, flags, sourceFile, parser.getDiagnostics())
    }

    // ---- the key ------------------------------------------------------------

    @Test
    fun `a stored parse is served back for the same path bytes and flags`() {
        val content = "export const a = 1;"
        val stored = parse("/p/a.ts", content)
        CrawlParseCache.store("/p/a.ts", stored)
        val served = CrawlParseCache.lookup("/p/a.ts", content, flagsFor("/p/a.ts", content))
        // Identity, not equality: serving an equal-but-different tree would
        // still be correct and would still cost the parse this exists to avoid.
        val isSameInstance = served?.sourceFile === stored.sourceFile
        assert(isSameInstance)
    }

    @Test
    fun `a byte difference misses - this is what makes an edit safe`() {
        val content = "export const a = 1;"
        CrawlParseCache.store("/p/a.ts", parse("/p/a.ts", content))
        // A DIFFERENT length on purpose, so this pin and the same-length one
        // below separate the two mistakes they are each named for: dropping the
        // content compare fails both, comparing LENGTHS fails only the other.
        val edited = "export const aRenamed = 12345;"
        assert(content.length != edited.length)
        val served = CrawlParseCache.lookup("/p/a.ts", edited, flagsFor("/p/a.ts", edited))
        assert(served == null)
    }

    @Test
    fun `a one-character difference of the same length misses`() {
        // A length-only or size-only comparison — the mtime/stat family of
        // mistakes — passes this input and serves the stale tree.
        val content = "export const a = 1;"
        val edited = "export const a = 9;"
        assert(content.length == edited.length)
        CrawlParseCache.store("/p/a.ts", parse("/p/a.ts", content))
        val served = CrawlParseCache.lookup("/p/a.ts", edited, flagsFor("/p/a.ts", edited))
        assert(served == null)
    }

    @Test
    fun `a parser-flag difference misses`() {
        val content = "export const a = 1;"
        val stored = parse("/p/a.ts", content)
        CrawlParseCache.store("/p/a.ts", stored)
        val other = stored.flags.copy(noImplicitAny = !stored.flags.noImplicitAny)
        assert(CrawlParseCache.lookup("/p/a.ts", content, other) == null)
    }

    @Test
    fun `another path with identical bytes misses`() {
        val content = "export const a = 1;"
        CrawlParseCache.store("/p/a.ts", parse("/p/a.ts", content))
        assert(CrawlParseCache.lookup("/p/b.ts", content, flagsFor("/p/b.ts", content)) == null)
    }

    @Test
    fun `the disabled arm neither serves nor stores`() {
        val content = "export const a = 1;"
        CrawlParseCache.enabled = false
        CrawlParseCache.store("/p/a.ts", parse("/p/a.ts", content))
        assert(CrawlParseCache.size == 0)
        CrawlParseCache.enabled = true
        CrawlParseCache.store("/p/a.ts", parse("/p/a.ts", content))
        CrawlParseCache.enabled = false
        assert(CrawlParseCache.lookup("/p/a.ts", content, flagsFor("/p/a.ts", content)) == null)
    }

    @Test
    fun `an edited file replaces its entry rather than adding one`() {
        for (i in 0..4) {
            val content = "export const a = $i;"
            CrawlParseCache.store("/p/a.ts", parse("/p/a.ts", content))
        }
        assert(CrawlParseCache.size == 1)
    }

    // ---- the effect, through the real driver --------------------------------

    private fun project(main: String, helper: String): InMemoryVfs = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to tsconfig,
            "/proj/main.ts" to main,
            "/proj/helper.ts" to helper,
        )
    )

    @Test
    fun `a second build of the same project serves every crawl parse from the cache`() {
        val vfs = project(
            main = "import { h } from './helper';\nexport const m: number = h;\n",
            helper = "export const h = 1;\n",
        )
        val compiler = ProjectCompiler(vfs)
        compiler.build("/proj", noEmit = true)
        val missesAfterFirst = CrawlParseCache.misses
        val hitsAfterFirst = CrawlParseCache.hits
        compiler.build("/proj", noEmit = true)
        // The first build parses both files; the second parses neither.
        assert(missesAfterFirst == 2L)
        assert(hitsAfterFirst == 0L)
        assert(CrawlParseCache.misses == 2L)
        assert(CrawlParseCache.hits == 2L)
        assert(CrawlParseCache.size == 2)
    }

    @Test
    fun `the SERVED tree is the one the second build compiles`() {
        // INV.1(e)'s lying sentinel: a record whose recorded content and flags
        // are the file's but whose TREE is another program's. Nothing else can
        // observe which tree a build used; the emitted JS can.
        //
        // The record is taken from the cache AFTER a real build rather than
        // rebuilt here, because the driver computes its parser flags from the
        // LOADED tsconfig (`strict: true` sets `noImplicitAny`) and a
        // hand-computed `CompilerOptions()` set does not match — which would
        // make this pin miss for a reason that has nothing to do with what it
        // is testing.
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to tsconfig,
                "/proj/main.ts" to "export const realOne = 1;\n",
            )
        )
        val compiler = ProjectCompiler(vfs)
        compiler.build("/proj", noEmit = true)
        val recorded = CrawlParseCache.peek("/proj/main.ts")
        assert(recorded != null)
        val sentinel = parse("/proj/main.ts", "export const sentinelTwo = 2;\n")
        CrawlParseCache.store(
            "/proj/main.ts",
            PreParsedFile(recorded.content, recorded.flags, sentinel.sourceFile, sentinel.diagnostics),
        )
        compiler.build("/proj", noEmit = false)
        val js = vfs.readText("/proj/main.js") ?: ""
        assert(js.contains("sentinelTwo"))
        assert(!js.contains("realOne"))
    }

    // ---- the edit -----------------------------------------------------------

    @Test
    fun `editing a file between builds is seen - an error appears`() {
        val vfs = project(
            main = "import { h } from './helper';\nexport const m: number = h;\n",
            helper = "export const h = 1;\n",
        )
        val compiler = ProjectCompiler(vfs)
        val first = compiler.build("/proj", noEmit = true)
        assert(first.diagnostics.none { it.code == 2322 })
        // The edit a daemon client makes: same path, new bytes.
        vfs.writeText("/proj/helper.ts", "export const h = 'a string now';\n")
        val second = compiler.build("/proj", noEmit = true)
        assert(second.diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `editing a file between builds is seen - an error disappears`() {
        val vfs = project(
            main = "import { h } from './helper';\nexport const m: number = h;\n",
            helper = "export const h = 'a string';\n",
        )
        val compiler = ProjectCompiler(vfs)
        val first = compiler.build("/proj", noEmit = true)
        assert(first.diagnostics.any { it.code == 2322 })
        vfs.writeText("/proj/helper.ts", "export const h = 1;\n")
        val second = compiler.build("/proj", noEmit = true)
        assert(second.diagnostics.none { it.code == 2322 })
    }

    @Test
    fun `an edit that adds an import changes which files the crawl reaches`() {
        // THE wrong-answer path, and the one that the compilation core's own
        // INV.1(e) gate does NOT cover. That gate re-checks content at
        // `ParsedSource`, so a mis-keyed hit there degrades to a redundant
        // parse and a correct type-check — but the CRAWL has already used the
        // stale tree's `moduleSpecifiers` to decide which files exist, and no
        // later gate revisits that. A stale tree here loses a whole file from
        // the program, which surfaces as TS2307 on a program that is fine.
        val vfs = InMemoryVfs(
            mapOf(
                // Explicit `files`, so the program is decided by the CRAWL from
                // main.ts's specifiers rather than by the directory glob — which
                // would put extra.ts in the program whatever the tree said.
                "/proj/tsconfig.json" to """{ "compilerOptions": { "strict": true }, "files": ["main.ts"] }""",
                "/proj/main.ts" to "export const m = 1;\n",
                "/proj/extra.ts" to "export const e = 2;\n",
            )
        )
        val compiler = ProjectCompiler(vfs)
        val first = compiler.build("/proj", noEmit = true)
        assert(first.programFiles.none { it.endsWith("/extra.ts") })
        vfs.writeText("/proj/main.ts", "import { e } from './extra';\nexport const m = e;\n")
        val second = compiler.build("/proj", noEmit = true)
        assert(second.programFiles.any { it.endsWith("/extra.ts") })
        assert(second.diagnostics.none { it.code == 2307 })
    }

    @Test
    fun `the edited file misses while its unchanged neighbour hits`() {
        // The editor workload in miniature, and the reason the prize is real:
        // one file changes and the rest of the program does not.
        val vfs = project(
            main = "import { h } from './helper';\nexport const m: number = h;\n",
            helper = "export const h = 1;\n",
        )
        val compiler = ProjectCompiler(vfs)
        compiler.build("/proj", noEmit = true)
        CrawlParseCache.hits = 0
        CrawlParseCache.misses = 0
        vfs.writeText("/proj/helper.ts", "export const h = 2;\n")
        compiler.build("/proj", noEmit = true)
        assert(CrawlParseCache.hits == 1L)
        assert(CrawlParseCache.misses == 1L)
    }

    @Test
    fun `an edit is seen with the cache disabled too - the negative control`() {
        // The complement population (round 790): the same comparison over the
        // arm where no reuse happens must give the same answer, or the edit
        // pins above are testing the compiler rather than the cache.
        CrawlParseCache.enabled = false
        val vfs = project(
            main = "import { h } from './helper';\nexport const m: number = h;\n",
            helper = "export const h = 1;\n",
        )
        val compiler = ProjectCompiler(vfs)
        assert(compiler.build("/proj", noEmit = true).diagnostics.none { it.code == 2322 })
        vfs.writeText("/proj/helper.ts", "export const h = 'a string now';\n")
        assert(compiler.build("/proj", noEmit = true).diagnostics.any { it.code == 2322 })
        assert(CrawlParseCache.hits == 0L)
        assert(CrawlParseCache.size == 0)
    }

    @Test
    fun `two builds with the cache on and off agree on the diagnostics`() {
        val vfs = project(
            main = "import { h } from './helper';\nexport const m: number = h;\nexport const bad: string = 1;\n",
            helper = "export const h = 1;\n",
        )
        CrawlParseCache.enabled = false
        val off = ProjectCompiler(vfs).build("/proj", noEmit = true)
            .diagnostics.map { "${it.code}@${it.start}" }.sorted()
        CrawlParseCache.enabled = true
        ProjectCompiler(vfs).build("/proj", noEmit = true)
        val onSecond = ProjectCompiler(vfs).build("/proj", noEmit = true)
            .diagnostics.map { "${it.code}@${it.start}" }.sorted()
        assert(onSecond == off)
        assert(CrawlParseCache.hits > 0L)
    }
}
