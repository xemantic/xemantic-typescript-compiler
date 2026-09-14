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
 * (LEGACY.0b) step 6, family F6d — TypeScript 7 reports TS2303 at **every alias
 * declaration on a circular alias chain**, where tsc 6 reported exactly one.
 *
 * tsgo's rule is one line of `resolveAlias` (`checker.go` ~16199): the alias's target
 * resolution is bracketed by `pushTypeResolution`/`popTypeResolution`, and a failing pop
 * — which is *every* frame from the cycle's start upwards, because `pushTypeResolution`
 * marks the whole suffix false when it detects the cycle — emits
 * `c.error(getDeclarationOfAliasSymbol(symbol), Circular_definition_of_import_alias_0)`.
 * So the row count is the number of alias DECLARATIONS the cycle passes through, and each
 * row is named after its own symbol and anchored at its own declaration.
 *
 * This checker has no single alias-resolution stack; the four cycle SHAPES are recognised
 * by four dedicated walkers, and this class pins the "report every declaration" rule at
 * each of them:
 *
 *  - `checkCircularImportAlias` — an entity-name chain (`import A = B; import B = A`);
 *  - `checkCircularExportEqualsImportAlias` — `import self = require("M"); export = self`,
 *    where each cycle member contributes TWO declarations;
 *  - `checkExportAsNamespaceSelfCycle` — `export = N` + `export as namespace N`;
 *  - the 16.4ee declaration-emit pair — an unresolvable `import Foo = NoSuch` that some
 *    `export { Foo }` / `export default Foo` re-exports.
 *
 * Every expectation below was read off `tools/tsgo-7.0.2/lib/tsc` on the same source
 * (columns from the plain output, widths from `--pretty`). Baselines closed:
 * `circularModuleImports`, `exportAsNamespaceConflict`,
 * `recursiveExportAssignmentAndFindAliasedType1`..`6`,
 * `declarationEmitUnknownImport(target=es2015)`,
 * `declarationEmitUnknownImport2(target=es2015)`.
 *
 * The negative controls are the load-bearing half: this round only ADDS rows, so the
 * direction that can regress is a false positive on legal code.
 */
class Ts2303CircularAliasEveryDeclarationTest {

    /** TS2303 rows in reading order. The harness STRIPS the `// @directive` header before
     *  compiling (as tsc's own does), so a row's line is the fixture's own. */
    private fun ts2303(
        source: String,
        directives: String = "// @strict: true\n// @target: es2015",
        fileName: String = "t.ts",
    ): List<Diagnostic> =
        diagnose(source, directives = directives, fileName = fileName)
            .filter { it.code == 2303 }
            .sortedWith(compareBy({ it.fileName }, { it.line }, { it.character }))

    private fun circular(name: String) = "Circular definition of import alias '$name'."

    // ---------------------------------------------------------------------
    // 1. entity-name chains — `checkCircularImportAlias`
    // ---------------------------------------------------------------------

    /**
     * `circularModuleImports`. tsgo: `(5,5)` 'A' and `(7,5)` 'B', each squiggling the whole
     * 13-character `import A = B;`. tsc 6 emitted the 'A' row alone.
     */
    @Test
    fun `an entity-name alias cycle reports at every import in the cycle`() {
        val rows = ts2303(
            """
            namespace M {
                import A = B;
                import B = A;
            }
            """
        )
        assert(rows.size == 2)
        assert(rows[0].message == circular("A"))
        assert(rows[0].line == 2)
        assert(rows[0].character == 5)
        assert(rows[0].length == 13)
        assert(rows[1].message == circular("B"))
        assert(rows[1].line == 3)
        assert(rows[1].character == 5)
        assert(rows[1].length == 13)
    }

    /** Three declarations on the chain, three rows — tsgo `(2,5)` 'A', `(3,5)` 'B', `(4,5)` 'C'. */
    @Test
    fun `a three member entity-name alias cycle reports three times`() {
        val rows = ts2303(
            """
            namespace M {
                import A = B;
                import B = C;
                import C = A;
            }
            """
        )
        assert(rows.size == 3)
        assert(rows.map { it.message } == listOf(circular("A"), circular("B"), circular("C")))
    }

