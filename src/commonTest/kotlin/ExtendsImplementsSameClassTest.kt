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
 * Round 479: `class C extends B implements B` — implementing the class it
 * EXTENDS is trivially satisfied (every member is inherited); the TS2720
 * walker's missing-member collection reads OWN members only, so it FP'd on
 * harnessLanguageService's NativeLanguageServiceHost.
 */
class ExtendsImplementsSameClassTest {

    @Test
    fun `implements of the extended class draws no TS2720`() {
        diagnose(
            """
            class Base {
                open(file: string): void { void file; }
                close(): void {}
            }
            export class Derived extends Base implements Base {
                extra(): number { return 1; }
            }
            """,
        ) should {
            have(none { it.code == 2720 })
        }
    }

    @Test
    fun `negative control - implementing an unrelated class with missing members still fires`() {
        diagnose(
            """
            class Contract {
                open(file: string): void { void file; }
                close(): void {}
            }
            export class Impl implements Contract {
                other(): number { return 1; }
            }
            """,
        ) should {
            have(any { it.code == 2720 })
        }
    }
}
