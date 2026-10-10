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
import com.xemantic.typescript.goport.ir.IrPackage
import com.xemantic.typescript.goport.lower.CallLowering
import com.xemantic.typescript.goport.lower.EscapeAnalysis
import com.xemantic.typescript.goport.lower.EscapeIr
import com.xemantic.typescript.goport.lower.Literals
import com.xemantic.typescript.goport.lower.Program
import com.xemantic.typescript.goport.lower.TypeMapper
import com.xemantic.typescript.goport.naming.Naming
import kotlinx.serialization.json.Json
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

    /** (TSGO.4-a) the language service's packages, counted apart in the census pins (docs/goport-ls.md). */
    private fun isLanguageService(f: File): Boolean =
        listOf("ls/", "lsp/", "format/", "project/", "vfs/wrapvfs/", "jsonrpc/").any { f.relativeTo(gen).path.startsWith(it) }

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
        assert(genFunction("scanner/Scanner.kt", "$scanner.Scanner.scanString").let { (it.contains("indexByteAt(") || it.contains("indexByteIn(")) && !suffixCopy.containsMatchIn(it) })
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
        // The (TSGO.2) compiler test harness (docs/goport-lowering.md § 1c): not on the compiler's path, counted apart.
        val harness = listOf("testrunner/", "testutil/", "execute/incremental/", "tsoptions/tsoptionstest/", "vfs/vfstest/", "vfs/iovfs/", "vfs/internal/")
        fun isHarness(f: File) = harness.any { f.relativeTo(gen).path.startsWith(it) }
        // (TSGO.5) the command line (execute, execute/tsc, vfs/osvfs): counted apart too.
        fun isCli(f: File) = f.relativeTo(gen).path.let { (it.startsWith("execute/") && !it.startsWith("execute/incremental/")) || it.startsWith("vfs/osvfs/") }
        // (TSGO.3-b) the API session (gen/api/*.kt, not api/encoder): counted apart too.
        fun isApiSession(f: File) = f.relativeTo(gen).path.let { it.startsWith("api/") && !it.startsWith("api/encoder/") }
        fun census(dirs: List<String>?, harnessOnly: Boolean = false, apiOnly: Boolean = false, lsOnly: Boolean = false, cliOnly: Boolean = false) = gen.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") && (dirs == null || it.relativeTo(gen).path.substringBefore('/') in dirs) && isHarness(it) == harnessOnly && isApiSession(it) == apiOnly && isLanguageService(it) == lsOnly && isCli(it) == cliOnly }
            .sumOf { f -> suffixCopy.findAll(f.readText()).count() }
        // The (TSGO.1) spike's 14 packages: 48 as first generated; 34 after the window rule, 33 after window parameters (2026-10-07).
        val spike = listOf("api", "ast", "binder", "collections", "core", "debug", "diagnostics", "jsnum", "json", "locale", "parser", "scanner", "stringutil", "tspath")
        assert(census(spike) <= 33)
        // The whole (TSGO.2) closure (checker, compiler, printer, transformers, …): 108 at first generation (2026-10-07).
        assert(census(null) <= 108)
        // The harness slices: 7 at first generation (2026-10-08); 8 once (TSGO.3) reached the emit baselines
        // (`harnessutil` `Repeat`'s option parsing, `tsbaseline` type baselines — none on the compiler's path).
        assert(census(null, harnessOnly = true) <= 8)
        // The API session: 1 at first generation (2026-10-08, `resolveNodeHandle`'s path tail).
        assert(census(null, apiOnly = true) <= 1)
        // (TSGO.4-a) the language service (ls, lsp, format, project/*, vfs/wrapvfs, jsonrpc): 54 at first generation (2026-10-08).
        assert(census(null, lsOnly = true) <= 54)
        // (TSGO.5) the command line: 1 at first generation (2026-10-08, `--help`'s description wrapping).
        assert(census(null, cliOnly = true) <= 1)
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
        val all = gen.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && !isLanguageService(it) }.flatMap { f -> inline.findAll(f.readText()).map { it.groupValues[1] } }.toList()
        // 61 since (TSGO.5) (`execute/tsc.WriteConfigFile`, `tsc --init`).
        assert(all.size in 20..64)
        // (TSGO.4-a) the language service's: 9 at first generation (2026-10-08).
        val ls = gen.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && isLanguageService(it) }.sumOf { f -> inline.findAll(f.readText()).count() }
        assert(ls <= 9)
        // A function that stores, nil-tests or passes on its func parameter stays a normal function.
        // SetParseJSDocForNode STORES its parameter; parseList passes it into a closure.
        assert(!genFunction("ast/Ast.kt", "github.com/microsoft/typescript-go/internal/ast.SetParseJSDocForNode").contains("inline fun"))
        assert(!genFunction("parser/Parser1.kt", "github.com/microsoft/typescript-go/internal/parser.Parser.parseList").contains("inline fun"))
    }

    // ---- the check-path rules of docs/goport-perf.md § 6

    @Test
    fun `a struct copy is elided only where no other reference can reach the object`() {
        val checker = "github.com/microsoft/typescript-go/internal/checker"
        // `return CacheHashKey(b.h.Sum128())`: a conversion of a call's (fresh) result is fresh.
        assert(genFunction("checker/Checker6.kt", "$checker.keyBuilder.hash").contains("return this!!.h.sum128()\n"))
        // `return flowType` in getTypeAtFlowCall: a local defined once from a single value, never captured
        // nor address-taken, is handed out as is.
        val call = genFunction("checker/Flow.kt", "$checker.Checker.getTypeAtFlowCall")
        assert(call.contains("return flowType\n") && !call.contains("return flowType.goCopy()"))
        // getTypeAtFlowNode's `t` calls a pointer-receiver method (`t.IsNil()`): its address is taken, so it
        // is still copied out — and so is every field or element read (`f.sharedFlows[i].flowType`).
        val node = genFunction("checker/Flow.kt", "$checker.Checker.getTypeAtFlowNode")
        assert(node.contains("return t.goCopy()") && node.contains("return this!!.sharedFlows[i].flowType.goCopy()"))
    }

    @Test
    fun `a map range is one table snapshot, not a key list probed again per entry`() {
        // initializeChecker merges every file's locals into the globals: the old form hashed each key twice.
        val init = genFunction("checker/Checker1.kt", "github.com/microsoft/typescript-go/internal/checker.Checker.initializeChecker")
        assert(init.contains(".localsContainerBase.locals.iter()") && init.contains(".next())"))
        val snapshots = gen.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sumOf { f -> Regex("""\.keysSnapshot\(\)""").findAll(f.readText()).count() }
        assert(snapshots == 0)
    }

    @Test
    fun `slot identity and a prefix LastIndex allocate nothing`() {
        // core.Same: `&s1[0] == &s2[0]` compares slots without two GoElemPtr objects.
        assert(genFunction("core/Core.kt", "github.com/microsoft/typescript-go/internal/core.Same").contains("s1.sameSlot(0, s2, 0)"))
        // parseJSDocComment's indent: `strings.LastIndex(p.sourceText[:start], "\n")`, once a prefix copy per JSDoc comment.
        val jsdoc = genFunction("parser/Jsdoc.kt", "github.com/microsoft/typescript-go/internal/parser.Parser.parseJSDocComment")
        assert(jsdoc.contains("lastIndexIn(this!!.sourceText, this!!.sourceText_o, this!!.sourceText_o + goViewBound(this!!.sourceText_n, start), \"\\n\")") && !jsdoc.contains("substring(0, start)"))
    }

    @Test
    fun `a string parameter used only as a view gets a window overload and a sliced argument is not copied`() {
        val parser = "github.com/microsoft/typescript-go/internal/parser"
        // isJSDocLikeText(p.sourceText[start:]) — a suffix copy of the whole source per JSDoc comment.
        val jsdoc = genFunction("parser/Jsdoc.kt", "$parser.Parser.parseJSDocComment")
        // (TSGO.6-g) sourceText is a window field now, so the window is read through its slots.
        assert(jsdoc.contains("isJSDocLikeTextWin(this!!.sourceText, this!!.sourceText_o + goViewBound(this!!.sourceText_n, start), goStrView("))
        assert(!jsdoc.contains("isJSDocLikeText(this!!.sourceText.substring("))
        // The overload reads the window through the view helpers; the copying function stays for other callers.
        val text = File(gen, "parser/Utilities.kt").readText()
        assert(text.contains("fun isJSDocLikeTextWin(text_b0: String, text_o1: Int, text_n2: Int): Boolean {"))
        assert(text.contains("text_n2 >= 4 && goViewByte(text_b0, text_o1, text_n2, 1) == 42"))
        assert(text.contains("fun isJSDocLikeText(text: String): Boolean {"))
    }

    @Test
    fun `a window field narrows without copying - the parser's JSDoc source and the scanner's text`() {
        val parser = "github.com/microsoft/typescript-go/internal/parser"
        val scanner = "github.com/microsoft/typescript-go/internal/scanner"
        // p.sourceText = p.sourceText[:end-2] was a copy of the file prefix per JSDoc comment (docs/goport-perf.md § 8.2).
        val jsdoc = genFunction("parser/Jsdoc.kt", "$parser.Parser.parseJSDocComment")
        assert(!jsdoc.contains("this!!.sourceText.substring("))
        assert(jsdoc.contains("this!!.scanner.setTextWin(this!!.sourceText, this!!.sourceText_o, this!!.sourceText_n)"))
        // The struct carries the slots; a copy and a goSet carry them too.
        val scannerKt = File(gen, "scanner/Scanner.kt").readText()
        assert(scannerKt.contains("@kotlin.jvm.JvmField var text_n: Int = text.length,"))
        assert(scannerKt.contains("Scanner(text = text, text_o = text_o, text_n = text_n,"))
        // SetText's window overload stores the window; reads go through the view (bounds checked against the window).
        assert(genFunction("scanner/Scanner.kt", "$scanner.Scanner.SetText").contains("fun Scanner?.setTextWin(text_1_b0: String, text_1_o1: Int, text_1_n2: Int)"))
        assert(genFunction("scanner/Scanner.kt", "$scanner.Scanner.charAndSize").contains("decodeRuneInStringIn("))
        // A whole read materializes (the base itself when the window covers it).
        assert(genFunction("scanner/Scanner.kt", "$scanner.Scanner.Text").contains("goStrWin(this!!.text, this!!.text_o, this!!.text_n)"))
    }

    @Test
    fun `an immutable struct is its own copy and its pure-receiver local is rebound - FlowType`() {
        val checker = "github.com/microsoft/typescript-go/internal/checker"
        val flow = File(gen, "checker/Flow.kt").readText()
        assert(flow.contains("fun goCopy(): FlowType = this"))
        val node = genFunction("checker/Flow.kt", "$checker.Checker.getTypeAtFlowNode")
        assert(node.contains("t = this.getTypeAtFlowAssignment(f, flow)"))
        assert(!node.contains("t.goSet("))
    }

    @Test
    fun `nodeData and TypeData are abstract classes and every implementer calls their constructor`() {
        val ast = File(gen, "ast/Ast.kt").readText()
        assert(ast.contains("abstract class nodeData {"))
        assert(ast.contains("    abstract fun localsContainerData(): LocalsContainerBase?"))
        assert(!ast.contains("interface nodeData"))
        val types = File(gen, "checker/Types.kt").readText()
        assert(types.contains("abstract class TypeData {"))
        assert(types.contains(") : TypeData() {"))
        // Every implementer's supertype list names them with a constructor call (`nodeData()`), never bare.
        val bare = gen.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sumOf { f ->
            Regex("""\bnodeData[,{ ]|\bTypeData[,{ ]""").findAll(f.readText().lines().filter { it.startsWith(") : ") }.joinToString("\n")).count()
        }
        assert(bare == 0)
    }

    @Test
    fun `every keyBuilder local is pooled - acquired from the thread's stack and released in a finally`() {
        val checker = "github.com/microsoft/typescript-go/internal/checker"
        val c6 = File(gen, "checker/Checker6.kt").readText()
        assert(c6.contains("val POOL: GoLocalPool<keyBuilder> = GoLocalPool({ keyBuilder() }, { it.goReset() })"))
        assert(c6.contains("    fun goReset() {\n        h.goReset()\n    }"))
        val rel = genFunction("checker/Checker6.kt", "$checker.getRelationKey")
        assert(rel.contains("val b: com.xemantic.typescript.tsgo.checker.keyBuilder = ls4.acquire()\n    try {"))
        assert(rel.contains("    } finally {\n        ls4.release()\n    }\n}"))
        // `&b` handed to writeFlowCacheKey (whose parameter is proved non-retaining) does not refuse the local.
        assert(genFunction("checker/Flow.kt", "$checker.Checker.getFlowReferenceKey").contains("ls0.acquire()"))
        // All 13 sites, and no keyBuilder is constructed anywhere but in the pool's zero.
        val all = gen.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.map { it.readText() }.toList()
        assert(all.sumOf { Regex("""keyBuilder\.POOL\.stack\(\)""").findAll(it).count() } == 13)
        assert(all.sumOf { Regex("""= com\.xemantic\.typescript\.tsgo\.checker\.keyBuilder\(\)""").findAll(it).count() } == 0)
    }

    /** A tiny synthetic IR: `type kb struct{ h xxh3.Hasher }` and functions using `var b kb` locals. */
    private fun escapeAnalysisOf(decls: String): EscapeAnalysis {
        val objs = listOf(
            "pkg.kb", "pkg.kb.w", "", "pkg.kb.h", "xxh3.Hasher.Write", "pkg.keep", "", "pkg.sink",
            "pkg.ok", "", "pkg.bad", "", "pkg.bad2", "", "pkg.bad3", "", "", "pkg.g",
        ).mapIndexed { i, k ->
            val t = when (i) { 2, 6 -> 2; 9, 11, 13, 15 -> 1; else -> 0 }
            """{"id": $i, "key": ${if (k.isEmpty()) "null" else "\"$k\""}, "t": $t}"""
        }
        val json = Json.parseToJsonElement("""{"decls": [$decls]}""") as JsonObject
        val objects = objs.map { Json.parseToJsonElement(it) as JsonObject }
        val p = IrPackage("pkg", "pkg", listOf(json), emptyList(), objects, emptyList(), emptyList())
        val ir = object : EscapeIr {
            override fun objKey(p: IrPackage, id: Int): String? = (p.objects[id]["key"] as? JsonPrimitive)?.content?.takeIf { it != "null" }
            override fun named(p: IrPackage, typeId: Int?): String? = if (typeId == 1) "pkg.kb" else null
            override fun pointee(p: IrPackage, typeId: Int?): String? = if (typeId == 2) "pkg.kb" else null
        }
        return EscapeAnalysis(ir, listOf(p), setOf("pkg.kb"), setOf("xxh3.Hasher"))
    }

    private fun id(name: String, obj: Int, def: Boolean = false) = """{"k": "Ident", "name": "$name", "obj": $obj${if (def) ", \"def\": true" else ""}}"""
    private fun varDecl(obj: Int) = """{"k": "DeclStmt", "decl": {"k": "GenDecl", "tok": "var", "specs": [{"k": "ValueSpec", "names": [${id("b", obj, true)}], "type": ${id("kb", 0)}}]}}"""
    private fun callW(x: String) = """{"k": "ExprStmt", "x": {"k": "CallExpr", "call": "method", "fun": {"k": "SelectorExpr", "x": $x, "sel": ${id("w", 1)}, "selk": "method", "autoAddr": true, "callee": true}, "args": []}}"""
    private fun func(obj: Int, body: String, params: String = "[]", recv: String? = null) =
        """{"k": "FuncDecl", "obj": $obj${recv?.let { ", \"recv\": $it" } ?: ""}, "type": {"k": "FuncType", "params": {"k": "FieldList", "list": $params}}, "body": {"k": "BlockStmt", "list": [$body]}}"""

    @Test
    fun `the escape proof pools a local only when no pointer into it can outlive the function`() {
        // func (b *kb) w() { b.h.Write(nil) } — the receiver reaches only a non-retaining shim method.
        val w = func(1, """{"k": "ExprStmt", "x": {"k": "CallExpr", "call": "method", "fun": {"k": "SelectorExpr", "x": {"k": "SelectorExpr", "x": ${id("b", 2)}, "sel": ${id("h", 3)}, "selk": "field"}, "sel": ${id("Write", 4)}, "selk": "method", "autoAddr": true, "callee": true}, "args": []}}""",
            recv = """{"k": "FieldList", "list": [{"k": "Field", "names": [${id("b", 2, true)}], "type": {"k": "StarExpr", "t": 2}}]}""")
        // func keep(b *kb) { sink = b } — RETAINS its parameter.
        val keep = func(5, """{"k": "AssignStmt", "tok": "=", "lhs": [${id("sink", 7)}], "rhs": [${id("b", 6)}]}""",
            params = """[{"k": "Field", "names": [${id("b", 6, true)}], "type": {"k": "StarExpr", "t": 2}}]""")
        // func ok() { var b kb; b.w() }
        val ok = func(8, "${varDecl(9)}, ${callW(id("b", 9))}")
        // func bad() { var b kb; keep(&b) }
        val bad = func(10, """${varDecl(11)}, {"k": "ExprStmt", "x": {"k": "CallExpr", "call": "func", "fun": ${id("keep", 5)}, "args": [{"k": "UnaryExpr", "op": "&", "x": ${id("b", 11)}}]}}""")
        // func bad2() { var b kb; go func() { b.w() }() }
        val bad2 = func(12, """${varDecl(13)}, {"k": "GoStmt", "call": {"k": "CallExpr", "call": "dynamic", "fun": {"k": "FuncLit", "body": {"k": "BlockStmt", "list": [${callW(id("b", 13))}]}}, "args": []}}""")
        // func bad3() { var b kb; f := func() { b.w() }; g(f) } — the closure escapes into g.
        val bad3 = func(14, """${varDecl(15)}, {"k": "AssignStmt", "tok": ":=", "lhs": [${id("f", 16, true)}], "rhs": [{"k": "FuncLit", "body": {"k": "BlockStmt", "list": [${callW(id("b", 15))}]}}]}, {"k": "ExprStmt", "x": {"k": "CallExpr", "call": "func", "fun": ${id("g", 17)}, "args": [${id("f", 16)}]}}""")
        val a = escapeAnalysisOf(listOf(w, keep, ok, bad, bad2, bad3).joinToString(", "))
        assert("pkg.kb.w#-1" in a.nonRetaining)
        assert("pkg.keep#0" !in a.nonRetaining)
        assert(a.pooled["pkg"] == setOf(9))
        assert(a.refusals.size == 3)
        assert(a.refusals.any { it.startsWith("pkg.bad b:") && "escapes" in it })
        assert(a.refusals.any { it.startsWith("pkg.bad2 b:") && "GoStmt" in it })
        assert(a.refusals.any { it.startsWith("pkg.bad3 b:") && "function literal" in it })
    }

    @Test
    fun `compareSymbols is a primitive fun interface - SAM-converted where assigned, called unboxed, adapted where handed on`() {
        val checker = "github.com/microsoft/typescript-go/internal/checker"
        val c1 = File(gen, "checker/Checker1.kt").readText()
        assert(c1.contains("@kotlin.jvm.JvmField var compareSymbols: Checker_compareSymbols_Fn? = null"))
        assert(c1.contains("fun interface Checker_compareSymbols_Fn {\n    operator fun invoke(p0: Symbol?, p1: Symbol?): Int\n}"))
        // The producer: the method value is SAM-converted right at the assignment (its lambda returns int).
        assert(genFunction("checker/Checker1.kt", "$checker.NewChecker").contains(
            "c!!.compareSymbols = run { val r1 = c; Checker_compareSymbols_Fn(fun(p0: Symbol?, p1: Symbol?): Int = r1.compareSymbolsWorker(p0, p1)) }"))
        // A call stays a call (`invoke` is an operator); a pass to a function is adapted back, nil kept.
        assert(genFunction("checker/Utilities.kt", "$checker.CompareTypes").contains("t1!!.checker!!.compareSymbols!!(t1!!.symbol, t2!!.symbol)"))
        assert(genFunction("checker/Utilities.kt", "$checker.Checker.sortSymbols").contains(
            "this!!.compareSymbols?.let { g0 -> fun(p0: Symbol?, p1: Symbol?): Int = g0(p0, p1) }"))
        // compareSymbolChains is not a candidate: still a Kotlin function type.
        assert(c1.contains("var compareSymbolChains: ((GoSlice<Symbol?>, GoSlice<Symbol?>) -> Int)? = null"))
    }

    @Test
    fun `a primitive func field read anywhere the porter does not lower itself refuses the field`() {
        fun n(json: String) = Json.parseToJsonElement(json) as JsonObject
        val field = """{"k": "SelectorExpr", "selk": "field"}"""
        // Allowed: a call, an argument, a nil comparison, an assignment of a method value / literal / nil.
        assert(Program.primFuncUseAllowed(n("""{"k": "CallExpr", "call": "dynamic"}"""), "fun"))
        assert(Program.primFuncUseAllowed(n("""{"k": "CallExpr", "call": "func"}"""), "args"))
        assert(Program.primFuncUseAllowed(n("""{"k": "BinaryExpr", "op": "!=", "x": $field, "y": {"k": "Ident", "m": "nil"}}"""), "x"))
        assert(Program.primFuncUseAllowed(n("""{"k": "AssignStmt", "tok": "=", "lhs": [$field], "rhs": [{"k": "SelectorExpr", "selk": "method"}]}"""), "lhs"))
        // Refused: a store of the field's value, a return, a local, a variadic argument, a composite literal, any other value assigned.
        assert(!Program.primFuncUseAllowed(n("""{"k": "AssignStmt", "tok": "=", "lhs": [{"k": "Ident"}], "rhs": [$field]}"""), "rhs"))
        assert(!Program.primFuncUseAllowed(n("""{"k": "ReturnStmt", "results": [$field]}"""), "results"))
        assert(!Program.primFuncUseAllowed(n("""{"k": "AssignStmt", "tok": ":=", "lhs": [$field], "rhs": [{"k": "FuncLit"}]}"""), "lhs"))
        assert(!Program.primFuncUseAllowed(n("""{"k": "CallExpr", "call": "func", "variadicFrom": 0}"""), "args"))
        assert(!Program.primFuncUseAllowed(n("""{"k": "KeyValueExpr"}"""), "value"))
        assert(!Program.primFuncUseAllowed(n("""{"k": "AssignStmt", "tok": "=", "lhs": [$field], "rhs": [{"k": "Ident", "m": "variable"}]}"""), "lhs"))
    }
}
