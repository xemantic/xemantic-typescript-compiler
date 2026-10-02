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
 * (CHK.199) round (P18.264) — three `new`-expression gaps (P18.261)'s matrix found. Every
 * expected row below is tsgo 7.0.2's, measured on the same source
 * (`build/bench/p18264-agent/mx`), line and column 1-based as tsgo prints them.
 *
 * (a) TS2351 on a class INSTANCE used as a `new` callee — silent before in a MODULE file
 * (the old emitters read `globals`) and for an annotated declaration / a parameter of a
 * class that declares a constructor (whose instance type carries a construct signature
 * here). (b) B264's inherited-overloaded-constructor check ran only over top-level
 * expression STATEMENTS. (c) TS2344 / TS2559 on a violated class type-parameter constraint
 * in `new C<…>()`, after which tsgo does not check the arguments.
 */
class Chk199NewExpressionGapsTest {

    private fun rows(source: String): List<String> = diagnose(source, directives = "").map { d ->
        "TS${d.code}@${d.line},${d.character}: ${d.message}" +
            d.messageChain.joinToString("") { " | " + it.trim() }
    }

    private val notConstructable = "This expression is not constructable. | Type 'C' has no construct signatures."

    // ---- (a) instance callee --------------------------------------------------------------

    @Test
    fun `a - instance of a class with a constructor used as a new callee in a module file - c05`() {
        val r = rows(
            """
            class C { constructor() {} }
            const i = new C();
            export const j = new i();
            """
        )
        assert(r == listOf("TS2351@3,22: $notConstructable"))
    }

    @Test
    fun `a - annotated instance declaration in a module file`() {
        val r = rows(
            """
            class C { constructor() {} }
            declare const i: C;
            const j = new i();
            export {};
            """
        )
        assert(r == listOf("TS2351@3,15: $notConstructable"))
    }

    @Test
    fun `a - instance-typed parameter of a constructor-less class in a module file`() {
        val r = rows(
            """
            class C { x = 1; }
            function f(i: C) { return new i(); }
            export {};
            """
        )
        assert(r == listOf("TS2351@2,31: $notConstructable"))
    }

    @Test
    fun `a - annotated instance declaration of a class with a constructor in a script file`() {
        val r = rows(
            """
            class C { constructor() {} }
            declare const i: C;
            const j = new i();
            """
        )
        assert(r == listOf("TS2351@3,15: $notConstructable"))
    }

    @Test
    fun `a - alias chain to an instance in a module file`() {
        val r = rows(
            """
            class C { x = 1; }
            const i0 = new C();
            const i = i0;
            const j = new i();
            export {};
            """
        )
        assert(r == listOf("TS2351@4,15: $notConstructable"))
    }

    @Test
    fun `a - a parameter spelling the class name holds an instance`() {
        val r = rows(
            """
            class D { constructor() {} } function g(D: D) { return new D(); }
            export {};
            """
        )
        assert(r == listOf("TS2351@1,60: This expression is not constructable. | Type 'D' has no construct signatures."))
    }

