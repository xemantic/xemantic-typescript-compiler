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
 */

package com.xemantic.typescript.compiler

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 435g: `return undefined`/`return null` against an ALIAS whose union
 * body syntactically carries the matching nullish keyword is legal — the
 * resolved union can collapse when enum-member constituents resolve through
 * cross-file merging (tsc's barrel-imported `ResolutionMode = ModuleKind.ESNext
 * | ModuleKind.CommonJS | undefined`, parseResolutionMode ×4), so the return
 * path trusts the syntactic proof (the M1.8 TS2366 rule extended to the
 * return-VALUE path), and `aliasUnionContainsNullishKeyword` falls through an
 * IMPORTED alias's ImportSpecifier symbol to the merged globals' declaration.
 *
 * The discriminating pin is the self-compile A/B (the collapse needs the real
 * cross-file first-touch ordering; a local facsimile resolves cleanly) — this
 * test pins the gate's BOUNDS: it fires only for bare nullish identifier
 * returns, and only when the alias union carries the keyword.
 */
class NullishAliasUnionReturnTest {

    private fun ts2322s(source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")
            .diagnostics.filter { it.code == 2322 }

    /** The parseResolutionMode shape (single-file facsimile). */
    @Test fun returnUndefinedAgainstNullishAliasUnionIsLegal() {
        val diags = ts2322s(
            """
            enum ModuleKind { None = 0, CommonJS = 1, ESNext = 99 }
            type ResolutionMode = ModuleKind.ESNext | ModuleKind.CommonJS | undefined;
            function parse(mode: string | undefined): ResolutionMode {
                if (!mode) {
                    return undefined;
                }
                if (mode === "import") {
                    return ModuleKind.ESNext;
                }
                return undefined;
            }
            """.trimIndent()
        )
        assertTrue(diags.isEmpty(), "expected no TS2322, got: $diags")
    }

    /** NEGATIVE control: a CONCRETE mismatched return against an alias union
     *  still fires — the gate returns early only for bare nullish identifiers. */
    @Test fun concreteMismatchAgainstAliasUnionStillFires() {
        val diags = ts2322s(
            """
            type MaybeString = string | undefined;
            function f(): MaybeString {
                return 42;
            }
            """.trimIndent()
        )
        assertTrue(diags.isNotEmpty(), "expected TS2322 for number vs string | undefined")
    }
}
