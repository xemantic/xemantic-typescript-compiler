/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only
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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (KIR.EMIT.1) `rewriteRelativeImportExtensions` — `./e.ts` -> `./e.js`.
 *
 * Our ESM output was not runnable on Node AS EMITTED: Node resolves a specifier
 * LITERALLY, so `'./e.ts'` is refused however well the program type-checked,
 * and the option that tsc 5.7 added for exactly this was parsed and then
 * ignored by the emit. Invisible to every gate we own — the corpus pins emitted
 * BYTES against tsc's baselines and no baseline asks whether Node can load the
 * result — so these are the pins.
 *
 * The population is tsc's own `shouldRewriteModuleSpecifier`: RELATIVE, not a
 * declaration file name, carrying a TypeScript extension. Each of those three
 * conditions gets a negative control below, because each of them being wrong
 * produces a specifier that resolves to nothing at run time and to no
 * diagnostic at compile time.
 */
class RewriteRelativeImportExtensionsTest {

    private val target = """
        // @filename: e.ts
        export const a = 1;
        export const b = 2;
    """.trimIndent() + "\n"

    private fun emit(
        @Language("typescript") main: String,
        directives: String = "// @module: esnext\n// @target: es2020\n" +
            "// @allowImportingTsExtensions: true\n// @rewriteRelativeImportExtensions: true\n",
    ): String = TypeScriptCompiler()
        .compile(directives + target + "// @filename: m.ts\n" + main, "m.ts")
        .jsOutputs.joinToString("\n") { it.second }

    private val off = "// @module: esnext\n// @target: es2020\n" +
        "// @allowImportingTsExtensions: true\n"

    // ── the option itself ─────────────────────────────────────────────────

    @Test
    fun `an ESM import specifier loses its TypeScript extension`() {
        val js = emit(
            """
            import { a } from "./e.ts";
            console.log(a);
            """.trimIndent()
        )
        assert("\"./e.js\"" in js)
        assert(".ts\"" !in js)
    }

    @Test
    fun `negative control - without the option the specifier is emitted verbatim`() {
        val js = emit(
            """
            import { a } from "./e.ts";
            console.log(a);
            """.trimIndent(),
            directives = off
        )
        assert("\"./e.ts\"" in js)
    }

    @Test
    fun `a re-export specifier is rewritten too`() {
        val js = emit(
            """
            export { a } from "./e.ts";
            """.trimIndent()
        )
        assert("\"./e.js\"" in js)
    }

    @Test
    fun `a CommonJS require specifier is rewritten`() {
        val js = emit(
            """
            import { a } from "./e.ts";
            console.log(a);
            """.trimIndent(),
            directives = "// @module: commonjs\n// @target: es2020\n" +
                "// @allowImportingTsExtensions: true\n" +
                "// @rewriteRelativeImportExtensions: true\n"
        )
        assert("require(\"./e.js\")" in js)
    }

    @Test
    fun `a dynamic import specifier is rewritten`() {
        // The one module specifier that is an EXPRESSION, so the one position
        // the statement-level post-pass cannot reach.
        val js = emit(
            """
            export async function load(): Promise<number> {
              const m = await import("./e.ts");
              return m.a;
            }
            """.trimIndent()
        )
        assert("\"./e.js\"" in js)
    }

    // ── the three conditions of the population, each as a control ─────────

    @Test
    fun `negative control - a bare specifier keeps its extension`() {
        // Not relative: the module names its own output extension, and we do
        // not know what it is.
        val js = emit(
            """
            import { a } from "pkg/e.ts";
            console.log(a);
            """.trimIndent()
        )
        assert("\"pkg/e.ts\"" in js)
    }

    @Test
    fun `negative control - a declaration file specifier keeps its extension`() {
        // `./types.d.ts` has no JavaScript counterpart to point at.
        val js = emit(
            """
            import { a } from "./e.ts";
            import "./types.d.ts";
            console.log(a);
            """.trimIndent()
        )
        assert("\"./types.d.ts\"" in js)
        assert("\"./e.js\"" in js)
    }

    @Test
    fun `negative control - a specifier that already names JavaScript is untouched`() {
        val js = emit(
            """
            import { a } from "./e.ts";
            import "./legacy.js";
            console.log(a);
            """.trimIndent()
        )
        assert("\"./legacy.js\"" in js)
    }

    // ── the module-format extensions keep their letter ────────────────────

    @Test
    fun `mts becomes mjs and cts becomes cjs`() {
        val js = emit(
            """
            import { a } from "./e.ts";
            import "./esm.mts";
            import "./cjs.cts";
            console.log(a);
            """.trimIndent()
        )
        assert("\"./esm.mjs\"" in js)
        assert("\"./cjs.cjs\"" in js)
    }

    @Test
    fun `a directory named like a declaration file does not make one`() {
        // tsc looks at the BASE NAME, so the `.d.` on the way to an ordinary
        // module is not the module's own.
        val js = emit(
            """
            import { a } from "./e.ts";
            import "./x.d.y/m.ts";
            console.log(a);
            """.trimIndent()
        )
        assert("\"./x.d.y/m.js\"" in js)
    }

}
