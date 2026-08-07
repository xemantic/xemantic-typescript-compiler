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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * M1.10 (round 388): the `-readonly` mapped modifier STRIPS readonly — tsc's
 * `Mutable<T> = { -readonly [K in keyof T]: T[K] }` idiom. The parser used to
 * consume the minus sign without recording it, and a homomorphic mapped member
 * carries its SOURCE declaration (whose `readonly` modifier the predicate scan
 * saw) — so `(node as Mutable<SourceFile>).flags |= x` FP'd TS2540 ×64 on tsc's
 * own sources. `MappedType.readonlyMinus` + the `mappedMutableMemberIds`
 * side-channel (checked FIRST by the readonly predicates) fix it.
 */
class MutableMappedTypeTest {

    private fun compile(@Language("typescript") source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")

    private val decls = """
        interface SF { readonly flags: number; readonly text: string; }
        type Mutable<T extends object> = { -readonly [K in keyof T]: T[K] };
        declare const sf: SF;
    """.trimIndent()

    @Test
    fun `a write through a Mutable cast is legal`() {
        compile("$decls\n(sf as Mutable<SF>).flags |= 4;\n(sf as Mutable<SF>).text = \"x\";\n") should {
            have(diagnostics.none { it.code == 2540 })
        }
    }

    @Test
    fun `a write through a Mutable-annotated var is legal`() {
        compile("$decls\ndeclare const m: Mutable<SF>;\nm.flags = 1;\n") should {
            have(diagnostics.none { it.code == 2540 })
        }
    }

    @Test
    fun `negative control - a direct readonly write still rejects`() {
        compile("$decls\nsf.flags = 1;\n") should {
            have(diagnostics.any { it.code == 2540 })
        }
    }

    @Test
    fun `a plain homomorphic mapped type keeps the source readonly`() {
        // WITHOUT the minus, a bare `readonly` mapped type ADDS readonly.
        compile(
            """
            interface P { x: number; }
            type Frozen<T> = { readonly [K in keyof T]: T[K] };
            declare const f: Frozen<P>;
            f.x = 1;
            """.trimIndent(),
        ) should {
            have(diagnostics.any { it.code == 2540 })
        }
    }
}
