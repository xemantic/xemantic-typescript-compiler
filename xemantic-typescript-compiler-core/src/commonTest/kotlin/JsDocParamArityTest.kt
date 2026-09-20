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
 * (LEGACY.0b) A JSDoc `@param` tag makes its parameter REQUIRED, and a JS parameter
 * without one stays optional.
 *
 * ## The rule, measured cell by cell against `tools/tsgo-7.0.2/lib/tsc`
 *
 * | tag | arity |
 * |---|---|
 * | `@param {T} n` | REQUIRED |
 * | `@param {T} [n]` | optional |
 * | `@param {T} [n=1]` | optional |
 * | `@param {T=} n` | optional |
 * | `@param {...T} n` | REQUIRED, type `T[]`, arity still exactly ONE |
 * | (no tag) | optional |
 *
 * ## Why this needed a round at all — the rule was already implemented
 *
 * `Checker.jsDocRequiredParamNames` and `paramInfo`'s `jsDocRequiredNames` parameter
 * are B434's, and they were wired to exactly ONE of the five arity sites: the
 * CROSS-FILE map. So the identical two lines measured byte-identical to tsgo when
 * the declaration and the call lived in two `.js` files and read `Expected 0-1`
 * when they lived in one. What made that hard to find is that the last site to
 * write the map WINS: `collectFuncDecls` computed the correct entry and the
 * nested-function OVERLAY in `spineArgCtxAt` then recomputed it without the tag
 * set and clobbered it, so wiring the obvious site changed nothing at all.
 *
 * ## The variadic cell is a PARSER change, and it retired a walker
 *
 * `@param {...T}` used to be reparsed as a REST parameter (B437's `restNames`),
 * which makes arity unbounded — so the two TS2554 rows of the `jsdocRestParameter`
 * baseline could not fire, and a corpus-unique walker emitted a TS2345 tsgo does
 * not produce to fill the gap. tsgo types the parameter `T[]` and leaves it an
 * ordinary required parameter; ours already typed it `T[]`, so only the rest
 * marking was wrong. The walker was PassLab-priced at one mismatch (its own
 * pending baseline) and 0 collateral before being deleted.
 *
 * ## What the ablation says (one mistake at a time, 7 tests per arm)
 *
 * | arm | RED |
 * |---|---|
 * | un-wire the nested-function OVERLAY in `spineArgCtxAt` | **4** |
 * | un-wire `collectFuncDecls`' body-bearing site | **0 — undiscriminated** |
 * | drop the `{T=}` suffix rule | 1 — the optional-spellings pin |
 * | type a variadic tag `T` instead of `T[]` | 1 — the array-type pin |
 *
 * The second is recorded rather than claimed: wherever the overlay runs it writes
 * LAST and wins, which is the whole reason this took a round to find, so no fixture
 * here can separate the two. The wiring stays for two reasons — every arity site
 * now goes through one helper, so a site added later cannot half-wire it the way
 * B434 did; and the overlay deliberately skips an entry already marked
 * `isOverloaded`, so an overloaded JS function's arity comes from `collectFuncDecls`
 * alone.
 */
class JsDocParamArityTest {

    private val directives = "// @allowJs: true\n// @checkJs: true\n// @strict: true"

    private fun arityRows(body: String): List<String> =
        diagnose(body, directives = directives, fileName = "a.js")
            .filter { it.code == 2554 }
            .map { it.message }

    @Test
    fun `a plain JSDoc param tag makes its parameter required`() {
        assert(
            arityRows("/** @param {number} p */\nfunction f(p) {}\nf();") ==
                listOf("Expected 1 arguments, but got 0."),
        )
    }

    /**
     * The three optional spellings. Bracketed and defaulted were already B434's;
     * the `{T=}` type suffix is (LEGACY.0b)'s addition and is the one an
     * implementation reading only the brackets gets wrong.
     */
    @Test
    fun `the three optional spellings leave the parameter optional`() {
        assert(arityRows("/** @param {number} [p] */\nfunction f(p) {}\nf();").isEmpty())
        assert(arityRows("/** @param {number} [p=1] */\nfunction f(p) {}\nf();").isEmpty())
        assert(arityRows("/** @param {number=} p */\nfunction f(p) {}\nf();").isEmpty())
    }

    /**
     * The negative control, and it is what the whole rule rests on: an UNTAGGED
     * parameter in a JS file is optional, so this cannot be satisfied by an
     * implementation that simply made every JS parameter required.
     */
    @Test
    fun `an untagged JS parameter stays optional`() {
        assert(arityRows("function f(p) {}\nf();").isEmpty())
    }

    /** Two tags, two required parameters — the count, not just the flag. */
    @Test
    fun `every tagged parameter counts toward the required arity`() {
        assert(
            arityRows("/** @param {number} a @param {number} b */\nfunction f(a, b) {}\nf(1);") ==
                listOf("Expected 2 arguments, but got 1."),
        )
    }

    /**
     * The variadic cell: `{...T}` is REQUIRED and its arity is exactly one — it is
     * not a rest parameter, which is where tsgo and TypeScript 6 part company.
     *
     * Both directions are asserted because only the pair discriminates: the
     * too-many row fails if the parameter is still marked rest (unbounded arity),
     * and the too-few row fails if it is treated as optional.
     */
    @Test
    fun `a variadic JSDoc param is required and is not a rest parameter`() {
        val rows = arityRows("/** @param {...number} a */\nfunction f(a) {}\nf();\nf(1, 2);")
        assert(
            rows == listOf(
                "Expected 1 arguments, but got 0.",
                "Expected 1 arguments, but got 2.",
            ),
        )
    }

    /**
     * …and it is typed `T[]`, which is what makes `f([1, 2])` legal — the half we
     * already had, pinned so the parser change cannot silently take it away.
     */
    @Test
    fun `a variadic JSDoc param is typed as an array of its element`() {
        val d = diagnose(
            "/** @param {...number} a */\nfunction f(a) {\n  /** @type {string} */\n  const probe = a;\n}",
            directives = directives,
            fileName = "a.js",
        ).filter { it.code == 2322 }.map { it.message }
        assert(d == listOf("Type 'number[]' is not assignable to type 'string'."))
    }

    /**
     * The ordering pin: the same declaration and call in ONE file must answer what
     * they answer in TWO. Before (LEGACY.0b) the cross-file map was the only wired
     * site, so this was `Expected 0-1` while the two-file spelling was `Expected 1`
     * — a divergence no reference disagreed with us about, because tsgo answers the
     * same thing for both.
     */
    @Test
    fun `the same-file and cross-file answers agree`() {
        val sameFile = arityRows("/** @param {number} p */\nfunction f(p) {}\nf(1, 2);")
        val crossFile = diagnose(
            "// @Filename: lib.js\n/** @param {number} p */\nfunction f(p) {}\n" +
                "// @Filename: use.js\nf(1, 2);",
            directives = directives,
        ).filter { it.code == 2554 }.map { it.message }
        assert(sameFile == listOf("Expected 1 arguments, but got 2."))
        assert(crossFile == sameFile)
    }
}
