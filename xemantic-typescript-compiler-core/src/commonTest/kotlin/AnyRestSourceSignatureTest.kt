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
 * Round 480: a source signature whose last param is a rest with an
 * any/unresolvable ELEMENT accepts any target params —
 * `(...args: any[]) => void` is assignable to `(project: Project) => void`
 * (tsc harness incrementalUtils.ts); the B196 element expansion previously
 * fell through to comparing the ARRAY type contravariantly.
 */
class AnyRestSourceSignatureTest {

    @Test
    fun `any-rest source fn assigns to a specific fn member`() {
        diagnose(
            """
            interface Project { name: string; }
            interface Service {
                verifyProgram: (project: Project) => void;
            }
            declare function withCallbacks(cb: (project: Project) => void): (...args: any[]) => void;
            declare function verifyProgram(project: Project): void;
            export function install(service: Service): void {
                service.verifyProgram = withCallbacks(verifyProgram);
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a typed rest element still fails an incompatible target`() {
        diagnose(
            """
            interface Service {
                verify: (n: number) => void;
            }
            declare const src: (...args: string[]) => void;
            export function install(service: Service): void {
                service.verify = src;
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }
}
