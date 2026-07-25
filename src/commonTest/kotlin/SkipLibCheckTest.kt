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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M4.9 (round 681): `skipLibCheck` drops SEMANTIC diagnostics reported inside
 * declaration files.
 *
 * The option was parsed and then never consulted. Nothing noticed while `.d.ts`
 * files came only from the corpus and the bundled libs; it became visible the
 * moment M4.8 let `@types` packages into the program, because `@types/node` is
 * ~70 declaration files and checking them reported 15 TS7008s against
 * DefinitelyTyped's own code in a project that had explicitly asked not to.
 *
 * Two boundaries matter and are pinned below: the default is OFF (a `.d.ts`
 * error still reports), and the flag must not silence a diagnostic in a normal
 * `.ts` file.
 */
class SkipLibCheckTest {

    /** A `.d.ts` carrying a semantic error, plus a clean consumer. */
    private fun sources(skip: Boolean): String = buildString {
        append("// @module: commonjs\n")
        append("// @noImplicitAny: true\n")
        if (skip) append("// @skipLibCheck: true\n")
        append("// @filename: lib.d.ts\n")
        append("export declare function bad(cb: (a, b) => void): void;\n")
        append("// @filename: main.ts\n")
        append("import { bad } from \"./lib\";\nexport const r = bad;\n")
    }

    private fun diags(skip: Boolean): List<Diagnostic> =
        TypeScriptCompiler().compile(sources(skip), "main.ts").diagnostics

    @Test
    fun `skipLibCheck true suppresses semantic errors inside a d-ts`() {
        val ds = diags(skip = true)
        assertTrue(
            ds.none { it.fileName?.endsWith(".d.ts") == true },
            "no diagnostic may come from a declaration file, got: $ds",
        )
    }

    @Test
    fun `default is OFF - the same d-ts error still reports`() {
        // The control that makes the test above meaningful.
        val ds = diags(skip = false)
        assertTrue(
            ds.any { it.fileName?.endsWith(".d.ts") == true },
            "without skipLibCheck the declaration error must report, got: $ds",
        )
    }

    @Test
    fun `skipLibCheck does not silence errors in a normal ts file`() {
        val src = """
            // @module: commonjs
            // @skipLibCheck: true
            // @filename: main.ts
            export const n: number = "not a number";
        """.trimIndent()
        val ds = TypeScriptCompiler().compile(src, "main.ts").diagnostics
        assertEquals(1, ds.count { it.code == 2322 }, "got: $ds")
    }

    @Test
    fun `skipLibCheck leaves a clean project clean`() {
        val src = """
            // @module: commonjs
            // @skipLibCheck: true
            // @filename: lib.d.ts
            export declare function ok(a: number): string;
            // @filename: main.ts
            import { ok } from "./lib";
            export const r: string = ok(1);
        """.trimIndent()
        val ds = TypeScriptCompiler().compile(src, "main.ts").diagnostics
        assertEquals(0, ds.size, "got: $ds")
    }
}
