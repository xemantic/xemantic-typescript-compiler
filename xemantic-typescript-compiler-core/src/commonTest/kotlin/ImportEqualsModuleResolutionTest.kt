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
 * (CHK.73)(i): `import x = require("<relative specifier>")` on a project whose file names
 * are PATHS.
 *
 * `resolveModuleSpecifier` matches a specifier against `fileResults` KEYS ((CHK.78)), and
 * on a real on-disk project those are absolute paths — so `"./m"` matched nothing, the
 * alias resolved to NOTHING, and the binding typed `any`. Silently, as always: `any` is
 * legal everywhere. The `ImportDeclaration` arm has carried a directory-relative leg since
 * round 512 and this one did not.
 *
 * **THE FIXTURE NAMES ARE PATH-SHAPED ON PURPOSE.** With flat corpus-style names (`m.ts`)
 * the bare resolver matches `"./m"` by string alone and every pin here is VACUOUS — which
 * is why 183 active corpus case files use this import form and none of them can see the
 * defect, and why the 8 profiles and both library arms contain ZERO of it ((CHK.124): the
 * grid is a CONTROL here, counted, not assumed).
 *
 * A `never` target keeps tsc's literal generalization ((CHK.83)) out of the asserted text.
 * Every expectation was measured against `tools/tsgo-7.0.2/lib/tsc` on the same shapes.
 */
class ImportEqualsModuleResolutionTest {

    private val files = """
        // @Filename: /proj/src/plain.ts
        export const version: string = "1";
        export function helper(n: number): string { return ""; }

        // @Filename: /proj/src/legacy.ts
        function legacy(n: number): string { return ""; }
        namespace legacy { export const inner: string = "1"; }
        export = legacy;

        // @Filename: /proj/src/main.ts
    """.trimIndent() + "\n"

    private fun rows(main: String): List<String> =
        diagnose(files + main, directives = "// @strict: true\n// @module: commonjs")
            .filter { it.code == 2322 }
            .map { it.message }

    private val strNotNever = "Type 'string' is not assignable to type 'never'."

    @Test
    fun `an import-equals of an ordinary module resolves a member`() {
        assert(
            rows("import p = require(\"./plain\");\nconst a: never = p.version;\nexport {};") ==
                listOf(strNotNever)
        )
    }

    @Test
    fun `an import-equals of an ordinary module resolves a member CALL`() {
        assert(
            rows("import p = require(\"./plain\");\nconst a: never = p.helper(1);\nexport {};") ==
                listOf(strNotNever)
        )
    }

    @Test
    fun `an import-equals of an export equals module denotes the target's callable side`() {
        assert(
            rows("import l = require(\"./legacy\");\nconst a: never = l(1);\nexport {};") ==
                listOf(strNotNever)
        )
    }

    @Test
    fun `a namespace import of an export equals module denotes the export target`() {
        // tsc's `resolveExternalModuleSymbol` follows `export =` for a namespace import
        // exactly as for `import = require(...)`; before this the arm built a module
        // object over the file's LOCALS instead, which does not carry the target's call
        // signature. Measured against tsgo 7.0.2.
        assert(
            rows("import * as n from \"./legacy\";\nconst a: never = n(1);\nexport {};") ==
                listOf(strNotNever)
        )
    }

    @Test
    fun `negative control - a correctly typed import-equals member read is silent`() {
        assert(rows("import p = require(\"./plain\");\nconst a: string = p.version;\nexport {};").isEmpty())
    }

    @Test
    fun `negative control - an unresolvable specifier still resolves to nothing`() {
        // The new ladder may only ADD a resolution. A specifier naming no file in the
        // program must keep answering nothing rather than adopting a same-named one.
        assert(rows("import q = require(\"./nowhere\");\nconst a: never = q.version;\nexport {};").isEmpty())
    }

    @Test
    fun `residue - a function-namespace MERGE's static side is still any`() {
        // `l.inner` is declared by the NAMESPACE half of the `export =` target. tsgo
        // reports TS2322 here (measured 2026-09-21); we are silent, because a merged
        // function+namespace symbol's value type is its call signature alone. That is a
        // separate mechanism and the last `export =` gap left.
        assert(rows("import l = require(\"./legacy\");\nconst a: never = l.inner;\nexport {};").isEmpty())
    }
}
