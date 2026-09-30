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
 * (CHK.178) three defects from the (CHK.177) census, every expectation tsgo 7.0.2's row
 * (`tools/tsgo-7.0.2/lib/tsc`, fixtures under `build/bench/p18234-agent/pins/`), rendered
 * `line,col: TScode message` plus the chain lines.
 *
 * (a) the TS2345 chain names a union argument's FIRST failing constituent in the stable
 * order, as the four assignment-shaped chains have since (LEGACY.0a) — it named the LAST.
 * (b) a CAST receiver `(u as U).a` / `<U>u.a` to a union or a type-literal alias lacking the
 * member reports TS2339 — it was silent (only a simple named interface was admitted).
 * (c) an object-literal member whose value is an OBJECT (or a union holding one) against a
 * SIMPLE target member drills to the member key — it reported the whole literal.
 */
class Chk178UnionChainCastReceiverObjLitDrillTest {

    private val prelude = """
        type A = { k: "a", a: string }
        type B = { k: "b" }
        type U = A | B
    """.trimIndent() + "\n"

    private fun rows(body: String): List<String> =
        diagnose(prelude + body.trimIndent() + "\nexport {}").map { d ->
            (listOf("${d.line},${d.character}: TS${d.code} ${d.message}") + d.messageChain.map { it.trim() })
                .joinToString(" | ")
        }

    // ---- (a) the argument chain picker

    @Test
    fun `a union argument's chain names its first failing constituent`() {
        val r = rows(
            """
            declare function take(n: number): void
            declare const u: U
            take(u)
            """,
        )
        assert(r == listOf(
            "6,6: TS2345 Argument of type 'U' is not assignable to parameter of type 'number'. | " +
                "Type 'A' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `an optional union argument's chain names undefined first`() {
        val r = rows(
            """
            declare function take(n: number): void
            function f(u: U | undefined) { take(u) }
            """,
        )
        assert(r == listOf(
            "5,37: TS2345 Argument of type 'U | undefined' is not assignable to parameter of type 'number'. | " +
                "Type 'undefined' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `a primitive union argument's chain names its first failing constituent`() {
        val r = rows(
            """
            declare function take(n: number): void
            declare const v: string | boolean
            take(v)
            """,
        )
        assert(r == listOf(
            "6,6: TS2345 Argument of type 'string | boolean' is not assignable to parameter of type 'number'. | " +
                "Type 'string' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `negative control - a union whose first constituent relates names the one that fails`() {
        val r = rows(
            """
            declare function take(n: number): void
            declare const v: number | A
            take(v)
            """,
        )
        assert(r == listOf(
            "6,6: TS2345 Argument of type 'number | A' is not assignable to parameter of type 'number'. | " +
                "Type 'A' is not assignable to type 'number'.",
        ))
    }

    // ---- (b) the cast receiver

    @Test
    fun `an as-cast receiver to a union lacking the member reports TS2339`() {
        val r = rows("function f(u: U) { return (u as U).a }")
        assert(r == listOf(
            "4,36: TS2339 Property 'a' does not exist on type 'U'. | Property 'a' does not exist on type 'B'.",
        ))
    }

    @Test
    fun `an angle-bracket cast receiver to a union lacking the member reports TS2339`() {
        val r = rows("function f(u: U) { return (<U>u).a }")
        assert(r == listOf(
            "4,34: TS2339 Property 'a' does not exist on type 'U'. | Property 'a' does not exist on type 'B'.",
        ))
    }

    @Test
    fun `a cast receiver to a type-literal alias lacking the member reports TS2339`() {
        val r = rows(
            """
            declare const w: unknown
            const z = (w as B).a
            """,
        )
        assert(r == listOf("5,20: TS2339 Property 'a' does not exist on type 'B'."))
    }

    @Test
    fun `a cast receiver to a mixed union names the constituent lacking the member`() {
        val r = rows(
            """
            declare const w: unknown
            const z = (w as A | string).a
            """,
        )
        assert(r == listOf(
            "5,29: TS2339 Property 'a' does not exist on type 'string | A'. | Property 'a' does not exist on type 'string'.",
        ))
    }

    @Test
    fun `negative control - a cast receiver whose every constituent has the member`() {
        assert(rows("function f(u: U) { return (u as U).k }").isEmpty())
    }

    @Test
    fun `negative control - a cast receiver with a string index signature`() {
        val r = rows(
            """
            declare const w: unknown
            const z = (w as { [key: string]: number }).zz
            """,
        )
        assert(r.isEmpty())
    }

    @Test
    fun `negative control - an element access through a cast emits no TS2339`() {
        // tsgo 7.0.2 reports TS7053 at `(w as B)` here, never TS2339; this checker's
        // element-access TS2339 for an IDENTIFIER receiver (`u["a"]`) is a pre-existing
        // ours-only row, and the cast arm must not extend it.
        val d = diagnose(prelude + "declare const w: unknown\nconst z = (w as B)[\"a\"]\nexport {}")
        assert(d.none { it.code == 2339 })
    }

    // ---- (c) the object-literal member drill

    @Test
    fun `an object-literal member holding a union of objects drills to the key`() {
        val r = rows(
            """
            declare const u: U
            const o: { p: number } = { p: u }
            """,
        )
        assert(r == listOf(
            "5,28: TS2322 Type 'U' is not assignable to type 'number'. | Type 'A' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `an object-literal member holding an object drills to the key with no chain`() {
        val r = rows(
            """
            declare const u: A
            const o: { p: number } = { p: u }
            """,
        )
        assert(r == listOf("5,28: TS2322 Type 'A' is not assignable to type 'number'."))
    }

    @Test
    fun `an object-literal member holding a mixed union names the failing constituent`() {
        val r = rows(
            """
            declare const u: number | A
            const o: { p: number } = { p: u }
            """,
        )
        assert(r == listOf(
            "5,28: TS2322 Type 'number | A' is not assignable to type 'number'. | Type 'A' is not assignable to type 'number'.",
        ))
    }

    @Test
    fun `an object-literal member holding an array drills to its own key`() {
        val r = rows(
            """
            declare const u: A[]
            const o: { p: number, q: string } = { p: u, q: "s" }
            """,
        )
        assert(r == listOf("5,39: TS2322 Type 'A[]' is not assignable to type 'number'."))
    }

    @Test
    fun `negative control - an object-literal member whose union value relates`() {
        val r = rows(
            """
            declare const u: U
            const o: { p: U } = { p: u }
            """,
        )
        assert(r.isEmpty())
    }
}
