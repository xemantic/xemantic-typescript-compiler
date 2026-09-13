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
 * (LEGACY.0b) step 2, family F9 — diagnostics whose CODE and SPAN already matched
 * TypeScript 7 and whose TEXT did not.
 *
 * Every expectation here is the text `tools/tsgo-7.0.2/lib/tsc` produces, read off
 * tsgo's own checked-in baselines under
 * `typescript-go-repo/testdata/baselines/reference/submodule/compiler`. TypeScript 6's
 * wording is recorded in each test's KDoc so a future reader can tell a deliberate
 * TypeScript 7 change from a regression.
 */
class TsgoMessageWordingTest {

    /**
     * TS5090. tsc 6: `Non-relative paths are not allowed when 'baseUrl' is not set. Did
     * you forget a leading './'?` — TypeScript 7 removed `baseUrl`, so the clause naming
     * it went with it. Baselines: `pathsValidation5`,
     * `pathMappingBasedModuleResolution1_node`.
     */
    @Test
    fun `a non-relative paths substitution reports TS5090 without naming baseUrl`() {
        val diagnostics = diagnose(
            """
            // @Filename: tsconfig.json
            {
              "compilerOptions": {
                  "paths": {
                    "@blah": ["blah"]
                  }
              }
            }

            // @filename: src/main.ts
            export const x = 1;
            """,
            directives = "// @module: commonjs",
        )
        diagnostics should {
            have(any {
                it.code == 5090 &&
                    it.message == "Non-relative paths are not allowed. Did you forget a leading './'?"
            })
        }
    }

    /**
     * TS5090 negative control — a RELATIVE substitution is accepted, so the rule still
     * discriminates rather than firing on every `paths` entry.
     */
    @Test
    fun `negative control - a relative paths substitution reports no TS5090`() {
        val diagnostics = diagnose(
            """
            // @Filename: tsconfig.json
            {
              "compilerOptions": {
                  "paths": {
                    "@blah": ["./blah"]
                  }
              }
            }

            // @filename: src/main.ts
            export const x = 1;
            """,
            directives = "// @module: commonjs",
        )
        diagnostics should { have(none { it.code == 5090 }) }
    }

    /**
     * TS5074. tsc 6: `Option '--incremental' can only be specified using tsconfig,
     * emitting to single file or when option '--tsBuildInfoFile' is specified.`
     * Baseline: `incrementalInvalid`.
     */
    @Test
    fun `incremental without a config file reports the TypeScript 7 TS5074 wording`() {
        val diagnostics = diagnose(
            """
            const x = 10;
            """,
            directives = "// @target: es2015\n// @incremental: true",
        )
        diagnostics should {
            have(any {
                it.code == 5074 &&
                    it.message == "Option '--incremental' is only valid with a known " +
                    "configuration file (like 'tsconfig.json') or when '--tsBuildInfoFile' " +
                    "is explicitly provided."
            })
        }
    }

    /**
     * TypeScript 7 has no `Call signature return types '{0}' and '{1}' are incompatible.`
     * message at all — ZERO tsgo baselines carry it against twelve tsc ones — so a
     * return-type mismatch between two function types is elaborated with the ordinary
     * assignability line. Baselines: `nestedCallbackErrorNotFlattened`,
     * `typeParameterArgumentEquivalence5`, `promisePermutations` and its two siblings,
     * `genericCallAtYieldExpressionInGenericCall1`.
     */
    @Test
    fun `a nested function return mismatch elaborates with an assignability line`() {
        val diagnostics = diagnose(
            """
            type Cb<T> = { noAlias: () => T }["noAlias"];
            declare const src: Cb<Cb<number>>;
            let tgt: Cb<Cb<string>>;
            tgt = src;
            """,
        )
        val chains = diagnostics.filter { it.code == 2322 }.flatMap { it.messageChain }
        assert(chains.none { it.contains("Call signature return types") })
        assert(chains.any { it.trim() == "Type '() => number' is not assignable to type '() => string'." })
    }

    /**
     * Negative control for the same elaborator — a PARAMETER mismatch keeps its own
     * `Types of parameters ... are incompatible.` line, which TypeScript 7 did not change.
     */
    @Test
    fun `negative control - a parameter mismatch keeps the parameters chain line`() {
        val diagnostics = diagnose(
            """
            declare const src: (a: number) => void;
            let tgt: (a: string) => void;
            tgt = src;
            """,
        )
        val chains = diagnostics.filter { it.code == 2322 }.flatMap { it.messageChain }
        assert(chains.any { it.trim() == "Types of parameters 'a' and 'a' are incompatible." })
    }

    /**
     * The `--pretty` baseline rendering of RELATED information. TypeScript 7 puts the
     * message on the location line and opens EVERY block with a blank line; tsc 6 put
     * the message below the code frame and opened only the first block with a blank.
     * Baselines: `deeplyNestedAssignabilityIssue`, `duplicateIdentifierRelatedSpans3`,
     * `duplicateIdentifierRelatedSpans5`, `duplicateIdentifierRelatedSpans6`,
     * `esModuleInteropPrettyErrorRelatedInformation`.
     */
    @Test
    fun `pretty related information renders its message on the location line`() {
        val related = Diagnostic(
            message = "'a' is declared here.",
            category = DiagnosticCategory.Message,
            code = 2728,
            fileName = "t.ts",
            line = 1,
            character = 5,
            length = 1,
        )
        val rendered = formatErrorBaseline(
            diagnostics = listOf(
                Diagnostic(
                    message = "Property 'a' is missing.",
                    category = DiagnosticCategory.Error,
                    code = 2741,
                    fileName = "t.ts",
                    line = 2,
                    character = 1,
                    length = 2,
                    relatedInformation = listOf(related, related.copy(line = 3, character = 1)),
                ),
            ),
            sourceFiles = listOf("t.ts" to "    a: number;\nbb\ncc\n"),
            pretty = true,
        )
        assert(rendered.contains(" - 'a' is declared here.\r\n"))
        // tsc 6 rendered the message on its own line, indented four spaces, BELOW the
        // code frame; that line must be gone.
        assert(!rendered.contains("\r\n    'a' is declared here.\r\n"))
        // A blank line opens each of the two related blocks (so the rendered text splits
        // into three parts around the two openers).
        assert(rendered.split("\r\n\r\n  ").size == 3)
    }

    /**
     * TypeScript 7 separates consecutive pretty diagnostics with a blank line
     * (`FormatDiagnosticsWithColorAndContext`'s `if i > 0` newline); tsc 6 left none.
     * Baselines: `deeplyNestedAssignabilityIssue`, the `duplicateIdentifierRelatedSpans`
     * family, `manyCompilerErrorsInTheTwoFiles`.
     */
    @Test
    fun `pretty output separates consecutive diagnostics with a blank line`() {
        fun at(line: Int) = Diagnostic(
            message = "Boom.",
            category = DiagnosticCategory.Error,
            code = 2304,
            fileName = "t.ts",
            line = line,
            character = 1,
            length = 2,
        )
        val rendered = formatErrorBaseline(
            diagnostics = listOf(at(1), at(2)),
            sourceFiles = listOf("t.ts" to "aa\nbb\n"),
            pretty = true,
        )
        // Squiggle of the first diagnostic, a blank line, then the second header.
        assert(rendered.contains("\u001b[0m\r\n\r\n\u001b[96mt.ts\u001b[0m:\u001b[93m2\u001b[0m"))
    }
}
