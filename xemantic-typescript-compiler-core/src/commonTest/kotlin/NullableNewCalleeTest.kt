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
 * (CHK.173) G1 arc B5f (N1) — `new X!()` with a nullable constructor. The PARSER stopped a
 * `new` callee at `X` (its member loop had no `!` arm, where tsgo's `parseMemberExpressionRest`
 * has one), so `new W!()` parsed as a CALL of `(new W)!` — every nullable constructor read
 * `new W` without arguments against `(new () => S) | undefined`: a false TS2351 and an `any`
 * instance, which also kept an assignment of it from narrowing (a false TS18047 later). Then
 * three readers had to learn the non-null callee tsgo's `resolveNewExpression` reads through
 * `checkNonNullExpression`: the instance type ([Checker.getReturnTypeOfNewExpression], also
 * for a MEMBER callee that is not a class — `new o.W!()` was `any`), the construct emitter
 * (a nullable callee WITHOUT `!` is TS18047/8/9, narrowing respected; a primitive callee is
 * TS2351 "Type 'Number' …"), and TS2511 through a `!`. TS1209 moved to the parser and covers
 * every `?.` after a `new` callee.
 *
 * Every expectation is tsgo 7.0.2's rows for the same file (`build/bench/p18227-agent/cells`).
 */
class NullableNewCalleeTest {

    private val prelude = "interface S { close(): void } interface T { open(): void }\n"

