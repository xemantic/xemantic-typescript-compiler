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
 * (CHK.173) Round B1: the flow walks match an ASSIGNMENT to their reference by BINDING,
 * not by name ([FlowShadowScope]): a same-named `let` / `const` / `catch` / `for`-header
 * binding declared in a scope that does not contain the read no longer re-narrows the
 * outer variable (`if (!s) return; { const s = g(); } s.length` reported TS18047, tsgo is
 * silent), in both narrowing walks and in the definite-assignment walk (TS2454). With it
 * three narrowing gaps the census found on the same cells: N15 `x === void 0`, N2
 * `(m = re.exec(s)) != null` (tsgo's reference candidate) and N5 `s &&= s.trim()` (the
 * right operand is bound under the left's truthy condition).
 *
 * Every fixture is a cell of the (P18.215) census (`build/scratch-p18215-census/cells/lk`,
 * `nar`, Round A's `u/k12`) or of this round (`build/bench/p18216-agent/{d2,e}`), and
 * every expectation is tsgo 7.0.2's WHOLE row list for it, `line:col` as tsgo prints.
 *
 * Residues deliberately NOT pinned (tsgo reports, we do not): a `for…of` body read under
 * an outer guard; `s &&= g()`'s post-state after a guard;
 * the TS2454 rows the top-level emitter owns (census d01 / d03) and a case clause read
 * before its clause's `const` (z1). N8 (`if (!s) s = first(a)`) is its own round.
 * The CONDITION half (a condition inside the shadow's scope) is `FlowShadowConditionTest`.
 */
class FlowShadowNarrowingTest {

    private val STRICT = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private fun rowsOf(source: String): List<String> =
        diagnose(source, STRICT).map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }.sorted()

    // FSL - a block-scoped shadow does not re-narrow the outer variable (silent)
    @Test
    fun `f01 a block const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; { const s = g(); } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f02 a block let assigned null is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; { let s = g(); s = null; } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f07 a block const null is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; { const s = null; } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f10 a case-clause const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; switch (1) { case 1: const s = g(); } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f15 an if-block const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; if (Math.random()) { const s = g(); s; } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f17 a for-header let is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; for (let s = g(); ;) { break } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f19 a block const read in a nested block is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; { const s = g(); { s } } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f20 a block let assigned later is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; { let s: string | null; s = g(); } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f22 a labeled block const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; label: { const s = g(); } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f23 a try-block const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; try { const s = g(); } finally {} return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f24 a block const captured by a closure is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; { const s = g(); const t = () => s; } return s.charAt(0) }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f25 a block const whose block ends on the line before the read is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; { const s = g(); }
            s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `r01 a declaration reader after a block const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function h(s: string): void; function f(s: string | null) { if (!s) return; { const s = g(); } const t: string = s; return t }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `r02 an argument after a block const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function h(s: string): void; function f(s: string | null) { if (!s) return; { const s = g(); } h(s) }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `r04 an element read after a block const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function h(s: string): void; function f(s: string | null) { if (!s) return; { const s = g(); } return s['length'] }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `r05 a typeof guard and a block const number is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function h(s: string): void; function f(s: string | number) { if (typeof s !== 'string') return; { const s = 1; } h(s) }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e02 a default-clause let is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null) { if (!s) return; switch (1) { case 1: { break } default: let s = g(); s = null } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e06 a closure read after a block const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null) { if (!s) return; { const s = g(); } const h = () => s.length; return h }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e08 a catch variable assigned null is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null) { if (!s) return; try { throw 1 } catch (s) { s = null } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e10 a block let assigned in a loop is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null) { if (!s) return; { let s = g(); while (c) { s = null } } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e21 a labeled for-header let is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null) { if (!s) return; L: for (let s = g(); ;) { break L } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e25 a loop-body const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null) { if (!s) return; while (c) { const s = g(); if (!s) break } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    // FSL - the outer variable keeps its own type after the shadow (reports)
    @Test
    fun `f06 an un-narrowed parameter after a block const string reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { { const s = 'a'; } return s.length }
            export {}
            """)
        assert(rows == listOf(
            "1:95 TS18047: 's' is possibly 'null'.",
        ))
    }

    @Test
    fun `f12 an un-narrowed parameter after a block let string reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { { let s: string | null = 'a'; s.length } s.length }
            export {}
            """)
        assert(rows == listOf(
            "1:110 TS18047: 's' is possibly 'null'.",
        ))
    }

    @Test
    fun `k12 an un-narrowed parameter after a block const literal reports as tsgo`() {
        val rows = rowsOf("""
            function f(x: string | null) { { const x = "a" } return x.length }
            export {}
            """)
        assert(rows == listOf(
            "1:57 TS18047: 'x' is possibly 'null'.",
        ))
    }

    @Test
    fun `r06 a declaration reader after a block const string reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function h(s: string): void; function f(s: string | null) { { const s = 'a'; } const t: string = s; return t }
            export {}
            """)
        assert(rows == listOf(
            "1:131 TS2322: Type 'string | null' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `e01 an inner read reads the inner binding and the outer read the outer reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null) { if (!s) return; { const s = g(); s.length } return s.length }
            export {}
            """)
        assert(rows == listOf(
            "1:128 TS18047: 's' is possibly 'null'.",
        ))
    }

    @Test
    fun `e22 an inner declaration reader reads the inner binding reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null) { if (!s) return; { const s = g(); { const t: string = s } } const u: string = s; return u }
            export {}
            """)
        assert(rows == listOf(
            "1:136 TS2322: Type 'string | null' is not assignable to type 'string'.",
        ))
    }

    // FSL controls
    @Test
    fun `f13 an inner var is the same variable reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; { var s = g(); } return s.length }
            export {}
            """)
        assert(rows == listOf(
            "1:109 TS18047: 's' is possibly 'null'.",
        ))
    }

    @Test
    fun `f16 a loop-body let is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; while (Math.random()) { let s = g(); s = null; } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f03 a for-of const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; for (const s of [g()]) {} return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f04 a catch variable is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; try {} catch (s) {} return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f05 an arrow-body const is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; const h = () => { const s = g(); return s }; return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f09 a block array destructuring is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; { const [s] = [g()]; } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f11 a nested function let is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { if (!s) return; function h() { let s = g(); } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f14 a property path under a block const receiver is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(o: { p: string | null }) { if (!o.p) return; { const o = { p: g() }; } return o.p.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f18 a narrowing after the block is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; function f(s: string | null) { { let s = g(); } if (!s) return; return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `f21 a file-level let shadowed in a function block is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; let s: string | null = g(); function f() { if (!s) return; { const s = g(); } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `d02 a definite-assignment read on both branches reports as tsgo`() {
        val rows = rowsOf("""
            function f(c: boolean) { let s: string; if (c) { let s = 'a'; s; } else { s = 'b' } return s.length }
            export {}
            """)
        assert(rows == listOf(
            "1:92 TS2454: Variable 's' is used before being assigned.",
        ))
    }

    @Test
    fun `e03 a for-header var is the same variable reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null) { if (!s) return; for (var s = g(); c;) { break } return s.length }
            export {}
            """)
        assert(rows == listOf(
            "1:150 TS18047: 's' is possibly 'null'.",
        ))
    }

    @Test
    fun `e19 a block var then a block const reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null) { if (!s) return; { var s = g(); } { const s = 'a'; } return s.length }
            export {}
            """)
        assert(rows == listOf(
            "1:154 TS18047: 's' is possibly 'null'.",
        ))
    }

    @Test
    fun `e05 a property path assigned under a block const receiver is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(o: { p: string | null }) { if (!o.p) return; { const o = { p: g() }; o.p = null } return o.p.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e24 a block class is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null) { if (!s) return; { class s {} } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    // TS2454 - a block-scoped initializer does not assign the outer variable
    @Test
    fun `d11 read in a later if-block reports as tsgo`() {
        val rows = rowsOf("""
            declare const c: boolean; function f() { let s: string; { let s = 'a'; s; } if (c) { s.length } }
            export {}
            """)
        assert(rows == listOf(
            "1:86 TS2454: Variable 's' is used before being assigned.",
        ))
    }

    @Test
    fun `d12 read in a later loop body reports as tsgo`() {
        val rows = rowsOf("""
            declare const c: boolean; function f() { let s: string; { let s = 'a'; s; } while (c) { s.length } }
            export {}
            """)
        assert(rows == listOf(
            "1:89 TS2454: Variable 's' is used before being assigned.",
        ))
    }

    @Test
    fun `d15 after a for-header let reports as tsgo`() {
        val rows = rowsOf("""
            declare const c: boolean; function f() { let s: string; for (let s = 'a'; c;) { s; } if (c) { s.length } }
            export {}
            """)
        assert(rows == listOf(
            "1:95 TS2454: Variable 's' is used before being assigned.",
        ))
    }

    @Test
    fun `d16 after a block const reports as tsgo`() {
        val rows = rowsOf("""
            declare const c: boolean; function f() { let s: number; { const s = 1; } if (c) { s + 1 } }
            export {}
            """)
        assert(rows == listOf(
            "1:83 TS2454: Variable 's' is used before being assigned.",
        ))
    }

    @Test
    fun `d14 control - an outer assignment in a block is silent`() {
        val rows = rowsOf("""
            declare const c: boolean; function f() { let s: string; { var s2 = 1; s = 'a' } if (c) { s.length } }
            export {}
            """)
        assert(rows.isEmpty())
    }

    // N15 - void 0 is undefined in an equality
    @Test
    fun `n15 x eqeqeq void 0 is silent`() {
        val rows = rowsOf("""
            function f(x: string | undefined) { if (x === void 0) return; return x.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `n15b void 0 eqeqeq x is silent`() {
        val rows = rowsOf("""
            function f(x: string | undefined) { if (void 0 === x) return; return x.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e11 x not eqeq void 0 is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(x: string | undefined) { if (x !== void 0) return x.length; return 0 }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e12 loose void 0 eqeq x is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(x: string | null | undefined) { if (void 0 == x) return; return x.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e13 the equal branch keeps undefined reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(x: string | undefined) { if (x === void 0) { x.length } }
            export {}
            """)
        assert(rows == listOf(
            "1:120 TS18048: 'x' is possibly 'undefined'.",
        ))
    }

    // N2 - an assignment operand of an equality narrows its target
    @Test
    fun `n2c loose not eq null in a while is silent`() {
        val rows = rowsOf("""
            declare const re: RegExp; function f() { let m = re.exec(''); while ((m = re.exec('')) != null) { m.index } }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `n2d strict not eqeq null in a while is silent`() {
        val rows = rowsOf("""
            declare const re: RegExp; function f() { let m = re.exec(''); while ((m = re.exec('')) !== null) { m.index } }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `n2g eqeqeq null early return is silent`() {
        val rows = rowsOf("""
            declare const re: RegExp; function f() { let m = re.exec(''); if ((m = re.exec('')) === null) return; m.index }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `n2p a parameter target is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function mk(): string; declare function first<T>(a: T[]): T; declare function fd<T, U>(a: T[], f: (t: T) => U | undefined): U | undefined; function f(re: RegExp, m: RegExpExecArray | null) { while ((m = re.exec('')) != null) { m.index } }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e14 not eqeq undefined is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; declare function mk(): string | undefined; function f() { let m = mk(); if ((m = mk()) !== undefined) { m.length } }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e15 a parenthesized reference is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(x: string | null) { if ((x) === null) return; return x.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `n2e control - a truthy assignment is silent`() {
        val rows = rowsOf("""
            declare const re: RegExp; function f() { let m = re.exec(''); if ((m = re.exec(''))) { m.index } }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `n2f control - a bare truthy assignment is silent`() {
        val rows = rowsOf("""
            declare const re: RegExp; function f() { let m = re.exec(''); while (m = re.exec('')) { m.index } }
            export {}
            """)
        assert(rows.isEmpty())
    }

    // N5 - the right operand of &&= reads the left narrowed truthy
    @Test
    fun `n5c a local is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function first<T>(a: T[]): T; function f() { let s = g(); s &&= s.trim(); return s }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `n5p a parameter is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function mk(): string; declare function first<T>(a: T[]): T; declare function fd<T, U>(a: T[], f: (t: T) => U | undefined): U | undefined; function f(s: string | null) { s &&= s.trim(); return 0 }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e17 a property path is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(o: { p: string | null }) { o.p &&= o.p.trim(); return 0 }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `e16 the post-state keeps null reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f() { let s = g(); s &&= s.trim(); return s.length }
            export {}
            """)
        assert(rows == listOf(
            "1:115 TS18047: 's' is possibly 'null'.",
        ))
    }

    @Test
    fun `n5d control - or-equals is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function first<T>(a: T[]): T; function f() { let s = g(); s ||= 'x'; return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `n5e control - andand is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function first<T>(a: T[]): T; function f() { let s = g(); s && s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `n8d control - an element assignment is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function first<T>(a: T[]): T; function f(a: string[]) { let s = g(); if (!s) { s = a[0] } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    @Test
    fun `n8e control - a call assignment is silent`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare function first<T>(a: T[]): T; function f(a: string[]) { let s = g(); if (!s) { s = a.join('') } return s.length }
            export {}
            """)
        assert(rows.isEmpty())
    }

    // A NESTED walk restores the outer walk's reference. `s === t` inside the shadow's
    // block re-walks `t` (S-G4's equality-value walk) on the first branch's unwind; a
    // reference left at `t` (inside the block) would let the second branch's `s = 'x'`
    // re-narrow the OUTER `s` and silence the row. The discrimination rides on the
    // inner `s === t`: (P18.222) closed the condition leak, but the site test runs AFTER
    // the condition's nested walk and reads the reference that walk left behind — so a
    // missing restore still keeps the inner condition (and the inner assignment) and
    // silences the row. Re-measured: (P18.216)'s A7 arm is still RED on g01 and g02.

    @Test
    fun `g01 a nested equality walk before an else-branch assignment reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null, t: string | undefined) { if (!t) return; { let s = g(); if (c) { if (s === t) { } else { return } } else { s = 'x' } } return s.length }
            export {}
            """)
        assert(rows == listOf("1:219 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `g02 a nested equality walk in an early return before an else-branch assignment reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null, t: string | undefined) { if (!t) return; { let s = g(); if (c) { if (s !== t) return } else { s = 'x' } } return s.length }
            export {}
            """)
        assert(rows == listOf("1:206 TS18047: 's' is possibly 'null'."))
    }

    @Test
    fun `g03 control - the assignment branch first reports as tsgo`() {
        val rows = rowsOf("""
            declare function g(): string | null; declare const c: boolean; function f(s: string | null, t: string | undefined) { if (!t) return; { let s = g(); if (c) { s = 'x' } else { if (s !== t) return } } return s.length }
            export {}
            """)
        assert(rows == listOf("1:206 TS18047: 's' is possibly 'null'."))
    }
}
