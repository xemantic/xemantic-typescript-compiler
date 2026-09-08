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
 * Round 452 (self-compile burn-down): an empty tuple/array (`[]`) must be
 * assignable to an ALL-OPTIONAL tuple target (`[a?, b?]` / `[a?: T, b?: T]`)
 * without FP-firing TS2739/TS2322 — tsc's own moduleSpecifiers.ts
 * `return emptyArray as []` against `readonly [kind?, specifiers?, …]`.
 *
 * ROOT CAUSE: the parser DISCARDS a tuple element's `?` token (and any label),
 * so the resolved tuple `Type` marked every numbered member required AND its
 * `length` a fixed literal. Fix: the parser now records per-element optionality
 * on `TupleType.elementOptional`; `getTupleType` marks optional members
 * (`optionalTupleMemberIds`, consulted by `isOptionalProperty`) and makes an
 * optional-containing tuple's `length` the union `minLength | … | maxLength`.
 */
class OptionalTupleAssignabilityTest {

    @Test
    fun `empty array is assignable to an all-optional named tuple`() {
        diagnose(
            """
            declare const emptyArray: never[];
            function f(): readonly [kind?: string, specifiers?: readonly string[], moduleFile?: object] {
                return emptyArray as [];
            }
            """,
        ) should {
            have(none { it.code == 2739 || it.code == 2740 || it.code == 2322 })
        }
    }

    @Test
    fun `empty array literal is assignable to an all-optional tuple variable`() {
        diagnose(
            """
            const t: [a?: number, b?: string] = [];
            """,
        ) should {
            have(none { it.code == 2739 || it.code == 2740 || it.code == 2322 })
        }
    }

    @Test
    fun `unnamed optional tuple elements accept an empty source`() {
        diagnose(
            """
            declare const emptyArray: never[];
            function f(): [number?, string?] {
                return emptyArray as [];
            }
            """,
        ) should {
            have(none { it.code == 2739 || it.code == 2740 || it.code == 2322 })
        }
    }

    @Test
    fun `negative control - empty source vs a tuple with a required leading element still errors`() {
        diagnose(
            """
            declare const emptyArray: never[];
            function f(): [a: number, b?: string] {
                return emptyArray as [];
            }
            """,
        ) should {
            have(any { it.code == 2739 || it.code == 2322 })
        }
    }

    /**
     * (CHK.108) CORRECTED: this shape is `TS2322` plus the ARITY sub-line in pristine
     * `typescript@6.0.3` AND tsgo 7.0.2 — measured, and the corpus's own `tupleTypes.ts`
     * baseline (line 41) says the same. It read `TS2739 Type '[]' is missing the following
     * properties … 0, 1` here, which no reference prints; the pin asserted OUR shape rather
     * than the references', and the corpus could not see it because that whole file is
     * served by a pin walker.
     */
    @Test
    fun `negative control - empty source vs an all-required tuple is TS2322 with the arity line`() {
        diagnose(
            """
            declare const emptyArray: never[];
            function f(): [number, string] {
                return emptyArray as [];
            }
            """,
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type '[]' is not assignable to type '[number, string]'." &&
                    it.messageChain == listOf("  Source has 0 element(s) but target requires 2.")
            })
        }
    }
}
