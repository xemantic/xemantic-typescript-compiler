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
 * Round 440 (M3.1, self-compile burn-down): a generic call whose type-param can only be
 * anchored from an `any`-typed argument at a RETURN-TYPE site infers `T = any` instead of
 * leaving the return's TP un-inferred. tsc's `Debug.checkDefined<T>(value: T | null |
 * undefined): T` called with a destructured-const local `pos` (typed anyType via the
 * currentParamBindingNames side set) yielded no inference candidate → returned the bare `T`,
 * which then FP'd against a concrete consumer (`createFileDiagnostic`'s `number` param) and a
 * downstream `T - pos` arithmetic (TS2362/TS2365).
 *
 * The T=any binding is tsc-faithful (`id<T>(x:T)` with an `any` arg infers `T = any`) and
 * strictly suppression-only — an `any` return is assignable to any consumer, so it can only
 * REMOVE the un-inferred-TP FP, never add one. Gated to a return-type site with a genuine
 * `any`-typed arg soft-skip; the arg-vs-param check site keeps the hard bail.
 */
class GenericInferAnyArgFallbackTest {

    @Test
    fun `generic call with an any-typed arg returns any, not un-inferred T`() {
        diagnose(
            """
            namespace Debug {
                export function checkDefined<T>(value: T | null | undefined): T { return value as T; }
            }
            interface Loc { pos: number; end: number; }
            declare function getLoc(): unknown;
            declare function needNumber(n: number): void;
            export function f() {
                const { pos } = getLoc() as Loc;
                needNumber(Debug.checkDefined(pos));
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `un-inferred-T result of an any-arg generic call is arithmetic-safe`() {
        diagnose(
            """
            namespace Debug {
                export function checkDefined<T>(value: T | null | undefined): T { return value as T; }
            }
            interface Loc { pos: number; end: number; }
            declare function getLoc(): unknown;
            export function f(): number {
                const { pos, end } = getLoc() as Loc;
                return Debug.checkDefined(end) - pos;
            }
            """
        ) should {
            have(none { it.code == 2362 })
            have(none { it.code == 2365 })
        }
    }

    @Test
    fun `generic call with a concrete arg still infers precisely - negative control`() {
        // The any-arg fallback must not shadow real inference: a concrete arg still infers
        // T precisely, so a wrong consumer still fires TS2345.
        diagnose(
            """
            declare function identity<T>(value: T): T;
            declare function needString(s: string): void;
            export function f() {
                needString(identity(42));
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
