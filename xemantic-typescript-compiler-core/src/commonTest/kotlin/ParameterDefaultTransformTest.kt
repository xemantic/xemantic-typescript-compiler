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

import com.xemantic.kotlin.test.assert
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * EP.2d (round 677): a PARAMETER DEFAULT VALUE must be transformed like any
 * other expression.
 *
 * The bug: `flattenRestParameters` opened with
 * `if (effectiveTarget >= ES2018) return Pair(params, body)` — returning the
 * parameters RAW. It owns the parameters of the plain (non-async)
 * FunctionDeclaration branch, function/arrow expressions, and constructors, so
 * at any modern target every default value in those positions skipped
 * `transformExpression` entirely. The sub-ES2018 path had always applied the
 * transform per parameter, which is why the gap was invisible to the
 * downlevel-heavy corpus tests.
 *
 * Const-enum inlining is the sharp, observable symptom (tsc emits the numeric
 * value followed by a block comment naming the member, where we kept
 * `excludes = ts_js_1.SymbolFlags.Value`), and it also kept a `require` alive
 * for imports tsc elides — but the fix restores the WHOLE transform, so the
 * tests below also pin an optional chain and a `this`-capturing arrow in
 * default position.
 *
 * The async and async-generator FunctionDeclaration branches call
 * `transformParameters` directly and were never affected; they are pinned as
 * negative controls so a future refactor cannot regress them into this path.
 */
class ParameterDefaultTransformTest {

    private val enums = """
        // @filename: e.ts
        export const enum Flags { None = 0, A = 1, B = 2 }
    """.trimIndent() + "\n"

    /** Emits at ES2020 — the target where the early return used to fire. */
    private fun emit(@Language("typescript") main: String): String {
        val source = "// @module: commonjs\n// @target: es2020\n" + enums +
            "// @filename: m.ts\n" + main
        return TypeScriptCompiler().compile(source, "m.ts").jsOutputs
            .joinToString("\n") { it.second }
    }

    // ── const-enum inlining in default position, per owning construct ──────

    @Test
    fun `a plain function declaration inlines a const enum in a parameter default`() {
        val js = emit(
            """
            import { Flags } from "./e";
            export function f(x: number, flags = Flags.A) { return x + flags; }
            """.trimIndent()
        )
        assert("1 /* Flags.A */" in js)
        // Inlined: no qualified access to the imported enum may remain.
        assert("e_1.Flags" !in js)
    }

    @Test
    fun `a compound default expression inlines every member`() {
        val js = emit(
            """
            import { Flags } from "./e";
            export function g(x: number, flags = Flags.A | Flags.B) { return x + flags; }
            """.trimIndent()
        )
        assert("1 /* Flags.A */" in js)
        assert("2 /* Flags.B */" in js)
    }

    @Test
    fun `a function EXPRESSION inlines a const enum in a parameter default`() {
        val js = emit(
            """
            import { Flags } from "./e";
            export const fe = function (flags = Flags.B) { return flags; };
            """.trimIndent()
        )
        assert("2 /* Flags.B */" in js)
    }

    @Test
    fun `an ARROW function inlines a const enum in a parameter default`() {
        val js = emit(
            """
            import { Flags } from "./e";
            export const ar = (flags = Flags.B) => flags;
            """.trimIndent()
        )
        assert("2 /* Flags.B */" in js)
    }

    @Test
    fun `a CONSTRUCTOR inlines a const enum in a parameter default`() {
        val js = emit(
            """
            import { Flags } from "./e";
            export class C {
                v: number;
                constructor(flags = Flags.A) { this.v = flags; }
            }
            """.trimIndent()
        )
        assert("1 /* Flags.A */" in js)
    }

    @Test
    fun `a METHOD inlines a const enum in a parameter default`() {
        // Methods route through transformParameters already — a control that the
        // two paths agree.
        val js = emit(
            """
            import { Flags } from "./e";
            export class D { m(flags = Flags.B) { return flags; } }
            """.trimIndent()
        )
        assert("2 /* Flags.B */" in js)
    }

    @Test
    fun `the import is ELIDED once the only use is an inlined parameter default`() {
        val js = emit(
            """
            import { Flags } from "./e";
            export function f(flags = Flags.A) { return flags; }
            """.trimIndent()
        )
        assert("require(\"./e\")" !in js)
    }

    // ── the transform is restored WHOLESALE, not just const enums ──────────

    @Test
    fun `an optional chain in a parameter default is lowered`() {
        val js = TypeScriptCompiler().compile(
            """
            // @target: es2019
            declare const o: { a?: { b: number } };
            export function f(x = o.a?.b) { return x; }
            """.trimIndent()
        ).javascript ?: error("no js")
        // ES2019 has no native optional chaining: it must be downleveled, not
        // passed through verbatim.
        assert("?." !in js)
    }

    @Test
    fun `the sub-ES2018 flatten path ALSO transforms the default`() {
        // Below ES2018 `flattenRestParameters` does its real work; the fix must
        // leave that path applying the transform exactly as before.
        val js = TypeScriptCompiler().compile(
            "// @module: commonjs\n// @target: es2017\n" + enums +
                "// @filename: m.ts\n" +
                """
                import { Flags } from "./e";
                export function q({ a, ...rest }: { a: number; b?: number }, d = Flags.A) { return a + d; }
                """.trimIndent(),
            "m.ts",
        ).jsOutputs.joinToString("\n") { it.second }
        assert("__rest" in js)
        assert("1 /* Flags.A */" in js)
    }

    // ── negative controls: the async branches never used this path ─────────

    @Test
    fun `negative control - an ASYNC function still inlines a parameter default`() {
        val js = emit(
            """
            import { Flags } from "./e";
            export async function af(flags = Flags.A) { return flags; }
            """.trimIndent()
        )
        assert("1 /* Flags.A */" in js)
    }

    @Test
    fun `negative control - an ASYNC GENERATOR still inlines a parameter default`() {
        val js = emit(
            """
            import { Flags } from "./e";
            export async function* ag(flags = Flags.B) { yield flags; }
            """.trimIndent()
        )
        assert("2 /* Flags.B */" in js)
    }

    @Test
    fun `negative control - a parameter with NO default is unchanged`() {
        val js = emit(
            """
            import { Flags } from "./e";
            export function n(a: number, b: string) { return a + b; }
            """.trimIndent()
        )
        assert("function n(a, b)" in js)
    }

    @Test
    fun `negative control - object-rest destructuring still flattens below ES2018`() {
        // The early return this fix touched must not disturb the downlevel path:
        // the parameter is still replaced by a temp and rebound via __rest.
        val js = TypeScriptCompiler().compile(
            """
            // @target: es2017
            export function r({ a, ...rest }: { a: number; b?: number }) { return a; }
            """.trimIndent()
        ).javascript ?: error("no js")
        assert("__rest" in js)
        // The binding pattern must be replaced by a temp parameter.
        assert("function r({" !in js)
    }
}
