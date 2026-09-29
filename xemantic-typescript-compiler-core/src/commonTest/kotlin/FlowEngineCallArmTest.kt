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
 * (CHK.173) B5a (P18.224) — the ENGINE half of the flow walk's CALL arm. An assignment
 * `x = call(…)` resets the walked reference to the call's type; the arm used to read only
 * the resolved callee's return ANNOTATION, so it gave up on every interface / lib METHOD
 * (`s = p.substring(1)`, `m = re.exec(x)`) and on every GENERIC callee, explicit type
 * arguments included (`if (!s) s = first(a)`, `r = tok<"a" | "b">()`), and the declared
 * nullish type survived the overwrite. It now falls back to the call's resolved return
 * type, refusing a `?.` on the call or its callee chain, an `any` / `error` / `unknown`
 * result, and a result still carrying an unresolved type parameter. A CONDITIONAL
 * right-hand side with a call branch is typed branch by branch through the same arms and
 * reduces the declared union as tsgo's `getAssignmentReducedType` does.
 *
 * Every fixture is a cell of `build/bench/p18224-agent/m1` and every expectation is tsgo
 * 7.0.2's WHOLE row list for it, `line:col` as tsgo prints. Residues NOT pinned (tsgo in
 * brackets): c18 `s = first(a)` with `a: T[]` prints `T | undefined` [`T`] — the
 * type-parameter refusal cannot tell an in-scope `T` from a failed inference; c20 an
 * ANNOTATED declaration `let s: string | undefined = first(a)` prints the annotation
 * [`string`] — the declaration arm answers the annotation before any call arm; c22 a
 * conditional with a LITERAL branch `c ? first(a) : 1` is refused [`string | number`].
 */
class FlowEngineCallArmTest {

    private val STRICT = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private val PRELUDE =
        "declare function g(): string | null; declare function first<T>(a: T[]): T; " +
            "declare function mk<T>(): T; declare function anyf(): any; declare const re: RegExp; " +
            "declare function tok<T extends string>(): T; declare const c: boolean; " +
            "declare const o: { m(): string } | undefined; declare function id<T>(x: T): T; " +
            "interface A { x: number } interface B { x: string } declare function mkA(): A; " +
            "declare const ob: { b(): B } | undefined;\n"

    private fun rowsOf(body: String): List<String> =
        diagnose(PRELUDE + body + "\nexport {}\n", STRICT)
            .map { "${it.line}:${it.character} TS${it.code}: ${it.message}" }.sorted()

    // GENERIC callee — the two shipped false positives (census nar n8c / n8p).

