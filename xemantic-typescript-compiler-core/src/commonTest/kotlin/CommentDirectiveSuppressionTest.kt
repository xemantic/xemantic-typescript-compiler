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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.31) tsc's `// @ts-ignore` / `// @ts-expect-error` comment directives.
 *
 * Every expectation here was read out of `tools/tsgo-7.0.2/lib/tsc` over the
 * same fixture rather than hand-written — including the two that surprise
 * (`@ts-ignoreXYZ` IS a directive, and a directive on an INNER line of a block
 * comment is NOT one).
 *
 * FIXTURE RULE: a fixture may NOT begin with the directive. `parseMultiFileSource`
 * treats a leading colon-less `// @…` line as a HARNESS directive while it is
 * still in the global-directive preamble and DROPS it (`CompilerOptions.kt`), so
 * such a pin would compile a source with no directive in it at all and read as a
 * failure of the feature. Every fixture below opens with a statement.
 */
class CommentDirectiveSuppressionTest {

    @Test
    fun `ts-ignore suppresses the error on the line below it`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-ignore
            const a: number = "x";
            """
        )
        diagnostics should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `ts-expect-error suppresses the error on the line below it`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-expect-error
            const a: number = "x";
            """
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `negative control - an error on an undirected line is still reported`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-ignore
            const a: number = "x";
            const b: number = "y";
            """
        )
        val reported = diagnostics.filter { it.code == 2322 }.map { it.line }
        assert(reported == listOf(4))
    }

    @Test
    fun `an unused ts-expect-error is reported as TS2578 at the comment`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-expect-error
            const a: number = 1;
            """
        )
        assert(diagnostics.size == 1)
        val d = diagnostics[0]
        assert(d.code == 2578)
        assert(d.message == "Unused '@ts-expect-error' directive.")
        assert(d.line == 2)
        assert(d.character == 1)
        assert(d.length == "// @ts-expect-error".length)
    }

    @Test
    fun `a ts-ignore that suppresses nothing is never TS2578`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-ignore
            const a: number = 1;
            """
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `the walk up crosses a blank line`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-ignore

            const a: number = "x";
            """
        )
        diagnostics should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `the walk up crosses a plain slash-slash comment line`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-ignore
            // an ordinary comment
            const a: number = "x";
            """
        )
        diagnostics should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `the walk up STOPS at a code line`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-ignore
            const between = 1;
            const a: number = "x";
            """
        )
        diagnostics should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `the walk up STOPS at a block comment line`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-ignore
            /* an ordinary block comment */
            const a: number = "x";
            """
        )
        diagnostics should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `a directive on the SAME line as the error does not suppress it`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            const a: number = "x"; // @ts-expect-error
            """
        )
        diagnostics should { have(any { it.code == 2322 }) }
        diagnostics should { have(any { it.code == 2578 }) }
    }

    @Test
    fun `a TRAILING directive suppresses the line below it`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            const one = 1; // @ts-ignore
            const a: number = "x";
            """
        )
        diagnostics should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `a one-line block comment is a directive`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            /* @ts-ignore */
            const a: number = "x";
            """
        )
        diagnostics should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `a one-line JSDoc comment is a directive`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            /** @ts-expect-error */
            const a: number = "x";
            """
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `the LAST line of a multi-line block comment is a directive`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            /**
             @ts-expect-error */
            const a: number = "x";
            """
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `an INNER line of a multi-line block comment is NOT a directive`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            /*
             * @ts-expect-error
             */
            const a: number = "x";
            """
        )
        diagnostics should { have(any { it.code == 2322 }) }
        diagnostics should { have(none { it.code == 2578 }) }
    }

    @Test
    fun `two and three slashes are both directives and extra text is allowed`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            //@ts-ignore
            const a: number = "x";
            ///@ts-expect-error
            const b: number = "y";
            //   @ts-ignore   additional commenting
            const c: number = "z";
            """
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `a directive name with a trailing suffix still suppresses - tsgo has no word boundary`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-ignoreXYZ
            const a: number = "x";
            """
        )
        diagnostics should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `one directive suppresses EVERY error on the line below it`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-expect-error
            const a: number = "x", b: number = "y";
            """
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `the fflate shape - a directive suppresses TS2391 for one member only`() {
        val diagnostics = diagnose(
            """
            export class Zip {
              // @ts-ignore
              ondata: (err: unknown, dat: number, final: boolean) => void;
              add(file: number): void;
            }
            """
        )
        val reported = diagnostics.filter { it.code == 2391 }.map { it.line }
        assert(reported == listOf(4))
    }

    @Test
    fun `a syntax error is NOT suppressed - directives filter checker diagnostics only`() {
        val diagnostics = diagnose(
            """
            const zero = 0;
            // @ts-ignore
            const a: number = ;
            """
        )
        diagnostics should { have(any { it.code == 1109 }) }
    }
}
