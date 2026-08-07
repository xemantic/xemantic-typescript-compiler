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
 * Round 445 (self-compile burn-down, services TS2430 3 → 0): the interface-extends-interface
 * check (TS2430) had two false-positive families from tsc's own compiler/services sources:
 *
 *  (A) An OPTIONAL base member `p?: T` accepts a narrower derived override `p: undefined`
 *      (convertParamsToDestructuredObject.ts's `ValidParameterDeclaration extends
 *      ParameterDeclaration { modifiers: undefined }`) — `undefined` is assignable to
 *      `T | undefined`. The raw base declared type dropped the optional `| undefined`, so
 *      widen it for the relation (same source-nullish-gated widen as the class-override fix).
 *
 *  (B) A derived METHOD member implementing a base function-typed DATA property
 *      (EmitHost.getCanonicalFileName(fileName): string implementing
 *      SourceFileMayBeEmittedHost.getCanonicalFileName: GetCanonicalFileName) was compared
 *      as method-RETURN-type (`string`) vs the base property's full function type — the wrong
 *      shape. Skip the simple property comparison when the derived member is a method.
 */
class InterfaceExtendsOptionalAndMethodPropertyTest {

    @Test
    fun `interface member narrowing an optional base member to undefined - no TS2430`() {
        diagnose(
            """
            interface Decorator { d: number; }
            interface ParameterDeclaration {
                modifiers?: Decorator[];
                name: string;
            }
            interface ValidParameterDeclaration extends ParameterDeclaration {
                modifiers: undefined;
            }
            """,
        ) should {
            have(none { it.code == 2430 })
        }
    }

    @Test
    fun `negative control - interface member with a WRONG concrete type still fires TS2430`() {
        diagnose(
            """
            interface Decorator { d: number; }
            interface ParameterDeclaration {
                modifiers?: Decorator[];
            }
            interface ValidParameterDeclaration extends ParameterDeclaration {
                modifiers: number;
            }
            """,
        ) should {
            have(any { it.code == 2430 })
        }
    }

    @Test
    fun `derived method implementing a base function-typed property - no TS2430`() {
        diagnose(
            """
            type GetCanonicalFileName = (fileName: string) => string;
            interface BaseHost {
                getCanonicalFileName: GetCanonicalFileName;
            }
            interface DerivedHost extends BaseHost {
                getCanonicalFileName(fileName: string): string;
            }
            """,
        ) should {
            have(none { it.code == 2430 })
        }
    }
}
