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
 * (LEGACY.0b) step 3, family F8 — where TypeScript 7 ANCHORS an unused-local diagnostic.
 *
 * The rule is tsgo's `checkUnusedLocalsAndParameters` and the three reporters it dispatches
 * to (`checker.go`): `reportUnusedLocal` builds the diagnostic for `node.Name()`,
 * `reportUnusedVariableDeclarations` for `declaration.Name()`, and only the two GROUPING
 * codes take a wider node — TS6199 the `VariableDeclarationList`, TS6198 the binding
 * PATTERN, TS6192 the whole `ImportDeclaration`. tsc 6 had two extra special cases that
 * TypeScript 7 dropped, and each of them is a whole squiggle wide:
 *
 *  - a clause declaring exactly ONE binding squiggled the whole `import …;` statement;
 *  - a one-element binding pattern in a variable declaration or a parameter was rerouted
 *    through the VARIABLE reporter, which squiggled the PATTERN rather than the element.
 *
 * And TS6199's own span narrowed by one character: the list, not the statement, so the
 * trailing `;` is outside it.
 *
 * Every expectation below was read off `tools/tsgo-7.0.2/lib/tsc` (columns from the plain
 * output, widths from `--pretty`) on the same fixtures. Baselines closed: `unusedImports1`,
 * `unusedImports2`, `unusedImports6`, `unusedImports7`, `unusedImports12`,
 * `extendsUntypedModule`, `unusedDestructuring`, `unusedDestructuringParameters`,
 * `unusedLocalsInMethod2`, `unusedLocalsInMethod3`.
 */
class TsgoUnusedLocalAnchorTest {

    private val directives =
        "// @strict: true\n// @module: commonjs\n// @target: es2015\n" +
            "// @noUnusedLocals: true\n// @noUnusedParameters: true"

    private fun unused(source: String): List<Diagnostic> =
        diagnose(source, directives = directives).filter { it.code == 6133 }

    /**
     * `import { Calculator } from "./file1";` — tsgo `t.ts(1,10)`, width 10 (the name).
     * tsc 6 anchored `(1,1)` with the whole 37-character statement.
     */
    @Test
    fun `a lone unused named import is anchored on the imported name`() {
        val rows = unused(
            """
            // @Filename: file1.ts
            export class Calculator { }

            // @Filename: t.ts
            import { Calculator } from "./file1";
            """
        )
        val row = rows.single()
        assert(row.message == "'Calculator' is declared but its value is never read.")
        assert(row.line == 1)
        assert(row.character == 10)
        assert(row.length == 10)
    }

    /**
     * `import D from "./file1";` — the clause's own default NAME, tsgo `(1,8)` width 1.
     * This is `extendsUntypedModule`'s shape.
     */
    @Test
    fun `a lone unused default import is anchored on the default name`() {
        val rows = unused(
            """
            // @Filename: file1.ts
            export default class DD { }

            // @Filename: t.ts
            import D from "./file1";
            """
        )
        val row = rows.single()
        assert(row.message == "'D' is declared but its value is never read.")
        assert(row.line == 1)
        assert(row.character == 8)
        assert(row.length == 1)
    }

    /** `import * as ns from "./file1";` — the namespace NAME, tsgo `(1,13)` width 2. */
    @Test
    fun `a lone unused namespace import is anchored on the namespace name`() {
        val rows = unused(
            """
            // @Filename: file1.ts
            export class Calculator { }

            // @Filename: t.ts
            import * as ns from "./file1";
            """
        )
        val row = rows.single()
        assert(row.message == "'ns' is declared but its value is never read.")
        assert(row.line == 1)
        assert(row.character == 13)
        assert(row.length == 2)
    }

    /**
     * `import { test as tt } from "./file1";` — the LOCAL name, tsgo `(1,18)` width 2. The
     * aliased form is what separates "anchor on the specifier" from "anchor on its name".
     */
    @Test
    fun `a lone unused aliased import is anchored on the local name`() {
        val rows = unused(
            """
            // @Filename: file1.ts
            export function test() { }

            // @Filename: t.ts
            import { test as tt } from "./file1";
            """
        )
        val row = rows.single()
        assert(row.message == "'tt' is declared but its value is never read.")
        assert(row.line == 1)
        assert(row.character == 18)
        assert(row.length == 2)
    }

    /**
     * Negative control for the import half: TWO unused bindings still group into TS6192 on
     * the whole statement, so the change narrows the ANCHOR and does not retire the
     * grouping. tsgo: `t.ts(1,1): error TS6192` with the statement squiggled.
     */
    @Test
    fun `negative control - two unused bindings still group into TS6192 on the statement`() {
        val diagnostics = diagnose(
            """
            // @Filename: file1.ts
            export class Calculator { }
            export function test() { }

            // @Filename: t.ts
            import { Calculator, test } from "./file1";
            """,
            directives = directives,
        )
        assert(diagnostics.none { it.code == 6133 })
        val row = diagnostics.single { it.code == 6192 }
        assert(row.message == "All imports in import declaration are unused.")
        assert(row.line == 1)
        assert(row.character == 1)
        assert(row.length == 43)
    }