    @Test
    fun `a - negative control - class values are constructable in a module file`() {
        val r = rows(
            """
            class A { constructor() {} static self() { return this; } }
            class B extends A {}
            const v = A; new v();
            const k1 = Math.random() ? A : B; new k1();
            function mk(K: typeof A) { return new K(); }
            const k2 = A.self(); new k2();
            const o = { K: A }; const k3 = o.K; new k3();
            for (const K of [A, B]) new K();
            function w(K = A) { return new K(); }
            export {};
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a - negative control - a block-scoped class constructed by its own name`() {
        val r = rows(
            """
            function f() { class Inner { constructor() {} } new Inner(); const x = new Inner(); return x; }
            export {};
            """
        )
        assert(r.isEmpty())
    }

    // ---- (b) inherited overloaded constructor ---------------------------------------------

    private val chain = """
        declare class BaseBase<T, U> { constructor(x: T); constructor(x: T, y: U); }
        declare class Base extends BaseBase<string, number> {}
        class Derived extends Base {}
    """.trimIndent()

    private val numberToString = "Argument of type 'number' is not assignable to parameter of type 'string'."

    @Test
    fun `b - inherited overload mismatch in a variable initializer - c20`() {
        assert(rows("$chain\nconst d = new Derived(1);") == listOf("TS2345@4,23: $numberToString"))
    }

    @Test
    fun `b - inherited overload mismatch in an exported initializer - c11`() {
        assert(rows("$chain\nexport const d = new Derived(1);") == listOf("TS2345@4,30: $numberToString"))
    }

    @Test
    fun `b - inherited overload mismatch in a return and an assignment and an array element`() {
        val r = rows(
            "$chain\nfunction f() { return new Derived(1); }\nlet d; d = new Derived(1);\nconst a = [new Derived(1)];"
        )
        assert(
            r == listOf(
                "TS2345@4,35: $numberToString",
                "TS2345@5,24: $numberToString",
                "TS2345@6,24: $numberToString",
            )
        )
    }

    @Test
    fun `b - three overloads with only the two-parameter one applicable - c22`() {
        val r = rows(
            """
            declare class BaseBase<T, U> { constructor(x: T); constructor(x: T, y: U); constructor(x: T, y: U, z: U); }
            declare class Base extends BaseBase<string, number> {}
            class Derived extends Base {}
            const d = new Derived(1, 2);
            """
        )
        assert(r == listOf("TS2345@4,23: $numberToString"))
    }

    @Test
    fun `b - several applicable overloads all failing report the last one`() {
        val r = rows(
            """
            declare class BaseBase<T, U> { constructor(x: T); constructor(x: U); }
            declare class Base extends BaseBase<string, number> {}
            class Derived extends Base {}
            const d = new Derived(true);
            """
        )
        assert(
            r == listOf(
                "TS2769@4,23: No overload matches this call. | The last overload gave the following error." +
                    " | Argument of type 'boolean' is not assignable to parameter of type 'number'."
            )
        )
    }

    @Test
    fun `b - negative control - a legal call and shadowing bindings stay silent`() {
        val r = rows(
            "$chain\n" +
                "const ok = new Derived(\"a\", 2);\n" +
                "function f(Derived: new (x: number) => object) { return new Derived(1); }\n" +
                "function g() { const Derived = class { constructor(x: number) {} }; return new Derived(1); }\n" +
                "function h() { class Derived { constructor(x: number) {} } return new Derived(1); }\n" +
                "if (Math.random()) { class Derived { constructor(x: number) {} } new Derived(1); }"
        )
        assert(r.isEmpty())
    }

    // ---- (c) type-argument constraint -----------------------------------------------------

    private val generics = """
        class G<T extends string> { constructor(x: T) {} }
        interface W { a?: number }
        class K<T extends W> { constructor() {} }
    """.trimIndent()

    @Test
    fun `c - violated constraint in a module file - c15`() {
        val r = rows(
            """
            class G<T extends string> { constructor(x: T) {} }
            export const g = new G<number>(1);
            """
        )
        assert(r == listOf("TS2344@2,24: Type 'number' does not satisfy the constraint 'string'."))
    }

    @Test
    fun `c - violated constraint in a script file - c21`() {
        val r = rows(
            """
            class G<T extends string> { constructor(x: T) {} }
            const g = new G<number>(1);
            """
        )
        assert(r == listOf("TS2344@2,17: Type 'number' does not satisfy the constraint 'string'."))
    }

    @Test
    fun `c - the arguments are not checked once a constraint fails`() {
        val r = rows("$generics\nnew G<number>(\"x\");\nexport {};")
        assert(r == listOf("TS2344@4,7: Type 'number' does not satisfy the constraint 'string'."))
    }

    @Test
    fun `c - weak constraint and member elaboration`() {
        val r = rows("$generics\nnew K<{ b: number }>();\nnew K<{ a: string }>();\nexport {};")
        assert(
            r == listOf(
                "TS2559@4,7: Type '{ b: number; }' has no properties in common with type 'W'.",
                "TS2344@5,7: Type '{ a: string; }' does not satisfy the constraint 'W'." +
                    " | Types of property 'a' are incompatible. | Type 'string' is not assignable to type 'number'.",
            )
        )
    }

    @Test
    fun `c - negative control - satisfied constraints stay silent and the argument check still runs`() {
        val r = rows("$generics\nnew G<\"a\">(\"a\");\nnew K<{ a: number }>();\nnew G<\"a\">(\"b\");\nexport {};")
        assert(r == listOf("TS2345@6,12: Argument of type '\"b\"' is not assignable to parameter of type '\"a\"'."))
    }
}
