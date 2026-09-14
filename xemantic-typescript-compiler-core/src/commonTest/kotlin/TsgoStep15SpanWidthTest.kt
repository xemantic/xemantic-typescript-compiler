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
 * (LEGACY.0b) step 15, round (P18.100): the F8 span/width family — seven mechanisms where the
 * diagnostic's CODE and TEXT already matched TypeScript 7 (tsgo 7.0.2, the ONLY compatibility
 * target) and only an anchor, a width, an order or a rendering differed. Every expectation was
 * read off `tools/tsgo-7.0.2/lib/tsc -p .` over the same fixture text or off tsgo's harness
 * baselines (`typescript-go-repo/testdata/baselines/reference/submodule/…`), never hand-derived.
 *
 *  * **M1** — two FILE-LESS rows are ordered by tsgo's `Loc.Pos()` before their code: an
 *    options diagnostic (`UndefinedTextRange`, pos -1) precedes a checker global (the zero
 *    range), so `error TS5053` precedes every `error TS2318`. (A `tsconfig.json` row keeps its
 *    precedence over a source file's: pure path order — tsgo's — moves seven green baselines
 *    tsgo 7 never runs, so `pathsValidation5` stays pending.)
 *  * **M2** — the TS1356 *Did you mean to mark this function as 'async'?* hint on an ANONYMOUS
 *    function expression anchors on its ASSIGNED name (`ast.GetAssignedName`: the variable, the
 *    property, the assignment target), and on the `function` keyword only where there is none.
 *  * **M3** — TS2447 *The '^=' operator is not allowed for boolean types* anchors on the
 *    OPERATOR token, width = the operator's text (`c.error(operatorToken, …)`).
 *  * **M4** — TS4032 on an expando assignment spans the whole `q.val = f()` expression (the
 *    declaration-emit `errorNode` is the BinaryExpression), not the target and not the `;`.
 *  * **M5** — TS1092 for a JSDoc `@template` on a constructor spans the reparsed
 *    TypeParameters LIST: from the first `@template`'s `@` to the last `@template` tag's end,
 *    and a JSDoc tag ends at the next tag's `@` or at the comment's close.
 *  * **M6** — the `--pretty` code frame prints ONE `~` for a zero-width span
 *    (`writeCodeSnippet`: `if length == 0 { lastLineChar++ }`).
 *  * **M7** — the `.errors.txt` squiggle line is built from the source line's RUNES (padding
 *    and tildes per code point, `error_baseline.go`) while the header stays UTF-16; and in a
 *    NON-unicode regex a character-class range over an astral character starts at the rune
 *    and ends before the maximum's rune (tsgo's `pendingLowSurrogate` split).
 */
class TsgoStep15SpanWidthTest {

    private fun baseline(text: String, fileName: String = "t.ts"): String =
        TypeScriptCompiler().compile(text, fileName).toErrorBaseline() ?: ""

    /** `(line, column, length)` — line numbers are of the compiled file, i.e. AFTER the harness
     *  strips the `// @directive` header lines. */
    private fun at(d: Diagnostic): Triple<Int?, Int?, Int?> = Triple(d.line, d.character, d.length)

    // ------------------------------------------------------------------ M1

    @Test
    fun `M1 an options row precedes every checker global in the summary and in the marker block`() {
        val lines = baseline("// @noLib: true\n// @lib: es5\ndeclare const x: number;\n").split("\r\n")
        val first = lines[0]
        val second = lines[1]
        assert(first == "error TS5053: Option 'lib' cannot be specified with option 'noLib'.")
        assert(second == "error TS2318: Cannot find global type 'Array'.")
        val markers = lines.filter { it.startsWith("!!! error TS") }
        val firstMarker = markers.first()
        val globalMarkers = markers.count { it.startsWith("!!! error TS2318") }
        val secondMarker = markers[1]
        assert(firstMarker == "!!! error TS5053: Option 'lib' cannot be specified with option 'noLib'.")
        assert(globalMarkers == 10)
        assert(secondMarker == "!!! error TS2318: Cannot find global type 'Array'.")
    }

    @Test
    fun `M1 negative control - a file-anchored row keeps its place after every file-less row`() {
        val lines = baseline("// @noLib: true\n// @lib: es5\nzzzUndeclared;\n").split("\r\n")
        val lastFileLess = lines.indexOfLast { it.startsWith("error TS") }
        val fileRow = lines.indexOfFirst { it.startsWith("t.ts(") }
        assert(fileRow > lastFileLess)
        assert(lines[fileRow] == "t.ts(1,1): error TS2304: Cannot find name 'zzzUndeclared'.")
    }

    // ------------------------------------------------------------------ M2

    private val awaitShapes = """
        export {};
        const fe = function(p: Promise<number>) {
          await p;
        }
        const named = function nm(p: Promise<number>) {
          await p;
        }
        const o = { prop: function(p: Promise<number>) {
          await p;
        } };
        let holder: any;
        holder.member = function(p: Promise<number>) {
          await p;
        }
        holder = function(p: Promise<number>) {
          await p;
        }
        const paren = (function(p: Promise<number>) {
          await p;
        });
        holder["lit"] = function(p: Promise<number>) {
          await p;
        }
        const [de] = [function(p: Promise<number>) {
          await p;
        }];
    """

