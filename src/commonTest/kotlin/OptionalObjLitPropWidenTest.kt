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
 * Round 440 (M3.4, self-compile burn-down): a fresh object-literal property VALUE typed
 * `T | undefined` assigned to an OPTIONAL target property `a?: T` is accepted when
 * exactOptionalPropertyTypes is off — tsc's sourcemap.ts captureMapping returns
 * `{ sourceIndex: hasSource ? sourceIndex : undefined }` (`number | undefined`) against
 * `Mapping.sourceIndex?: number`.
 *
 * The nested object-literal per-property leaf (checkNestedObjLitPropTypes) compared the
 * value against the BARE declared member type, ignoring optionality; it now routes the
 * relation target through widenOptionalTargetPropType (source-nullish gated), which widens
 * an optional target to `T | undefined` for a nullish/union-with-undefined source only.
 */
class OptionalObjLitPropWidenTest {

    @Test
    fun `number-or-undefined value to optional number prop is accepted`() {
        diagnose(
            """
            interface Mapping {
                generatedLine: number;
                sourceIndex?: number;
            }
            export function make(hasSource: boolean, sourceIndex: number, generatedLine: number): Mapping {
                return {
                    generatedLine,
                    sourceIndex: hasSource ? sourceIndex : undefined,
                };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `bare undefined value to optional prop is accepted`() {
        diagnose(
            """
            interface Mapping { a: number; b?: number; }
            export function make(a: number): Mapping {
                return { a, b: undefined };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `concrete mismatched value to optional prop still fires - negative control`() {
        // A NON-nullish concrete source elaborates against the BARE prop type: `boolean`
        // is not assignable to optional `string` (widenOptionalTargetPropType is
        // source-nullish gated, so it does NOT widen here).
        diagnose(
            """
            interface T { a: number; b?: string; }
            export function make(a: number, flag: boolean): T {
                return { a, b: flag };
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `number-or-undefined value to REQUIRED number prop still fires - negative control`() {
        // A required (non-optional) target property does not accept undefined.
        diagnose(
            """
            interface T { a: number; b: number; }
            export function make(a: number, x: number, has: boolean): T {
                return { a, b: has ? x : undefined };
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
