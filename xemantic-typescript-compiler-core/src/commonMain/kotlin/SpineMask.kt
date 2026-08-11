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

/**
 * (WARM.13b) round 888 — the OFF arm of the per-kind spine skip mask, in the
 * SAME binary as the ON arm (round 795).
 *
 * `spineEnterNode` selects [SpineDispatch.enterSkipMask] or [ZERO] ONCE per
 * file into a `Checker` field, so the arm costs an array-reference load rather
 * than a per-node branch and **the two arms execute the identical instruction
 * sequence** — the boundary count is unchanged, which is what makes a
 * before/after row comparison legal (round 793) and what makes this the
 * ablation switch for `SpineMaskEquivalenceTest`.
 *
 * [ZERO] is all-zero, i.e. "skip nothing", which is exactly the pre-888
 * straight-line prologue.
 */
internal object SpineMask {

    /** `true` restores the pre-888 path: all 46 handlers consulted at every node. */
    var off: Boolean = false

    /** The "skip nothing" mask — the OFF arm's table. */
    val ZERO: LongArray = LongArray(SpineDispatch.KINDS)

    /** The table `spineEnterNode` reads, chosen once per file. */
    fun enterMask(): LongArray = if (off) ZERO else SpineDispatch.enterSkipMask

    fun reset() {
        off = false
    }
}
