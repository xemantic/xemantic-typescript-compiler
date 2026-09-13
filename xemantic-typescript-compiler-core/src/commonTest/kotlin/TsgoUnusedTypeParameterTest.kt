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
 * (LEGACY.0b) step 2, family F4 — an unused TYPE PARAMETER is TS6196
 * `'X' is declared but never used.` and not TS6133 `'X' is declared but its value is never
 * read.`, and TypeScript 7 anchors it differently from tsc 6.
 *
 * The rule is tsgo's `checkUnusedTypeParameters` / `reportUnusedLocal`: the code is chosen by
 * `ast.IsTypeDeclaration`, which lists `TypeParameter` beside class / interface / type alias /
 * enum; the SPAN is the type-parameter NODE, so it covers a `const`/`in`/`out` modifier and
 * any constraint or default. Where tsc 6 grouped — squiggling the whole `<T>` for a lone
 * unused parameter, or the whole `@template T` tag — TypeScript 7 does not; its only grouping
 * is TS6205 `All type parameters are unused.` over the `<…>` list when the declaration has
 * MORE THAN ONE parameter and every one is unreferenced.
 *
 * Every expectation below was read off `tools/tsgo-7.0.2/lib/tsc` on the fixture in the test.
 */
class TsgoUnusedTypeParameterTest {

    private val directives =
        "// @strict: true\n// @noUnusedLocals: true\n// @noUnusedParameters: true"

    /**
     * The whole point of the family: a type parameter is a TYPE-space entity, so it takes
     * `is declared but never used` and code TS6196. tsc 6 said TS6133 and squiggled `<T>`.
     * Baselines: the `unusedTypeParameter*` and `unusedTypeParameters*` families.
     */
    @Test
    fun `a lone unused type parameter is TS6196 anchored on its own name`() {
        val diagnostics = diagnose(
            """
            function zz1<T>() { }
            zz1;
            """,
            directives = directives,
        )
        val row = diagnostics.single { it.code == 6196 }
        assert(row.message == "'T' is declared but never used.")
        assert(row.character == 14)
        assert(row.length == 1)
    }

    /**
     * The other half of the same predicate — a VALUE-space local keeps TS6133 and its own
     * wording. Without this the change could have been "rename every unused-declaration row".
     */
    @Test
    fun `an unused value local stays TS6133`() {
        val diagnostics = diagnose(
            """
            function zz5() { const unusedLocal = 1; }
            zz5;
            """,
            directives = directives,
        )
        val row = diagnostics.single { it.code == 6133 }
        assert(row.message == "'unusedLocal' is declared but its value is never read.")
        diagnostics should { have(none { it.code == 6196 }) }
    }

    /** One of several parameters unused — per-parameter TS6196, and no TS6205 grouping. */
    @Test
    fun `one unused parameter of several is reported alone`() {
        val diagnostics = diagnose(
            """
            function zz2<X, Y>() { const a: X = null!; a; }
            zz2;
            """,
            directives = directives,
        )
        val row = diagnostics.single { it.code == 6196 }
        assert(row.message == "'Y' is declared but never used.")
        assert(row.character == 17)
        assert(row.length == 1)
        diagnostics should { have(none { it.code == 6205 }) }
    }

    /**
     * More than one parameter and all unused — ONE TS6205 over the `<…>` list, brackets
     * included, and no per-parameter row.
     */
    @Test
    fun `every parameter unused collapses to a single TS6205 over the angle brackets`() {
        val diagnostics = diagnose(
            """
            function zz3<T, U>() { }
            zz3;
            """,
            directives = directives,
        )
        val row = diagnostics.single { it.code == 6205 }
        assert(row.message == "All type parameters are unused.")
        assert(row.character == 13)
        assert(row.length == 6)
        diagnostics should { have(none { it.code == 6196 }) }
    }

    /**
     * An underscore-prefixed parameter counts as USED (tsgo `isUnreferencedTypeParameter`),
     * so a list containing one can never collapse to TS6205 — the boundary that separates the
     * grouping rule from a plain "all declared parameters are unreported" test.
     */
    @Test
    fun `an underscore-prefixed parameter keeps the list out of the TS6205 grouping`() {
        val diagnostics = diagnose(
            """
            function zz4<_T, U>() { }
            zz4;
            """,
            directives = directives,
        )
        val row = diagnostics.single { it.code == 6196 }
        assert(row.message == "'U' is declared but never used.")
        assert(row.character == 18)
        diagnostics should { have(none { it.code == 6205 }) }
    }

    /**
     * The span is the NODE, so a variance modifier is inside it — `out U`, five characters,
     * not the one-character name. Anchoring on the name alone passes every other test here.
     */
    @Test
    fun `a variance modifier is inside the reported span`() {
        val diagnostics = diagnose(
            """
            class ZzD<in T, out U> { m(x: T): void { x; } }
            declare const zd: ZzD<number, string>;
            zd;
            """,
            directives = directives,
        )
        val row = diagnostics.single { it.code == 6196 }
        assert(row.message == "'U' is declared but never used.")
        assert(row.character == 17)
        assert(row.length == 5)
    }

    /**
     * A conditional type's `infer U` is the second emitter of the family and moves with it:
     * TS6196 on the NAME, where tsc 6 said TS6133 over the whole `infer U`.
     * Baseline: `unusedTypeParameters_infer`.
     */
    @Test
    fun `an unused infer parameter is TS6196 anchored on its name`() {
        val diagnostics = diagnose(
            """
            type ZzLen<T> = T extends ArrayLike<infer U> ? number : never;
            declare const zl: ZzLen<string[]>;
            zl;
            """,
            directives = directives,
        )
        val row = diagnostics.single { it.code == 6196 }
        assert(row.message == "'U' is declared but never used.")
        assert(row.character == 43)
        assert(row.length == 1)
    }
}
