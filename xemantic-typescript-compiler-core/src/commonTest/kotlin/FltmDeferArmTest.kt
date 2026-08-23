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
 * (INC.11) THE MEASUREMENT ARM MUST BE INERT WHEN UNSET, and the pin is a COUNT
 * rather than a time — CLAUDE.md's round-876 law: a redundant or relocated
 * computation is invisible to every output gate here, so only a counter sees it.
 *
 * [FltmDefer] splits `init:buildFileLocalTypeMaps` into three phases so (INC.10)'s
 * three-point table is re-measurable in one binary. The default is every phase
 * eager, which is the shipped pass; anything else arms a lazy per-file build behind
 * the map's one reader, and the two capture sweeps are what grade it.
 *
 * The arm is REFUSED as a default, with numbers: fully deferred moves
 * `scripts/capture-equivalence.sh` from 5 divergent spans to 471 in 8 of 76 files,
 * and `TYPEALIAS`-only-eager to 137 in 10. What it buys is the whole-program
 * FIRST-TOUCH ORDER, which is the pass's real product.
 */
class FltmDeferArmTest {

    /** The shipped default is every phase eager — no lazy path armed. */
    @Test
    fun `the default eager set is every phase and the lazy path is disarmed`() {
        assert(FltmDefer.eager == FltmDefer.Phases.ALL)
        assert(!FltmDefer.armed)
        assert(FltmDefer.Phases.ALL.size == 3)
    }

    /** An unset or unrecognised environment value means the shipped behaviour. */
    @Test
    fun `an unset or unknown arm name means every phase eager`() {
        assert(FltmDefer.fromName(null) == FltmDefer.Phases.ALL)
        assert(FltmDefer.fromName("") == FltmDefer.Phases.ALL)
        assert(FltmDefer.fromName("nonsense") == FltmDefer.Phases.ALL)
        assert(FltmDefer.fromName("all") == FltmDefer.Phases.ALL)
    }

    /** The three named points of (INC.10)'s table are distinct and ordered. */
    @Test
    fun `the named arms are the three points of the measured table`() {
        assert(FltmDefer.fromName("decls") == FltmDefer.Phases.DECLS)
        assert(FltmDefer.fromName("typealias") == FltmDefer.Phases.TYPEALIAS_ONLY)
        assert(FltmDefer.fromName("none") == FltmDefer.Phases.NONE)
        assert(FltmDefer.Phases.DECLS.size == 2)
        assert(FltmDefer.Phases.TYPEALIAS_ONLY.size == 1)
        assert(FltmDefer.Phases.NONE.isEmpty())
        // Every non-default arm is armed; the default is not.
        assert(FltmDefer.Phases.DECLS != FltmDefer.Phases.ALL)
    }

    /**
     * A compile at the default builds every file's map in the `init` pass and
     * performs NO lazy build — the receipt that the phase split did not silently
     * turn the shipped pass into an on-demand one.
     */
    @Test
    fun `a default compile builds the maps eagerly and never lazily`() {
        val diagnostics = diagnose(
            """
            interface Shape { a: number }
            type Alias = Shape;
            declare const s: Alias;
            const bad: string = s.a;
            """,
        )
        assert(diagnostics.any { it.code == 2322 })
        assert(FltmDefer.eagerBuilds > 0)
        assert(FltmDefer.lazyBuilds == 0)
    }
}
