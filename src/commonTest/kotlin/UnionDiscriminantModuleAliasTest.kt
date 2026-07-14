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
 * INV.3(d) round 512 (indirectDiscriminantAndExcessProperty): the
 * union-discriminant object-literal arg check
 * ([Checker.tryEmitUnionDiscriminantPropMismatch]) resolves the param's
 * type-alias annotation and the arg's value identifier under the NODE's
 * owning file — a MODULE file's top-level `type`/`let` is no longer in the
 * merged [Checker.globals] after the INV.3(d) retire, so the legacy consult
 * silently bailed and the whole emitter died in module files.
 */
class UnionDiscriminantModuleAliasTest {

    private val moduleDecls = """
        // @strict: false
        // @module: commonjs
        // @filename: disc.ts
        export type Blah =
            | { type: "foo", abc: string }
            | { type: "bar", xyz: number };

        declare function thing(blah: Blah): void;
    """.trimIndent()

    @Test
    fun `widened let discriminant in a module file fires TS2322 against the literal union`() {
        diagnose(
            moduleDecls + """

            let foo1 = "foo";
            thing({
                type: foo1,
                abc: "hello!"
            });
            """.trimIndent(),
            directives = "",
        ) should {
            have(any { it.code == 2322 && it.message == "Type 'string' is not assignable to type '\"foo\" | \"bar\"'." })
        }
    }

    @Test
    fun `negative control - a matching literal discriminant in a module file stays clean`() {
        diagnose(
            moduleDecls + """

            thing({
                type: "foo",
                abc: "hello!"
            });
            """.trimIndent(),
            directives = "",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `annotated string value in a module file fires too`() {
        diagnose(
            moduleDecls + """

            declare const s: string;
            thing({
                type: s,
                abc: "hello!"
            });
            """.trimIndent(),
            directives = "",
        ) should {
            have(any { it.code == 2322 && it.message == "Type 'string' is not assignable to type '\"foo\" | \"bar\"'." })
        }
    }
}
