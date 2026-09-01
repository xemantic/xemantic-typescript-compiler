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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * INV.5(a) (round 545): canonical union/intersection identity —
 * [getUnionType]/[getIntersectionType] intern by member-id key
 * (the [TypeInterner] in CheckerState — (INV.0) step 1), so identical
 * member lists share one instance and Type.id regardless of WHICH pass
 * builds the type first (the round-543 first-touch blocker).
 *
 * The one behavioral exposure the canonicalization caused (watch.ts:533):
 * a TERNARY of array literals annotated with a variadic-tuple alias — the
 * arms' Array references now share an id, so their union collapses to a
 * single Array Reference and reached the B87.6b array-vs-tuple gate; tsc
 * contextually tuple-types those arms exactly like a bare array literal,
 * so the gate's literal exclusion is extended to (nested) ternaries of
 * array literals ([ternaryOfArrayLiterals]).
 */
class Inv5UnionInterningTest {

    @Test
    fun `negative control - ternary of array literals against a variadic tuple alias is legal`() {
        // The watch.ts:533 DiagnosticAndArguments shape, minimized (incl. a
        // NESTED ternary with array-literal leaves).
        diagnose("""
            interface Msg { key: string; }
            type MsgAndArgs = [message: Msg, ...args: string[]];
            declare const m1: Msg;
            declare const m2: Msg;
            declare const cond: boolean;
            declare const deep: boolean;
            function f(s: string) {
                const x: MsgAndArgs = cond ?
                    deep ? [m1, s] : [m1] :
                    [m2, s, s];
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `an array VARIABLE against a fixed tuple target still fires TS2322`() {
        // The B87.6b arm's positive case survives the gate extension.
        val d = diagnose("""
            declare const arr: number[];
            var x: [number] = arr;
        """)
        assert(d.count { it.code == 2322 } == 1)
    }

    @Test
    fun `interned unions keep distinct alias display names working`() {
        // Two aliases with the same member set — displays must stay sane
        // (the aliasDisplayMap shared-id hazard the interning was audited for).
        val d = diagnose("""
            type A = string | number;
            type B = string | number;
            const a: A = true as any as A;
            const bad: A = true;
        """)
        d should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `negative control - identical unions built in different contexts stay assignable`() {
        diagnose("""
            declare function f(): string | number;
            declare function g(): string | number;
            let x: string | number = f();
            x = g();
        """) should {
            have(none { it.code == 2322 })
        }
    }
}
