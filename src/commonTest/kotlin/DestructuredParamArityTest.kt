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
 * Round 446: a method with a DESTRUCTURED parameter (`{ fileName, pos }: Range`) FP'd
 * TS2554 "Expected 1-0 arguments, but got 1." for a correctly-argumented property-access
 * call. A binding-pattern param produces NO Symbol, so `sig.parameters` dropped it (empty
 * list → maxParams 0) while `minArgumentCount` stayed 1 — an impossible range.
 * `checkTs2554ForPropertyAccessCall` now recovers the true arity from the DECLARATION's
 * parameter list (paramInfo counts binding-pattern params).
 */
class DestructuredParamArityTest {

    @Test
    fun `single destructured param called correctly is not too-many`() {
        diagnose(
            """
            interface Range { fileName: string; pos: number; }
            class C {
                goToRangeStart({ fileName, pos }: Range): void {}
                caller(r: Range) { this.goToRangeStart(r); }
            }
            """
        ) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `mixed identifier and destructured params called correctly`() {
        diagnose(
            """
            class C {
                m(a: number, { x }: { x: number }): void {}
                caller() { this.m(1, { x: 2 }); }
            }
            """
        ) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `array binding pattern param called correctly`() {
        diagnose(
            """
            class C {
                m([a, b]: [number, number]): void {}
                caller() { this.m([1, 2]); }
            }
            """
        ) should {
            have(none { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - too many args with a destructured param still fires`() {
        diagnose(
            """
            interface Range { fileName: string; pos: number; }
            class C {
                goToRangeStart({ fileName, pos }: Range): void {}
                caller(r: Range) { this.goToRangeStart(r, r); }
            }
            """
        ) should {
            have(any { it.code == 2554 })
        }
    }

    @Test
    fun `negative control - too few args with a destructured param still fires`() {
        diagnose(
            """
            class C {
                m(a: number, { x }: { x: number }): void {}
                caller() { this.m(1); }
            }
            """
        ) should {
            have(any { it.code == 2554 })
        }
    }
}
