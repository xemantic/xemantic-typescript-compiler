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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * EP.2h (round 678): consecutive `//` comments in an INNER position must not be
 * separated by a blank line.
 *
 * `emitInnerComments` writes a newline after a `//` comment (it terminates its
 * own line) and then the NEXT comment wrote a second newline for its
 * `hasPrecedingNewLine` — so every pair of consecutive line comments gained a
 * blank line between them. The archetype is a two-line comment block before an
 * `else if`, which tsc keeps adjacent; it accounted for 32 hunks on the compiler
 * profile (checker.js 23, utilities.js 5, emitter.js 4).
 *
 * Verified byte-identical against reference tsc 6.0.3 on the repro.
 */
class InnerCommentBlankLineTest {

    private fun emit(@Language("typescript") src: String): String =
        TypeScriptCompiler().compile("// @target: es2020\n" + src.trimIndent()).javascript
            ?: error("no js")

    /** Blank lines that sit between two `//` comment lines. */
    private fun blanksBetweenComments(js: String): Int {
        val lines = js.split("\n")
        var n = 0
        for (i in 1 until lines.size - 1) {
            if (lines[i].isBlank() &&
                lines[i - 1].trim().startsWith("//") &&
                lines[i + 1].trim().startsWith("//")
            ) n++
        }
        return n
    }

    @Test
    fun `a two-line comment block before an else-if stays adjacent`() {
        val js = emit(
            """
            export function f(x: number): number {
                if (x === 1) {
                    return 1;
                }
                // first comment line
                // second comment line
                else if (x === 2) {
                    return 2;
                }
                return 0;
            }
            """
        )
        assert(blanksBetweenComments(js) == 0)
        assert("// first comment line\n    // second comment line" in js)
    }

    @Test
    fun `a THREE-line comment block before an else stays adjacent`() {
        val js = emit(
            """
            export function f(x: number): number {
                if (x === 1) {
                    return 1;
                }
                // one
                // two
                // three
                else {
                    return 2;
                }
            }
            """
        )
        assert(blanksBetweenComments(js) == 0)
    }

    @Test
    fun `the comments themselves all survive`() {
        val js = emit(
            """
            export function f(x: number): number {
                if (x === 1) {
                    return 1;
                }
                // alpha
                // beta
                else {
                    return 2;
                }
            }
            """
        )
        assert("// alpha" in js)
        assert("// beta" in js)
    }

    @Test
    fun `a block comment followed by a line comment keeps its own line break`() {
        val js = emit(
            """
            export function f(x: number): number {
                if (x === 1) {
                    return 1;
                }
                /* block */
                // line
                else {
                    return 2;
                }
            }
            """
        )
        assert("/* block */" in js)
        assert("// line" in js)
        // The two must not collapse onto one line.
        assert("/* block */ // line" !in js)
    }

    @Test
    fun `a single comment before else is unchanged`() {
        val js = emit(
            """
            export function f(x: number): number {
                if (x === 1) {
                    return 1;
                }
                // only one
                else {
                    return 2;
                }
            }
            """
        )
        assert("// only one" in js)
        assert(blanksBetweenComments(js) == 0)
    }

    @Test
    fun `a blank line between the comments IN SOURCE is collapsed - as tsc does`() {
        // The boundary this fix defines: we no longer emit a blank between two
        // line comments even when the source had one. Verified byte-identical
        // against reference tsc 6.0.3, which collapses it the same way — so the
        // collapse is faithful, not merely convenient.
        val js = emit(
            """
            export function f(x: number): number {
                if (x === 1) {
                    return 1;
                }
                // first

                // second
                else {
                    return 2;
                }
            }
            """
        )
        assert(blanksBetweenComments(js) == 0)
        assert("// first" in js)
        assert("// second" in js)
    }
}
