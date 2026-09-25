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
import kotlin.test.Test

/**
 * (CHK.172) `emitDeclarationOnly` restricts EMIT, never checking, and `noCheck` is honoured.
 *
 * Before this round an `emitDeclarationOnly` compile took an early return in
 * `TypeScriptCompiler` that built the `Checker` in a `declarationOnly` WHITELIST mode —
 * `checkSpine` never ran, so every assignability / argument / property / arity / flow / unused
 * check was silent (a library that bundles with esbuild and uses the compiler only for `.d.ts`
 * output got a no-op checker). The multi-file branch also dropped `.d.ts` inputs from the
 * program. tsgo 7.0.2's checker never reads the option — only `program.go`'s option validation
 * and the output paths do — so the program is now checked exactly as a plain build is, and only
 * the JavaScript output is withheld.
 *
 * `noCheck` was parsed and never read. tsgo's `Program.SkipTypeChecking` answers true for every
 * file under it, so no bind/check diagnostic is reported while syntactic, option and DECLARATION
 * diagnostics survive (`tsc/noCheck/{syntax,semantic,dts}-errors` baselines).
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW over the identical text (`build/scratch-p18203/cells`
 * and `nc`, `strict`, `declaration`, `emitDeclarationOnly`). A row is `line:column code message`.
 * The `-project` twin (`EmitDeclarationOnlyProjectTest`) drives the same through
 * `ProjectCompiler` with a real tsconfig, a `.d.ts` import and a package import.
 */
class EmitDeclarationOnlyChecksTest {

    private val edo = "// @strict: true\n// @target: es2022\n// @module: esnext\n" +
        "// @declaration: true\n// @emitDeclarationOnly: true"

    private val plain = "// @strict: true\n// @target: es2022\n// @module: esnext\n// @declaration: true"

    private fun rows(source: String, directives: String = edo): List<String> =
        diagnose(source, directives = directives, fileName = "index.ts").map { d ->
            "${d.line}:${d.character} ${d.code} ${d.message}"
        }.sorted()

    // ── the checks run under emitDeclarationOnly ────────────────────────────

    @Test
    fun `the c01 repro - an argument and an assignment error are both reported`() {
        val r = rows(
            """
            declare function pn(n: number): void;
            pn("x");
            const n: number = "s";
            export {}
            """
        )
        assert(
            r == listOf(
                "2:4 2345 Argument of type 'string' is not assignable to parameter of type 'number'.",
                "3:7 2322 Type 'string' is not assignable to type 'number'.",
            )
        )
    }

    @Test
    fun `control - the same text without emitDeclarationOnly reports the same rows`() {
        val src = """
            declare function pn(n: number): void;
            pn("x");
            const n: number = "s";
            export {}
            """
        assert(rows(src, plain) == rows(src, edo))
        assert(rows(src, plain).size == 2)
    }

    @Test
    fun `a missing property is reported`() {
        val r = rows(
            """
            const o = { a: 1 };
            export const v = o.b;
            """
        )
        assert(r == listOf("2:20 2339 Property 'b' does not exist on type '{ a: number; }'."))
    }

    @Test
    fun `an arity error is reported`() {
        val r = rows(
            """
            function f(a: number) { return a; }
            export const v = f(1, 2);
            """
        )
        assert(r == listOf("2:23 2554 Expected 1 arguments, but got 2."))
    }

    @Test
    fun `an implicit any parameter is reported`() {
        val r = rows("export function f(x) { return x; }")
        assert(r == listOf("1:19 7006 Parameter 'x' implicitly has an 'any' type."))
    }

    @Test
    fun `a block-scoped redeclaration is reported at both declarations`() {
        val r = rows(
            """
            let a = 1;
            let a = 2;
            export {}
            """
        )
        assert(
            r == listOf(
                "1:5 2451 Cannot redeclare block-scoped variable 'a'.",
                "2:5 2451 Cannot redeclare block-scoped variable 'a'.",
            )
        )
    }

    @Test
    fun `an unused local is reported under noUnusedLocals`() {
        val r = rows(
            "export function f() { const unused = 1; return 2; }",
            "$edo\n// @noUnusedLocals: true",
        )
        assert(r == listOf("1:29 6133 'unused' is declared but its value is never read."))
    }

    @Test
    fun `a dts input stays in the program - its default export's type is checked`() {
        // The old multi-file branch dropped every `.d.ts` from the program, so `d` was
        // unresolved and the TS2322 below could never be computed.
        val d = diagnose(
            """
            // @filename: lib.d.ts
            declare const x: string;
            export default x;
            // @filename: index.ts
            import d from "./lib";
            export const v: number = d;
            """,
            directives = edo,
        )
        val r = d.map { "${it.fileName?.substringAfterLast('/')} ${it.line}:${it.character} ${it.code} ${it.message}" }
        assert(r == listOf("index.ts 2:14 2322 Type 'string' is not assignable to type 'number'."))
    }

