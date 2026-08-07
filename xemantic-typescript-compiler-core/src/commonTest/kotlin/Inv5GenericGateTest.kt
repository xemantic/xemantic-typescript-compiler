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
 * INV.5(e) (round 553): the `hasUnresolvedTypeParams` skip in
 * [canUseTypeEngine]'s Object↔Object branch is LIFTED — generic
 * instantiations whose resolved args still contain a bare TypeParam now flow
 * through the structural relation (TypeParam↔TypeParam relates via apparent
 * types; a bare TP vs a concrete arg correctly FAILS). Verified corpus-green
 * (11,256/0) + listAll ×8 error-line identical: on real generic code the
 * engine's verdicts now match what the old bail produced everywhere it
 * mattered, and the probe shape below was a FALSE NEGATIVE at HEAD (emitted
 * nothing) that now fires exactly like tsc.
 */
class Inv5GenericGateTest {

    @Test
    fun `a bare-TP generic instantiation vs a concrete instantiation fires TS2322`() {
        // tsc: Type 'Box<T>' is not assignable to type 'Box<string>'.
        // Pre-lift this was SILENT (canUseTypeEngine bailed on the TP arg).
        val d = diagnose("""
            interface Box<T> { v: T; w: number; }
            function f<T>(b: Box<T>) {
                const x: Box<string> = b;
            }
        """)
        val msg = d.firstOrNull { it.code == 2322 }?.message ?: ""
        assert(d.count { it.code == 2322 } == 1)
        assert("Box<T>" in msg && "Box<string>" in msg)
    }

    @Test
    fun `negative control - same bare-TP instantiation stays assignable`() {
        diagnose("""
            interface Box<T> { v: T; w: number; }
            function f<T>(b: Box<T>) {
                const x: Box<T> = b;
                const y: Box<unknown> = { v: b.v as unknown, w: 1 };
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - concrete generic assignment is unaffected`() {
        diagnose("""
            interface Box<T> { v: T; w: number; }
            declare const a: Box<string>;
            const b: Box<string> = a;
        """) should {
            have(none { it.code == 2322 })
        }
    }
}
