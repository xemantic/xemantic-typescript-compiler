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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.ParserFlags
import kotlin.test.Test

/**
 * (API.6) [SourceIndex.signatureAnchorAt] — WHICH call the caret is in and WHICH
 * argument slot it occupies, decided from the token stream and the parse with no
 * checker anywhere near it.
 *
 * The cheapest and sharpest pins in the feature, and deliberately written first: an
 * anchor that names the wrong call or the wrong argument produces a signature list
 * that looks entirely plausible, so nothing downstream can catch it. Each assertion
 * names BOTH the call's kind and the argument index, because an implementation that
 * gets the call right and the index wrong is the likelier mistake.
 *
 * Every offset is derived by `indexOf` plus an explicit caret marker, never
 * hardcoded: a hardcoded offset pins this file's arithmetic and would pass for an
 * implementation that ignored its argument.
 */
class SignatureAnchorTest {

    /** U+2038 CARET — the caret marker. `|` is a union separator (round 917). */
    private val CARET = '‸'

    private fun indexOf(text: String): SourceIndex = SourceIndex.of(
        text,
        "/t.ts",
        ParserFlags(
            forceJsx = false,
            topLevelAwait = false,
            needsJsxFlag = false,
            noImplicitAny = false,
        ),
    )

    /** The anchor at the caret marked `‸` in [marked], which is stripped first. */
    private fun anchorAt(marked: String): SignatureAnchor? {
        val caret = marked.indexOf(CARET)
        assert(caret >= 0)
        assert(marked.indexOf(CARET, caret + 1) < 0)
        val text = marked.substring(0, caret) + marked.substring(caret + 1)
        return indexOf(text).signatureAnchorAt(caret)
    }

    // --- the argument index table -------------------------------------------------

    @Test
    fun `an empty argument list is argument 0`() {
        val anchor = anchorAt("declare function f(a: number): void;\nf(‸);\n")
        assert(anchor != null)
        assert(anchor.call.kind.name == "CallExpression")
        assert(anchor.activeArgument == 0)
    }

    @Test
    fun `a caret after a comma and a space is the NEXT argument`() {
        val anchor = anchorAt("declare function f(a: number, b: number): void;\nf(1, ‸);\n")
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
    }

    @Test
    fun `a caret immediately after a comma is the next argument too`() {
        // No space: the index is a count of COMMAS, so whitespace cannot matter.
        val anchor = anchorAt("declare function f(a: number, b: number): void;\nf(1,‸);\n")
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
    }

    @Test
    fun `a caret at the END of the last argument is still that argument`() {
        // THE CASE A CONTAINMENT TEST GETS WRONG: spans are half-open, so the caret
        // is outside `two` and inside the call — and yet the answer is argument 1,
        // because only one comma precedes it.
        val anchor = anchorAt(
            "declare function f(a: number, b: number): void;\nconst two = 2;\nf(1, two‸);\n",
        )
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
    }

    @Test
    fun `a caret inside the last argument is that argument`() {
        val anchor = anchorAt(
            "declare function f(a: number, b: number): void;\nconst two = 2;\nf(1, tw‸o);\n",
        )
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
    }

    @Test
    fun `two commas make argument 2`() {
        val anchor = anchorAt(
            "declare function f(a: number, b: number, c: number): void;\nf(1, 2, ‸);\n",
        )
        assert(anchor != null)
        assert(anchor.activeArgument == 2)
    }

    @Test
    fun `a caret at the closing paren is still in the argument list`() {
        val anchor = anchorAt("declare function f(a: number): void;\nf(1‸);\n")
        assert(anchor != null)
        assert(anchor.activeArgument == 0)
    }

    @Test
    fun `a caret spread over newlines counts commas the same way`() {
        val anchor = anchorAt(
            "declare function f(a: number, b: number): void;\nf(\n  1,\n  ‸\n);\n",
        )
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
    }

    // --- commas that are NOT this list's separators --------------------------------

