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
 * M1.12 / M3.4 (round 416): the arithmetic pass has no flow narrowing, so an
 * `Enum | undefined` operand truthy-narrowed by an enclosing `&&` FP'd TS2362/TS2363 —
 * tsc's own checker.ts trips this six times (`checkMode && checkMode & CheckMode.Inferential`,
 * `contextFlags && contextFlags & ContextFlags.NoConstraints`, where the enum param is
 * `CheckMode | undefined`). A `Type.Union` carries no Undefined flag on itself, so the
 * strictNullChecks bail in `checkBinaryOperatorTypes` never fires and the operand classifier
 * rejects the undefined union member.
 *
 * Fix: `arithTruthyNarrowedNames` — while walking the RIGHT of a `&&`, the LEFT's
 * truthy-narrowable identifiers have their nullish members stripped in `arithOperandType`.
 * FP-safe: a truthy operand provably has no nullish value, so this only ever suppresses a
 * wrong error. Negative controls pin that a bare un-narrowed enum-union operand still fires.
 */
class ArithmeticAmpAmpNarrowingTest {

    @Test
    fun `enum-union operand narrowed by preceding same-name truthy - no TS2362`() {
        diagnose(
            """
            const enum Mode { A = 1, B = 2, C = 4 }
            export function f(mode: Mode | undefined): boolean {
                return !!(mode && mode & Mode.A);
            }
            """
        ) should {
            have(none { it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `chained AmpAmp narrows through multiple uses - no TS2362`() {
        // The checker.ts:33385 shape — checkMode used twice in the same `&&` chain.
        diagnose(
            """
            const enum CheckMode { Normal = 0, Inferential = 8, SkipContextSensitive = 4 }
            export function f(checkMode: CheckMode | undefined): boolean {
                return !!(checkMode && (checkMode & CheckMode.Inferential) && !(checkMode & CheckMode.SkipContextSensitive));
            }
            """
        ) should {
            have(none { it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `AmpAmp narrowing scoped to RHS only - LHS still fires`() {
        // FP-safety: the narrowing only applies within the `&&`'s right operand. A bare
        // `flags & X` OUTSIDE any `&&` guard (flags still `Flags | undefined`) must still fire.
        diagnose(
            """
            const enum Flags { A = 1 }
            export function f(flags: Flags | undefined): number {
                return flags & Flags.A;
            }
            """
        ) should {
            have(any { it.code == 2362 })
        }
    }

    @Test
    fun `not-equal-undefined guard narrows in AmpAmp RHS - no TS2362`() {
        diagnose(
            """
            const enum Mode { A = 1 }
            export function f(mode: Mode | undefined): number {
                return mode !== undefined && (mode & Mode.A) ? 1 : 0;
            }
            """
        ) should {
            have(none { it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `plain OR guard does NOT narrow the operand - control`() {
        // `||` does not truthy-narrow its right operand (the right is evaluated when the left is
        // falsy). A `(x || 0) & Flags.A` is fine because `x || 0` is non-undefined, but a bare
        // `x` inside the `||` right stays nullish. Here `x` un-narrowed → still fires. This pins
        // that we did NOT over-broaden the narrowing to `||`.
        diagnose(
            """
            const enum Flags { A = 1 }
            export function f(x: Flags | undefined): number {
                return (false || (x & Flags.A)) ? 1 : 0;
            }
            """
        ) should {
            have(any { it.code == 2362 })
        }
    }

    @Test
    fun `ternary false branch of a nullish test narrows - no TS2365`() {
        // scanner.ts:3995 — `end = length === undefined ? text.length : start! + length;`
        // In the false branch, `length` is non-undefined.
        diagnose(
            """
            export function f(start: number | undefined, length: number | undefined, textLen: number): number {
                return length === undefined ? textLen : start! + length;
            }
            """
        ) should {
            have(none { it.code == 2365 || it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `ternary true branch of a truthy test narrows - no TS2365`() {
        diagnose(
            """
            export function f(label: number | undefined): number {
                return label ? label + 1 : 0;
            }
            """
        ) should {
            have(none { it.code == 2365 || it.code == 2362 || it.code == 2363 })
        }
    }

    @Test
    fun `ternary false branch does NOT narrow when the test is not nullish - control`() {
        // A plain `x ? A : B` where the false branch has x falsy but still possibly-undefined
        // must NOT have x stripped in the FALSE branch (x could be undefined there). Here the
        // false branch uses x in arithmetic → still fires (x is `number | undefined` when falsy,
        // and 0/undefined are both falsy so x is not narrowed to a valid operand).
        diagnose(
            """
            const enum Flags { A = 1 }
            export function f(x: Flags | undefined): number {
                return x ? 0 : (x & Flags.A);
            }
            """
        ) should {
            have(any { it.code == 2362 })
        }
    }
}
