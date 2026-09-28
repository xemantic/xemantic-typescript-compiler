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
 * (CHK.173) Round A: TS18047 / TS18048 / TS18049 on a property access whose BARE
 * identifier receiver is declared a nullable union and stays nullable after
 * narrowing (`Checker.emitTs1804xForNullableIdentifierReceiver`). Every fixture is a
 * cell of the (P18.210) census (`build/scratch-p18210-census/cells`) and every
 * expectation is tsgo 7.0.2's whole row list for it, `line:col` as tsgo prints.
 * The silent half covers the narrowing, optional-chain, strictNullChecks and
 * block-shadow gates (the last is the binding guard,
 * `LocalShadowGuard.nullableReceiverBindingRefused`, R1 catch / R2 declaration /
 * R3 block destructuring). Cells where tsgo reports and we do not yet (body
 * locals, optional parameters, type-parameter receivers, `(x).p`, ...) are
 * deliberately NOT pinned: they are the next rounds' countdowns.
 */
class IdentifierReceiverNullishTest {

    private val STRICT = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"
    private val STRICT_OFF = "// @strict: false\n// @target: es2022\n// @useRealLibs: true"

    private fun rowsOf(source: String, directives: String): List<String> =
        diagnose(source, directives).map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }.sorted()

    private fun codesOf(source: String): List<String> =
        diagnose(source, STRICT).map { "${it.line}:${it.character} TS${it.code}" }.sorted()

    @Test
    fun `c01 reports a string or null parameter`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:39 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `c02 reports a string or undefined parameter`() {
        val rows = rowsOf(
            """
            function f(x: string | undefined) { return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:44 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `c03 reports a string or null or undefined parameter`() {
        val rows = rowsOf(
            """
            function f(x: string | null | undefined) { return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:51 TS18049: 'x' is possibly 'null' or 'undefined'."))
    }

    @Test
    fun `c04 reports a method call on a nullable parameter`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return x.toUpperCase() }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:39 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `c05 reports a file-level declare const`() {
        val rows = rowsOf(
            """
            declare const d: { a: number } | null; const n = d.a;
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:50 TS18047: 'd' is possibly 'null'."))
    }

    @Test
    fun `c09 reports a write through the receiver`() {
        val rows = rowsOf(
            """
            function f(x: { p: number } | null) { x.p = 1 }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:39 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `c10 reports an increment through the receiver`() {
        val rows = rowsOf(
            """
            function f(x: { p: number } | null) { x.p++ }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:39 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `c11 reports a closure-captured null`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return () => x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:45 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `c20 reports a union alias`() {
        val rows = rowsOf(
            """
            type M = string | null; function f(x: M) { return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:51 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `c22 reports a call with a callback`() {
        val rows = rowsOf(
            """
            function f(x: number[] | undefined) { return x.map(v => v + 1) }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:46 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `c26 reports a method call on an object-or-null`() {
        val rows = rowsOf(
            """
            function f(x: { f(): void } | null) { x.f() }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:39 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `c28 reports a file-level let read in a function`() {
        val rows = rowsOf(
            """
            let g: string | null = null; function h() { return g.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:52 TS18047: 'g' is possibly 'null'."))
    }

    @Test
    fun `c34 reports two receivers in one expression`() {
        val rows = rowsOf(
            """
            function f(x: string | null, y: string | null) { return x.length + y.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:57 TS18047: 'x' is possibly 'null'.", "1:68 TS18047: 'y' is possibly 'null'."))
    }

    @Test
    fun `c36 reports a for-of variable`() {
        val rows = rowsOf(
            """
            function f(xs: (string | null)[]) { for (const x of xs) x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:57 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `c38 reports a method parameter`() {
        val rows = rowsOf(
            """
            class C { m(x: string | null) { return x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:40 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `c39 reports an arrow parameter`() {
        val rows = rowsOf(
            """
            const f = (x: string | null) => x.length;
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:33 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `c41 reports a Map or undefined`() {
        val rows = rowsOf(
            """
            function f(x: Map<string, number> | undefined) { x.get("a") }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:50 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `c43 reports a template span`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return `${"$"}{x.length}` }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:42 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `d13 reports a read after a for-in over the receiver`() {
        val rows = rowsOf(
            """
            function f(x: { a: number } | null) { for (const k in x) {} return x.a }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:68 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `d18 reports two reads of one receiver`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { x.length; x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:32 TS18047: 'x' is possibly 'null'.", "1:42 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `g01 reports a receiver narrowed to pure null`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { if (x === null) { x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:50 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `g02 reports a falsy-narrowed undefined`() {
        val rows = rowsOf(
            """
            function f(x: string | undefined) { if (!x) { x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:47 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `g04 reports a narrowed null picking the code from the narrowed type`() {
        val rows = rowsOf(
            """
            function f(x: string | null | undefined) { if (x === undefined) return; x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:73 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `g05 reports an undefined or null declared type`() {
        val rows = rowsOf(
            """
            function f(x: undefined | null) { x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:35 TS18049: 'x' is possibly 'null' or 'undefined'."))
    }

    @Test
    fun `m01 reports a destructured object parameter leaf`() {
        val rows = rowsOf(
            """
            function f({ x }: { x: string | null }) { return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:50 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `m02 reports a destructured tuple parameter leaf`() {
        val rows = rowsOf(
            """
            function f([x]: [string | null]) { return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:43 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `m03 reports a for-of array destructured leaf`() {
        val rows = rowsOf(
            """
            function f(es: [string, string | null][]) { for (const [k, v] of es) v.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:70 TS18047: 'v' is possibly 'null'."))
    }

    @Test
    fun `m04 reports a for-of object destructured leaf`() {
        val rows = rowsOf(
            """
            function f(es: { v: string | null }[]) { for (const { v } of es) v.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:66 TS18047: 'v' is possibly 'null'."))
    }

    @Test
    fun `m06 reports a file-level object destructured leaf`() {
        val rows = rowsOf(
            """
            declare const o: { v: string | null }; const { v } = o; v.length;
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:57 TS18047: 'v' is possibly 'null'."))
    }

    @Test
    fun `m07 reports a file-level tuple destructured leaf`() {
        val rows = rowsOf(
            """
            declare const t: [string | null]; const [v] = t; v.length;
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:50 TS18047: 'v' is possibly 'null'."))
    }

    @Test
    fun `h01 reports a class property initializer`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            class K { p = x.s }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:15 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h04 reports an enum member initializer`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            enum E { A = x.a.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:14 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h05 reports object and array literal members`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            const o = { v: x.s, w: [x.a] };
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:16 TS18047: 'x' is possibly 'null'.", "2:25 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h06 reports an array spread`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            const arr = [...x.a];
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:17 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h08 reports a switch discriminant`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            switch (x.s) { default: }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:9 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h09 reports a throw operand`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            function f() { throw x.s }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:22 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h11 reports a new expression callee`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            new x.C();
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:5 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h12 reports a tagged template tag`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            x.t`a`;
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:1 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h13 reports an await operand`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            async function f() { await x.p }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:28 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h14 reports a yield operand`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            function* g() { yield x.s }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:23 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h15 reports a non-null assertion operand`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            const n = x.a!;
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:11 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h16 reports if and while conditions`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            if (x.s) {} while (x.a.length) {}
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:20 TS18047: 'x' is possibly 'null'.", "2:5 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h17 reports an optional chain continuation base`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            x.a?.length;
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:1 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h18 reports a void operand`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            void x.s;
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:6 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h19 reports a template literal span`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            const k = `${"$"}{x.s}`;
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:14 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h20 reports an export default expression`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            export default x.s;
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:16 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `h21 reports all three conditional operands`() {
        val rows = rowsOf(
            """
            declare const x: { a: number[], s: string, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number> } | null;
            const c = x.s ? x.a : x.s;
            export {}
            """, STRICT,
        )
        assert(rows == listOf("2:11 TS18047: 'x' is possibly 'null'.", "2:17 TS18047: 'x' is possibly 'null'.", "2:23 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `d03 reports a closure-captured null or undefined as TS18049`() {
        val rows = rowsOf(
            """
            function f(x: string | null | undefined) { return () => x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:57 TS18049: 'x' is possibly 'null' or 'undefined'."))
    }

    @Test
    fun `c12 reports a closure-captured undefined exactly once`() {
        val rows = rowsOf(
            """
            function f(x: string | undefined) { return () => x.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:50 TS18048: 'x' is possibly 'undefined'."))
    }

    @Test
    fun `c25 reports an element read on the member exactly once`() {
        val rows = rowsOf(
            """
            function f(x: string[] | null) { return x[0].length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:41 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `d05 reports only the read outside the optional chain`() {
        val rows = rowsOf(
            """
            function f(x: { a: string } | null) { x?.a.length; x.a }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:52 TS18047: 'x' is possibly 'null'."))
    }

    @Test
    fun `d22 reports a nullable callee as TS2721 only`() {
        val rows = rowsOf(
            """
            function f(x: (() => void) | null) { x() }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:38 TS2721: Cannot invoke an object which is possibly 'null'."))
    }

    @Test
    fun `g03 reports a never receiver as TS2339 only`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { if (typeof x === "number") { x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:63 TS2339: Property 'length' does not exist on type 'never'."))
    }

    @Test
    fun `g06 reports a bare undefined as TS18050 only`() {
        val rows = rowsOf(
            """
            function f() { undefined.length }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:16 TS18050: The value 'undefined' cannot be used here."))
    }

    @Test
    fun `k05 reports a catch shadow as TS18046 only`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { try {} catch (x) { x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == listOf("1:51 TS18046: 'x' is of type 'unknown'."))
    }

    @Test
    fun `c08a is silent for an if truthiness guard`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { if (x) return x.length; return 0 }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c08b is silent for an and guard`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return x && x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c08c is silent for a non-null assertion`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return x!.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c08d is silent for an optional access`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return x?.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c08e is silent for an early return`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { if (!x) return 0; return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c08f is silent for a not-equal null ternary`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return x != null ? x.length : 0 }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c08g is silent for a typeof guard`() {
        val rows = rowsOf(
            """
            function f(x: string | number | null) { if (typeof x === "string") return x.length; return 0 }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c08h is silent for a loop after a throw guard`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { if (x === null) throw 1; for (let i = 0; i < 2; i++) { x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c08i is silent for an assignment`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { x = "a"; return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c27 is silent for a file-level assignment`() {
        val rows = rowsOf(
            """
            declare let x: string | null; x = "a"; x.length;
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c40 is silent for a switch case null`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { switch (x) { case null: return 0; default: return x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c42 is silent for an undefined equality return`() {
        val rows = rowsOf(
            """
            declare const undefined_: undefined; function f(x: string | undefined) { if (x === undefined) return; return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `d06 is silent for an optional chain callback`() {
        val rows = rowsOf(
            """
            function f(p: { a(): number[] } | undefined) { p?.a().forEach(() => p.a) }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `d07 is silent for an optional element chain`() {
        val rows = rowsOf(
            """
            function f(t: string[] | undefined) { return t?.[t.length - 1] }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `d08 is silent for an optional call argument`() {
        val rows = rowsOf(
            """
            function f(d: { m(n: number): void, p: number } | undefined) { d?.m(d.p) }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `d09 is silent for a guarded closure`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { const g = () => { if (x) return x.length; return 0 }; return g }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `d10 is silent for an optional chain in a condition`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return x?.length === 1 ? x.length : 0 }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `d16 is silent for a labeled break guard`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { label: { if (!x) break label; x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `d25 is silent for a negated ternary`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return !x ? 0 : x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `d26 is silent for an or guard`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return x === null || x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `d27 is silent for a loose undefined equality`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { if (x == undefined) return; x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `d28 is silent for a nullish assignment`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { x ??= "a"; return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k01 is silent for a block const shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { { const x = "a"; x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k02 is silent for a block let shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { { let x = "a"; x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k03 is silent for a block array destructured shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { { const [x] = ["a"]; x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k04 is silent for a block object destructured shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { { const { x } = { x: "a" }; x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k06 is silent for a for header let shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { for (let x = "a"; x.length < 3; ) { x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k07 is silent for a for-of shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { for (const x of ["a"]) { x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k08 is silent for a for-in shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { for (const x in {}) { x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k09 is silent for a case block shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null, k: number) { switch (k) { case 1: { const x = "a"; x.length } } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k10 is silent for a case clause shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null, k: number) { switch (k) { case 1: const x = "a"; x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k11 is silent for an if block shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { if (1) { const x: string = "a"; x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k13 is silent for a block shadow of a file-level name`() {
        val rows = rowsOf(
            """
            declare const x: string | null; function f() { { const x = "a"; x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k14 is silent for a body shadow of a file-level name`() {
        val rows = rowsOf(
            """
            declare const x: string | null; function f() { const x = "a"; return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k15 is silent for a body destructured shadow of a file-level name`() {
        val rows = rowsOf(
            """
            declare const x: string | null; function f() { const [x] = ["a"]; return x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k16 is silent for an arrow parameter shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return (x: string) => x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k17 is silent for a nested function parameter shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { function g(x: string) { return x.length } return g }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k18 is silent for a function expression own name`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { const g = function x() { return x.length }; return g }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k20 is silent for a block function declaration`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { { function x() {} x.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k21 is silent for a file-level block shadow`() {
        val rows = rowsOf(
            """
            declare const x: string | null; { const x = "a"; x.length }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k22 is silent for a block var`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { { var y = "a"; y.length } }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `k23 is silent for a defaulted arrow parameter shadow`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { const cb = (x = "a") => x.length; return cb }
            export {}
            """, STRICT,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `n01 is silent for strictNullChecks off`() {
        val rows = rowsOf(
            """
            function f(x: string | null) { return x.length }
            function g(x: string | undefined) { return x.length }
            export {}
            """, STRICT_OFF,
        )
        assert(rows == emptyList<String>())
    }

    @Test
    fun `c46 does not unwrap a parenthesized receiver`() {
        // tsgo reports TS2531 here (`Object is possibly 'null'.`), not TS18047 — we are
        // silent (residue); what this pins is that the arm does not see through parens.
        val rows = rowsOf(
            """
            function f(x: string | null) { return (x).length }
            export {}
            """, STRICT,
        )
        assert(rows.none { it.contains("TS1804") })
    }

    @Test
    fun `e01 to e03 keep the TS2339 that follows the report`() {
        val e01 = codesOf("function f(x: string | null) { return x.nope }\nexport {}")
        val e02 = codesOf("function f(x: { a: number } | undefined) { return x.b }\nexport {}")
        val e03 = codesOf("function f(x: { a: number } | null | undefined) { return x.lenght }\nexport {}")
        assert(e01 == listOf("1:39 TS18047", "1:41 TS2339"))
        assert(e02 == listOf("1:51 TS18048", "1:53 TS2339"))
        assert(e03 == listOf("1:58 TS18049", "1:60 TS2339"))
    }
}
