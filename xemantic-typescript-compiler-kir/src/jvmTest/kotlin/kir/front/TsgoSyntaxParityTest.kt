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

package com.xemantic.typescript.compiler.kir.front

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.tsgo.runtime.GoString
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.test.Test

/**
 * (TSGO.7-e) [TsgoSyntax] — tsgo's tree as the lowering's syntax — against `-core`'s parser, which built that
 * syntax before: every KIR corpus program and project file converts to the same tree, field for field.
 *
 * Compared: every constructor field the lowering could read, `pos` included (a refusal reports the line and
 * column of `Node.pos`). Not compared: comments, layout-only fields no lowering reads (`multiLine`, `rawText`,
 * …), parser bookkeeping, and `end` — no lowering reads it, and `-core`'s overshoots: its parser records the
 * end of the token AFTER the node (`getEnd()` after `nextToken()`), where tsgo's is the node's own ([SyntaxDiff.ignored]).
 */
class TsgoSyntaxParityTest {

    @Test
    fun `every KIR corpus and project file converts to -core's tree`() {
        val root = File(checkNotNull(javaClass.classLoader.getResource("corpus")).toURI()).parentFile
        val files = root.walk().filter { it.isFile && it.extension == "ts" }.sortedBy { it.path }.toList()
        val diffs = files.flatMap { SyntaxDiff.of(it.relativeTo(root).path, it.readText()).take(3) }
        assert(files.size == 44)
        // The one difference, and the converter is the right side of it: `-core` hands a template's middle and
        // tail over RAW, so the lowering compiled `\n` in a template to a backslash and an `n`; tsgo's text is
        // the cooked string (`KirTemplateEscapeTest`).
        assert(
            diffs == listOf(
                "projects/toml/src/error.ts.statements[3].members[3].body.statements[2].expression.arguments[0]" +
                    ".templateSpans[0].literal.text: \"\\n\\n\" != \"\n\n\"",
            )
        )
    }
}

/** Field-by-field comparison of two `-core` syntax trees. */
internal object SyntaxDiff {

    /** Fields no lowering reads: comments, layout, and parser bookkeeping. */
    val ignored = setOf(
        "end", "kind", "kindId", "internSalt", "nodeId", "parent", "nodeCount", "typeAliasesWithTpDefaults", "nestedScopeDecls",
        "moduleSpecifiers", "referencedPaths", "referencedTypes", "multiLine", "closeBracePos", "closeBracketPos",
        "hasTrailingComma", "singleQuote", "rawText", "isUnterminated", "newLineBefore", "newLineAfterDot", "headerEnd",
        "tightEnd", "instantiationEnd", "instantiationTerminatesChain", "closeParenMissing", "operatorHasPrecedingLineBreak",
        "multiLineComma", "syntheticSemicolons", "signatureAborted", "commaRecovered", "jsDocTagPos", "jsDocTagEnd",
        "bodylessEnd", "assertClausePos", "singleLine", "hasParenthesizedParameters", "inAsyncContext",
        "typeFromJSDoc", "fromJSDoc", "jsdocCastType",
    )

    /** The differences between `-core`'s parse of [text] and tsgo's tree converted, as `path: core != tsgo`. */
    fun of(fileName: String, text: String): List<String> {
        val core = parseForLowering(fileName, text)
        val converted = TsgoPrograms.onDeepStack {
            val absolute = "/xtsc-kir-syntax/$fileName"
            val config = "/xtsc-kir-syntax/tsconfig.json"
            val fs = InMemoryFS(mapOf(absolute to text, config to "{ \"files\": [\"$absolute\"] }"))
            val opened = TsgoPrograms.open(config, fs)
            val file = opened.program.getSourceFile(GoString.fromUtf16(absolute))!!
            TsgoSyntax(file, fileName).sourceFile
        }
        val out = mutableListOf<String>()
        compare(fileName, core, converted, out)
        return out
    }

    private fun compare(path: String, a: Any?, b: Any?, out: MutableList<String>) {
        if (out.size >= 20) return
        when {
            a === b -> {}
            a == null || b == null -> out += "$path: ${show(a)} != ${show(b)}"
            a is Node && b is Node -> {
                if (a::class != b::class) { out += "$path: ${show(a)} != ${show(b)}"; return }
                for (field in fieldsOf(a.javaClass)) {
                    compare("$path.${field.name}", field.get(a), field.get(b), out)
                }
            }
            a is List<*> && b is List<*> -> {
                // `-core` keeps a block's trailing comments on a placeholder statement; nothing is lowered for it.
                val a = a.filter { it !is com.xemantic.typescript.compiler.NotEmittedStatement }
                if (a.size != b.size) out += "$path: ${a.size} elements != ${b.size} (${a.map(::show)} vs ${b.map(::show)})"
                else a.indices.forEach { compare("$path[$it]", a[it], b[it], out) }
            }
            a != b -> out += "$path: ${show(a)} != ${show(b)}"
        }
    }

    private fun show(x: Any?): String = when (x) {
        is Node -> "${x::class.simpleName}@${x.pos}-${x.end}"
        is String -> "\"$x\""
        else -> x.toString()
    }

    private val fields = HashMap<Class<*>, List<Field>>()

    private fun fieldsOf(c: Class<*>): List<Field> = fields.getOrPut(c) {
        generateSequence(c) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && it.name !in ignored && !it.name.endsWith("Comments") }
            .onEach { it.isAccessible = true }
            .toList()
    }
}

/** `-core`'s parse of [text]: the syntax the lowering walked before (TSGO.7-e). */
internal fun parseForLowering(fileName: String, text: String): SourceFile {
    val options = com.xemantic.typescript.compiler.CompilerOptions()
    val flags = com.xemantic.typescript.compiler.computeParserFlags(fileName, text, options)
    return com.xemantic.typescript.compiler.Parser(
        text,
        fileName,
        forceJsx = flags.forceJsx,
        topLevelAwait = flags.topLevelAwait,
        needsJsxFlag = flags.needsJsxFlag,
        noImplicitAny = flags.noImplicitAny,
    ).parse()
}

/** Every `.ts` under the given directories through [SyntaxDiff], with a summary (a sweep beyond the corpus). */
fun main(args: Array<String>) {
    var files = 0
    var differing = 0
    for (dir in args) File(dir).walk().filter { it.isFile && it.extension == "ts" && !it.name.endsWith(".d.ts") }.sortedBy { it.path }.forEach {
        files++
        val diffs = runCatching { SyntaxDiff.of(it.name, it.readText()) }.getOrElse { e -> listOf("THREW $e at ${e.stackTrace.take(4).joinToString(" < ")}") }
        if (diffs.isNotEmpty()) {
            differing++
            println("== ${it.path}")
            diffs.take(5).forEach { d -> println("  $d") }
        }
    }
    println("files=$files differing=$differing")
}
