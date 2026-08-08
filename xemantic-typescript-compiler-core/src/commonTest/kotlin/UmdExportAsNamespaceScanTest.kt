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
 * (WARM.7) round 860 — the SHARED `export as namespace X` scan.
 *
 * Round 859 measured `checkUmdGlobalVsDeclareGlobalConst` and
 * `checkCrossFileModuleAugmentationDuplicates` as the two slowest-WARMING
 * passes in the ~416-pass tail (0.85x and 1.05x against a 2.90x median), each
 * running its own copy of the same pattern over the full ~10 MB of program
 * text, together **1.30% of a warm rebuild** and matching zero times
 * (`docs/perf/warm-tail-attribution.md` § 4). Both now read
 * [scanUmdExportAsNamespace] through a per-FILE memo.
 *
 * What this class pins, and why each pin can FAIL:
 *
 *  - the scanner's own contract — name AND offset — on the shapes the pattern
 *    admits and on the near-misses it must refuse. The offset is load-bearing:
 *    it is the `start` of the emitted TS2451 and the input to
 *    `getLineAndCharacterOfPosition`, so a scanner that found the right names
 *    at the wrong positions would still emit, with wrong line numbers.
 *  - the two passes end-to-end, each with a POSITIVE control (a fixture that
 *    DOES contain the construct and must still produce TS2451) and its
 *    complement (round 790: a skip's positive control is its complement
 *    population, and it costs nothing to ship).
 *  - **the per-file keying**, which is the one hazard the memo introduces. A
 *    memo that leaked one file's occurrences to another would attribute a UMD
 *    occurrence to a file that has none, and the cross-file fixture below emits
 *    a THIRD TS2451 the moment it does.
 */
class UmdExportAsNamespaceScanTest {

    // ---------------------------------------------------------------- scanner

    @Test
    fun `the scan reports the identifier and the offset at which it starts`() {
        val text = "export as namespace Lib;\n"
        val found = scanUmdExportAsNamespace(text)
        assert(found.map { it.name } == listOf("Lib"))
        assert(found.single().pos == text.indexOf("Lib"))
    }

    @Test
    fun `leading indentation and runs of spaces and tabs between the tokens are admitted`() {
        val name = "Lib_\$0"
        val text = "\t  export\t \tas   namespace\t\t$name;\n"
        val found = scanUmdExportAsNamespace(text)
        assert(found.map { it.name } == listOf(name))
        assert(found.single().pos == text.indexOf(name))
    }

    @Test
    fun `every line of a multi-line file is an anchor and the results come in source order`() {
        val text = "export as namespace A;\nconst x = 1;\r\nexport as namespace B;\rexport as namespace C;\n"
        val found = scanUmdExportAsNamespace(text)
        assert(found.map { it.name } == listOf("A", "B", "C"))
        assert(found.map { it.pos } == listOf(text.indexOf("A;"), text.indexOf("B;"), text.indexOf("C;")))
    }

    @Test
    fun `negative control - shapes the pattern does not admit report nothing`() {
        // not at a line start / no whitespace run / a longer token ending in the keyword
        assert(scanUmdExportAsNamespace("const q = 1; export as namespace Lib;\n").isEmpty())
        assert(scanUmdExportAsNamespace("export as namespaceLib;\n").isEmpty())
        assert(scanUmdExportAsNamespace("export asnamespace Lib;\n").isEmpty())
        assert(scanUmdExportAsNamespace("exports as namespace Lib;\n").isEmpty())
        assert(scanUmdExportAsNamespace("export as namespace 9Lib;\n").isEmpty())
        assert(scanUmdExportAsNamespace("export\nas namespace Lib;\n").isEmpty())
        assert(scanUmdExportAsNamespace("declare namespace Lib { }\n").isEmpty())
    }

    // ------------------------------------------- checkUmdGlobalVsDeclareGlobalConst

    /** The UMD global and the `declare global` const collide: TS2451 at BOTH. */
    @Test
    fun `positive control - a UMD global redeclared as a declare-global const still emits TS2451`() {
        val diagnostics = diagnose(
            """
            export as namespace Lib;
            declare global {
                const Lib: string;
            }
            export {};
            """,
        )
        assert(diagnostics.count { it.code == 2451 } == 2)
    }

    @Test
    fun `negative control - the same declare-global const without the UMD global is silent`() {
        val diagnostics = diagnose(
            """
            declare global {
                const Lib: string;
            }
            export {};
            """,
        )
        assert(diagnostics.none { it.code == 2451 })
    }

    // --------------------------------------------------------- the per-file keying

    /**
     * The Inv3NodeKeyedLookupTest multi-file pattern (path-shaped names per the
     * round-501 lesson), run THROUGH [runWithDeepStack] — the funnel every JVM
     * compile crosses. That is not decoration: `PassLab.ensureLoaded()` lives
     * there, so a `build/pass-lab.txt` ablation reaches these fixtures. A
     * direct `Checker(...)` construction never loads the lab, which is exactly
     * how round 860's first discrimination check read a pass-B pin as green
     * against a binary whose pass B was disabled.
     */
    private fun checkFiles(vararg files: Pair<String, String>): List<Diagnostic> {
        val options = CompilerOptions()
        val results = files.map { (name, src) -> Binder(options).bind(Parser(src.trimIndent(), name).parse()) }
        return runWithDeepStack { Checker(options, results, isMultiFileSource = true).getDiagnostics() }
    }

    /**
     * The UMD occurrence lives in a.ts and the colliding `declare global` const in
     * b.ts, so exactly TWO TS2451 are correct — one per file. A memo that served
     * a.ts's occurrence list for b.ts as well would find a second UMD occurrence
     * "in" b.ts and emit a third.
     */
    @Test
    fun `an occurrence is attributed to its OWN file only - the memo does not leak across files`() {
        val diagnostics = checkFiles(
            "/proj/a.ts" to """
                export as namespace Lib;
                export const value = 1;
            """,
            "/proj/b.ts" to """
                declare global {
                    const Lib: string;
                }
                export {};
            """,
        ).filter { it.code == 2451 }
        assert(diagnostics.size == 2)
        assert(diagnostics.map { it.fileName }.toSet() == setOf("/proj/a.ts", "/proj/b.ts"))
    }

    // ------------------------------ checkCrossFileModuleAugmentationDuplicates (B555)

    /**
     * B555's UMD leg: `declare global { namespace Lib { export const value } }`
     * merges into the module `export as namespace Lib` projects, which already
     * exports `value` — TS2451 at the hub and at the augmentation. This pass reads
     * the shared scan ONLY to build its name-to-module-file map, so it is the pin
     * that fails if that map went empty.
     */
    @Test
    fun `positive control - a declare-global namespace redeclaring a UMD module's export still emits TS2451`() {
        val diagnostics = checkFiles(
            "/proj/a.ts" to """
                export as namespace Lib;
                export const value = 1;
            """,
            "/proj/b.ts" to """
                declare global {
                    namespace Lib {
                        export const value: number;
                    }
                }
                export {};
            """,
        ).filter { it.code == 2451 }
        assert(diagnostics.size == 2)
        assert(diagnostics.map { it.fileName }.toSet() == setOf("/proj/a.ts", "/proj/b.ts"))
    }

    @Test
    fun `negative control - without the UMD declaration the same augmentation is silent`() {
        val diagnostics = checkFiles(
            "/proj/a.ts" to """
                export const value = 1;
            """,
            "/proj/b.ts" to """
                declare global {
                    namespace Lib {
                        export const value: number;
                    }
                }
                export {};
            """,
        )
        assert(diagnostics.none { it.code == 2451 })
    }
}
