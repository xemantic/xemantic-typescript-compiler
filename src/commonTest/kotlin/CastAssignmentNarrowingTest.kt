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
 * Round 454 (M3.4, self-compile burn-down): an assignment `x = expr as T` narrows `x` by the
 * CAST TARGET type T, regardless of the inner expression. `rhsIsDefinitelyNonNullish` unwrapped
 * the `as`/`<T>` cast to its inner expression and then classified THAT — so a non-nullish cast of
 * an `any`-returning call (`Object.create(Array.prototype) as NodeArray<Node>`, tsc's own
 * debug.ts) was not recognized as non-nullish, and a `NodeArray<Node> | undefined` module var
 * stayed narrowed to `undefined` from the enclosing `if (!proto)` falsy guard → the next call
 * `attachNodeArrayDebugInfoWorker(proto)` FP-fired TS2345 (`undefined` not assignable). Now a
 * cast whose target resolves to a concrete non-nullish type re-narrows the reference to the
 * declared type minus nullish. FP-safe: a cast to a nullable target (or `any`/`unknown`) still
 * falls through to the inner expression's own shape.
 */
class CastAssignmentNarrowingTest {

    private val prelude = """
        interface Node2 { kind: number; }
        interface NodeArray2<T> { arr: T[]; }
        declare function worker(array: NodeArray2<Node2>): void;
        declare function makeAny(): any;
    """.trimIndent() + "\n"

    @Test
    fun `an as-cast to a non-nullish type re-narrows a possibly-undefined reference`() {
        diagnose(
            prelude +
            """
            let proto: NodeArray2<Node2> | undefined;
            export function attach(): void {
                if (!proto) {
                    proto = makeAny() as NodeArray2<Node2>;
                    worker(proto);
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `an angle-bracket cast to a non-nullish type re-narrows too`() {
        diagnose(
            prelude +
            """
            let proto: NodeArray2<Node2> | undefined;
            export function attach(): void {
                if (!proto) {
                    proto = <NodeArray2<Node2>>makeAny();
                    worker(proto);
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `no cast keeps the falsy-guard undefined narrowing - negative control`() {
        // Without the cast, `proto2` stays exactly `undefined` from `if (!proto2)`, so the call
        // must still fire TS2345 — the fix must not globally suppress the possibly-undefined arg
        // check.
        diagnose(
            prelude +
            """
            let proto2: NodeArray2<Node2> | undefined;
            export function neg(): void {
                if (!proto2) {
                    worker(proto2);
                }
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}
