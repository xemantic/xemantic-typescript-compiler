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
 * Round 468 (M3.1): a CALL-EXPRESSION arg whose type carries an un-inferred
 * FOREIGN type param (a generic callee's un-substituted result) is unjudgeable in
 * the single-signature arg check — tsc codeFixProvider.ts's
 * `fixIdToRegistration.get(cast(context.fixId, isString))` where `cast<TOut …>`
 * returns a bare `TOut` our inference leaves unbound. Same rule as the overload
 * helpers' overloadArgSkippable. Gated to CallExpression args.
 */
class ForeignTpCallArgBailTest {

    @Test
    fun `an un-inferred generic call result arg is not checked`() {
        diagnose(
            """
            declare function cast<TOut extends TIn, TIn = any>(value: TIn, test: (v: TIn) => v is TOut): TOut;
            declare function isString(v: any): v is string;
            declare function useString(s: string): void;
            export function run(fixId: {}): void {
                useString(cast(fixId, isString));
            }
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a concrete wrong-typed call result arg still fires`() {
        diagnose(
            """
            declare function getNum(): number;
            declare function useString(s: string): void;
            export function run(): void {
                useString(getNum());
            }
            """,
        ) should {
            // The callee is non-generic — its concrete `number` return keeps checking.
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an own-TP identifier arg keeps its checks`() {
        diagnose(
            """
            declare function useString(s: string): void;
            export function run<T extends number>(x: T): void {
                useString(x);
            }
            """,
        ) should {
            // A bare own-TP arg (constraint number) vs string — still an error.
            have(any { it.code == 2345 })
        }
    }
}
