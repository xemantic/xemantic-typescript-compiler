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
 * M1.7 (round 387): two bounded engine bugs found by the self-compile family map.
 *
 * (a) An EXPLICIT `undefined` argument is legal for an OPTIONAL parameter (absent
 * and undefined are interchangeable for parameters — B176's overload-path rule,
 * now honored on the single-signature path too). tsc's factory calls
 * `createX(..., /*questionToken*/ undefined, ...)` drew 65 TS2345 FPs. `null`
 * stays checked — it is NOT interchangeable with absence.
 *
 * (b) `new Map<string, number>()` — a CONSTRUCTOR-INTERFACE callee (`declare var
 * Map: MapConstructor`) with EXPLICIT type args fell through to the constructor
 * interface as the instance type (the interface has no own type params; the
 * generics live on the construct sig's return), so every `m.get`/`m.set` was
 * TS2339 "does not exist on type 'MapConstructor'" (44 sites).
 */
class OptionalParamAndCtorInterfaceTest {

    private fun compile(@Language("typescript") source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")

    private val tok = """
        interface Tok<T> { k: T; }
    """.trimIndent()

    @Test
    fun `an explicit undefined is legal for an optional reference param`() {
        compile("$tok\ndeclare function f(a: number, q?: Tok<number>): void;\nf(1, undefined);\n") should {
            have(diagnostics.none { it.code == 2345 })
        }
    }

    @Test
    fun `an explicit undefined is legal for a defaulted reference param`() {
        compile("$tok\nfunction g(a: number, q: Tok<number> = { k: 1 }): void {}\ng(1, undefined);\n") should {
            have(diagnostics.none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - null is still rejected for an optional reference param`() {
        compile("$tok\ndeclare function f(a: number, q?: Tok<number>): void;\nf(1, null);\n") should {
            have(diagnostics.any { it.code == 2345 && it.message.contains("'null'") })
        }
    }

    @Test
    fun `negative control - undefined is still rejected for a required reference param`() {
        compile("$tok\ndeclare function h(q: Tok<number>): void;\nh(undefined);\n") should {
            have(diagnostics.any { it.code == 2345 && it.message.contains("'undefined'") })
        }
    }

    @Test
    fun `an explicit undefined is legal for an optional function-typed param`() {
        compile("declare function j<T>(x: T, cb?: (a: T) => number): void;\nj(1, undefined);\n") should {
            have(diagnostics.none { it.code == 2345 })
        }
    }

    @Test
    fun `a new Map with explicit type args yields the instance type`() {
        compile("const m = new Map<string, number>();\nm.set(\"a\", 1);\nconst v = m.get(\"a\");\n") should {
            have(diagnostics.none { it.code == 2339 })
        }
    }

    @Test
    fun `a new Map without type args still yields the instance type`() {
        compile("const m = new Map();\nm.set(\"a\", 1);\n") should {
            have(diagnostics.none { it.code == 2339 })
        }
    }

    @Test
    fun `a user-declared ctor interface with explicit type args`() {
        compile(
            """
            interface Box<T> { val: T; boxed(): T; }
            interface BoxCtor { new(): Box<any>; }
            declare var Box: BoxCtor;
            const b = new Box<number>();
            const x = b.boxed();
            b.val = 1;
            """.trimIndent() + "\n",
        ) should {
            have(diagnostics.none { it.code == 2339 })
        }
    }
}
