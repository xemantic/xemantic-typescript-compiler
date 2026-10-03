/*
 * Copyright 2025-2026 Kazimierz Pogoda / Xemantic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (P18.272) / (CHK.207) F5 — four parser gaps found by the (P18.265) real-library census
 * (immer, hono, superstruct, type-fest), each a false row on LEGAL code that tsgo 7.0.2 parses
 * cleanly. Every expectation below is tsgo's own row list for the same source (measured with
 * `tools/tsgo-7.0.2/lib/tsc`); an empty list is tsgo's silence.
 *
 * 1. `Parser.parseTypeArgumentsOfTypeReference` — a `<` that starts a NEW LINE never opens a
 *    type reference's type arguments (tsgo `parseTypeArgumentsOfTypeReference`, and
 *    `parseTypeQuery`'s ASI guard).
 * 2. A class property named `get` / `set` followed by `!` or `?` is a property, not an accessor.
 * 3. `Parser.leadingTypeIsSpecifierModifier` — an import/export specifier NAMED `type`.
 * 4. An index signature consumes its own `;`, so a NUMERIC member after it is not a TS1005.
 */
class ParserLibraryShapesTest {

    private fun rows(source: String, directives: String = "// @strict: true"): List<String> =
        diagnose(source, directives).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    // ---- 1. type arguments across a line break -------------------------------------------

