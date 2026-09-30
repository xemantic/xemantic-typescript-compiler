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
 * (CHK.179)(a) a LITERAL-key element access whose receiver lacks the member. It reported
 * TS2339 at the key (the property-access funnel's row) in every configuration; tsgo 7.0.2
 * (`getPropertyTypeForIndexType`) is SILENT without `noImplicitAny` and otherwise reports
 * TS7053 at the whole access with a `Property … does not exist` chain line — or TS7015
 * (number index), TS7052 (a matching `get`/`set` accessor), TS2576 (static member) or
 * TS2551 (spelling). Every expectation is tsgo's row (`tools/tsgo-7.0.2/lib/tsc`,
 * fixtures under `build/bench/p18235-agent/pins/`), rendered `line,col: TScode message`
 * plus the chain lines.
 */
class Chk179ElementAccessMissingMemberTest {

    private val prelude = """
        type A = { k: "a", a: string }
        type B = { k: "b" }
        type U = A | B
        interface I { k: string }
        class C { k = 1; static s = 1 }
    """.trimIndent() + "\n"

    private fun rows(directives: String, body: String): List<String> =
        diagnose(prelude + body.trimIndent() + "\nexport {}", directives).map { d ->
            (listOf("${d.line},${d.character}: TS${d.code} ${d.message}") + d.messageChain.map { it.trim() })
                .joinToString(" | ")
        }

    @Test
    fun `a union receiver read with a missing literal key is TS7053 at the whole access`() {
        val r = rows("// @strict: true", """
            declare const u: U
            const v = u["a"]
        """)
        assert(r == listOf(
            "7,11: TS7053 Element implicitly has an 'any' type because expression of type '\"a\"' can't be used to index type 'U'. | Property 'a' does not exist on type 'U'.",
        ))
    }

    @Test
    fun `a union receiver write with a missing literal key is TS7053`() {
        val r = rows("// @strict: true", """
            function f(u: U) { u["a"] = 1 }
        """)
        assert(r == listOf(
            "6,20: TS7053 Element implicitly has an 'any' type because expression of type '\"a\"' can't be used to index type 'U'. | Property 'a' does not exist on type 'U'.",
        ))
    }

    @Test
    fun `without noImplicitAny a missing literal key on a union is silent for read write and number keys`() {
        val r = rows("// @strict: false", """
            declare const u: U
            const v = u["a"]
            const w = u[0]
            u["a"] = 1
        """)
        assert(r == emptyList<String>())
    }

    @Test
    fun `an interface receiver with a missing literal key is TS7053`() {
        val r = rows("// @strict: true", """
            declare const i: I
            const v = i["a"]
        """)
        assert(r == listOf(
            "7,11: TS7053 Element implicitly has an 'any' type because expression of type '\"a\"' can't be used to index type 'I'. | Property 'a' does not exist on type 'I'.",
        ))
    }

    @Test
    fun `without noImplicitAny an interface or class receiver is silent`() {
        val r = rows("// @strict: false", """
            declare const i: I
            const v = i["a"]
            declare const c: C
            const w = c[0]
        """)
        assert(r == emptyList<String>())
    }

    @Test
    fun `a class instance with a missing numeric key is TS7053 naming the number`() {
        val r = rows("// @strict: true", """
            declare const c: C
            const v = c[0]
        """)
        assert(r == listOf(
            "7,11: TS7053 Element implicitly has an 'any' type because expression of type '0' can't be used to index type 'C'. | Property '0' does not exist on type 'C'.",
        ))
    }

    @Test
    fun `a cast interface receiver is TS7053 anchored at the parenthesis`() {
        val r = rows("// @strict: true", """
            declare const x: unknown
            const v = (x as I)["a"]
        """)
        assert(r == listOf(
            "7,11: TS7053 Element implicitly has an 'any' type because expression of type '\"a\"' can't be used to index type 'I'. | Property 'a' does not exist on type 'I'.",
        ))
    }

    @Test
    fun `a tuple receiver with a string key is TS7015 at the key`() {
        val r = rows("// @strict: true", """
            declare const t: [number]
            const v = t["a"]
        """)
        assert(r == listOf(
            "7,13: TS7015 Element implicitly has an 'any' type because index expression is not of type 'number'.",
        ))
    }

    @Test
    fun `a constrained type parameter receiver displays its constraint`() {
        val r = rows("// @strict: true", """
            function g<T extends { a: string }>(x: T) { return x["zz"] }
        """)
        assert(r == listOf(
            "6,52: TS7053 Element implicitly has an 'any' type because expression of type '\"zz\"' can't be used to index type '{ a: string; }'. | Property 'zz' does not exist on type '{ a: string; }'.",
        ))
    }

