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
 * (INV.0) (P18.308) The two families moved out of `Checker` in this round, as they live in their
 * collaborators after the extraction: `StaticTypeParamRefChecks` (TS2302, the pass
 * `checkStaticMembersReferenceTypeParams` with its statement / class-member / type / expression
 * walkers and the nested-generic shadow helper) and `PropertyInitOrderChecks` (TS2729 with its
 * TS2728 related row, the pass `checkPropertyUseBeforeInit` with its statement walker, the
 * inherited-name collector and the `this.X` / `ClassName.X` reference collector). Every expected
 * row is tsgo 7.0.2's (cells under `build/bench/p18308-agent/matrix/`, 1-based column); all cells
 * read byte-identical on both arms of the move.
 *
 * Pre-existing divergences shared by both arms, NOT asserted here: `static e: T[keyof U]` misses
 * tsgo's TS2302 on the `U` inside `keyof` (no `TypeOperator` arm in the type walker); a write
 * `(this.b = 2, …)` in a field initializer is silent here where tsgo reports TS2729 at the write
 * target; and a field of a class on a cyclic `extends` chain misses its TS2729, because the cycle
 * makes the class's own members read as inherited. In a MODULE file a base class is looked up in
 * `globals` and so is never found, which makes an inherited field re-declared below its read an
 * ours-only TS2729 (tsgo is silent); the inherited-name pin is therefore a SCRIPT file.
 */
class StaticAndInitOrderChecksCollaboratorTest {

    private val esm = "// @strict: true\n// @target: es2020\n// @module: esnext\n// @moduleResolution: bundler"

    private fun rows(source: String): List<String> =
        diagnose(source, directives = esm, fileName = "t.ts")
            .map { d ->
                "${d.fileName}:${d.line}:${d.character} TS${d.code} ${d.message}" +
                    d.relatedInformation.joinToString("") { " [${it.line}:${it.character} ${it.message}]" }
            }.sorted()

    private val ts2302 = "TS2302 Static members cannot reference class type parameters."

    @Test
    fun `TS2302 - a static property type and initializer type reference the class type parameter`() {
        val r = rows(
            """
            export class C<T> {
                static p: T;
                static q: T[] = [];
            }
            """,
        )
        assert(r == listOf("t.ts:2:15 $ts2302", "t.ts:3:15 $ts2302"))
    }

    @Test
    fun `TS2302 - a static method parameter, return and type-literal return reference the class type parameter`() {
        val r = rows(
            """
            export class C<T> {
                static m(x: T): T { return x; }
                static n(): { a: T } { return null!; }
            }
            """,
        )
        assert(r == listOf("t.ts:2:17 $ts2302", "t.ts:2:21 $ts2302", "t.ts:3:22 $ts2302"))
    }

    @Test
    fun `TS2302 - a static method body reaches a local annotation and an assertion inside an arrow`() {
        val r = rows(
            """
            export class C<T> {
                static m() {
                    let v: T;
                    const f = (y: unknown) => y as T;
                    return [v!, f];
                }
            }
            """,
        )
        assert(r == listOf("t.ts:3:16 $ts2302", "t.ts:4:40 $ts2302"))
    }

    @Test
    fun `TS2302 - a static method's own type parameter shadows the class one and a different name does not`() {
        val r = rows(
            """
            export class C<T> {
                static m<T>(x: T): T { return x; }
                static n<U>(x: U, y: T): U { return x; }
            }
            """,
        )
        assert(r == listOf("t.ts:3:26 $ts2302"))
    }

    @Test
    fun `TS2302 - static accessors of a class nested in a function block and a namespace class are reached`() {
        val r = rows(
            """
            export function f() {
                if (true) {
                    class D<T> {
                        static get g(): T { return null!; }
                        static set s(v: T) {}
                    }
                    return D;
                }
            }
            export namespace N {
                export class E<K> {
                    static k: K | undefined;
                }
            }
            """,
        )
        assert(r == listOf("t.ts:12:19 $ts2302", "t.ts:4:29 $ts2302", "t.ts:5:29 $ts2302"))
    }

