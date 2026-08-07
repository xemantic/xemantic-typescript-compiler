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
 * Round 450 (self-compile burn-down, services TS2345 -4): the default-initialization
 * idiom `x = x || DEFAULT;` (utilities.ts `maximumLength = maximumLength || defaultMax`)
 * must narrow a `T | undefined` reference to the non-nullish `T` for subsequent reads.
 *
 * `rhsIsDefinitelyNonNullish` — consulted by `narrowByAssignmentRhs` — did not classify
 * a `||`/`??` RHS. `a || b` / `a ?? b` are non-nullish iff the RIGHT operand is non-nullish;
 * the right operand is usually a const/local reference (not syntactically classifiable),
 * so its type is resolved and checked for nullishness.
 */
class OrDefaultAssignNarrowTest {

    @Test
    fun `x = x or DEFAULT narrows a maybe-undefined reference for later reads`() {
        diagnose(
            """
            declare const cache: Map<number, string>;
            const DEFAULT = 16000;
            function f(maximumLength: number | undefined): string {
                maximumLength = maximumLength || DEFAULT;
                if (!cache.has(maximumLength)) {
                    cache.set(maximumLength, "x");
                }
                return cache.get(maximumLength)!;
            }
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `x = x nullish-coalesce DEFAULT narrows for later reads`() {
        diagnose(
            """
            declare function needNum(n: number): void;
            const DEFAULT = 8;
            function f(x: number | undefined): void {
                x = x ?? DEFAULT;
                needNum(x);
            }
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - no reassignment leaves the maybe-undefined arg failing`() {
        diagnose(
            """
            declare function needNum(n: number): void;
            function f(x: number | undefined): void {
                needNum(x);
            }
            """,
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a nullish right operand does not narrow`() {
        // `x = x || maybe` where `maybe: number | undefined` keeps `x` nullable, so the
        // `||`-narrowing must NOT fire — `narrowByExcludingNullUndefined` is only sound
        // when the assigned value is provably non-nullish.
        diagnose(
            """
            declare function needNum(n: number): void;
            declare const maybe: number | undefined;
            function f(x: number | undefined): void {
                x = x || maybe;
                needNum(x);
            }
            """,
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