    @Test
    fun `a string constrained type parameter receiver is TS7015`() {
        val r = rows("// @strict: true", """
            function g<T extends string>(x: T) { return x["zz"] }
        """)
        assert(r == listOf(
            "6,47: TS7015 Element implicitly has an 'any' type because index expression is not of type 'number'.",
        ))
    }

    @Test
    fun `an interface receiver with a get and set method is TS7052 naming the read or write accessor`() {
        val r = rows("// @strict: true", """
            interface Store { get(k: string): number; set(k: string, v: number): void }
            declare const st: Store
            const v = st["zz"]
            st["zz"] = 1
        """)
        assert(r == listOf(
            "8,11: TS7052 Element implicitly has an 'any' type because type 'Store' has no index signature. Did you mean to call 'st.get'?",
            "9,1: TS7052 Element implicitly has an 'any' type because type 'Store' has no index signature. Did you mean to call 'st.set'?",
        ))
    }

    @Test
    fun `a generic interface accessor is instantiated before the key is tested`() {
        val r = rows("// @strict: true", """
            interface Box<K> { get(k: K): number }
            declare const bn: Box<number>
            declare const bs: { b: Box<string> }
            const v = bn["zz"]
            const w = bs.b["zz"]
        """)
        assert(r == listOf(
            "9,11: TS7053 Element implicitly has an 'any' type because expression of type '\"zz\"' can't be used to index type 'Box<number>'. | Property 'zz' does not exist on type 'Box<number>'.",
            "10,11: TS7052 Element implicitly has an 'any' type because type 'Box<string>' has no index signature. Did you mean to call 'bs.b.get'?",
        ))
    }

    @Test
    fun `without noImplicitAny the static member and spelling rows are silent too`() {
        val r = rows("// @strict: false", """
            declare const c: C
            const v = c["s"]
            interface N { name: string }
            declare const n: N
            const w = n["nme"]
        """)
        assert(r == emptyList<String>())
    }

    @Test
    fun `under noImplicitAny the static member and spelling rows stand`() {
        val r = rows("// @strict: true", """
            declare const c: C
            const v = c["s"]
            interface N { name: string }
            declare const n: N
            const w = n["nme"]
        """)
        assert(r == listOf(
            "7,11: TS2576 Property 's' does not exist on type 'C'. Did you mean to access the static member 'C[\"s\"]' instead?",
            "10,13: TS2551 Property 'nme' does not exist on type 'N'. Did you mean 'name'?",
        ))
    }

    @Test
    fun `strict with noImplicitAny false is silent`() {
        val r = rows("// @strict: true\n// @noImplicitAny: false", """
            declare const u: U
            const v = u["a"]
            declare const i: I
            const w = i[0]
        """)
        assert(r == emptyList<String>())
    }

    @Test
    fun `an unset strict defaults noImplicitAny on`() {
        val r = rows("", """
            declare const u: U
            const v = u["a"]
        """)
        assert(r == listOf(
            "7,11: TS7053 Element implicitly has an 'any' type because expression of type '\"a\"' can't be used to index type 'U'. | Property 'a' does not exist on type 'U'.",
        ))
    }

    @Test
    fun `negative control - a present literal key reports nothing`() {
        val r = rows("// @strict: true", """
            declare const u: U
            const v = u["k"]
            declare const i: I
            const w = i["k"]
        """)
        assert(r == emptyList<String>())
    }

    @Test
    fun `a class instance with get and set methods is TS7052 naming the read or write accessor`() {
        val r = rows("// @strict: true", """
            class Cache { get(k: string) { return 1 } set(k: string, v: number) {} }
            declare const ca: Cache
            const v = ca["zz"]
            ca["zz"] = 1
        """)
        assert(r == listOf(
            "8,11: TS7052 Element implicitly has an 'any' type because type 'Cache' has no index signature. Did you mean to call 'ca.get'?",
            "9,1: TS7052 Element implicitly has an 'any' type because type 'Cache' has no index signature. Did you mean to call 'ca.set'?",
        ))
    }

    @Test
    fun `a generic class accessor is instantiated before the key is tested`() {
        val r = rows("// @strict: true", """
            class GCache<K> { get(k: K) { return 1 } }
            declare const gn: GCache<number>
            declare const gs: GCache<string>
            const v = gn["zz"]
            const w = gs["zz"]
        """)
        assert(r == listOf(
            "9,11: TS7053 Element implicitly has an 'any' type because expression of type '\"zz\"' can't be used to index type 'GCache<number>'. | Property 'zz' does not exist on type 'GCache<number>'.",
            "10,11: TS7052 Element implicitly has an 'any' type because type 'GCache<string>' has no index signature. Did you mean to call 'gs.get'?",
        ))
    }

    @Test
    fun `negative control - a numeric key present on some tuple member reports nothing`() {
        val r = rows("// @strict: true", """
            declare const t: [number] | [number, string]
            const v = t[1]
        """)
        assert(r == emptyList<String>())
    }
}
