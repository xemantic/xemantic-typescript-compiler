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
 * (P18.152): a DEFAULT IMPORT CLAUSE needs `__importDefault`, so under `importHelpers` with no
 * resolvable `tslib` it is TS2354 — and `checkImportHelpersWithoutTslib` only ever looked at
 * `namedBindings`, so `import path from "path"`, the shape a user is most likely to write, was
 * the one shape it could not see.
 *
 * **The ledger's recorded reason said `ours: ==== file.ts (0 errors) ====`, i.e. that we emitted
 * NOTHING for the whole baseline.** We emitted three of its four rows correctly; the gap was one
 * row. That is the third recorded reason in this session to overstate a gap, and the pattern is
 * the same each time — a reason quotes the FIRST differing line of a diff and reads as a verdict
 * on the mechanism.
 *
 * Every expectation measured against tsgo 7.0.2 first (`module: commonjs`, `importHelpers`,
 * `noEmitHelpers`, an ambient `declare module "path"` and no tslib).
 */
class ImportHelpersDefaultImportTest {

    private val directives =
        "// @target: es2015\n// @module: commonjs\n// @importHelpers: true\n// @noEmitHelpers: true"

    private fun rows(source: String): List<Pair<Int?, Int?>> =
        diagnose(source, directives = directives)
            .filter { it.code == 2354 }
            .map { it.line to it.character }

    private val ambient = "// @filename: refs.d.ts\ndeclare module \"path\";\n"

    /** A plain default import, anchored at the WHOLE statement — tsgo's column 1. */
    @Test
    fun `a default import clause reports at the whole statement`() {
        assert(
            rows(
                ambient +
                    "// @filename: a.ts\n" +
                    "import path from \"path\";\n" +
                    "export class A { }\n",
            ) == listOf(1 to 1),
        )
    }

    /**
     * A clause carrying BOTH a default name and a `default as` specifier reports at the
     * SPECIFIER, not at the statement — measured 1:16 against 1:1, which is why the new arm is
     * ordered after the named-specifier one and gated on the statement having emitted nothing.
     * Without that ordering this row would move to column 1.
     */
    @Test
    fun `a mixed clause reports at the specifier, not at the statement`() {
        assert(
            rows(
                ambient +
                    "// @filename: f.ts\n" +
                    "import path, { default as r } from \"path\";\n" +
                    "export { r };\n",
            ) == listOf(1 to 16),
        )
    }

    /** Negative control: a named-only import needs no helper and must stay silent. */
    @Test
    fun `negative control - a named-only import reports nothing`() {
        assert(
            rows(
                ambient +
                    "// @filename: c.ts\n" +
                    "import { Bar } from \"path\";\n" +
                    "export { Bar };\n",
            ).isEmpty(),
        )
    }

    /** Negative control: a side-effect import needs no helper. */
    @Test
    fun `negative control - a side-effect import reports nothing`() {
        assert(
            rows(
                ambient +
                    "// @filename: e.ts\n" +
                    "import \"path\";\n" +
                    "export class E { }\n",
            ).isEmpty(),
        )
    }

    /**
     * residue - tsgo reports TS2354 at (1,1) for a TYPE-ONLY default import
     * (`import type path from "path"`) and we do not. A type-only import emits nothing, so no
     * helper can be required; this reads as a tsgo defect (its emit resolver appears to ask
     * whether the clause has a default NAME without consulting `isTypeOnly`), and following it
     * would add a row to every `import type X from` in a project using `importHelpers`.
     *
     * Measured, out of (P18.152) deliberately, and in NO baseline either way — so it costs
     * nothing in either direction and is recorded rather than chased. Re-measure before
     * re-pointing.
     */
    @Test
    fun `residue - a type-only default import is silent here and reports in tsgo`() {
        assert(
            rows(
                ambient +
                    "// @filename: g.ts\n" +
                    "import type path from \"path\";\n" +
                    "export class G { }\n",
            ).isEmpty(),
        )
    }
}
