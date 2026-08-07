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
 * Round 479: a `this.prop` assignment nested in a constructor var-decl
 * INITIALIZER (`const js = this.js = new SortedMap(...)` — tsc harness
 * compilerImpl.ts) definitely assigns the property, so TS2564 must not fire;
 * chained `this.a = this.b = x` assigns both.
 */
class CtorVarDeclThisAssignmentTest {

    @Test
    fun `this-assignment nested in a ctor var-decl initializer counts`() {
        diagnose(
            """
            export class C {
                public readonly js: ReadonlyMap<string, string>;
                constructor() {
                    const js = this.js = new Map<string, string>();
                    void js;
                }
            }
            """,
        ) should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `chained this-assignments assign both properties`() {
        diagnose(
            """
            export class C {
                a: number;
                b: number;
                constructor() {
                    this.a = this.b = 1;
                }
            }
            """,
        ) should {
            have(none { it.code == 2564 })
        }
    }

    @Test
    fun `negative control - an unassigned property still fires`() {
        diagnose(
            """
            export class C {
                a: number;
                constructor() {
                    const x = 1;
                    void x;
                }
            }
            """,
        ) should {
            have(any { it.code == 2564 && "'a'" in it.message })
        }
    }
}
