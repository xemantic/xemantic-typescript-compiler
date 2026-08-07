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
 * Round 455 (M3.1, self-compile burn-down): a body-local `const clone = …` (un-annotated,
 * initializer type unmodeled) that SHADOWS a same-named exported/global generic function
 * (tsc's own core.ts `clone<T>(object: T): T`) must NOT fall through to that global in a
 * value-position use (`return clone`). tsc's nodeFactory.ts `cloneIdentifier`/`clonePrivateIdentifier`
 * (top-level `const clone = createBaseIdentifier(...)`) and expressionToTypeNode.ts:535 /
 * checker.ts:15679 (`const clone = …` inside an `if` block, then `return clone`) FP-fired
 * TS2322 `'<T>(object: T) => T' is not assignable to type 'Identifier' / 'Node | undefined' / …`.
 * `applyBodyLocalShadowing` now registers such a global-shadowing local as anyType (and descends
 * into nested blocks for the same case). Suppression-only: a concretely-inferable local keeps its
 * type, and a genuine value-position use of the global elsewhere is unaffected.
 */
class LocalConstShadowsGlobalFunctionTest {

    private val prelude = """
        export function clone<T>(object: T): T { return object; }
        interface Ident { escapedText: string; }
        declare function createBaseIdentifier(text: string): Ident;
    """.trimIndent()

    @Test
    fun `a top-level const shadowing a global generic function does not FP the return`() {
        // cloneIdentifier: `const clone = createBaseIdentifier(...)` at the top of the body,
        // then `return clone` against `Ident` — must not resolve `clone` to the global
        // `<T>(object: T) => T`.
        diagnose(
            prelude + """
            export function cloneIdentifier(node: Ident): Ident {
                const clone = createBaseIdentifier(node.escapedText);
                return clone;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a const inside an if-block shadowing a global generic function does not FP the return`() {
        // expressionToTypeNode.ts:535 shape: `const clone = …` inside an `if`, then `return clone`.
        diagnose(
            prelude + """
            export function maybeClone(node: Ident, flag: boolean): Ident | undefined {
                if (flag) {
                    const clone = createBaseIdentifier(node.escapedText);
                    return clone;
                }
                return undefined;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `an ANNOTATED shadow keeps its concrete type - so a genuine mismatch still fires - negative control`() {
        // `const clone: Ident = …` — the annotated branch records the concrete `Ident` type
        // (not anyType), so assigning it to a `number` still fires TS2322. Proves the fix is
        // scoped to UN-annotated global shadows and does not blanket-suppress.
        diagnose(
            prelude + """
            export function bad(node: Ident): number {
                const clone: Ident = createBaseIdentifier(node.escapedText);
                const n: number = clone;
                return n;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
