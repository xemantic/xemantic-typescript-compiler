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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

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

    private val prelude = """
        interface Node2 { kind: number }
        interface Identifier2 extends Node2 { text: string }
        interface DiagnosticMessage { key: string }
        declare const Diagnostics: { Identifier_expected: DiagnosticMessage };
    """.trimIndent()

    @Test
    fun `later matching overload is selected`() {
        diagnose(
            prelude + """

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
            """
        ) should {
            have(none { it.code == 2345 || it.code == 2769 })
        }
    }

    @Test
    fun `first overload still serves when its args match`() {
        // literal false matches the first overload's pinned `false` param
        diagnose(
            prelude + """

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
            """
        ) should {
            have(none { it.code == 2345 || it.code == 2769 })
        }
    }

    @Test
    fun `constraint-filtered candidate reports the arg error not TS2344`() {
        // Applicability filters by TYPE-ARG CONSTRAINT first (tsc): `foo1<Date>("")`
        // disqualifies the `T extends Number` overload, so the arg failure reports
        // TS2345 'string' ≁ 'Date' against the `T extends Date` one — NOT a TS2344
        // against the Number constraint (typeArgumentConstraintResolution1's pin,
        // which the unfiltered first cut broke).
        diagnose(
            """
            function foo1<T extends Date>(test: T);
            function foo1<T extends Number>(test: string);
            function foo1<T extends String>(test: any) { }
            foo1<Date>("");
            """,
            directives = "",
        ) should {
            have(any { it.code == 2345 && "'Date'" in it.message })
            have(none { it.code == 2344 })
        }
    }

    @Test
    fun `negative control - args matching no overload still report`() {
        // reports against the first candidate, the pre-existing error-reporting target
        diagnose(
            prelude + """

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
            """
        ) should {
            have(any { it.code == 2345 || it.code == 2769 })
        }
    }
}
