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
 * (CHK.194)(a) a static and an instance member of ONE name are two members. The class member
 * table used to be LAST-WINS across the two sides, so `class A { s = 1; static s = true }`
 * read `boolean` on `new A().s` (tsgo: `number`) and the other declaration order broke `A.s`.
 * The instance member now holds the (dual-populated) member table and the static lives in
 * `staticMembers` alone; a class-VALUE receiver (`A`, `N.A`, an import alias, `A["s"]`) reads
 * the static for a clashing name. Every expectation is `tools/tsgo-7.0.2/lib/tsc`'s output
 * for the same file (`build/bench/p18251-agent/cells`); an empty list is a tsgo-silent cell.
 */
class StaticInstanceMemberSeparationTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @module: esnext\n// @lib: es2022,dom"

    private fun rows(source: String): List<String> =
        diagnose(source, directives = directives).map { d ->
            val head = "${d.fileName?.substringAfterLast('/')}(${d.line},${d.character}) TS${d.code}: ${d.message}"
            (listOf(head) + d.messageChain.map { it.trim() }).joinToString(" / ")
        }.sorted()

    private fun file(body: String) = "// @Filename: /proj/src/a.ts\nexport {};\n$body"

    @Test
    fun `an instance property declared first wins the instance read and the static the class read`() {
        val r = rows(file("class A { s = 1; static s = true }\nconst a: null = new A().s;\nconst b: null = A.s;\n"))
        assert(r == listOf(
            "a.ts(3,7) TS2322: Type 'number' is not assignable to type 'null'.",
            "a.ts(4,7) TS2322: Type 'boolean' is not assignable to type 'null'.",
        ))
    }

    @Test
    fun `a static property declared first does not take the class read from the instance one`() {
        val r = rows(file("class A { static s = true; s = 1 }\nconst a: null = new A().s;\nconst b: null = A.s;\n"))
        assert(r == listOf(
            "a.ts(3,7) TS2322: Type 'number' is not assignable to type 'null'.",
            "a.ts(4,7) TS2322: Type 'boolean' is not assignable to type 'null'.",
        ))
    }

    @Test
    fun `a static and an instance method of one name return their own types in both orders`() {
        val r = rows(file(
            "class A { m() { return 1 } static m() { return \"\" } }\n" +
                "const a: null = new A().m();\nconst b: null = A.m();\n" +
                "class B { static m() { return \"\" } m() { return 1 } }\n" +
                "const c: null = new B().m();\nconst d: null = B.m();\n"
        ))
        assert(r == listOf(
            "a.ts(3,7) TS2322: Type 'number' is not assignable to type 'null'.",
            "a.ts(4,7) TS2322: Type 'string' is not assignable to type 'null'.",
            "a.ts(6,7) TS2322: Type 'number' is not assignable to type 'null'.",
            "a.ts(7,7) TS2322: Type 'string' is not assignable to type 'null'.",
        ))
    }

    @Test
    fun `arguments are checked against the side the receiver names`() {
        // The first use of the class is a CALL statement: the member tables are resolved lazily
        // and the static table must be read only after that resolution.
        val r = rows(file(
            "class A { static m(x: number) { return 1 } m(x: boolean) { return \"\" } }\n" +
                "A.m(\"x\");\nnew A().m(1);\n"
        ))
        assert(r == listOf(
            "a.ts(3,5) TS2345: Argument of type 'string' is not assignable to parameter of type 'number'.",
            "a.ts(4,11) TS2345: Argument of type 'number' is not assignable to parameter of type 'boolean'.",
        ))
    }

    @Test
    fun `a static and an instance accessor of one name are not merged into one member`() {
        val r = rows(file(
            "class A { get s(): number { return 1 } static get s(): string { return \"\" } }\n" +
                "const a: null = new A().s;\nconst b: null = A.s;\n" +
                "class C { static get s(): string { return \"\" } get s(): number { return 1 } }\n" +
                "const c: null = new C().s;\nconst d: null = C.s;\n"
        ))
        assert(r == listOf(
            "a.ts(3,7) TS2322: Type 'number' is not assignable to type 'null'.",
            "a.ts(4,7) TS2322: Type 'string' is not assignable to type 'null'.",
            "a.ts(6,7) TS2322: Type 'number' is not assignable to type 'null'.",
            "a.ts(7,7) TS2322: Type 'string' is not assignable to type 'null'.",
        ))
    }

    @Test
    fun `both sides are inherited through extends`() {
        val r = rows(file("class A { s = 1; static s = true }\nclass B extends A {}\nconst a: null = new B().s;\nconst b: null = B.s;\n"))
        assert(r == listOf(
            "a.ts(4,7) TS2322: Type 'number' is not assignable to type 'null'.",
            "a.ts(5,7) TS2322: Type 'boolean' is not assignable to type 'null'.",
        ))
    }

    @Test
    fun `a derived instance property is compared against the base instance one not the base static`() {
        val r = rows(file("class A { s = 1; static s = true }\nclass B extends A { s = \"x\" }\n"))
        assert(r == listOf(
            "a.ts(3,21) TS2416: Property 's' in type 'B' is not assignable to the same property in base type 'A'. / " +
                "Type 'string' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `an element access and a destructuring read their own sides`() {
        val r = rows(file(
            "class A { s = 1; static s = true }\nconst { s } = new A();\nconst a: null = s;\n" +
                "const k: null = A[\"s\"];\nconst k2: null = new A()[\"s\"];\n"
        ))
        assert(r == listOf(
            "a.ts(4,7) TS2322: Type 'number' is not assignable to type 'null'.",
            "a.ts(5,7) TS2322: Type 'boolean' is not assignable to type 'null'.",
            "a.ts(6,7) TS2322: Type 'number' is not assignable to type 'null'.",
        ))
    }

    @Test
    fun `a namespace-qualified class value reads the static`() {
        val r = rows(file(
            "namespace N { export class A { s = 1; static s = true } }\n" +
                "const a: null = N.A.s;\nconst b: null = new N.A().s;\n"
        ))
        assert(r == listOf(
            "a.ts(3,7) TS2322: Type 'boolean' is not assignable to type 'null'.",
            "a.ts(4,7) TS2322: Type 'number' is not assignable to type 'null'.",
        ))
    }

    @Test
    fun `a parameter that shadows the class reads the instance member`() {
        val r = rows(file("class A { s = 1; static s = true }\nfunction f(A: A) { const a: null = A.s; }\n"))
        assert(r == listOf("a.ts(3,26) TS2322: Type 'number' is not assignable to type 'null'."))
    }

    @Test
    fun `a local that shadows the class reads the instance member`() {
        val r = rows(file(
            "class A { s = 1; static s = true }\ndeclare const mk: () => A;\n" +
                "function f() { { const A = mk(); const a: null = A.s; } }\n"
        ))
        assert(r == listOf("a.ts(4,40) TS2322: Type 'number' is not assignable to type 'null'."))
    }

    @Test
    fun `a class target requires its instance member`() {
        val r = rows(file("class D { s = 1; static s = true }\nconst x: D = {};\nconst y: D = { s: \"x\" };\n"))
        assert(r == listOf(
            "a.ts(3,7) TS2741: Property 's' is missing in type '{}' but required in type 'D'.",
            "a.ts(4,16) TS2322: Type 'string' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `an instance source relates through its instance member`() {
        val r = rows(file("class A { s = 1; static s = true }\nconst x: { s: string } = new A();\n"))
        assert(r == listOf(
            "a.ts(3,7) TS2322: Type 'A' is not assignable to type '{ s: string; }'. / " +
                "Types of property 's' are incompatible. / Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `tsgo-silent - a class value relates through its static member and an instance through its instance member`() {
        val r = rows(file("class A { s = 1; static s = true }\nconst x: { s: number } = new A();\nconst y: { s: boolean } = A;\n"))
        assert(r.isEmpty())
    }

    @Test
    fun `control - a non-clashing static and instance member read as before`() {
        val r = rows(file(
            "class C { static t = \"\"; u = 1 }\nconst a: null = C.t;\nconst b: null = new C().u;\n" +
                "class D extends C {}\nconst c: null = D.t;\n"
        ))
        assert(r == listOf(
            "a.ts(3,7) TS2322: Type 'string' is not assignable to type 'null'.",
            "a.ts(4,7) TS2322: Type 'number' is not assignable to type 'null'.",
            "a.ts(6,7) TS2322: Type 'string' is not assignable to type 'null'.",
        ))
    }
}
