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
 */

package com.xemantic.typescript.compiler

/**
 * (INC.53) THE DETERMINISTIC INSTRUMENT for `Checker`'s three whole-program FIELD
 * INITIALIZERS, moved onto first ask.
 *
 * ## Why this class of work was invisible for ~950 rounds
 *
 * The pass-gating arc ((INC.7) / (INC.20) / (INC.21)) swept `pass("…")` bodies —
 * 157 + 13 + 19 walkers gated onto the check partition — and nothing it used could
 * see a FIELD INITIALIZER. `--passTiming`'s table is built by `pass()`, so a
 * `private val` that walks the whole program contributes to no row; `cost_gate.py`
 * reads that table's counters; the 8-profile grid compares diagnostics, which do
 * not move. Measured with a purpose-built front-end probe, `Checker`'s ~494
 * property initializers cost **16-30 ms on EVERY build** — 0.4% of a 5.2 s full
 * compile, which is why no round ever noticed, and **~30% of a 63-72 ms
 * INCREMENTAL FLOOR**, which is what an editor pays per keystroke.
 *
 * Four initializers are essentially all of it; the other ~490 are 0.2-1.2 ms
 * between them. Three of the four are indices over `binderResults` and are what
 * this census covers.
 *
 * ## Why a COUNT and not a millisecond
 *
 * (INC.52)'s law, and it is the reason that round claimed no time either: a
 * per-pass row on a ~68 ms floor read 13.16 ms in one draw and 8.42 in the next of
 * the SAME binary. A population is deterministic and needs no second binary.
 *
 * Counters only — there is no mode to arm and no default to get wrong ((INC.16)'s
 * law: a pin that installs the mode it wants leaves the shipped default pinned by
 * nothing). The laziness is unconditional, so a pin over these counters is a pin
 * on what ships.
 */
object EagerIndexCensus {

    /**
     * Files whose nested-type-alias index was actually built.
     *
     * The eager form scanned EVERY program file (a DFS through every function body,
     * block, namespace and `if` branch); the lazy form builds one file's index the
     * first time that file is asked about. So this is `0` on a build that checks
     * nothing and small on a narrowed one, against `binderResults.size` before.
     */
    var localTypeAliasFileScans: Int = 0

    /** 1 if the whole-program import-specifier index was built, else 0. */
    var enclosingImportBuilds: Int = 0

    /** 1 if the whole-program top-level `const` string index was built, else 0. */
    var topLevelConstBuilds: Int = 0

    /**
     * (INC.57) How many times the program's file-NAME set was built, per compile.
     *
     * **1, and invariant to the program's file count — that is the whole
     * assertion.** It used to be `2 x files`, because `extractRelativeImports`
     * rebuilt `allFiles.map { it.fileName }.toSet()` on entry and the emit-order
     * scan calls it twice for every file, which made the region quadratic:
     * measured on generated many-small-file projects, `FrontEnd.IMPORTS` read
     * 18.9 / 76.3 / 331.6 ms at 601 / 1201 / 2401 files — 4x the cost for 2x the
     * files, textbook.
     *
     * Counted rather than timed for (INC.52)'s reason, and because a COUNT is
     * the only thing that expresses a COMPLEXITY claim: one build of a 2,401-file
     * program and one of a 601-file program must read the SAME number here, which
     * no wall-clock assertion can say and which a quadratic binary fails by 4x.
     */
    var programNameSetBuilds: Int = 0

    fun resetCounters() {
        localTypeAliasFileScans = 0
        enclosingImportBuilds = 0
        topLevelConstBuilds = 0
        programNameSetBuilds = 0
    }
}
