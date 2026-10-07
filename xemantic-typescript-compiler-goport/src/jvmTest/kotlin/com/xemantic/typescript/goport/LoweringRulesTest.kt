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

package com.xemantic.typescript.goport

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.goport.lower.CallLowering
import com.xemantic.typescript.goport.lower.Literals
import com.xemantic.typescript.goport.lower.TypeMapper
import com.xemantic.typescript.goport.naming.Naming
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test

/**
 * Pins for lowering rules whose failure is SILENT (a wrong value that still compiles). The
 * end-to-end gate is `-tsgo`'s OracleParityTest (encoded-AST byte equality against tsgo).
 */
class LoweringRulesTest {

    private fun c(kind: String, v: String) = JsonObject(mapOf("kind" to JsonPrimitive(kind), "v" to JsonPrimitive(v)))

    @Test
    fun `Go names map to Kotlin lowerCamelCase as the shims spell them`() {
        assert(Naming.lowerCamel("GetTypeOfSymbol") == "getTypeOfSymbol")
        assert(Naming.lowerCamel("ID") == "id")
        assert(Naming.lowerCamel("URLPath") == "urlPath")
        assert(Naming.lowerCamel("ToValidUTF8") == "toValidUTF8")
        assert(Naming.lowerCamel("NaN") == "naN")
        assert(Naming.lowerCamel("x") == "x")
        assert(Naming.escape("is") == "`is`")
    }

    @Test
    fun `Go packages map to Kotlin packages`() {
        assert(Naming.kotlinPackage("github.com/microsoft/typescript-go/internal/api/encoder") == "com.xemantic.typescript.tsgo.api.encoder")
        assert(Naming.kotlinPackage("unicode/utf8") == "com.xemantic.typescript.tsgo.go.unicode.utf8")
        assert(Naming.kotlinPackage("golang.org/x/text/language") == "com.xemantic.typescript.tsgo.go.golang_org.x.text.language")
    }

    @Test
    fun `a Go string literal is emitted as its bytes, escaped for a Kotlin literal`() {
        // "é$\n" in UTF-8: C3 A9 24 0A — one Kotlin char per byte, `$` escaped (no template).
        val lit = Literals.byteString(byteArrayOf(0xC3.toByte(), 0xA9.toByte(), '$'.code.toByte(), '\n'.code.toByte(), '"'.code.toByte()))
        assert(lit == "\"\\u00C3\\u00A9\\$\\n\\\"\"")
    }

    @Test
    fun `integer constants keep their exact value at the edges of each representation`() {
        assert(Literals.raw(c("int", "-2147483648"), TypeMapper.Rep.INT).code == "Int.MIN_VALUE")
        assert(Literals.raw(c("int", "-9223372036854775808"), TypeMapper.Rep.LONG).code == "Long.MIN_VALUE")
        assert(Literals.raw(c("int", "4294967295"), TypeMapper.Rep.UINT).code == "4294967295u")
        assert(Literals.raw(c("int", "18446744073709551615"), TypeMapper.Rep.ULONG).code == "18446744073709551615uL")
        // A negative constant in an unsigned slot is its two's complement (Go `uint32(^uint32(0))` folds there).
        assert(Literals.raw(c("int", "-1"), TypeMapper.Rep.UINT).code == "4294967295u")
        // A negative literal is a prefix expression: it gets parentheses as an operand.
        assert(Literals.raw(c("int", "-5"), TypeMapper.Rep.INT).at(100) == "(-5)")
    }

    @Test
    fun `a constant out of the 32-bit int range is refused, not wrapped`() {
        val refused = try {
            Literals.raw(c("int", "9223372036854775806"), TypeMapper.Rep.INT)
            false
        } catch (_: com.xemantic.typescript.goport.lower.Refusal) {
            true
        }
        assert(refused)
    }

