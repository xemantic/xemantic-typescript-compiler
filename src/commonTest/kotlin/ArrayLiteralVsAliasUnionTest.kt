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
 * Round 481: the string-layer union check (isAssignableTo, union members are
 * DISPLAY strings without "@") must treat a NAMED union member whose alias body
 * or interface heritage is array-ish as unknowable for an array-literal source —
 * `ArrayOrSingle<T> = T | readonly T[]` and `interface X extends
 * ReadonlyArray<T>` both accept `[expected]` (fourslashImpl's
 * `expected = [expected]` FP'd TS2322 'array' vs the union).
 */
class ArrayLiteralVsAliasUnionTest {

    @Test
    fun `an array literal assigned to a union with an array-ish alias member is accepted`() {
        diagnose(
            """
            type ArrayOrSingle<T> = T | readonly T[];
            interface Plus { readonly plusArg: string; }
            function f(expected: ArrayOrSingle<string> | Plus) {
                expected = ["a"];
                return expected;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `an Array-extending interface member alone does NOT suppress the check`() {
        // The interface's extra members make a bare array literal a genuine tsc
        // error — only ALIAS bodies get the array-ish leniency.
        diagnose(
            """
            interface Plus extends ReadonlyArray<string> { readonly plusArg: string; }
            interface Other { x: number; }
            function f(expected: Other | Plus) {
                expected = ["a"];
                return expected;
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an array literal vs a union of plain named members still fires`() {
        diagnose(
            """
            interface Foo { x: number; }
            interface Bar { y: string; }
            function g(v: Foo | Bar) {
                v = [1];
                return v;
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
