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
 * (INV.0) (P18.255) The NAMED / DEFAULT IMPORT EXISTENCE family as it lives in
 * `NamedImportExistence` after the extraction: the default-import pass (TS1192 / TS2613 /
 * TS2614, and tsgo's `canHaveSyntheticDefault` with its `__esModule` marker for a declaration
 * file), the named-import and `export { … } from` existence pass, tsgo's
 * `errorNoModuleMemberSymbol` order (TS2724 / TS2614 / TS2460 / TS2459 / TS2305) with the
 * TS2728 / TS6204 related rows, and the TS2305 emitter that three callers left on `Checker`
 * still reach (an `export =` named-member import here).
 *
 * Every row is tsgo 7.0.2's (cells under `build/bench/p18255-agent/matrix/`, 1-based column);
 * all sixteen cells agree on both arms of the move. Two display residues are NOT pinned: for a
 * default import of a relative module tsgo names the RESOLVED file by its absolute path where
 * this compiler prints the specifier without `./` (only the code and span are asserted there),
 * and a NAMED import of an `export =` module reads TS2616 / TS2595 here where tsgo reads TS2305.
 */
class NamedImportExistenceCollaboratorTest {

    private fun diags(files: String, module: String = "commonjs"): List<Diagnostic> =
        diagnose(files, directives = "// @strict: true\n// @module: $module")

