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
 * INV.3(c)(iii) phase 1 (round 506): the protected-member walker cluster
 * (pw/pmr/pm — TS2445/TS2446) keys its merged-globals fallbacks by the name
 * IDENTIFIER node's owning file. A module file referencing a class it never
 * imports no longer resolves it through a foreign module file's leaked local
 * (real tsc sees TS2304 there — a protected-access verdict about an invisible
 * class is always bogus), while same-file, imported, and script-file
 * resolution is unchanged.
 */
class Inv3ProtectedNodeKeyTest {

    private val typesTs = """
        // @filename: types.ts
        export class Secret {
            protected data: number = 1;
            protected static token: string = "t";
        }
    """

    @Test
    fun `an UNIMPORTED foreign class's protected static no longer draws TS2445`() {
        diagnose(
            typesTs + """

            // @filename: leak.ts
            export function f(): string {
                return Secret.token;
            }
            """
        ) should {
            have(none { it.code == 2445 })
        }
    }

    @Test
    fun `a param annotated with an UNIMPORTED foreign class no longer draws TS2445`() {
        diagnose(
            typesTs + """

            // @filename: leak.ts
            export function g(s: Secret): number {
                return s.data;
            }
            """
        ) should {
            have(none { it.code == 2445 })
        }
    }

    @Test
    fun `negative control - a same-file protected access keeps drawing TS2445`() {
        diagnose(
            """
            export class C {
                protected d: number = 1;
            }
            export function f(c: C): number {
                return c.d;
            }
            """
        ) should {
            have(any { it.code == 2445 && it.message.contains("'d' is protected") })
        }
    }

    @Test
    fun `negative control - a cross-file SCRIPT class keeps drawing TS2445`() {
        diagnose(
            """
            // @filename: a.ts
            class C {
                protected d: number = 1;
            }

            // @filename: b.ts
            function f(c: C): number {
                return c.d;
            }
            """
        ) should {
            have(any { it.code == 2445 && it.message.contains("'d' is protected") })
        }
    }

    @Test
    fun `negative control - a same-file protected STATIC access keeps drawing TS2445`() {
        diagnose(
            """
            export class C {
                protected static t: string = "x";
            }
            export function f(): string {
                return C.t;
            }
            """
        ) should {
            have(any { it.code == 2445 && it.message.contains("'t' is protected") })
        }
    }
}
