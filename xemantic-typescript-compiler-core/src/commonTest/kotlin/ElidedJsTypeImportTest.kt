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
 * (P18.139) TS18042's SUGGESTED `import("<spec>")` CARRIES A `.<name>` TAIL ONLY FOR AN
 * `ImportSpecifier` — never for the DEFAULT binding, which is the only form this compiler
 * emits the row for.
 *
 * tsgo appends it under one gate (`internal/checker/checker.go:6758`):
 *
 *     importText := "import(\"" + specifierText + "\")"
 *     if ast.IsImportSpecifier(node) { importText = importText + "." + identifierText }
 *
 * `import D from "m"` binds an `ImportClause`, not an `ImportSpecifier`, so tsgo prints the
 * bare `import("m")`. TypeScript 6 appended unconditionally, which is where our old
 * `import("@truffle/contract").TruffleContract` came from and what
 * `elidedJSImport1.errors.txt` recorded as its only divergence.
 *
 * MEASURED against `tools/tsgo-7.0.2/lib/tsc` (one scratch project per row, an ambient
 * `declare module` exporting a type-only namespace by default plus two type aliases by
 * name), which is the population the predicate has to separate:
 *
 *     import D from "m"          -> TS18042 … Use 'import("m")'            <- pinned below
 *     import { N } from "m"      -> TS18042 … Use 'import("m").N'          <- residue, we are silent
 *     import { N as R } from "m" -> TS18042 on 'N' … Use 'import("m").N'   <- residue, we are silent
 *     import * as NS from "m"    -> silent
 *     import X = require("m")    -> TS8002 in a JS file, never reaches TS18042
 *
 * The renamed row is the one that names the rule precisely: tsgo's error node is
 * `node.PropertyNameOrName()`, so both the quoted name and the tail are the PROPERTY name,
 * not the local one. Our emitter is B508's, which reaches only `ImportClause.name`, so the
 * two `ImportSpecifier` rows are a separate MISSING emitter rather than a wrong one.
 */
class ElidedJsTypeImportTest {

    /** An ambient module whose default export is a type-only namespace, plus two named types. */
    private val ambient = """
        // @Filename: node_modules/@truffle/contract/index.d.ts
        declare module "@truffle/contract" {
            interface ContractObject {
                foo: number;
            }
            namespace TruffleContract {
                export type Contract = ContractObject;
            }
            interface NamedIface { a: number }
            type NamedAlias = number;
            export default TruffleContract;
            export { TruffleContract, NamedIface, NamedAlias };
        }
    """.trimIndent()

    private val js = "// @allowJs: true\n// @checkJs: true\n// @target: es2015\n// @module: ES2020\n// @moduleResolution: bundler"

    @Test
    fun `a default import of a type-only namespace suggests the bare import call`() {
        val d = diagnose(
            ambient + "\n" + """
            // @Filename: caller.js
            import TruffleContract from '@truffle/contract';
            """.trimIndent(),
            directives = js,
        )
        d should {
            have(any {
                it.code == 18042 &&
                    it.message == "'TruffleContract' is a type and cannot be imported in " +
                    "JavaScript files. Use 'import(\"@truffle/contract\")' in a JSDoc type annotation."
            })
        }
        // and NOT the TypeScript 6 spelling
        d should { have(none { it.code == 18042 && ".TruffleContract'" in it.message }) }
    }

    @Test
    fun `residue - a named import specifier of a type reports nothing here`() {
        // tsgo: caller.js(1,10): error TS18042: 'NamedIface' is a type and cannot be imported
        // in JavaScript files. Use 'import("@truffle/contract").NamedIface' in a JSDoc type
        // annotation.  — B508 reaches only the default binding, so this row is MISSING here.
        val d = diagnose(
            ambient + "\n" + """
            // @Filename: caller.js
            import { NamedIface } from '@truffle/contract';
            """.trimIndent(),
            directives = js,
        )
        d should { have(none { it.code == 18042 }) }
    }

    @Test
    fun `residue - a renamed import specifier of a type reports nothing here`() {
        // tsgo: the same row, quoting the PROPERTY name 'NamedIface' (not the local 'Renamed')
        // and suggesting 'import("@truffle/contract").NamedIface'.
        val d = diagnose(
            ambient + "\n" + """
            // @Filename: caller.js
            import { NamedIface as Renamed } from '@truffle/contract';
            """.trimIndent(),
            directives = js,
        )
        d should { have(none { it.code == 18042 }) }
    }

    @Test
    fun `negative control - a namespace import of the same module is silent on both compilers`() {
        val d = diagnose(
            ambient + "\n" + """
            // @Filename: caller.js
            import * as NS from '@truffle/contract';
            """.trimIndent(),
            directives = js,
        )
        assert(d.none { it.code == 18042 })
    }
}
