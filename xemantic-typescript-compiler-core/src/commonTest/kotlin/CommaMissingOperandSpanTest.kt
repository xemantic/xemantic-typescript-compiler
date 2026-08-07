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
 * Local corner-case tests for the TS2695 span of a comma operator whose LEFT
 * operand is missing (`(, ANY)`).
 *
 * Round 695, adopting the `expressions/commaOperator` conformance category.
 * Our recovery synthesizes an empty-text Identifier anchored at the OFFENDING
 * token, but tsc anchors its missing node at the FULL START — the end of the
 * previous token, before trivia — and gives it no width. Both halves matter:
 *
 *  - the position is only observable when trivia separates `(` from `,`
 *    (`( , )`), which is why that shape is pinned separately from `(, x)`;
 *  - the zero LENGTH is what orders TS2695 BEFORE the same-position TS1109,
 *    since the diagnostic comparator is start -> length -> code (2695 > 1109,
 *    so equal lengths would put the parser error first and mismatch tsc).
 *
 * The corpus case pins one file's worth of these; these pin the invariant, the
 * trivia case, and the negative control that a PRESENT left operand still
 * squiggles its own text.
 */
class CommaMissingOperandSpanTest {

    private val directives = "// @allowUnreachableCode: false"

    private fun commaDiags(source: String): List<Diagnostic> =
        diagnose(source, directives).filter { it.code == 2695 }

    @Test
    fun `missing left operand puts TS2695 at the previous token's end with no width`() {
        val diags = commaDiags(
            """
            declare var ANY: any;
            (, ANY);
            """
        )
        assert(diags.size == 1)
        // `(` occupies column 1, so the missing operand's full start is column 2.
        assert(diags[0].character == 2)
        assert(diags[0].length == 0)
    }

    @Test
    fun `trivia between the paren and the comma does not move the TS2695 anchor`() {
        val diags = commaDiags(
            """
            declare var ANY: any;
            (   , ANY);
            """
        )
        assert(diags.size == 1)
        // Still the `(`'s end (column 2) — NOT the `,` at column 5.
        assert(diags[0].character == 2)
        assert(diags[0].length == 0)
    }

    @Test
    fun `the zero-width TS2695 precedes the same-position TS1109`() {
        val diags = diagnose(
            """
            declare var ANY: any;
            (, ANY);
            """,
            directives,
        ).filter { it.code == 2695 || it.code == 1109 }
        val unused = diags.single { it.code == 2695 }
        val expected = diags.single { it.code == 1109 }
        // Same start; the shorter span sorts first, which is the ordering tsc emits.
        assert(unused.start == expected.start)
        assert(unused.length == 0)
        assert(expected.length == 1)
    }

    @Test
    fun `negative control - a present left operand still squiggles its own text`() {
        val diags = commaDiags(
            """
            declare var ANY: any;
            declare var OBJECT: Object;
            (OBJECT, ANY);
            """
        )
        assert(diags.size == 1)
        assert(diags[0].character == 2)
        assert(diags[0].length == 6)
    }

    @Test
    fun `negative control - a missing left operand in a side-effect-free comma only`() {
        // A left operand WITH side effects draws no TS2695 at all; the missing-operand
        // branch must not turn that into an emission.
        val diags = commaDiags(
            """
            declare var ANY: any;
            declare function go(): void;
            (go(), ANY);
            """
        )
        assert(diags.isEmpty())
    }
}
