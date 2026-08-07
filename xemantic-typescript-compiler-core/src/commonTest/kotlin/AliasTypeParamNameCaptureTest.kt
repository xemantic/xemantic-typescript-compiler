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
 * Round 472: a generic alias's body must resolve the alias's OWN type-parameter
 * names through the substitution map, never through the ENCLOSING type-param
 * scope — `getTypeFromTypeReference` consults `currentTypeParamScope` before
 * `currentTypeAliasArgs`, so a callee TP named like an alias TP CAPTURED the
 * body's reference. tsc's `binarySearchKey<T, U>(array, key, keySelector,
 * keyComparer: Comparer<U>)` where `Comparer<T> = (a: T, b: T) => Comparison`:
 * the alias body's `a: T` resolved to binarySearchKey's T, so the contextual
 * anchor mapping T→Node typed the comparer callback param as Node → FP TS2538
 * on `children[middle]` (tsc utilities.ts:1750). The alias substitution now
 * shadows exactly its own TP names out of the scope for the body resolution.
 */
class AliasTypeParamNameCaptureTest {

    private val prelude = """
        interface Node2 { end: number; }
        const enum Comparison { LessThan = -1, EqualTo = 0, GreaterThan = 1 }
        type Comparer<T> = (a: T, b: T) => Comparison;
        declare function binarySearchKey<T, U>(
            array: readonly T[],
            key: U,
            keySelector: (v: T, i: number) => U,
            keyComparer: Comparer<U>,
            offset?: number,
        ): number;
    """.trimIndent()

    @Test
    fun `an alias TP colliding with a callee TP name is not captured by the callee scope`() {
        diagnose(
            prelude + "\n" + """
            function find(children: Node2[], position: number): Node2 | undefined {
                const i = binarySearchKey(children, position, (_, i) => i, (middle, _) => {
                    if (position < children[middle].end) {
                        return Comparison.EqualTo;
                    }
                    return Comparison.LessThan;
                });
                if (i >= 0 && children[i]) {
                    return children[i];
                }
                return undefined;
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2538 })
        }
    }

    @Test
    fun `negative control - an object-typed index via a non-colliding alias still fires`() {
        // The callback param genuinely IS an object type here (Keyer<Node2>) —
        // the TS2538 must keep firing.
        diagnose(
            """
            interface Node2 { end: number; }
            type Keyer<K> = (key: K) => number;
            declare function search(fn: Keyer<Node2>): number;
            function find2(children: Node2[]) {
                search(k => children[k].end);
            }
            """.trimIndent()
        ) should {
            have(any { it.code == 2538 })
        }
    }
}
