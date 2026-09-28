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
 * (CHK.173) S-G4b - overload SELECTION for tsc's two-overload `visitNodes(nodes, visitor,
 * test, start, count)` (`expressionToTypeNode.ts:596`). Three defects in
 * `Checker.signatureAcceptsArgs` made every overload refuse, or the wrong one accept, so
 * `resolveCallOverload` fell back to the FIRST (the predicate overload) and typed the
 * result through its un-inferred `TOut`:
 *
 *  1. an OPTIONAL parameter (`t?: T`, `t: T = ...`) did not accept `undefined` - an optional
 *     parameter passed through (`T | undefined`) was refused by the overload it matches;
 *  2. a type parameter whose constraint names ANOTHER type parameter (`TInArray extends
 *     NodeArray<TIn> | undefined`) was related to the raw constraint, still carrying `TIn`;
 *  3. a callback parameter spelling a type PREDICATE accepted an argument returning a plain
 *     `boolean` (tsgo: TS1224 *must be a type predicate*), because the relation models a
 *     predicate return as `boolean`. Refused at overload acceptance only, and only for an
 *     argument whose every signature has an explicit non-predicate return annotation (an
 *     un-annotated function may carry a predicate tsgo infers).
 *
 * The TS2769 matcher (`allArgumentsMatch`, `getFirstArgumentError`) asks rule 3 too.
 * Residues (round note): the single-signature argument check does not refuse a boolean
 * callback against a predicate parameter (tsgo TS2345); a predicate argument selecting the
 * generic predicate overload leaves `TOut` un-inferred; an un-annotated non-predicate arrow
 * still selects a predicate overload; TS2769's TS1224 chain line and a predicate parameter's
 * display (`(n: Node) => n is Node`) are not rendered.
 *
 * Every expectation is tsgo 7.0.2's over the same source (code, 1-based position, message).
 */
class OverloadSelectionOptionalPredicateTest {

    private val strictDirectives = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"
    private val looseDirectives = "// @strict: false\n// @target: es2022\n// @useRealLibs: true"

    private fun rows(d: List<Diagnostic>): List<String> =
        d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `the real visitNodes site selects the optional-test overload and types the result`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function visitNodes<TIn extends Node, TInArray extends NodeArray<TIn> | undefined, TOut extends Node>(nodes: TInArray, visitor: Visitor, test: (node: Node) => node is TOut, start?: number, count?: number): NodeArray<TOut> | (TInArray & undefined);
            declare function visitNodes<TIn extends Node, TInArray extends NodeArray<TIn> | undefined>(nodes: TInArray, visitor: Visitor, test?: (node: Node) => boolean, start?: number, count?: number): NodeArray<Node> | (TInArray & undefined);
            function inner(nodes: NodeArray<Node> | undefined, visitor: Visitor, test?: (node: Node) => boolean, start?: number, count?: number): NodeArray<Node> | undefined {
                let result = visitNodes(nodes, visitor, test, start, count);
                if (result) {
                    const q1: boolean = result;
                    if (result === nodes) { use(nodes["length"]); }
                }
                return result;
            }
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("10:15 TS2322 Type 'NodeArray<Node>' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `a direct probe on the two-overload call reads the second overload result`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function visitNodes<TIn extends Node, TInArray extends NodeArray<TIn> | undefined, TOut extends Node>(nodes: TInArray, visitor: Visitor, test: (node: Node) => node is TOut, start?: number, count?: number): NodeArray<TOut> | (TInArray & undefined);
            declare function visitNodes<TIn extends Node, TInArray extends NodeArray<TIn> | undefined>(nodes: TInArray, visitor: Visitor, test?: (node: Node) => boolean, start?: number, count?: number): NodeArray<Node> | (TInArray & undefined);
            function g(nodes: NodeArray<Node> | undefined, visitor: Visitor, test?: (node: Node) => boolean) { const q: boolean = visitNodes(nodes, visitor, test); }
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("7:106 TS2322 Type 'NodeArray<Node> | undefined' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `a required boolean callback does not select the predicate overload`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function visitNodes<TIn extends Node, TInArray extends NodeArray<TIn> | undefined, TOut extends Node>(nodes: TInArray, visitor: Visitor, test: (node: Node) => node is TOut, start?: number, count?: number): NodeArray<TOut> | (TInArray & undefined);
            declare function visitNodes<TIn extends Node, TInArray extends NodeArray<TIn> | undefined>(nodes: TInArray, visitor: Visitor, test?: (node: Node) => boolean, start?: number, count?: number): NodeArray<Node> | (TInArray & undefined);
            function g(nodes: NodeArray<Node> | undefined, visitor: Visitor, test: (node: Node) => boolean) { const q: boolean = visitNodes(nodes, visitor, test); }
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("7:105 TS2322 Type 'NodeArray<Node> | undefined' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `an optional parameter passed through selects the optional overload`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t: string): string;
            declare function f(t?: number): number;
            function g(t?: number) { const q: boolean = f(t); }
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("7:32 TS2322 Type 'number' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `an undefined argument selects the optional overload`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t: string): string;
            declare function f(t?: number): number;
            const q: boolean = f(undefined);
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("7:7 TS2322 Type 'number' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `both overloads optional - the matching one is selected`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t?: string): string;
            declare function f(t?: number): number;
            function g(t?: number) { const q: boolean = f(t); }
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("7:32 TS2322 Type 'number' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `an overloaded method selects its optional overload`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            interface I { m(t: string): string; m(t?: number): number; }
            function g(i: I, t?: number) { const q: boolean = i.m(t); }
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("6:38 TS2322 Type 'number' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `an optional second parameter selects its overload`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(a: number, t: string): string;
            declare function f(a: number, t?: number): number;
            function g(t?: number) { const q: boolean = f(1, t); }
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("7:32 TS2322 Type 'number' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `a parameter with an initializer accepts undefined`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t: string): string;
            function f2(t: string): string;
            function f2(t: number = 1): number;
            function f2(t: any = 1): any { return t; }
            function g(t?: number) { const q: boolean = f2(t); }
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("7:13 TS2371 A parameter initializer is only allowed in a function or constructor implementation.", "9:32 TS2322 Type 'number' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `a non-generic predicate overload refuses a boolean callback`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t: (n: Node) => n is Node): string;
            declare function f(t?: (n: Node) => boolean): number;
            function g(t: (n: Node) => boolean) { const q: boolean = f(t); }
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("7:45 TS2322 Type 'number' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `an arrow annotated to return boolean does not select the predicate overload`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t: (n: Node) => n is Node): string;
            declare function f(t?: (n: Node) => boolean): number;
            const q: boolean = f((n: Node): boolean => true);
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("7:7 TS2322 Type 'number' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `a method returning boolean does not select the predicate overload`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t: (n: Node) => n is Node): string;
            declare function f(t?: (n: Node) => boolean): number;
            declare const o: { m(n: Node): boolean };
            const q: boolean = f(o.m);
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("8:7 TS2322 Type 'number' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `a constraint naming another type parameter is related through its constraint`() {
        val d = diagnose(
            """
            declare function f<A extends number[]>(a: A, x: string): string;
            declare function f<T extends number, A extends T[]>(a: A, x: number): number;
            declare const arr: number[];
            const q: boolean = f(arr, 1);
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("4:7 TS2322 Type 'number' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `a predicate refusal keeps the candidate in the no-overload pool`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t: (n: Node) => n is Node, x: string): string;
            declare function f(t: (n: Node) => boolean, x: number): number;
            declare const b: (n: Node) => boolean;
            f(b, "s");
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("8:6 TS2769 No overload matches this call.")
        assert(rows(d) == expected)
    }

    @Test
    fun `a predicate refusal on the last overload reports it`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t: (n: Node) => boolean, x: number): number;
            declare function f(t: (n: Node) => n is Node, x: string): string;
            declare const b: (n: Node) => boolean;
            f(b, "s");
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("8:3 TS2769 No overload matches this call.")
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a named type guard still selects the predicate overload`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function isN(n: Node): n is Node;
            declare function f(t: (n: Node) => n is Node): string;
            declare function f(t?: (n: Node) => boolean): number;
            const q: boolean = f(isN);
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("8:7 TS2322 Type 'string' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - an unannotated arrow is never refused`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t: (n: Node | undefined) => n is Node): string;
            declare function f(t?: (n: Node | undefined) => boolean): number;
            const q: boolean = f(n => n !== undefined);
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("7:7 TS2322 Type 'string' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - an asserts predicate parameter is not refused`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t: (n: Node) => asserts n is Node): string;
            declare function f(t?: (n: Node) => boolean): number;
            declare const b: (n: Node) => void;
            const q: boolean = f(b);
            export {};
            """,
            strictDirectives,
        )
        val expected = listOf<String>("8:7 TS2322 Type 'string' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - without strict null checks the optional overload is selected too`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            interface Node { kind: number }
            interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }
            type Visitor = (n: Node) => Node;
            declare function f(t: string): string;
            declare function f(t?: number): number;
            function g(t?: number) { const q: boolean = f(t); }
            export {};
            """,
            looseDirectives,
        )
        val expected = listOf<String>("7:32 TS2322 Type 'number' is not assignable to type 'boolean'.")
        assert(rows(d) == expected)
    }
}
