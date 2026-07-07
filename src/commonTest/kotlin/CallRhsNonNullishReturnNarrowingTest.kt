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
 * Round 424 — an assignment whose RHS is a CALL to a callee with a provably
 * non-nullish return ANNOTATION narrows the assigned reference non-nullish
 * (tsc checker.ts:21168: `type.restrictiveInstantiation = instantiateType(type,
 * restrictiveMapper);` followed by a read of the property — `instantiateType`
 * declares `: Type`).
 *
 * The classifier is deliberately SYNTACTIC and conservative — the sharp corners
 * pinned here are the bails: a `Type | undefined` return annotation, an
 * optional-chained call (short-circuits to `undefined`), and a reference to the
 * callee's OWN type parameter (`<T>(x: T): T` — T may be instantiated nullish)
 * must all keep the TS18048.
 */
class CallRhsNonNullishReturnNarrowingTest {

    private val prelude = """
        interface Ty { id: number; inst?: Ty; }
    """

    @Test
    fun callRhsWithNonNullishReturnAnnotationNarrows() {
        diagnose(
            prelude + """
            declare function instantiate(t: Ty): Ty;
            export function f(t: Ty) {
                t.inst = instantiate(t);
                t.inst.inst = t.inst;
            }
            """,
        ) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun aliasReturnAnnotationResolvesThroughTypeAlias() {
        diagnose(
            prelude + """
            type Instantiated = Ty;
            declare function instantiate(t: Ty): Instantiated;
            export function f(t: Ty) {
                t.inst = instantiate(t);
                t.inst.inst = t.inst;
            }
            """,
        ) should {
            have(none { it.code == 18048 })
        }
    }

    @Test
    fun nullableReturnAnnotationStillFires() {
        diagnose(
            prelude + """
            declare function maybe(t: Ty): Ty | undefined;
            export function f(t: Ty) {
                t.inst = maybe(t);
                t.inst.inst = t.inst;
            }
            """,
        ) should {
            have(any { it.code == 18048 })
        }
    }

    @Test
    fun optionalChainedCallStillFires() {
        diagnose(
            prelude + """
            declare const factory: { make(t: Ty): Ty } | undefined;
            export function f(t: Ty) {
                t.inst = factory?.make(t);
                t.inst.inst = t.inst;
            }
            """,
        ) should {
            have(any { it.code == 18048 })
        }
    }

    @Test
    fun genericOwnTypeParamReturnStillFires() {
        diagnose(
            prelude + """
            declare function ident<T>(x: T): T;
            export function f(t: Ty, m: Ty | undefined) {
                t.inst = ident(m);
                t.inst.inst = t.inst;
            }
            """,
        ) should {
            have(any { it.code == 18048 })
        }
    }
}
