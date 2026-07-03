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

import kotlin.test.Test
import kotlin.test.assertTrue

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

    private fun compile(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")

    private val tok = """
        interface Tok<T> { k: T; }
    """.trimIndent()

    @Test fun explicitUndefinedIsLegalForOptionalReferenceParam() {
        val r = compile("$tok\ndeclare function f(a: number, q?: Tok<number>): void;\nf(1, undefined);\n")
        assertTrue(
            r.diagnostics.none { it.code == 2345 },
            "explicit undefined to an optional param must not error: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun explicitUndefinedIsLegalForDefaultedReferenceParam() {
        val r = compile("$tok\nfunction g(a: number, q: Tok<number> = { k: 1 }): void {}\ng(1, undefined);\n")
        assertTrue(
            r.diagnostics.none { it.code == 2345 },
            "explicit undefined to a defaulted param must not error: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun nullIsStillRejectedForOptionalReferenceParam() {
        val r = compile("$tok\ndeclare function f(a: number, q?: Tok<number>): void;\nf(1, null);\n")
        assertTrue(
            r.diagnostics.any { it.code == 2345 && it.message.contains("'null'") },
            "null is NOT interchangeable with absence — must stay TS2345: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun undefinedIsStillRejectedForRequiredReferenceParam() {
        val r = compile("$tok\ndeclare function h(q: Tok<number>): void;\nh(undefined);\n")
        assertTrue(
            r.diagnostics.any { it.code == 2345 && it.message.contains("'undefined'") },
            "undefined to a REQUIRED param must stay TS2345: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun explicitUndefinedIsLegalForOptionalFunctionTypedParam() {
        val r = compile("declare function j<T>(x: T, cb?: (a: T) => number): void;\nj(1, undefined);\n")
        assertTrue(
            r.diagnostics.none { it.code == 2345 },
            "explicit undefined to an optional fn-typed param must not error: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun newMapWithExplicitTypeArgsYieldsInstanceType() {
        val r = compile("const m = new Map<string, number>();\nm.set(\"a\", 1);\nconst v = m.get(\"a\");\n")
        assertTrue(
            r.diagnostics.none { it.code == 2339 },
            "Map<string, number> instance members must resolve (not MapConstructor): " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun newMapWithoutTypeArgsStillYieldsInstanceType() {
        val r = compile("const m = new Map();\nm.set(\"a\", 1);\n")
        assertTrue(
            r.diagnostics.none { it.code == 2339 },
            "the pre-existing no-type-args construct-sig path must keep working: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }

    @Test fun userDeclaredCtorInterfaceWithExplicitTypeArgs() {
        val r = compile(
            """
            interface Box<T> { val: T; boxed(): T; }
            interface BoxCtor { new(): Box<any>; }
            declare var Box: BoxCtor;
            const b = new Box<number>();
            const x = b.boxed();
            b.val = 1;
            """.trimIndent() + "\n",
        )
        assertTrue(
            r.diagnostics.none { it.code == 2339 },
            "user ctor-interface with explicit args must resolve instance members: " +
                r.diagnostics.joinToString { "TS${it.code} ${it.message}" },
        )
    }
}
