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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * M1.12 (self-compile burn-down, round 415): TS2366 "Function lacks ending return statement"
 * Pattern C2 — an exhaustive enum `switch` with NO `default` and every case returning is
 * TERMINATING (tsc narrows the discriminant to `never` after all cases, so the endpoint is
 * unreachable). tsc's own source: `getCategoryFormat(category: DiagnosticCategory)`,
 * `getNonAssignmentOperatorForCompoundAssignment(kind: CompoundAssignmentOperator)`.
 *
 * FP-safe by construction: it only claims exhaustive when it can PROVE the discriminant's full
 * value set is covered. Since `.errors.txt` error-baseline tests are disabled, the corpus is a
 * weak gate for TS2366 false-negatives — the negative controls below are the real firewall.
 */
class ExhaustiveEnumSwitchTerminationTest {

    @Test
    fun `exhaustive enum switch on a param - no TS2366`() {
        diagnose(
            """
            enum Category { Error, Warning, Suggestion, Message }
            function fmt(category: Category): string {
                switch (category) {
                    case Category.Error: return "red";
                    case Category.Warning: return "yellow";
                    case Category.Suggestion: return "info";
                    case Category.Message: return "blue";
                }
            }
            """,
        ) should {
            have(none { it.code == 2366 || it.code == 7030 || it.code == 2355 })
        }
    }

    @Test
    fun `exhaustive enum-plus-undefined switch with case undefined - no TS2366`() {
        diagnose(
            """
            enum NewLine { Crlf, Lf }
            function nl(kind: NewLine | undefined): string {
                switch (kind) {
                    case NewLine.Crlf: return "\r\n";
                    case NewLine.Lf:
                    case undefined:
                        return "\n";
                }
            }
            """,
        ) should {
            have(none { it.code == 2366 || it.code == 7030 || it.code == 2355 })
        }
    }

    @Test
    fun `exhaustive type-alias-union of enum members - no TS2366`() {
        // Mirrors `CompoundAssignmentOperator = SyntaxKind.PlusEqualsToken | ...`.
        diagnose(
            """
            enum Tok { PlusEq, MinusEq, StarEq, Other }
            type CompoundOp = Tok.PlusEq | Tok.MinusEq | Tok.StarEq;
            function op(kind: CompoundOp): string {
                switch (kind) {
                    case Tok.PlusEq: return "+";
                    case Tok.MinusEq: return "-";
                    case Tok.StarEq: return "*";
                }
            }
            """,
        ) should {
            have(none { it.code == 2366 || it.code == 7030 || it.code == 2355 })
        }
    }

    @Test
    fun `missing an enum member STILL fires - negative control`() {
        diagnose(
            """
            enum Category { Error, Warning, Suggestion, Message }
            function fmt(category: Category): string {
                switch (category) {
                    case Category.Error: return "red";
                    case Category.Warning: return "yellow";
                    case Category.Suggestion: return "info";
                }
            }
            """,
        ) should {
            have(any { it.code == 2366 })
        }
    }

    @Test
    fun `enum-plus-undefined WITHOUT case undefined STILL fires - negative control`() {
        diagnose(
            """
            enum NewLine { Crlf, Lf }
            function nl(kind: NewLine | undefined): string {
                switch (kind) {
                    case NewLine.Crlf: return "\r\n";
                    case NewLine.Lf: return "\n";
                }
            }
            """,
        ) should {
            have(any { it.code == 2366 })
        }
    }

    @Test
    fun `a non-returning case body STILL fires - negative control`() {
        diagnose(
            """
            enum Category { Error, Warning }
            function fmt(category: Category): string {
                switch (category) {
                    case Category.Error: return "red";
                    case Category.Warning: break;
                }
            }
            """,
        ) should {
            have(any { it.code == 2366 })
        }
    }
}
