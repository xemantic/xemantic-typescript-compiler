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
 * (CHK.179)(a2) and (c). (a2): an element access whose key is NOT a literal but a written
 * `string` / `number` — tsgo's `getPropertyTypeForIndexType` has no property name for it and
 * reports, under `noImplicitAny` only, TS7053 at the whole access with the chain
 * `No index signature with a parameter of type 'string' was found on type 'R'.` (TS7015 at the
 * key for a number-indexed receiver, TS7052 for a matching `get` accessor); a literal key on a
 * CAST receiver reaches the (a) restatement too. (c): `u!.a`, `(u satisfies U).a` and
 * `const v = x as U; v.a` on a union lacking `a` report TS2339 naming the alias. Every
 * expectation is tsgo 7.0.2's row (`build/bench/p18236-agent/pins/`), rendered
 * `line,col: TScode message | chain`.
 */
class Chk179StringKeyAndWrappedReceiverTest {

    private val prelude = """
        type A = { k: "a", a: string }
        type B = { k: "b" }
        type U = A | B
        type O = { k: string }
        interface I { k: string }
        interface N { [n: number]: string }
        interface G { get(key: string): number }
        class C { k = 1 }
    """.trimIndent() + "\n"

    private fun rows(directives: String, body: String): List<String> =
        diagnose(prelude + body.trimIndent() + "\nexport {}", directives).map { d ->
            (listOf("${d.line},${d.character}: TS${d.code} ${d.message}") + d.messageChain.map { it.trim() })
                .joinToString(" | ")
        }

    @Test
    fun `a string key read on a type literal without an index signature is TS7053 with the no-index-signature chain`() {
        val r = rows(STRICT, """
            function f(o: O, k: string) { return o[k] }
        """)
        assert(r == listOf(
            "9,38: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'O'. | No index signature with a parameter of type 'string' was found on type 'O'.",
        ))
    }

    @Test
    fun `a string key write is TS7053 too`() {
        val r = rows(STRICT, """
            function f(o: O, k: string) { o[k] = "x" }
        """)
        assert(r == listOf(
            "9,31: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'O'. | No index signature with a parameter of type 'string' was found on type 'O'.",
        ))
    }