    /** The TS1356 hint attached to the TS1308 at file line [awaitLine]. */
    private fun asyncHint(awaitLine: Int): Triple<Int?, Int?, Int?> {
        val diags = diagnose(awaitShapes, "// @target: esnext")
        val d = diags.first { it.code == 1308 && it.line == awaitLine }
        return at(d.relatedInformation.first { it.code == 1356 })
    }

    @Test
    fun `M2 the async hint anchors on the variable an anonymous function expression is assigned to`() {
        val hint = asyncHint(3)
        assert(hint == Triple(2, 7, 2))
    }

    @Test
    fun `M2 negative control - a named function expression keeps its own name`() {
        val hint = asyncHint(6)
        assert(hint == Triple(5, 24, 2))
    }

    @Test
    fun `M2 the async hint anchors on a property assignment's name`() {
        val hint = asyncHint(9)
        assert(hint == Triple(8, 13, 4))
    }

    @Test
    fun `M2 the async hint anchors on the property name of an assignment target`() {
        val hint = asyncHint(13)
        assert(hint == Triple(12, 8, 6))
    }

    @Test
    fun `M2 the async hint anchors on the identifier of an assignment target`() {
        val hint = asyncHint(16)
        assert(hint == Triple(15, 1, 6))
    }

    @Test
    fun `M2 negative control - a parenthesized function expression keeps the function keyword`() {
        val hint = asyncHint(19)
        assert(hint == Triple(18, 16, 8))
    }

    @Test
    fun `M2 the async hint anchors on the string key of an element-access assignment target`() {
        val hint = asyncHint(22)
        assert(hint == Triple(21, 8, 5))
    }

    @Test
    fun `M2 negative control - an array-literal element keeps the function keyword`() {
        val hint = asyncHint(25)
        assert(hint == Triple(24, 15, 8))
    }

    // ------------------------------------------------------------------ M3

    private val bitwise = """
        var a = true;
        var b = 1;
        a ^= a;
        b ^= b;
        a ^= b;
        a & a;
        a /* c */ |= a;
        var s = "x";
        s ^= s;
    """

    @Test
    fun `M3 TS2447 anchors on the compound operator token with the operator's width`() {
        val d = diagnose(bitwise, "// @target: es2015").filter { it.code == 2447 }
        val first = at(d.first { it.line == 3 })
        val msg = d.first { it.line == 3 }.message
        assert(first == Triple(3, 3, 2))
        assert(msg == "The '^=' operator is not allowed for boolean types. Consider using '!==' instead.")
    }

    @Test
    fun `M3 a non-compound bitwise operator is one character wide`() {
        val d = at(diagnose(bitwise, "// @target: es2015").first { it.code == 2447 && it.line == 6 })
        assert(d == Triple(6, 3, 1))
    }

    @Test
    fun `M3 the anchor skips trivia between the operand and the operator`() {
        val d = at(diagnose(bitwise, "// @target: es2015").first { it.code == 2447 && it.line == 7 })
        assert(d == Triple(7, 11, 2))
    }

    @Test
    fun `M3 negative control - a number operand keeps TS2362 on the operand`() {
        val diags = diagnose(bitwise, "// @target: es2015")
        val mixed = diags.none { it.code == 2447 && it.line == 5 }
        val left = at(diags.first { it.code == 2362 && it.line == 5 })
        val str = at(diags.first { it.code == 2362 && it.line == 9 })
        val strRight = at(diags.first { it.code == 2363 && it.line == 9 })
        assert(mixed)
        assert(left == Triple(5, 1, 1))
        assert(str == Triple(9, 1, 1))
        assert(strRight == Triple(9, 6, 1))
    }

    // ------------------------------------------------------------------ M4

    private val expando = """
        // @module: commonjs
        // @target: es2015
        // @declaration: true
        // @filename: a.ts
        interface I {}
        export function f(): I { return null as I; }
        // @filename: b.ts
        import {f} from "./a";

        export function q() {}
        q.val = f() ;
    """

    @Test
    fun `M4 TS4032 on an expando assignment spans the whole assignment expression`() {
        val d = diagnose(expando, "").first { it.code == 4032 }
        val where = at(d)
        val file = d.fileName
        assert(where == Triple(4, 1, 11))
        assert(file == "b.ts")
        assert(d.message == "Property 'val' of exported interface has or is using name 'I' from private module '\"a\"'.")
    }

    // ------------------------------------------------------------------ M5

    private val jsdocTemplates = """
        class C1 {
            /** @template T */
            constructor() { }
        }
        class C2 {
            /** @template T   */
            constructor() { }
        }
        class C3 {
            /** @template T @template U */
            constructor() { }
        }
        class C4 {
            /** @template T
             * @param {number} x */
            constructor(x) { }
        }
        class C5 {
            /** @template {string} T */
            constructor() { }
        }
        class D {
            /** @return {number} */
            constructor() {}
        }
    """
    private val jsDirectives = "// @allowJs: true\n// @checkJs: true\n// @noEmit: true\n// @target: es2015"

