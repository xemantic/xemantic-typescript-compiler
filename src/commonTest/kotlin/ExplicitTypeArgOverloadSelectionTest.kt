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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 436d: an explicit-type-arg call against MULTIPLE arity-accommodating
 * generic overloads selects the first whose ARGUMENTS match (tsc overload
 * resolution) — not blindly the first overload. tsc parser.ts's
 * `createMissingNode<Identifier>(kind, /*reportAtCurrentPosition*/ true, msg)`
 * must select the 2nd overload; the 1st pins reportAtCurrentPosition to the
 * literal `false` and FP'd TS2345 'true' ≁ 'false' ×5. The namespace container
 * is load-bearing for the repro (a top-level overload cluster resolves through
 * a different path).
 */
class ExplicitTypeArgOverloadSelectionTest {

    private fun diags(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 2345 || it.code == 2769 }

    private val prelude = """
        interface Node2 { kind: number }
        interface Identifier2 extends Node2 { text: string }
        interface DiagnosticMessage { key: string }
        declare const Diagnostics: { Identifier_expected: DiagnosticMessage };
    """.trimIndent()

    @Test fun laterMatchingOverloadIsSelected() {
        val d = diags(
            prelude + "\n" +
                """
                namespace Parser {
                    function createMissingNode<T extends Node2>(kind: T["kind"], reportAtCurrentPosition: false, diagnosticMessage?: DiagnosticMessage): T;
                    function createMissingNode<T extends Node2>(kind: T["kind"], reportAtCurrentPosition: boolean, diagnosticMessage: DiagnosticMessage): T;
                    function createMissingNode<T extends Node2>(kind: T["kind"], reportAtCurrentPosition: boolean, diagnosticMessage?: DiagnosticMessage): T {
                        return { kind } as T;
                    }
                    export function parse(): Identifier2 {
                        return createMissingNode<Identifier2>(1, /*rACP*/ true, Diagnostics.Identifier_expected);
                    }
                }
                """.trimIndent()
        )
        assertTrue(d.isEmpty(), "expected no TS2345/TS2769, got: $d")
    }

    /** The FIRST overload still serves when its args match (literal false). */
    @Test fun firstOverloadStillSelectedWhenMatching() {
        val d = diags(
            prelude + "\n" +
                """
                namespace Parser {
                    function createMissingNode<T extends Node2>(kind: T["kind"], reportAtCurrentPosition: false, diagnosticMessage?: DiagnosticMessage): T;
                    function createMissingNode<T extends Node2>(kind: T["kind"], reportAtCurrentPosition: boolean, diagnosticMessage: DiagnosticMessage): T;
                    function createMissingNode<T extends Node2>(kind: T["kind"], reportAtCurrentPosition: boolean, diagnosticMessage?: DiagnosticMessage): T {
                        return { kind } as T;
                    }
                    export function parse(): Identifier2 {
                        return createMissingNode<Identifier2>(1, /*rACP*/ false);
                    }
                }
                """.trimIndent()
        )
        assertTrue(d.isEmpty(), "expected no TS2345/TS2769, got: $d")
    }

    /** Applicability filters by TYPE-ARG CONSTRAINT first (tsc): `foo1<Date>("")`
     *  disqualifies the `T extends Number` overload, so the arg failure reports
     *  TS2345 'string' ≁ 'Date' against the `T extends Date` one — NOT a TS2344
     *  against the Number constraint (typeArgumentConstraintResolution1's pin,
     *  which the unfiltered first cut broke). */
    @Test fun constraintFilteredCandidateReportsArgError() {
        val all = TypeScriptCompiler().compile(
            """
            function foo1<T extends Date>(test: T);
            function foo1<T extends Number>(test: string);
            function foo1<T extends String>(test: any) { }
            foo1<Date>("");
            """.trimIndent(),
            "t.ts",
        ).diagnostics
        assertTrue(all.any { it.code == 2345 && "'Date'" in it.message },
            "expected TS2345 'string' vs 'Date', got: $all")
        assertTrue(all.none { it.code == 2344 },
            "expected NO TS2344 (constraint-failing overload is not applicable), got: $all")
    }

    /** NEGATIVE control: args matching NO overload still report (against the
     *  first candidate, the pre-existing error-reporting target). */
    @Test fun noMatchingOverloadStillReports() {
        val d = diags(
            prelude + "\n" +
                """
                namespace Parser {
                    function pick<T extends Node2>(kind: T["kind"], flag: false): T;
                    function pick<T extends Node2>(kind: T["kind"], flag: true): T;
                    function pick<T extends Node2>(kind: T["kind"], flag: boolean): T {
                        return { kind } as T;
                    }
                    export function parse(): Identifier2 {
                        return pick<Identifier2>(1, "nope");
                    }
                }
                """.trimIndent()
        )
        assertTrue(d.isNotEmpty(), "expected a TS2345 against the first overload, got none")
    }
}
