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
     * The eager set. Assigning anything other than [Phases.ALL] arms the lazy path.
     *
     * NOT routed through `ModeLedger`: this is not a CLI flag, and the two sweeps
     * that read it each run one arm per process.
     */
    var eager: Set<Phase> = Phases.ALL

    /** True when some phase is deferred, i.e. when the lazy path may run. */
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
}