    @Test
    fun `a comma inside a nested call does not advance the argument`() {
        val anchor = anchorAt(
            "declare function f(a: number, b: number): void;\n" +
                "declare function g(x: number, y: number): number;\n" +
                "f(g(1, 2), ‸);\n",
        )
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
    }

    @Test
    fun `a comma inside an object literal argument does not advance the argument`() {
        val anchor = anchorAt(
            "declare function f(a: object, b: number): void;\nf({ p: 1, q: 2 }, ‸);\n",
        )
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
    }

    @Test
    fun `a comma inside an arrow parameter list does not advance the argument`() {
        val anchor = anchorAt(
            "declare function f(a: object, b: number): void;\nf((x: number, y: number) => x, ‸);\n",
        )
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
    }

    @Test
    fun `a comma inside a TYPE ARGUMENT of an argument does not advance the argument`() {
        // The one that no bracket-depth scan over `(` `[` `{` could exclude: `<` and
        // `>` are not brackets. It falls out of testing the ARGUMENTS' own spans.
        val anchor = anchorAt(
            "declare function f(a: unknown, b: number): void;\n" +
                "declare const m: Map<string, number>;\n" +
                "f(m as Map<string, number>, ‸);\n",
        )
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
    }

    @Test
    fun `the callee's own type arguments are not counted`() {
        val anchor = anchorAt(
            "declare function f<A, B>(a: number, b: number): void;\nf<string, number>(1, ‸);\n",
        )
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
    }

    // --- which call ----------------------------------------------------------------

    @Test
    fun `a nested call wins over the call containing it`() {
        val text = "declare function f(a: number): void;\n" +
            "declare function g(x: number): number;\n" +
            "f(g(‸));\n"
        val anchor = anchorAt(text)
        assert(anchor != null)
        assert(anchor.activeArgument == 0)
        // The INNER call: its argument list opens after `g(`, i.e. later than `f(`.
        val stripped = text.replace(CARET.toString(), "")
        assert(anchor.argumentListStart == stripped.lastIndexOf("g(") + 2)
    }

    @Test
    fun `a caret past a nested call's closing paren belongs to the OUTER call`() {
        val text = "declare function f(a: number, b: number): void;\n" +
            "declare function g(x: number): number;\n" +
            "f(g(1), ‸);\n"
        val anchor = anchorAt(text)
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
        val stripped = text.replace(CARET.toString(), "")
        assert(anchor.argumentListStart == stripped.lastIndexOf("f(") + 2)
    }

    @Test
    fun `a caret inside an argument's arrow BODY belongs to the enclosing call`() {
        val text = "declare function f(a: () => void, b: number): void;\n" +
            "f(() => { ‸ }, 2);\n"
        val anchor = anchorAt(text)
        assert(anchor != null)
        // Still argument 0 — the braces are inside the first argument, so the comma
        // that follows them is not before the caret.
        assert(anchor.activeArgument == 0)
        val stripped = text.replace(CARET.toString(), "")
        assert(anchor.argumentListStart == stripped.lastIndexOf("f(") + 2)
    }

    @Test
    fun `a call whose callee is itself a call anchors on the OUTER argument list`() {
        val text = "declare function f(): (b: number) => void;\nf()(‸);\n"
        val anchor = anchorAt(text)
        assert(anchor != null)
        assert(anchor.activeArgument == 0)
        val stripped = text.replace(CARET.toString(), "")
        assert(anchor.argumentListStart == stripped.lastIndexOf("f()(") + 4)
    }

    @Test
    fun `a method call through a receiver anchors`() {
        val anchor = anchorAt(
            "declare const o: { m(a: number): void };\no.m(‸);\n",
        )
        assert(anchor != null)
        assert(anchor.call.kind.name == "CallExpression")
        assert(anchor.activeArgument == 0)
    }

