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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * M3.2 (round 436): a GENERIC callee's callback param whose RETURN carries the
 * callee's own (un-inferred) type parameter must not fail the fn-vs-fn
 * return-mismatch check — tsc infers U from the callback's own return, so an
 * unconstrained U accepts ANY return type (tsc's `forEachEntry<K, V, U>(map,
 * cb: (value: V, key: K) => U | undefined)` called with a boolean-returning
 * callback, and `firstDefinedIterator` with a `true | undefined` callback).
 *
 * The skip lives in `allowFuncReturnMismatch` (checkArgumentsAgainstSignature)
 * and is gated to a GENERIC callee (`sigIn.typeParameters` non-empty) — a
 * non-generic callee's concrete callback-return mismatch must keep firing.
 */
class CallbackReturnTpParamTest {

    @Test
    fun `boolean callback against a TP-union return param is legal`() {
        // The forEachEntry shape: U only in the callback return position.
        diagnose(
            """
            declare function forEachEntry<K, V, U>(
                map: ReadonlyMap<K, V>,
                callback: (value: V, key: K) => U | undefined,
            ): U | undefined;
            declare const exports2: ReadonlyMap<string, number>;
            function hasExportedMembers() {
                return forEachEntry(exports2, (_, id) => id !== "export=");
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `true-or-undefined callback against a TP-union return param is legal`() {
        // The firstDefinedIterator shape: `true | undefined` callback return.
        diagnose(
            """
            declare function firstDefinedIterator<T, U>(
                iter: Iterable<T>,
                callback: (element: T) => U | undefined,
            ): U | undefined;
            declare function startsWith(s: string, p: string): boolean;
            function isInvalidated(locationPath: string, checks: Map<string, true>) {
                return firstDefinedIterator(
                    checks.keys(),
                    p => startsWith(locationPath, p) ? true : undefined,
                );
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a concrete return mismatch on a non-generic callee still fires`() {
        // The skip is gated to generic callees. An arrow arg with only a return
        // mismatch reports the fine-grained TS2322 at the arrow's return
        // expression per the round-79l rule, so accept either TS2345 or TS2322.
        diagnose(
            """
            declare function eachString(callback: (x: number) => string): void;
            eachString((x) => x > 0);
            """
        ) should {
            have(any { it.code == 2345 || it.code == 2322 })
        }
    }
}
