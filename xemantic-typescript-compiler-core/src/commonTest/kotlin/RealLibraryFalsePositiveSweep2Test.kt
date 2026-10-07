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
 * (P18.313) (LIBS.4) round 2 — false positives in zod and hono, each reduced to a fixture.
 * Every full-text expected list is tsgo 7.0.2's output on the identical source; where our
 * row is at tsgo's position but differs in text, only the code and line are asserted and
 * the KDoc says so. One residue is NOT pinned: `groups[0] = [1, 2]` against
 * `[string, string][]` — tsgo reports two element rows, we now report none (the array
 * literal -> tuple pair this engine cannot decide, round 459).
 */
class RealLibraryFalsePositiveSweep2Test {

    private val directives = "// @strict: true\n// @useRealLibs: true\n// @lib: esnext\n// @target: es2022"

    private fun rows(d: List<Diagnostic>): List<String> =
        d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    @Test
    fun `a for-of loop variable over a this member is typed by the member's element type`() {
        val d = diagnose(
            """
            class C {
              xs: number[] = [];
              m() {
                for (const x of this.xs) { const s: string = x; }
              }
            }
            abstract class Base<Out> { arr: Out[] = []; }
            class B extends Base<bigint> {
              m() {
                const a2 = this.arr; const t: string = a2;
                for (const y of a2) { const u: string = y; }
                for (const z of this.arr) { const v: string = z; }
              }
            }
            function f(this: C) { for (const w of this.xs) { const s: string = w; } }
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "4:38 TS2322 Type 'number' is not assignable to type 'string'.",
                "10:32 TS2322 Type 'bigint[]' is not assignable to type 'string'.",
                "11:33 TS2322 Type 'bigint' is not assignable to type 'string'.",
                "12:39 TS2322 Type 'bigint' is not assignable to type 'string'.",
                "15:56 TS2322 Type 'number' is not assignable to type 'string'.",
            )
        )
    }

    @Test
    fun `an any modulo a bigint member read through a this member loop variable is bigint`() {
        val d = diagnose(
            """
            interface Def0 { typeName: string }
            type Check = { kind: "min"; value: bigint } | { kind: "max"; value: bigint };
            interface BDef extends Def0 { checks: Check[] }
            abstract class Base<Out = any, Def extends Def0 = Def0> {
              readonly _def!: Def;
            }
            export class B extends Base<bigint, BDef> {
              m(data: any) {
                for (const check of this._def.checks) {
                  if (data % check.value !== BigInt(0)) {}
                }
              }
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a two-any arithmetic number is not evidence for a bigint no-overlap row`() {
        val d = diagnose(
            """
            type Check = { kind: "min"; value: bigint } | { kind: "max"; value: bigint };
            declare const k: Check; declare const b: bigint; declare const o: { value: bigint };
            function m(data: any, p: Check) {
              if (data % k.value !== BigInt(0)) {}
              if (data % b !== BigInt(0)) {}
              if (data % o.value !== BigInt(0)) {}
              if (data % p.value !== BigInt(0)) {}
              const local = p; if (data % local.value !== BigInt(0)) {}
              for (const c of [p]) { if (data % c.value !== BigInt(0)) {} }
            }
            declare const a: any; declare const n: number;
            if (a % a !== BigInt(0)) {}
            if (a % n !== BigInt(0)) {}
            if (BigInt(1) === (a * a)) {}
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "12:5 TS2367 This comparison appears to be unintentional because the types 'number' and 'bigint' have no overlap.",
                "13:5 TS2367 This comparison appears to be unintentional because the types 'number' and 'bigint' have no overlap.",
                "14:5 TS2367 This comparison appears to be unintentional because the types 'bigint' and 'number' have no overlap.",
            )
        )
    }

    @Test
    fun `an array literal arm fits a fixed tuple member of a union return type`() {
        val d = diagnose(
            """
            const t1: [string] | [] = []
            function g1(): [string] | [] { return [] }
            function g2(b: boolean): [string] | [] { return b ? ['x'] : [] }
            const g3 = (b: boolean): [string] | [] => { return b ? ['x'] : [] }
            const g4 = (b: boolean): [string, number] | [] => { const s = 'q'; return b ? [s, 1] : [] }
            const g5 = (b: boolean): [Set<string>, Set<string>] | [] => { const s = new Set<string>(); return b ? [s, s] : [] }
            const g6 = (b: boolean): [Set<string>, Set<string>] | [] => { const s = new Set<string>(); return b ? [s, (s as any)] : [] }
            function g7(b: boolean): [string, number] { return b ? ['x', 1] : ['y', 2] }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - an array literal arm that fits no tuple member keeps its row`() {
        val d = diagnose(
            """
            export function g8(b: boolean): [string, number] | [] { return b ? [1, 1] : [] }
            """,
            directives,
        )
        // tsgo: 1:69 TS2322 Type 'number' is not assignable to type 'string'. — we report the
        // whole arm at the same line.
        assert(d.map { it.line to it.code } == listOf(1 to 2322))
    }

    @Test
    fun `an array literal or a conditional of them written into a tuple element slot is not reported`() {
        val d = diagnose(
            """
            const groups: [string, string][] = []
            groups[0] = ['a', 'b']
            function f2(s: string) { s.replace(/x/g, (m) => { groups[1] = [m, m]; return m }) }
            type Pattern = readonly [string, string, RegExp | true] | '*'
            const patternCache: { [key: string]: Pattern } = {}
            function f3(k: string, n: boolean, m: string[]) {
              patternCache[k] = n ? [k, m[1], new RegExp('x')] : [k, m[1], new RegExp('y')]
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `an array literal keeps its element literals under a union with an array constituent`() {
        val d = diagnose(
            """
            type DT = 'path' | 'querystring'
            type CT = 'cookie'
            interface O1 { order: DT[]; caches: CT[] | false }
            export const a1: O1 = { order: ['path'], caches: ['cookie'] }
            export const a2: O1 = { order: ['path'], caches: false }
            interface O4 { caches: CT[] | false; order: DT[] }
            export const a5: O4 = { caches: ['cookie'], order: ['path'] }
            export const b1: CT[] | false = ['cookie']
            export function b2(): CT[] | false { return ['cookie'] }
            let b3: CT[] | false = false; b3 = ['cookie']
            const b4: { [k: string]: CT[] | false } = {}; b4.x = ['cookie']; b4['y'] = ['cookie']
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - a wrong element literal under an array union still reports`() {
        val d = diagnose(
            """
            type DT = 'path' | 'query'
            export const d1: { o: DT[]; c: 'c'[] | false } = { o: ['nope'], c: ['c'] }
            export const d2: { c: 'c'[] | false } = { c: ['x'] }
            """,
            directives,
        )
        // tsgo drills to the element ('"nope"' / '"x"'); we report the member / the whole
        // literal on the same lines.
        assert(d.map { it.line to it.code } == listOf(2 to 2322, 3 to 2322))
    }

    @Test
    fun `an empty object relates to a weak member type and the weak-type controls keep their rows`() {
        val d = diagnose(
            """
            interface W { a?: string; b?: number }
            export const o5: { w: W } = { w: {} }
            const e = {}
            export const o6: { w: W } = { w: e }
            enum E { A = "a" }
            export const o8: { w: W } = { w: E.A }
            const q = { c: 1 }
            export const o10: { w: W } = { w: q }
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "6:31 TS2559 Type 'E' has no properties in common with type 'W'.",
                "8:32 TS2559 Type '{ c: number; }' has no properties in common with type 'W'.",
            )
        )
    }

    @Test
    fun `a fresh object literal returned against an intersection or an awaited promise takes its literals`() {
        val d = diagnose(
            """
            interface R { status: string; body?: string; bodyEncoding?: 'text' | 'base64' }
            export const f1 = async (b: boolean): Promise<R> => {
              return { status: 'x', ...(b && { bodyEncoding: 'base64' }) }
            }
            export function f2(b: boolean): R {
              return { status: 'x', ...(b && { bodyEncoding: 'base64' }) }
            }
            export const r3: R = { status: 'x', ...(true && { bodyEncoding: 'base64' }) }
            export function f4(b: boolean): R { return { status: 'x', bodyEncoding: b ? 'base64' : undefined } }
            export function f5(b: boolean): R & { duplex?: 'half' } { return { status: 'x', duplex: b ? 'half' : undefined } }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a spread of a union with disagreeing members is not a missing-member verdict`() {
        val d = diagnose(
            """
            type WH = { headers: Record<string, string> }; type WM = { multi: Record<string, string[]> }
            type AR = { code: number } & (WH | WM)
            export function f6(b: boolean): AR { const r: AR = { code: 1, ...(b ? { multi: {} } : { headers: {} }) }; return r }
            export const r7: AR = { code: 1 }
            export const r6: AR = { code: 'x', ...(Math.random() ? { multi: {} } : { headers: {} }) }
            """,
            directives,
        )
        assert(
            rows(d) == listOf(
                "4:14 TS2322 Type '{ code: number; }' is not assignable to type 'AR'.",
                "5:25 TS2322 Type 'string' is not assignable to type 'number'.",
            )
        )
    }

    @Test
    fun `negative control - a wrong literal in a returned object still reports`() {
        val d = diagnose(
            """
            interface R { status: string; body?: string; bodyEncoding?: 'text' | 'base64' }
            export const f1b = async (): Promise<R> => { return { status: 1 } }
            export function f5(b: boolean): R & { duplex?: 'half' } { return { status: 'x', duplex: b ? 'full' : undefined } }
            """,
            directives,
        )
        // tsgo: 2:55 TS2322 'number' vs 'string'; 3:81 TS2322 '"full" | undefined' vs
        // '"half" | undefined' — we report on the same lines.
        assert(d.map { it.line to it.code } == listOf(2 to 2322, 3 to 2322))
    }

    private val guardPrelude = """
        interface Coll { isLeaf(): this is Leaf; count(): number }
        declare function isL(c: Coll): c is Leaf
        export class Leaf implements Coll { constructor(public text: string) {} isLeaf(): this is Leaf { return true } count() { return 1 } }
    """.trimIndent() + "\n"

    @Test
    fun `a guard-narrowed value in a returned object-literal conditional arm relates to the return type`() {
        val d = diagnose(
            guardPrelude + """
            export class Node2 implements Coll {
              constructor(private readonly children: Coll[] = []) {}
              isLeaf(): this is Leaf { return false }
              count() { return 0 }
              m(acc: number): { position: number; leaf: Leaf | undefined; } {
                for (const child of this.children) {
                  return child.isLeaf() ? { position: acc, leaf: child } : (child as Node2).m(acc)
                }
                return { position: acc, leaf: undefined }
              }
            }
            export function m1(child: Coll): { leaf: Leaf | undefined; } {
              return child.isLeaf() ? { leaf: child } : { leaf: undefined }
            }
            export function m2(child: Coll): { leaf: Leaf | undefined; } {
              return isL(child) ? { leaf: child } : { leaf: undefined }
            }
            """.trimIndent(),
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - a narrowed object-literal arm with an excess a missing or a wrong member still reports`() {
        val d = diagnose(
            guardPrelude + """
            export function c1(child: Coll): { leaf: Leaf | undefined; } {
              return child.isLeaf() ? { leaf: child, extra: 1 } : { leaf: undefined }
            }
            export function c2(child: Coll): { leaf: Leaf; n: number } {
              return child.isLeaf() ? { leaf: child } : { leaf: child as Leaf, n: 1 }
            }
            export function c3(child: Coll, s: string): { leaf: Leaf | undefined; n: number } {
              return child.isLeaf() ? { leaf: child, n: s } : { leaf: undefined, n: 1 }
            }
            """.trimIndent(),
            directives,
        )
        // tsgo: 5:42 TS2353 'extra', 8:27 TS2741 'n', 11:42 TS2322 'string' vs 'number' — we
        // report the whole arm (TS2322) on each of the same lines, as before this round.
        assert(d.map { it.line to it.code } == listOf(5 to 2322, 8 to 2322, 11 to 2322))
    }
}