    private fun ts1092(line: Int): Triple<Int?, Int?, Int?> =
        at(diagnose(jsdocTemplates, jsDirectives, "a.js").first { it.code == 1092 && it.line == line })

    @Test
    fun `M5 TS1092 spans the template tag from its at-sign to the comment close`() {
        val d = ts1092(2)
        assert(d == Triple(2, 9, 12))
    }

    @Test
    fun `M5 the tag range keeps same-line trailing whitespace before the close`() {
        val d = ts1092(6)
        assert(d == Triple(6, 9, 14))
    }

    @Test
    fun `M5 the range runs from the first template tag to the last one's end`() {
        val d = ts1092(10)
        assert(d == Triple(10, 9, 24))
    }

    @Test
    fun `M5 a tag ends where the next tag's at-sign begins`() {
        val d = ts1092(14)
        assert(d == Triple(14, 9, 19))
    }

    @Test
    fun `M5 a constrained template tag is spanned from its at-sign`() {
        val d = ts1092(19)
        assert(d == Triple(19, 9, 21))
    }

    @Test
    fun `M5 negative control - TS1093 keeps its anchor on the return type text`() {
        val d = at(diagnose(jsdocTemplates, jsDirectives, "a.js").first { it.code == 1093 })
        assert(d == Triple(23, 18, 6))
    }

    @Test
    fun `M5 negative control - a TypeScript file reports no JSDoc constructor tag`() {
        val none = diagnose(jsdocTemplates, "// @target: es2015").none { it.code == 1092 || it.code == 1093 }
        assert(none)
    }

    // ------------------------------------------------------------------ M6

    @Test
    fun `M6 the pretty code frame prints one tilde for a zero-width span`() {
        val b = baseline("// @target: es2015\n// @pretty: true\n// @filename: index.ts\nif (true) {\n")
        val frame = "[7m2[0m \r\n[7m [0m [91m~[0m\r\n"
        assert(frame in b)
    }

    @Test
    fun `M6 negative control - a multi-character span keeps its width in the pretty frame`() {
        val b = baseline("// @target: es2015\n// @pretty: true\n// @filename: index.ts\nlet abc: string = 1;\n")
        val frame = "[7m [0m [91m    ~~~[0m\r\n"
        assert(frame in b)
    }

    // ------------------------------------------------------------------ M7

    private fun squiggle(indent: Int, tildes: Int): String = "\r\n    " + " ".repeat(indent) + "~".repeat(tildes) + "\r\n"

    @Test
    fun `M7 a non-unicode class range over an astral character starts at the rune and ends before the maximum's rune`() {
        val src = "const r = /[𝘈-𝘡][𝘡-𝘈]/;"
        val d = diagnose(src, "// @target: esnext").filter { it.code == 1517 }.map(::at)
        assert(d == listOf(Triple(1, 13, 3), Triple(1, 20, 3)))
        val b = baseline("// @target: esnext\n$src\n")
        val firstSquiggle = squiggle(12, 2) in b
        val secondSquiggle = squiggle(17, 2) in b
        assert(firstSquiggle)
        assert(secondSquiggle)
    }

    @Test
    fun `M7 a unicode-mode class range keeps its code-point span and squiggles per code point`() {
        val src = "const u = /[𝘈-𝘡][𝘡-𝘈]/u;"
        val d = diagnose(src, "// @target: esnext").filter { it.code == 1517 }.map(::at)
        assert(d == listOf(Triple(1, 20, 5)))
        val b = baseline("// @target: esnext\n$src\n")
        val sq = squiggle(17, 3) in b
        assert(sq)
    }

    @Test
    fun `M7 an unknown astral regex flag is one tilde wide under a code-point column`() {
        val src = "const g = /a/𝘨;"
        val d = diagnose(src, "// @target: esnext").filter { it.code == 1499 }.map(::at)
        assert(d == listOf(Triple(1, 14, 2)))
        val b = baseline("// @target: esnext\n$src\n")
        val sq = squiggle(13, 1) in b
        assert(sq)
    }

    @Test
    fun `M7 a squiggle after an astral character on the same line sits one column left per pair`() {
        val src = "const 𐊧 = 1; const s: string = 𐊧;"
        val d = diagnose(src, "// @target: esnext").filter { it.code == 2322 }.map(::at)
        assert(d == listOf(Triple(1, 21, 1)))
        val b = baseline("// @target: esnext\n$src\n")
        val sq = squiggle(19, 1) in b
        assert(sq)
    }

    @Test
    fun `M7 negative control - a BMP-only line keeps its columns and widths`() {
        val src = "const z = /[z-a]/;"
        val d = diagnose(src, "// @target: esnext").filter { it.code == 1517 }.map(::at)
        assert(d == listOf(Triple(1, 13, 3)))
        val b = baseline("// @target: esnext\n$src\n")
        val sq = squiggle(12, 3) in b
        assert(sq)
    }
}
