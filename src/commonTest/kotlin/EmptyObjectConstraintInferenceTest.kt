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
 * Round 430 (M3.1): two inference-gate unlocks for tsc's exact `append`/`addRange`
 * signatures.
 *
 *  (a) An EMPTY anonymous object target `{}` accepts any non-nullish, non-void
 *      source in the relation (tsc: `{}` is the supertype of everything but
 *      null/undefined). tsc core.ts declares `append<T extends {}>` — the
 *      constraint check rejected `string` and killed the whole call-site
 *      inference, leaving the return `T[]` un-instantiated.
 *  (b) `readonly T[]` params (Reference(ReadonlyArray, [T])) and `readonly X[]`
 *      args anchor array-of-tp inference like mutable arrays —
 *      `addRange(to: T[] | undefined, from: readonly T[] | undefined)`.
 */
class EmptyObjectConstraintInferenceTest {

    @Test
    fun `append with T extends empty-object constraint infers from string args`() {
        diagnose(
            """
            export function append<T extends {}>(to: T[], value: T | undefined): T[];
            export function append<T extends {}>(to: T[] | undefined, value: T): T[];
            export function append<T extends {}>(to: T[] | undefined, value: T | undefined): T[] | undefined;
            export function append<T extends {}>(to: T[] | undefined, value: T | undefined): T[] | undefined {
                if (value === undefined) return to;
                if (to === undefined) return [value];
                to.push(value);
                return to;
            }
            export function f(specifier: string) {
                let xs: string[] | undefined;
                xs = append(xs, specifier);
                return xs;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `addRange with readonly-array params infers the element type`() {
        diagnose(
            """
            export function addRange<T>(to: T[] | undefined, from: readonly T[] | undefined, start?: number, end?: number): T[] | undefined {
                return to;
            }
            interface Diag { msg: string; }
            declare function getDiags(): readonly Diag[];
            export function f() {
                let diagnostics: Diag[] | undefined;
                diagnostics = addRange(diagnostics, getDiags());
                return diagnostics;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a wrong-element array assignment still fires`() {
        diagnose(
            """
            export function addRange<T>(to: T[] | undefined, from: readonly T[] | undefined): T[] | undefined {
                return to;
            }
            interface Diag { msg: string; }
            declare function getNums(): readonly number[];
            export function f() {
                let nums: number[] | undefined;
                let diagnostics: Diag[] | undefined;
                diagnostics = addRange(nums, getNums());
                return diagnostics;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - null and undefined still rejected by an empty-object target`() {
        // (Uses the var-decl path — an anonymous `{}` PARAM isn't simple-checkable,
        // so the call-arg path never checked it at baseline either.)
        diagnose(
            """
            export const a: {} = null;
            export const b: {} = undefined;
            """
        ) should {
            have(count { it.code == 2322 } == 2)
        }
    }

    @Test
    fun `primitives assigned to an empty-object target are clean`() {
        diagnose(
            """
            export const a: {} = "ok";
            export const b: {} = 42;
            export const c: {} = true;
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }
}
