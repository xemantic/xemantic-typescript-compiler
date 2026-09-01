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
 * (INC.87)(a) The POSITIVE control for `checkMissingTypesReferenceExports`' package.json
 * scan, which round 949 put behind an `endsWith("/package.json")` pre-gate.
 *
 * ## Why a positive control is the pin that matters here
 *
 * The gated regex is rooted at an alternation, so it carries no Boyer-Moore literal and was
 * attempted at every position of every file name in the program — 3.30 ms of a 4.51 ms
 * `FrontEnd.POST_DIAGS` on a 2,401-file project that contains no `node_modules` at all, i.e.
 * the whole cost was paid to answer NO. The pre-gate is exact (the pattern's own tail is
 * `/package\.json$`, so a name it can match necessarily ends with `/package.json`), which
 * means **no ABSENCE assertion can discriminate it**: on the fixture the row is measured on,
 * a broken gate and a correct one both report nothing.
 *
 * What a wrong gate breaks is the case where the diagnostic SHOULD fire, and that is the
 * only thing asserted here. Two ablations redden it and are the reason it exists: narrowing
 * the gate to a name-relative form (`startsWith("node_modules/")`, which every absolute path
 * in a real project fails) and misspelling the literal both silently return an empty
 * `pkgJsonByName`, whereupon the function early-returns and TS2688 is never emitted — with
 * no error anywhere, because a missing diagnostic is invisible to every gate in this repo.
 *
 * The negatives below are not decoration: they are what separates "the scan ran" from "the
 * scan ran and its verdict is still the exports-hides-types one", so that a future round
 * cannot make this green by emitting TS2688 for every referenced package.
 */
class TypesReferenceExportsGateTest {

    /**
     * The MULTI-FILE harness, not `ProjectCompiler`, and that is a finding rather than a
     * convenience: `ProjectCompiler` never puts a `package.json` into the program (its own
     * comment says so — the nearest enclosing manifest "is not one of the program's inputs"),
     * so `checkMissingTypesReferenceExports` can only ever see one through a source set that
     * names the file explicitly. A first draft of this pin used `ProjectCompiler` and read
     * `programFiles=[/proj/src/index.ts]` with an empty diagnostic list — green for the
     * wrong reason had it been written as an absence assertion.
     */
    private fun diagnoseFiles(source: String) =
        TypeScriptCompiler().compile(source.trimIndent()).diagnostics

    private fun ts2688For(diags: List<Diagnostic>, name: String) =
        diags.any { it.code == 2688 && it.message.contains("'$name'") }

    /**
     * The shape the whole mechanism exists for: an `exports` map that routes consumers to
     * JavaScript and exposes no `.d.ts` and no `"types"`/`"typings"` condition, so the
     * reference directive cannot resolve to a declaration file.
     */
    @Test
    fun `a types reference to a package whose exports hide its types reports TS2688`() {
        val diags = diagnoseFiles(
            """
            // @Filename: node_modules/hidden/package.json
            { "name": "hidden", "exports": { ".": { "import": "./index.mjs" } } }

            // @Filename: main.ts
            /// <reference types="hidden" />
            export const n: number = 1;
            """,
        )
        assert(ts2688For(diags, "hidden"))
    }

    /**
     * Negative control 1 — no `exports` field at all, so resolution falls back to
     * `types`/`typings`/`index.d.ts` and there is nothing to report. A gate that simply
     * emitted for every referenced package would fail here.
     */
    @Test
    fun `a package with no exports field is silent`() {
        val diags = diagnoseFiles(
            """
            // @Filename: node_modules/plain/package.json
            { "name": "plain", "types": "./index.d.ts" }

            // @Filename: main.ts
            /// <reference types="plain" />
            export const n: number = 1;
            """,
        )
        assert(!ts2688For(diags, "plain"))
    }

    /**
     * Negative control 2 — the `exports` map DOES expose types, which is the exact
     * discrimination `packageExportsHidesTypes` makes.
     */
    @Test
    fun `a package whose exports expose types is silent`() {
        val diags = diagnoseFiles(
            """
            // @Filename: node_modules/shown/package.json
            { "name": "shown", "exports": { ".": { "types": "./index.d.ts", "import": "./index.mjs" } } }

            // @Filename: main.ts
            /// <reference types="shown" />
            export const n: number = 1;
            """,
        )
        assert(!ts2688For(diags, "shown"))
    }

    /**
     * The reference directive is only honoured in the LEADING comment block — the scan
     * breaks at the first non-comment line. Pinned because the pre-gate round moved the
     * code around it, and a break that moved would be silent in the other direction.
     */
    @Test
    fun `a reference directive below real code is not honoured`() {
        val diags = diagnoseFiles(
            """
            // @Filename: node_modules/hidden/package.json
            { "name": "hidden", "exports": { ".": { "import": "./index.mjs" } } }

            // @Filename: main.ts
            export const n: number = 1;
            /// <reference types="hidden" />
            """,
        )
        assert(!ts2688For(diags, "hidden"))
    }
}
