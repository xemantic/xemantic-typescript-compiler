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
 * (CHK.73): a function MERGED with a same-named namespace carries that namespace's
 * exports as PROPERTIES of its VALUE TYPE — tsc's `typeof f` for `function f() {}` plus
 * `namespace f { export const v }` is `{ (): void; v: … }`.
 *
 * Without it the merged symbol's value type was its call signature ALONE, so the
 * namespace side was reachable only through the SYNTACTIC qualified-name path. That is
 * why a same-file merge and a NAMED import of one already resolved `f.v` while the same
 * member through a MODULE OBJECT (`m.f.v`) or an `export =` surface
 * (`import f = require("./m"); f.v`) did not — and the second is how the DefinitelyTyped
 * ecosystem publishes a callable CommonJS package, so it is a typed-interop gap.
 *
 * **FILE-LEVEL MERGES ONLY, AND THAT IS A MEASUREMENT.** This binder merges every
 * same-named `namespace` block of one container into ONE `exports` table, including
 * blocks carrying no `export` modifier. Attaching that table for a namespace nested
 * inside another one LOSES two rows of `mergedDeclarations3` (measured: 1 mismatch of
 * 8,725 on the corpus screen), where tsgo reports TS2339 for the unexported blocks'
 * members. The over-merge is a BINDER defect; the last pin here records it.
 *
 * Every expectation was measured against `tools/tsgo-7.0.2/lib/tsc`; a `never` target
 * keeps tsc's literal generalization ((CHK.83)) out of the asserted text.
 */
class MergedNamespaceStaticsTest {

    private val files = """
        // @Filename: /proj/src/local.ts
        export function loc(n: number): string { return ""; }
        export namespace loc { export const version: string = "1"; }

        // @Filename: /proj/src/main.ts
    """.trimIndent() + "\n"

    private fun rows(main: String): List<String> =
        diagnose(files + main, directives = "// @strict: true\n// @module: commonjs")
            .filter { it.code == 2322 }
            .map { it.message }

    private val strNotNever = "Type 'string' is not assignable to type 'never'."

    @Test
    fun `a merged namespace member reached through a MODULE OBJECT is typed`() {
        assert(
            rows("import * as m from \"./local\";\nconst a: never = m.loc.version;\nexport {};") ==
                listOf(strNotNever)
        )
    }

    @Test
    fun `a named import of a merge keeps resolving its namespace member - control`() {
        assert(
            rows("import { loc } from \"./local\";\nconst a: never = loc.version;\nexport {};") ==
                listOf(strNotNever)
        )
    }

    @Test
    fun `the callable side survives the attachment`() {
        assert(
            rows("import { loc } from \"./local\";\nconst a: never = loc(1);\nexport {};") ==
                listOf(strNotNever)
        )
    }

    @Test
    fun `negative control - a member the namespace does not declare still reports TS2339`() {
        val d = diagnose(
            files + "import { loc } from \"./local\";\nconst a = loc.nope;\nexport {};",
            directives = "// @strict: true\n// @module: commonjs",
        ).filter { it.code == 2339 }
        assert(d.isNotEmpty())
    }

    @Test
    fun `the FILE-LEVEL restriction keeps a nested namespace's unexported block invisible`() {
        // THE DISCRIMINATING SHAPE FOR THE CONTAINMENT, and the reason it exists. This
        // binder merges every same-named `namespace` block of one container into ONE
        // `exports` table, unexported blocks included — so attaching that table for a
        // NESTED namespace makes `M.foo.x` legal, where tsgo reports TS2339 and the
        // syntactic qualified-name path (which respects export-ness) already answers
        // correctly. Measured against tsgo 7.0.2: exactly this one row, `M.foo.y` silent.
        // `mergedDeclarations3` is the corpus baseline carrying the same pair, and it is
        // the instrument that found the over-reach — 1 mismatch of 8,725.
        //
        // The fixture is FLAT-NAMED on purpose: through a path-shaped `-project` layout
        // the same shape resolves down another route and the pin cannot fail.
        val d = diagnose(
            """
            namespace M {
                export function foo() {}
            }
            namespace M {
                namespace foo { export var x = 1; }
            }
            namespace M {
                export namespace foo { export var y = 2; }
            }
            M.foo.x;
            M.foo.y;
            """.trimIndent(),
            directives = "// @target: es2015",
        ).filter { it.code == 2339 }
        assert(d.map { it.message } == listOf("Property 'x' does not exist on type 'typeof foo'."))
    }
}
