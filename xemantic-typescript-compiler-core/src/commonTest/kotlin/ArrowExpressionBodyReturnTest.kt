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
 * (CHK.168) round 1, round P18.194 — an annotated arrow's EXPRESSION body is return-checked by the
 * block-body `return` machinery, from the scoped walk that has the arrow's parameters in scope
 * (`Checker.walkArrowExpressionBodyScoped` -> `checkArrowExpressionBodyReturn`). tsgo
 * `checkFunctionExpressionOrObjectLiteralMethodDeferred` hands a concise body to the SAME
 * `checkReturnExpression` a `return` statement reaches; only the error node differs — the effective
 * expression (parentheses and `satisfies` skipped) instead of the `return` keyword. Before this the
 * check ran on the SPINE, where the parameters are out of scope, so only a scope-free body reported:
 * `(x: string): number => x` was silent.
 *
 * Also pinned: inside an ASYNC function each branch of a returned ternary is related to the PROMISED
 * type (`Checker.craConditionalBranches`), block body and concise body alike — tsgo recurses into the
 * branches with the unwrapped return type. It was an ours-only row on every literal arm before.
 * And `keyof typeof ns.Enum` — the QUALIFIED spelling — is the enum's member names, as the Identifier
 * spelling already was (`Checker.keyofTypeQueryEnumMemberNames`); without it the concise check added
 * one ours-only row to the harness profile (`fourslashImpl.ts:3890`), and the block path already had
 * the same false positive.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18194/pins` on the identical
 * text (`strict`, `target: es2020`). A row is `line:column code message` with its chain appended as
 * ` / <line>`.
 */
class ArrowExpressionBodyReturnTest {

    private val directives = """
        // @useRealLibs: true
        // @strict: true
        // @target: es2020
    """.trimIndent()

    private fun compile(source: String): List<Diagnostic> =
        diagnose(directives + "\n" + source.trimIndent(), directives = "")

    private fun rowsOf(ds: List<Diagnostic>): List<String> =
        ds.map { d ->
            "${d.line}:${d.character} ${d.code} ${d.message}" +
                d.messageChain.joinToString("") { " / " + it.trim() }
        }.sorted()

    private fun rows(source: String) = rowsOf(compile(source))

    private fun spans(source: String): List<String> =
        compile(source).map { "${it.line}:${it.character} len=${it.length}" }.sorted()

    @Test
    fun `an identifier body is checked against the annotation`() {
        val actual = rows(
            """
            export const f = (x: string): number => x;
            """
        )
        val expected = listOf("1:41 2322 Type 'string' is not assignable to type 'number'.")
        assert(actual == expected)
    }

    @Test
    fun `a property access and a call body are checked`() {
        val actual = rows(
            """
            export const f = (o: { s: string }): number => o.s;
            export const g = (h: () => string): number => h();
            """
        )
        val expected = listOf(
            "1:48 2322 Type 'string' is not assignable to type 'number'.",
            "2:47 2322 Type 'string' is not assignable to type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a conditional body reports its failing branch exactly once`() {
        val actual = rows(
            """
            export const f = (b: boolean, x: string): number => b ? x : 1;
            """
        )
        val expected = listOf("1:57 2322 Type 'string' is not assignable to type 'number'.")
        assert(actual == expected)
    }

    @Test
    fun `a conditional body with a literal branch against a plain declaration reports once`() {
        // corpus conditionalReturnExpression's return5 shape, without its export
        val actual = rows(
            """
            declare function getAny(): any;
            const return5 = (x: string): string => x.startsWith("a") ? getAny() : 1;
            export {};
            """
        )
        val expected = listOf("2:71 2322 Type 'number' is not assignable to type 'string'.")
        assert(actual == expected)
    }

    @Test
    fun `a parenthesized body anchors at the expression inside the parentheses`() {
        val actual = spans(
            """
            export const f = (x: number): string => (x);
            """
        )
        assert(actual == listOf("1:42 len=1"))
    }

    @Test
    fun `a satisfies body anchors at the expression it wraps with its own width`() {
        val actual = spans(
            """
            export const g = (x: string): number => ((x + "") satisfies string);
            export const h = (x: string): number => x.trim();
            """
        )
        assert(actual == listOf("1:43 len=6", "2:41 len=8"))
    }

    @Test
    fun `a weak target reports TS2559 at the body`() {
        val actual = rows(
            """
            type W = { a?: number; b?: string };
            export const f = (x: { c: number }): W => x;
            """
        )
        val expected = listOf("2:43 2559 Type '{ c: number; }' has no properties in common with type 'W'.")
        assert(actual == expected)
    }

    @Test
    fun `negative control - an async arrow's body is related to the promised type`() {
        val actual = rows(
            """
            export const f = async (x: number): Promise<number> => x;
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - a generic method call body is not checked as its uninstantiated signature`() {
        val actual = rows(
            """
            export const f = (xs: number[]): string[] => xs.map(String);
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `negative control - a promise returned from a non-async arrow is related as a promise`() {
        val actual = rows(
            """
            export const f = (p: Promise<string>): Promise<string> => p.then(s => s);
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `an async arrow's conditional branches are related to the promised type`() {
        val actual = rows(
            """
            export const f = async (b: boolean, s: string): Promise<number> => b ? 1 : s;
            export const g = async (b: boolean, n: number): Promise<number> => b ? n : 2;
            """
        )
        val expected = listOf("1:76 2322 Type 'string' is not assignable to type 'number'.")
        assert(actual == expected)
    }

    @Test
    fun `an async function's returned conditional branches are related to the promised type`() {
        val actual = rows(
            """
            export async function f(b: boolean, n: number): Promise<number> { return b ? n : 2; }
            export async function g(b: boolean, s: string): Promise<number> { return b ? 1 : s; }
            """
        )
        val expected = listOf("2:82 2322 Type 'string' is not assignable to type 'number'.")
        assert(actual == expected)
    }

    @Test
    fun `an argument arrow's contextually typed parameter reaches the body check`() {
        val actual = rows(
            """
            declare function take(cb: (x: string) => unknown): void;
            take((x): number => x);
            export {};
            """
        )
        val expected = listOf("2:21 2322 Type 'string' is not assignable to type 'number'.")
        assert(actual == expected)
    }

    @Test
    fun `a variable annotation's contextually typed parameter reaches the body check`() {
        val actual = rows(
            """
            export const g: (x: string) => number = (x): number => x;
            """
        )
        val expected = listOf("1:56 2322 Type 'string' is not assignable to type 'number'.")
        assert(actual == expected)
    }

    @Test
    fun `nested arrows are checked in function bodies methods object literals returns calls and arrows`() {
        val actual = rows(
            """
            export function outer() { const h = (x: string): number => x; return h; }
            export class C { m(y: string) { const h = (x: string): number => x + y; return h; } }
            export const o = { m: (x: string): number => x };
            export function mk() { return (x: string): number => x; }
            export const v = ((x: string): number => x)("a");
            export const f = (a: string) => (x: string): number => x + a;
            """
        )
        val expected = listOf(
            "1:60 2322 Type 'string' is not assignable to type 'number'.",
            "2:66 2322 Type 'string' is not assignable to type 'number'.",
            "3:46 2322 Type 'string' is not assignable to type 'number'.",
            "4:54 2322 Type 'string' is not assignable to type 'number'.",
            "5:42 2322 Type 'string' is not assignable to type 'number'.",
            "6:56 2322 Type 'string' is not assignable to type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a parameterless arrow is still checked with the spine call retired`() {
        val actual = rows(
            """
            declare const s: string;
            export const f = (): number => "lit";
            export const g = (): number => s;
            """
        )
        val expected = listOf(
            "2:32 2322 Type 'string' is not assignable to type 'number'.",
            "3:32 2322 Type 'string' is not assignable to type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `the target shapes elaborate as a return statement does`() {
        val actual = rows(
            """
            export const a = (x: string[]): number[] => x;
            interface P { a: number }
            export const b = (x: { a: string }): P => x;
            export const c = (x: string): never => x;
            export const d = (x: string): { a: number } => x;
            export const e = (x: boolean): number | string => x;
            export const f = (x: string): void => x;
            export const g = (x: readonly string[]): string[] => x;
            """
        )
        val expected = listOf(
            "1:45 2322 Type 'string[]' is not assignable to type 'number[]'. / Type 'string' is not assignable to type 'number'.",
            "3:43 2322 Type '{ a: string; }' is not assignable to type 'P'. / Types of property 'a' are incompatible. / Type 'string' is not assignable to type 'number'.",
            "4:40 2322 Type 'string' is not assignable to type 'never'.",
            "5:48 2322 Type 'string' is not assignable to type '{ a: number; }'.",
            "6:51 2322 Type 'boolean' is not assignable to type 'string | number'.",
            "7:39 2322 Type 'string' is not assignable to type 'void'.",
            "8:54 4104 The type 'readonly string[]' is 'readonly' and cannot be assigned to the mutable type 'string[]'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `a parenthesized object-literal body keeps the annotation as its contextual type`() {
        // the harness profile's fourslashImpl.ts:3886 shape: `matchKind: e.matchKind || "exact"`
        // against a literal-union member must stay literal, as it does in a `return ({ … })`
        val actual = rows(
            """
            interface N { k: "a" | "b"; n: string }
            export const f = (s?: "a" | "b"): N => ({ k: s || "a", n: "" });
            export const g = (xs: { k?: "a" | "b" }[]) => xs.map((e): N => ({ k: e.k || "a", n: "" }));
            export const h = (s?: "a" | "b"): N => ({ k: s || "c", n: "" });
            """
        )
        val expected = listOf("4:43 2322 Type '\"a\" | \"b\" | \"c\"' is not assignable to type '\"a\" | \"b\"'.")
        assert(actual == expected)
    }

    @Test
    fun `a qualified keyof typeof enum is the enum's member names in every return spelling`() {
        // the harness profile's fourslashImpl.ts:3890 shape, which the concise check would
        // otherwise have reported: `keyof typeof ts.PatternMatchKind` answered `string`/`never`
        // for the QUALIFIED spelling while the Identifier spelling answered the names
        val actual = rows(
            """
            namespace ts { export enum PatternMatchKind { exact, prefix, substring, camelCase } }
            interface NI { matchKind: keyof typeof ts.PatternMatchKind; }
            interface O { readonly matchKind?: keyof typeof ts.PatternMatchKind; }
            export const f = (e: O): NI => ({ matchKind: e.matchKind || "exact" });
            export function g(e: O): NI { return { matchKind: e.matchKind || "exact" }; }
            export const h = (e: O): NI => { return { matchKind: e.matchKind || "exact" }; };
            const v: NI = { matchKind: e0().matchKind || "exact" };
            declare function e0(): O;
            export const k: keyof typeof ts.PatternMatchKind = "exact";
            const p: number = null! as O["matchKind"];
            """
        )
        val expected = listOf(
            "10:7 2322 Type 'string | undefined' is not assignable to type 'number'. / Type 'undefined' is not assignable to type 'number'.",
        )
        assert(actual == expected)
    }

    @Test
    fun `negative control - narrowed and legal bodies stay clean`() {
        val actual = rows(
            """
            export const a = (x: string | undefined): boolean => !!x && x.length > 0;
            export const b = (x: string | undefined): string => x!;
            export const c = (x: string | undefined): string => x ?? "";
            export const d = (x: string | null): string => x !== null ? x : "";
            export const e = (x: string | number): number => typeof x === "number" ? x : 0;
            declare function isS(v: unknown): v is string;
            export const g = (x: string | number): string => isS(x) ? x : "";
            type S = { k: "a"; v: string } | { k: "b"; v: number };
            export const h = (s: S): string => s.k === "a" ? s.v : String(s.v);
            interface A { a: string } interface B { b: string }
            export const i = (x: A | B): string => "a" in x ? x.a : x.b;
            export const j = (b: boolean): "a" | "b" => b ? "a" : "b";
            export const k = (m: Map<string, number>): number => m.get("a") ?? 0;
            export const l = (o: { a?: string }): string => o.a ?? "d";
            export const m = (x: string): void => console.log(x);
            export function n(y: string | undefined) { if (!y) return; const q = (): string => y; return q; }
            interface K { k: 1 }
            export const p = (x: K | undefined): K => x ?? { k: 1 };
            export const r = (x: string): ((y: number) => number) => y => y + x.length;
            """
        )
        assert(actual.isEmpty())
    }
}
