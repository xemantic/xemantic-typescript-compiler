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
 * Round 457 (M3.4): `asserts node is Exclude<T, U>` narrowing — tsc's
 * `Debug.assertNotNode<T, U extends T>(node: T | undefined, test: (n) => n is U):
 * asserts node is Exclude<T, U>`. After the call, `node`'s union type has the
 * members assignable to `U` REMOVED. `U` is the callee's type param, bound from the
 * sibling `test` argument's predicate target (`isNonNullChain` → `NonNullChain`).
 *
 * tsc's es2020.ts `flattenChain`: `Debug.assertNotNode(chain, isNonNullChain)`
 * narrows `OptionalChain` = `PropertyAccessChain | ElementAccessChain | CallChain |
 * NonNullChain` down to the first three (NonNullChain lacks `questionDotToken`), so
 * `chain.questionDotToken` resolves.
 *
 * The narrowing lives in `narrowByAssertCall` (both flow walkers reach it), alongside
 * the existing `asserts x is NonNullable<T>` special-case. Suppression-only / FP-safe:
 * it fires only when a member is genuinely removed and something survives.
 */
class AssertNotNodeExcludeNarrowingTest {

    private val prelude = """
        interface A { tag: "a"; q: number; }
        interface B { tag: "b"; q: number; }
        interface D { tag: "d"; d: number; }
        type U = A | B | D;
        declare function isD(x: { tag: string }): x is D;
        declare function assertNotNode<T, V extends T>(node: T | undefined, test: (n: any) => n is V): asserts node is Exclude<T, V>;

    """.trimIndent()

    @Test
    fun `assertNotNode excludes the guarded member - a property on the survivors resolves`() {
        diagnose(
            prelude + """
            function f(u: U): number {
                assertNotNode(u, isD);
                return u.q;
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `assertNotNode exclusion survives a loop back-edge`() {
        // The receiver is read in a while CONDITION reached via the loop label; the
        // loop-entry-following narrowing variant picks up the pre-loop assert.
        diagnose(
            prelude + """
            function flatten(chain: U): number {
                assertNotNode(chain, isD);
                let sum = 0;
                while (chain.q > 0) {
                    sum += chain.q;
                    chain = chain;
                    assertNotNode(chain, isD);
                }
                return sum;
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `assertNotNode excludes an intersection-typed member (NonNullChain shape)`() {
        diagnose(
            """
            interface Base { expr: number; }
            interface PropChain extends Base { tag: "a"; qd: number; }
            type NNChain = Base & { _brand: any; };
            type OptChain = PropChain | NNChain;
            declare function isNN(x: Base): x is NNChain;
            declare function assertNotNode<T, V extends T>(node: T | undefined, test: (n: any) => n is V): asserts node is Exclude<T, V>;
            function f(chain: OptChain): number {
                assertNotNode(chain, isNN);
                return chain.qd;
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - the excluded member's own property stays inaccessible`() {
        // After excluding D, `u` is `A | B` — accessing D's own property `d` must
        // still fire TS2339 (the exclusion is precise, not a blanket suppression).
        diagnose(
            prelude + """
            function f(u: U): number {
                assertNotNode(u, isD);
                return u.d;
            }
            """,
        ) should {
            have(any { it.code == 2339 })
        }
    }
}