    /** A one-hop self alias is ONE declaration, so it stays ONE row — tsgo `(2,5)` 'A'. */
    @Test
    fun `a self-referential alias reports once because the cycle has one declaration`() {
        val rows = ts2303(
            """
            namespace M {
                import A = A;
            }
            """
        )
        assert(rows.size == 1)
        assert(rows[0].message == circular("A"))
    }

    /** Negative control: an alias chain that terminates in a real namespace is silent in tsgo. */
    @Test
    fun `negative control - a terminating entity-name alias chain reports nothing`() {
        val rows = ts2303(
            """
            namespace Q { export const x = 1; }
            namespace M {
                import A = Q;
                import B = A;
                export const y = B.x;
            }
            """
        )
        assert(rows.isEmpty())
    }

    // ---------------------------------------------------------------------
    // 2. `import self = require(...)` + `export = self`
    // ---------------------------------------------------------------------

    private val cjs = "// @strict: true\n// @target: es2015\n// @module: commonjs"

    /**
     * `recursiveExportAssignmentAndFindAliasedType4`. A self-importing module contributes
     * TWO alias declarations, and tsgo reports both: `m.ts(1,1)` over the 29-character
     * `import self = require("./m");` and `m.ts(2,1)` over the 14-character `export = self;`.
     * tsc 6 reported only the import, and only for ONE member of the cycle.
     */
    @Test
    fun `a self-importing module reports at both the import and the export assignment`() {
        val rows = ts2303(
            """
            // @Filename: m.ts
            import self = require("./m");
            export = self;

            // @Filename: c.ts
            import m = require("./m");
            export var v: number = 1;
            """,
            directives = cjs,
        )
        assert(rows.size == 2)
        assert(rows.all { it.fileName == "m.ts" })
        assert(rows.all { it.message == circular("self") })
        assert(rows[0].character == 1)
        assert(rows[0].length == 29)
        assert(rows[1].character == 1)
        assert(rows[1].length == 14)
        assert(rows[1].line == (rows[0].line ?: 0) + 1)
    }

    /**
     * `recursiveExportAssignmentAndFindAliasedType5`: a two-module cycle is FOUR alias
     * declarations and tsgo prints four rows. tsc 6 printed one, at whichever member a
     * non-cycle file happened to import — the entry-point heuristic this round deleted.
     */
    @Test
    fun `a two module require cycle reports at all four alias declarations`() {
        val rows = ts2303(
            """
            // @Filename: c.ts
            import self = require("./d");
            export = self;

            // @Filename: d.ts
            import self = require("./c");
            export = self;

            // @Filename: a.ts
            import moduleC = require("./c");
            export var b: number = 1;
            """,
            directives = cjs,
        )
        assert(rows.size == 4)
        assert(rows.count { it.fileName == "c.ts" } == 2)
        assert(rows.count { it.fileName == "d.ts" } == 2)
        assert(rows.all { it.message == circular("self") })
        assert(rows.map { it.length } == listOf(29, 14, 29, 14))
    }

    /** Negative control: re-exporting ANOTHER module is the same syntax and is not a cycle. */
    @Test
    fun `negative control - a re-exporting module outside a cycle reports nothing`() {
        val rows = ts2303(
            """
            // @Filename: b.ts
            class B { p: number = 1; }
            export = B;

            // @Filename: d.ts
            import x = require("./b");
            export = x;

            // @Filename: a.ts
            import d = require("./d");
            export var v: number = 1;
            """,
            directives = cjs,
        )
        assert(rows.isEmpty())
    }

    // ---------------------------------------------------------------------
    // 3. `export = N` + `export as namespace N`
    // ---------------------------------------------------------------------

    /**
     * `exportAsNamespaceConflict`. Both halves are alias declarations of the same UMD name:
     * tsgo `/a.d.ts(2,1)` over the 11-character `export = N;` and `(3,1)` over the
     * 22-character `export as namespace N;`. tsc 6 reported only the UMD half.
     */
    @Test
    fun `a UMD self-cycle reports at the export assignment as well as the namespace export`() {
        val rows = ts2303(
            """
            declare global { namespace N {} }
            export = N;
            export as namespace N;
            """,
            fileName = "t.d.ts",
        )
        assert(rows.size == 2)
        assert(rows.all { it.message == circular("N") })
        assert(rows[0].character == 1)
        assert(rows[0].length == 11)
        assert(rows[1].character == 1)
        assert(rows[1].length == 22)
        assert(rows[1].line == (rows[0].line ?: 0) + 1)
    }