    @Test
    fun `a body local defaulted from a generic call is not possibly null`() {
        val rows = rowsOf("function f(a: string[]) { let s = g(); if (!s) { s = first(a) } return s.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a parameter defaulted from a generic call is not possibly undefined`() {
        val rows = rowsOf("function f(a: string[], s: string | undefined) { if (!s) { s = first(a) } return s.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `explicit type arguments on a generic callee`() {
        val rows = rowsOf("function f(r: \"a\" | \"b\" | undefined) { r = tok<\"a\" | \"b\">(); r.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a generic call nested in a generic call`() {
        val rows = rowsOf("function f(a: string[], s: string | undefined) { s = id(first(a)); s.length }")
        assert(rows.isEmpty())
    }

    // lib METHOD callee (census N14).

    @Test
    fun `a parameter assigned from a lib string method`() {
        val rows = rowsOf("function f(p: string, s: string | undefined) { s = p.substring(1); s.slice(1) }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a parameter assigned from trim`() {
        val rows = rowsOf("function f(p: string, s: string | undefined) { s = p.trim(); s.length }")
        assert(rows.isEmpty())
    }

    @Test
    fun `the declaration reader prints the assigned method type`() {
        val rows = rowsOf("function f(p: string) { let s: string | undefined; s = p.substring(1); const n: number = s }")
        assert(rows == listOf("2:78 TS2322: Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `a nullable method result keeps its null`() {
        val rows = rowsOf("function f(x: string, m: RegExpExecArray | null | undefined) { m = re.exec(x); m.index }")
        assert(rows == listOf("2:80 TS18047: 'm' is possibly 'null'."))
    }

    @Test
    fun `a nullable method result keeps its null after a guard`() {
        val rows = rowsOf("function f(x: string, m: RegExpExecArray | undefined | null) { if (!m) { m = re.exec(x) } m.index }")
        assert(rows == listOf("2:91 TS18047: 'm' is possibly 'null'."))
    }

    @Test
    fun `a nullable method result prints its declared type`() {
        val rows = rowsOf("function f(x: string, m: RegExpExecArray | null) { m = re.exec(x); const n: number = m }")
        assert(rows == listOf("2:74 TS2322: Type 'RegExpExecArray | null' is not assignable to type 'number'."))
    }

    @Test
    fun `a generic call over nullable elements keeps its undefined`() {
        val rows = rowsOf("function f(a: (string | undefined)[], s: string | undefined) { s = first(a); s.length }")
        assert(rows == listOf("2:78 TS18048: 's' is possibly 'undefined'."))
    }

    @Test
    fun `a lib method that can answer undefined keeps it`() {
        val rows = rowsOf("function f(p: string, s: string | undefined) { s = p.at(0); s.length }")
        assert(rows == listOf("2:61 TS18048: 's' is possibly 'undefined'."))
    }

    // REFUSALS - the reference keeps its declared nullish type, as tsgo's does.

    @Test
    fun `refusal - an optional-chained callee`() {
        val rows = rowsOf("function f(s: string | undefined) { s = o?.m(); s.length }")
        assert(rows == listOf("2:49 TS18048: 's' is possibly 'undefined'."))
    }

    @Test
    fun `refusal - an optional link deeper in the callee chain`() {
        val rows = rowsOf("function f(p: { q?: { m(): string } }, s: string | undefined) { s = p.q?.m(); s.length }")
        assert(rows == listOf("2:79 TS18048: 's' is possibly 'undefined'."))
    }

    @Test
    fun `refusal - an any result`() {
        val rows = rowsOf("function f(s: string | undefined) { s = anyf(); s.length }")
        assert(rows == listOf("2:49 TS18048: 's' is possibly 'undefined'."))
    }

    @Test
    fun `refusal - a type parameter no argument infers`() {
        val rows = rowsOf("function f(s: string | undefined) { s = mk(); s.length }")
        assert(rows == listOf("2:47 TS18048: 's' is possibly 'undefined'."))
    }

    /**
     * `s = pick(a, x => x)` with `a: T[]` resolves to the enclosing function's own `T`.
     * Accepting it would make the declaration reader SILENT — this checker does not report
     * a bare type parameter against `number` there — where tsgo reports TS2322 (`Type 'T'
     * …`); the refusal keeps the row, with the message still naming `T | undefined` (the
     * c18 residue in the class KDoc). Pinned by code and position only.
     */
    @Test
    fun `refusal - a type parameter result keeps the declaration row`() {
        val rows = diagnose(
            "declare function pick<T, U>(a: T[], f: (t: T) => U): U;\n" +
                "function f<T>(a: T[], s: T | undefined) { s = pick(a, x => x); const n: number = s }\nexport {}\n",
            STRICT,
        ).map { "${it.line}:${it.character} TS${it.code}" }
        assert(rows == listOf("2:70 TS2322"))
    }

    @Test
    fun `a generic call inferred through a callback`() {
        val rows = diagnose(
            "declare function pick<T, U>(a: T[], f: (t: T) => U): U; interface N { name: string }\n" +
                "function f(xs: N[], s: string | undefined) { if (!s) { s = pick(xs, x => x.name) } s.length }\nexport {}\n",
            STRICT,
        )
        assert(rows.isEmpty())
    }

    // CONDITIONAL right-hand side with a call branch.

    @Test
    fun `a conditional of two calls reduces the declared union`() {
        val rows = rowsOf("function f(bs: B[], s: A | B | undefined) { s = c ? mkA() : first(bs); s.x }")
        assert(rows.isEmpty())
    }

    @Test
    fun `a conditional of two calls prints the reduced union`() {
        val rows = rowsOf("function f(bs: B[], s: A | B | undefined) { s = c ? mkA() : first(bs); const n: number = s }")
        assert(rows == listOf("2:78 TS2322: Type 'A | B' is not assignable to type 'number'."))
    }

    @Test
    fun `a conditional of two calls after a guard`() {
        val rows = rowsOf("function f(bs: B[], s: A | B | null | undefined) { if (!s) { s = c ? first(bs) : mkA() } s.x }")
        assert(rows.isEmpty())
    }

    @Test
    fun `refusal - a conditional with an undefined branch`() {
        val rows = rowsOf("function f(s: A | B | undefined) { s = c ? mkA() : undefined; s.x }")
        assert(rows == listOf("2:63 TS18048: 's' is possibly 'undefined'."))
    }

    @Test
    fun `refusal - a conditional with an optional-chained call branch`() {
        val rows = rowsOf("function f(s: A | B | undefined) { s = c ? mkA() : ob?.b(); s.x }")
        assert(rows == listOf("2:61 TS18048: 's' is possibly 'undefined'."))
    }

    @Test
    fun `refusal - a conditional with an any call branch`() {
        val rows = rowsOf("function f(s: A | B | undefined) { s = c ? mkA() : anyf(); s.x }")
        assert(rows == listOf("2:60 TS18048: 's' is possibly 'undefined'."))
    }
}