    @Test
    fun `math MaxInt and MinInt as an int sentinel become the 32-bit bounds`() {
        assert(Literals.raw(c("int", "9223372036854775807"), TypeMapper.Rep.INT).code == "Int.MAX_VALUE")
        assert(Literals.raw(c("int", "-9223372036854775808"), TypeMapper.Rep.INT).code == "Int.MIN_VALUE")
    }

    // ---- substring elimination (docs/goport-lowering.md § 3) — a census over the checked-in gen/

    /** A copying single-bound slice `s.substring(i)`. */
    private val suffixCopy = Regex("""\.substring\([^,()]*\)""")

    private val gen = File("../xemantic-typescript-compiler-tsgo/src/commonMain/kotlin/gen")

    /** The text of the generated function whose `// go:` trace line names [goQname] (to the next trace line). */
    private fun genFunction(file: String, goQname: String): String {
        val text = File(gen, file).readText()
        val start = text.indexOf("// go: $goQname ")
        assert(start >= 0)
        val end = text.indexOf("// go: ", start + 1).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    @Test
    fun `the parse-path string slices that only feed an index or a prefix test are never copied`() {
        val scanner = "github.com/microsoft/typescript-go/internal/scanner"
        val parser = "github.com/microsoft/typescript-go/internal/parser"
        val ast = "github.com/microsoft/typescript-go/internal/ast"
        // A view local: `text := s.text[s.pos:s.end]` read only through len/index (the 134x of docs/goport-perf.md § 2).
        val ascii = genFunction("scanner/Scanner.kt", "$scanner.Scanner.scanASCIIWhile")
        assert(!ascii.contains(".substring("))
        assert(ascii.contains("goStrView(") && ascii.contains("goViewByte("))
        // Fused calls: HasPrefix / IndexByte / Index over a suffix of the source.
        // (scanString keeps ONE bounded `substring`: the literal's value genuinely escapes as a string.)
        assert(genFunction("scanner/Scanner.kt", "$scanner.Scanner.scanString").let { it.contains("indexByteAt(") && !suffixCopy.containsMatchIn(it) })
        assert(!genFunction("scanner/Scanner.kt", "$scanner.Scanner.processCommentDirective").contains(".substring("))
        assert(!genFunction("parser/Parser3.kt", "$parser.match").contains(".substring("))
        assert(!genFunction("parser/Parser3.kt", "$parser.skipTo").contains(".substring("))
        // IndexAny(text[index:], "ir") and text[index:index+size] == expected.
        val find = genFunction("ast/Utilities1.kt", "$ast.findImportOrRequire")
        assert(!find.contains(".substring("))
        assert(find.contains("indexAnyAt(") && find.contains("goStrEqIn("))
    }

    @Test
    fun `every fused strings call has its window variants in the shim`() {
        val shim = File("../xemantic-typescript-compiler-tsgo/src/commonMain/kotlin/go/strings/Strings.kt").readText()
        for ((key, f) in CallLowering.FUSIONS) {
            assert(key.startsWith("strings.") && f.pkg.endsWith(".go.strings"))
            assert(shim.contains("fun ${f.name}At(s: String, from: Int,"))
            assert(!f.inOk || shim.contains("fun ${f.name}In(s: String, from: Int, to: Int,"))
        }
    }

    @Test
    fun `the census of copying single-bound string slices in gen does not grow`() {
        fun census(dirs: List<String>?) = gen.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") && (dirs == null || it.relativeTo(gen).path.substringBefore('/') in dirs) }
            .sumOf { f -> suffixCopy.findAll(f.readText()).count() }
        // The (TSGO.1) spike's 14 packages: 48 as first generated; 34 after the window rule, 33 after window parameters (2026-10-07).
        val spike = listOf("api", "ast", "binder", "collections", "core", "debug", "diagnostics", "jsnum", "json", "locale", "parser", "scanner", "stringutil", "tspath")
        assert(census(spike) <= 33)
        // The whole (TSGO.2) closure (checker, compiler, printer, transformers, …): 108 at first generation (2026-10-07).
        assert(census(null) <= 108)
    }

    @Test
    fun `a comma-ok map read is one probe and no tuple`() {
        // `identifier, ok := p.identifiers[text]` in internIdentifier, the hottest map read of a parse.
        val intern = genFunction("parser/Parser2.kt", "github.com/microsoft/typescript-go/internal/parser.Parser.internIdentifier")
        assert(intern.contains(".probe(text)") && intern.contains("!== GoMapAbsent"))
        // No generated code calls the two-probe, tuple-allocating GoMap.lookup any more.
        val calls = Regex("""\b[mte]\d*\.lookup\(|\.(identifiers|args)\.lookup\(""")
        val n = gen.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sumOf { f -> calls.findAll(f.readText()).count() }
        assert(n == 0)
    }

    @Test
    fun `a function whose func-typed parameters are only called is inline and takes them non-null`() {
        val scanner = "github.com/microsoft/typescript-go/internal/scanner"
        // scanASCIIWhile: one call site, six predicates — a megamorphic boxed Function1 unless inlined.
        val ascii = genFunction("scanner/Scanner.kt", "$scanner.Scanner.scanASCIIWhile")
        assert(ascii.contains("inline fun Scanner?.scanASCIIWhile(pred: ((Int) -> Boolean))"))
        assert(ascii.contains("!pred(b)") && !ascii.contains("pred!!"))
        // A named func type is a NULLABLE typealias (`Visitor`); the inline parameter takes its signature.
        val visit = genFunction("ast/Ast.kt", "github.com/microsoft/typescript-go/internal/ast.visit")
        assert(visit.contains("inline fun visit(v: ((Node?) -> Boolean), node: Node?)"))
        // The callers pass function literals as is (Kotlin inlines them); no `!!` on a literal.
        val ident = genFunction("scanner/Scanner.kt", "$scanner.Scanner.scanIdentifier")
        assert(ident.contains("this.scanASCIIWhile(fun(b: Int): Boolean {"))
        // Every inline function is small: the body is copied into each caller (JIT.1 counts it).
        val inline = Regex("""// go: (\S+) [0-9a-f]+\n(?:@[^\n]*\n)?inline fun """)
        val all = gen.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.flatMap { f -> inline.findAll(f.readText()).map { it.groupValues[1] } }.toList()
        assert(all.size in 20..60)
        // A function that stores, nil-tests or passes on its func parameter stays a normal function.
        // SetParseJSDocForNode STORES its parameter; parseList passes it into a closure.
        assert(!genFunction("ast/Ast.kt", "github.com/microsoft/typescript-go/internal/ast.SetParseJSDocForNode").contains("inline fun"))
        assert(!genFunction("parser/Parser1.kt", "github.com/microsoft/typescript-go/internal/parser.Parser.parseList").contains("inline fun"))
    }

    @Test
    fun `a string parameter used only as a view gets a window overload and a sliced argument is not copied`() {
        val parser = "github.com/microsoft/typescript-go/internal/parser"
        // isJSDocLikeText(p.sourceText[start:]) — a suffix copy of the whole source per JSDoc comment.
        val jsdoc = genFunction("parser/Jsdoc.kt", "$parser.Parser.parseJSDocComment")
        assert(jsdoc.contains("isJSDocLikeTextWin(this!!.sourceText, start, goStrView(this!!.sourceText, start, this!!.sourceText.length))"))
        assert(!jsdoc.contains("isJSDocLikeText(this!!.sourceText.substring("))
        // The overload reads the window through the view helpers; the copying function stays for other callers.
        val text = File(gen, "parser/Utilities.kt").readText()
        assert(text.contains("fun isJSDocLikeTextWin(text_b0: String, text_o1: Int, text_n2: Int): Boolean {"))
        assert(text.contains("text_n2 >= 4 && goViewByte(text_b0, text_o1, text_n2, 1) == 42"))
        assert(text.contains("fun isJSDocLikeText(text: String): Boolean {"))
    }
}