    /**
     * `const { e } = o;` — tsgo `(3,9)` width 1 (the element). tsc 6 anchored `(3,7)` width
     * 5, the `{ e }` pattern, because a one-element pattern in a `VariableDeclarationList`
     * was rerouted through its variable reporter. The leading `export {};` is load-bearing:
     * without it the file is a SCRIPT, its top-level declarations are globals and
     * `noUnusedLocals` does not reach them at all.
     */
    @Test
    fun `a one-element object binding pattern is anchored on the element name`() {
        val rows = unused(
            """
            export {};
            declare const o: any;
            const { e } = o;
            """
        )
        val row = rows.single()
        assert(row.message == "'e' is declared but its value is never read.")
        assert(row.line == 3)
        assert(row.character == 9)
        assert(row.length == 1)
    }

    /** `const { f: g } = o;` — the LOCAL name `g`, tsgo `(3,12)` width 1. */
    @Test
    fun `a renamed one-element binding element is anchored on the local name`() {
        val rows = unused(
            """
            export {};
            declare const o: any;
            const { f: g } = o;
            """
        )
        val row = rows.single()
        assert(row.message == "'g' is declared but its value is never read.")
        assert(row.line == 3)
        assert(row.character == 12)
        assert(row.length == 1)
    }

    /** `const arrow1 = ([a]) => { };` — tsgo `(2,18)` width 1, not the `[a]` pattern. */
    @Test
    fun `a one-element array binding parameter is anchored on the element name`() {
        val rows = unused(
            """
            declare const o: any;
            const arrow1 = ([a]: number[]) => { };
            arrow1;
            """
        )
        val row = rows.single()
        assert(row.message == "'a' is declared but its value is never read.")
        assert(row.line == 2)
        assert(row.character == 18)
        assert(row.length == 1)
    }

    /** `const arrow2 = ({ a2 }) => { };` — tsgo `(2,19)` width 2, not the `{ a2 }` pattern. */
    @Test
    fun `a one-element object binding parameter is anchored on the element name`() {
        val rows = unused(
            """
            const arrow2 = ({ a2 }: { a2: number }) => { };
            arrow2;
            """
        )
        val row = rows.single()
        assert(row.message == "'a2' is declared but its value is never read.")
        assert(row.line == 1)
        assert(row.character == 19)
        assert(row.length == 2)
    }

    /**
     * Negative control for the pattern half: a TWO-element pattern with ONE unused element
     * already anchored on that element before this change and still does — tsgo `(3,9)`.
     * It is the shape the one-element case was diverging from.
     */
    @Test
    fun `negative control - one unused element of a two-element pattern anchors on the element`() {
        val rows = unused(
            """
            export {};
            declare const o: any;
            const { c, d } = o;
            d;
            """
        )
        val row = rows.single()
        assert(row.message == "'c' is declared but its value is never read.")
        assert(row.line == 3)
        assert(row.character == 9)
        assert(row.length == 1)
    }

    /**
     * TS6199 is built for the `VariableDeclarationList`, so `var x, y = 10;` squiggles 13
     * characters and not the statement's 14. tsgo: `t.ts(3,9)` with `var x, y = 10`.
     */
    @Test
    fun `TS6199 spans the declaration list and not the statement's semicolon`() {
        val diagnostics = diagnose(
            """
            class greeter {
                public function1() {
                    var x, y = 10;
                }
            }
            greeter;
            """,
            directives = directives,
        )
        val row = diagnostics.single { it.code == 6199 }
        assert(row.message == "All variables are unused.")
        assert(row.line == 3)
        assert(row.character == 9)
        assert(row.length == 13)
    }

    /**
     * Negative control for the TS6199 span: with no trailing `;` the two spans coincide, so
     * the trim is a `;` rule and not a blanket "one character shorter".
     */
    @Test
    fun `negative control - a semicolon-less declaration list keeps its full TS6199 span`() {
        val diagnostics = diagnose(
            """
            class greeter {
                public function1() {
                    var x, y = 10
                }
            }
            greeter;
            """,
            directives = directives,
        )
        val row = diagnostics.single { it.code == 6199 }
        assert(row.length == 13)
    }

    /**
     * Negative control for the grouping: TS6198 still takes the whole PATTERN, which is the
     * one binding-pattern anchor TypeScript 7 keeps. tsgo: `t.ts(3,7)` width 10.
     */
    @Test
    fun `negative control - TS6198 still spans the whole binding pattern`() {
        val diagnostics = diagnose(
            """
            export {};
            declare const o: any;
            const { p1, p2 } = o;
            """,
            directives = directives,
        )
        assert(diagnostics.none { it.code == 6133 })
        val row = diagnostics.single { it.code == 6198 }
        assert(row.message == "All destructured elements are unused.")
        assert(row.line == 3)
        assert(row.character == 7)
        assert(row.length == 10)
    }
}
