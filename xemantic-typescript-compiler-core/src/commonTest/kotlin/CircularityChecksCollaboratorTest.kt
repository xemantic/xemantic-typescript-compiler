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
 * (INV.0) (P18.303) The circularity family as it lives in `CircularityChecks` after the extraction:
 * one pin per entry point (the nine passes) plus the three helpers re-pointed from `Checker` —
 * `classHasCircularBase` (reached through `collectFuncDecls`' implicit-constructor arity),
 * `aliasStatementSpanEnd` and `emitTS2303At` (reached through `checkUnresolvedInImportEquals`'
 * declaration-emit TS2303); the last pin asserts the TS2303 squiggle lengths, because
 * `aliasStatementSpanEnd` changes only a span. Every expected row is tsgo 7.0.2's (cells under
 * `build/bench/p18303-agent/matrix/`, 1-based column); all cells read byte-identical on both arms
 * of the move.
 *
 * Pre-existing divergences shared by both arms, NOT asserted here: a two-alias cycle
 * `type A = B; type B = A` is silent here where tsgo reports TS2456 at both; `type X =
 * NumArray<X extends {} ? number : number>` is TS4109 here where tsgo reports TS2456; `type F =
 * () => F[]` is an ours-only TS2577; and `class S extends S<number>` carries an ours-only TS2315
 * beside the rows tsgo reports (filtered out of the one pin that uses the shape).
 */
class CircularityChecksCollaboratorTest {

    private val esm = "// @strict: true\n// @target: es2020\n// @module: esnext\n// @moduleResolution: bundler"

    private fun rows(source: String, directives: String = esm, fileName: String = "t.ts"): List<String> =
        diagnose(source, directives = directives, fileName = fileName)
            .map { "${it.fileName}:${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `a three-alias import-equals cycle is TS2303 at every alias`() {
        val r = rows(
            """
            import A = B;
            import B = C;
            import C = A;
            """,
        )
        assert(
            r == listOf(
                "t.ts:1:1 TS2303 Circular definition of import alias 'A'.",
                "t.ts:2:1 TS2303 Circular definition of import alias 'B'.",
                "t.ts:3:1 TS2303 Circular definition of import alias 'C'.",
            ),
        )
    }

    @Test
    fun `two ambient modules re-exporting each other through export equals are TS2303 at both halves`() {
        val r = rows(
            """
            declare module "a" {
                import b = require("b");
                export = b;
            }
            declare module "b" {
                import a = require("a");
                export = a;
            }
            """,
            fileName = "m.d.ts",
        )
        assert(
            r == listOf(
                "m.d.ts:2:5 TS2303 Circular definition of import alias 'b'.",
                "m.d.ts:3:5 TS2303 Circular definition of import alias 'b'.",
                "m.d.ts:6:5 TS2303 Circular definition of import alias 'a'.",
                "m.d.ts:7:5 TS2303 Circular definition of import alias 'a'.",
            ),
        )
    }

    @Test
    fun `export equals of a global-only namespace beside export as namespace is TS2303 at both`() {
        val r = rows(
            """
            declare global { namespace N { } }
            export = N;
            export as namespace N;
            """,
            fileName = "a.d.ts",
        )
        assert(
            r == listOf(
                "a.d.ts:2:1 TS2303 Circular definition of import alias 'N'.",
                "a.d.ts:3:1 TS2303 Circular definition of import alias 'N'.",
            ),
        )
    }

    @Test
    fun `two classes extending each other are TS2506 at both`() {
        val r = rows(
            """
            class A extends B {}
            class B extends A {}
            """,
        )
        assert(
            r == listOf(
                "t.ts:1:17 TS2449 Class 'B' used before its declaration.",
                "t.ts:1:7 TS2506 'A' is referenced directly or indirectly in its own base expression.",
                "t.ts:2:7 TS2506 'B' is referenced directly or indirectly in its own base expression.",
            ),
        )
    }

    @Test
    fun `a class cycle inside an ambient namespace is TS2506 at both and never TS2449`() {
        val r = rows(
            """
            declare namespace NS {
                class A extends B {}
                class B extends A {}
            }
            """,
        )
        assert(
            r == listOf(
                "t.ts:2:11 TS2506 'A' is referenced directly or indirectly in its own base expression.",
                "t.ts:3:11 TS2506 'B' is referenced directly or indirectly in its own base expression.",
            ),
        )
    }

