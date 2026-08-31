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

    /**
     * (INC.58) Iterations of `resolveJsxTsxCandidate`'s path-suffix scan, per compile.
     *
     * **0 for a program with no `.jsx`/`.tsx` file, whatever its size — that is the
     * assertion.** It used to scan EVERY file of the program, once per import
     * specifier per extension, i.e. `2 x files x specifiers`, and it did so precisely
     * when `--jsx` is UNSET — the common case, and the one where the answer is always
     * null. Measured on a 2,401-file project with no JSX in it, the pass cost
     * **709.7 ms of a 774.7 ms floor pass table** and grew **14.6x for 4x the files**;
     * (INC.54)(a) had ranked it at 1.2 ms from the tsc profile, which is the
     * corpus-SHAPE law of (INC.57) restated 600-fold.
     *
     * Counted rather than timed for the same reason as [programNameSetBuilds]: the
     * claim is a COMPLEXITY one, and only a count can say "this does not grow with
     * the program".
     */
    var jsxSuffixScanSteps: Long = 0

    /**
     * (INC.59) How many times the emit-order `transformOrder` set was built, per compile.
     *
     * **1, and invariant to the program's file count.** It was written INSIDE a
     * `filter` lambda (`parsedSourceFiles.filter { it.key !in transformOrder.toSet() }`),
     * so an N-element set was rebuilt once per entry of an N-entry map — the third
     * instance of this exact shape found in one session, after [programNameSetBuilds]
     * and [jsxSuffixScanSteps], and the second of the three in a `--noEmit` path that
     * emits nothing. Measured on generated many-small-file projects,
     * `FrontEnd.POST_EMITPREP` read 6.8-8.2 ms at 601 files and **158.5-175.3 at
     * 2401** — 21x for 4x the files — and 1.8-2.8 ms after the hoist.
     *
     * A count, for the reason the two above it are counts: the claim is a COMPLEXITY
     * one, and only a count can say "this does not grow with the program".
     */
    var transformOrderSetBuilds: Int = 0

    /**
     * (INC.64) Calls to `extractRelativeImports`, per compile.
     *
     * **0 on a `--noEmit` build, and `2 x emitting files` otherwise.** Its whole
     * product is `importDeps` / `importDepsNoRefPath`, and every consumer of those
     * exists to ORDER emitted output: `transformOrder` (whose only reader is the
     * transform loop, already `emptyList()` under `skipEmitOutputs` since round 738),
     * `sortedTsFiles` (which orders `jsOutputMap` entries a `--noEmit` build never
     * produces) and `cpcRequireOnlyOrphans` (gated on the same flag already). So an
     * editor keystroke was building a dependency ORDER for an emit that never
     * happens — (INC.59)'s finding one call deeper, and the second time the emit-order
     * region has been found running in the `--noEmit` path.
     *
     * **THE CORPUS CANNOT GATE THIS AND THAT IS THE POINT**: `skipEmitOutputs` is set
     * ONLY by `ProjectCompiler`, never by the `@noEmit` corpus directive (round 738),
     * so all ~13k baselines run with the gate OPEN and are a control. The instruments
     * that see it are the `-project` pins and the 8-profile `--noEmit` grid.
     */
    var relativeImportExtractions: Int = 0

    /**
     * (INC.69) How many times the checker built its BASENAME index, per compile.
     *
     * **1, and invariant to the number of passes that consult it — that is the
     * whole assertion.** Twenty-one registered corpus-PIN walkers are gated on
     * `fileName.substringAfterLast('/') != "<one literal>"`, and each of them used
     * to reach that test by iterating every program file and allocating a `String`
     * per file to compare. Measured on the 2,401-file `many-small-2400-dom`
     * fixture, that is **~0.44 ms EACH** — a plateau of 21 rows of an
     * essentially identical price, **~9.6 ms of a 121 ms incremental floor** and
     * the bulk of what was left of the init-block pass dispatch after
     * (INC.7)/(INC.20)/(INC.21) gated everything gateable.
     *
     * A COUNT, for the reason [programNameSetBuilds] and [transformOrderSetBuilds]
     * are counts: the claim is that the work is done ONCE per compile rather than
     * once per consulting pass, and no wall-clock assertion on a ~120 ms floor can
     * say that ((INC.52): the same binary reads 13.16 and 8.42 ms for one row in
     * two draws of one run). An un-memoized binary reads 21 here.
     */
    var fileBasenameIndexBuilds: Int = 0

    /**
     * (INC.70) How many per-file `perFileScope` tables were actually built, per compile.
     *
     * **`binderResults.size` before, and on a NARROWED build only the files something
     * resolved a name in — that is the assertion.** `init:buildPerFileScopes` used to
     * allocate two maps, copy a file's own locals into one and precompute a
     * `LayeredSymbolTable` shadow list for EVERY program file on EVERY build, whether
     * or not any name was ever resolved there; measured on the 2,401-file
     * `many-small-2400-dom` fixture that is 3.3 ms of a ~120 ms incremental floor.
     *
     * A count for (INC.16)'s reason as well as (INC.52)'s: whether deferring a per-file
     * table pays at all is decided by WHO FORCES it, and that is a population to be
     * measured rather than read off the source.
     */
    var perFileScopeBuilds: Int = 0

    /**
     * (INC.71) 1 if the INV.3(b)(ii) per-file VISIBILITY sets were built, else 0.
     *
     * **0 on a build whose check partition is empty — that is the assertion.** Their
     * three readers ([globalsForFile], [globalsForFileNode],
     * [libValueBehindTypeOnlyShadow]) are all NAME RESOLUTION, and a build that checks
     * nothing resolves nothing: measured on the 2,401-file `many-small-2400-dom`
     * fixture, **0 asks on a floor build against 335,881 on a full one**, for a pass
     * that was 5.6-7.2 ms of a ~136 ms incremental floor.
     *
     * Unlike [perFileScopeBuilds] this is not a per-file population — the sets are one
     * whole-program set difference — so the only two values it can take are 0 and 1,
     * and the pin is that a FLOOR build reads 0 while a full one reads 1.
     */
    var perFileVisibilityBuilds: Int = 0

    fun resetCounters() {
        transformOrderSetBuilds = 0
        relativeImportExtractions = 0
        localTypeAliasFileScans = 0
        enclosingImportBuilds = 0
        topLevelConstBuilds = 0
        programNameSetBuilds = 0
        jsxSuffixScanSteps = 0
        fileBasenameIndexBuilds = 0
        perFileScopeBuilds = 0
        perFileVisibilityBuilds = 0
    }
}
