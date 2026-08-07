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
 * M3.1 (round 461): a bare TypeParam SOURCE whose declared constraint chain reaches
 * a type assignable to the assignment target is itself assignable — tsc's rule that
 * a type parameter relates to X iff its constraint does. The relation engine
 * deliberately has NO general TypeParam-source rule (39+ cycle gate; the round-456
 * broad attempt perturbed overload selection), so the bail lives at the TS2322
 * assignment EMISSION site only (`bareTpConstraintRelatesTo`).
 *
 * tsc-source shapes: transformers/classFields.ts:1800 `currentClassContainer = node`
 * where `node: T extends ClassLikeDeclaration`; utilities.ts:12338
 * `clone = getSynthesizedDeepCloneWorker(...)` returning bare `T extends Node`.
 */
class TypeParamSourceAssignmentConstraintTest {

    @Test
    fun `param typed T extends C assigned to a C-or-undefined local draws no TS2322`() {
        diagnose("""
            interface C { x: number }
            let cur: C | undefined;
            function f<T extends C>(node: T) {
                cur = node;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `un-inferred generic call result typed bare T assigned to constraint-typed local draws no TS2322`() {
        diagnose("""
            interface N { kind: number }
            declare function worker<T extends N>(node: T): T;
            declare function replaceNode(n: N): N | undefined;
            function g<T extends N>(node: T): T {
                let clone = replaceNode(node);
                if (clone) {
                }
                else {
                    clone = worker(node as NonNullable<T>);
                }
                return clone as T;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `constraint CHAIN T extends U extends C relates through two levels`() {
        diagnose("""
            interface C { x: number }
            let cur: C | undefined;
            function f<U extends C, T extends U>(node: T) {
                cur = node;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - constrained T whose constraint does NOT relate still fires TS2322`() {
        diagnose("""
            function bad2<T extends number>(x: T) {
                let s: string = "";
                s = x;
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - self-referential constraint T extends T does not loop and does not suppress`() {
        // Cycle guard: `T extends T` walks to itself and must terminate returning false,
        // so the assignment to an unrelated concrete target still fires.
        diagnose("""
            function cyc<T extends T>(x: T) {
                let s: string = "";
                s = x;
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }
}