    @Test
    fun `a new expression anchors as a NewExpression`() {
        val anchor = anchorAt(
            "class C { constructor(a: number, b: string) {} }\nnew C(1, ‸);\n",
        )
        assert(anchor != null)
        assert(anchor.call.kind.name == "NewExpression")
        assert(anchor.activeArgument == 1)
    }

    // --- what is NOT an argument list ----------------------------------------------

    @Test
    fun `a caret past the closing paren is in no argument list`() {
        assert(anchorAt("declare function f(a: number): void;\nf(1)‸;\n") == null)
    }

    @Test
    fun `a caret on the callee is in no argument list`() {
        assert(anchorAt("declare function f(a: number): void;\nf‸(1);\n") == null)
        assert(anchorAt("declare function f(a: number): void;\n‸f(1);\n") == null)
    }

    @Test
    fun `a caret in a file with no call at all is in no argument list`() {
        assert(anchorAt("const a = 1;\n‸\n") == null)
    }

    @Test
    fun `a tagged template is refused - it has no parenthesized argument list`() {
        assert(anchorAt("declare function tag(s: TemplateStringsArray): string;\ntag`a‸b`;\n") == null)
    }

    @Test
    fun `a new expression written without parentheses is in no argument list`() {
        // `new C` has a null argument list, so there is nothing for a caret to be in;
        // the caret here sits on the statement terminator.
        assert(anchorAt("class C {}\nnew C‸;\n") == null)
    }

    @Test
    fun `a DECORATOR factory is an ordinary call and is not refused`() {
        val anchor = anchorAt(
            "declare function dec(tag: string): (t: unknown) => void;\n@dec(‸)\nclass D {}\n",
        )
        assert(anchor != null)
        assert(anchor.call.kind.name == "CallExpression")
        assert(anchor.activeArgument == 0)
    }

    @Test
    fun `an offset outside the file is in no argument list`() {
        val index = indexOf("declare function f(a: number): void;\nf(1);\n")
        assert(index.signatureAnchorAt(-1) == null)
        assert(index.signatureAnchorAt(10_000) == null)
    }

    // --- INCOMPLETE calls: what the parser actually does ----------------------------

    @Test
    fun `an argument list left open at end of file still anchors`() {
        // The parser builds the CallExpression the moment it sees `(` and then
        // reports the missing `)`, so the node exists — but its own real end is
        // snapped back BELOW the caret, which is why a containment test cannot find
        // it and this anchor is computed from the token stream instead.
        val anchor = anchorAt("declare function f(a: number): void;\nf(‸")
        assert(anchor != null)
        assert(anchor.call.kind.name == "CallExpression")
        assert(anchor.activeArgument == 0)
    }

    @Test
    fun `an argument list left open before a closing brace still anchors`() {
        val anchor = anchorAt(
            "declare function f(a: number, b: number): void;\nfunction m() {\n  f(1, ‸\n}\n",
        )
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
    }

    @Test
    fun `an argument list left open ends at the brace that closes the enclosing block`() {
        val text = "declare function f(a: number): void;\nfunction m() {\n  f(1, ‸\n}\n"
        val anchor = anchorAt(text)
        assert(anchor != null)
        val stripped = text.replace(CARET.toString(), "")
        // The region stops at the `}` rather than running to end of file: an
        // unmatched closer means the enclosing construct is closing.
        assert(anchor.argumentListEnd == stripped.lastIndexOf('}'))
    }

    @Test
    fun `a template substitution inside an argument does not unbalance the region`() {
        // The `}` closing a `${…}` is re-scanned into a template middle or tail by
        // the token index, so it is not a CloseBrace here and cannot end the region.
        val text =
            "declare function f(a: string, b: number): void;\nconst x = 1;\nf(`v\${x}w`, ‸);\n"
        val anchor = anchorAt(text)
        assert(anchor != null)
        assert(anchor.activeArgument == 1)
        val stripped = text.replace(CARET.toString(), "")
        assert(anchor.argumentListEnd == stripped.lastIndexOf(')'))
    }
}
