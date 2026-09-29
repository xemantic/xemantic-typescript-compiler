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
 * (CHK.173) (P18.222) — the CONDITION half of Round B1: a narrowing SITE (a flow condition,
 * a `switch` discriminant, an assertion call) inside the scope of a same-named block-scoped
 * `let` / `const` / `catch` / `for`-header binding tests THAT binding, and must not narrow
 * the outer variable the walk reads ([FlowShadowScope.readsOtherBinding]). Before it,
 * `function f(s: string | null) { { const s = g(); if (!s) return } return s.length }`
 * missed tsgo's TS18047 (the outer `s` read as narrowed to `string`) — at the property
 * access, the declaration, the argument and the return readers alike.
 *
 * A `switch` statement's discriminant is evaluated OUTSIDE its case block, so a case
 * clause's `const` does not scope it (c08, c23, c40, c41 stay narrowed).
 *
 * Every fixture is a cell of `build/bench/p18222-agent/c`, and every expectation is tsgo
 * 7.0.2's WHOLE row list for it, `line:col` as tsgo prints. The census cells c06 / c07
 * (the same shapes over a union ALIAS) are pinned in their interface form (c38 / c39):
 * we print an alias-named union's expansion where tsgo prints `U`, a pre-existing display
 * residue that reproduces with no shadow at all.
 */
class FlowShadowConditionTest {

