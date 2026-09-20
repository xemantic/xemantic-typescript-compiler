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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (LEGACY.0b) step 35, 2026-09-20 — tsgo's `checkUnmatchedJSDocParameters`
 * (`internal/checker/jsdoc.go`), measured cell by cell against tsgo 7.0.2 and closing the
 * ledger row `noParameterReassignmentIIFEAnnotated.errors.txt`.
 *
 * **The check has TWO branches, chosen by whether the function reads `arguments`, and they
 * report different CODES over different TAGS.** The whole family was one branch here — a
 * TS8024 per unmatched tag, with no `arguments` question asked anywhere — plus a
 * corpus-unique walker (B558) that emitted TS8029 for a VARIADIC `@param {...T}` tag, which
 * is the one shape tsgo can never report, because a variadic tag IS an array type.
 *
 * Reads `arguments`:
 *  - only the **LAST** tag can report, and it reports **TS8029** (*…It would match
 *    'arguments' if it had an array type.*);
 *  - it is silent when that tag names a real parameter, is qualified, carries NO type, or
 *    carries an **array** type (`{...T}`, `{T[]}`, `Array<T>`, `ReadonlyArray<T>`);
 *  - every EARLIER tag is silent whatever it names — so one matching last tag suppresses
 *    the whole function;
 *  - `isNameFirst` is NOT consulted here, where the other branch skips such a tag.
 *
 * Does not read `arguments`: TS8024 per unmatched tag, skipping a NAME-FIRST tag
 * (`@param n {T}`, and the type-less `@param n`) and a tag whose index is that of a
 * BINDING-PATTERN parameter.
 *
 * **`arguments` is read only through tsgo's own lexical-environment rule**, and that rule
 * puts `ArrowFunction` on the list — so a tag on a function whose only `arguments` sits in a
 * nested ARROW reports TS8024, not TS8029, even though JavaScript gives that arrow the
 * enclosing function's `arguments`. Measured, not inferred.
 *
 * RESIDUES, each measured against tsgo and each leaving the row it already had:
 *  - a type ALIAS that resolves to an array (`@typedef {number[]} Nums`) reads as a
 *    non-array here, because JSDoc types are text at this layer — ours-only TS8029 where
 *    tsgo is silent (it was an ours-only TS8024 before);
 *  - a tag attached to the enclosing `VariableStatement` rather than to the function
 *    expression it initializes is not seen by the walker at all — a MISSING row, unchanged
 *    by this round;
 *  - a QUALIFIED tag name is **TS8032** *Qualified name '{0}' is not allowed without a
 *    leading '@param {object} {1}'* in tsgo and silent here; that diagnostic has no emitter
 *    in this compiler, so it is its own family.
 */
class JsDocUnmatchedParamArgumentsTest {

    private fun js(@Language("typescript") source: String) =
        diagnose(source, directives = "// @allowJs: true\n// @checkJs: true", fileName = "t.js")

    private val List<Diagnostic>.unmatchedParamRows: List<Pair<Int, String>>
        get() = filter { it.code == 8024 || it.code == 8029 }
            .map { it.code to it.message }

    // ── the `arguments` branch: TS8029, and only for the LAST tag ────────────────────

    @Test
    fun `a non-array tag on a function reading arguments is TS8029`() {
        val rows = js(
            """
            /** @param {number} rest */
            function c2() { return arguments.length; }
            """
        ).unmatchedParamRows
        assert(
            rows == listOf(
                8029 to "JSDoc '@param' tag has name 'rest', but there is no parameter with " +
                    "that name. It would match 'arguments' if it had an array type."
            )
        )
    }

    /**
     * The ledger row's own mechanism: `noParameterReassignmentIIFEAnnotated` carries
     * `@param {...unknown} rest` on a function whose body is `importScripts.apply(this,
     * arguments)`, and tsgo reports NOTHING for it.
     */
    @Test
    fun `a VARIADIC tag on a function reading arguments is silent - it is an array type`() {
        val rows = js(
            """
            /** @param {...unknown} rest */
            function c1() { return arguments.length; }
            """
        ).unmatchedParamRows
        assert(rows.isEmpty())
    }

    @Test
    fun `an explicit array tag on a function reading arguments is silent`() {
        val bracket = js(
            """
            /** @param {number[]} rest */
            function c3() { return arguments.length; }
            """
        ).unmatchedParamRows
        val generic = js(
            """
            /** @param {Array<number>} rest */
            function d4() { return arguments.length; }
            """
        ).unmatchedParamRows
        val readonly = js(
            """
            /** @param {ReadonlyArray<number>} rest */
            function d8() { return arguments.length; }
            """
        ).unmatchedParamRows
        assert(bracket.isEmpty())
        assert(generic.isEmpty())
        assert(readonly.isEmpty())
    }

