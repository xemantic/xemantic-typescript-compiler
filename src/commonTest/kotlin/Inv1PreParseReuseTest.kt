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
 * INV.1(e) (round 494): the compilation core's multi-file parse site reuses a
 * crawl-supplied pre-parse ([ParsedSource.preParsed]) ONLY on an exact
 * content + [ParserFlags] match; any mismatch falls back to a fresh parse, so
 * reuse is a pure optimization the corpus suite cannot distinguish from the
 * old double-parse. These tests pin what the suite therefore cannot see:
 *
 *  - reuse actually FIRES on a match (observed via a deliberately-lying
 *    sentinel tree — the only externally visible proof of identity);
 *  - the two safety gates (flags mismatch, content mismatch) each force a
 *    fresh parse;
 *  - the real [ProjectCompiler] driver path reuses every program file's crawl
 *    parse, under options whose parser flags DIFFER from the defaults (module
 *    es2022 drives `topLevelAwait` from OPTIONS, so a crawl that parsed with
 *    default flags would mismatch and the counter would read 0);
 *  - the string-based [TypeScriptCompiler.compile] path (the whole corpus)
 *    carries no pre-parses and always parses fresh.
 */
class Inv1PreParseReuseTest {

    private fun parseWith(fileName: String, content: String, flags: ParserFlags): PreParsedFile {
        val parser = Parser(
            content, fileName, forceJsx = flags.forceJsx, topLevelAwait = flags.topLevelAwait,
            needsJsxFlag = flags.needsJsxFlag, noImplicitAny = flags.noImplicitAny,
        )
        val sourceFile = parser.parse()
        return PreParsedFile(content, flags, sourceFile, parser.getDiagnostics())
    }

    private val fileName = "/p/a.ts"
    private val realContent = "export const realOne = 1;"
    private val sentinelContent = "export const sentinelTwo = 2;"

    /** A pre-parse whose TREE is the sentinel's but whose recorded content/flags
     *  are [realContent]'s — deliberately violating [PreParsedFile]'s fidelity
     *  contract so the emit reveals WHICH tree the core used. */
    private fun lyingPreParse(options: CompilerOptions, flags: ParserFlags = computeParserFlags(fileName, realContent, options)): PreParsedFile {
        val sentinel = parseWith(fileName, sentinelContent, flags)
        return PreParsedFile(realContent, flags, sentinel.sourceFile, sentinel.diagnostics)
    }

    private fun compileWithPreParse(pre: PreParsedFile?, options: CompilerOptions = CompilerOptions()): String {
        val parsed = ParsedSource(
            options,
            listOf(SourceFileEntry(fileName, realContent)),
            hasExplicitFilenames = true,
            preParsed = if (pre == null) emptyMap() else mapOf(fileName to pre),
        )
        val result = TypeScriptCompiler().compileParsed(parsed, options, fileName)
        return result.jsOutputs.joinToString("\n") { it.second }
    }

    @Test
    fun `a matching pre-parse is reused by the multi-file core`() {
        val options = CompilerOptions()
        val js = compileWithPreParse(lyingPreParse(options), options)
        assert(js.contains("sentinelTwo"))
        assert(!js.contains("realOne"))
    }

    @Test
    fun `negative control - a flags mismatch forces a fresh parse`() {
        val options = CompilerOptions()
        val flags = computeParserFlags(fileName, realContent, options)
        val lying = lyingPreParse(options, flags.copy(topLevelAwait = !flags.topLevelAwait))
        val js = compileWithPreParse(lying, options)
        assert(js.contains("realOne"))
        assert(!js.contains("sentinelTwo"))
    }

    @Test
    fun `negative control - a content mismatch forces a fresh parse`() {
        val options = CompilerOptions()
        val flags = computeParserFlags(fileName, realContent, options)
        val sentinel = parseWith(fileName, sentinelContent, flags)
        val stale = PreParsedFile("$realContent ", flags, sentinel.sourceFile, sentinel.diagnostics)
        val js = compileWithPreParse(stale, options)
        assert(js.contains("realOne"))
        assert(!js.contains("sentinelTwo"))
    }

    @Test
    fun `the driver reuses every crawl parse under option-driven parser flags`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/r/tsconfig.json" to
                    """{ "compilerOptions": { "module": "es2022", "target": "es2022" }, "files": ["src/index.ts"] }""",
                "/r/src/index.ts" to "import \"./util\";\nexport const i = 1;",
                "/r/src/util.ts" to "export const u = 2;",
            )
        )
        PassTiming.reset()
        PassTiming.enabled = true
        val result = try {
            ProjectCompiler(vfs).build("/r", noEmit = true)
        } finally {
            PassTiming.enabled = false
        }
        assert(result.programFiles.size == 2)
        // module es2022 makes `topLevelAwait` OPTION-driven for every file: a crawl
        // that parsed with default flags would record topLevelAwait=false, fail the
        // core's flags gate, and this counter would read 0.
        assert(PassTiming.preParseReused == 2L)
        assert(PassTiming.preParseFresh == 0L)
        PassTiming.reset()
    }

    @Test
    fun `a reused parse carries native top-level await through check and emit`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/t/tsconfig.json" to
                    """{ "compilerOptions": { "module": "es2022", "target": "es2022", "outDir": "out" }, "files": ["src/index.ts"] }""",
                "/t/src/index.ts" to "const v = await Promise.resolve(1);\nexport const x = v;",
            )
        )
        val result = ProjectCompiler(vfs).build("/t")
        // The file is a module (top-level export) using top-level await — legal under
        // module es2022. A wrongly-flagged parse (topLevelAwait=false) would read
        // `await` as an identifier and cascade parse errors; the reused crawl parse
        // must flow through check and emit as a native await expression.
        assert(result.errorCount == 0)
        val out = result.written.firstOrNull { it.first.endsWith("index.js") }
        assert(out != null)
        val js = vfs.readText(out.first) ?: ""
        assert(js.contains("await Promise.resolve(1)"))
    }

    @Test
    fun `negative control - the string compile path parses fresh`() {
        PassTiming.reset()
        PassTiming.enabled = true
        try {
            TypeScriptCompiler().compile(
                "// @Filename: a.ts\nexport const a = 1;\n// @Filename: b.ts\nexport const b = 2;",
                "multi.ts",
            )
        } finally {
            PassTiming.enabled = false
        }
        assert(PassTiming.preParseReused == 0L)
        assert(PassTiming.preParseFresh == 2L)
        PassTiming.reset()
    }
}
