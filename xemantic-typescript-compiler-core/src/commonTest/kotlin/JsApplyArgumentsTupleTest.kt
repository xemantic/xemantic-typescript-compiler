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
 * (LEGACY.0b) step 36, 2026-09-20 — `f.apply(x, arguments)`'s parameter-TUPLE display, and
 * the ledger row `argumentsReferenceInFunction1_Js.errors.txt`.
 *
 * **TypeScript 7 gives a JS function NO implicit `...any[]` rest.** `strictBindCallApply`
 * types `apply`'s second parameter as the receiver's parameter tuple, and tsc 6 inferred an
 * un-annotated JS function that referenced `arguments` as `[p?: any, …, ...any[]]`. The
 * corpus-unique walker B230 transcribed that tail. Measured against tsgo 7.0.2: the tuple is
 * `[a?: any]`, and the arity of such a function is `0-1` — with or without an `arguments`
 * reference in its body, which is the observation that retires the tail rather than narrowing it.
 *
 * **What the surviving `bodyMentionsArguments` gate now means.** It used to select the shape
 * that GOT the implicit rest. With no such rest it selects nothing semantic at all — it is a
 * CONFINEMENT to the receivers whose tuple this hardcoded display renders exactly. That is
 * worth stating because the gate is a plain substring test over the body text, so it fires
 * for a body that only mentions `arguments` inside a STRING; under TypeScript 7 that is
 * harmless, because tsgo reports there too.
 *
 * **MEASURED AND RECORDED — the walker sits in front of a much larger gap.** tsgo types every
 * `f.apply(x, arguments)` from the receiver's real signature: 8 of 8 scratch cells report where
 * these gates leave us silent for all but one. A REST receiver renders `any[]` and changes the
 * CODE to TS2740; a defaulted parameter renders `[a?: number | undefined]`; a JSDoc-tagged or
 * TS-annotated one renders `[a: number]`. That is the general `bindCallApplyType` path
 * ((CHK.134)), not a transcription — so the JSDoc-tagged receiver is REFUSED here instead of
 * rendered wrong, at the cost of one MISSING row, pinned below as a residue.
 */
class JsApplyArgumentsTupleTest {

    private fun js(@Language("typescript") source: String) = diagnose(
        source,
        directives = "// @target: es2015\n// @strict: true\n// @allowJs: true\n// @checkJs: true",
        fileName = "index.js",
    )

    private val List<Diagnostic>.applyRows: List<String>
        get() = filter { it.code == 2345 || it.code == 2740 }.map { it.message }

    /**
     * The ledger row's own fixture (`argumentsReferenceInFunction1_Js`), reduced to the two
     * declarations that decide it. tsgo renders `[f?: any]`; the tail is what diverged.
     */
    @Test
    fun `the tuple carries no implicit any-rest`() {
        val rows = js(
            """
            const format = function(f) {
              var args = arguments;
              return args.length + f;
            };

            const debuglog = function() {
              return format.apply(null, arguments);
            };
            """
        ).applyRows
        assert(
            rows == listOf(
                "Argument of type 'IArguments' is not assignable to parameter of type '[f?: any]'."
            )
        )
    }

    @Test
    fun `every parameter is rendered, and all of them optional`() {
        val rows = js(
            """
            const m3 = function (a, b) { var q = arguments; return a; };
            const n3 = function () { return m3.apply(null, arguments); };
            """
        ).applyRows
        assert(
            rows == listOf(
                "Argument of type 'IArguments' is not assignable to parameter of type " +
                    "'[a?: any, b?: any]'."
            )
        )
    }

    @Test
    fun `a FunctionDeclaration receiver renders the same tuple`() {
        val rows = js(
            """
            function m3(a, b) { var q = arguments; return a; }
            const n3 = function () { return m3.apply(null, arguments); };
            """
        ).applyRows
        assert(
            rows == listOf(
                "Argument of type 'IArguments' is not assignable to parameter of type " +
                    "'[a?: any, b?: any]'."
            )
        )
    }

    /**
     * The gate is a substring test over the body TEXT, so a body whose only `arguments` is
     * inside a string literal still selects the receiver — and tsgo reports there too, so the
     * imprecision cannot produce a divergence. A control for the gate, not a claim about it.
     */
    @Test
    fun `control - the gate is textual, and under TypeScript 7 that is harmless`() {
        val rows = js(
            """
            const m4 = function (a) { return "arguments" + a; };
            const n4 = function () { return m4.apply(null, arguments); };
            """
        ).applyRows
        assert(
            rows == listOf(
                "Argument of type 'IArguments' is not assignable to parameter of type '[a?: any]'."
            )
        )
    }

    /**
     * residue - a JSDoc-tagged receiver is REFUSED rather than rendered wrong. tsgo types the
     * parameter from the tag and prints `[a: number]` — required and typed — which this
     * hardcoded `?: any` rendering cannot express. Before this round the row was printed with
     * BOTH errors (`[a?: any, ...any[]]`); it is now missing, which is the conservative half
     * of the same divergence and is what the general `bindCallApplyType` path closes.
     */
    @Test
    fun `residue - a JSDoc-tagged receiver is refused, not rendered wrong`() {
        val rows = js(
            """
            /** @param {number} a */
            const m2 = function (a) { var q = arguments; return a; };
            const n2 = function () { return m2.apply(null, arguments); };
            """
        ).applyRows
        assert(rows.isEmpty())
    }

    /**
     * residue - a receiver whose body never mentions `arguments` is outside the gate, where
     * tsgo reports `[a?: any]`. One of the 8-of-8 cells the general path owns.
     */
    @Test
    fun `residue - a receiver that does not mention arguments is outside the gate`() {
        val rows = js(
            """
            const k1 = function (a) { return a; };
            const w1 = function () { return k1.apply(null, arguments); };
            """
        ).applyRows
        assert(rows.isEmpty())
    }

    /**
     * residue - a REST receiver is a different CODE, not a different tuple: tsgo's `apply`
     * parameter is `any[]` there, so it reports TS2740 rather than TS2345. The walker declines
     * it, which is why no wrong code is printed.
     */
    @Test
    fun `residue - a rest-parameter receiver is TS2740 in tsgo and silent here`() {
        val rows = js(
            """
            const k4 = function (...a) { var q = arguments; return a; };
            const w4 = function () { return k4.apply(null, arguments); };
            """
        ).applyRows
        assert(rows.isEmpty())
    }
}
