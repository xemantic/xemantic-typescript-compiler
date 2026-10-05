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
 * (CHK.232) Cross-file conflicts decided by MEANING, as tsgo's `mergeSymbol` (checker.go:14072)
 * decides them: a type alias occupies only the TYPE meaning, a namespace only the namespace (and,
 * when instantiated, value) meaning, and script files merge into `globals` in program order — so
 * the same three declarations answer TS2649 or TS2300 x3 depending on which file merges first.
 * Every expected row is tsgo 7.0.2's (cells `build/bench/p18299-agent/matrix/m01`-`m32`, 1-based
 * columns).
 */
class CrossFileMeaningMergeTest {

    private val esm = "// @strict: true\n// @target: es2020\n// @module: esnext\n// @moduleResolution: bundler"

    private fun rows(source: String): List<String> =
        diagnose(source, directives = esm)
            .map { "${it.fileName}:${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    private fun related(source: String): List<String> =
        diagnose(source, directives = esm)
            .flatMap { d -> d.relatedInformation.map { "${d.fileName}:${d.line}:${d.character} -> ${it.fileName}:${it.line}:${it.character} TS${it.code}" } }
            .sorted()

    // --- script type alias vs namespace ---

    @Test
    fun `a namespace merged before a script type alias is silent`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            namespace N {
                export const x = 1;
            }
            // @Filename: /p/b.ts
            type N = number;
            """,
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a script type alias beside a non-instantiated namespace is silent in both orders`() {
        val typeFirst = rows(
            """
            // @Filename: /p/a.ts
            type N = number;
            // @Filename: /p/b.ts
            namespace N { export type T = 1; }
            """,
        )
        val nsFirst = rows(
            """
            // @Filename: /p/a.ts
            namespace N { export type T = 1; }
            // @Filename: /p/b.ts
            type N = number;
            """,
        )
        assert(typeFirst.isEmpty())
        assert(nsFirst.isEmpty())
    }

    @Test
    fun `a type alias merged after an interface and namespace is TS2649 at the alias`() {
        val r = rows(
            """
            // @Filename: /p/initial.ts
            interface A { }
            namespace A {}
            // @Filename: /p/final.ts
            type A = {}
            """,
        )
        assert(r == listOf("/p/final.ts:1:6 TS2649 Cannot augment module 'A' with value exports because it resolves to a non-module entity."))
    }

    @Test
    fun `a type alias merged before an interface and namespace is TS2300 at all three declarations`() {
        val src = """
            // @Filename: /p/final.ts
            type A = {}
            // @Filename: /p/initial.ts
            interface A { }
            namespace A {}
            """
        assert(
            rows(src) == listOf(
                "/p/final.ts:1:6 TS2300 Duplicate identifier 'A'.",
                "/p/initial.ts:1:11 TS2300 Duplicate identifier 'A'.",
                "/p/initial.ts:2:11 TS2300 Duplicate identifier 'A'.",
            ),
        )
        assert(
            related(src) == listOf(
                "/p/final.ts:1:6 -> /p/initial.ts:1:11 TS6203",
                "/p/final.ts:1:6 -> /p/initial.ts:2:11 TS6204",
                "/p/initial.ts:1:11 -> /p/final.ts:1:6 TS6203",
                "/p/initial.ts:2:11 -> /p/final.ts:1:6 TS6203",
            ),
        )
    }

    @Test
    fun `a second type alias after an interface and namespace is TS2649 again`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            interface A { }
            namespace A {}
            // @Filename: /p/b.ts
            type A = {}
            // @Filename: /p/c.ts
            type A = {}
            """,
        )
        assert(
            r == listOf(
                "/p/b.ts:1:6 TS2649 Cannot augment module 'A' with value exports because it resolves to a non-module entity.",
                "/p/c.ts:1:6 TS2649 Cannot augment module 'A' with value exports because it resolves to a non-module entity.",
            ),
        )
    }

    @Test
    fun `a class merged after a namespace and a type alias is TS2649 at the class`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            namespace A { export type T = 1 }
            // @Filename: /p/b.ts
            type A = {}
            // @Filename: /p/c.ts
            class A {}
            """,
        )
        assert(r == listOf("/p/c.ts:1:7 TS2649 Cannot augment module 'A' with value exports because it resolves to a non-module entity."))
    }

    // --- script type alias vs interface (new true positives) ---

    @Test
    fun `a script interface and a script type alias of one name are TS2300 at both`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            interface A { x: 1 }
            // @Filename: /p/b.ts
            type A = {}
            """,
        )
        assert(
            r == listOf(
                "/p/a.ts:1:11 TS2300 Duplicate identifier 'A'.",
                "/p/b.ts:1:6 TS2300 Duplicate identifier 'A'.",
            ),
        )
    }

    @Test
    fun `two type aliases and an interface report the first alias against each later one`() {
        val src = """
            // @Filename: /p/a.ts
            type A = {}
            // @Filename: /p/b.ts
            type A = 1
            // @Filename: /p/c.ts
            interface A {}
            """
        assert(
            rows(src) == listOf(
                "/p/a.ts:1:6 TS2300 Duplicate identifier 'A'.",
                "/p/b.ts:1:6 TS2300 Duplicate identifier 'A'.",
                "/p/c.ts:1:11 TS2300 Duplicate identifier 'A'.",
            ),
        )
        assert(
            related(src) == listOf(
                "/p/a.ts:1:6 -> /p/b.ts:1:6 TS6203",
                "/p/a.ts:1:6 -> /p/c.ts:1:11 TS6203",
                "/p/b.ts:1:6 -> /p/a.ts:1:6 TS6203",
                "/p/c.ts:1:11 -> /p/a.ts:1:6 TS6203",
            ),
        )
    }

    @Test
    fun `a var merged with a type alias is reported with it when an interface collides`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            type A = {}
            // @Filename: /p/b.ts
            var A = 1
            // @Filename: /p/c.ts
            interface A {}
            """,
        )
        assert(
            r == listOf(
                "/p/a.ts:1:6 TS2300 Duplicate identifier 'A'.",
                "/p/b.ts:1:5 TS2300 Duplicate identifier 'A'.",
                "/p/c.ts:1:11 TS2300 Duplicate identifier 'A'.",
            ),
        )
    }

    @Test
    fun `negative control - a script type alias and a script function of one name are silent`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            type A = {}
            // @Filename: /p/b.ts
            function A() {}
            """,
        )
        assert(r.isEmpty())
    }

    // --- augmentation vs re-export ---

    private fun augReexport(augDecl: String, other: String, reexport: String = "export { N } from \"./other\";"): List<String> =
        rows(
            """
            // @Filename: /p/aug.ts
            export {};
            declare module "./index" {
                $augDecl
            }
            // @Filename: /p/index.ts
            $reexport
            // @Filename: /p/other.ts
            $other
            """,
        )

    @Test
    fun `a type alias augmentation against a re-exported const is silent`() {
        assert(augReexport("type N = number;", "export const N = 1;").isEmpty())
    }

    @Test
    fun `a type alias augmentation against a re-exported function or namespace is silent`() {
        assert(augReexport("type N = number;", "export function N() {}").isEmpty())
        assert(augReexport("type N = number;", "export namespace N { export const x = 1 }").isEmpty())
    }

    @Test
    fun `a type alias augmentation against a renamed re-exported const is silent`() {
        assert(augReexport("type N = number;", "export const M = 1;", "export { M as N } from \"./other\";").isEmpty())
    }

    @Test
    fun `a const augmentation against a re-exported type alias is silent`() {
        assert(augReexport("const N: number;", "export type N = string;").isEmpty())
    }

    @Test
    fun `a type alias augmentation against a re-exported interface is TS2300 at both`() {
        assert(
            augReexport("type N = number;", "export interface N { a: 1 }") == listOf(
                "/p/aug.ts:3:10 TS2300 Duplicate identifier 'N'.",
                "/p/index.ts:1:10 TS2300 Duplicate identifier 'N'.",
            ),
        )
    }

    @Test
    fun `a type alias augmentation against a renamed re-exported type is TS2300 at both`() {
        assert(
            augReexport("type N = number;", "export type M = 1;", "export { M as N } from \"./other\";") == listOf(
                "/p/aug.ts:3:10 TS2300 Duplicate identifier 'N'.",
                "/p/index.ts:1:15 TS2300 Duplicate identifier 'N'.",
            ),
        )
    }

    @Test
    fun `a const augmentation against a re-exported function is TS2451 at both`() {
        assert(
            augReexport("const N: number;", "export function N() {}") == listOf(
                "/p/aug.ts:3:11 TS2451 Cannot redeclare block-scoped variable 'N'.",
                "/p/index.ts:1:10 TS2451 Cannot redeclare block-scoped variable 'N'.",
            ),
        )
    }

    @Test
    fun `a type alias augmentation against an unresolved re-export reports only the missing export`() {
        assert(augReexport("type N = number;", "export const Q = 1;") == listOf("/p/index.ts:1:10 TS2305 Module '\"./other\"' has no exported member 'N'."))
    }

    // --- enum augmentation through a nested project path ---

    @Test
    fun `an enum augmentation of a class in a nested directory is TS2567 at both`() {
        val r = rows(
            """
            // @Filename: /p/src/m.ts
            export class E {}
            // @Filename: /p/src/aug.ts
            export {};
            declare module "./m" {
                export enum E { A }
            }
            """,
        )
        assert(
            r == listOf(
                "/p/src/aug.ts:3:17 TS2567 Enum declarations can only merge with namespace or other enum declarations.",
                "/p/src/m.ts:1:14 TS2567 Enum declarations can only merge with namespace or other enum declarations.",
            ),
        )
    }

    @Test
    fun `an enum augmentation of a class reached through a nested export star is TS2567 at both`() {
        val r = rows(
            """
            // @Filename: /p/src/file.ts
            export class Foo { member!: string; }
            // @Filename: /p/src/reexport.ts
            export * from "./file";
            // @Filename: /p/src/augment.ts
            export {};
            declare module "./reexport" {
                export enum Foo { A }
            }
            """,
        )
        assert(
            r == listOf(
                "/p/src/augment.ts:3:17 TS2567 Enum declarations can only merge with namespace or other enum declarations.",
                "/p/src/file.ts:1:14 TS2567 Enum declarations can only merge with namespace or other enum declarations.",
            ),
        )
    }

    @Test
    fun `negative control - an enum augmentation of an enum in a nested directory is silent`() {
        val r = rows(
            """
            // @Filename: /p/src/m.ts
            export enum E { A }
            // @Filename: /p/src/aug.ts
            export {};
            declare module "./m" {
                export enum E { B = 1 }
            }
            """,
        )
        assert(r.isEmpty())
    }
}
