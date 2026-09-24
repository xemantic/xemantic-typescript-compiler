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
 * (CHK.159) step 2, round P18.193 — the call's CONTEXTUAL type, inferred into its return type, fills the
 * type parameters the ARGUMENTS left open, for the call's RESULT type (`Checker.argInferContextFallback`).
 * tsgo's `inferTypeArguments` infers from the contextual type at `InferencePriorityReturnType` first and
 * an argument candidate at priority 0 wipes it per type parameter (`inference.go` ~189); step 1
 * (`ArgumentResultInferenceTest`) supplied the argument half and discarded its partial map, so a call
 * with no arguments, or whose type parameter occurs only in the return and a callback parameter, returned
 * the RAW callee return — silent where tsgo reports (`mk()` at `{ a: string; b: string }`), and a false
 * positive where the raw callee `T`/`R` collided by NAME with the caller's (rxjs `groupBy.ts:147`).
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18192-census/cells` (the
 * census's 44-cell matrix) and `build/scratch-p18193/pins` on the identical text less the `// @`
 * directive lines the harness strips. A row is `line:column code message` with its chain appended as
 * ` / <line>`.
 *
 * SAFEGUARDS, each pinned: the result is ALL-OR-NOTHING over the type parameters the return mentions;
 * the combined map is constraint-checked; nothing fires where step 1 refused a leak; and a type parameter
 * an ANNOTATED parameter of a context-sensitive callback mentions counts as argument-bound (tsgo's second
 * pass binds it from the annotation; this leg does not model that pass).
 *
 * RESIDUES (pinned as `residue - …` with tsgo's row in a comment): tsgo's second pass over a
 * context-sensitive callback (an annotated parameter, a callback's return), `unknown` for a nested call's
 * un-inferred type parameter, the constraint substituted for a no-candidate or failing type parameter,
 * and a literal argument kept where the context is literal-ish. DISPLAY residues, asserted by head line
 * only: an optional or union annotation's elaboration chain, and an array of a generic function type.
 */
class ContextualResultInferenceTest {

    private val directives = """
        // @useRealLibs: true
        // @strict: true
        // @target: es2020
    """.trimIndent()

    private val abPrelude = """
        interface AB<T> { a: T; b: number }
        declare function mk<T>(): AB<T>;
        declare function id<U>(x: U): U;
        declare function pn(x: number): void;
        declare const cond: boolean;

    """.trimIndent()

    private val obsPrelude = """
        interface Obs<T> { v: T; subscribe(s: Partial<Obr<T>>): void }
        interface Obr<T> { next(v: T): void; error(e: any): void }
        interface Sub<T> extends Obr<T> { closed: boolean }
        interface UF<T, R> { (s: T): R }
        interface OpFn<T, R> extends UF<Obs<T>, Obs<R>> {}
        interface G<K, T> extends Obs<T> { key: K }
        declare function operate<T, R>(init: (src: Obs<T>, sub: Sub<R>) => (() => void) | void): OpFn<T, R>;
        declare function cos<T>(dest: Sub<any>, onNext?: (value: T) => void): Sub<T>;
        declare function map<T, R>(p: (v: T, i: number) => R): OpFn<T, R>;
        declare function pipe<A, B, C>(a: OpFn<A, B>, b: OpFn<B, C>): OpFn<A, C>;
        declare function pn(x: number): void;
        declare const cond: boolean;

    """.trimIndent()

    private fun compile(source: String): List<Diagnostic> =
        diagnose(directives + "\n" + source, directives = "")

    private fun rowsOf(ds: List<Diagnostic>): List<String> =
        ds.map { d ->
            "${d.line}:${d.character} ${d.code} ${d.message}" +
                d.messageChain.joinToString("") { " / " + it.trim() }
        }.sorted()

    private fun headsOf(ds: List<Diagnostic>): List<String> =
        ds.map { "${it.line}:${it.character} ${it.code} ${it.message}" }.sorted()

    private fun abRows(source: String) = rowsOf(compile(abPrelude + "\n" + source.trimIndent()))
    private fun abHeads(source: String) = headsOf(compile(abPrelude + "\n" + source.trimIndent()))
    private fun obsRows(source: String) = rowsOf(compile(obsPrelude + "\n" + source.trimIndent()))
    private fun obsHeads(source: String) = headsOf(compile(obsPrelude + "\n" + source.trimIndent()))
    private fun plainRows(source: String) = rowsOf(compile(source.trimIndent()))

    @Test
    fun `a no-argument call's result binds its type parameter from the declaration's annotation`() {
        val actual = abRows(
            """
            const x: { a: string; b: string } = mk();
            """
        )
        val expected = listOf(
            "7:7 2322 Type 'AB<string>' is not assignable to type '{ a: string; b: string; }'. / Types of property 'b' are incompatible. / Type 'number' is not assignable to type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a no-argument call's result binds from a return position's annotation`() {
        val actual = abRows(
            """
            function f1(): { a: string; b: string } { return mk(); }
            """
        )
        val expected = listOf(
            "7:43 2322 Type 'AB<string>' is not assignable to type '{ a: string; b: string; }'. / Types of property 'b' are incompatible. / Type 'number' is not assignable to type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a no-argument call's result binds from an object-literal property's contextual type`() {
        val actual = abRows(
            """
            const o: { p: { a: string; b: string } } = { p: mk() };
            """
        )
        val expected = listOf(
            "7:46 2322 Type 'AB<string>' is not assignable to type '{ a: string; b: string; }'. / Types of property 'b' are incompatible. / Type 'number' is not assignable to type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a no-argument call's result binds through an optional annotation`() {
        // display residue: we print a repeated union-member line tsgo folds away
        // tsgo 7.0.2, head line only
        val actual = abHeads(
            """
            const x: { a: string; b: string } | undefined = mk();
            """
        )
        val expected = listOf(
            "7:7 2322 Type 'AB<string>' is not assignable to type '{ a: string; b: string; }'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a no-argument call's result binds a union from a union annotation`() {
        // display residue: tsgo's chain ends with the failing constituent `Type 'string' is not assignable to type 'number'.`, ours stops one line earlier
        // tsgo 7.0.2, head line only
        val actual = abHeads(
            """
            const x: { a: string; b: string } | { a: number; b: string } = mk();
            """
        )
        val expected = listOf(
            "7:7 2322 Type 'AB<string | number>' is not assignable to type '{ a: string; b: string; } | { a: number; b: string; }'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an overloaded no-argument call's result binds from the annotation`() {
        val actual = abRows(
            """
            declare function ov<T>(): AB<T>;
            declare function ov<T>(x: number): T[];
            const x: { a: string; b: string } = ov();
            """
        )
        val expected = listOf(
            "9:7 2322 Type 'AB<string>' is not assignable to type '{ a: string; b: string; }'. / Types of property 'b' are incompatible. / Type 'number' is not assignable to type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a literal-typed annotation binds a literal type argument`() {
        val actual = abRows(
            """
            const x: { a: "lit"; b: string } = mk();
            """
        )
        val expected = listOf(
            "7:7 2322 Type 'AB<\"lit\">' is not assignable to type '{ a: \"lit\"; b: string; }'. / Types of property 'b' are incompatible. / Type 'number' is not assignable to type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an assignment's target binds a no-argument call's result`() {
        val actual = abRows(
            """
            let v: { a: string; b: string } = { a: "", b: "" };
            v = mk();
            """
        )
        val expected = listOf(
            "8:1 2322 Type 'AB<string>' is not assignable to type '{ a: string; b: string; }'. / Types of property 'b' are incompatible. / Type 'number' is not assignable to type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a class property's annotation binds a no-argument call's result`() {
        val actual = abRows(
            """
            class K { p: { a: string; b: string } = mk(); }
            """
        )
        val expected = listOf(
            "7:11 2322 Type 'AB<string>' is not assignable to type '{ a: string; b: string; }'. / Types of property 'b' are incompatible. / Type 'number' is not assignable to type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `the arguments bind one type parameter and the context the other`() {
        val actual = abRows(
            """
            declare function fu<T, U>(x: T): { a: T; u: U };
            const y: { a: string; u: string } = fu(1);
            """
        )
        val expected = listOf(
            "8:7 2322 Type '{ a: number; u: string; }' is not assignable to type '{ a: string; u: string; }'. / Types of property 'a' are incompatible. / Type 'number' is not assignable to type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `an argument candidate beats the context and a context-sensitive callback is skipped`() {
        val actual = abRows(
            """
            declare function fc<T, U>(x: T, cb: (t: T) => void): { a: T; u: U };
            const y: { a: string; u: string } = fc(1, t => {});
            """
        )
        val expected = listOf(
            "8:7 2322 Type '{ a: number; u: string; }' is not assignable to type '{ a: string; u: string; }'. / Types of property 'a' are incompatible. / Type 'number' is not assignable to type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `rxjs groupBy - operate's result binds both type parameters from the return context`() {
        val actual = plainRows(
            """
            interface Obs<T> { v: T }
            interface Sub<T> { next(v: T): void }
            interface UF<T, R> { (s: T): R }
            interface OpFn<T, R> extends UF<Obs<T>, Obs<R>> {}
            interface G<K, T> extends Obs<T> { key: K }
            declare function operate<T, R>(init: (src: Obs<T>, sub: Sub<R>) => void): OpFn<T, R>;
            export function groupBy<T, K, R>(k: (v: T) => K): OpFn<T, G<K, R>> {
              return operate((source, subscriber) => {});
            }
            export function g2<T, K, R>(k: (v: T) => K): OpFn<T, G<K, R>> {
              return operate<T, G<K, R>>((source, subscriber) => {});
            }
            export function g3<A, K, B>(k: (v: A) => K): OpFn<A, G<K, B>> {
              return operate((source, subscriber) => {});
            }
            declare function ps(x: string): void;
            ps(operate((source: Obs<number>, subscriber: Sub<string>) => {}));
            """
        )
        val expected = listOf(
            "17:4 2345 Argument of type 'OpFn<number, string>' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `rxjs groupBy reduced - the raw callee type parameters no longer collide by name`() {
        val actual = obsRows(
            """
            export function groupBy<T, K, R>(k: (v: T) => K): OpFn<T, G<K, R>> {
              return operate((source, subscriber) => {});
            }
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `a callback's contextual subscriber binds the result from the declaration`() {
        val actual = obsRows(
            """
            export function h<T>(s: Sub<T>) {
              const x: Sub<string> = cos(s, (v) => {});
            }
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `control - step 1 alone binds from the argument`() {
        val actual = abRows(
            """
            declare function f1<T>(x: T): AB<T>;
            const y: { a: string; b: number } = f1(1);
            """
        )
        val expected = listOf(
            "8:7 2322 Type 'AB<number>' is not assignable to type '{ a: string; b: number; }'. / Types of property 'a' are incompatible. / Type 'number' is not assignable to type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `control - step 1 alone binds through a pipe of maps`() {
        // display residue, pre-existing: tsgo's chain continues `Types of property 'v' are incompatible. / Type 'string' is not assignable to type 'boolean'.`, ours stops at `Type 'Obs<string>' is not assignable to type 'Obs<boolean>'.`
        // tsgo 7.0.2, head line only
        val actual = obsHeads(
            """
            const p: OpFn<number, boolean> = pipe(map((v: number) => v), map((v: number) => String(v)));
            """
        )
        val expected = listOf(
            "14:7 2322 Type 'OpFn<number, string>' is not assignable to type 'OpFn<number, boolean>'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `control - a combined candidate that fails its constraint keeps the raw return`() {
        val actual = abRows(
            """
            declare function cf<T extends string, U>(x: T): { a: T; u: U };
            const y: { a: string; u: number } = cf(1);
            """
        )
        val expected = listOf(
            "8:40 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `control - a generic function argument binds and the context fills the rest`() {
        // display residue, pre-existing and unrelated to inference: tsgo parenthesizes an array of a generic function type, `(<V>(v: V) => V)[]`, ours prints `<V>(v: V) => V[]` (the same with a declared type and no call)
        val actual = abHeads(
            """
            declare function wr<T, U>(x: T): { a: T; b: T[]; u: U };
            const y: { a: string; b: number[]; u: number } = wr(<V>(v: V) => v);
            """
        )
        val expected = listOf(
            "8:7 2322 Type '{ a: <V>(v: V) => V; b: <V>(v: V) => V[]; u: number; }' is not assignable to type '{ a: string; b: number[]; u: number; }'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `residue - an annotated parameter of a context-sensitive callback keeps the result raw`() {
        // tsgo 7.0.2 reports:
        //   15:3 2322 Type 'OpFn<string, B>' is not assignable to type 'OpFn<A, B>'.
        //     Types of parameters 's' and 's' are incompatible.
        //     Type 'Obs<A>' is not assignable to type 'Obs<string>'.
        //     Types of property 'v' are incompatible.
        //     Type 'A' is not assignable to type 'string'.
        val actual = obsRows(
            """
            export function g4<A, B>(): OpFn<A, B> {
              return operate((source: Obs<string>, sub) => {});
            }
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `residue - an annotated callback parameter's type parameter is not taken from the context`() {
        // tsgo 7.0.2 reports:
        //   8:7 2322 Type '{ a: string; b: string[]; u: number; }' is not assignable to type '{ a: number; b: string[]; u: number; }'.
        //     Types of property 'a' are incompatible.
        //     Type 'string' is not assignable to type 'number'.
        val actual = abRows(
            """
            declare function op3<T, U>(cb: (x: T, y: number) => void): { a: T; b: T[]; u: U };
            const z: { a: number; b: string[]; u: number } = op3((x: string, y) => {});
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `residue - a nested call's un-inferred type parameter refuses the whole inference`() {
        // tsgo 7.0.2 reports:
        //   9:7 2322 Type '{ a: unknown[]; b: unknown[][]; u: number; }' is not assignable to type '{ a: string; b: number[]; u: number; }'.
        //     Types of property 'a' are incompatible.
        //     Type 'unknown[]' is not assignable to type 'string'.
        val actual = abRows(
            """
            declare function mk2<V>(): V[];
            declare function wr<T, U>(x: T): { a: T; b: T[]; u: U };
            const y: { a: string; b: number[]; u: number } = wr(mk2());
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `residue - a type parameter only a callback's return binds keeps the result raw`() {
        // tsgo 7.0.2 reports:
        //   8:5 2322 Type '(x: string) => number' is not assignable to type '(x: string) => string'.
        //     Type 'number' is not assignable to type 'string'.
        val actual = abRows(
            """
            declare function wrap<T, U>(cb: (x: T) => U): (x: T) => U;
            let f: (x: string) => string = wrap(s => s.length);
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `residue - a raw type parameter left by a partial inference is not substituted where it collides with the caller's`() {
        // tsgo 7.0.2 reports:
        //   8:42 2322 Type '{ a: string; u: number; }' is not assignable to type '{ a: string; u: U[]; }'.
        //     Types of property 'u' are incompatible.
        //     Type 'number' is not assignable to type 'U[]'.
        val actual = abRows(
            """
            declare function fu2<T, U>(cb: (x: T) => U): { a: T; u: U };
            function g<U>(): { a: string; u: U[] } { return fu2(s => s.length); }
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `residue - a no-argument call with a constrained type parameter is not bound to its constraint`() {
        // tsgo 7.0.2 reports:
        //   8:7 2322 Type 'AB<number>' is not assignable to type '{ a: string; b: string; }'.
        //     Types of property 'a' are incompatible.
        //     Type 'number' is not assignable to type 'string'.
        val actual = abRows(
            """
            declare function mkc<T extends number>(): AB<T>;
            const x: { a: string; b: string } = mkc();
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `residue - a single-signature constraint failure keeps the raw return`() {
        // tsgo 7.0.2 reports:
        //   8:40 2345 Argument of type 'number' is not assignable to parameter of type 'string'.
        //   8:7 2322 Type '{ a: string; u: number; }' is not assignable to type '{ a: number; u: number; }'.
        //     Types of property 'a' are incompatible.
        //     Type 'string' is not assignable to type 'number'.
        val actual = abRows(
            """
            declare function cf<T extends string, U>(x: T): { a: T; u: U };
            const y: { a: number; u: number } = cf(1);
            """
        )
        val expected = listOf(
            "8:40 2345 Argument of type 'number' is not assignable to parameter of type 'string'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `residue - an argument literal is widened where the context asks for the literal`() {
        // tsgo 7.0.2 reports:
        //   nothing
        val actual = abRows(
            """
            declare function f1<T>(x: T): AB<T>;
            const y: AB<"x"> = f1("x");
            """
        )
        val expected = listOf(
            "8:7 2322 Type 'AB<string>' is not assignable to type 'AB<\"x\">'. / Type 'string' is not assignable to type '\"x\"'.",
        )
        assert(actual == expected)
    }
}
