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
 * (CHK.173) S-G4 - reference equality with a value that cannot be nullish narrows the
 * nullish member out of the reference on the equal branch, as tsgo's
 * `narrowTypeByEquality` (flow.go:556) does by keeping only the members comparable to the
 * value's type. The value's type is its FLOW type: `if (r) { if (r === nodes) nodes.x }`
 * narrows `nodes` because `r` is narrowed there (tsc's `expressionToTypeNode.ts:596`, the
 * real site). Before, only an OBJECT-typed value narrowed ((CHK.167)), and only through
 * its declared type. The real site's value is typed `NodeArray<Node> | (TInArray & undefined)`,
 * so it additionally needs `(A | undefined) & undefined` to distribute to `undefined` at
 * construction, as tsgo's `getIntersectionType` does - otherwise no truthiness guard sees
 * the member as nullish.
 *
 * Controls: the else branch, a value that stays nullable (declared or after a guard that
 * removes only `null`), `any` / `unknown` / an unconstrained type parameter / `void` values
 * (all of which `undefined` is comparable to), the true branch of `!==`, and an optional
 * chain compared with a nullable value.
 *
 * Known residues NOT pinned here (see the round note): a value constrained `T extends string`
 * (tsgo narrows, we keep the row), a `this.s` value (no reference path for `this`), and a
 * value whose narrowing an `as`-cast assignment should have reset (the flow walk passes
 * through that assignment - a pre-existing walker gap this rule inherits).
 *
 * Every expectation is tsgo 7.0.2's over the same source (code, 1-based position, message).
 */
class EqualityValueNullishNarrowTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private val prelude = "declare function use(v: unknown): void;\ndeclare function vis(a: number[] | undefined): number[] | undefined;\ndeclare function gs(): string;\n"

    private fun rows(d: List<Diagnostic>): List<String> =
        d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `a value narrowed by an enclosing truthiness guard narrows the reference - the real site`() {
        val d = diagnose(prelude + "export function f(nodes: number[] | undefined) { let r = vis(nodes); if (r) { if (r === nodes) use(nodes['length']); } }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a non-nullable primitive value narrows the reference`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string) { if (x === y) use(x['length']); }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `the reversed operand order narrows`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string) { if (y === x) use(x['length']); }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `loose equality narrows`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string) { if (x == y) use(x['length']); }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `an inequality guard with an early return narrows`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string) { if (x !== y) return; use(x['length']); }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a conditional expression narrows its true branch`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string) { return y === x ? x['length'] : 0 }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a value narrowed on the left of an and narrows the reference on its right`() {
        val d = diagnose(prelude + "export function f(nodes: number[] | undefined) { const r = vis(nodes); if (r && r === nodes) use(nodes['length']); }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a member value narrows`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, o: { s: string }) { if (x === o.s) use(x['length']); }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a call value narrows`() {
        val d = diagnose(prelude + "export function f(x: string | undefined) { if (x === gs()) use(x['length']); }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a primitive value narrowed by an enclosing guard narrows`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string | undefined) { if (y) { if (x === y) use(x['length']); } }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a narrowed member value narrows`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, o: { s?: string }) { if (o.s) { if (x === o.s) use(x['length']); } }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `three nested narrowed values narrow`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string | undefined, z: string | undefined) { if (z) { if (y === z) { if (x === y) use(x['length']); } } }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a null member is dropped too`() {
        val d = diagnose(prelude + "export function f(x: string | null, y: string) { if (x === y) use(x['length']); }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a value narrowed inside a loop narrows`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, ys: (string | undefined)[]) { for (const y of ys) { if (!y) continue; if (x === y) use(x['length']); } }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `a narrowed object value also runs the object half`() {
        val d = diagnose(prelude + "export function f(x: string | number[] | undefined, y: number[] | undefined) { if (y) { if (x === y) { const q: number[] = x; use(q); } } }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `an optional chain compared with a narrowed value narrows the chain root`() {
        val d = diagnose(prelude + "export function f(d: { p: number } | undefined, n: number | undefined) { if (n !== undefined) { if (n === d?.p) use(d['p']); } }" + "\n", directives)
        val expected = emptyList<String>()
        assert(rows(d) == expected)
    }

    @Test
    fun `the var-decl reader sees the narrowed type`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string | undefined) { if (y) { if (x === y) { const q: boolean = x; use(q); } } }" + "\n", directives)
        val expected = listOf(
            "4:97 TS2322 Type 'string' is not assignable to type 'boolean'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - the else branch keeps the nullish member`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string) { if (x === y) {} else use(x['length']); }" + "\n", directives)
        val expected = listOf(
            "4:80 TS18048 'x' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a value that stays nullable does not narrow`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, z: string | undefined) { if (x === z) use(x['length']); }" + "\n", directives)
        val expected = listOf(
            "4:84 TS18048 'x' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a value narrowed only away from null keeps undefined possible`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string | null | undefined) { if (y !== null) { if (x === y) use(x['length']); } }" + "\n", directives)
        val expected = listOf(
            "4:109 TS18048 'x' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - an any value does not narrow`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: any) { if (x === y) use(x['length']); }" + "\n", directives)
        val expected = listOf(
            "4:69 TS18048 'x' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - an unknown value does not narrow`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: unknown) { if (x === y) use(x['length']); }" + "\n", directives)
        val expected = listOf(
            "4:73 TS18048 'x' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - an unconstrained type parameter value does not narrow`() {
        val d = diagnose(prelude + "export function f<T>(x: string | undefined, y: T) { if (x === y) use(x['length']); }" + "\n", directives)
        val expected = listOf(
            "4:70 TS18048 'x' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - a void value does not narrow`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: void) { if (x === y) use(x['length']); }" + "\n", directives)
        val expected = listOf(
            "4:70 TS18048 'x' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - an inequality true branch does not narrow`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string) { if (x !== y) { use(x['length']); } }" + "\n", directives)
        val expected = listOf(
            "4:74 TS18048 'x' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - an optional chain compared with a nullable value keeps reporting`() {
        val d = diagnose(prelude + "export function f(d: { p: number } | undefined, n: number | undefined) { if (n === d?.p) use(d['p']); }" + "\n", directives)
        val expected = listOf(
            "4:94 TS18048 'd' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `negative control - the else branch of a narrowed value keeps the nullish member`() {
        val d = diagnose(prelude + "export function f(x: string | undefined, y: string | undefined) { if (y) { if (x === y) {} else use(x['length']); } }" + "\n", directives)
        val expected = listOf(
            "4:101 TS18048 'x' is possibly 'undefined'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `the real site shape - a union intersected with undefined reduces and a truthiness guard then narrows the value`() {
        val d = diagnose(
            "declare function use(v: unknown): void;\n" +
            "interface Node { kind: number }\n" +
            "interface NodeArray<T extends Node> extends ReadonlyArray<T> { readonly pos: number; readonly end: number; readonly hasTrailingComma: boolean }\n" +
            "type Visitor = (n: Node) => Node;\n" +
            "declare function visitNodes<TIn extends Node, TInArray extends NodeArray<TIn> | undefined>(nodes: TInArray, visitor: Visitor, test?: (node: Node) => boolean, start?: number, count?: number): NodeArray<Node> | (TInArray & undefined);\n" +
            "export function v(nodes: NodeArray<Node> | undefined, visitor: Visitor): NodeArray<Node> | undefined {\n" +
            "    let result = visitNodes(nodes, visitor);\n" +
            "    const q0: boolean = result;\n" +
            "    if (result) {\n" +
            "        const q1: boolean = result;\n" +
            "        if (result === nodes) {\n" +
            "            use(nodes['length']);\n" +
            "        }\n" +
            "    }\n" +
            "    return result;\n" +
            "}\n",
            directives,
        )
        val expected = listOf(
            "10:15 TS2322 Type 'NodeArray<Node>' is not assignable to type 'boolean'.",
            "8:11 TS2322 Type 'NodeArray<Node> | undefined' is not assignable to type 'boolean'.",
        )
        assert(rows(d) == expected)
    }

    @Test
    fun `a union intersected with a nullish type distributes and keeps only the nullish member`() {
        val d = diagnose(
            "declare function use(v: unknown): void;\n" +
            "declare function g<T extends string | undefined>(x: T): number | (T & undefined);\n" +
            "declare function h<T>(x: T): number | (T & undefined);\n" +
            "declare function k<T extends object | null>(x: T): number | (T & null);\n" +
            "export function f(a: string | undefined, b: string, c: string[] | null, d: void | string) {\n" +
            "    const q1: boolean = g(a);\n" +
            "    const q2: boolean = g(b);\n" +
            "    const q3: boolean = h(d);\n" +
            "    const q4: boolean = k(c);\n" +
            "    const q5: boolean = h(a);\n" +
            "}\n",
            directives,
        )
        val expected = listOf(
            "10:11 TS2322 Type 'number | undefined' is not assignable to type 'boolean'.",
            "6:11 TS2322 Type 'number | undefined' is not assignable to type 'boolean'.",
            "7:11 TS2322 Type 'number' is not assignable to type 'boolean'.",
            "8:11 TS2322 Type 'number | undefined' is not assignable to type 'boolean'.",
            "9:11 TS2322 Type 'number | null' is not assignable to type 'boolean'.",
        )
        assert(rows(d) == expected)
    }
}
