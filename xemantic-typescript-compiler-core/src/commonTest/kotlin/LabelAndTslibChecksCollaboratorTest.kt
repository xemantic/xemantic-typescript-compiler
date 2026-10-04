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
 * (INV.0) (P18.289) The label family as it lives in `LabelChecks` (TS1114 duplicate label, TS7028 unused
 * label) and the tslib emit-helper family as it lives in `TslibHelperChecks` (TS2354 / TS2343) after the
 * extraction. Every expected row is tsgo 7.0.2's (cells under `build/bench/p18289-agent/matrix/`,
 * 1-based column); all eight cells there read byte-identical on both arms of the move.
 */
class LabelAndTslibChecksCollaboratorTest {

    private fun rows(source: String, directives: String): List<String> =
        diagnose(source, directives = directives)
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `a label redeclared inside its own body is TS1114`() {
        val r = rows(
            """
            export {};
            outer: for (let i = 0; i < 2; i++) {
                outer: for (let j = 0; j < 2; j++) {
                    break outer;
                }
            }
            """,
            "// @strict: true\n// @target: es2020\n// @module: esnext",
        )
        assert(r == listOf("3:5 TS1114 Duplicate label 'outer'."))
    }

    @Test
    fun `an unused label is TS7028 under allowUnusedLabels false and a used one is silent`() {
        val r = rows(
            """
            export {};
            unused: for (let i = 0; i < 2; i++) {
                used: while (true) { break used; }
            }
            """,
            "// @strict: true\n// @target: es2020\n// @module: esnext\n// @allowUnusedLabels: false",
        )
        assert(r == listOf("2:1 TS7028 Unused label."))
    }

    @Test
    fun `a label referenced only by an inner continue keeps the outer one and flags the inner`() {
        val r = rows(
            """
            export {};
            a: for (let i = 0; i < 2; i++) { b: for (;;) { continue a; } }
            """,
            "// @strict: true\n// @target: es2020\n// @module: esnext\n// @allowUnusedLabels: false",
        )
        assert(r == listOf("2:34 TS7028 Unused label."))
    }

    @Test
    fun `an async function under importHelpers with no tslib is TS2354`() {
        val r = rows(
            """
            export {};
            async function f() { await 1; }
            """,
            "// @strict: true\n// @target: es2016\n// @module: commonjs\n// @importHelpers: true",
        )
        assert(r == listOf("2:16 TS2354 This syntax requires an imported helper but module 'tslib' cannot be found."))
    }

    @Test
    fun `negative control - labels are silent under the default and no helper is needed at es2020`() {
        val r = rows(
            """
            export {};
            unused: for (let i = 0; i < 2; i++) {}
            async function f() { await 1; }
            """,
            "// @strict: true\n// @target: es2020\n// @module: commonjs\n// @importHelpers: true",
        )
        assert(r.isEmpty())
    }
}
