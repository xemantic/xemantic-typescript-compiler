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
 * Self-compile burn-down (CFA): a `switch (typeof value)` whose cases cover exactly the
 * subject's ACTUAL possible tags is exhaustive — after them tsc narrows the subject to
 * `never`, so a value-returning function needs no trailing return. tsc's own `utilities.ts`
 * `hasValue(value: string | number | PseudoBigInt)` switches on string/number/object and FP'd
 * TS2366. The prior check only recognized a switch covering ALL 8 typeof strings; it now also
 * accepts the subject's own (fully covered) tag set. FP-safe: `typeofTagsOfType` bails to null
 * on any uncertain constituent (any/unknown/type-param/enum), keeping TS2366/TS7030 firing.
 */
class TypeofSwitchExhaustiveReturnTest {

    @Test
    fun `typeof switch covering the subject's three tags - no TS2366`() {
        diagnose(
            """
            interface PseudoBigInt { negative: boolean; base10Value: string; }
            declare function s(v: string): boolean;
            declare function n(v: number): boolean;
            declare function b(v: PseudoBigInt): boolean;
            export function hasValue(value: string | number | PseudoBigInt): boolean {
                switch (typeof value) {
                    case "string": return s(value);
                    case "number": return n(value);
                    case "object": return b(value);
                }
            }
            """,
        ) should {
            have(none { it.code == 2366 })
            have(none { it.code == 7030 })
        }
    }

    @Test
    fun `typeof switch missing a required tag STILL fires TS2366 - negative control`() {
        // FP-safety: the object case is absent, so a PseudoBigInt value falls through → TS2366.
        diagnose(
            """
            interface PseudoBigInt { negative: boolean; }
            export function hasValue(value: string | number | PseudoBigInt): boolean {
                switch (typeof value) {
                    case "string": return true;
                    case "number": return true;
                }
            }
            """,
        ) should {
            have(any { it.code == 2366 })
        }
    }

    @Test
    fun `typeof switch on an any-typed subject STILL fires TS2366 - negative control`() {
        // FP-safety: `any` has no provable tag set → typeofTagsOfType bails → TS2366 stands.
        diagnose(
            """
            export function f(value: any): boolean {
                switch (typeof value) {
                    case "string": return true;
                    case "number": return true;
                }
            }
            """,
        ) should {
            have(any { it.code == 2366 })
        }
    }

    @Test
    fun `two-tag string-or-number switch is exhaustive - no TS2366`() {
        diagnose(
            """
            export function f(value: string | number): boolean {
                switch (typeof value) {
                    case "string": return true;
                    case "number": return false;
                }
            }
            """,
        ) should {
            have(none { it.code == 2366 })
        }
    }
}
