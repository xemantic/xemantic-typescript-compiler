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
 * (INC.69) The 21 corpus-PIN walkers reach their file through ONE basename index.
 *
 * ## What was wrong
 *
 * Twenty-one registered passes are gated on a single hard-coded corpus file name:
 * their whole body is a loop over every program file whose first act is
 * `if (fileName.substringAfterLast('/') != "<one literal>") continue`. So each of
 * them walked the entire program and allocated a `String` per file to compare it
 * against a name no real project contains.
 *
 * Measured on the 2,401-file `many-small-2400-dom` fixture, that is **~0.44 ms
 * EACH** — a plateau of 21 rows of an almost identical price, **~9.6 ms of a
 * 121 ms incremental floor**, and the bulk of what was left of the init-block
 * pass dispatch after (INC.7)/(INC.20)/(INC.21) had gated everything gateable.
 * They now iterate `filesNamed("<literal>")`, which consults one index built on
 * first ask.
 *
 * ## Why the value pins use NESTED paths - and why the corpus cannot
 *
 * The generated corpus harness materialises no directory: its file names are FLAT
 * (`temporal.ts`), where `substringAfterLast('/')` returns the whole string. So
 * all ~13k baselines exercise the degenerate key and are a CONTROL for the
 * conversion, not coverage of it. An index keyed by the full path instead of the
 * last segment passes every one of them and silently stops pinning a real
 * project's `src/dates/temporal.ts` — which is the direction that matters here,
 * because a lost pin is a MISSING diagnostic and nothing in this repo prints it.
 *
 * The same-basename-twice pin is the second half: an index built as
 * `Map<String, BinderResult>` rather than as a map to a LIST answers only one of
 * two files that share a basename, which no fixture in the corpus can show
 * either.
 *
 * ## Why the cost pin is a COUNT
 *
 * (INC.52)'s law: one pass row on a ~120 ms floor read 13.16 ms in one draw and
 * 8.42 in the next of the SAME binary, so a timed assertion here would be a coin
 * flip. And the claim is a COMPLEXITY one — the work is done once per COMPILE
 * rather than once per consulting PASS — which only a count can state. An
 * un-memoized binary reads 21 here; a binary that rebuilt per call would read 21
 * at ten files and 21 at a hundred.
 */
class CorpusPinBasenameIndexTest {

    /** The census is process-global, so save and restore it ((INC.53)'s idiom). */
    private fun <T> withCensus(block: () -> T): T {
        val lta = EagerIndexCensus.localTypeAliasFileScans
        val eii = EagerIndexCensus.enclosingImportBuilds
        val tlc = EagerIndexCensus.topLevelConstBuilds
        val pns = EagerIndexCensus.programNameSetBuilds
        val tos = EagerIndexCensus.transformOrderSetBuilds
        val rie = EagerIndexCensus.relativeImportExtractions
        val jss = EagerIndexCensus.jsxSuffixScanSteps
        val fbi = EagerIndexCensus.fileBasenameIndexBuilds
        EagerIndexCensus.resetCounters()
        try {
            return block()
        } finally {
            EagerIndexCensus.localTypeAliasFileScans = lta
            EagerIndexCensus.enclosingImportBuilds = eii
            EagerIndexCensus.topLevelConstBuilds = tlc
            EagerIndexCensus.programNameSetBuilds = pns
            EagerIndexCensus.transformOrderSetBuilds = tos
            EagerIndexCensus.relativeImportExtractions = rie
            EagerIndexCensus.jsxSuffixScanSteps = jss
            EagerIndexCensus.fileBasenameIndexBuilds = fbi
        }
    }

    private fun compile(source: String): CompilationResult =
        TypeScriptCompiler().compile(source, "/p/entry.ts")

    /**
     * `checkTemporalPin` is the probe: its only gates are "not a `.d.ts`" and the
     * basename, and it emits four TS2339 rows unconditionally for every file it
     * reaches, so its firing is an exact, deterministic report of which files the
     * walker's iteration source offered it.
     */
    private val temporalMessage = "Property 'year' does not exist on type 'Instant'."

    private fun temporalRows(result: CompilationResult, fileName: String): List<Diagnostic> =
        result.diagnostics.filter { it.fileName == fileName && it.code == 2339 }

    @Test
    fun `a corpus pin walker reaches its file through a NESTED directory path`() {
        val result = compile(
            """
            // @strict: true
            // @Filename: /p/entry.ts
            export const e = 0;
            // @Filename: /p/sub/dir/temporal.ts
            export const x = 1;
            """.trimIndent(),
        )
        val rows = temporalRows(result, "/p/sub/dir/temporal.ts")
        assert(rows.size == 4)
        assert(rows.any { it.message == temporalMessage })
    }

    @Test
    fun `negative control - a basename that merely ENDS WITH the pinned name is not reached`() {
        val result = compile(
            """
            // @strict: true
            // @Filename: /p/entry.ts
            export const e = 0;
            // @Filename: /p/sub/dir/xtemporal.ts
            export const x = 1;
            """.trimIndent(),
        )
        assert(temporalRows(result, "/p/sub/dir/xtemporal.ts").isEmpty())
    }

    @Test
    fun `two files sharing one basename in different directories are BOTH reached`() {
        val result = compile(
            """
            // @strict: true
            // @Filename: /p/entry.ts
            export const e = 0;
            // @Filename: /p/a/temporal.ts
            export const x = 1;
            // @Filename: /p/b/temporal.ts
            export const y = 2;
            """.trimIndent(),
        )
        assert(temporalRows(result, "/p/a/temporal.ts").size == 4)
        assert(temporalRows(result, "/p/b/temporal.ts").size == 4)
    }

    /**
     * A chain of [n] modules, each importing the one before it — a real program
     * whose file count is the axis the complexity claim is about.
     */
    private fun chain(n: Int): String = buildString {
        append("// @strict: true\n")
        append("// @Filename: /p/m0.ts\n")
        append("export const v0 = 0;\n")
        for (i in 1 until n) {
            append("// @Filename: /p/m$i.ts\n")
            append("import { v${i - 1} } from \"./m${i - 1}\";\n")
            append("export const v$i = v${i - 1} + $i;\n")
        }
    }

    private fun compileChain(n: Int): CompilationResult =
        TypeScriptCompiler().compile(chain(n), "/p/m0.ts")

    @Test
    fun `the basename index is built ONCE per compile - not once per consulting pass`() {
        withCensus {
            val result = compileChain(10)
            assert(EagerIndexCensus.fileBasenameIndexBuilds == 1)
            // Vacuity guard: the fixture really is a ten-file program.
            assert(result.sourceEchoes.size == 10)
        }
    }

    @Test
    fun `the index build count does not grow with the program - it is the same at 10x the files`() {
        val small = withCensus {
            compileChain(10)
            EagerIndexCensus.fileBasenameIndexBuilds
        }
        val large = withCensus {
            val result = compileChain(100)
            assert(result.sourceEchoes.size == 100)
            EagerIndexCensus.fileBasenameIndexBuilds
        }
        assert(small == 1)
        assert(large == 1)
    }
}
