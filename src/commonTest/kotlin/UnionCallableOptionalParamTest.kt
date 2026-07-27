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
import kotlin.test.Test

/**
 * Calling a UNION of callables whose signatures carry a TRAILING OPTIONAL parameter.
 *
 * tsc's `combineSignaturesOfUnionMembers` combines such a union unconditionally: it
 * takes the LONGEST parameter list, intersects position-wise (a position the shorter
 * signature lacks contributes `unknown`) and sets the combined `minArgumentCount` to
 * the MAX of the members'. Our B516 gate additionally demanded that every parameter be
 * REQUIRED (`minArgumentCount == parameters.size`), which excluded every lib method
 * with a trailing optional — `forEach(callbackfn, thisArg?)` above all — and sent it to
 * the "Each member of the union type … has signatures, but none of those signatures are
 * compatible with each other" TS2349 path instead.
 *
 * Found round 728 as two of (LIB.1)'s remaining seven real-lib false positives, both
 * exactly this shape: tsc's own `resolutions.forEach(…)` on a
 * `Set<Resolution> | Map<string, Resolution>` (resolutionCache.ts) and
 * `program.fileInfos.forEach(…)` on a union of two readonly arrays (builder.ts).
 *
 * The controls run in BOTH directions, which is what makes them controls:
 *  - the genuinely incompatible union (differing type parameters) must STILL be TS2349;
 *  - and the combined-parameter path must be observable, so the `number & boolean`
 *    = `never` case asserts a diagnostic that only APPEARS once the combination
 *    happens — on unmodified HEAD that call reported TS2349, not TS2345.
 */
class UnionCallableOptionalParamTest {

    @Test
    fun `a union of forEach-shaped members with a trailing optional parameter is callable`() {
        val diagnostics = diagnose(
            """
            interface SetLike<T> {
                forEach(cb: (value: T, value2: T, set: SetLike<T>) => void, thisArg?: any): void
            }
            interface MapLike<K, V> {
                forEach(cb: (value: V, key: K, map: MapLike<K, V>) => void, thisArg?: any): void
            }
            declare const u: SetLike<string> | MapLike<number, string>
            u.forEach(v => { })
            """,
        )
        assert(diagnostics.none { it.code == 2349 })
    }

    @Test
    fun `the combined signature accepts an argument valid for every member`() {
        val diagnostics = diagnose(
            """
            declare const f: ((x: string, y?: number) => void) | ((x: string, z?: boolean) => void)
            f("ok")
            """,
        )
        assert(diagnostics.isEmpty())
    }

    @Test
    fun `control - a union differing in type parameters stays not callable`() {
        val diagnostics = diagnose(
            """
            declare const fnUnion2: (<T extends number>(a: T) => void) | (<T>(a: string) => void)
            fnUnion2("")
            """,
        )
        assert(diagnostics.any { it.code == 2349 })
    }

    @Test
    fun `control - a bad argument against the intersected parameter is reported`() {
        val diagnostics = diagnose(
            """
            declare const g: ((x: number, y?: string) => void) | ((x: boolean, y?: string) => void)
            g("nope")
            """,
        )
        // This diagnostic only exists once the members are combined: the parameter is
        // `number & boolean` = `never`. On unmodified HEAD this call reported TS2349.
        assert(diagnostics.any { it.code == 2345 })
        assert(diagnostics.none { it.code == 2349 })
    }

    @Test
    fun `control - the combined arity still rejects too many arguments`() {
        val diagnostics = diagnose(
            """
            declare const h: ((x: number, y?: string) => void) | ((x: boolean, y?: string) => void)
            h(1, "a", 3)
            """,
        )
        assert(diagnostics.any { it.code == 2554 })
    }
}