    private fun rows(files: String): List<String> =
        diags(files).map { "${it.fileName}:${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    private fun related(d: Diagnostic): List<String> =
        d.relatedInformation.map { "${it.fileName}:${it.line}:${it.character} ${it.code} ${it.message}" }

    @Test
    fun `a default import of a module with no default export is TS1192`() {
        val d = diags(
            """
            // @Filename: m.ts
            export const a = 1;
            // @Filename: main.ts
            import d from "./m";
            export { d };
            """
        ).single()
        assert(d.code == 1192 && d.fileName == "main.ts" && d.line == 1 && d.character == 8)
        assert(d.message.endsWith("has no default export."))
    }

    @Test
    fun `a default import naming a named export is TS2613`() {
        val d = diags(
            """
            // @Filename: m.ts
            export const d = 1;
            // @Filename: main.ts
            import d from "./m";
            export { d };
            """
        ).single()
        assert(d.code == 2613 && d.fileName == "main.ts" && d.line == 1 && d.character == 8)
        assert(d.message.contains("has no default export. Did you mean to use 'import { d } from"))
    }

    @Test
    fun `a declaration file declaring the esModule marker has no synthetic default`() {
        val d = diags(
            """
            // @Filename: m.d.ts
            export declare const __esModule: true;
            export declare const a: number;
            // @Filename: main.ts
            import d from "./m";
            export { d };
            """
        ).single()
        assert(d.code == 1192 && d.line == 1 && d.character == 8)
    }

    @Test
    fun `negative control - a declaration file without the marker has a synthetic default`() {
        val r = rows(
            """
            // @Filename: m.d.ts
            export declare const a: number;
            // @Filename: main.ts
            import d from "./m";
            export { d };
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a named import absent from a module with a default export is TS2614`() {
        val r = rows(
            """
            // @Filename: m.ts
            export default 1;
            export const a = 1;
            // @Filename: main.ts
            import { qqqq } from "./m";
            export { qqqq };
            """
        )
        assert(r == listOf("main.ts:1:10 TS2614 Module '\"./m\"' has no exported member 'qqqq'. Did you mean to use 'import qqqq from \"./m\"' instead?"))
    }

    @Test
    fun `a misspelt named import is TS2724 with the declaration as related information`() {
        val d = diags(
            """
            // @Filename: m.ts
            export const helper = 1;
            // @Filename: main.ts
            import { helpr } from "./m";
            export { helpr };
            """
        ).single()
        assert(d.code == 2724 && d.line == 1 && d.character == 10)
        assert(d.message == "'\"./m\"' has no exported member named 'helpr'. Did you mean 'helper'?")
        assert(related(d) == listOf("m.ts:1:14 2728 'helper' is declared here."))
    }

    @Test
    fun `a spelling suggestion wins over the default import hint`() {
        val d = diags(
            """
            // @Filename: m.ts
            export default 1;
            export const helper = 1;
            // @Filename: main.ts
            import { helpr } from "./m";
            export { helpr };
            """
        ).single()
        assert(d.code == 2724 && d.line == 1 && d.character == 10)
        assert(related(d) == listOf("m.ts:2:14 2728 'helper' is declared here."))
    }

    @Test
    fun `a local exported under another name is TS2460`() {
        val d = diags(
            """
            // @Filename: m.ts
            const internalValue = 1;
            export { internalValue as publicName };
            // @Filename: main.ts
            import { internalValue } from "./m";
            export { internalValue };
            """
        ).single()
        assert(d.code == 2460 && d.line == 1 && d.character == 10)
        assert(d.message == "Module '\"./m\"' declares 'internalValue' locally, but it is exported as 'publicName'.")
        assert(related(d) == listOf("m.ts:1:7 2728 'internalValue' is declared here."))
    }

    @Test
    fun `a local that is not exported is TS2459 with every declaration related`() {
        val d = diags(
            """
            // @Filename: m.ts
            interface Hidden { a: number }
            interface Hidden { b: number }
            export const other = 1;
            // @Filename: main.ts
            import { Hidden } from "./m";
            export type { Hidden };
            """
        ).single()
        assert(d.code == 2459 && d.line == 1 && d.character == 10)
        assert(d.message == "Module '\"./m\"' declares 'Hidden' locally, but it is not exported.")
        assert(related(d) == listOf("m.ts:1:11 2728 'Hidden' is declared here.", "m.ts:2:11 6204 and here."))
    }

    @Test
    fun `a name the module does not declare is TS2305`() {
        val r = rows(
            """
            // @Filename: m.ts
            export const alpha = 1;
            // @Filename: main.ts
            import { zeta } from "./m";
            export { zeta };
            """
        )
        assert(r == listOf("main.ts:1:10 TS2305 Module '\"./m\"' has no exported member 'zeta'."))
    }

    @Test
    fun `a default specifier against a module with no default export is TS2305`() {
        val r = rows(
            """
            // @Filename: m.ts
            export const alpha = 1;
            // @Filename: main.ts
            import { default as D } from "./m";
            export { D };
            """
        )
        assert(r == listOf("main.ts:1:10 TS2305 Module '\"./m\"' has no exported member 'default'."))
    }

    @Test
    fun `a re-export absent from a module with a default export is TS2614`() {
        val r = rows(
            """
            // @Filename: m.ts
            export default 1;
            export const alpha = 1;
            // @Filename: main.ts
            export { zeta } from "./m";
            """
        )
        assert(r == listOf("main.ts:1:10 TS2614 Module '\"./m\"' has no exported member 'zeta'. Did you mean to use 'import zeta from \"./m\"' instead?"))
    }

    @Test
    fun `an absent name through a star barrel is TS2305 naming the barrel`() {
        val r = rows(
            """
            // @Filename: m.ts
            export const alpha = 1;
            // @Filename: b.ts
            export * from "./m";
            // @Filename: main.ts
            import { zeta } from "./b";
            export { zeta };
            """
        )
        assert(r == listOf("main.ts:1:10 TS2305 Module '\"./b\"' has no exported member 'zeta'."))
    }

    @Test
    fun `a named import absent from an export equals declaration file is TS2305 from a caller left on Checker`() {
        val r = rows(
            """
            // @Filename: m.d.ts
            declare namespace N { const a: number; }
            export = N;
            // @Filename: main.ts
            import { b } from "./m";
            export { b };
            """
        )
        assert(r == listOf("main.ts:1:10 TS2305 Module '\"./m\"' has no exported member 'b'."))
    }
}
