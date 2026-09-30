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
 * (CHK.173) B6 — the final G1 round: a BODY LOCAL receiver (`const x = maybe(); x.length`)
 * reports tsgo's TS18047 / TS18048 / TS18049 (TS2531 when parenthesized). The member-access
 * walker reads a body local as `any`, so `Checker.bodyLocalReceiverDeclaredType` resolves its
 * declared type at the receiver: the annotation, else the initializer (a reference narrowed at
 * its own position; `&&` / `||` / `??` and a conditional with their operands narrowed — N11),
 * else a destructured leaf's type (B5c) — then refuses where this checker cannot see what tsgo
 * sees: a leaf after an element-access guard (B5c's residue), an initializer naming a binding the
 * read's scope shadows, a read in an object-literal / class-expression method (tsgo extends the
 * flow container there, this flow graph does not), and the UNRESOLVED-RHS VETO — a reaching
 * assignment whose right operand types `any` or names a block shadow, an optional-chain
 * comparison against an untyped operand (N10), or every reaching assignment non-nullish (that
 * last arm is discriminated by the marked library's `Instance.ts`, ten rows, not by a pin here:
 * the census could not reduce it below the library's own type files).
 *
 * The last two pins cover the cost fix of the same round: an `asserts x is Box<U>` target whose
 * `U` no guard argument binds (tsc's first `Debug.assertEachNode` overload; tsgo picks a
 * later, non-asserting one) no longer narrows to the unbound generic.
 *
 * Every expectation is tsgo 7.0.2's output for the same text (the `// @strict: true`
 * directive line this harness consumes is not part of it). Measured costs, not pinned (tsgo
 * reports, this is silent): a genuine `any` assignment (`s = anyv; s.length`) the veto refuses;
 * a generic inference this checker leaves as `U | undefined`; a receiver named after a lib
 * global (`top`).
 */
class BodyLocalNullableReceiverTest {

    private val prelude = "declare function g(): string | null\ndeclare function u(): string | undefined\ndeclare const c: boolean\n"

    private fun rows(source: String): List<String> =
        diagnose(prelude + source.trimIndent())
            .filter { it.code == 18047 || it.code == 18048 || it.code == 18049 || it.code == 2531 }
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    @Test
    fun `an annotated let from a call reports`() {
        val rows = rows("""
            function f() {
                let s: string | null = g()
                return s.length
            }
        """)
        assert(rows == listOf("6:12 TS18047 's' is possibly 'null'."))
    }

    @Test
    fun `an identifier initializer is typed at its own position`() {
        val rows = rows("""
            function f(x: string | null) {
                const y = x
                return y.length
            }
        """)
        assert(rows == listOf("6:12 TS18047 'y' is possibly 'null'."))
    }

    @Test
    fun `an annotated let with no initializer holds undefined`() {
        val rows = rows("""
            function f() {
                let s: string | undefined
                return s.length
            }
        """)
        assert(rows == listOf("6:12 TS18048 's' is possibly 'undefined'."))
    }

    @Test
    fun `a conditional assignment leaves undefined on the other path`() {
        val rows = rows("""
            function f() {
                let y: string | undefined
                if (c) y = 'a'
                return y.length
            }
        """)
        assert(rows == listOf("7:12 TS18048 'y' is possibly 'undefined'."))
    }

    @Test
    fun `an object destructuring leaf reports without the R3 refusal`() {
        val rows = rows("""
            function f(o: { x?: string }) {
                const { x } = o
                return x.length
            }
        """)
        assert(rows == listOf("6:12 TS18048 'x' is possibly 'undefined'."))
    }

    @Test
    fun `an array destructuring leaf reports`() {
        val rows = rows("""
            function f(t: [string | null]) {
                const [a] = t
                return a.length
            }
        """)
        assert(rows == listOf("6:12 TS18047 'a' is possibly 'null'."))
    }

    @Test
    fun `N11 an and-and initializer narrows its left operand and keeps the right's undefined`() {
        val rows = rows("""
            interface I { t: string }
            declare function fi(x: I): I | undefined
            function f(p: I | undefined) {
                if (!p) return
                const r = p && fi(p)
                return r.t
            }
        """)
        assert(rows == listOf("9:12 TS18048 'r' is possibly 'undefined'."))
    }

