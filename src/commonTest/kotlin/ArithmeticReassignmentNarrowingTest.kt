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
 * Round 453 (M3.4): the arithmetic pass now consults flow narrowing for a bare
 * reference operand (Identifier / PropertyAccess), so a `number | undefined`
 * reference proven non-nullish by a CROSS-STATEMENT reassignment or an enclosing
 * guard is a valid arithmetic operand.
 *
 * tsc's own sources trip this:
 *  - parser.ts `parseJSDocCommentWorker(start = 0, length: number | undefined)`:
 *    `length = end - start; scanner.scanRange(start + 3, length - 5, ...)` — the
 *    reassignment's arithmetic RHS proves `length` non-nullish.
 *  - checker.ts `nodeBuilder`: `flags = flags || NodeBuilderFlags.None; flags & X`
 *    — the `||`-with-enum-member default proves `flags` non-nullish.
 *
 * Two coupled changes: (1) `arithOperandType` runs a scoped flow-narrowing walk
 * (currentFlowGraph set ONLY for the walk — the pass-wide version regressed 78
 * tests); (2) `rhsIsDefinitelyNonNullish` classifies arithmetic / bitwise /
 * relational / `+` BinaryExpressions and enum-member value accesses as non-nullish.
 *
 * FP-safe by construction: flow narrowing only ever REMOVES union members, and the
 * result is used only when it PROVES the operand non-nullish. Negative controls pin
 * that a genuinely maybe-undefined operand still fires TS2362.
 */
class ArithmeticReassignmentNarrowingTest {

    @Test
    fun `reassignment with arithmetic RHS proves non-nullish - no TS2362`() {
        diagnose(
            """
            export function f(start = 0, length: number | undefined): number {
                const end = length === undefined ? 100 : start + length;
                length = end - start;
                return length - 5;
            }
            """,
        ) should {
            have(none { it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `flags = flags OR EnumMember default proves non-nullish - no TS2362`() {
        diagnose(
            """
            enum Flags { None = 0, NoTrunc = 1 }
            export function f(flags: Flags | undefined): number {
                flags = flags || Flags.None;
                return flags & Flags.NoTrunc;
            }
            """,
        ) should {
            have(none { it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `if guard narrows a compound-assignment RHS operand - no TS2365`() {
        diagnose(
            """
            export function g(indent: number, margin: number | undefined): void {
                if (margin !== undefined) { indent += margin; }
            }
            """,
        ) should {
            have(none { it.code == 2362 || it.code == 2363 || it.code == 2365 })
        }
    }

    @Test
    fun `x = x OR numeric-literal default proves non-nullish - no TS2362`() {
        diagnose(
            """
            export function f(flags: number | undefined): number {
                flags = flags || 0;
                return flags & 1;
            }
            """,
        ) should {
            have(none { it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `negative control - genuinely maybe-undefined operand still fires TS2362`() {
        diagnose(
            """
            export function neg(a: number | undefined): number {
                return a - 1;
            }
            """,
        ) should {
            have(any { it.code == 2362 })
        }
    }

    @Test
    fun `negative control - reassignment to a maybe-undefined value still fires`() {
        // The RHS is another maybe-undefined param, NOT a non-nullish expression,
        // so the reassignment does not prove `x` non-nullish.
        diagnose(
            """
            export function neg(x: number | undefined, y: number | undefined): number {
                x = y;
                return x - 1;
            }
            """,
        ) should {
            have(any { it.code == 2362 })
        }
    }
}
