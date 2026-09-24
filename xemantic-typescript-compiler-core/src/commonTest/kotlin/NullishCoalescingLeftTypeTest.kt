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
 * (CHK.170), round P18.199 — `a ?? b` (and `a ??= b`) is typed as the LEFT type, unchanged, when
 * the left cannot be nullish; only a left that CAN be `null`/`undefined` unions in the right side.
 *
 * tsgo's `??` arm of `checkBinaryLikeExpressionWorker` (checker.go ~12480): `resultType := leftType`,
 * replaced by `getNonNullableType(left) | right` only when `hasTypeFacts(leftType,
 * TypeFactsEQUndefinedOrNull)`. Before this, `a.f ?? a.p` with `f: string, p?: string` read
 * `string | undefined` and reported on legal code (census cell x05; the `session.ts:2158` row that
 * blocked (CHK.169)). `Checker.mayBeNullishByTypeFacts` is that fact under `strictNullChecks`:
 * `any`/`unknown`/`null`/`undefined`/`void` carry it, a union when any member does, an
 * intersection when every non-brand member does, a type parameter through its constraint (an
 * unconstrained one is `unknown`). Outside `strictNullChecks` every type carries it, so the old
 * union stands there.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW over the identical text (`build/scratch-p18199/pins`,
 * `strict`, `target: es2020`). A row is `line:column code message` with its chain appended as
 * ` / <line>`. Residues, asserted head-only or by code below, none of them in the rule this round
 * changes: a union argument's chain names the LAST failing constituent where tsgo names the first,
 * and a type-parameter argument carries no `Type '<constraint>' …` chain line (both pre-existing,
 * identical for a plain union / type-parameter argument); tsgo names an alias for a branded
 * intersection (`Brand`) where we spell the intersection out, and tsgo types `unknown ?? s` as
 * `{}` (a `NonNullable<unknown>` joined by subtype reduction) where we print `unknown | string`.
 */
class NullishCoalescingLeftTypeTest {

    private val prelude = """
        export {};
        declare function pn(n: number): void;
        declare function ps(s: string): void;
    """.trimIndent()

    private fun diagnostics(body: String, strict: Boolean = true) =
        diagnose(prelude + "\n" + body.trimIndent(), directives = "// @strict: $strict\n// @target: es2020")

    private fun rows(body: String, strict: Boolean = true): List<String> =
        diagnostics(body, strict).map { d ->
            "${d.line}:${d.character} ${d.code} ${d.message}" +
                d.messageChain.joinToString("") { " / " + it.trim() }
        }.sorted()

    private fun heads(body: String, strict: Boolean = true): List<String> =
        diagnostics(body, strict).map { d -> "${d.line}:${d.character} ${d.code} ${d.message}" }.sorted()

    @Test
    fun `a non-nullable member left of an optional one is legal - the census x05 shape`() {
        val x05 = rows("""
            interface A { f: string; p?: string }
            function g(a: A) { ps(a.f ?? a.p); const q: string = a.f ?? a.p; if (!a.p) ps(a.f ?? a.p); }
        """)
        assert(x05.isEmpty())
    }

    @Test
    fun `a left that cannot be nullish is the whole answer`() {
        val body = """
            interface O { a: number }
            type Brand = string & { __b: 1 };
            function f(s: string, u: string | undefined, o: O, p: O | undefined, su: string | boolean, bu: bigint | undefined, b: Brand) {
              pn(s ?? u);
              pn(o ?? p);
              pn(su ?? bu);
              const y: string | boolean = su ?? bu;
              pn(b ?? 1);
            }
        """
        val all = heads(body)
        assert(all.filter { !it.startsWith("11:") } == listOf(
            "7:6 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
            "8:6 2345 Argument of type 'O' is not assignable to parameter of type 'number'.",
            "9:6 2345 Argument of type 'string | boolean' is not assignable to parameter of type 'number'.",
        ))
        // tsgo: `Argument of type 'Brand' …` — the alias display is the residue, the row is not.
        val brand = heads(body).filter { it.startsWith("11:") }.map { it.substringBefore(" Argument") }
        assert(brand == listOf("11:6 2345"))
    }

    @Test
    fun `a left that can be nullish still unions in the right side`() {
        val all = heads("""
            interface A { f: string; p?: string; q?: boolean }
            function g(a: A, n: string | undefined, b: boolean, v: void) {
              pn(a.p ?? a.f);
              pn(a.q ?? a.f);
              pn(n ?? b);
              pn(v ?? "s");
            }
        """)
        assert(all == listOf(
            "6:6 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
            "7:6 2345 Argument of type 'string | boolean' is not assignable to parameter of type 'number'.",
            "8:6 2345 Argument of type 'string | boolean' is not assignable to parameter of type 'number'.",
            "9:6 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
        ))
    }

    @Test
    fun `a type parameter answers for its constraint`() {
        val all = heads("""
            function f<U extends string, W extends { a: 1 }>(u: U, w: W, s: string | undefined) {
              pn(u ?? s);
              pn(w ?? s);
            }
        """)
        assert(all == listOf(
            "5:6 2345 Argument of type 'U' is not assignable to parameter of type 'number'.",
            "6:6 2345 Argument of type 'W' is not assignable to parameter of type 'number'.",
        ))
    }

    @Test
    fun `a nullish-coalescing assignment to a non-nullable left is the left`() {
        val all = rows("""
            function f(s: string, t: "a", o: { s: string }) {
              pn(s ??= t);
              pn(o.s ??= t);
            }
        """)
        assert(all == listOf(
            "5:6 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
            "6:6 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
        ))
    }

    @Test
    fun `negative control - without strictNullChecks every left unions in the right side`() {
        val all = heads("""
            function f(s: string, u: boolean, o: { a: number }) {
              pn(s ?? u);
              pn(o ?? s);
            }
        """, strict = false)
        assert(all == listOf(
            "5:6 2345 Argument of type 'string | boolean' is not assignable to parameter of type 'number'.",
            "6:6 2345 Argument of type 'string | { a: number; }' is not assignable to parameter of type 'number'.",
        ))
    }

    @Test
    fun `negative control - any is absorbed and unknown still unions`() {
        // tsgo: `6:6 2345 Argument of type '{}' …` — the `unknown ?? s` display is the residue.
        val all = heads("""
            function f(x: any, y: unknown, s: string) {
              pn(x ?? s);
              pn(y ?? s);
            }
        """).map { it.substringBefore(" Argument") }
        assert(all == listOf("6:6 2345"))
    }

    @Test
    fun `the harness session shape is legal and its optional twin reports`() {
        val all = rows("""
            interface FileRequestArgs { file: string; projectFileName?: string }
            declare function logf(fileName: string): void;
            function m(args: FileRequestArgs, other: { file?: string; projectFileName?: string }) {
              logf(args.file ?? args.projectFileName);
              logf(other.file ?? other.projectFileName);
            }
        """)
        assert(all == listOf(
            "8:8 2345 Argument of type 'string | undefined' is not assignable to parameter of type 'string'." +
                " / Type 'undefined' is not assignable to type 'string'.",
        ))
    }
}