    @Test
    fun `TS2302 - function, constructor, tuple, conditional and intersection types are walked`() {
        val r = rows(
            """
            export class C<T, U> {
                static a: (x: T) => U;
                static b: new () => T;
                static c: [T, U?, ...T[]];
                static d: T extends string ? U : never;
                static f: T & U;
            }
            """,
        )
        assert(
            r == listOf(
                "t.ts:2:19 $ts2302", "t.ts:2:25 $ts2302", "t.ts:3:25 $ts2302",
                "t.ts:4:16 $ts2302", "t.ts:4:19 $ts2302", "t.ts:4:26 $ts2302",
                "t.ts:5:15 $ts2302", "t.ts:5:34 $ts2302",
                "t.ts:6:15 $ts2302", "t.ts:6:19 $ts2302",
            ),
        )
    }

    @Test
    fun `TS2302 - a generic arrow, function type and function expression shadow the class type parameter`() {
        val r = rows(
            """
            export class B4<T, S> {
                static create: any = <S>(x: S, y: T) => x;
                static g: <T>(x: T) => T;
                static h = function <S>(a: S, b: T) { return a; };
            }
            """,
        )
        assert(r == listOf("t.ts:2:39 $ts2302", "t.ts:4:38 $ts2302"))
    }

    @Test
    fun `negative control - instance members may reference the class type parameter`() {
        val r = rows(
            """
            export class C<T> {
                p!: T;
                m(x: T): T { return x; }
                static ok: number = 1;
            }
            """,
        )
        assert(r.isEmpty())
    }

    @Test
    fun `TS2729 - a field read before the field below it is initialized carries the declared-here row`() {
        val r = rows(
            """
            export class C {
                a = this.b;
                b = 1;
            }
            """,
        )
        assert(r == listOf("t.ts:2:14 TS2729 Property 'b' is used before its initialization. [3:5 'b' is declared here.]"))
    }

    @Test
    fun `TS2729 - a static field through the class name and an instance field through this`() {
        val r = rows(
            """
            export class C {
                static a = C.b;
                static b = 1;
                c = this.d + 1;
                d = 2;
            }
            """,
        )
        assert(
            r == listOf(
                "t.ts:2:18 TS2729 Property 'b' is used before its initialization. [3:12 'b' is declared here.]",
                "t.ts:4:14 TS2729 Property 'd' is used before its initialization. [5:5 'd' is declared here.]",
            ),
        )
    }

    @Test
    fun `TS2729 - a field above with no initializer is reported unless it is optional or definite`() {
        val r = rows(
            """
            export abstract class C {
                abstract x: string;
                y = this.x;
                o?: number;
                z = this.o;
                w!: number;
                v = this.w;
            }
            """,
        ).filter { "TS2729" in it }
        assert(r == listOf("t.ts:3:14 TS2729 Property 'x' is used before its initialization. [2:14 'x' is declared here.]"))
    }

    @Test
    fun `TS2729 - inherited members are exempt and an own field below is not`() {
        val r = rows(
            """
            class Base {
                q = 1;
                m() { return 1; }
            }
            export class D extends Base {
                a = this.q;
                b = this.m();
                c = this.e;
                e = 0;
            }
            """,
        )
        assert(r == listOf("t.ts:8:14 TS2729 Property 'e' is used before its initialization. [9:5 'e' is declared here.]"))
    }

    @Test
    fun `TS2729 - arrow and function bodies are deferred while a computed object key is eager`() {
        val r = rows(
            """
            export class C {
                a = () => this.b;
                f = function (this: C) { return this.b; };
                g = { [this.b]: 1 };
                b = "k";
            }
            """,
        )
        assert(r == listOf("t.ts:4:17 TS2729 Property 'b' is used before its initialization. [5:5 'b' is declared here.]"))
    }

    @Test
    fun `TS2729 - a class in a function loop and a namespace class template span are reached`() {
        val r = rows(
            """
            export function f() {
                for (;;) {
                    class C {
                        a = this.b;
                        b = 1;
                    }
                    return C;
                }
            }
            export namespace N {
                export class D {
                    a = `${'$'}{this.b}`;
                    b = 1;
                }
            }
            """,
        )
        assert(
            r == listOf(
                "t.ts:12:21 TS2729 Property 'b' is used before its initialization. [13:9 'b' is declared here.]",
                "t.ts:4:22 TS2729 Property 'b' is used before its initialization. [5:13 'b' is declared here.]",
            ),
        )
    }

    @Test
    fun `TS2729 - negative control - a script class re-declaring an inherited field below its read is exempt`() {
        val r = rows(
            """
            class Base10 {
                q = 1;
            }
            class D10 extends Base10 {
                a = this.q;
                q = 2;
            }
            """,
        )
        assert(r.isEmpty())
    }
}