    @Test
    fun `without noImplicitAny a string key read and write are silent`() {
        val r = rows(LOOSE, """
            function f(o: O, k: string) { o[k] = "x"; return o[k] }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `a union receiver prints its alias name`() {
        val r = rows(STRICT, """
            function f(u: U, k: string) { return u[k] }
        """)
        assert(r == listOf(
            "9,38: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'U'. | No index signature with a parameter of type 'string' was found on type 'U'.",
        ))
    }

    @Test
    fun `a number key names number in the message and the chain`() {
        val r = rows(STRICT, """
            function f(o: O, n: number) { return o[n] }
        """)
        assert(r == listOf(
            "9,38: TS7053 Element implicitly has an 'any' type because expression of type 'number' can't be used to index type 'O'. | No index signature with a parameter of type 'number' was found on type 'O'.",
        ))
    }

    @Test
    fun `a string or number key prints the whole key type and chains string`() {
        val r = rows(STRICT, """
            function f(o: O, k: string | number) { return o[k] }
        """)
        assert(r == listOf(
            "9,47: TS7053 Element implicitly has an 'any' type because expression of type 'string | number' can't be used to index type 'O'. | No index signature with a parameter of type 'string' was found on type 'O'.",
        ))
    }

    @Test
    fun `a class instance receiver is TS7053`() {
        val r = rows(STRICT, """
            function f(c: C, k: string) { return c[k] }
        """)
        assert(r == listOf(
            "9,38: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'C'. | No index signature with a parameter of type 'string' was found on type 'C'.",
        ))
    }

    @Test
    fun `an array receiver answers TS7015 at the key`() {
        val r = rows(STRICT, """
            function f(xs: number[], k: string) { return xs[k] }
        """)
        assert(r == listOf(
            "9,49: TS7015 Element implicitly has an 'any' type because index expression is not of type 'number'.",
        ))
    }

    @Test
    fun `a tuple receiver answers TS7015 at the key`() {
        val r = rows(STRICT, """
            function f(t: [number, string], k: string) { return t[k] }
        """)
        assert(r == listOf(
            "9,55: TS7015 Element implicitly has an 'any' type because index expression is not of type 'number'.",
        ))
    }

    @Test
    fun `a string receiver answers TS7015 at the key`() {
        val r = rows(STRICT, """
            function f(s: string, k: string) { return s[k] }
        """)
        assert(r == listOf(
            "9,45: TS7015 Element implicitly has an 'any' type because index expression is not of type 'number'.",
        ))
    }

    @Test
    fun `a user type with only a number index answers TS7015 at the key`() {
        val r = rows(STRICT, """
            function f(x: N, k: string) { return x[k] }
        """)
        assert(r == listOf(
            "9,40: TS7015 Element implicitly has an 'any' type because index expression is not of type 'number'.",
        ))
    }

    @Test
    fun `a receiver with a get accessor taking a string answers TS7052`() {
        val r = rows(STRICT, """
            function f(g: G, k: string) { return g[k] }
        """)
        assert(r == listOf(
            "9,38: TS7052 Element implicitly has an 'any' type because type 'G' has no index signature. Did you mean to call 'g.get'?",
        ))
    }

    @Test
    fun `a Map receiver answers TS7052 naming the get accessor`() {
        val r = rows(STRICT, """
            function f(m: Map<string, number>, k: string) { return m[k] }
        """)
        assert(r == listOf(
            "9,56: TS7052 Element implicitly has an 'any' type because type 'Map<string, number>' has no index signature. Did you mean to call 'm.get'?",
        ))
    }

    @Test
    fun `a for-in variable over a plain object is a string key`() {
        val r = rows(STRICT, """
            function f(o: O) { for (const q in o) { o[q] } }
        """)
        assert(r == listOf(
            "9,41: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'O'. | No index signature with a parameter of type 'string' was found on type 'O'.",
        ))
    }

    @Test
    fun `an unconstrained type parameter receiver is indexed as unknown`() {
        val r = rows(STRICT, """
            function f<T>(t: T, k: string) { return t[k] }
        """)
        assert(r == listOf(
            "9,41: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'unknown'. | No index signature with a parameter of type 'string' was found on type 'unknown'.",
        ))
    }

    @Test
    fun `a constrained type parameter receiver is indexed as its constraint`() {
        val r = rows(STRICT, """
            function f<T extends O>(t: T, k: string) { return t[k] }
        """)
        assert(r == listOf(
            "9,51: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'O'. | No index signature with a parameter of type 'string' was found on type 'O'.",
        ))
    }

    @Test
    fun `a cast receiver is TS7053`() {
        val r = rows(STRICT, """
            function f(x: unknown, k: string) { return (x as O)[k] }
        """)
        assert(r == listOf(
            "9,44: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'O'. | No index signature with a parameter of type 'string' was found on type 'O'.",
        ))
    }

    @Test
    fun `an optional-chain receiver drops the nullish part`() {
        val r = rows(STRICT, """
            function f(o: O | undefined, k: string) { return o?.[k] }
        """)
        assert(r == listOf(
            "9,50: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'O'. | No index signature with a parameter of type 'string' was found on type 'O'.",
        ))
    }

    @Test
    fun `a concatenation and a String call and a string method call are string keys`() {
        val r = rows(STRICT, """
            function f(o: O, k: string, n: number) { return [o[k + "x"], o[String(n)], o[k.toLowerCase()]] }
        """)
        assert(r == listOf(
            "9,50: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'O'. | No index signature with a parameter of type 'string' was found on type 'O'.",
            "9,62: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'O'. | No index signature with a parameter of type 'string' was found on type 'O'.",
            "9,76: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'O'. | No index signature with a parameter of type 'string' was found on type 'O'.",
        ))
    }

    @Test
    fun `negative control - a keyof key is silent`() {
        val r = rows(STRICT, """
            function f(o: O, key: keyof O) { return o[key] }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a key narrowed to a present literal is silent`() {
        val r = rows(STRICT, """
            function f(o: O, k: string) { if (k === "k") return o[k] }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a const key initialized with a literal is silent`() {
        val r = rows(STRICT, """
            function f(o: O) { const c = "k"; return o[c] }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a receiver with a string index signature is silent`() {
        val r = rows(STRICT, """
            function f(r: { [key: string]: number }, k: string) { return r[k] }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a for-in variable over a type parameter is silent`() {
        val r = rows(STRICT, """
            function f<T>(t: T) { for (const q in t) { t[q] } }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - a for-in variable over an array is silent`() {
        val r = rows(STRICT, """
            function f(xs: number[]) { for (const q in xs) { xs[q] } }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `an assigned string parameter is still a string key`() {
        val r = rows(STRICT, """
            function f(o: O, key: string) { key = "k"; return o[key] }
        """)
        assert(r == listOf(
            "9,51: TS7053 Element implicitly has an 'any' type because expression of type 'string' can't be used to index type 'O'. | No index signature with a parameter of type 'string' was found on type 'O'.",
        ))
    }

    @Test
    fun `a cast to a union read with a missing literal key is TS7053 naming the alias`() {
        val r = rows(STRICT, """
            function f(x: unknown) { return (x as U)["a"] }
        """)
        assert(r == listOf(
            "9,33: TS7053 Element implicitly has an 'any' type because expression of type '\"a\"' can't be used to index type 'U'. | Property 'a' does not exist on type 'U'.",
        ))
    }

    @Test
    fun `negative control - without noImplicitAny the cast literal key is silent`() {
        val r = rows(LOOSE, """
            function f(x: unknown) { return (x as U)["a"] }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `a cast to an interface read with a missing numeric key is TS7053`() {
        val r = rows(STRICT, """
            function f(x: unknown) { return (x as I)[0] }
        """)
        assert(r == listOf(
            "9,33: TS7053 Element implicitly has an 'any' type because expression of type '0' can't be used to index type 'I'. | Property '0' does not exist on type 'I'.",
        ))
    }

    @Test
    fun `a non-null asserted union receiver reports the missing member with the alias and a constituent chain`() {
        val r = rows(STRICT, """
            function f(u: U | undefined) { return u!.a }
        """)
        assert(r == listOf(
            "9,42: TS2339 Property 'a' does not exist on type 'U'. | Property 'a' does not exist on type 'B'.",
        ))
    }

    @Test
    fun `the non-null asserted union receiver reports without noImplicitAny too`() {
        val r = rows(LOOSE, """
            function f(u: U | undefined) { return u!.a }
        """)
        assert(r == listOf(
            "9,42: TS2339 Property 'a' does not exist on type 'U'. | Property 'a' does not exist on type 'B'.",
        ))
    }

    @Test
    fun `a satisfies-wrapped union receiver reports the missing member`() {
        val r = rows(STRICT, """
            function f(u: U) { return (u satisfies U).a }
        """)
        assert(r == listOf(
            "9,43: TS2339 Property 'a' does not exist on type 'U'. | Property 'a' does not exist on type 'B'.",
        ))
    }

    @Test
    fun `a const initialized with a cast to a union reports the missing member naming the alias`() {
        val r = rows(STRICT, """
            function f(x: unknown) { const v = x as U; return v.a }
        """)
        assert(r == listOf(
            "9,53: TS2339 Property 'a' does not exist on type 'U'. | Property 'a' does not exist on type 'B'.",
        ))
    }

    @Test
    fun `negative control - narrowed non-null satisfies and cast-const receivers are silent`() {
        val r = rows(STRICT, """
            function f(u: U | undefined, x: unknown) { const v = x as U; if (u && u.k === "a") u!.a; if (v.k === "a") v.a; if (u?.k === "a") (u satisfies U | undefined) }
        """)
        assert(r.isEmpty())
    }

    @Test
    fun `a non-null asserted union element access with a missing literal key is TS7053`() {
        val r = rows(STRICT, """
            function f(u: U | undefined) { return u!["a"] }
        """)
        assert(r == listOf(
            "9,39: TS7053 Element implicitly has an 'any' type because expression of type '\"a\"' can't be used to index type 'U'. | Property 'a' does not exist on type 'U'.",
        ))
    }

    @Test
    fun `a const initialized with an angle-bracket cast reports the missing member`() {
        val r = rows(STRICT, """
            function f(x: unknown) { const v = <U>x; return v.a }
        """)
        assert(r == listOf(
            "9,51: TS2339 Property 'a' does not exist on type 'U'. | Property 'a' does not exist on type 'B'.",
        ))
    }

    @Test
    fun `negative control - a receiver typed by an overload pick is not trusted`() {
        // tsgo picks the second overload (a mapped type with a string index); this checker's
        // pick is the first, whose object type has no index signature - only a WRITTEN
        // receiver type is believed.
        val r = rows(STRICT, """
            declare function groupBy<T, U extends T>(values: readonly T[], keySelector: (value: T) => value is U): { true?: U[]; false?: Exclude<T, U>[]; };
            declare function groupBy<T, K extends string | number | boolean>(values: readonly T[], keySelector: (value: T) => K): { [P in K as `${"$"}{P}`]?: T[]; };
            function f(xs: string[], k: string) {
                const g = groupBy(xs, x => x + "!");
                for (const q in g) { g[q]; }
                return g[k];
            }
        """)
        assert(r.isEmpty())
    }

    private companion object {
        const val STRICT = "// @strict: true\n// @target: es2022\n// @lib: es2022\n// @useRealLibs: true"
        const val LOOSE = "// @strict: false\n// @target: es2022\n// @lib: es2022\n// @useRealLibs: true"
    }
}
