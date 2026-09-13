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
 * (LEGACY.0b) step 3, family F0 — TS1127 `Invalid character.` spans ONE character.
 *
 * tsc 6 reported four of its TS1127 positions with a ZERO-width span, so its baselines
 * carry a blank squiggle line under them. TypeScript 7's scanner reports the code through
 * `errorAt(diagnostics.Invalid_character, …)` with an explicit width — 1 at the incomplete
 * escape, the rune size at a genuinely unscannable character — and every one of its ~8,800
 * checked-in baselines squiggles at least one `~`.
 *
 * The four sites this closes are all the ones that carried `overrideLength = 0`: an
 * identifier whose first character comes from an escape that does not decode to an
 * identifier start, the var-declaration-list recovery that treats a stray `\` as a comma,
 * and the two `parseIdentifier` / `parseIdentifierName` escape checks. The sites that
 * already computed a width are untouched, which is why the ACTIVE baselines that expect a
 * one-character TS1127 today (`constructorWithIncompleteTypeAnnotation`,
 * `unicodeIdentifierName2`, `parseErrorInHeritageClause1`, `TransportStream`) are a control
 * rather than a risk — a census of every active `.errors.txt` found NO baseline expecting a
 * zero-width TS1127.
 *
 * Every expectation was read off `tools/tsgo-7.0.2/lib/tsc --pretty`. Baselines closed:
 * `invalidUnicodeEscapeSequance`, `invalidUnicodeEscapeSequance2`,
 * `invalidUnicodeEscapeSequance3`, `invalidUnicodeEscapeSequance4`,
 * `slashBeforeVariableDeclaration1`. `unicodeEscapesInNames02(target=es2015)` stays pending
 * for a different mechanism the widening UNCOVERED: its span and column are now correct and
 * the squiggle PRINTS one column right, because the annotated source line holds an astral
 * character and the reference's baseline formatter pads by codepoint where ours pads by
 * UTF-16 unit.
 */
class TsgoInvalidCharacterSpanTest {

    private fun invalidCharacters(source: String): List<Diagnostic> =
        diagnose(source, directives = "// @target: es2015").filter { it.code == 1127 }

    /** `var arg\u003` — an escape with three hex digits. tsgo `(1,8)` width 1. */
    @Test
    fun `an incomplete unicode escape in an identifier spans one character`() {
        val row = invalidCharacters("var arg\\u003").single()
        assert(row.message == "Invalid character.")
        assert(row.line == 1)
        assert(row.character == 8)
        assert(row.length == 1)
    }

    /** `var arg\uxxxx` — an escape with non-hex digits. tsgo `(1,8)` width 1. */
    @Test
    fun `a non-hex unicode escape in an identifier spans one character`() {
        val row = invalidCharacters("var arg\\uxxxx").single()
        assert(row.line == 1)
        assert(row.character == 8)
        assert(row.length == 1)
    }

    /** `a\u` — a truncated escape in expression position. tsgo `(1,2)` width 1. */
    @Test
    fun `a truncated escape in expression position spans one character`() {
        val row = invalidCharacters("a\\u").single()
        assert(row.line == 1)
        assert(row.character == 2)
        assert(row.length == 1)
    }

    /**
     * `var <esc>a;` where the escape decodes to `1`, which is not an identifier START, so
     * the
     * diagnostic comes from the scan-time `invalidEscapeIdentStartPos` site rather than from
     * the two `parseIdentifier*` ones. tsgo `(2,5)` width 1. The first line is the control:
     * A non-initial escape of the same character is legal and reports nothing.
     */
    @Test
    fun `an escape that decodes to a non-identifier start spans one character`() {
        // The escape is BUILT rather than written: a literal valid escape in this file
        // would be decoded before it ever reached the fixture.
        val esc = "\\" + "u0031"
        val rows = invalidCharacters("var a$esc;\nvar ${esc}a;")
        val row = rows.single()
        assert(row.line == 2)
        assert(row.character == 5)
        assert(row.length == 1)
    }

    /** `\ declare var v;` — a bare backslash before a statement. tsgo `(1,1)` width 1. */
    @Test
    fun `a bare backslash before a declaration spans one character`() {
        val row = invalidCharacters("\\ declare var v;").single()
        assert(row.line == 1)
        assert(row.character == 1)
        assert(row.length == 1)
    }

    /**
     * Negative control: a VALID unicode escape in an identifier is silent, so the four
     * widened sites still discriminate rather than firing on every escape.
     */
    @Test
    fun `negative control - a valid unicode escape reports no TS1127`() {
        assert(invalidCharacters("var a\\u0031 = 1; a1;").isEmpty())
    }
}
