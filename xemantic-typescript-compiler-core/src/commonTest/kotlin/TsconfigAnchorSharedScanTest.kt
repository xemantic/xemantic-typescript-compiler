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
 * (LEGACY.1)(d) The HARNESS half of the one-scanner rule: the corpus's embedded
 * `@Filename: tsconfig.json` fixtures read their option positions from the same
 * `tsconfigOptionPositionsOf` the project path now uses, and the anchor for an option
 * that is set but not written in the root config is tsgo's `createDiagnosticForOption`
 * fallback — the root's `"compilerOptions"` key, on BOTH ladders — measured on tsgo
 * 7.0.2: `tsc -p . --downlevelIteration --esModuleInterop false` beside a config
 * reports `tsconfig.json(1,3)` for TS5102 AND TS5108, and `{ "extends": "./base.json",
 * "compilerOptions": {} }` reports `base.json`'s option at `tsconfig.json(1,29)`.
 *
 * The project-path pins live in `ProjectTsconfigOptionAnchorTest`; this class keeps the
 * harness path honest, because the corpus itself has ZERO fixtures using `extends` and
 * none pairing a deprecated directive with an embedded tsconfig — the two shapes this
 * round changed on this path.
 */
class TsconfigAnchorSharedScanTest {

    /**
     * Control — the value anchor the corpus screen already gates, in its multi-line
     * form (`AlwaysStrictRemovedTest`'s TS5108 pin is the one-option sibling, at line
     * 3 column 25 — `diagnose` strips the `@Filename` header line): line 3, column 28,
     * the five characters of `false`. Green on both arms by design.
     */
    @Test
    fun `control - the harness anchors esModuleInterop false at its value as before`() {
        val diagnostics = diagnose(
            """
            // @Filename: /foo/tsconfig.json
            {
                "compilerOptions": {
                    "esModuleInterop": false
                }
            }

            // @filename: /foo/a.ts
            const a = 1;
            """,
            directives = "// @typeScriptVersion: 7.0",
        )
        val row = diagnostics.singleOrNull { it.code == 5108 }
        assert(row != null)
        assert(row.fileName == "/foo/tsconfig.json")
        assert(row.line == 3)
        assert(row.character == 28)
        assert(row.length == 5)
    }

    /**
     * An option written only in an EXTENDED config: before this round the harness
     * merged the extended file's positions in and anchored the row INSIDE `base.json`;
     * tsgo (and tsc 6 before it) name the root and its `"compilerOptions"` key.
     */
    @Test
    fun `an option written only in an extended tsconfig anchors at the root's compilerOptions key`() {
        val diagnostics = diagnose(
            """
            // @Filename: /foo/base.json
            { "compilerOptions": { "alwaysStrict": false } }

            // @Filename: /foo/tsconfig.json
            { "extends": "./base.json", "compilerOptions": { "strict": true } }

            // @filename: /foo/a.ts
            const a = 1;
            """,
            directives = "// @typeScriptVersion: 7.0",
        )
        val row = diagnostics.singleOrNull { it.code == 5108 }
        assert(row != null)
        assert(row.fileName == "/foo/tsconfig.json")
        assert(row.line == 1)
        assert(row.character == 29)
        assert(row.length == 17)
    }

    /**
     * A KEY-ladder option set by a DIRECTIVE (the harness's command line) beside an
     * embedded tsconfig that does not write it: tsgo `tsconfig.json(1,3)`, the root's
     * `"compilerOptions"` key. Before this round only the VALUE ladder took that
     * fallback and this row was file-less.
     */
    @Test
    fun `a directive-set key-ladder option beside an embedded tsconfig anchors at the compilerOptions key`() {
        val diagnostics = diagnose(
            """
            // @Filename: /foo/tsconfig.json
            { "compilerOptions": { "strict": true } }

            // @filename: /foo/a.ts
            const a = 1;
            """,
            directives = "// @downlevelIteration: true",
        )
        val row = diagnostics.singleOrNull { it.code == 5101 }
        assert(row != null)
        assert(row.fileName == "/foo/tsconfig.json")
        assert(row.line == 1)
        assert(row.character == 3)
        assert(row.length == 17)
    }

    /** Control — with no tsconfig anywhere the same directive stays file-less, as `tsc --downlevelIteration` is. */
    @Test
    fun `control - a directive-set option with no tsconfig at all stays file-less`() {
        val row = diagnose("const a = 1;", directives = "// @downlevelIteration: true").singleOrNull { it.code == 5101 }
        assert(row != null)
        assert(row.fileName == null)
        assert(row.line == null)
    }
}
