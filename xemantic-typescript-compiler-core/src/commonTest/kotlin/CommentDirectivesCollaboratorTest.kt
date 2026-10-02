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
 * (INV.0) (P18.270) The `@ts-ignore` / `@ts-expect-error` COMMENT-DIRECTIVE family as it lives
 * in `CommentDirectives` after the extraction: the per-file directive scan (the `//` and
 * block-comment regexes, the string-aware opener search, (CHK.205)'s JSDoc code-fence rule),
 * the walk up from a diagnostic, and the funnel filter `applyTsCommentDirectives` that
 * `Checker.getDiagnostics` calls (suppression, then TS2578 for an unused `@ts-expect-error`,
 * skipping a plain-JS file per (CHK.202)).
 *
 * Every row is tsgo 7.0.2's (cells under `build/bench/p18270-agent/matrix/`, 1-based column);
 * all nine cells agree on both arms of the move. `CommentDirectiveSuppressionTest` and
 * `DocCommentDirectiveTest` carry the per-rule pins; these are the cross-rule shapes.
 */
class CommentDirectivesCollaboratorTest {

    private fun rows(source: String, directives: String = "// @strict: true\n// @target: es2020", fileName: String = "t.ts"): List<String> =
        diagnose(source, directives = directives, fileName = fileName)
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `ts-ignore suppresses only the line below it`() {
        val r = rows(
            """
            export {};
            // @ts-ignore
            const a: number = "s";
            const b: number = "t";
            """
        )
        assert(r == listOf("4:7 TS2322 Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `an unused ts-expect-error is TS2578 at the comment`() {
        val r = rows(
            """
            export {};
            const ok = 1;
            // @ts-expect-error
            const c: number = ok;
            """
        )
        assert(r == listOf("3:1 TS2578 Unused '@ts-expect-error' directive."))
    }

    @Test
    fun `a slash-slash line inside a JSDoc block is block text and not a directive`() {
        val r = rows(
            """
            export {};
            /**
             * // @ts-ignore
             */
            const d: number = "s";
            """
        )
        assert(r == listOf("5:7 TS2322 Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `the last line of a multi-line block comment is a used directive`() {
        val r = rows(
            """
            export {};
            /* first line
               @ts-expect-error */
            const e: number = "s";
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a directive spelled in a string literal is not a directive`() {
        val r = rows(
            """
            export {};
            const s = "// @ts-ignore";
            const f: number = "s";
            """
        )
        assert(r == listOf("3:7 TS2322 Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `a directive after a closed block comment is anchored at its own slashes`() {
        val r = rows(
            """
            export {};
            /* a */ // @ts-expect-error
            const g = 1;
            """
        )
        assert(r == listOf("2:9 TS2578 Unused '@ts-expect-error' directive."))
    }

    @Test
    fun `a JSDoc block whose code-fence line and last line both spell the directive anchors TS2578 at the last line`() {
        val r = rows(
            """
            export {};
            /**
             * example:
             * // @ts-expect-error
             * @ts-expect-error */
            const h = 1;
            """
        )
        assert(r == listOf("5:1 TS2578 Unused '@ts-expect-error' directive."))
    }

    @Test
    fun `a plain JS file honours no directive and reports none unused`() {
        val r = rows(
            """
            const z = 0;
            // @ts-expect-error
            const j = 1;
            // @ts-ignore
            const k = undefinedName;
            """,
            directives = "// @allowJs: true",
            fileName = "t.js",
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a checked JS file reports an unused ts-expect-error and honours a triple-slash ts-ignore`() {
        val r = rows(
            """
            const z = 0;
            // @ts-expect-error
            const m = 1;
            /// @ts-ignore
            const n = undefinedName;
            """,
            directives = "// @allowJs: true\n// @checkJs: true",
            fileName = "t.js",
        )
        assert(r == listOf("2:1 TS2578 Unused '@ts-expect-error' directive."))
    }
}
