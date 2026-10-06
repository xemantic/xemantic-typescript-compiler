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
 * (INV.0) (P18.312) The two families moved out of `Checker` in this round, as they live in their
 * collaborators after the extraction: `InRhsPrimitiveTypeParamChecks` (the pass
 * `checkInRhsPrimitiveTypeParams`: an unconstrained type parameter on the right of `in`, its
 * `= undefined` parameter default and `Object.keys` of it — TS2322 / TS2769 with the TS2208
 * related row) and `TypeAsNamespaceChecks` (the pass `checkTypeUsedAsNamespaceRefs`: a
 * namespace-local type used as a qualifier — TS2702 / TS2713, and an enum member indexed on the
 * enum type — TS2339). Every expected row is tsgo 7.0.2's (cells under
 * `build/bench/p18312-agent/matrix/`, 1-based column); all cells read byte-identical on both arms
 * of the move. The lib-file related row of `Object.keys` carries no position here
 * (`pinRel(…, null, null, …)`), where tsgo names `lib.es5.d.ts:262:5`.
 */
class InRhsAndTypeAsNamespaceCollaboratorTest {

    private val esm = "// @strict: true\n// @target: es2020\n// @module: esnext\n// @moduleResolution: bundler"

    private fun rows(source: String): List<String> =
        diagnose(source, directives = esm, fileName = "t.ts")
            .map { d ->
                "${d.fileName}:${d.line}:${d.character} TS${d.code} ${d.message}" +
                    d.messageChain.joinToString("") { " | ${it.trim()}" } +
                    d.relatedInformation.joinToString("") { " [${it.fileName}:${it.line}:${it.character} ${it.message}]" }
            }.sorted()

    private val needsObject = "This type parameter might need an `extends object` constraint."

    @Test
    fun `TS2322 - an unconstrained type parameter on the right of in`() {
        val r = rows(
            """
            export function f<T>(x: T) {
                return "a" in x;
            }
            """,
        )
        assert(r == listOf("t.ts:2:19 TS2322 Type 'T' is not assignable to type 'object'. [t.ts:1:19 $needsObject]"))
    }

    @Test
    fun `TS2322 - a literal-and-object constraint still fails on the right of in`() {
        val r = rows(
            """
            export function f<T extends "hello" | object>(x: T) {
                return "a" in x;
            }
            """,
        )
        assert(r.size == 1)
        assert(r[0].startsWith("t.ts:2:19 TS2322 Type 'T' is not assignable to type 'object'."))
    }

    @Test
    fun `TS2322 - an arrow expression body and an and-chain reach both type parameters`() {
        val r = rows(
            """
            export const g = <T,>(x: T) => "a" in x ? 1 : 2;
            export const h = <T, U>(x: T, y: U) => "a" in x && "b" in y;
            """,
        )
        assert(
            r == listOf(
                "t.ts:1:39 TS2322 Type 'T' is not assignable to type 'object'. [t.ts:1:19 $needsObject]",
                "t.ts:2:47 TS2322 Type 'T' is not assignable to type 'object'. [t.ts:2:19 $needsObject]",
                "t.ts:2:59 TS2322 Type 'U' is not assignable to type 'object'. [t.ts:2:22 $needsObject]",
            ),
        )
    }

    @Test
    fun `TS2322 - an undefined default for an own unconstrained type parameter`() {
        val r = rows(
            """
            export function f<T>(x: T = undefined) {
                return x;
            }
            export const h = <U,>(y: U = undefined) => y;
            """,
        )
        assert(
            r == listOf(
                "t.ts:1:22 TS2322 Type 'undefined' is not assignable to type 'T'. | 'T' could be instantiated with an arbitrary type which could be unrelated to 'undefined'.",
                "t.ts:4:23 TS2322 Type 'undefined' is not assignable to type 'U'. | 'U' could be instantiated with an arbitrary type which could be unrelated to 'undefined'.",
            ),
        )
    }

    @Test
    fun `TS2769 - Object keys of an unconstrained type parameter in an arrow body`() {
        val r = rows(
            """
            export const k = <T,>(x: T) => Object.keys(x);
            """,
        )
        assert(r.size == 1)
        assert(r[0].startsWith("t.ts:1:44 TS2769 No overload matches this call. | The last overload gave the following error. | Argument of type 'T' is not assignable to parameter of type 'object'."))
        assert("[t.ts:1:19 $needsObject]" in r[0])
        assert("The last overload is declared here." in r[0])
    }

    @Test
    fun `negative control - an object or empty-object constraint is legal on the right of in`() {
        val r = rows(
            """
            export function f<T extends object>(x: T) {
                return "a" in x;
            }
            export function g<T extends {}>(x: T) {
                return "a" in x;
            }
            """,
        )
        assert(r.isEmpty())
    }

    @Test
    fun `TS2713 - a namespace-local interface used as a qualifier whose member exists`() {
        val r = rows(
            """
            export namespace N {
                interface Foo { bar: string }
                export var x: Foo.bar;
            }
            """,
        )
        assert(
            r == listOf(
                "t.ts:3:19 TS2713 Cannot access 'Foo.bar' because 'Foo' is a type, but not a namespace. " +
                    "Did you mean to retrieve the type of the property 'bar' in 'Foo' with 'Foo[\"bar\"]'?",
            ),
        )
    }

    @Test
    fun `TS2702 - a namespace-local interface used as a qualifier whose member is absent`() {
        val r = rows(
            """
            export namespace N {
                interface Foo { a: number }
                export var x: Foo.baz;
            }
            """,
        )
        assert(r == listOf("t.ts:3:19 TS2702 'Foo' only refers to a type, but is being used as a namespace here."))
    }

    @Test
    fun `TS2713 - a type reached through a nested namespace and a class and an alias qualifier`() {
        val r = rows(
            """
            export namespace N {
                export namespace M { export interface Foo { a: number } }
                export var y: M.Foo.a;
                class K { k = 1 }
                type A = { q: string };
                export function f(p: K.k): A.q { return null!; }
            }
            """,
        )
        assert(
            r == listOf(
                "t.ts:3:25 TS2713 Cannot access 'Foo.a' because 'Foo' is a type, but not a namespace. " +
                    "Did you mean to retrieve the type of the property 'a' in 'Foo' with 'Foo[\"a\"]'?",
                "t.ts:6:26 TS2713 Cannot access 'K.k' because 'K' is a type, but not a namespace. " +
                    "Did you mean to retrieve the type of the property 'k' in 'K' with 'K[\"k\"]'?",
                "t.ts:6:32 TS2713 Cannot access 'A.q' because 'A' is a type, but not a namespace. " +
                    "Did you mean to retrieve the type of the property 'q' in 'A' with 'A[\"q\"]'?",
            ),
        )
    }

    @Test
    fun `TS2339 - an enum member name indexed on the enum type`() {
        val r = rows(
            """
            export namespace N {
                export enum E { A, B }
                export var ok: E.A;
                export type I = E["A"];
            }
            """,
        )
        assert(r == listOf("t.ts:4:23 TS2339 Property 'A' does not exist on type 'E'."))
    }

    @Test
    fun `negative control - a namespace qualifier reaching an interface is legal`() {
        val r = rows(
            """
            export namespace N {
                export namespace M { export interface Foo { a: number } }
                export var ok: M.Foo;
            }
            """,
        )
        assert(r.isEmpty())
    }
}
