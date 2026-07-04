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
 * M2.2 (round 393): under `useRealLibs` the library utility aliases
 * (`Pick`/`Omit`/`Readonly`/…) resolve to a REAL lib `TypeAlias` symbol, so the generic
 * alias-substitution path expands their definitions — `Omit<T,K> = Pick<T, Exclude<keyof
 * T,K>>` → the non-homomorphic mapped type `{ [P in K]: T[P] }` — and DROPS the optional
 * / readonly modifiers (our mapped-type evaluator doesn't yet treat `[P in K extends keyof
 * T]` as homomorphic). The embedded lib doesn't declare these names, so it hit the
 * modifier-preserving `materialize*` dispatch (symbol==null). The fix routes a lib-only
 * utility symbol through the same materializers so both paths agree.
 *
 * These pins mirror the `omitTypeHelperModifiers01` / `omitTypeTestErrors01` corpus
 * shapes under real libs, plus a user-shadow negative control (a local `type Omit<…>`
 * must win, so the materializer must NOT hijack it).
 */
class RealLibsUtilityModifiersTest {

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(
            "// @useRealLibs: true\n// @strict: true\n// @target: es2015\n$body",
            "t.ts",
        ).diagnostics

    @Test
    fun `Omit preserves the readonly modifier under real libs`() {
        // `readonly c` survives Omit — writing it is TS2540. If the modifier were dropped
        // (the non-homomorphic mapped-type expansion), no error would fire.
        val d = diags(
            """
            type A = { a: number; b?: string; readonly c: boolean; };
            type B = Omit<A, 'a'>;
            function f(x: B) { x.c = true; }
            """.trimIndent(),
        )
        assertTrue(
            d.any { it.code == 2540 },
            "Omit must preserve `readonly c` → TS2540 on write; got: " + d.joinToString { "TS${it.code}" },
        )
    }

    @Test
    fun `Omit preserves the optional modifier under real libs`() {
        // `b?: string` survives Omit as optional, so `x.b = undefined` is legal (b is
        // `string | undefined`). A dropped `?` would make b required `string` → a wrong
        // TS2322 'undefined' is not assignable to 'string'.
        val d = diags(
            """
            type A = { a: number; b?: string; readonly c: boolean; };
            type B = Omit<A, 'a'>;
            function f(x: B) { x.b = undefined; }
            """.trimIndent(),
        )
        assertTrue(
            d.none { it.code == 2322 },
            "Omit must keep `b?` optional so `x.b = undefined` is legal; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `Omit removes the omitted key under real libs`() {
        // `Omit<Foo, "c">` must not have `c` → accessing it is TS2339.
        val d = diags(
            """
            interface Foo { a: string; b: number; c: boolean; }
            type Bar = Omit<Foo, "c">;
            function g(bar: Bar) { return bar.c; }
            """.trimIndent(),
        )
        assertTrue(
            d.any { it.code == 2339 },
            "Omit<Foo,'c'> must drop `c` → TS2339; got: " + d.joinToString { "TS${it.code}" },
        )
    }

    @Test
    fun `a user Omit shadow is NOT hijacked by the materializer`() {
        // NEGATIVE control: a local `type Omit<…>` has a user declaration (not in
        // builtinLibDecls) → isBuiltinUtilityAlias returns false → the user definition
        // wins. Here the user Omit is the identity `{ [P in keyof T]: T[P] }`, so `c`
        // survives and `bar.c` is legal — NO TS2339.
        val d = diags(
            """
            type Omit<T, K> = { [P in keyof T]: T[P] };
            interface Foo { a: string; c: boolean; }
            type Bar = Omit<Foo, "c">;
            function g(bar: Bar) { return bar.c; }
            """.trimIndent(),
        )
        assertTrue(
            d.none { it.code == 2339 },
            "a user Omit shadow must win (materializer must not hijack it); got: " +
                d.joinToString { "TS${it.code}" },
        )
    }
}
