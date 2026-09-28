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
 * (CHK.173) S-G1 — an optional-chain comparison narrows the chain ROOT, as tsgo's
 * `narrowTypeByOptionalChainContainment` (flow.go:1019) and its switch sibling
 * `narrowTypeBySwitchOptionalChainContainment` (flow.go:1202) do. `d?.p !== undefined`,
 * `d?.p === undefined; return`, `d?.p != null`, `n === d?.p` (n non-nullable), a deeper link
 * (`d?.a?.length !== 1`), an optional call / element link, a non-optional continuation link,
 * a `null` root, the `&&` / conditional forms and `switch (d?.k)` / `switch (typeof d?.p)` were
 * all false positives on the element-read channel (`d['p']` reported TS18048/TS18047) and on
 * the mis-assignment probe (which printed `… | undefined`).
 *
 * The controls are the branches where tsgo keeps `undefined`: the then branch of
 * `=== undefined`, the false branch of `=== 1`, the else of `!== undefined`, a nullable /
 * `any` / `unknown` compared value, `!== null` (strict, only `undefined` is the chain's
 * value), a parenthesised receiver (it ends the chain), a `default` in the clause range,
 * `case undefined`, `case 'undefined'` under typeof, and a plain `d!.x?.y` receiver.
 *
 * Every expectation is tsgo 7.0.2's over the same source (code, 1-based position, message) —
 * the directive header does not count toward the line numbers.
 */
class OptionalChainContainmentNarrowingTest {

    private val directives = "// @strict: true\n// @target: es2022\n// @useRealLibs: true"

    private fun rows(d: List<Diagnostic>): List<String> =
        d.map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `a strict inequality against undefined narrows the chain root`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p !== undefined) use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `an equality against undefined followed by return narrows what follows`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p === undefined) return;
              use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `the else branch of an equality against undefined is narrowed`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p === undefined) {} else use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a loose inequality against null narrows the chain root`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p != null) use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a loose equality against null followed by return narrows what follows`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p == null) return;
              use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a strict inequality against void 0 narrows the chain root`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p !== void 0) use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a reversed operand order narrows the chain root`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (undefined !== d?.p) use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `an equality against a non-nullable value narrows the chain root`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(n: number, d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (n === d?.p) use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `an inequality against a non-nullable value followed by return narrows what follows`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(n: number, d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p !== n) return;
              use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a comparison two optional links deep narrows the chain root`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.a?.length !== 1) return;
              use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `an optional method call compared against undefined narrows the chain root`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.m() !== undefined) use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `an optional element read compared against undefined narrows the chain root`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.['p'] !== undefined) use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a non-optional continuation link still belongs to the chain`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { a: { b?: number } } | undefined) {
              if (d?.a.b !== undefined) use(d['a']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a null chain root is narrowed`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number } | null) {
              if (d?.p !== undefined) use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `the right operand of an and expression is narrowed`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              return d?.p !== undefined && d['p'];
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `the true arm of a conditional is narrowed`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              return d?.p !== undefined ? d['p'] : 0;
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a parenthesized chain operand narrows the chain root`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if ((d?.p) !== undefined) use(d['p']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a loose inequality of an optional call against null narrows the chain root`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { m(): number | null } | undefined) {
              if (d?.m() != null) use(d['m']);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a switch over an optional chain narrows the root inside a case`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              switch (d?.k) {
                case 'a': use(d['p']);
              }
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a switch over typeof an optional chain narrows the root inside a case`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              switch (typeof d?.p) {
                case 'number': use(d['p']);
              }
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `a switch case value whose union type carries undefined still narrows as tsgo does`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(z: 'a' | undefined, d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              switch (d?.k) {
                case z: use(d['p']);
              }
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `the discriminant filter still sees the reduced type`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            type U = { kind: 'a'; aOnly: number } | { kind: 'b'; bOnly: string };
            export function f(d: U | undefined) {
              if (d?.kind === 'a') use(d.aOnly);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `the discriminant filter still sees the reduced type after return`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            type U = { kind: 'a'; aOnly: number } | { kind: 'b'; bOnly: string };
            export function f(d: U | undefined) {
              if (d?.kind !== 'a') return;
              use(d.aOnly);
            }
            """,
            directives,
        )
        assert(d.isEmpty())
    }

    @Test
    fun `the mis-assignment probe reads the narrowed root`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p !== undefined) { const q: boolean = d; }
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("3:35 TS2322 Type '{ p?: number | undefined; a?: number[] | undefined; k: \"a\" | \"b\"; m(): number; }' is not assignable to type 'boolean'."))
    }

    @Test
    fun `negative control - the then branch of an equality against undefined keeps undefined`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p === undefined) use(d['p']);
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("3:31 TS18048 'd' is possibly 'undefined'."))
    }

    @Test
    fun `negative control - an equality against a literal followed by return keeps undefined`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p === 1) return;
              use(d['p']);
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("4:7 TS18048 'd' is possibly 'undefined'."))
    }

    @Test
    fun `negative control - the else branch of an inequality against undefined keeps undefined`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p !== undefined) {} else use(d['p']);
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("3:39 TS18048 'd' is possibly 'undefined'."))
    }

    @Test
    fun `negative control - an equality against a nullable value keeps undefined`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(n: number | undefined, d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (n === d?.p) use(d['p']);
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("3:23 TS18048 'd' is possibly 'undefined'."))
    }

    @Test
    fun `negative control - an equality against an any value keeps undefined`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(a: any, d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (a === d?.p) use(d['p']);
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("3:23 TS18048 'd' is possibly 'undefined'."))
    }

    @Test
    fun `negative control - an equality against an unknown value keeps undefined`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(a: unknown, d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (a === d?.p) use(d['p']);
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("3:23 TS18048 'd' is possibly 'undefined'."))
    }

    @Test
    fun `negative control - a strict inequality against null keeps undefined`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              if (d?.p !== null) use(d['p']);
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("3:26 TS18048 'd' is possibly 'undefined'."))
    }

    @Test
    fun `negative control - a parenthesized receiver ends the chain`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { a: { b?: number } } | undefined) {
              if ((d?.a)?.b !== undefined) use(d['a']);
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("3:36 TS18048 'd' is possibly 'undefined'."))
    }

    @Test
    fun `negative control - a default clause in the range keeps undefined`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              switch (d?.k) {
                case 'a':
                default: use(d['p']);
              }
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("5:18 TS18048 'd' is possibly 'undefined'."))
    }

    @Test
    fun `negative control - a case undefined keeps undefined`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              switch (d?.k) {
                case undefined: use(d['p']);
              }
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("4:25 TS18048 'd' is possibly 'undefined'."))
    }

    @Test
    fun `negative control - a typeof case undefined keeps undefined`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { p?: number; a?: number[]; k: 'a' | 'b'; m(): number } | undefined) {
              switch (typeof d?.p) {
                case 'undefined': use(d['p']);
              }
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("4:27 TS18048 'd' is possibly 'undefined'."))
    }

    @Test
    fun `negative control - a plain member receiver is not an optional chain`() {
        val d = diagnose(
            """
            declare function use(v: unknown): void;
            export function f(d: { x?: { y?: number } } | undefined) {
              if (d!.x?.y !== undefined) use(d['x']);
            }
            """,
            directives,
        )
        assert(rows(d) == listOf("3:34 TS18048 'd' is possibly 'undefined'."))
    }
}
