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
 * Round 480: a FRESH object-literal member keeps its literal type against a
 * literal-expecting target member at the per-property ARG leaf — the widened
 * `string` FP'd `type: "file"` against `type: "file"` as 'string' ⊄ 'string'
 * (fourslash organizeImports args).
 */
class FreshLiteralPerPropArgLeafTest {

    private val prelude = """
        interface OrganizeImportsArgs {
            fileName: string;
            type: "file";
        }
        declare function organizeImports(args: OrganizeImportsArgs, extra: number): void;
    """.trimIndent()

    @Test
    fun `fresh literal member matches the literal target member`() {
        diagnose(prelude + """

            export function f(name: string): void {
                organizeImports({ fileName: name, type: "file" }, 1);
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 || it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a wrong literal member still fires`() {
        diagnose(prelude + """

            export function f(name: string): void {
                organizeImports({ fileName: name, type: "directory" }, 1);
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 || it.code == 2345 })
        }
    }
}
