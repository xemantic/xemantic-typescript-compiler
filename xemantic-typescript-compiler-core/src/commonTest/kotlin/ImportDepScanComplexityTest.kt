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
 * (INC.57) The emit-order import scan is LINEAR in the program's file count.
 *
 * ## What was wrong
 *
 * `extractRelativeImports` opened with `allFiles.map { it.fileName }.toSet()` — a
 * fresh list AND a fresh hash set of EVERY program file name — and the emit-order
 * scan calls it TWICE per file (once with `/// <reference path>` edges, once
 * without). So the region cost `2 x files^2` string hashes per build, and two
 * neighbouring `parsed.files.any { it.fileName == … }` scans in the same loop were
 * the same shape.
 *
 * ## Why no instrument here could see it
 *
 * The eight dashboard profiles are all tsc's own sources: **78 files averaging 128
 * KB**, where `2 x 78^2` is twelve thousand probes and vanishes. An application
 * project is the opposite shape — thousands of files of about a kilobyte — and
 * that is where a per-FILE overhead dominates. Measured on generated
 * many-small-file projects, `FrontEnd.IMPORTS` read **18.9 / 76.3 / 331.6 ms at
 * 601 / 1201 / 2401 files**: 4x the cost for 2x the files, which is the definition
 * of quadratic, and 20% of a 1,653 ms incremental floor at the top size. After the
 * hoist the same three read 5.8 / 7.1 / 16.1 ms, i.e. a flat ~6-8 us per file.
 *
 * ## Why the assertions are COUNTS and why they compare two PROGRAM SIZES
 *
 * (INC.52)'s law: a floor row read 13.16 ms and 8.42 ms in two draws of one
 * binary, so a timed assertion here would be a coin flip (round 868). More than
 * that — the claim is about COMPLEXITY, and only a count can state one. A wall
 * assertion says "this build was fast"; `programNameSetBuilds == 1` at BOTH sizes
 * says the work does not grow with the program, which is the property that was
 * broken and the property a future edit could silently break again.
 *
 * (INC.55)'s caveat about comparing two different programs does NOT apply: that
 * one bit because the `pass("…")` poll count is not constant across programs, so
 * a DIFFERENCE was swamped. Here the quantity is 1 per build by construction, an
 * absolute value rather than a delta, and program size is precisely the axis under
 * test.
 *
 * ## The ablation these pins name
 *
 * Move the set build back inside `extractRelativeImports`, carrying the census
 * increment with it, and the two count pins read `2 x files` — 20 and 200 against
 * the 1 asserted here. The VALUE pin is the independent half: it fails if the
 * hoisted set is wrong or EMPTY rather than merely rebuilt, which no count can
 * see, and an empty set is exactly what a careless hoist produces.
 */
class ImportDepScanComplexityTest {

    /** The census is process-global, so save and restore it (INC.53)'s idiom. */
    private fun <T> withCensus(block: () -> T): T {
        val lta = EagerIndexCensus.localTypeAliasFileScans
        val eii = EagerIndexCensus.enclosingImportBuilds
        val tlc = EagerIndexCensus.topLevelConstBuilds
        val pns = EagerIndexCensus.programNameSetBuilds
        val tos = EagerIndexCensus.transformOrderSetBuilds
        val rie = EagerIndexCensus.relativeImportExtractions
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
        }
    }

    /**
     * A chain of [n] modules, each importing the one before it and re-exporting a
     * value, plus an entry point. Every file therefore carries a real relative
     * specifier that the scan must resolve — an import-free program would make the
     * count assertions pass over an empty population.
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

    private fun compile(source: String): CompilationResult =
        TypeScriptCompiler().compile(source, "/p/m0.ts")

    @Test
    fun `the program file-name set is built ONCE per compile - not once per import scan`() {
        withCensus {
            val result = compile(chain(10))
            // Ten files, twenty `extractRelativeImports` calls, ONE set.
            assert(EagerIndexCensus.programNameSetBuilds == 1)
            // Vacuity guard: the fixture really is a ten-file program the scan ran over.
            assert(result.sourceEchoes.size == 10)
        }
    }

    @Test
    fun `the name-set build count does not grow with the program - it is the same at 10x the files`() {
        val small = withCensus {
            compile(chain(10))
            EagerIndexCensus.programNameSetBuilds
        }
        val large = withCensus {
            val result = compile(chain(100))
            assert(result.sourceEchoes.size == 100)
            EagerIndexCensus.programNameSetBuilds
        }
        // THE complexity assertion. Before (INC.57) this read 20 against 200.
        assert(small == 1)
        assert(large == 1)
        assert(large == small)
    }

    /**
     * The independent half: a count cannot tell a correctly hoisted set from an
     * EMPTY one, and an empty set is exactly what a careless hoist produces.
     *
     * Emit ORDER is the one observable `extractRelativeImports` feeds — its two
     * results are the dependency map the emit-order topological sort runs on. The
     * four modules below are DECLARED in reverse dependency order, so a scan that
     * resolves no edge has no reason to reorder them and hands back input order;
     * only a populated name set turns that into dependency order.
     */
    @Test
    fun `the hoisted set still resolves import edges - emit order is dependency-first`() {
        val result = compile(
            """
            // @strict: true
            // @Filename: /p/d.ts
            import { c } from "./c";
            export const d = c + 1;
            // @Filename: /p/c.ts
            import { b } from "./b";
            export const c = b + 1;
            // @Filename: /p/b.ts
            import { a } from "./a";
            export const b = a + 1;
            // @Filename: /p/a.ts
            export const a = 1;
            """.trimIndent(),
        )
        val order = result.jsOutputs.map { it.first.substringAfterLast('/') }
        // Declared d, c, b, a — emitted a, b, c, d, because each import is an edge.
        assert(order == listOf("a.js", "b.js", "c.js", "d.js"))
    }

    /**
     * (INC.59) The SAME defect one subsystem over, and the third instance found in one
     * session: `parsedSourceFiles.filter { it.key !in transformOrder.toSet() }` built an
     * N-element set once per entry of an N-entry map, on every build INCLUDING
     * `--noEmit`, which emits nothing at all. `FrontEnd.POST_EMITPREP` read 6.8-8.2 ms
     * at 601 files and 158.5-175.3 at 2401 — 21x for 4x the files — against 1.8-2.8 ms
     * after the hoist, which took the whole post-checker region from 166-189 to 8.6-12.6.
     */
    @Test
    fun `the emit-order set is built ONCE per compile - at 10 files and at 100`() {
        val small = withCensus {
            compile(chain(10))
            EagerIndexCensus.transformOrderSetBuilds
        }
        val large = withCensus {
            val result = compile(chain(100))
            assert(result.sourceEchoes.size == 100)
            EagerIndexCensus.transformOrderSetBuilds
        }
        // Before (INC.59) this was one set per program FILE: 10 against 100.
        assert(small == 1)
        assert(large == 1)
        assert(large == small)
    }
}
