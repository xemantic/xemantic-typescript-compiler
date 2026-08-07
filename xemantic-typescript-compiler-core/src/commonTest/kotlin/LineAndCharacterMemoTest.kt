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
 * Round 486 (M5.1 perf): `getLineAndCharacterOfPosition` was an O(position) linear
 * newline scan from index 0 on every call — replaced with a per-source memoized
 * line-start table + binary search for the greatest line-start offset <= position.
 * The result (1-based line, 1-based character) must be byte-for-byte identical to the
 * former scan; positions near the end of a large file are exactly where a binary-search
 * off-by-one would show up. This pins the invariant OFFSET-INDEPENDENTLY: two
 * structurally identical errors placed a known number of lines apart must report a
 * line-gap of exactly that many lines and an identical column — regardless of any
 * absolute line offset from directive stripping.
 */
class LineAndCharacterMemoTest {

    @Test
    fun `line and column stay correct for positions far apart in a large source`() {
        // `a` sits on the line after 40 comment lines; `b` after 40 more — a 41-line
        // gap between the two error positions, both deep into the source so the binary
        // search over the line-start table must land the correct line and line-start.
        fun bad(name: String) = "let $name: number = \"oops\";"
        val src = "// filler\n".repeat(40) +
            bad("a") + "\n" +
            "// gap\n".repeat(40) +
            bad("b")
        val ts2322 = diagnose(src).filter { it.code == 2322 }.sortedBy { it.line }
        assert(ts2322.size >= 2)
        assert(ts2322[1].line!! - ts2322[0].line!! == 41)
        assert(ts2322[1].character == ts2322[0].character)
    }

    @Test
    fun `column matches for the same text near the top and far into the file`() {
        // Reassigning a declared var (not a redeclaration) lets the SAME statement repeat
        // and both fire TS2322. The first is near the top (small position — exercises the
        // line-start[0] = 0 base branch), the second 31 lines down (non-zero line-start).
        // Column = position - lineStart + 1 must be identical for the identical text.
        val src = "let v: number;\n" +
            "v = \"oops\";\n" +
            "// gap\n".repeat(30) +
            "v = \"oops\";"
        val ts2322 = diagnose(src).filter { it.code == 2322 }.sortedBy { it.line }
        assert(ts2322.size >= 2)
        assert(ts2322[1].line!! - ts2322[0].line!! == 31)
        assert(ts2322[1].character == ts2322[0].character)
    }
}