    /**
     * A union is not a `Type.Reference` on `Array`, however its last member is spelled —
     * so a top-level `|` refuses the array shortcut and the row is reported.
     */
    @Test
    fun `a union whose last member is an array is not an array type`() {
        val rows = js(
            """
            /** @param {number|string[]} rest */
            function u1() { return arguments.length; }
            """
        ).unmatchedParamRows
        assert(rows.size == 1)
        assert(rows[0].first == 8029)
    }

    @Test
    fun `only the LAST tag reports when the function reads arguments`() {
        val rows = js(
            """
            /** @param {number} a
             *  @param {number} b */
            function c5() { return arguments.length; }
            """
        ).unmatchedParamRows
        assert(rows.size == 1)
        assert(rows[0].first == 8029)
        assert(rows[0].second.contains("has name 'b'"))
    }

    /**
     * tsgo RETURNS from the whole check when the last tag names a real parameter — so the
     * earlier unmatched `nope` is silent too. A per-tag rule cannot express this.
     */
    @Test
    fun `a matching LAST tag suppresses an earlier unmatched one`() {
        val rows = js(
            """
            /** @param {number} nope
             *  @param {number} a */
            function d6(a) { return arguments.length; }
            """
        ).unmatchedParamRows
        assert(rows.isEmpty())
    }

    @Test
    fun `a tag with no type at all is silent on the arguments branch`() {
        val rows = js(
            """
            /** @param rest */
            function c7() { return arguments.length; }
            """
        ).unmatchedParamRows
        assert(rows.isEmpty())
    }

    /**
     * `isNameFirst` gates the TS8024 branch and NOT this one — measured: tsgo reports
     * TS8029 for `@param nope {number}` on a function reading `arguments`, and nothing for
     * the same tag on a function that does not.
     */
    @Test
    fun `a NAME-FIRST tag still reports on the arguments branch`() {
        val withArgs = js(
            """
            /** @param nope {number} */
            function g3() { return arguments.length; }
            """
        ).unmatchedParamRows
        val without = js(
            """
            /** @param nope {number} */
            function g2() { return 1; }
            """
        ).unmatchedParamRows
        assert(withArgs.size == 1)
        assert(withArgs[0].first == 8029)
        assert(without.isEmpty())
    }

    // ── tsgo's lexical-environment rule decides what "reads arguments" means ─────────

    /**
     * `ArrowFunction` is on tsgo's `nodeStartsNewLexicalEnvironment` list, so the nested
     * arrow's `arguments` does NOT belong to `d1` — even though at run time it is `d1`'s.
     */
    @Test
    fun `arguments inside a nested ARROW does not reach the enclosing function`() {
        val rows = js(
            """
            /** @param {number} rest */
            function d1() { return (() => arguments.length)(); }
            """
        ).unmatchedParamRows
        assert(rows.size == 1)
        assert(rows[0].first == 8024)
    }

    @Test
    fun `arguments inside a nested FUNCTION does not reach the enclosing function`() {
        val rows = js(
            """
            /** @param {number} rest */
            function d2() { return function () { return arguments.length; }; }
            """
        ).unmatchedParamRows
        assert(rows.size == 1)
        assert(rows[0].first == 8024)
    }

    @Test
    fun `arguments inside a nested CLASS METHOD does not reach the enclosing function`() {
        val rows = js(
            """
            /** @param {number} rest */
            function e3() { class K { m() { return arguments.length; } } return K; }
            """
        ).unmatchedParamRows
        assert(rows.size == 1)
        assert(rows[0].first == 8024)
    }

    /** A MEMBER named `arguments` is a name, not a reference — only the receiver is read. */
    @Test
    fun `a member named arguments is not a reference`() {
        val rows = js(
            """
            /** @param {number} rest */
            function m1(o) { return o.arguments; }
            """
        ).unmatchedParamRows
        assert(rows.size == 1)
        assert(rows[0].first == 8024)
    }

    @Test
    fun `a name that merely starts with arguments is not a reference`() {
        val rows = js(
            """
            /** @param {number} rest */
            function e5() { var arguments2 = 1; return arguments2; }
            """
        ).unmatchedParamRows
        assert(rows.size == 1)
        assert(rows[0].first == 8024)
    }

    /** A METHOD reading `arguments` takes the TS8029 branch exactly as a function does. */
    @Test
    fun `a class method reading arguments takes the TS8029 branch`() {
        val rows = js(
            """
            class D3 {
              /** @param {number} rest */
              m() { return arguments.length; }
            }
            """
        ).unmatchedParamRows
        assert(rows.size == 1)
        assert(rows[0].first == 8029)
    }

    // ── the TS8024 branch: the two skips tsgo has and this walker did not ───────────

