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
 * (INV.0) (P18.298) The cross-file duplicate / conflict family as it lives in
 * `CrossFileConflictChecks` after the extraction: one pin per entry point (the twelve passes) plus
 * the two helpers re-pointed from `Checker` — `duplicateCallRelatedInfos` (reached through
 * `checkClassShadowsLibType`) and `resolveAugmentationTargetFile` (reached for a `"."` augmentation
 * specifier). Every expected row is tsgo 7.0.2's (cells under `build/bench/p18298-agent/matrix/`,
 * 1-based column); all cells read byte-identical on both arms of
 * the move.
 *
 * The three pre-existing divergences this class used to list — a type-alias augmentation against a
 * re-exported `const`, a script `type N` beside a script `namespace N`, and an `enum` augmentation
 * reached through a nested project path — are closed by (CHK.232); `CrossFileMeaningMergeTest`
 * carries their tsgo rows.
 */
class CrossFileConflictChecksCollaboratorTest {

    private val esm = "// @strict: true\n// @target: es2020\n// @module: esnext\n// @moduleResolution: bundler"

    private fun rows(source: String, directives: String = esm): List<String> =
        diagnose(source, directives = directives)
            .map { "${it.fileName}:${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    private fun related(source: String, directives: String = esm): List<String> =
        diagnose(source, directives = directives)
            .flatMap { d -> d.relatedInformation.map { "${d.fileName}:${d.line} -> ${it.fileName}:${it.line}:${it.character} TS${it.code} ${it.message}" } }
            .sorted()

    @Test
    fun `a script class and a script const of one name are TS2451 at both`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            class Foo {}
            // @Filename: /p/b.ts
            const Foo = 1;
            """,
        )
        assert(
            r == listOf(
                "/p/a.ts:1:7 TS2451 Cannot redeclare block-scoped variable 'Foo'.",
                "/p/b.ts:1:7 TS2451 Cannot redeclare block-scoped variable 'Foo'.",
            ),
        )
    }

    @Test
    fun `a script let and a script const of one name are TS2451 at both`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            let x = 1;
            // @Filename: /p/b.ts
            const x = 2;
            """,
        )
        assert(
            r == listOf(
                "/p/a.ts:1:5 TS2451 Cannot redeclare block-scoped variable 'x'.",
                "/p/b.ts:1:7 TS2451 Cannot redeclare block-scoped variable 'x'.",
            ),
        )
    }

    @Test
    fun `a script enum and a script class of one name are TS2567 at both`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            enum E { A }
            // @Filename: /p/b.ts
            class E {}
            """,
        )
        assert(
            r == listOf(
                "/p/a.ts:1:6 TS2567 Enum declarations can only merge with namespace or other enum declarations.",
                "/p/b.ts:1:7 TS2567 Enum declarations can only merge with namespace or other enum declarations.",
            ),
        )
    }

    @Test
    fun `a merged interface member that is a property in one file and a method in another is TS2300 at both`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            interface I { m: number; }
            // @Filename: /p/b.ts
            interface I { m(): void; }
            """,
        )
        assert(
            r == listOf(
                "/p/a.ts:1:15 TS2300 Duplicate identifier 'm'.",
                "/p/b.ts:1:15 TS2300 Duplicate identifier 'm'.",
            ),
        )
    }

    @Test
    fun `two script classes of one name are TS2300 at both`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            class C {}
            // @Filename: /p/b.ts
            class C {}
            """,
        )
        assert(
            r == listOf(
                "/p/a.ts:1:7 TS2300 Duplicate identifier 'C'.",
                "/p/b.ts:1:7 TS2300 Duplicate identifier 'C'.",
            ),
        )
    }

    @Test
    fun `a UMD global and a declare-global const of one name are TS2451 at both`() {
        val r = rows(
            """
            // @Filename: /p/a.d.ts
            export as namespace Lib;
            export const value: number;
            // @Filename: /p/b.d.ts
            declare global {
                const Lib: string;
            }
            export {};
            """,
        )
        assert(
            r == listOf(
                "/p/a.d.ts:1:21 TS2451 Cannot redeclare block-scoped variable 'Lib'.",
                "/p/b.d.ts:2:11 TS2451 Cannot redeclare block-scoped variable 'Lib'.",
            ),
        )
    }

    @Test
    fun `an augmentation re-declaring a module's exported const is TS2451 at both`() {
        val r = rows(
            """
            // @Filename: /p/m.ts
            export const a = 1;
            // @Filename: /p/aug.ts
            import "./m";
            declare module "./m" {
                export const a: number;
            }
            """,
        ).filter { " TS2451 " in it }
        assert(
            r == listOf(
                "/p/aug.ts:3:18 TS2451 Cannot redeclare block-scoped variable 'a'.",
                "/p/m.ts:1:14 TS2451 Cannot redeclare block-scoped variable 'a'.",
            ),
        )
    }

    @Test
    fun `an augmentation const colliding with a re-exported name is TS2451 at both`() {
        val r = rows(
            """
            // @Filename: /p/index.ts
            export { N } from "./other";
            // @Filename: /p/other.ts
            export const N = 1;
            // @Filename: /p/aug.ts
            export {};
            declare module "./index" {
                const N: number;
            }
            """,
        )
        assert(
            r == listOf(
                "/p/aug.ts:3:11 TS2451 Cannot redeclare block-scoped variable 'N'.",
                "/p/index.ts:1:10 TS2451 Cannot redeclare block-scoped variable 'N'.",
            ),
        )
    }

    @Test
    fun `a dot augmentation specifier resolves to the sibling index through the re-pointed resolver`() {
        val r = rows(
            """
            // @Filename: /p/index.ts
            export { N } from "./other";
            // @Filename: /p/other.ts
            export const N = 1;
            // @Filename: /p/aug.ts
            export {};
            declare module "." {
                const N: number;
            }
            """,
        )
        assert(
            r == listOf(
                "/p/aug.ts:3:11 TS2451 Cannot redeclare block-scoped variable 'N'.",
                "/p/index.ts:1:10 TS2451 Cannot redeclare block-scoped variable 'N'.",
            ),
        )
    }

    @Test
    fun `a CommonJS string export widened by an augmentation still reports the string method access`() {
        val r = rows(
            """
            // @Filename: /p/test.js
            module.exports = {
              a: "ok"
            };
            // @Filename: /p/index.ts
            import { a } from "./test";

            declare module "./test" {
              export const a: number;
            }

            a.toFixed();
            """,
            directives = "$esm\n// @allowJs: true\n// @checkJs: true\n// @noEmit: true",
        ).filter { " TS2671 " in it || " TS2551 " in it }
        assert(
            r == listOf(
                "/p/index.ts:3:16 TS2671 Cannot augment module './test' because it resolves to a non-module entity.",
                "/p/index.ts:7:3 TS2551 Property 'toFixed' does not exist on type 'string'. Did you mean 'fixed'?",
            ),
        )
    }

    @Test
    fun `an enum augmentation of a class reached through export star is TS2567 at both`() {
        val r = rows(
            """
            // @Filename: file.ts
            export class Foo {
                member!: string;
            }
            // @Filename: reexport.ts
            export * from "./file";
            // @Filename: augment.ts
            import * as ns from "./reexport";

            declare module "./reexport" {
                export enum Foo {
                    A, B, C
                }
            }

            declare const f: ns.Foo;
            """,
            directives = "// @strict: true\n// @target: es2020\n// @module: commonjs",
        )
        assert(
            r == listOf(
                "augment.ts:4:17 TS2567 Enum declarations can only merge with namespace or other enum declarations.",
                "file.ts:1:14 TS2567 Enum declarations can only merge with namespace or other enum declarations.",
            ),
        )
    }

    @Test
    fun `a type alias and an interface in two files' declare-global namespace are TS2300 at both`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            export {};
            declare global {
                namespace NS {
                    type T = number;
                }
            }
            // @Filename: /p/b.ts
            export {};
            declare global {
                namespace NS {
                    interface T { x: number; }
                }
            }
            """,
        )
        assert(
            r == listOf(
                "/p/a.ts:4:14 TS2300 Duplicate identifier 'T'.",
                "/p/b.ts:4:19 TS2300 Duplicate identifier 'T'.",
            ),
        )
    }

    /**
     * (CHK.232) tsgo 7.0.2 is SILENT here: a type alias occupies only the type meaning, an
     * instantiated namespace only the value / namespace one, so they merge. This pin used to assert
     * an ours-only TS2649 (`residue -`); re-pointed to tsgo's answer.
     */
    @Test
    fun `a script type alias beside a script namespace of one name is silent`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            type N = number;
            // @Filename: /p/b.ts
            namespace N {
                export const x = 1;
            }
            """,
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a script class shadowing a lib type carries its related rows through the re-pointed builder`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            class Symbol {}
            """,
        )
        assert(r == listOf("/p/a.ts:1:7 TS2300 Duplicate identifier 'Symbol'."))
        val rel = related(
            """
            // @Filename: /p/a.ts
            class Symbol {}
            """,
        )
        assert(rel.isNotEmpty())
        assert(rel.all { " TS6203 " in it || " TS6204 " in it })
    }

    @Test
    fun `the cross-file hub carries a TS6203 related row pointing at the other file`() {
        val rel = related(
            """
            // @Filename: /p/a.ts
            class C {}
            // @Filename: /p/b.ts
            class C {}
            """,
        )
        assert(
            rel == listOf(
                "/p/a.ts:1 -> /p/b.ts:1:7 TS6203 'C' was also declared here.",
                "/p/b.ts:1 -> /p/a.ts:1:7 TS6203 'C' was also declared here.",
            ),
        )
    }

    @Test
    fun `negative control - the same names in two module files are silent`() {
        val r = rows(
            """
            // @Filename: /p/a.ts
            export class C {}
            export const x = 1;
            // @Filename: /p/b.ts
            export class C {}
            export let x = 2;
            """,
        )
        assert(r.isEmpty())
    }
}
