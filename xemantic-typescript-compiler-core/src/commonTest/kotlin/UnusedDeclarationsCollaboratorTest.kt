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
 * (INV.0) (P18.275) The UNUSED-DECLARATION family as it lives in `UnusedDeclarations` after the
 * extraction: the three passes (`checkUnusedDeclarations`, `checkUnusedParameterProperties`,
 * `checkUnusedInferParameters`), the reference collectors under them, the type-parameter
 * checkers, the private-member check with its computed-key memo, the TS6198 / TS6199 grouping,
 * and the two helpers `Checker` still calls from outside the family —
 * `computeBindingPatternSpan` (the TS1182 squiggle) and `collectTypeReferenceNames`.
 *
 * Every row is tsgo 7.0.2's, length included where its `--pretty` squiggle shows one (cells under
 * `build/bench/p18275-agent/matrix/`, 1-based column); all nine cells agree on both arms of the
 * move. One cell carried a pre-existing ours-only row (a PUBLIC parameter property read TS6138
 * where tsgo is silent), closed by (CHK.221) and pinned in `ParameterPropertyUnusedTest`; the
 * fixture below keeps only the private one.
 */
class UnusedDeclarationsCollaboratorTest {

    private val unusedDirectives =
        "// @strict: true\n// @target: es2020\n// @noUnusedLocals: true\n// @noUnusedParameters: true"

    private fun rows(source: String): List<String> =
        diagnose(source, directives = unusedDirectives)
            .filter { it.fileName == null || it.fileName.endsWith("t.ts") }
            .map { "${it.line}:${it.character} TS${it.code} len=${it.length} ${it.message}" }.sorted()

    private fun rowsNoLen(source: String): List<String> =
        diagnose(source, directives = unusedDirectives)
            .filter { it.fileName == null || it.fileName.endsWith("t.ts") }
            .map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `an unread local is TS6133 and a read one is silent`() {
        val r = rowsNoLen(
            """
            export function f(): number {
                const unused = 1;
                let used = 2;
                return used;
            }
            """
        )
        assert(r == listOf("2:11 TS6133 'unused' is declared but its value is never read."))
    }

    @Test
    fun `an import declaration whose every binding is unread is one TS6192`() {
        val r = rowsNoLen(
            """
            // @Filename: m.ts
            export const a = 1;
            export const b = 2;
            export const c = 3;

            // @Filename: t.ts
            import { a, b } from "./m";
            import { c } from "./m";
            export const x = c;
            """
        )
        assert(r == listOf("1:1 TS6192 All imports in import declaration are unused."))
    }

    @Test
    fun `an unused type alias and interface are TS6196`() {
        val r = rowsNoLen(
            """
            type Unused = string;
            interface AlsoUnused { p: number }
            type Used = number;
            export const v: Used = 1;
            """
        )
        assert(
            r == listOf(
                "1:6 TS6196 'Unused' is declared but never used.",
                "2:11 TS6196 'AlsoUnused' is declared but never used.",
            )
        )
    }

    @Test
    fun `an unread parameter and an unread private parameter property`() {
        val r = rowsNoLen(
            """
            export function g(a: number, _b: number, c: number): number {
                return a;
            }
            export class K {
                constructor(private readonly p: number) {}
            }
            """
        )
        assert(
            r == listOf(
                "1:42 TS6133 'c' is declared but its value is never read.",
                "5:34 TS6138 Property 'p' is declared but its value is never read.",
            )
        )
    }

    @Test
    fun `an infer type parameter that the true branch never reads`() {
        val r = rowsNoLen(
            """
            export type Elem<T> = T extends Array<infer U> ? string : never;
            export type Elem2<T> = T extends Array<infer U> ? U : never;
            """
        )
        assert(r == listOf("1:45 TS6196 'U' is declared but never used."))
    }

    @Test
    fun `an unread private method and field and a read private method`() {
        val r = rowsNoLen(
            """
            export class C {
                private never(): void {}
                private used(): number { return 1; }
                private field = 2;
                m(): number { return this.used(); }
            }
            """
        )
        assert(
            r == listOf(
                "2:13 TS6133 'never' is declared but its value is never read.",
                "4:13 TS6133 'field' is declared but its value is never read.",
            )
        )
    }

    @Test
    fun `an all-unused object and array pattern group at the root pattern`() {
        val r = rows(
            """
            export function h(o: { a: number; b: { c: number; d: number } }): void {
                const { a, b: { c, d } } = o;
                const [x, y] = [1, 2];
            }
            """
        )
        assert(
            r == listOf(
                "2:11 TS6198 len=18 All destructured elements are unused.",
                "3:11 TS6198 len=6 All destructured elements are unused.",
            )
        )
    }

    @Test
    fun `unused class interface and alias type parameters`() {
        val r = rowsNoLen(
            """
            export class Box<T, U> {
                value!: T;
            }
            export interface I<A, B> { a: A }
            type Alias<P, Q> = P;
            export const z: Alias<number, string> = 1;
            """
        )
        assert(
            r == listOf(
                "1:21 TS6196 'U' is declared but never used.",
                "4:23 TS6196 'B' is declared but never used.",
                "5:15 TS6196 'Q' is declared but never used.",
            )
        )
    }

    @Test
    fun `a template type reference counts and a pattern without initializer spans the pattern`() {
        val r = rows(
            """
            type Trim<S extends string> = S;
            export type T2<P extends string> = `${'$'}{Trim<P>}`;
            let a = 1, b = 2;
            export {};
            const { p, q };
            """
        ).filter { "TS7031" !in it }
        assert(
            r == listOf(
                "3:1 TS6199 len=16 All variables are unused.",
                "5:7 TS1182 len=8 A destructuring declaration must have an initializer.",
                "5:7 TS6198 len=8 All destructured elements are unused.",
            )
        )
    }
}
