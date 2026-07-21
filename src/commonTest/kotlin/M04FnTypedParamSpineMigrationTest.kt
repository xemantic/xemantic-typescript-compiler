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
 * (M0.4, round 626): pins for the checkFnTypedParamCalls (B128/B215) spine
 * migration — the FnParamCtx downward threading rebuilt as a pull-based
 * per-anchor ancestor fold: fnParams/tpVarRefs/classInstVarRefs REBUILT at
 * every fn-like boundary (a nested arrow does NOT see the outer fn's
 * fn-typed params), tparams/tpAst and fnParamsAccum ACCUMULATED through
 * boundaries (with param-name shadowing on the accum), the class
 * property-initializer edge KEEPING the enclosing maps while adding class
 * TPs, and the legacy REACH silences (void/template operands, class
 * EXPRESSION members). All expectations verified against the pre-migration
 * walker.
 */
class M04FnTypedParamSpineMigrationTest {

    @Test
    fun `TS2558 - type args on a call to a fn-typed param with no own type params`() {
        diagnose(
            """
            function f<T>(cb: (x: T) => void) {
                cb<number>(1);
            }
            """
        ) should {
            have(any { it.code == 2558 && "Expected 0 type arguments, but got 1." in it.message })
        }
    }

    @Test
    fun `negative control - a fn-typed param whose FunctionType has own type params draws no TS2558`() {
        diagnose(
            """
            function f(cb: <U>(x: U) => void) {
                cb<number>(1);
            }
            """
        ) should {
            have(none { it.code == 2558 })
        }
    }

    @Test
    fun `TS2345 case A - a DIFFERENT unconstrained type-param arg with the TS2208 related info`() {
        diagnose(
            """
            function f<T, U>(cb: (x: T) => void, y: U) {
                cb(y);
            }
            """
        ) should {
            have(any { d ->
                d.code == 2345 && "'U' is not assignable to parameter of type 'T'" in d.message &&
                    d.relatedInformation.any { it.code == 2208 }
            })
        }
    }

    @Test
    fun `negative control - the SAME type-param arg is legal`() {
        diagnose(
            """
            function f<T>(cb: (x: T) => void, x: T) {
                cb(x);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `TS2345 case B - a concrete simple-type arg against a bare unconstrained tp param`() {
        diagnose(
            """
            function f<T>(cb: (x: T) => void) {
                cb(42);
            }
            """
        ) should {
            have(any { d ->
                d.code == 2345 && "'number' is not assignable to parameter of type 'T'" in d.message &&
                    d.messageChain.any { "could be instantiated" in it }
            })
        }
    }

    @Test
    fun `negative control - a CONSTRAINED type param is not in the unconstrained set`() {
        diagnose(
            """
            function f<T extends string>(cb: (x: T) => void) {
                cb(42);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `scope rebuild - a nested arrow does NOT see the outer fn-typed param`() {
        diagnose(
            """
            function f<T>(cb: (x: T) => void) {
                const g = () => cb<number>(1);
            }
            """
        ) should {
            have(none { it.code == 2558 })
        }
    }

    @Test
    fun `tp accumulation - an outer fn's type param is in scope for a nested fn's own fn-typed param`() {
        diagnose(
            """
            function outer<T>() {
                function inner(cb: (x: T) => void) {
                    cb(42);
                }
            }
            """
        ) should {
            have(any { d ->
                d.code == 2345 && "'number' is not assignable to parameter of type 'T'" in d.message
            })
        }
    }

    @Test
    fun `B215 - apply on a fn-typed param accumulates through a nested closure`() {
        diagnose(
            """
            function f(fn: (a: string) => void) {
                return () => fn.apply(null, [1]);
            }
            """
        ) should {
            have(any { d ->
                d.code == 2345 && "[a: string]" in d.message &&
                    d.messageChain.any { "no match for required element at position 0" in it }
            })
        }
    }

    @Test
    fun `B215 shadow - an inner param of the same name shadows the accumulated fn-typed param`() {
        diagnose(
            """
            function f(fn: (a: string) => void) {
                return (fn: number) => fn.apply(null, [1]);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `reach - a void operand is unreached`() {
        diagnose(
            """
            function f<T>(cb: (x: T) => void) {
                void cb<number>(1);
            }
            """
        ) should {
            have(none { it.code == 2558 })
        }
    }

    @Test
    fun `reach - a template span is unreached`() {
        diagnose(
            """
            function f<T>(cb: (x: T) => number) {
                const s = `${'$'}{cb<number>(1)}`;
            }
            """
        ) should {
            have(none { it.code == 2558 })
        }
    }

    @Test
    fun `reach - a class DECLARATION method body fires TS2558`() {
        diagnose(
            """
            class D {
                m(cb: (x: number) => void) {
                    cb<string>(1);
                }
            }
            """
        ) should {
            have(any { it.code == 2558 })
        }
    }

    @Test
    fun `reach - a class EXPRESSION member is unreached`() {
        diagnose(
            """
            const C = class {
                m(cb: (x: number) => void) {
                    cb<string>(1);
                }
            };
            """
        ) should {
            have(none { it.code == 2558 })
        }
    }

    @Test
    fun `class property initializer KEEPS the enclosing fn's fn-typed params`() {
        diagnose(
            """
            function f(cb: (x: number) => void) {
                class C {
                    p = cb<string>(1);
                }
            }
            """
        ) should {
            have(any { it.code == 2558 })
        }
    }

    @Test
    fun `generic method call on a generic-class-instance param - TS2345 against the mapped tp`() {
        diagnose(
            """
            class B<T> {
                foo(x: T): void {}
            }
            function g<U>(b: B<U>) {
                b.foo(42);
            }
            """
        ) should {
            have(any { d ->
                d.code == 2345 && "'number' is not assignable to parameter of type 'U'" in d.message
            })
        }
    }

    @Test
    fun `arrow EXPRESSION body is a reached anchor position`() {
        diagnose(
            """
            function f<T>() {
                const g = (cb: (x: T) => void) => cb(42);
            }
            """
        ) should {
            have(any { d ->
                d.code == 2345 && "'number' is not assignable to parameter of type 'T'" in d.message
            })
        }
    }
}
