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
 * (INC.11) THE MEASUREMENT ARM for `init:buildFileLocalTypeMaps` — the single
 * largest row in the incremental floor (62-66 ms of a ~212 ms pass table).
 *
 * (INC.10) built the deferral, measured it, and REVERTED it: it takes the row to
 * 0.01 ms and the narrowed-query median from 402 to 349 ms, every diagnostics gate
 * stays green, and the ONLY thing that moves is `scripts/capture-equivalence.sh`,
 * which goes from 5 divergent spans to 2,722. The pass's real product is therefore
 * not its 4,161 entries but the program-wide FIRST-TOUCH ORDER in which types are
 * interned and alias names attached, and its price is what the capture channel
 * renders.
 *
 * That refusal was recorded as a THREE-POINT table, and this object is what makes
 * the three points re-measurable in ONE binary rather than three: the pass's body
 * splits into three phases and the mode says which of them run eagerly. **The
 * default is [Phases.ALL], which is byte-for-byte the pre-(INC.11) pass** — every
 * phase eager, no lazy path armed, so the flag is behaviour-free when unset, as
 * INV.0 requires.
 *
 * The mode is read from the environment (`XTSC_FLTM_EAGER`) rather than from a CLI
 * flag because its consumers are the two capture sweeps, which drive
 * `ProjectCompiler` directly and never parse an argument vector — and because
 * CLAUDE.md records that Gradle does not forward `-D` to a test JVM.
 */
object FltmDefer {

    /**
     * Which phases of `buildFileLocalTypeMaps` run in the `init` pass. Everything
     * not named here is built ON DEMAND, per file, behind the map's one reader.
     *
     * The split follows the pass's own `when` on `Symbol.flags`, and the phases are
     * named after what (INC.10)'s table measured:
     *
     *  * [DECL] — `Function | Class | Interface | Enum | Alias`;
     *  * [TYPEALIAS] — `TypeAlias`, which owns 83% of the divergence for 6.81 ms;
     *  * [VAR] — an annotated `Variable | Property`, which owns 1.13 ms and no
     *    divergence at all.
     */
    enum class Phase { DECL, TYPEALIAS, VAR }

    /** The named points of (INC.10)'s table, as eager-phase sets. */
    object Phases {
        /** Every phase eager — the shipped behaviour, and the default. */
        val ALL: Set<Phase> = setOf(Phase.DECL, Phase.TYPEALIAS, Phase.VAR)
        /** (INC.10) row 3: the whole DECLARATION branch eager — 64.94 ms, 5 spans. */
        val DECLS: Set<Phase> = setOf(Phase.DECL, Phase.TYPEALIAS)
        /** (INC.10) row 2: the `TypeAlias` symbols only — 6.81 ms, 462 spans. */
        val TYPEALIAS_ONLY: Set<Phase> = setOf(Phase.TYPEALIAS)
        /** (INC.10) row 1: fully deferred — 0.01 ms, 2,722 spans. */
        val NONE: Set<Phase> = emptySet()
    }

    /**
     * (INC.23) THE SECOND AXIS — WHICH FILES the `init` pass covers, as opposed to
     * [eager], which says which PHASES each covered file gets.
     *
     * (INC.10) and (INC.11) both varied [eager], i.e. changed what every file's map
     * carries, which perturbs a FULL build's first-touch order as much as a narrowed
     * one's; both were refused. [PARTITION] instead iterates the INV.6(6d) partition
     * view, which **IS** `binderResults` whenever there is no partition — so an
     * unpartitioned compile runs byte-for-byte the pass it always ran and only a
     * `recheckOnly` build skips anything.
     *
     * (INC.22) measured that arm at **floor 131 -> 57 ms, narrowed-query median
     * 166 -> 116, ratio 29.86x -> 42.61x** and REFUSED it: `capture-channel-
     * equivalence`'s `narrowRendersMoreAny` goes **168 -> 229**, i.e. +61 member
     * types collapse to `any` under a narrowed build. That is a WRONG answer, so
     * [PROGRAM] is the shipped default and [PARTITION] is a MEASUREMENT ARM — the
     * instrument (INC.23) censuses the 61 with, not a behaviour anything ships.
     */
    enum class Scope {
        /** Every file in the program — the shipped pass. */
        PROGRAM,

        /** The check partition, with the map's one reader building the rest. */
        PARTITION,
    }

    /**
     * The eager set. Assigning anything other than [Phases.ALL] arms the lazy path.
     *
     * NOT routed through `ModeLedger`: this is not a CLI flag, and the two sweeps
     * that read it each run one arm per process.
     */
    var eager: Set<Phase> = Phases.ALL

    /**
     * (INC.23) Which files the eager pass covers. [Scope.PROGRAM] is the shipped
     * pass; anything else is an arm, and `FltmDeferArmTest` pins that the DEFAULT
     * is the shipped one with no mode install of its own — (INC.16) arm a1's
     * lesson, that a pin which sets the mode it wants leaves the default pinned by
     * nothing.
     */
    var scope: Scope = Scope.PROGRAM

    /**
     * True when some PHASE is deferred, i.e. when the lazy path may run even on a
     * build with no partition.
     *
     * Deliberately says nothing about [scope]: a partition-scoped pass needs the
     * lazy path only when there IS a partition, which is a property of the checker
     * and not of this object, so that half is decided per-checker.
     */
    val armed: Boolean get() = eager.size != 3

    /** Per-checker count of files whose map was built lazily, for the pins. */
    var lazyBuilds: Int = 0

    /** Per-checker count of files whose map was built by the eager pass. */
    var eagerBuilds: Int = 0

    /** Resets the counters; called at the top of the eager pass. */
    fun resetCounters() {
        lazyBuilds = 0
        eagerBuilds = 0
    }

    /** Parses `XTSC_FLTM_EAGER`; unset or unrecognised means [Phases.ALL]. */
    fun fromName(name: String?): Set<Phase> = when (name?.lowercase()) {
        null, "", "all" -> Phases.ALL
        "decls" -> Phases.DECLS
        "typealias" -> Phases.TYPEALIAS_ONLY
        "none" -> Phases.NONE
        else -> Phases.ALL
    }

    /**
     * (INC.23) Parses `XTSC_FLTM_SCOPE`; unset or unrecognised means [Scope.PROGRAM],
     * the shipped pass. A SEPARATE environment variable from `XTSC_FLTM_EAGER`
     * because the two axes are independent and a sweep must be able to vary exactly
     * one of them.
     */
    fun scopeFromName(name: String?): Scope = when (name?.lowercase()) {
        "partition" -> Scope.PARTITION
        else -> Scope.PROGRAM
    }
}
