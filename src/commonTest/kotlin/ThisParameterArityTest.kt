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
 * Round 730: a `this` PSEUDO-parameter is never a call argument, but
 * `buildSignatureForFunctionLikeTypeNode` (the FunctionType/ConstructorType
 * annotation path) counted it into `Signature.minArgumentCount` while
 * `getParameterSymbols` dropped it from `Signature.parameters` — so
 * `minArgumentCount` EXCEEDED `parameters.size` and every arity gate read the
 * signature as "target provides too few arguments". A `this`-carrying function
 * type was therefore not assignable to ITSELF.
 *
 * The same builder also zipped the surviving symbols positionally against the
 * DECLARATION's parameter list, shifting every parameter type by one.
 *
 * Both are invisible under the embedded lib (it declares no `this` parameters)
 * and fire on every real-lib `Array`/`ReadonlyArray` member taking a `thisArg`.
 */
class ThisParameterArityTest {

    @Test
    fun `a this-carrying function type is assignable to itself`() {
        diagnose(
            """
            interface Src { m(cb: (this: any, a: number, b: number, c: number) => void): void }
            interface Tgt { m(cb: (this: any, a: number, b: number, c: number) => void): void }
            declare const s: Src;
            export const t: Tgt = s;
            """.trimIndent(),
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `a this-carrying property function type is assignable to itself`() {
        diagnose(
            """
            interface Src { f: (this: any, a: number) => void }
            interface Tgt { f: (this: any, a: number) => void }
            declare const s: Src;
            export const t: Tgt = s;
            """.trimIndent(),
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `a this-carrying function type assigned directly is assignable to itself`() {
        diagnose(
            """
            declare const fn: (this: any, a: number, b: number) => void;
            export const g: (this: any, a: number, b: number) => void = fn;
            """.trimIndent(),
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `a trailing optional keeps a this-carrying function type assignable`() {
        diagnose(
            """
            declare const fn: (this: any, a: number, b?: number) => void;
            export const g: (this: any, a: number) => void = fn;
            """.trimIndent(),
        ) should { have(none { it.code == 2322 }) }
    }

    // ---- negative controls -------------------------------------------------
    // Every one of these ALSO errored before the fix, so they discriminate by
    // MESSAGE, not by presence: a control that merely errors cannot tell the
    // arity bug from the real diagnostic.

    @Test
    fun `a this-carrying parameter type mismatch names the right parameter and types`() {
        // Pre-fix this reported the ARITY message instead, because the positional
        // zip had shifted `b`'s type to `a`'s.
        diagnose(
            """
            declare const fn: (this: any, a: number, b: string) => void;
            export const g: (this: any, a: number, b: number) => void = fn;
            """.trimIndent(),
        ) should {
            have(any {
                it.code == 2322 && it.messageChain.any { line ->
                    line.contains("Types of parameters 'b' and 'b' are incompatible")
                }
            })
        }
    }

    @Test
    fun `a genuine arity gap in a this-carrying function type is still reported`() {
        diagnose(
            """
            declare const fn: (this: any, a: number, b: number) => void;
            export const g: (this: any, a: number) => void = fn;
            """.trimIndent(),
        ) should {
            have(any {
                it.code == 2322 && it.messageChain.any { line ->
                    line.contains("Expected 2 or more, but got 1")
                }
            })
        }
    }

    @Test
    fun `a genuine arity gap without a this parameter is still reported`() {
        diagnose(
            """
            declare const fn: (a: number, b: number) => void;
            export const g: (a: number) => void = fn;
            """.trimIndent(),
        ) should {
            have(any {
                it.code == 2322 && it.messageChain.any { line ->
                    line.contains("Expected 2 or more, but got 1")
                }
            })
        }
    }

    @Test
    fun `a this-carrying call still reports too few arguments`() {
        // `this` must not be counted as a required argument at CALL sites either.
        diagnose(
            """
            declare function f(this: any, a: number, b: number): void;
            f(1);
            """.trimIndent(),
        ) should {
            have(any { it.code == 2554 && it.message.contains("Expected 2 arguments, but got 1") })
        }
    }

    @Test
    fun `a this-carrying call with all real arguments is accepted`() {
        diagnose(
            """
            declare function f(this: any, a: number, b: number): void;
            f(1, 2);
            """.trimIndent(),
        ) should { have(none { it.code == 2554 }) }
    }

    // ---- the live real-lib shape (server/utilitiesPublic.ts:22) ------------

    @Test
    fun `a branded Array subtype is assignable to the branded ReadonlyArray subtype`() {
        // Every `Array`/`ReadonlyArray` member taking a `thisArg` declares its
        // callback with a `this` parameter, so the arity bug made the two
        // interfaces mutually non-assignable through `flatMap`/`forEach`/`map`.
        diagnose(
            """
            interface SortedReadonlyArray<T> extends ReadonlyArray<T> { " __sortedArrayBrand": any; }
            interface SortedArray<T> extends Array<T> { " __sortedArrayBrand": any; }
            declare function createSortedArray<T>(): SortedArray<T>;
            export const emptyArray: SortedReadonlyArray<never> = createSortedArray<never>();
            """.trimIndent(),
            directives = "// @useRealLibs: true\n// @target: es2020",
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `a real-lib Array is assignable to a ReadonlyArray of the same element`() {
        diagnose(
            """
            declare function mk<T>(): Array<T>;
            export const ro: ReadonlyArray<string> = mk<string>();
            """.trimIndent(),
            directives = "// @useRealLibs: true\n// @target: es2020",
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `negative control - a real-lib Array of the wrong element still errors`() {
        diagnose(
            """
            declare function mk<T>(): Array<T>;
            export const ro: ReadonlyArray<string> = mk<number>();
            """.trimIndent(),
            directives = "// @useRealLibs: true\n// @target: es2020",
        ) should { have(any { it.code == 2322 }) }
    }
}
