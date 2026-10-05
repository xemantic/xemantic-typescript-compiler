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
 * (P18.299) Call arguments the argument walker never related, found reducing type-fest's
 * `exact.ts` (FBOUND) and the `Require*` family (REQONE). Every expected row is tsgo 7.0.2's
 * (cells `build/bench/p18299-agent/pins/`, 1-based columns):
 *  - a FILE-LEVEL statement block was not a scope to `LocalShadowGuard`, so its locals read `any`;
 *  - an un-annotated body-local `const f = (a: A) => …` callee, and a local object / array /
 *    `as` initializer, read `any` (B83.5) — now typed at the declaration's leave;
 *  - an inference that fails its type parameter's constraint is replaced by the (instantiated)
 *    constraint, as tsgo's `getInferredType` does, and an OBJECT argument against a primitive
 *    constraint is decidable;
 *  - a plain object literal against a union / intersection of concrete object types, with
 *    tsgo's excess-property row first;
 *  - two relation defects those exposed on real code: an enum's own type against a literal
 *    union (hono `jws.ts`), and a rest-only source against a rest-carrying target
 *    (`String.replace`'s replacer, tsc `editorServices.ts`).
 *
 * Residues, not asserted: a block-local `const s = 1` SHADOWING a file-level `s` still reads
 * `any` in the argument walker (tsgo reports TS2345 at the inner call); a genuinely F-bounded
 * conditional constraint (`T extends ([T] extends [P] ? T : P)`) is TS2313 "circular
 * constraint" in tsgo and an ours-only TS2345 here (pre-existing).
 */
class ArgumentReachP18299Test {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    private val numToStr = "TS2345 Argument of type 'number' is not assignable to parameter of type 'string'."

    @Test
    fun `a file-level block's const is typed for the argument walker`() {
        val r = rows(
            """
            declare function g2(a: string): void;
            { const i3 = 1; g2(i3); }
            """,
        )
        assert(r == listOf("2:20 $numToStr"))
    }

    @Test
    fun `a file-level block local does not leak past the block`() {
        val r = rows(
            """
            declare function g2(a: string): void;
            const s = "x";
            { const s = 1; g2(s); }
            g2(s);
            """,
        )
        assert(r.none { it.startsWith("4:") })
    }

    @Test
    fun `a call to a body-local arrow const is related`() {
        assert(rows("function w() { const fn = (a: string) => a; fn(1); }") == listOf("1:48 $numToStr"))
    }

    @Test
    fun `a call to a block-local arrow const is related`() {
        assert(rows("{ const fn = (a: string) => a; fn(1); }") == listOf("1:35 $numToStr"))
    }

    @Test
    fun `negative control - a valid call to a body-local arrow const is silent`() {
        assert(rows("function w() { const fn = (a: string) => a; fn(\"x\"); }").isEmpty())
    }

    @Test
    fun `an inference failing its constraint is replaced by the constraint`() {
        val r = rows(
            """
            declare function g<T extends string>(a: T): T;
            const o = {};
            g(o);
            declare const u: unknown;
            g(u);
            const input = 1;
            g(input);
            g("a");
            """,
        )
        assert(
            r == listOf(
                "3:3 TS2345 Argument of type '{}' is not assignable to parameter of type 'string'.",
                "5:3 TS2345 Argument of type 'unknown' is not assignable to parameter of type 'string'.",
                "7:3 $numToStr",
            ),
        )
    }

    @Test
    fun `a constraint alias mentioning its own type parameter that evaluates without it is checked`() {
        val r = rows(
            """
            type Ex<P, I> = P extends string ? P : P;
            declare function f<T extends Ex<string, T>>(a: T): T;
            const x = 1;
            f(x);
            f("a");
            """,
        )
        assert(r == listOf("4:3 $numToStr"))
    }

    @Test
    fun `an object literal against an intersection of object types is related`() {
        val r = rows(
            """
            declare function h(_: { a: string } & { d: string }): void;
            h({});
            h({ a: "", d: "" });
            """,
        )
        assert(r == listOf("2:3 TS2345 Argument of type '{}' is not assignable to parameter of type '{ a: string; } & { d: string; }'."))
    }

    @Test
    fun `an object literal member no union constituent declares is TS2353 at the member`() {
        val r = rows(
            """
            declare function k(_: { a: string } | { b: string }): void;
            k({ d: "" });
            k({ a: "" });
            """,
        )
        assert(r == listOf("2:5 TS2353 Object literal may only specify known properties, and 'd' does not exist in type '{ a: string; } | { b: string; }'."))
    }

    @Test
    fun `the RequireAtLeastOne shape rejects a literal missing every required key`() {
        val r = rows(
            """
            type U1 = ({ a: string } & { b?: string }) | ({ b: string } & { a?: string });
            type R = U1 & { d: string };
            declare function f(_: R): void;
            f({});
            f({ d: "" });
            f({ a: "x", d: "" });
            """,
        )
        assert(
            r == listOf(
                "4:3 TS2345 Argument of type '{}' is not assignable to parameter of type 'R'.",
                "5:3 TS2345 Argument of type '{ d: string; }' is not assignable to parameter of type 'R'.",
            ),
        )
    }

    @Test
    fun `negative control - a literal-typed member target is not related by the object literal gate`() {
        assert(rows("declare function m(_: { u: \"x\" | \"y\" } & { d: string }): void;\nm({ u: \"x\", d: \"\" });").isEmpty())
    }

    @Test
    fun `a string enum relates to a literal union holding every member value`() {
        val r = rows(
            """
            enum U { A = "a", B = "b" }
            type L = "a" | "b" | "c";
            declare const u: U;
            const x: L = u;
            enum V { A = "a", D = "d" }
            declare const v: V;
            const y: L = v;
            """,
        )
        assert(r == listOf("7:7 TS2322 Type 'V' is not assignable to type 'L'."))
    }

    @Test
    fun `a rest-only source relates position by position to a rest-carrying target`() {
        val r = rows(
            """
            const f1: (s: string, ...args: any[]) => string = (...g: string[]) => g[0];
            const f2: (s: string, ...args: any[]) => string = (...g: number[]) => "";
            const r = "".replace(/x/, (...groups: string[]) => groups[0]);
            """,
        )
        assert(r == listOf("2:7 TS2322 Type '(...g: number[]) => string' is not assignable to type '(s: string, ...args: any[]) => string'."))
    }
}
