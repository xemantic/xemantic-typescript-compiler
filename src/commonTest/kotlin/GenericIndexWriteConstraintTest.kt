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
import kotlin.test.Test

/**
 * M1.12 (self-compile burn-down): TS2862 ("Type 'T' is generic and can only be indexed for
 * reading.") fires ONLY for a generic index-WRITE whose receiver would otherwise fall back to a
 * STRING/symbol index signature (tsc checker.ts ~19294:
 * `accessFlags & NoIndexSignatures && indexInfo.keyType !== numberType`). A bare
 * `T extends object` has no such `indexInfo`, so tsc emits nothing — tsc's own
 * `assign<T extends object>(t: T){ t[p] = arg[p] }` in core.ts compiles clean.
 *
 * The B98.r80 walker fired for ANY constrained type param, FP-ing on `assign`. The fix narrows
 * eligibility to type params whose constraint bears a string/symbol index signature (inline
 * `{ [s: string]: V }`, `Record<K, V>` with a string/symbol-like K, or an intersection of either).
 *
 * Positive controls reproduce the two `cannotIndexGenericWritingError` corpus shapes.
 */
class GenericIndexWriteConstraintTest {

    private fun has2862(source: String) = diagnose(source).any { it.code == 2862 }

    @Test
    fun `assign over T extends object - no TS2862`() {
        // The exact tsc core.ts `assign` idiom: `object` has no index signature.
        have(
            !has2862(
                """
                function assign<T extends object>(t: T, arg: T): T {
                    for (const p in arg) {
                        t[p] = arg[p];
                    }
                    return t;
                }
                """,
            ),
            "index-write on a `T extends object` receiver must not fire TS2862 (no index signature in the constraint)",
        )
    }

    @Test
    fun `T extends plain interface with no index signature - no TS2862`() {
        have(
            !has2862(
                """
                interface Point { a: number; b: number; }
                function set<T extends Point>(t: T, p: keyof T, v: T[keyof T]): void {
                    t[p] = v;
                }
                """,
            ),
            "a constraint without a string/symbol index signature must not fire TS2862",
        )
    }

    @Test
    fun `T extends Record with string-symbol key - fires TS2862`() {
        // cannotIndexGenericWritingError.ts foo() — the corpus positive shape.
        have(
            has2862(
                """
                function foo<T extends Record<string | symbol, any>>(target: T, p: string | symbol) {
                    target[p] = "";
                }
                """,
            ),
            "a `Record<string | symbol, any>` constraint (string index signature) must still fire TS2862",
        )
    }

    @Test
    fun `T extends inline string index signature intersection - fires TS2862`() {
        // cannotIndexGenericWritingError.ts foo2() — the corpus positive shape.
        have(
            has2862(
                """
                function foo2<T extends number[] & { [s: string]: number | string }>(target: T, p: string | number) {
                    target[p] = 1;
                    target[1] = 1; // ok — numeric-literal index is exempt
                }
                """,
            ),
            "an intersection constraint bearing a `{ [s: string]: … }` index signature must still fire TS2862",
        )
    }

    @Test
    fun `T extends Record of string number - fires TS2862`() {
        have(
            has2862(
                """
                function put<T extends Record<string, number>>(t: T, k: string): void {
                    t[k] = 1;
                }
                """,
            ),
            "a `Record<string, number>` constraint must fire TS2862",
        )
    }
}
