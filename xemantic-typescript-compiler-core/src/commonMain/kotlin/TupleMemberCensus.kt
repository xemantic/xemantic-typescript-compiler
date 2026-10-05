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
 * (CHK.233) Census of tuple member tables `Checker.buildTupleFromTypes` actually built.
 *
 * The table is deferred (`Type.Object.lazyMembers`): a tuple minted only to be spread into
 * the next level of an accumulator recursion (`F<T, [...Acc, unknown]>`, which tsgo's tail
 * budget runs 1,000 levels deep) is never asked for its members, so it never mints its k
 * element symbols nor writes their k global `symbolTypes` entries. [elementSymbols] is the
 * population that eager construction made quadratic in the recursion depth; a pin over it
 * is a COUNT, deterministic where a timed or heap assertion is a coin flip (round 868).
 *
 * Process-global counters, so a reader saves and restores them.
 */
object TupleMemberCensus {
    /** Tuple member tables built (one per forced tuple). */
    var tables: Int = 0

    /** Numbered element symbols minted across those tables. */
    var elementSymbols: Int = 0
}
