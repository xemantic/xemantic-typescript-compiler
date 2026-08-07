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
 * Round 472: a TP-referencing fn-typed PARAM skipped by the B516 gate must still
 * SHADOW its name — left unregistered, a call to it resolved through merged
 * globals to a same-named cross-file top-level function's WRONG signature.
 * tsc documentHighlights' nested `useParent<T>(node, nodeTest, getNodes: (node:
 * T, sourceFile: SourceFile) => …)` calls `getNodes(node, sourceFile)` while
 * fixAwaitInSyncFunction declares a top-level `function getNodes(sourceFile,
 * start)` → the arg `node` FP'd TS2345 against the codefix's SourceFile param.
 */
class TpReferencingFnParamShadowTest {

    @Test
    fun `a TP-referencing fn-typed param shadows a same-named cross-file function`() {
        diagnose(
            """
            // @filename: types2.ts
            export interface SourceFile2 { fileName: string; _declarationBrand: any; }
            // @filename: other.ts
            import { SourceFile2 } from "./types2";
            export function fixIt(): void {}
            function getNodes(sourceFile: SourceFile2, start: number): { insertBefore: number } | undefined {
                return undefined;
            }
            // @filename: main.ts
            import { SourceFile2 } from "./types2";
            interface Node2 { kindNum: number; }
            interface HighlightSpan { start: number; }
            declare function highlightSpans(nodes: readonly Node2[] | undefined): HighlightSpan[] | undefined;
            export function getHighlightSpans(node: Node2, sourceFile: SourceFile2) {
                return useParent(node, getNodes2);
                function useParent<T extends Node2>(node: Node2, getNodes: (node: T, sourceFile: SourceFile2) => readonly Node2[] | undefined): HighlightSpan[] | undefined {
                    return highlightSpans(getNodes(node as T, sourceFile));
                }
                function getNodes2(n: Node2, sf: SourceFile2): readonly Node2[] | undefined {
                    return undefined;
                }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a call to the genuine cross-file function still checks`() {
        diagnose(
            """
            // @filename: types2.ts
            export interface SourceFile2 { fileName: string; _declarationBrand: any; }
            // @filename: other.ts
            import { SourceFile2 } from "./types2";
            export function getNodes(sourceFile: SourceFile2, start: number): number {
                return start;
            }
            // @filename: main.ts
            import { getNodes } from "./other";
            interface Node2 { kindNum: number; }
            export function run(node: Node2) {
                return getNodes(node as any as import("./types2").SourceFile2, "not a number" as any as Node2);
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