    @Test
    fun `control - a non-array tag without arguments is still TS8024`() {
        val rows = js(
            """
            /** @param {number} rest */
            function c4() { return 1; }
            """
        ).unmatchedParamRows
        assert(
            rows == listOf(
                8024 to "JSDoc '@param' tag has name 'rest', but there is no parameter with that name."
            )
        )
    }

    /**
     * A VARIADIC tag is an array type and still reports on THIS branch — the array test
     * belongs to the `arguments` rung alone, which is what separates it from a blanket
     * "variadic tags are exempt" rule.
     */
    @Test
    fun `control - a VARIADIC tag without arguments is still TS8024`() {
        val rows = js(
            """
            /** @param {...unknown} rest */
            function c6() { return 1; }
            """
        ).unmatchedParamRows
        assert(rows.size == 1)
        assert(rows[0].first == 8024)
    }

    /** `@param n {T}` and the type-less `@param n` are both name-first, and tsgo skips both. */
    @Test
    fun `a NAME-FIRST tag is skipped on the TS8024 branch`() {
        val typed = js(
            """
            /** @param nope {number} */
            function g2() { return 1; }
            """
        ).unmatchedParamRows
        val untyped = js(
            """
            /** @param nope */
            function g1() { return 1; }
            """
        ).unmatchedParamRows
        assert(typed.isEmpty())
        assert(untyped.isEmpty())
    }

    /** tsgo's `excludedParameters`: the tag at the index of a BINDING-PATTERN parameter. */
    @Test
    fun `a tag at the index of a binding-pattern parameter is skipped`() {
        val withoutArgs = js(
            """
            /** @param {number} nope */
            function f1({ a }) { return a; }
            """
        ).unmatchedParamRows
        val withArgs = js(
            """
            /** @param {number} nope */
            function f4({ a }) { return arguments.length; }
            """
        ).unmatchedParamRows
        assert(withoutArgs.isEmpty())
        assert(withArgs.isEmpty())
    }

    /** A bracketed OPTIONAL name is still an unmatched name — brackets exempt nothing here. */
    @Test
    fun `control - a bracketed optional name still reports, in both branches`() {
        val withoutArgs = js(
            """
            /** @param {number} [nope] */
            function g4() { return 1; }
            """
        ).unmatchedParamRows
        val withArgs = js(
            """
            /** @param {number} [nope] */
            function g5() { return arguments.length; }
            """
        ).unmatchedParamRows
        assert(withoutArgs.size == 1)
        assert(withoutArgs[0].first == 8024)
        assert(withArgs.size == 1)
        assert(withArgs[0].first == 8029)
    }

    // ── the two reference fixtures ──────────────────────────────────────────────────

    /**
     * `paramTagOnFunctionUsingArguments` (tsgo baseline
     * `submodule/conformance/paramTagOnFunctionUsingArguments.errors.txt`), reconstructed:
     * its CASE file is absent from this repo's sparse `typescript-repo` clone, so the
     * corpus cannot gate it and this pin is its only instrument.
     *
     * It carries BOTH branches' decisive cells in one file — `concat`'s `{string}` reports
     * and `correct`'s `{...string}` does not — which is why a walker that got either rung
     * wrong could not pass it.
     */
    @Test
    fun `paramTagOnFunctionUsingArguments - both cells in one file`() {
        val rows = js(
            """
            /**
             * @param {string} first
             */
            function concat(/* first, second, ... */) {
              var s = ''
              for (var i = 0, l = arguments.length; i < l; i++) {
                s += arguments[i]
              }
              return s
            }

            /**
             * @param {...string} strings
             */
            function correct() {
                arguments
            }
            """
        ).unmatchedParamRows
        assert(
            rows == listOf(
                8029 to "JSDoc '@param' tag has name 'first', but there is no parameter with " +
                    "that name. It would match 'arguments' if it had an array type."
            )
        )
    }

    /**
     * The ledger row itself. tsgo reports exactly two diagnostics — a TS2683 and a TS2740 —
     * and no JSDoc row at all. The TS2683 count is the guard on the OTHER half of this
     * round: the `importScripts.apply` pin walker used to hardcode a second copy of it,
     * which stayed invisible for as long as the baseline also mismatched on the TS8029.
     */
    @Test
    fun `noParameterReassignmentIIFEAnnotated - no JSDoc row, and exactly one TS2683`() {
        val ds = diagnose(
            """
            self.importScripts = (function (importScripts) {
                /**
                 * @param {...unknown} rest
                 */
                return function () {
                    return importScripts.apply(this, arguments);
                };
            })(importScripts);
            """,
            directives = "// @target: es2015\n// @allowJs: true\n// @checkJs: true",
            fileName = "index.js",
        )
        assert(ds.unmatchedParamRows.isEmpty())
        assert(ds.count { it.code == 2683 } == 1)
    }
}