    private fun rows(body: String): List<String> =
        diagnose(prelude + body + "\nexport {}\n")
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }
            .sorted()

    @Test
    fun `n1 - a non-null constructor builds its instance and an assignment of it narrows`() {
        val silent = listOf(
            "function f(o: { W?: new () => S }) { let s: S | null = null; s = new o.W!(); s.close() }",
            "declare const W: (new () => S) | undefined; function f() { let s: S | null = null; s = new W!(); s.close() }",
            "declare const W: (new () => S) | undefined; function f() { const s = new W!(); s.close() }",
            "declare const p: boolean; function f(o: { W?: new () => S }) { const { W } = o; let s: S | null = null; try { s = p ? new W!() : new W!() } catch { return } s.close() }",
            "function f(o: { W?: new () => S }) { let s: S | null = null; try { s = new o.W!() } catch { return } s.close() }",
            "class K { static make: (new () => S) | undefined; m() { let s: S | null = null; s = new K.make!(); s.close() } }",
            "declare const W: (new () => S) | null | undefined; function f() { let s: S | undefined; s = new W!(); s.close() }",
        )
        val all = silent.flatMap { rows(it) }
        assert(all.isEmpty())
    }

    @Test
    fun `the instance of a non-null constructor is typed in every callee form`() {
        assert(rows("declare const W: (new () => S) | undefined; function f() { const s: number = new W!() }") ==
            listOf("2:66 TS2322 Type 'S' is not assignable to type 'number'."))
        assert(rows("declare const Wn: (new () => S) | null; function f() { const s = new Wn!(); const n: number = s }") ==
            listOf("2:83 TS2322 Type 'S' is not assignable to type 'number'."))
        assert(rows("declare const W: (new () => S) | undefined; const s = new W!(); const n: number = s") ==
            listOf("2:71 TS2322 Type 'S' is not assignable to type 'number'."))
        assert(rows("declare const W: (new () => S) | undefined; function f() { const s = new (W!)(); const n: number = s }") ==
            listOf("2:88 TS2322 Type 'S' is not assignable to type 'number'."))
        assert(rows("declare const W: (new () => S) | undefined; function f() { const s = new W!; const n: number = s }") ==
            listOf("2:84 TS2322 Type 'S' is not assignable to type 'number'."))
        assert(rows("class K { static make: (new () => S) | undefined } function f() { const s = new K.make!(); const n: number = s }") ==
            listOf("2:98 TS2322 Type 'S' is not assignable to type 'number'."))
        assert(rows("declare const arr: ((new () => S) | undefined)[]; function f() { const s = new arr[0]!(); const n: number = s }") ==
            listOf("2:97 TS2322 Type 'S' is not assignable to type 'number'."))
        assert(rows("declare class D { x: number } declare const DC: typeof D | undefined; function f() { const s = new DC!(); const n: string = s.x }") ==
            listOf("2:113 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `a union of two constructors with undefined builds the union of their instances`() {
        assert(rows("declare const U: (new () => S) | (new () => T) | undefined; function f() { const s = new U!(); const n: number = s }") ==
            listOf("2:102 TS2322 Type 'S | T' is not assignable to type 'number'."))
        assert(rows("declare const U: (new () => S) | (new () => T); function f() { const s = new U(); const n: number = s }") ==
            listOf("2:89 TS2322 Type 'S | T' is not assignable to type 'number'."))
    }

    @Test
    fun `arity and argument types are checked against the non-null constructor`() {
        assert(rows("declare const W2: (new (x: number) => S) | undefined; function f() { const s = new W2!() }") ==
            listOf("2:80 TS2554 Expected 1 arguments, but got 0."))
        assert(rows("declare const W: (new () => S) | undefined; function f() { const s = new W!(1) }") ==
            listOf("2:77 TS2554 Expected 0 arguments, but got 1."))
        assert(rows("declare const W2: (new (x: number) => S) | undefined; function f() { new W2!(\"a\") }") ==
            listOf("2:78 TS2345 Argument of type 'string' is not assignable to parameter of type 'number'."))
    }

    @Test
    fun `a callback argument is contextually typed by the non-null constructor`() {
        assert(rows("declare const W: (new (cb: (n: number) => void) => S) | undefined; function f() { new W!(n => { const s: string = n }) }") ==
            listOf("2:103 TS2322 Type 'number' is not assignable to type 'string'."))
        assert(rows("declare const W: (new (o: { cb: (n: number) => void }) => S) | undefined; function f() { new W!({ cb: n => { const s: string = n } }) }") ==
            listOf("2:116 TS2322 Type 'number' is not assignable to type 'string'."))
        // Without the assertion: TS18048 at the callee AND the argument still contextually typed.
        assert(rows("declare const W: (new (cb: (n: number) => void) => S) | undefined; function f() { new W(n => { const s: string = n }) }") ==
            listOf("2:102 TS2322 Type 'number' is not assignable to type 'string'.", "2:87 TS18048 'W' is possibly 'undefined'."))
    }

    @Test
    fun `a non-null assertion inside a member callee emits as one callee`() {
        // Before the parser arm `new o.x!.y!(1)` parsed as `(new o.x)!.y!(1)` and emitted
        // `(new o.x).y(1)` - a different program. tsgo emits `new o.x.y(1)`.
        val js = TypeScriptCompiler().compile(
            "declare const W: any; declare const o: any;\nconst a = new W!();\nconst b = new W!;\nconst c = new o.x!.y!(1);\n",
            "m.ts",
        ).jsOutputs.joinToString("\n") { it.second }
        assert(js.lines().filter { it.startsWith("const") } ==
            listOf("const a = new W();", "const b = new W;", "const c = new o.x.y(1);"))
    }

    @Test
    fun `an abstract class through a non-null assertion is TS2511`() {
        assert(rows("abstract class AC { x = 1 } declare const ac: typeof AC | undefined; function f() { const s = new ac!() }") ==
            listOf("2:95 TS2511 Cannot create an instance of an abstract class."))
    }

    @Test
    fun `a nullable callee without the assertion is TS18047 or TS18048 and the instance is still typed`() {
        assert(rows("declare const W: (new () => S) | undefined; function f() { const s = new W(); s.close() }") ==
            listOf("2:74 TS18048 'W' is possibly 'undefined'."))
        assert(rows("function f(o: { W?: new () => S }) { const s = new o.W(); s.close() }") ==
            listOf("2:52 TS18048 'o.W' is possibly 'undefined'."))
        assert(rows("declare const W: (new () => S) | null; function f() { const s = new W(); const n: number = s }") ==
            listOf("2:69 TS18047 'W' is possibly 'null'.", "2:80 TS2322 Type 'S' is not assignable to type 'number'."))
    }

    @Test
    fun `a narrowed nullable callee is not reported`() {
        assert(rows("declare const W: (new () => S) | undefined; function f() { if (W) { const s = new W(); const n: number = s } }") ==
            listOf("2:94 TS2322 Type 'S' is not assignable to type 'number'."))
        assert(rows("function f(o: { W?: new () => S }) { if (o.W) { const s = new o.W(); const n: number = s } }") ==
            listOf("2:76 TS2322 Type 'S' is not assignable to type 'number'."))
        assert(rows("declare const W: (new () => S) | undefined; function f() { const s = W ? new W() : null; const n: number = s }") ==
            listOf("2:96 TS2322 Type 'S | null' is not assignable to type 'number'."))
    }

    @Test
    fun `control - a value that is not a constructor keeps its row`() {
        val d = diagnose(prelude + "declare const n: number | undefined; function f() { const s = new n!() }\nexport {}\n")
        val r = d.map { "${it.line}:${it.character} TS${it.code} ${it.message} ${it.messageChain}" }
        assert(r == listOf("2:67 TS2351 This expression is not constructable. [  Type 'Number' has no construct signatures.]"))
        assert(rows("declare const n: number | undefined; function f() { const s = new n() }") ==
            listOf("2:67 TS18048 'n' is possibly 'undefined'.", "2:67 TS2351 This expression is not constructable."))
        assert(rows("declare const g: (() => S) | undefined; function f() { const s = new g!() }") ==
            listOf("2:66 TS7009 'new' expression, whose target lacks a construct signature, implicitly has an 'any' type."))
    }

    @Test
    fun `TS1209 - an optional chain straight after a new callee is a syntax error naming the callee`() {
        assert(diagnose("declare const W: (new () => S) | undefined; new W?.()\nexport {}\n", directives = "")
            .filter { it.code == 1209 }.map { "${it.line}:${it.character} ${it.message}" } ==
            listOf("1:50 Invalid optional chain from new expression. Did you mean to call 'W()'?"))
        assert(diagnose("declare const a: { b: new () => { c(): void } }; new a.b?.c()\nexport {}\n", directives = "")
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" } ==
            listOf("1:57 TS1209 Invalid optional chain from new expression. Did you mean to call 'a.b()'?"))
        assert(diagnose("declare const W: new () => number[]; new W?.[0]\nexport {}\n", directives = "")
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" } ==
            listOf("1:43 TS1209 Invalid optional chain from new expression. Did you mean to call 'W()'?"))
        assert(diagnose("class A { b() {} } new A?.b()\nexport {}\n", directives = "")
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" } ==
            listOf("1:25 TS1209 Invalid optional chain from new expression. Did you mean to call 'A()'?"))
        assert(diagnose("declare const W: new <X>() => X; new W<string>?.()\nexport {}\n", directives = "")
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" } ==
            listOf("1:47 TS1209 Invalid optional chain from new expression. Did you mean to call 'W()'?"))
    }
}
