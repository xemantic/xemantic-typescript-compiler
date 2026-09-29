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
 * (CHK.173) Round B3 — the receivers Round A does not reach, all tsgo 7.0.2's
 * `checkNonNullExpression` on the receiver of a member access:
 *  - G5, a MEMBER receiver (`o.p.length`, `this.x.length`, `o['p'].length`,
 *    `a[0].p.length`, `this.#x.length`) — `Checker.emitTs1804xForNullableCompoundReceiver`,
 *    with B81.1c (`emitTs18048ForOptionalPropertyAccessReceiver`) keeping the OPTIONAL
 *    members it resolves and now choosing the code from what survives and the
 *    entity-vs-"Object" display as tsgo does;
 *  - G4p, a PARENTHESIZED receiver (`(x).length` is TS2531/2532/2533 at the `(`);
 *  - G6, the non-null continuation: a member is typed on the receiver's NON-NULL part
 *    (`Checker.nonNullReceiverPart`), so a nested `o.p.q.length` reports twice and a
 *    following TS2339 names `'string'`, not `'string | null'`; TS2721/2722/2723 anchors
 *    at the whole callee.
 * Fixtures are cells of the (P18.215) Round B census (`build/scratch-p18215-census/cells`
 * g4 / g5 / g6, and Round A's q) plus hand shapes measured against tsgo; every expectation
 * is tsgo's row list restricted to the codes this family emits (TS2540 on m14 / h24h and
 * TS2684 on m15 are not ours). RESIDUES, not pinned (tsgo reports, we do not yet): s01 /
 * s19 (a property narrowing carried into a closure), s12 (the receiver-of-receiver
 * reassigned), s25 (`const p = o.p; if (!p) return; o.p.length` — aliased narrowing),
 * m36 (a destructured body local, G1), e04 (TS2551 on a single non-null member), e05
 * (TS2322 on a write through a nullable receiver), v09 (an optional chain `x?.a ?? 1` is
 * still typed `any` outside the receiver arm — typing it everywhere exposed three false
 * rows on tsc's sources whose narrowing partner reads `any`), a call / `new` receiver core
 * (`f().p.length`, unmeasured on real code), a `this.x` whose function assigns `this.x`
 * (`this.x = av; this.x.length`, tsgo TS2531 — refused by an interim guard until the
 * assigned values this checker reads as `any` are typed, G1), and a STATIC
 * method's `this.s.length` ([Checker.currentClassForThis] cannot hold `typeof C`).
 */
class NullableMemberReceiverTest {

    private val STRICT = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private val CODES = setOf(18047, 18048, 18049, 2531, 2532, 2533, 2339, 2551, 2322, 2721, 2722, 2723)

    private fun rows(source: String): List<String> =
        diagnose(source + "\nexport {}\n", STRICT)
            .filter { it.code in CODES }
            .map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }
            .sorted()

    @Test
    fun `G5 m01 reports`() {
        val rows = rows("class C { x: string | null = null; m() { return this.x.length } }")
        assert(rows == listOf(
            "1:49 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m02 reports`() {
        val rows = rows("class C { x: string | undefined; m() { return this.x.length } }")
        assert(rows == listOf(
            "1:47 TS2532: Object is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G5 m03 reports`() {
        val rows = rows("class C { x?: string; m() { return this.x.length } }")
        assert(rows == listOf(
            "1:36 TS2532: Object is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G5 m06 reports`() {
        val rows = rows("function f(o: { p: string | null }) { return o.p.length }")
        assert(rows == listOf(
            "1:46 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m07 reports`() {
        val rows = rows("function f(o: { p: string | undefined }) { return o.p.length }")
        assert(rows == listOf(
            "1:51 TS18048: 'o.p' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G5 m08 reports`() {
        val rows = rows("function f(o: { p: string | null | undefined }) { return o.p.length }")
        assert(rows == listOf(
            "1:58 TS18049: 'o.p' is possibly 'null' or 'undefined'.",
        ))
    }

    @Test
    fun `G5 m09 reports`() {
        val rows = rows("function f(o: { p?: string }) { return o.p.length }")
        assert(rows == listOf(
            "1:40 TS18048: 'o.p' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G5 m12 reports`() {
        val rows = rows("function f(o: { a: { p: string | null } }) { return o.a.p.length }")
        assert(rows == listOf(
            "1:53 TS18047: 'o.a.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m13 reports`() {
        val rows = rows("declare const o: { p: string | null }; o.p.length;")
        assert(rows == listOf(
            "1:40 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m14 reports`() {
        val rows = rows("function f(o: { p: string | null }) { o.p.length = 1 }")
        assert(rows == listOf(
            "1:39 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m15 reports`() {
        val rows = rows("function f(o: { p: (() => void) | null }) { o.p.call(null) }")
        assert(rows == listOf(
            "1:45 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m16 reports`() {
        val rows = rows("function f(o: { p: (() => void) | null }) { o.p() }")
        assert(rows == listOf(
            "1:45 TS2721: Cannot invoke an object which is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m19 reports`() {
        val rows = rows("function f(o: { p: string | null }) { const g = () => o.p.length; return g }")
        assert(rows == listOf(
            "1:55 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m20 reports`() {
        val rows = rows("interface I { p: string | null } function f(o: I) { return o.p.length }")
        assert(rows == listOf(
            "1:60 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m21 reports`() {
        val rows = rows("function f(o: { readonly p: string | null }) { return o.p.length }")
        assert(rows == listOf(
            "1:55 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m22 reports`() {
        val rows = rows("class C { static x: string | null = null; m() { return C.x.length } }")
        assert(rows == listOf(
            "1:56 TS18047: 'C.x' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m23 reports`() {
        val rows = rows("namespace N { export declare const v: string | null } N.v.length;")
        assert(rows == listOf(
            "1:55 TS18047: 'N.v' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m24 reports`() {
        val rows = rows("function f(a: { p: string | null }[]) { return a[0].p.length }")
        assert(rows == listOf(
            "1:48 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m25 reports`() {
        val rows = rows("function f(o: { p: string | null }) { return o['p'].length }")
        assert(rows == listOf(
            "1:46 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m26 reports`() {
        val rows = rows("function f(o: { p: string | null } | undefined) { return o!.p.length }")
        assert(rows == listOf(
            "1:58 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m27 reports`() {
        val rows = rows("class C { x: string | null = null; m() { const g = () => this.x.length; return g } }")
        assert(rows == listOf(
            "1:58 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m29 reports`() {
        val rows = rows("function f(o: { p: string | null }) { return o.p.nope }")
        assert(rows == listOf(
            "1:46 TS18047: 'o.p' is possibly 'null'.",
            "1:50 TS2339: Property 'nope' does not exist on type 'string'.",
        ))
    }

    @Test
    fun `G5 m30 reports`() {
        val rows = rows("function f(o: { p: Map<string, string> | undefined }) { return o.p.get('a') }")
        assert(rows == listOf(
            "1:64 TS18048: 'o.p' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G5 m32 reports`() {
        val rows = rows("class C { #x: string | null = null; m() { return this.#x.length } }")
        assert(rows == listOf(
            "1:50 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m34 reports`() {
        val rows = rows("class C { constructor(public x: string | null) {} m() { return this.x.length } }")
        assert(rows == listOf(
            "1:64 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m35 reports`() {
        val rows = rows("function f(o: { p: { q: string | null } | null }) { return o.p.q.length }")
        assert(rows == listOf(
            "1:60 TS18047: 'o.p' is possibly 'null'.",
            "1:60 TS18047: 'o.p.q' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 s13 reports`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { const { p } = o; if (p) return o.p.length }")
        assert(rows == listOf(
            "1:145 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `G5 m04 stays silent`() {
        val rows = rows("class C { x: string | null = null; m() { if (this.x) return this.x.length; return 0 } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 m05 stays silent`() {
        val rows = rows("class C { x: string | null = null; m() { if (!this.x) return; return this.x.length } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 m10 stays silent`() {
        val rows = rows("function f(o: { p: string | null }) { if (o.p) return o.p.length; return 0 }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 m11 stays silent`() {
        val rows = rows("function f(o: { p: string | null }) { if (o.p === null) return; return o.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 m17 stays silent`() {
        val rows = rows("function f(o: { p: string | null }) { return o.p?.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 m18 stays silent`() {
        val rows = rows("function f(o: { p: string | null }) { o.p = 'a'; return o.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 m31 stays silent`() {
        val rows = rows("class C { x: string | null = null; m() { this.x = 'a'; return this.x.length } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 m33 stays silent`() {
        val rows = rows("function f(o: { p: string | null }) { while (o.p) { o.p.length; break } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s02 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { if (o.p !== null) { for (let i = 0; i < 2; i++) o.p.length } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s03 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { o.p ??= ''; return o.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s04 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { o.p ||= ''; return o.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s05 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { if (o.q && o.q.r) return o.q.r.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s06 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { if (o.q?.r) return o.q.r.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s07 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { if (!o.n) throw 0; return o.n.m }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s08 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { return o.n && o.n.m }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s09 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { if (typeof o.p === 'string') return o.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s10 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { while (o.p) { o.p.length; o.p = null } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s11 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { if (o.p) { g(); return o.p.length } } declare function g(): void")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s14 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; class C { x: string | null = null; m() { if (this.x) return this.x.length } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s15 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { switch (o.p) { case null: return; default: return o.p.length } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s16 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { if (o.p == undefined) return; return o.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s17 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { if (!o.q) return; if (!o.q.r) return; return o.q.r.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s18 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; declare function isS(v: unknown): v is string; function f(o: O) { if (isS(o.p)) return o.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s20 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { return o.p ? o.p.length : 0 }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s21 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(os: O[]) { for (const o of os) { if (!o.p) continue; o.p.length } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s22 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { if (o.p === null) o.p = ''; return o.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s23 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: Readonly<O>) { if (o.p) return o.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G5 s24 stays silent`() {
        val rows = rows("type O = { p: string | null, q?: { r: string | null } | null, n: { m: number } | undefined }; function f(o: O) { if ('p' in o && o.p) return o.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G4p h24 reports`() {
        val rows = rows("declare const x: { a: number[], s: string, n: number, o: object, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number>, f(): void } | null;\nlet v = (x).s;")
        assert(rows == listOf(
            "2:9 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G4p h24b reports`() {
        val rows = rows("declare const x: { a: number[], s: string, n: number, o: object, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number>, f(): void } | null;\nfunction f() { return (x).s }")
        assert(rows == listOf(
            "2:23 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G4p h24c reports`() {
        val rows = rows("declare const x: { a: number[], s: string, n: number, o: object, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number>, f(): void } | null;\nfunction f(y: string | null) { return (y).length }")
        assert(rows == listOf(
            "2:39 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G4p h24d reports`() {
        val rows = rows("declare const x: { a: number[], s: string, n: number, o: object, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number>, f(): void } | null;\nfunction f(y: string | undefined) { return (y).length }")
        assert(rows == listOf(
            "2:44 TS2532: Object is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G4p h24e reports`() {
        val rows = rows("declare const x: { a: number[], s: string, n: number, o: object, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number>, f(): void } | null;\nfunction f(y: string | null | undefined) { return (y).length }")
        assert(rows == listOf(
            "2:51 TS2533: Object is possibly 'null' or 'undefined'.",
        ))
    }

    @Test
    fun `G4p h24f reports`() {
        val rows = rows("declare const x: { a: number[], s: string, n: number, o: object, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number>, f(): void } | null;\nfunction f(y: string | null) { return (y as string | null).length }")
        assert(rows == listOf(
            "2:39 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G4p h24g reports`() {
        val rows = rows("declare const x: { a: number[], s: string, n: number, o: object, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number>, f(): void } | null;\nfunction f(y: string | null) { return ((y)).length }")
        assert(rows == listOf(
            "2:39 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G4p h24h reports`() {
        val rows = rows("declare const x: { a: number[], s: string, n: number, o: object, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number>, f(): void } | null;\nfunction f(y: string | null) { (y).length = 1 }")
        assert(rows == listOf(
            "2:32 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G4p h24i reports`() {
        val rows = rows("declare const x: { a: number[], s: string, n: number, o: object, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number>, f(): void } | null;\nfunction f(y: string | null) { return (y)['length'] }")
        assert(rows == listOf(
            "2:39 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `G4p h24j stays silent`() {
        val rows = rows("declare const x: { a: number[], s: string, n: number, o: object, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number>, f(): void } | null;\nfunction f(y: string | null) { if (y) return (y).length; return 0 }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G4p h24k stays silent`() {
        val rows = rows("declare const x: { a: number[], s: string, n: number, o: object, C: new () => object, t(s: TemplateStringsArray): void, p: Promise<number>, f(): void } | null;\nfunction f(y: string | null) { return (y!).length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G6 e01 reports`() {
        val rows = rows("function f(x: string | null) { return x.nope }")
        assert(rows == listOf(
            "1:39 TS18047: 'x' is possibly 'null'.",
            "1:41 TS2339: Property 'nope' does not exist on type 'string'.",
        ))
    }

    @Test
    fun `G6 e02 reports`() {
        val rows = rows("function f(x: { a: number } | undefined) { return x.b }")
        assert(rows == listOf(
            "1:51 TS18048: 'x' is possibly 'undefined'.",
            "1:53 TS2339: Property 'b' does not exist on type '{ a: number; }'.",
        ))
    }

    @Test
    fun `G6 e03 reports`() {
        val rows = rows("function f(x: { a: number } | null | undefined) { return x.lenght }")
        assert(rows == listOf(
            "1:58 TS18049: 'x' is possibly 'null' or 'undefined'.",
            "1:60 TS2339: Property 'lenght' does not exist on type '{ a: number; }'.",
        ))
    }

    @Test
    fun `G6 m28 reports`() {
        val rows = rows("function f(x: { a?: { b: number } } | null) { return x.a.b }")
        assert(rows == listOf(
            "1:54 TS18047: 'x' is possibly 'null'.",
            "1:54 TS18048: 'x.a' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `G6 d02 reports`() {
        val rows = rows("function f(x: { f?: () => void } | null) { x.f() }")
        assert(rows == listOf(
            "1:44 TS18047: 'x' is possibly 'null'.",
            "1:44 TS2722: Cannot invoke an object which is possibly 'undefined'.",
        ))
    }

    @Test
    fun `round-412 receiver guard stays silent`() {
        val rows = rows("interface S { p: string | null }\ninterface D extends S { p: string }\ndeclare function isD(s: S): s is D;\nfunction g(s: S) { if (isD(s)) return s.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `an optional-chained receiver link stays silent`() {
        val rows = rows("function g(h: { getX?: () => { y: { z: number } } }) { return h.getX?.().y.z }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `B81 - optional member element access is Object`() {
        val rows = rows("function g(o: { p?: string }) { return o['p'].length }")
        assert(rows == listOf(
            "1:40 TS2532: Object is possibly 'undefined'.",
        ))
    }

    @Test
    fun `B81 - optional nullable member is 18049`() {
        val rows = rows("function g(o: { p?: string | null }) { return o.p.length }")
        assert(rows == listOf(
            "1:47 TS18049: 'o.p' is possibly 'null' or 'undefined'.",
        ))
    }

    @Test
    fun `B81 - optional nullable member narrowed past undefined keeps null`() {
        val rows = rows("function g(o: { p?: string | null }) { if (o.p !== undefined) return o.p.length }")
        assert(rows == listOf(
            "1:70 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `a nullable member under an element access reports`() {
        val rows = rows("function g(o: { p: string | null }) { return o.p['length'] }")
        assert(rows == listOf(
            "1:46 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `a parenthesized member receiver is Object`() {
        val rows = rows("function g(o: { p: string | null }) { return (o.p).length }")
        assert(rows == listOf(
            "1:46 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `a parenthesized receiver of receiver is not an entity path`() {
        val rows = rows("function g(o: { a: { p: string | null } }) { return (o.a).p.length }")
        assert(rows == listOf(
            "1:53 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `a discriminant on the receiver of receiver stays silent`() {
        val rows = rows("type U = { k: 'a', p: string } | { k: 'b', p: null }\nfunction g(u: U) { if (u.k === 'a') return u.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `a pre-loop guard on a member stays silent inside the loop`() {
        val rows = rows("function g(o: { p: string | null }) { if (!o.p) return; for (const c of [1]) { o.p.length } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `a non-null assertion on a this member stays silent`() {
        val rows = rows("class K { x: string | null = null; m() { return this.x!.length } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `the optional-chain condition stays silent`() {
        val rows = rows("function g(o: { q?: { r: string } | null }) { if (o.q?.r) return o.q.r.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `an argument of a chain link guarded by the member stays silent`() {
        val rows = rows("function g(o: { p: string | null }) { return o.p?.at(o.p.length) }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `a receiver inside an optional chain is an entity path`() {
        val rows = rows("function g9(o: { p: string | null } | undefined) { return o?.p.length }")
        assert(rows == listOf(
            "1:59 TS18047: 'o.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `a receiver after an optional link is an entity path`() {
        val rows = rows("function g(o: { a?: { p: string | null } }) { return o.a?.p.length }")
        assert(rows == listOf(
            "1:54 TS18047: 'o.a.p' is possibly 'null'.",
        ))
    }

    @Test
    fun `a deeper receiver after an optional link is an entity path`() {
        val rows = rows("function g(o: { a?: { p: { q: string | null } } }) { return o.a?.p.q.length }")
        assert(rows == listOf(
            "1:61 TS18047: 'o.a.p.q' is possibly 'null'.",
        ))
    }

    @Test
    fun `a receiver after an optional call is Object`() {
        val rows = rows("function g3(h: { getX?: () => { y: string | null } }) { return h.getX?.().y.length }")
        assert(rows == listOf(
            "1:64 TS2531: Object is possibly 'null'.",
        ))
    }

    @Test
    fun `an assertion predicate on the receiver of receiver stays silent`() {
        val rows = rows("interface S { p: string | null; k: string }\ninterface D extends S { p: string }\ndeclare function assertD(s: S): asserts s is D;\nfunction g(s: S) { assertD(s); return s.p.length }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `a this member assigned in its function from an untyped value stays silent`() {
        val rows = rows("interface WS { binaryType: string }\ninterface Cfg { Ctor?: { new (url: string): WS } }\nclass S { private _s: WS | null = null; private _c!: Cfg;\n  m() { const { Ctor } = this._c; let s: WS | null = null; s = new Ctor!('u'); this._s = s; this._s.binaryType = '' } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `a truthy optional element access narrows a member path`() {
        val rows = rows("interface Sym { declarations?: string[] }\ninterface Ty { flags: number; symbol: Sym }\ndeclare function h(s: string): void;\nfunction f(source: Ty) { if (source.flags & 1 && source.symbol?.declarations?.[0]) { h(source.symbol.declarations[0]) } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `a truthy optional element access narrows its receiver`() {
        val rows = rows("function f(d: string[] | undefined) { if (d?.[0]) { return d[0] } }")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `a falsy optional element access does not narrow`() {
        val rows = rows("function f(d: string[] | undefined) { if (!d?.[0]) { return d[0] } }")
        assert(rows == listOf(
            "1:61 TS18048: 'd' is possibly 'undefined'.",
        ))
    }

    @Test
    fun `an optional chain value outside the receiver arm stays untyped`() {
        val rows = rows("declare const hooks: { performanceTime?: { now(): number } } | undefined;\nconst t = hooks?.performanceTime;\nexport const ts: () => number = t ? () => t.now() : Date.now;")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `an optional chain value compared with a nested function result stays silent`() {
        val rows = rows("interface Sym { declarations?: string[]; exports?: Map<string, Sym> }\ndeclare function file(n: string): { symbol: Sym };\nfunction outer() {\n    function resolveAlias(s: Sym): Sym { return s }\n    function f(node: string, symbol: Sym) {\n        const target = resolveAlias(symbol);\n        const a = file(node).symbol?.exports?.get(node);\n        if (a === target) {\n            return a.declarations\n        }\n    }\n    return f\n}")
        assert(rows == emptyList<String>())
    }

    @Test
    fun `G6 a single surviving member carries no member chain`() {
        val chains = diagnose("function f(x: string | null) { return x.nope }\nexport {}\n", STRICT)
            .filter { it.code == 2339 }.map { it.messageChain }
        assert(chains == listOf(emptyList<String>()))
    }

    @Test
    fun `G6 two surviving members keep the member chain`() {
        val chains = diagnose("function f(x: { a: number } | { b: number } | null) { return x.a }\nexport {}\n", STRICT)
            .filter { it.code == 2339 }.map { "${it.message} ${it.messageChain}" }
        assert(chains == listOf("Property 'a' does not exist on type '{ a: number; } | { b: number; }'. [  Property 'a' does not exist on type '{ b: number; }'.]"))
    }
}