    private val STRICT = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private fun rowsOf(source: String): List<String> =
        diagnose(source, STRICT).map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }.sorted()

    private fun checkerOver(source: String): Checker {
        val options = CompilerOptions(strict = true)
        val results = listOf(Binder(options).bind(Parser(source, "t.ts").parse()))
        return Checker(options, results)
    }

    // COST - a site is asked only once it has narrowed the walked name.

    @Test
    fun `cost - conditions on another name are never asked`() {
        val checker = checkerOver(
            "declare const c: boolean; function f(s: string | null, t: string | null) { " +
                "if (!t) return; if (t === 'x') return; while (t && c) { } if (typeof t !== 'string') return; " +
                "return s.length }\nexport {}\n",
        )
        val codes = checker.getDiagnostics().map { it.code }
        assert(codes == listOf(18047))
        assert(checker.flowShadowScope.siteAsks == 0)
    }

    @Test
    fun `cost positive control - a narrowing site on the walked name is asked and refused`() {
        val checker = checkerOver(
            "declare function g(): string | null; function f(s: string | null) { " +
                "{ const s = g(); if (!s) return } return s.length }\nexport {}\n",
        )
        val codes = checker.getDiagnostics().map { it.code }
        assert(codes == listOf(18047))
        assert(checker.flowShadowScope.siteAsks > 0)
        assert(checker.flowShadowScope.siteRefused > 0)
    }

    @Test
    fun `c01 e07 an if-return on the inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); if (!s) return; s.length } return s.length }
            export {}
            """)
        assert(rows == listOf("1:184 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c02 an equality with null on the inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); if (s === null) return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:182 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c03 a typeof guard on the inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); if (typeof s !== 'string') return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:193 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c04 an instanceof guard on the inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; declare function gd(): Date | null; function f(s: Date | null) { { const s = gd(); if (!(s instanceof Date)) return } return s.getTime() }
            export {}
            """)
        assert(rows == listOf("1:227 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c05 an in guard on the inner const reports the outer member read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; type A = { a: string }; type B = { b: number }; declare function h(): A | B; function f(o: A | B) { { const o = h(); if (!('a' in o)) return } return o.a }
            export {}
            """)
        assert(rows == listOf("1:254 TS2339: Property 'a' does not exist on type 'A | B'."))
    }

    @Test
    fun `c09 a truthiness and-chain on the inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); if (s && s.length) { } else return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:194 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c10 a for-header condition on the inner let reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { for (let s = g(); !s;) { return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:174 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c11 a catch variable condition reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { try { throw 1 } catch (s) { if (!s) return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:185 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c14 a condition in a block nested in the shadow scope reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); { if (!s) return } } return s.length }
            export {}
            """)
        assert(rows == listOf("1:178 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c15 an assertion call on the inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; declare function assert(x: unknown): asserts x; function f(s: string | null) { { const s = g(); assert(s) } return s.length }
            export {}
            """)
        assert(rows == listOf("1:217 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c16 a property-path condition on the inner const reports the outer path read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; type O = { p: string | null }; declare function h(): O; function f(s: O) { { const s = h(); if (!s.p) return } return s.p.length }
            export {}
            """)
        assert(rows == listOf("1:220 TS18047: 's.p' is possibly 'null'."))
    }

    @Test
    fun `c17 a case-clause const condition reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { switch (c) { case true: const s = g(); if (!s) return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:196 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c18 a for-of header condition reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { for (const s of arr) { if (!s) return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:180 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c19 a condition before the block const is in its TDZ and reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { if (!s) return; const s = g() } return s.length }
            export {}
            """)
        assert(rows == listOf("1:140 TS2448: Block-scoped variable 's' used before its declaration.", "1:140 TS2454: Variable 's' is used before being assigned.", "1:174 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c20 an or-condition on the inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); if (!s || c) return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:179 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c21 a while condition on the inner let reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { let s = g(); while (!s) { s = g() } } return s.length }
            export {}
            """)
        assert(rows == listOf("1:180 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c22 a do-while condition on the inner let reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { let s = g(); do { s = g() } while (!s) } return s.length }
            export {}
            """)
        assert(rows == listOf("1:183 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c24 a loose equality with undefined on the inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); if (s == undefined) return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:186 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c25 an if-throw on the inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); if (!s) throw 0 } return s.length }
            export {}
            """)
        assert(rows == listOf("1:175 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c26 an else-return on the inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); if (s) { } else { return } } return s.length }
            export {}
            """)
        assert(rows == listOf("1:186 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c27 one branch shadowed and one branch outer reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { if (c) { const s = g(); if (!s) return } else { if (!s) return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:205 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c28 both branches shadowed reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { if (c) { const s = g(); if (!s) return } else { const s = g(); if (!s) return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:220 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c29 a file-level let is not narrowed by a block const condition`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; let s: string | null = g(); { const s = g(); if (!s) throw 0 } s.length
            export {}
            """)
        assert(rows == listOf("1:165 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c30 an array-destructured inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const [s] = arr; if (!s) return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:176 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c31 an object-destructured inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const { s } = { s: g() }; if (!s) return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:185 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c34 an aliased condition on the inner const reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); const ok = !!s; if (!ok) return } return s.length }
            export {}
            """)
        assert(rows == listOf("1:191 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c35 the declaration reader reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); if (!s) return } const t: string = s; return t }
            export {}
            """)
        assert(rows == listOf("1:173 TS2322: Type 'string | null' is not assignable to type 'string'."))
    }

    @Test
    fun `c36 the argument reader reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; declare function take(x: string): void; function f(s: string | null) { { const s = g(); if (!s) return } take(s) }
            export {}
            """)
        assert(rows == listOf("1:212 TS2345: Argument of type 'string | null' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `c37 the return reader reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null): string { { const s = g(); if (!s) return '' } return s }
            export {}
            """)
        assert(rows == listOf("1:178 TS2322: Type 'string | null' is not assignable to type 'string'."))
    }

    @Test
    fun `c38 a discriminant guard on the inner const reports the outer member read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; interface KA { k: 'a'; a: string } interface KB { k: 'b' } declare function h(): KA | KB; function f(u: KA | KB) { { const u = h(); if (u.k !== 'a') return } return u.a }
            export {}
            """)
        assert(rows == listOf("1:269 TS2339: Property 'a' does not exist on type 'KA | KB'."))
    }

    @Test
    fun `c39 a discriminant switch on the inner const reports the outer member read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; interface KA { k: 'a'; a: string } interface KB { k: 'b' } declare function h(): KA | KB; function f(u: KA | KB) { { const u = h(); switch (u.k) { case 'b': return } } return u.a }
            export {}
            """)
        assert(rows == listOf("1:279 TS2339: Property 'a' does not exist on type 'KA | KB'."))
    }

    @Test
    fun `c42 a nested assertion call reports the outer read at the declaration reader`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; declare function assert(x: unknown): asserts x; function f(s: string | null) { { const s = g(); { assert(s) } } const t: string = s; return t }
            export {}
            """)
        assert(rows == listOf("1:220 TS2322: Type 'string | null' is not assignable to type 'string'."))
    }

    @Test
    fun `c44 control - an inner reassignment under the inner guard still reports the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { let s = g(); if (!s) { s = 'x' } } return s.length }
            export {}
            """)
        assert(rows == listOf("1:177 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `c08 control - a switch discriminant is outside its case block so the outer read is narrowed`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { switch (s) { case null: return; default: { const s = 1; s } } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `c12 control - a condition on the outer binding before the shadow block narrows the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { if (!s) return; { const s = g() } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `c13 control - a block declaring another name does not stop the outer condition`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const t = g(); if (!s) return } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `c23 control - a discriminant switch on the outer binding narrows past a case-block shadow`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; type U = { k: 'a', a: string } | { k: 'b' }; declare function h(): U; function f(u: U) { switch (u.k) { case 'b': return; case 'a': { const u = 1; u } } return u.a }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `c32 control - a later outer condition narrows the outer read`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); if (!s) return; } { if (!s) return } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `c33 control - an inner var is the same variable`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { var s = g(); if (!s) return } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `c40 control - an interface discriminant switch on the outer binding narrows past a case-block shadow`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; interface KA { k: 'a'; a: string } interface KB { k: 'b' } declare function h(): KA | KB; function f(u: KA | KB) { switch (u.k) { case 'b': return; case 'a': { const u = 1; u } } return u.a }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `c41 control - a case clause const does not scope the switch discriminant`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { switch (s) { case null: return; default: const s = 1; s } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `c43 control - an optional chain on the outer read is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare const arr: (string | null)[]; function f(s: string | null) { { const s = g(); if (!s) return } return s?.length }
            export {}
            """)
        assert(rows.isEmpty())
    }
}