    @Test
    fun `N11 an and-and initializer whose right is non-nullish is silent`() {
        val rows = rows("""
            interface I { t: string }
            declare function fi(x: I): I
            function f(p: I | undefined) {
                if (!p) return
                const r = p && fi(p)
                return r.t
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `an and-and-equals keeps null on the short-circuit path`() {
        val rows = rows("""
            function f() {
                let s: string | null = g()
                s &&= s.trim()
                return s.length
            }
        """)
        assert(rows == listOf("7:12 TS18047 's' is possibly 'null'."))
    }

    @Test
    fun `a mutable local read in an arrow reports`() {
        val rows = rows("""
            function f() {
                let x: string | null = g()
                const h = () => x.length
                return h
            }
        """)
        assert(rows == listOf("6:21 TS18047 'x' is possibly 'null'."))
    }

    @Test
    fun `a hoisted function declaration sees the declared type of a captured const`() {
        val rows = rows("""
            function f() {
                const x: string | null = 'a'
                function h() { return x.length }
                return h
            }
        """)
        assert(rows == listOf("6:27 TS18047 'x' is possibly 'null'."))
    }

    @Test
    fun `a parenthesized body local is TS2531`() {
        val rows = rows("""
            function f() {
                const s: string | null = g()
                return (s).length
            }
        """)
        assert(rows == listOf("6:12 TS2531 Object is possibly 'null'."))
    }

    @Test
    fun `a conditional initializer unions both branches`() {
        val rows = rows("""
            function f(n: string | undefined) {
                if (!n) return
                const a = c ? n : u()
                return a.length
            }
        """)
        assert(rows == listOf("7:12 TS18048 'a' is possibly 'undefined'."))
    }

    @Test
    fun `a conditional initializer of narrowed branches is silent`() {
        val rows = rows("""
            function f(n: string | undefined) {
                if (!n) return
                const a = c ? n : 'z'
                return a.length
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a nullish-coalescing initializer with a narrowed right operand is silent`() {
        val rows = rows("""
            function f(n: string | undefined) {
                if (!n) return
                const tq = u() ?? n
                return tq.length
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a nullish-coalescing initializer with an unguarded right operand reports`() {
        val rows = rows("""
            function f(n: string | undefined) {
                const tq = u() ?? n
                return tq.length
            }
        """)
        assert(rows == listOf("6:12 TS18048 'tq' is possibly 'undefined'."))
    }

    @Test
    fun `a leaf destructured after an element-access guard is refused`() {
        val rows = rows("""
            function f(t: [string | null]) {
                if (!t[0]) return
                const [a] = t
                return a.length
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `an assignment whose right operand names a block shadow is refused`() {
        val rows = rows("""
            function f(s: string | null) {
                let t = s
                { const s = 'x'; t = s }
                return t.length
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `N10 an optional-chain comparison against an untyped operand is refused`() {
        val rows = rows("""
            interface N { parent: N | null; end: number }
            function f(ds: N[]) {
                let prev: N | undefined
                for (const d of ds) {
                    const n = d
                    if (prev?.parent === n.parent && prev.end) {}
                    prev = n
                }
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `N17 an assignment whose right operand types any is refused`() {
        val rows = rows("""
            interface Sub { unsubscribe(): void }
            interface Obs<T> { subscribe(x: (v: T) => void): Sub }
            declare function operate<T, R>(f: (source: Obs<T>, r: R) => void): (s: Obs<T>) => void
            export function op<T>() {
                return operate<T, T>((source, r) => {
                    let inner: Sub | null = null
                    inner = source.subscribe(() => {})
                    if (c) { inner.unsubscribe() }
                })
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a read inside a class-expression method is refused`() {
        val rows = rows("""
            function f() {
                const x: string | null = 'a'
                return class { m() { return x.length } }
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a guarded read is silent`() {
        val rows = rows("""
            function f() {
                const s = g()
                if (!s) return 0
                return s.length
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `a non-nullish overwrite is silent`() {
        val rows = rows("""
            function f() {
                let s: string | null = null
                s = 'a'
                return s.length
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `an initializer naming a binding a nested function shadows is refused`() {
        val rows = rows("""
            function f(x: string) {
                const y = x
                function h(x: string | null) { return y.length }
                return h
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `N6 a failed generic inference is silent through the base constraint`() {
        val rows = rows("""
            declare function fd<T, U>(a: readonly T[], f: (t: T) => U | undefined): U | undefined
            function f(xs: { n?: string }[]) {
                let d = fd(xs, (x) => x.n ? x : undefined)
                if (!d) d = xs[0]
                return d.n
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `N10 an optional-chain comparison is refused with no reaching assignment`() {
        val rows = rows("""
            function f() {
                let info: { pos: number } | undefined
                let pos = 0
                function h() { return info?.pos === pos ? info.pos : 0 }
                info = { pos: 1 }
                return h
            }
        """)
        assert(rows.isEmpty())
    }

    @Test
    fun `an in-scope constrained type parameter reads its constraint`() {
        val rows = rows("""
            declare function first<T>(a: T[]): T | undefined
            function f<T extends { n: number }>(a: T[]) {
                const x = first(a)
                return x.n
            }
        """)
        assert(rows == listOf("7:12 TS18048 'x' is possibly 'undefined'."))
    }

    @Test
    fun `a body local typed by a nullable-constrained type parameter reports`() {
        val rows = rows("""
            function f<T extends string | null>(v: T) {
                const x = v
                return x.length
            }
        """)
        assert(rows == listOf("6:12 TS18047 'x' is possibly 'null'."))
    }

    @Test
    fun `an assertion target naming a callee type parameter the call cannot bind narrows nothing`() {
        val rows = rows("""
            interface Box<T> { v: T }
            declare function assertEach<T, U extends T>(nodes: Box<T>, test: (n: T) => n is U): asserts nodes is Box<U>
            declare function assertEach(nodes: readonly string[] | undefined, test: ((n: string) => boolean) | undefined): void
            function f(xs: string[] | undefined, t: ((n: string) => boolean) | undefined) {
                assertEach(xs, t)
                return xs.length
            }
        """)
        assert(rows == listOf("9:12 TS18048 'xs' is possibly 'undefined'."))
    }

    @Test
    fun `an assertion target whose type parameter a guard argument binds still narrows`() {
        val rows = rows("""
            interface Box<T> { v: T }
            declare function isStr(n: unknown): n is string
            declare function assertEach<T, U extends T>(nodes: Box<T> | undefined, test: (n: T) => n is U): asserts nodes is Box<U>
            function f(xs: Box<unknown> | undefined) {
                assertEach(xs, isStr)
                return xs.v
            }
        """)
        assert(rows.isEmpty())
    }

}
