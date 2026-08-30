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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (INC.58) `checkJsxImportResolutions` does not scan the whole program per import.
 *
 * ## What was wrong
 *
 * `resolveJsxTsxCandidate`'s last resort is a PATH-SUFFIX match — "any program file
 * whose name ends with `/<base>.jsx`" — and it walked `fileResults.keys`, i.e. every
 * file of the program, once per import specifier per extension. That is
 * `2 x files x specifiers`, and the pass runs precisely when `--jsx` is **UNSET**,
 * which is the common case for an ordinary TypeScript project and the one where the
 * answer is always null.
 *
 * Measured on a generated 2,401-file project **containing no JSX whatsoever**, the
 * pass cost **709.7 ms of a 774.7 ms floor pass table — 92% of it — and grew 14.6x
 * for 4x the files**. It is what an editor pays per keystroke. (INC.54)(a) had
 * ranked this pass at **1.2 ms** from the tsc profile; the discrepancy is (INC.57)'s
 * corpus-SHAPE law (78 files of 128 KB cannot express a per-FILE cost) restated
 * six-hundred-fold.
 *
 * ## Why restricting the scan is EXACTLY equivalent, not merely conservative
 *
 * Every non-null return of `resolveJsxTsxCandidate` is a member of `fileResults`
 * whose name ends in `.jsx` or `.tsx`: the direct probes build their candidate as
 * `"…$ext"` and test `in fileResults`, and both arms of the suffix scan
 * (`fn.endsWith("/$base$ext")`, `fn == "$base$ext"`) can only match such a name. So
 *
 *  * scanning the `.jsx`/`.tsx` subset returns exactly what scanning all files did —
 *    and in the same order, which matters because the scan takes the FIRST match; and
 *  * a program with no such file can produce no TS6142 at all, so the pass may
 *    return immediately.
 *
 * ## Why the assertions are COUNTS
 *
 * The claim is about COMPLEXITY, and no wall-clock assertion can state one — see
 * (INC.52) for why a timed floor assertion is a coin flip anyway. The VALUE pin is
 * the independent half: a count cannot tell a correctly restricted scan from one
 * that was simply deleted, and TS6142 is a real diagnostic that must still fire.
 */
class JsxImportResolutionScanTest {

    /** The census is process-global, so save and restore it ((INC.53)'s idiom). */
    private fun <T> withCensus(block: () -> T): T {
        val lta = EagerIndexCensus.localTypeAliasFileScans
        val eii = EagerIndexCensus.enclosingImportBuilds
        val tlc = EagerIndexCensus.topLevelConstBuilds
        val pns = EagerIndexCensus.programNameSetBuilds
        val jss = EagerIndexCensus.jsxSuffixScanSteps
        EagerIndexCensus.resetCounters()
        try {
            return block()
        } finally {
            EagerIndexCensus.localTypeAliasFileScans = lta
            EagerIndexCensus.enclosingImportBuilds = eii
            EagerIndexCensus.topLevelConstBuilds = tlc
            EagerIndexCensus.programNameSetBuilds = pns
            EagerIndexCensus.jsxSuffixScanSteps = jss
        }
    }

    /**
     * A chain of [n] ordinary `.ts` modules, each with a real relative import — so
     * the scan has specifiers to work on — and NOT ONE `.jsx` or `.tsx` file, which
     * is the shape an ordinary TypeScript project has.
     */
    private fun tsOnlyChain(n: Int): String = buildString {
        append("// @strict: true\n")
        append("// @Filename: /p/m0.ts\n")
        append("export const v0 = 0;\n")
        for (i in 1 until n) {
            append("// @Filename: /p/m$i.ts\n")
            append("import { v${i - 1} } from \"./m${i - 1}\";\n")
            append("export const v$i = v${i - 1} + $i;\n")
        }
    }

    private fun compile(source: String) = TypeScriptCompiler().compile(source, "/p/m0.ts")

    @Test
    fun `a program with no jsx or tsx file never enters the suffix scan`() {
        withCensus {
            val result = compile(tsOnlyChain(10))
            assert(EagerIndexCensus.jsxSuffixScanSteps == 0L)
            // Vacuity guard: ten files, each but the first carrying a specifier the
            // scan would have had to consider.
            assert(result.sourceEchoes.size == 10)
        }
    }

    @Test
    fun `the suffix scan does not grow with the program - still zero at 10x the files`() {
        val small = withCensus {
            compile(tsOnlyChain(10))
            EagerIndexCensus.jsxSuffixScanSteps
        }
        val large = withCensus {
            val result = compile(tsOnlyChain(100))
            assert(result.sourceEchoes.size == 100)
            EagerIndexCensus.jsxSuffixScanSteps
        }
        // Before (INC.58) this grew as `2 x files x specifiers` — roughly 180 against
        // 19,800. The scan is now entered only by a program that has a JSX file at all.
        assert(small == 0L)
        assert(large == 0L)
        assert(large == small)
    }

    /**
     * The independent half, direct-probe path: a RELATIVE specifier resolves by
     * `"$dir/$base$ext" in fileResults`, an O(1) probe that never reaches the scan.
     *
     * The first draft of this test asserted `jsxSuffixScanSteps > 0` here and went
     * RED on a working binary — the diagnostic fired, but through the probe. That is
     * worth keeping as a comment: an assertion about WHICH path served an answer is
     * not implied by the answer being right, and the two pins below exist because
     * one fixture cannot cover both paths.
     */
    @Test
    fun `TS6142 still fires for a relative specifier - resolved by the direct probe`() {
        withCensus {
            val diagnostics = compile(
                """
                // @strict: true
                // @allowJs: true
                // @Filename: /p/app.ts
                import { thing } from "./widget";
                export const used = thing;
                // @Filename: /p/widget.jsx
                export const thing = 1;
                """.trimIndent(),
            ).diagnostics
            diagnostics should { have(any { it.code == 6142 }) }
            // The probe answered, so the scan was never needed.
            assert(EagerIndexCensus.jsxSuffixScanSteps == 0L)
        }
    }

    /**
     * The independent half, SUFFIX-SCAN path — the one this round narrowed, so
     * nothing else pins it.
     *
     * A BARE specifier `"widget"` builds candidates `widget.jsx`, `./widget.jsx`,
     * `/widget.jsx`, none of which is the program's `/p/widget.jsx`; only the
     * path-suffix match on `/widget.jsx` finds it. So this fails if the restricted
     * scan lost a file the full scan would have found.
     */
    @Test
    fun `TS6142 still fires through the SUFFIX SCAN for a bare specifier`() {
        withCensus {
            val diagnostics = compile(
                """
                // @strict: true
                // @allowJs: true
                // @Filename: /p/app.ts
                import { thing } from "widget";
                export const used = thing;
                // @Filename: /p/widget.jsx
                export const thing = 1;
                """.trimIndent(),
            ).diagnostics
            diagnostics should { have(any { it.code == 6142 }) }
            // …and it really did come through the narrowed scan, not around it.
            assert(EagerIndexCensus.jsxSuffixScanSteps > 0L)
        }
    }
}
