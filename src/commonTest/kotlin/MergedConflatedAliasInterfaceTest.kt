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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * Round 468b (Blocker #3): the target annotation names an interface SHADOWED by a
 * sibling module file's `type X` alias (round 443's SourceFileLike — the last-wins
 * Interface+TypeAlias merge picks the alias, so the resolved target is a bogus
 * union). tsc's true target is the MERGED interface: the base declaration plus
 * `declare module` AUGMENTATION members (tsc's services `declare module
 * "../compiler/types.js" { interface SourceFileLike { getLineAndCharacterOfPosition
 * } }`). The object literal is checked AST-side against that merged member table.
 */
class MergedConflatedAliasInterfaceTest {

    private val files = """
        // @filename: types.ts
        export interface SourceFileLike { readonly text: string; lineMap?: number[]; }
        export const d1 = 1;
        // @filename: aug.ts
        import { SourceFileLike } from "./types";
        declare module "./types" {
            export interface SourceFileLike { getPos(pos: number): number; }
        }
        export const d2: SourceFileLike | undefined = undefined;
        // @filename: tracker.ts
        interface Dummy { a: string; }
        interface Other { b: number; }
        type SourceFileLike = Dummy | Other;
        export const d3 = 3;
    """.trimIndent()

    @Test
    fun `an object literal satisfying the merged base plus augmentation members passes`() {
        diagnose(
            files + """

            // @filename: main.ts
            import { SourceFileLike } from "./types";
            export function make(text: string): SourceFileLike {
                return { text, getPos(pos: number) { return pos; } };
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a missing required augmentation member still fires`() {
        diagnose(
            files + """

            // @filename: main.ts
            import { SourceFileLike } from "./types";
            export function make(text: string): SourceFileLike {
                return { text };
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            // `getPos` is required by the augmentation — genuinely missing (tsc errors
            // too; our standard path reports the shape mismatch as 2322 or 2353).
            have(any { it.code == 2322 || it.code == 2353 })
        }
    }

    @Test
    fun `negative control - an excess member vs the merged set still fires`() {
        diagnose(
            files + """

            // @filename: main.ts
            import { SourceFileLike } from "./types";
            export function make(text: string): SourceFileLike {
                return { text, getPos(pos: number) { return pos; }, bogus: 1 };
            }
            """,
            directives = "// @strict: true\n// @module: commonjs",
        ) should {
            have(any { it.code == 2322 || it.code == 2353 })
        }
    }
}
