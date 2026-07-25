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
 * Round 455 (M3.1, self-compile burn-down): `widenType` rebuilt a tuple `Type.Object` WITHOUT
 * `tupleElementTypes` and widened its `length` literal (`2 → number`), turning `[SF, FR]` into a
 * `{ 0: SF; 1: FR; length: number; [x: number]: SF | FR }` object that no longer related to a tuple
 * target (`length: number ⊄ 2`). tsc's own `map(arr, x => [a, b])` idiom whose result flows through
 * a generic wrapper (`concatenate(...)`) and is assigned to a `[A, B][]` variable FP-fired TS2322
 * (declarations.ts/builder.ts/destructuring.ts, ×8). `widenType` now widens a tuple's ELEMENT types
 * only and rebuilds a proper tuple (length literal + tupleElementTypes preserved).
 */
class WidenTuplePreservesShapeTest {

    private val prelude = """
        interface SF { fileName: string; }
        interface FR { pos: number; }
        declare function map<T, U>(array: readonly T[], f: (x: T, i: number) => U): U[];
        declare function concatenate<T>(a: T[] | undefined, b: T[] | undefined): T[] | undefined;
        declare const sf: SF;
        declare const refs: readonly FR[];
    """.trimIndent()

    @Test
    fun `a map callback returning a tuple - assigned through a generic wrapper - relates to a tuple array`() {
        diagnose(
            prelude + """
            let rawReferencedFiles: [SF, FR][] | undefined;
            export function collect(): void {
                rawReferencedFiles = concatenate(rawReferencedFiles, map(refs, f => [sf, f]));
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a map callback tuple with a LITERAL element widens its element but stays a tuple`() {
        // `f => [true, f]` produces `[true, FR]`; widening changes the element `true → boolean`,
        // exercising the rebuild path — the result must be `[boolean, FR]` (a tuple), not a
        // `{0,1,length:number}` object, so it relates to the `[boolean, FR][]` target.
        diagnose(
            prelude + """
            let pairs: [boolean, FR][] | undefined;
            export function collect(): void {
                pairs = concatenate(pairs, map(refs, f => [true, f]));
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `an incompatible source assigned to a tuple array still fires - negative control`() {
        // `string[]` is not a `[SF, FR][]` — the assignment path must still emit TS2322 (the
        // widenType change must not disable tuple-array assignability checks wholesale).
        diagnose(
            prelude + """
            declare const strs: string[];
            let pairs: [SF, FR][] | undefined;
            export function collect(): void {
                pairs = strs;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
