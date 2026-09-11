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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.125) TS2394 — `unknown` AND `never` IN AN OVERLOAD-VS-IMPLEMENTATION CHECK,
 * WHERE THE **POSITION** DECIDES THE DIRECTION.
 *
 * `function f(x: string): void; function f(x: number): void; function f(x: unknown):
 * void {}` is an entirely ordinary shape and was an ours-only TS2394 — a false
 * positive on legal code, of the kind any real library would hit. The predicate
 * special-cased `any` in both directions and both positions and knew nothing about
 * the other two intrinsics, so `string` vs `unknown` fell through to "different
 * intrinsic types are incompatible".
 *
 * ### THE RULE IS TWO RULES, WHICH IS WHY A SYMMETRIC PREDICATE CANNOT EXPRESS IT
 *
 * tsc's `isImplementationCompatibleWithOverload`:
 *  - the RETURN types must be assignable **in either direction** — its own comment
 *    says "first see if the return types are compatible in either direction";
 *  - the PARAMETERS go through `isSignatureAssignableTo(impl, overload,
 *    ignoreReturnTypes = true)`, so the OVERLOAD's parameter must be assignable to
 *    the IMPLEMENTATION's: the impl has to be the wider one.
 *
 * Measured against `tools/tsgo-7.0.2/lib/tsc` AND pristine `typescript@6.0.3`, which
 * agree on all ten cells — `unknown` is the TOP type and `never` the BOTTOM one, so
 * each is assignable in exactly ONE direction, and the two positions then disagree
 * about four of the eight combinations.
 *
 * ### THE THREE REFUSALS ARE NEGATIVE CONTROLS, NOT CONSERVATISM
 *
 * `overload unknown / impl string` in PARAM position, `overload string / impl never`
 * in PARAM position, and `overload string / impl object` all REPORT in both
 * references, and we already agreed with them. They are here because a blanket
 * "let `unknown` and `never` through" would satisfy every positive pin below while
 * silently deleting three true diagnostics — the change is strictly permissive by
 * construction, and these are what prove it is not permissive everywhere.
 */
class OverloadIntrinsicCompatibilityTest {

    private fun t2394(src: String) = diagnose(src).count { it.code == 2394 }

    // --- PARAMETER position: the impl must be the WIDER one -----------------

    @Test
    fun `a param overload string against an impl unknown is legal`() {
        assert(
            t2394(
                """
                export function zzzF(zzzX: string): void;
                export function zzzF(zzzX: unknown): void {}
                """.trimIndent()
            ) == 0
        )
    }

    /**
     * The shape the item was found on: two ordinary overloads and an `unknown`
     * implementation parameter.
     */
    @Test
    fun `two overloads against an unknown impl param are legal`() {
        assert(
            t2394(
                """
                export function zzzF(zzzX: string): void;
                export function zzzF(zzzX: number): void;
                export function zzzF(zzzX: unknown): void {}
                """.trimIndent()
            ) == 0
        )
    }

    @Test
    fun `a param overload never against an impl string is legal`() {
        assert(
            t2394(
                """
                export function zzzF(zzzX: never): void;
                export function zzzF(zzzX: string): void {}
                """.trimIndent()
            ) == 0
        )
    }

    // --- the three NEGATIVE CONTROLS: these must STILL report ---------------

    /** `unknown` is not assignable to `string`, so the impl is NARROWER — TS2394. */
    @Test
    fun `negative control - a param overload unknown against an impl string reports`() {
        assert(
            t2394(
                """
                export function zzzF(zzzX: unknown): void;
                export function zzzF(zzzX: string): void {}
                """.trimIndent()
            ) == 1
        )
    }

    /** A `never` implementation parameter accepts nothing — TS2394. */
    @Test
    fun `negative control - a param overload string against an impl never reports`() {
        assert(
            t2394(
                """
                export function zzzF(zzzX: string): void;
                export function zzzF(zzzX: never): void {}
                """.trimIndent()
            ) == 1
        )
    }

    /** An unrelated widening (`string` is not an `object`) is untouched by this change. */
    @Test
    fun `negative control - a param overload string against an impl object reports`() {
        assert(
            t2394(
                """
                export function zzzF(zzzX: string): void;
                export function zzzF(zzzX: object): void {}
                """.trimIndent()
            ) == 1
        )
    }

    /** And a genuinely incompatible pair still reports — the change is not a deletion. */
    @Test
    fun `negative control - two disjoint primitives still report`() {
        assert(
            t2394(
                """
                export function zzzF(zzzX: string): void;
                export function zzzF(zzzX: boolean): void {}
                """.trimIndent()
            ) == 1
        )
    }

    // --- RETURN position: EITHER direction is enough ------------------------

    /**
     * These four are what make `returnPosition` load-bearing rather than tidy: in
     * PARAM position two of them are refusals, and in RETURN position all four are
     * legal. A single symmetric predicate cannot produce both answers.
     *
     * Note each uses a NON-`void` overload return: the caller escapes the return
     * check entirely for a `void` overload return, which would make these vacuous.
     */
    @Test
    fun `a return overload string against an impl unknown is legal`() {
        assert(
            t2394(
                """
                export function zzzF(zzzX: string): string;
                export function zzzF(zzzX: string): unknown { return zzzX; }
                """.trimIndent()
            ) == 0
        )
    }

    @Test
    fun `a return overload unknown against an impl string is legal`() {
        assert(
            t2394(
                """
                export function zzzF(zzzX: string): unknown;
                export function zzzF(zzzX: string): string { return zzzX; }
                """.trimIndent()
            ) == 0
        )
    }

    @Test
    fun `a return overload string against an impl never is legal`() {
        assert(
            t2394(
                """
                export function zzzF(zzzX: string): string;
                export function zzzF(zzzX: string): never { throw 1; }
                """.trimIndent()
            ) == 0
        )
    }

    @Test
    fun `a return overload never against an impl string is legal`() {
        assert(
            t2394(
                """
                export function zzzF(zzzX: string): never;
                export function zzzF(zzzX: string): string { return zzzX; }
                """.trimIndent()
            ) == 0
        )
    }

    /**
     * The pair that isolates the POSITION axis: the identical intrinsic pair
     * (`overload unknown` / `impl string`) is a refusal as a PARAMETER and legal as a
     * RETURN. If `returnPosition` were dropped, exactly one of these two must break.
     */
    @Test
    fun `the same intrinsic pair differs between param and return position`() {
        val asParam = t2394(
            """
            export function zzzP(zzzX: unknown): void;
            export function zzzP(zzzX: string): void {}
            """.trimIndent()
        )
        val asReturn = t2394(
            """
            export function zzzR(zzzX: string): unknown;
            export function zzzR(zzzX: string): string { return zzzX; }
            """.trimIndent()
        )
        assert(asParam == 1)
        assert(asReturn == 0)
    }
}
