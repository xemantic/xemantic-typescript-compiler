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
 * (INV.0) (P18.261) The `new`-EXPRESSION CHECK family as it lives in `NewExpressionChecks`
 * after the extraction: the per-`new` checker `checkSingleNewExpressionTypes` (reached from the
 * spine's NEW_EXPRESSION arm and from `checkCallTypesInExpr`, both left on `Checker`), its
 * TS2511 / TS2673 / TS2674 / TS2351 / TS7009 / TS2347 / TS18048 emitters and argument checks,
 * the B171 DataView typed-array emission, and B264's `checkInheritedOverloadedCtorArgs` pass.
 *
 * Every row is tsgo 7.0.2's (cells under `build/bench/p18261-agent/matrix/`, 1-based column)
 * unless a KDoc says otherwise; all 23 cells agree on both arms of the move. Residues NOT
 * pinned as positives: the instance-callee TS2351 is absent from a MODULE file (the
 * `globals` read of `newCalleeVarHoldsInstance` cannot see a module local, INV.3(d)), the
 * B264 pass is silent on a plain two-level generic chain with non-rest overloads (cells
 * c20 / c22) where the corpus shape below fires, and the TS2344 constraint row of `new G<number>(1)` is missing; tsgo reports all three.
 */
class NewExpressionChecksCollaboratorTest {

    private fun rows(source: String): List<String> =
        diagnose(source, directives = "// @strict: true\n// @target: es2020")
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `new on an abstract class is TS2511`() {
        val r = rows(
            """
            abstract class A {}
            export const a = new A();
            """
        )
        assert(r == listOf("2:18 TS2511 Cannot create an instance of an abstract class."))
    }

    @Test
    fun `new through a variable typed as the abstract constructor side is TS2511`() {
        val r = rows(
            """
            abstract class A {}
            const K: typeof A = A;
            export const k = new K();
            """
        )
        assert(r == listOf("3:18 TS2511 Cannot create an instance of an abstract class."))
    }

    @Test
    fun `new on a private constructor outside the class is TS2673`() {
        val r = rows(
            """
            class P { private constructor() {} }
            export const p = new P();
            """
        )
        assert(r == listOf("2:18 TS2673 Constructor of class 'P' is private and only accessible within the class declaration."))
    }

    @Test
    fun `new on a protected constructor outside the class is TS2674`() {
        val r = rows(
            """
            class Q { protected constructor() {} }
            export const q = new Q();
            """
        )
        assert(r == listOf("2:18 TS2674 Constructor of class 'Q' is protected and only accessible within the class declaration."))
    }

    @Test
    fun `negative control - a protected constructor is accessible inside the class and a subclass`() {
        val r = rows(
            """
            class Q { protected constructor() {} static make() { return new Q(); } }
            class R extends Q { static make2() { return new Q(); } }
            export { Q, R };
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `new on a variable holding an instance is TS2351 in a script file`() {
        val r = rows(
            """
            class C { constructor() {} }
            const i = new C();
            const j = new i();
            """
        )
        assert(r == listOf("3:15 TS2351 This expression is not constructable."))
    }

    @Test
    fun `new on a number is TS2351`() {
        val r = rows(
            """
            declare const n: number;
            export const z = new n();
            """
        )
        assert(r == listOf("2:22 TS2351 This expression is not constructable."))
    }

    @Test
    fun `a constructor argument of the wrong type is TS2345`() {
        val r = rows(
            """
            class C { constructor(x: number) {} }
            export const c = new C("s");
            """
        )
        assert(r == listOf("2:24 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `a member of an object literal argument of the wrong type is TS2322`() {
        val r = rows(
            """
            class C { constructor(x: { a: number }) {} }
            export const c = new C({ a: "s" });
            """
        )
        assert(r == listOf("2:26 TS2322 Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `no constructor overload accepting the argument is TS2769`() {
        val r = rows(
            """
            class O { constructor(x: number); constructor(x: string); constructor(x: any) {} }
            export const o = new O(true);
            """
        )
        assert(r == listOf("2:24 TS2769 No overload matches this call."))
    }

    @Test
    fun `too many constructor arguments is TS2554`() {
        val r = rows(
            """
            class C { constructor(x: number) {} }
            export const c = new C(1, 2);
            """
        )
        assert(r == listOf("2:27 TS2554 Expected 1 arguments, but got 2."))
    }

    @Test
    fun `new on a function with no construct signature is TS7009`() {
        val r = rows(
            """
            function F() {}
            export const f = new F();
            """
        )
        assert(r == listOf("2:18 TS7009 'new' expression, whose target lacks a construct signature, implicitly has an 'any' type."))
    }

    @Test
    fun `type arguments on an untyped new are TS2347`() {
        val r = rows(
            """
            declare const x: any;
            export const y = new x<number>();
            """
        )
        assert(r == listOf("2:18 TS2347 Untyped function calls may not accept type arguments."))
    }

    @Test
    fun `a possibly undefined constructor callee is TS18048`() {
        val r = rows(
            """
            class A { constructor() {} }
            declare const K: typeof A | undefined;
            export const k = new K();
            """
        )
        assert(r == listOf("3:22 TS18048 'K' is possibly 'undefined'."))
    }

    /** B171's dedicated emission; tsgo names the parameter `ArrayBufferLike & …` where the
     *  emission prints `ArrayBuffer & …` — the code and span are tsgo's, the text is ours. */
    @Test
    fun `a typed array handed to DataView is TS2345`() {
        val r = rows(
            """
            export const v = new DataView(new Uint8Array(4));
            """
        )
        assert(r == listOf("1:31 TS2345 Argument of type 'Uint8Array<ArrayBuffer>' is not assignable to parameter of type 'ArrayBuffer & { BYTES_PER_ELEMENT?: undefined; }'."))
    }

    @Test
    fun `an inherited overloaded generic constructor checks its arguments - B264`() {
        val r = rows(
            """
            class BaseBase2 {
                constructor(x: number) { }
            }
            declare class BaseBase<T, U> extends BaseBase2 {
                constructor(x: T, ...y: U[]);
                constructor(x1: T, x2: T, ...y: U[]);
                constructor(x1: T, x2: U, y: T);
            }
            class Base extends BaseBase<string, number> {
            }
            class Derived extends Base { }
            new Derived("", 3);
            new Derived(3);
            new Derived("", 3, "", 3);
            """
        )
        assert(
            r == listOf(
                "13:13 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
                "14:17 TS2769 No overload matches this call.",
            )
        )
    }
}