    // ── the emit channel keeps its shape ────────────────────────────────────

    @Test
    fun `emit channel - no JavaScript is produced and every input but tsconfig is echoed`() {
        val result = TypeScriptCompiler().compile(
            "$edo\n" + """
            // @filename: lib.d.ts
            export declare const x: string;
            // @filename: index.ts
            import { x } from "./lib";
            export const v: string = x;
            """.trimIndent(),
            "index.ts",
        )
        val echoed = result.sourceEchoes.map { it.first.substringAfterLast('/') }
        val js = result.jsOutputs.size
        assert(js == 0)
        assert(echoed == listOf("lib.d.ts", "index.ts"))
        assert(result.diagnostics.isEmpty())
    }

    @Test
    fun `emit channel - a single file produces no JavaScript`() {
        val result = TypeScriptCompiler().compile("$edo\nexport const a = 1;", "index.ts")
        val js = result.jsOutputs.size
        val echoed = result.sourceEchoes.map { it.first }
        assert(js == 0)
        assert(echoed == listOf("index.ts"))
    }

    @Test
    fun `emit channel control - without emitDeclarationOnly JavaScript is produced`() {
        val result = TypeScriptCompiler().compile("$plain\nexport const a = 1;", "index.ts")
        val js = result.jsOutputs.map { it.first }
        assert(js == listOf("index.js"))
    }

    // ── noCheck ─────────────────────────────────────────────────────────────

    @Test
    fun `noCheck - a type error is not reported`() {
        val r = rows("export const a: number = \"not ok\";", "$plain\n// @noCheck: true")
        assert(r.isEmpty())
    }

    @Test
    fun `noCheck - a type error is not reported under emitDeclarationOnly either`() {
        // tsc's own `noCheckDoesNotReportError` fixture.
        val r = rows("export const a: number = \"not ok\";", "$edo\n// @noCheck: true")
        assert(r.isEmpty())
    }

    @Test
    fun `noCheck control - without it the type error is reported`() {
        val r = rows("export const a: number = \"not ok\";", plain)
        assert(r == listOf("1:14 2322 Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `noCheck - a syntax error is still reported`() {
        val r = rows("export const a = \"hello", "$plain\n// @noCheck: true")
        assert(r == listOf("1:24 1002 Unterminated string literal."))
    }

    @Test
    fun `noCheck - a declaration diagnostic is still reported`() {
        // tsgo's `tsc/noCheck/dts-errors` baseline: TS4094 survives `--noCheck`.
        val r = rows("export const a = class { private p = 10; };", "$plain\n// @noCheck: true")
        assert(r == listOf("1:14 4094 Property 'p' of exported anonymous class type may not be private or protected."))
    }

    @Test
    fun `noCheck - an unused ts-expect-error is not reported`() {
        val r = rows(
            """
            // @ts-expect-error
            export const a: number = 1;
            declare function pn(n: number): void;
            pn("x");
            """,
            "$plain\n// @noCheck: true",
        )
        assert(r.isEmpty())
    }

    @Test
    fun `noCheck - an option diagnostic is still reported`() {
        val d = diagnose(
            "export const a: number = \"not ok\";",
            directives = "// @strict: true\n// @noCheck: true\n// @emitDeclarationOnly: true",
        )
        val codes = d.map { it.code }
        assert(codes == listOf(5069))
    }

    // ── a JSDoc typedef is an export of its JS module ───────────────────────

    @Test
    fun `a named import of a JSDoc typedef from a JS module is not TS2305`() {
        // Reached once emitDeclarationOnly stopped bypassing the checker
        // (`reuseTypeAnnotationImportTypeInGlobalThisTypeArgument`); tsgo is silent.
        val d = diagnose(
            """
            // @filename: types.js
            export {};
            /**
             * @typedef {{a: number}} Rec a thing
             */
            // @filename: main.ts
            import { Rec } from './types.js';
            export const r: Rec = { a: 1 };
            """,
            directives = "// @strict: true\n// @allowJs: true\n// @checkJs: true\n// @module: preserve\n// @target: es2015",
        )
        assert(d.none { it.code == 2305 })
    }

    @Test
    fun `control - a name the JS module does not declare is still TS2305`() {
        val d = diagnose(
            """
            // @filename: types.js
            export {};
            /**
             * @typedef {{a: number}} Rec a thing
             */
            // @filename: main.ts
            import { Nope } from './types.js';
            export const r: Nope = { a: 1 };
            """,
            directives = "// @strict: true\n// @allowJs: true\n// @checkJs: true\n// @module: preserve\n// @target: es2015",
        )
        assert(d.count { it.code == 2305 } == 1)
    }
}
