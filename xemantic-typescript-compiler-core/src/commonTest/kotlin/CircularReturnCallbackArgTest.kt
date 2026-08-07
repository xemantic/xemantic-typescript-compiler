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
 * M1.12 (self-compile burn-down): TS7023 ("implicitly has return type 'any' because it is
 * referenced directly or indirectly in one of its return expressions") must NOT fire when the
 * only self-reference is a CALLBACK ARGUMENT — `mapType(t, self)` — because there the argument
 * receives a contextual parameter type from the callee's signature, so self's own return type is
 * never needed to type the call, and the OTHER conditional branches supply a concrete type. tsc's
 * own `src/compiler/checker.ts` trips exactly this (0 errors in tsc's build):
 *
 *   function getMutableArrayOrTupleType(type: Type) {
 *       return type.flags & TypeFlags.Union ? mapType(type, getMutableArrayOrTupleType) : ... ;
 *   }
 *   function unwrapAwaitedType(type: Type) {
 *       return type.flags & TypeFlags.Union ? mapType(type, unwrapAwaitedType) : ... ;
 *   }
 *
 * Negative controls pin the FP-safety boundary: a self-reference in a genuinely stuck position
 * (array element that is then called, object-literal value) must STILL fire TS7023.
 */
class CircularReturnCallbackArgTest {

    @Test
    fun `self passed as a callback arg in one conditional branch - no TS7023`() {
        diagnose(
            """
            declare function mapType(t: object, f: (x: object) => object): object;
            declare function isUnion(t: object): boolean;
            function getMutable(type: object) {
                return isUnion(type) ? mapType(type, getMutable) : type;
            }
            """,
        ) should {
            have(none { it.code == 7023 })
        }
    }

    @Test
    fun `unwrapAwaitedType shape - self only as callback arg - no TS7023`() {
        diagnose(
            """
            declare function mapType(t: object, f: (x: object) => object): object;
            declare function isUnion(t: object): boolean;
            declare function isAwaited(t: object): boolean;
            declare function arg0(t: object): object;
            function unwrapAwaited(type: object) {
                return isUnion(type) ? mapType(type, unwrapAwaited) :
                    isAwaited(type) ? arg0(type) :
                    type;
            }
            """,
        ) should {
            have(none { it.code == 7023 })
        }
    }

    @Test
    fun `self in an array element that is then called STILL fires TS7023 - negative control`() {
        // `[self][0]()` genuinely forces self's return-type resolution → stuck → TS7023.
        diagnose(
            """
            function f() {
                return [f][0]();
            }
            """,
        ) should {
            have(any { it.code == 7023 })
        }
    }

    @Test
    fun `self as an object-literal value STILL fires TS7023 - negative control`() {
        diagnose(
            """
            function g() {
                return { next: g };
            }
            """,
        ) should {
            have(any { it.code == 7023 })
        }
    }
}
