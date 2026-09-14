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
 * (LEGACY.0b) step 10, the JS-EMIT family: three PRINTER rules of TypeScript 7 that this
 * compiler did not have, closing 12 `tsgoPendingBaselines` rows between them.
 *
 *  1. **An instantiation expression prints as its operand, bare.** The parser wraps
 *     `expr<T>` in a synthetic [ParenthesizedExpression] so the checker can squiggle it as
 *     a unit; that paren is not in the source and must not be in the output.
 *  2. **A `;` recovered as an object-literal separator is a separator like any other**, so
 *     a `;` immediately before `}` is a TRAILING separator and prints as a trailing comma.
 *  3. **tsc's `parenthesizeExpressionOfNew`**: a callee whose leftmost expression is a
 *     `new` with NO argument list is parenthesized — `new new D` prints `new (new D)`.
 *
 * **EVERY EXPECTATION HERE WAS MEASURED AGAINST `tools/tsgo-7.0.2/lib/tsc`**, not derived
 * from what this compiler now happens to print: the four fixtures below were run through
 * both compilers and are byte-identical, at `es2020` and (for the two chain shapes) at the
 * `es2019` downlevel as well.
 *
 * ## The negative controls are the point
 *
 * Each rule has a neighbour it must NOT touch, and in every case the neighbour's source
 * text differs by one character:
 *
 *  * `maybeBox instanceof (Box<number>)` keeps exactly one paren — an AUTHOR's paren is a
 *    separate node carrying no `instantiationEnd`, and a rule that dropped it would be
 *    invisible to any fixture that only spells the paren-less form;
 *  * `a?.b<c>.d` MUST print `(a?.b).d` — there the paren is semantically load-bearing,
 *    because the `<c>` ends the optional chain and the `.d` applies to its RESULT. The
 *    first cut of rule 1 dropped it, and the corpus screen's emit channel caught it as two
 *    moved green baselines (`optionalChainWithInstantiationExpression1` at both targets)
 *    before any of this was committed;
 *  * `{ a; b; c }` (no trailing `;`) prints WITHOUT the trailing comma, and a parameter or
 *    argument list drops a genuine trailing comma where an object literal keeps it —
 *    measured, not assumed;
 *  * `new new D()` and `new new D().x` keep their shape, because the leftmost `new` there
 *    HAS an argument list.
 */
class InstantiationAndListEmitTest {

    private fun emit(
        @Language("typescript") body: String,
        target: String = "es2020",
    ): String {
        val source = """
            // @target: $target
            // @module: esnext
            // @filename: m.ts
            ${body.trimIndent().replace("\n", "\n            ")}
        """.trimIndent()
        return TypeScriptCompiler().compile(source, "m.ts")
            .jsOutputs.joinToString("\n") { it.second }.trim()
    }

    private val prelude = """
        declare class Box<T> { value: T }
        declare const maybeBox: unknown;
        declare function foo<T>(x: T): T;
        let obj: { fn?: <T>() => T } = {};
    """.trimIndent()

    @Test
    fun `a value-position instantiation expression prints its operand with no parentheses`() {
        val js = emit("$prelude\nobj.fn<number> = () => 1234;\nconst b = foo<string>;")
        assert(js == "\"use strict\";\nlet obj = {};\nobj.fn = () => 1234;\nconst b = foo;")
    }

    @Test
    fun `an instantiation expression on the right of instanceof prints bare`() {
        val js = emit("$prelude\nmaybeBox instanceof Box<number>;")
        assert(js == "\"use strict\";\nlet obj = {};\nmaybeBox instanceof Box;")
    }

    @Test
    fun `negative control - a paren the author wrote around an instantiation expression survives`() {
        val js = emit("$prelude\nmaybeBox instanceof (Box<number>);")
        assert(js == "\"use strict\";\nlet obj = {};\nmaybeBox instanceof (Box);")
    }

    @Test
    fun `a JSDoc-nullable type argument list is erased like any other`() {
        // expressionWithJSDocTypeArguments: TypeScript 6 re-printed `foo<?>`; 7.0.2 does not.
        val js = emit("$prelude\nconst w = foo<?>;\nconst w2 = foo<string?>;")
        assert(js == "\"use strict\";\nlet obj = {};\nconst w = foo;\nconst w2 = foo;")
    }

    private val chain = """
        declare namespace A { export class b<T> { static d: number; constructor(x: T); } }
        type c = unknown;
        declare const a: typeof A | undefined;
    """.trimIndent()

    @Test
    fun `an instantiation expression ENDS an optional chain so its paren is printed`() {
        val js = emit("$chain\na?.b<c>.d;\na?.b.d;")
        assert(js == "\"use strict\";\n(a?.b).d;\na?.b.d;")
    }

    @Test
    fun `the chain-ending paren survives the downlevel lowering too`() {
        val js = emit("$chain\na?.b<c>.d;", target = "es2019")
        assert(js == "\"use strict\";\n(a === null || a === void 0 ? void 0 : a.b).d;")
    }

    @Test
    fun `an optional CALL on an instantiation expression drops the paren at both targets`() {
        val decl = "declare const a: (<T>() => void) | undefined;"
        assert(emit("$decl\na<number>?.();") == "\"use strict\";\na?.();")
        // Downlevel: the temp-assignment paren the lowering builds is its own node and stays;
        // the synthetic instantiation paren inside it does not (`_a = a`, never `_a = (a)`).
        assert(
            emit("$decl\na<number>?.();", target = "es2019") ==
                "\"use strict\";\nvar _a;\n(_a = a) === null || _a === void 0 ? void 0 : _a();"
        )
    }

    @Test
    fun `a semicolon recovered before the closing brace prints as a trailing comma`() {
        val js = emit("declare var b: any;\nvar v = { a; b; c; }")
        assert(js == "\"use strict\";\nvar v = { a, b, c, };")
    }

    @Test
    fun `negative control - no trailing semicolon means no trailing comma`() {
        val js = emit("declare var b: any;\nvar v = { a; b; c }")
        assert(js == "\"use strict\";\nvar v = { a, b, c };")
    }

    @Test
    fun `the recovery covers methods and accessors, not only shorthand properties`() {
        val js = emit("declare var b: any;\nvar v = { foo() { }; a: b; get baz() { }; }")
        assert(js == "\"use strict\";\nvar v = { foo() { }, a: b, get baz() { }, };")
    }

    @Test
    fun `negative control - a genuine object trailing comma is kept and a call's is dropped`() {
        val js = emit("var v3 = { a: 1, };\nfunction g(p: number, ) {}\ng(1, );")
        assert(js == "\"use strict\";\nvar v3 = { a: 1, };\nfunction g(p) { }\ng(1);")
    }

    @Test
    fun `a new whose callee is an argument-less new is parenthesized`() {
        val js = emit("declare class D { constructor(); }\nvar n1 = new new D;")
        assert(js == "\"use strict\";\nvar n1 = new (new D);")
    }

    @Test
    fun `negative control - an argument-list-bearing inner new is not parenthesized`() {
        val js = emit(
            "declare class D { constructor(); }\n" +
                "var n2 = new new D();\nvar n3 = new new D().x;\nvar n4 = new (new D).x;"
        )
        assert(
            js == "\"use strict\";\nvar n2 = new new D();\nvar n3 = new new D().x;\n" +
                "var n4 = new (new D).x;"
        )
    }
}