    @Test
    fun `two interfaces extending each other are TS2310 at both`() {
        val r = rows(
            """
            interface A extends B {}
            interface B extends A {}
            """,
        )
        assert(
            r == listOf(
                "t.ts:1:11 TS2310 Type 'A' recursively references itself as a base type.",
                "t.ts:2:11 TS2310 Type 'B' recursively references itself as a base type.",
            ),
        )
    }

    @Test
    fun `an indexed-access alias named in its interface's base arguments is TS2456 and TS2310`() {
        val r = rows(
            """
            interface Base<T> { y: T }
            type A = I["x"];
            interface I extends Base<A> { x: number }
            """,
        )
        assert(
            r == listOf(
                "t.ts:2:6 TS2456 Type alias 'A' circularly references itself.",
                "t.ts:3:11 TS2310 Type 'I' recursively references itself as a base type.",
            ),
        )
    }

    @Test
    fun `an interface extending a homomorphic mapped alias of itself is TS2313 and TS2310`() {
        val r = rows(
            """
            type Alias<T> = { [K in keyof T]: T[K] };
            interface S extends Alias<S> {}
            """,
        )
        assert(
            r == listOf(
                "t.ts:1:25 TS2313 Type parameter 'K' has a circular constraint.",
                "t.ts:2:11 TS2310 Type 'S' recursively references itself as a base type.",
            ),
        )
    }

    @Test
    fun `a directly self-referencing alias is TS2456`() {
        val r = rows(
            """
            type X = X;
            type U = string | U;
            """,
        )
        assert(
            r == listOf(
                "t.ts:1:6 TS2456 Type alias 'X' circularly references itself.",
                "t.ts:2:6 TS2456 Type alias 'U' circularly references itself.",
            ),
        )
    }

    @Test
    fun `a class whose base default type argument indexes into itself is TS2310`() {
        val r = rows(
            """
            class BaseType<T> {
                bar!: T;
            }
            class NextType<C extends { someProp: any }, T = C["someProp"]> extends BaseType<T> {
                baz!: string;
            }
            class Foo extends NextType<Foo> {
                someProp!: { test: true };
            }
            """,
        )
        assert(r == listOf("t.ts:7:7 TS2310 Type 'Foo' recursively references itself as a base type."))
    }

    @Test
    fun `a class with a circular base has a zero-argument implicit constructor`() {
        val r = rows(
            """
            class S extends S<number> {}
            new S(1);
            """,
        ).filter { "TS2315" !in it }
        assert(
            r == listOf(
                "t.ts:1:7 TS2506 'S' is referenced directly or indirectly in its own base expression.",
                "t.ts:2:7 TS2554 Expected 0 arguments, but got 1.",
            ),
        )
    }

    @Test
    fun `an exported import-equals of an unknown name under declaration emit is TS2303 at the alias and its re-export`() {
        val r = rows(
            """
            import Foo = SomeNonExistentName;
            export { Foo };
            """,
            directives = "$esm\n// @declaration: true",
        )
        assert(
            r == listOf(
                "t.ts:1:1 TS2303 Circular definition of import alias 'Foo'.",
                "t.ts:1:14 TS2304 Cannot find name 'SomeNonExistentName'.",
                "t.ts:1:14 TS2503 Cannot find namespace 'SomeNonExistentName'.",
                "t.ts:2:10 TS2303 Circular definition of import alias 'Foo'.",
            ),
        )
    }

    private fun spans(source: String, directives: String = esm, fileName: String = "t.ts"): List<String> =
        diagnose(source, directives = directives, fileName = fileName).filter { it.code == 2303 }
            .map { "${it.fileName}:${it.line}:${it.character} length ${it.length}" }.sorted()

    @Test
    fun `the TS2303 squiggles of an export equals cycle and a declaration-emit re-export are tsgo's`() {
        val umd = spans(
            """
            declare global { namespace N { } }
            export = N;
            export as namespace N;
            """,
            fileName = "a.d.ts",
        )
        val reExport = spans(
            """
            import Foo = SomeNonExistentName;
            export { Foo };
            """,
            directives = "$esm\n// @declaration: true",
        )
        assert(umd == listOf("a.d.ts:2:1 length 11", "a.d.ts:3:1 length 22"))
        assert(reExport == listOf("t.ts:1:1 length 33", "t.ts:2:10 length 3"))
    }
}
