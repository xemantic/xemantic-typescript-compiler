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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (LEGACY.0b) step 2, family F5 — what TypeScript 7 says about an option it no longer has.
 *
 * There are TWO populations and they take different diagnostics, which is the whole finding:
 *
 * - an option TypeScript 7 DELETED FROM ITS TABLE (`charset`, `out`, `keyofStringsOnly`,
 *   `noImplicitUseStrict`, `noStrictGenericChecks`, `suppressExcessPropertyErrors`,
 *   `suppressImplicitAnyIndexErrors`, `importsNotUsedAsValues`, `preserveValueImports` —
 *   none of them appears in `typescript-go-repo/internal/tsoptions/declscompiler.go`) is
 *   simply an **unknown** key: TS5023 at the option NAME, with no deprecation ladder, so
 *   neither `ignoreDeprecations` nor the harness's `@typeScriptVersion` silences it;
 * - an option TypeScript 7 KEEPS and no longer honours (`baseUrl`, `outFile`,
 *   `downlevelIteration`, `target=ES5`, `module=AMD`, `moduleResolution=Classic`,
 *   `esModuleInterop=false`, …) keeps the `has been removed` wording — tsgo's
 *   `createRemovedOptionDiagnostic` emits exactly TS5102 / TS5108, which is what this
 *   compiler already produces. Which VERSION reaches that wording by default is
 *   `simulatedVersion`, owned by (LEGACY.1).
 *
 * `target: ES3` is a third thing again: `es3` is not a `target` VALUE in TypeScript 7's
 * enum map at all, so it is an invalid ARGUMENT — TS6046 at the value.
 *
 * Baselines: `deprecatedCompilerOptions1`, `deprecatedCompilerOptions3`,
 * `deprecatedCompilerOptions4`, `deprecatedCompilerOptions5` — whose four different
 * `@typeScriptVersion` values (5.0 / 6.0 / 5.5 / 6.0, two of them with
 * `"ignoreDeprecations": "5.0"`) produce byte-identical tsgo output.
 */
class TsgoRemovedOptionWordingTest {

    private fun config(body: String, version: String) = diagnose(
        """
        // @Filename: /foo/tsconfig.json
        {
            "compilerOptions": {
        $body
            }
        }

        // @filename: /foo/a.ts
        const a = 1;
        """,
        directives = "// @typeScriptVersion: $version",
    )

    /** An option TypeScript 7 deleted from its table is TS5023 at the option NAME. */
    @Test
    fun `an option TypeScript 7 deleted from its table is an unknown compiler option`() {
        val diagnostics = config("""        "noImplicitUseStrict": true""", "6.0")
        val row = diagnostics.single { it.code == 5023 }
        assert(row.message == "Unknown compiler option 'noImplicitUseStrict'.")
        assert(row.fileName == "/foo/tsconfig.json")
        // The squiggle covers the quoted key.
        assert(row.length == 21)
        diagnostics should { have(none { it.code == 5101 || it.code == 5102 }) }
    }

    /**
     * The same option under an OLDER `@typeScriptVersion` and with `ignoreDeprecations` set —
     * TypeScript 7 has no deprecation ladder for a name it does not have, so neither silences
     * it. This is what makes `deprecatedCompilerOptions1/3/4/5` produce the same output.
     */
    @Test
    fun `neither an older typeScriptVersion nor ignoreDeprecations silences TS5023`() {
        val diagnostics = config(
            """        "ignoreDeprecations": "5.0",
        "keyofStringsOnly": true""",
            "5.0",
        )
        assert(diagnostics.count { it.code == 5023 } == 1)
        assert(
            diagnostics.single { it.code == 5023 }.message ==
                "Unknown compiler option 'keyofStringsOnly'."
        )
    }

    /**
     * `es3` is not in TypeScript 7's `target` enum map, so it is an invalid ARGUMENT reported
     * at the VALUE. The listed values are exactly the map's non-deprecated keys, which is why
     * `es5` — still present, but flagged deprecated — is absent from the sentence.
     */
    @Test
    fun `target es3 is an invalid argument rather than a removed option`() {
        val diagnostics = config(
            """        "target": "ES3",
        "strict": true""",
            "6.0",
        )
        val row = diagnostics.single { it.code == 6046 }
        assert(
            row.message == "Argument for '--target' option must be: 'es6', 'es2015', " +
                "'es2016', 'es2017', 'es2018', 'es2019', 'es2020', 'es2021', 'es2022', " +
                "'es2023', 'es2024', 'es2025', 'esnext'."
        )
        // Anchored on the VALUE, not on the key.
        assert(row.length == 5)
        diagnostics should { have(none { it.code == 5107 || it.code == 5108 }) }
    }

    /**
     * Negative control — an option TypeScript 7 still HAS keeps the `has been removed` /
     * deprecation ladder rather than becoming TS5023. Whether the default `simulatedVersion`
     * should make that TS5102 instead of TS5101 is (LEGACY.1)'s owner-gated question; what is
     * pinned here is only that the option is not treated as unknown.
     */
    @Test
    fun `negative control - an option TypeScript 7 still has is never TS5023`() {
        val diagnostics = config(
            """        "outFile": "dist.js",
        "strict": true""",
            "6.0",
        )
        diagnostics should { have(none { it.code == 5023 }) }
        assert(diagnostics.any { it.code == 5101 || it.code == 5102 })
    }
}
