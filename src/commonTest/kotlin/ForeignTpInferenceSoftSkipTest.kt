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
 * INV.3(d)(i): the round-510 ambiguous-both-constrained→FOREIGN default in
 * `typeContainsForeignTypeParam` is REVERTED (it suppressed genuine TP-vs-TP /
 * concrete-vs-TP checks — the typeParametersShouldNotBeEqual2 /
 * genericTypeAssertions4 corpus family); the checker.ts:7358 leak that leg
 * patched (`setTextRangeWorker(factory.createNodeArray(undefined, …), nodes)` —
 * a nested call's un-inferred `T extends Node` leaking through `NodeArray<T>`
 * into the OUTER call's inference, name-AND-shape-colliding with the enclosing
 * `T`) is killed at the INFERENCE side instead: a CallExpression arg whose type
 * still carries a TypeParam contributes no candidate at forReturnType sites,
 * degrading the outer tp to anyType exactly like the pre-retire merged-callee
 * any-degradation. The compiler-profile `--listAll` byte-identity vs the
 * round-510 capture is the whole-program pin for checker.ts:7358 itself.
 */
class ForeignTpInferenceSoftSkipTest {

    @Test
    fun `revert pin - a concrete source assigned to a constrained own TP still fires TS2322`() {
        // genericTypeAssertions4's distillation: `x = a` where x: T extends A —
        // A is not assignable to T (T could be instantiated with a subtype).
        // The round-510 ambiguous leg classified fresh-minted constrained own
        // TPs as foreign at the assignment gate, silently suppressing this.
        diagnose(
            """
            class A {
                foo() { return ""; }
            }
            function f<T extends A>(x: T, a: A) {
                x = a;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `revert pin - two distinct constrained TPs assigned still fire TS2322`() {
        // typeParametersShouldNotBeEqual-family distillation: T and U share the
        // constraint but are DISTINCT type parameters — u is not assignable to t.
        diagnose(
            """
            class A {
                foo() { return ""; }
            }
            function f<T extends A, U extends A>(t: T, u: U) {
                t = u;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `a CallExpression arg leaking an un-inferred TP degrades the outer inference - no TS2322`() {
        // The checker.ts:7358 shape: mkArr's inference cannot complete (undefined
        // elements), its own `T extends N` leaks through `Arr<T>` into setTR's
        // bare-T param; the outer tp must degrade to anyType (no candidate), so
        // the return check sees `any`, never a leaked `Arr<T>` vs `Arr<N>`.
        diagnose(
            """
            interface N { k: number; }
            interface Arr<T extends N> { items: T[]; hasTrailingComma: boolean; }
            declare function setTR<T extends N>(range: T, location: unknown): T;
            declare function mkArr<T extends N>(elements?: readonly T[], hasTrailingComma?: boolean): Arr<T>;
            function visit<T extends N>(nodes: Arr<T> | undefined): Arr<N> | undefined {
                if (nodes) {
                    return setTR(mkArr(undefined, nodes.hasTrailingComma), nodes);
                }
                return undefined;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an Identifier arg typed by the caller still anchors inference - genuine TS2322 fires`() {
        // The append idiom's mismatch case: `x` is an Identifier (not a
        // CallExpression), so the soft-skip must NOT fire — T anchors to string
        // and the string[] return genuinely fails the number[] target.
        diagnose(
            """
            declare function append<T>(to: T[] | undefined, value: T): T[];
            function h(x: string[] | undefined, item: string): number[] {
                return append(x, item);
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - the append idiom keeps inferring through an Identifier arg - no TS2322`() {
        diagnose(
            """
            declare function append<T>(to: T[] | undefined, value: T): T[];
            function g(x: string[] | undefined, item: string): string[] {
                x = append(x, item);
                return x;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a TP-free CallExpression arg still anchors inference - genuine TS2322 fires`() {
        // The soft-skip is gated on the arg TYPE carrying a TypeParam — a nested
        // call returning a concrete string[] must keep anchoring T := string, so
        // the genuine string[]-vs-number[] return mismatch still fires.
        diagnose(
            """
            declare function mk(): string[];
            declare function append<T>(to: T[] | undefined, value: T): T[];
            function g2(item: string): number[] {
                return append(mk(), item);
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