    @Test
    fun `a call signature on the next line is not type arguments of the previous return type`() {
        val actual = rows(
            """
            type AnyFunc = (...a: any[]) => any;
            interface IProduce {
              <C>(r: C): C
              <R extends AnyFunc>(r: R): R
            }
            declare const p: IProduce;
            export const q: IProduce = p;
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `a method return type followed by a call signature on the next line`() {
        val actual = rows(
            """
            interface I {
              m(): Array
              <T>(x: T): T
            }
            declare const i: I;
            const z: string = i(1);
            """
        )
        assert(
            actual == listOf(
                "2:8 TS2314 Generic type 'Array<T>' requires 1 type argument(s).",
                "6:7 TS2322 Type 'number' is not assignable to type 'string'.",
            )
        )
    }

    @Test
    fun `control - type arguments opened on the same line may continue on the next`() {
        val actual = rows(
            """
            interface Box<T> { v: T }
            type A = Box<
              string>;
            const a: A = { v: 1 };
            """
        )
        assert(actual == listOf("4:16 TS2322 Type 'number' is not assignable to type 'string'."))
    }

    @Test
    fun `a type query does not take type arguments from the next line`() {
        val actual = rows(
            """
            declare function f<T>(x: T): T;
            type Q = typeof f
            <string>;
            """
        )
        assert(actual == listOf("3:9 TS1109 Expression expected."))
    }

    @Test
    fun `control - a heritage clause still takes type arguments from the next line`() {
        val actual = rows(
            """
            class B<T> { v!: T }
            class C extends B
            <string> {}
            const s: number = new C().v;
            """
        )
        assert(actual == listOf("4:7 TS2322 Type 'string' is not assignable to type 'number'."))
    }

    // ---- 2. a property named get or set ---------------------------------------------------

    @Test
    fun `a definite get property is a property and not an accessor`() {
        val actual = rows(
            """
            type H = (path: string) => void;
            class App {
              get!: H
              post!: H
              constructor() { this.get = () => {}; this.post = () => {} }
            }
            new App().get("x");
            new App().get(1);
            """
        )
        assert(actual == listOf("8:15 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `a definite set property is a property and not an accessor`() {
        val actual = rows(
            """
            type H = (path: string) => void;
            class App { set!: H; constructor() { this.set = () => {} } }
            new App().set(1);
            """
        )
        assert(actual == listOf("3:15 TS2345 Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `an optional get property and an optional get method`() {
        val actual = rows(
            """
            class A { get?: number }
            class B { get?(): number }
            const s: string = new A().get;
            const t: string = new B().get!();
            """
        )
        assert(
            actual == listOf(
                "3:7 TS2322 Type 'number | undefined' is not assignable to type 'string'.",
                "4:7 TS2322 Type 'number' is not assignable to type 'string'.",
            )
        )
    }

    @Test
    fun `control - a real accessor pair and a get accessor on the next line`() {
        val actual = rows(
            """
            class A { get x(): number { return 1 } set x(v: number) {} }
            class B { get
            y(): number { return 1 } }
            const s: string = new A().x;
            const t: string = new B().y;
            """
        )
        assert(
            actual == listOf(
                "4:7 TS2322 Type 'number' is not assignable to type 'string'.",
                "5:7 TS2322 Type 'number' is not assignable to type 'string'.",
            )
        )
    }

    // ---- 3. a specifier named type --------------------------------------------------------

    private val types = """
        // @Filename: /proj/src/types.ts
        export function object() {}
        export function type() {}
        export function as() {}
        export type T = number;
    """.trimIndent()

    private fun importRows(use: String): List<String> =
        rows(types + "\n// @Filename: /proj/src/t.ts\n" + use.trimIndent(), "// @strict: true\n// @module: esnext")

    @Test
    fun `a named import called type after another specifier`() {
        assert(importRows("import { object, type } from './types'\nobject(); type();").isEmpty())
    }

    @Test
    fun `a named import called type alone first and with a trailing comma`() {
        assert(importRows("import { type } from './types'\ntype();").isEmpty())
        assert(importRows("import { type, object } from './types'\nobject(); type();").isEmpty())
        assert(importRows("import { object, type, } from './types'\nobject(); type();").isEmpty())
    }

    @Test
    fun `type as renames the export named type`() {
        assert(importRows("import { type as t } from './types'\nt();").isEmpty())
        assert(importRows("import { type as as } from './types'\nas();").isEmpty())
    }

    @Test
    fun `control - type X and type as as X are type-only imports`() {
        assert(importRows("import { type T } from './types'\nconst v: T = 1;").isEmpty())
        val actual = importRows("import { type as as x } from './types'\nx();")
        assert(actual.size == 1)
        assert(actual.single().contains("TS1361 'x' cannot be used as a value because it was imported using 'import type'."))
    }

    @Test
    fun `an export specifier called type`() {
        val actual = rows(
            """
            function type() {}
            export { type, type as other };
            """
        )
        assert(actual.isEmpty())
    }

    // ---- 4. a numeric member after an index signature ------------------------------------

    @Test
    fun `a numeric member after an index signature in a type literal and an interface`() {
        val actual = rows(
            """
            type A = {[x: string]: string; 0: string};
            interface I {[x: string]: string; 0: string}
            type B = {[x: string]: string; 1: string, 2: string};
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `control - an index signature followed by a string or comma separated member`() {
        val actual = rows(
            """
            type A = {[x: string]: string, 0: string};
            type B = {[x: string]: string; "0": string};
            type C = {[x: string]: string
            0: string};
            """
        )
        assert(actual.isEmpty())
    }

    @Test
    fun `an index signature nested in another member's type does not swallow that member's separator`() {
        // The @types/node zlib.d.ts shape: the inner index signature consumes ITS `;`, and the
        // outer `params` member must still consume its own `;` (a leaked flag made the `;` parse
        // as a member named `;` — a TS7008 on library code).
        val actual = rows(
            """
            interface O {
              params?:
                | {
                    [key: number]: boolean | number;
                  }
                | undefined;
              next: string;
            }
            const o: O = { next: 1 };
            type A = { p: { [k: string]: number; }; q: string };
            const a: A = { p: {}, q: 2 };
            """
        )
        assert(
            actual == listOf(
                "11:23 TS2322 Type 'number' is not assignable to type 'string'.",
                "9:16 TS2322 Type 'number' is not assignable to type 'string'.",
            )
        )
    }

    @Test
    fun `control - a missing separator after an index signature is still reported`() {
        val actual = rows("type A = {[x: number]: string 0: string};")
        assert(actual == listOf("1:31 TS1005 ';' expected."))
    }
}
