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
 * (CHK.205) A `//` inside a block comment opens nothing: a JSDoc code-fence example
 * spelling `// @ts-expect-error` on an inner line is not a directive (type-fest, 85 rows),
 * while the same text on the block's LAST line is one, exactly as any other block line.
 *
 * Every expected row was read out of `tools/tsgo-7.0.2/lib/tsc` over the same fixture.
 * The fixture opens with a block comment, never with a `// @…` line (which the harness
 * would read as a directive of its own — see `CommentDirectiveSuppressionTest`).
 */
class DocCommentDirectiveTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    private val matrix = """
            /**
            Example:
            // @ts-expect-error
            */
            const a1: number = "s";
            /**
            Example:
            // @ts-expect-error
            */
            const a2 = 1;
            /**
             * @ts-expect-error
             */
            const a3: number = "s";
            /**
             * x
             * @ts-expect-error */
            const a4: number = "s";
            /* @ts-expect-error */
            const a5: number = "s";
            /* @ts-expect-error */
            const a6 = 1;
            /** @ts-expect-error */
            const a7: number = "s";
            /*
            // @ts-expect-error */
            const a8: number = "s";
            /*
            // @ts-expect-error */
            const a9 = 1;
            /* a */ // @ts-expect-error
            const b1: number = "s";
            /* a */ // @ts-expect-error
            const b2 = 1;
            const s = "src/**/*.ts";
            // @ts-expect-error
            const b3: number = "s";
            // @ts-expect-error
            const b4 = 1;
            /**
            ```
            // @ts-ignore
            ```
            */
            const b5: number = "s";
            /**
             * // @ts-expect-error
             */
            const b6 = 1;
    """

    @Test
    fun `directive shapes inside and at the end of block comments - the tsgo rows`() {
        val expected = listOf(
            "5:7 TS2322 Type 'string' is not assignable to type 'number'.",
            "14:7 TS2322 Type 'string' is not assignable to type 'number'.",
            "21:1 TS2578 Unused '@ts-expect-error' directive.",
            "29:1 TS2578 Unused '@ts-expect-error' directive.",
            "33:9 TS2578 Unused '@ts-expect-error' directive.",
            "38:1 TS2578 Unused '@ts-expect-error' directive.",
            "45:7 TS2322 Type 'string' is not assignable to type 'number'.",
        ).sorted()
        assert(rows(matrix) == expected)
    }

    @Test
    fun `a code-fence example in a doc comment is not an unused directive`() {
        val actual = rows(
            """
            /**
            Example:
            ```
            // @ts-expect-error
            const x: number = "s";
            ```
            */
            export const y = 1;
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - a line comment after a string holding a glob is still a directive`() {
        val actual = rows(
            """
            const glob = "src/**/*.ts";
            // @ts-expect-error
            const n: number = "s";
            // @ts-expect-error
            const m = 1;
            """
        )
        assert(actual == listOf("4:1 TS2578 Unused '@ts-expect-error' directive."))
    }
}