    /** Negative control: the ordinary UMD pattern, where N IS declared module-locally. */
    @Test
    fun `negative control - a UMD export of a module-local namespace reports nothing`() {
        val rows = ts2303(
            """
            declare namespace N { const x: number; }
            export = N;
            export as namespace N;
            """,
            fileName = "t.d.ts",
        )
        assert(rows.isEmpty())
    }

    // ---------------------------------------------------------------------
    // 4. the 16.4ee declaration-emit pair
    // ---------------------------------------------------------------------

    private val declEmit = "// @strict: true\n// @target: es2015\n// @module: commonjs\n// @declaration: true"

    /**
     * `declarationEmitUnknownImport`. The re-export is a second alias declaration of the
     * same unresolvable target: tsgo `(1,1)` over the 19-character import and `(2,9)` over
     * the 3-character `Foo` INSIDE the export clause.
     */
    @Test
    fun `an unresolvable import alias re-exported by name reports at both declarations`() {
        val rows = ts2303(
            """
            import Foo = SomeNonExistingName
            export {Foo}
            """,
            directives = declEmit,
        )
        assert(rows.size == 2)
        assert(rows.all { it.message == circular("Foo") })
        assert(rows[0].character == 1)
        assert(rows[0].length == 32)
        assert(rows[1].character == 9)
        assert(rows[1].length == 3)
    }

    /**
     * The export specifier is named by its EXPORTED name and squiggled over the WHOLE
     * specifier — tsgo `(2,9)` 'Foo', width 10 for `Bar as Foo`, beside `(1,1)` 'Bar'.
     */
    @Test
    fun `a renamed re-export is reported under the exported name over the whole specifier`() {
        val rows = ts2303(
            """
            import Bar = SomeNonExistingName
            export {Bar as Foo}
            """,
            directives = declEmit,
        )
        assert(rows.size == 2)
        assert(rows[0].message == circular("Bar"))
        assert(rows[1].message == circular("Foo"))
        assert(rows[1].character == 9)
        assert(rows[1].length == 10)
    }

    /**
     * `declarationEmitUnknownImport2`. `export default Foo` squiggles the WHOLE statement
     * (tsgo `(2,1)`, width 18) and — unlike an `export { Foo as default }`, which tsgo names
     * `default` — is reported under the EXPRESSION's name.
     *
     * The trailing `declare const` is load-bearing: without a following TOKEN the statement's
     * end and `Identifier.end` coincide (the latter is the end of the token AFTER the
     * identifier, CLAUDE.md's `Node.end` law, and at EOF that is the identifier itself), so
     * the span computation cannot be discriminated. With it, reading `Node.end` overshoots
     * by the whole next statement.
     */
    @Test
    fun `an unresolvable import alias re-exported as default reports over the whole statement`() {
        val rows = ts2303(
            """
            import Foo = SomeNonExistingName
            export default Foo
            declare const zzz: number
            """,
            directives = declEmit,
        )
        assert(rows.size == 2)
        assert(rows.all { it.message == circular("Foo") })
        assert(rows[1].character == 1)
        assert(rows[1].length == 18)
    }

    /**
     * Negative control and the gate of the whole 16.4ee pair: an unresolvable alias that
     * reaches no declaration-emit surface is TS2503 alone in tsgo, with no circularity.
     */
    @Test
    fun `negative control - an unresolvable import alias that is not re-exported reports nothing`() {
        val rows = ts2303(
            """
            import Foo = SomeNonExistingName
            export {}
            """,
            directives = declEmit,
        )
        assert(rows.isEmpty())
    }

    /** Negative control: a RESOLVABLE alias re-exported the same way is silent in tsgo. */
    @Test
    fun `negative control - a resolvable import alias that is re-exported reports nothing`() {
        val rows = ts2303(
            """
            namespace Q { export const x = 1; }
            import Foo = Q
            export {Foo}
            """,
            directives = declEmit,
        )
        assert(rows.isEmpty())
    }
}
