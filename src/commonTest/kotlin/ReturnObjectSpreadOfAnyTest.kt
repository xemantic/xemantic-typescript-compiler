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
 * Round 445 (self-compile burn-down, findAllReferences.ts TS2739 ×5 → 0): tsc types an object
 * literal that spreads an `any`/unresolved value as `any` (the spread poisons the whole
 * object), so a returned `{ ...anyExpr, ... }` cannot be "missing" required target properties —
 * the spread may provide them. Our un-annotated function-return inference is incomplete, so a
 * spread of such a call (`...getFileAndTextSpanFromNode(node)` → `{ sourceFile, textSpan }`)
 * resolves to `any`; the returned object FP'd "missing sourceFile, textSpan". The return path
 * now bails when the returned object literal has an unresolved-typed spread.
 */
class ReturnObjectSpreadOfAnyTest {

    @Test
    fun `returned object spreading an unresolved-return call does not FP missing properties`() {
        diagnose(
            """
            interface Node { kind: number; }
            // No return annotation -> our checker cannot yet infer the object-literal return,
            // so getBits(...) resolves to any; the spread must be treated as providing anything.
            function getBits(node: Node) {
                return { sourceFile: node, textSpan: 1 };
            }
            function makeInfo(node: Node): { sourceFile: Node; textSpan: number; name: string } {
                return { ...getBits(node), name: "x" };
            }
            """,
        ) should {
            have(none { it.code == 2739 })
            have(none { it.code == 2740 })
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - spread of a RESOLVED type missing a required prop still fires`() {
        diagnose(
            """
            interface Partial { a: number; }
            declare const p: Partial;
            function f(): { a: number; b: number } {
                return { ...p };
            }
            """,
        ) should {
            // p is a concrete Partial (a: number), the spread cannot provide b — still fire.
            have(any { it.code == 2739 || it.code == 2741 || it.code == 2322 })
        }
    }
}
