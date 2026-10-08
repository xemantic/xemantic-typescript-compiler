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

import com.xemantic.typescript.tsgo.ast.localize
import com.xemantic.typescript.tsgo.runtime.GoString
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

internal actual fun engineDiagnose(text: String, fileName: String): List<Diagnostic>? =
    when (val engine = System.getenv("XTSC_ENGINE")) {
        // The default: no engine here, `diagnose` runs its own path. Only a census run (XTSC_PIN_DUMP)
        // records this module's answer, computed exactly as `diagnose` computes it.
        null, "", "core" -> if (System.getenv("XTSC_PIN_DUMP").isNullOrEmpty()) null else TsgoEngine.dumpCore(text, fileName)
        "tsgo" -> TsgoEngine.diagnose(text, fileName)
        else -> error("XTSC_ENGINE=$engine: expected 'core' or 'tsgo'")
    }

/**
 * The ported tsgo compiler test harness as a [diagnose] engine (docs/goport-pin-census.md). [diagnose]'s
 * composed text IS the conformance format tsgo's runner reads (`// @option: value` directives,
 * `// @Filename:` units), so it goes through the ported `makeUnitsFromTest`, configuration enumeration and
 * check-only compile unchanged; only the RESULT is mapped:
 *
 * - `fileName`: the harness roots files at its current directory (`/.src`): that prefix is removed, so
 *   `t.ts` stays `t.ts` and an absolute `@Filename: /proj/c.ts` stays absolute; a bundled lib keeps its
 *   base name (`lib.es5.d.ts`);
 * - `start`/`length`: tsgo's UTF-8 byte offsets → UTF-16 offsets into the file text;
 * - `line`/`character`: from the UTF-16 text with this module's [lineAndCharacterAt];
 * - `message` / `messageChain`: tsgo's flattened text (`diagnosticwriter.FlattenDiagnosticMessage`) split
 *   into its first line and the indented chain lines, as this module stores them;
 * - a case that varies by an option (`// @strict: true, false`) compiles its FIRST configuration;
 * - the TEXT is re-spelled from this module's harness format into tsgo's ([tsgoText]): `// @useRealLibs`
 *   ("compile against the real `lib.*.d.ts`, not the embedded minimal lib") is dropped — tsgo always uses its
 *   real bundled libs, and its harness would refuse the unknown option — and an indented directive line is
 *   de-indented (this module accepts one, tsgo's anchored `optionRegex` does not).
 *
 * Every compile runs on one 1 GB-stack thread (tsgo recurses as deep as Go allows). With
 * `XTSC_PIN_DUMP=<dir>` each compiled text is also written as a conformance case
 * `<dir>/compiler/<sha>/<fileName>` with the port's diagnostics beside it (`.port.jsonl`, the oracle's
 * format) and the calling test in `<dir>/calls.tsv`, so `scripts/tsgo-pin-census.py` can run the same
 * text through tsgo itself.
 */
internal object TsgoEngine {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(null, r, "xtsc-tsgo-engine", 1L shl 30).apply { isDaemon = true } }

    private val dump: File? = System.getenv("XTSC_PIN_DUMP")?.takeIf { it.isNotEmpty() }?.let { File(it) }

    @Volatile private var initialized = false

    /** The OUTERMOST frame of a test class: the test method itself (or its lambda, on a test's own thread), not a private helper of the class. */
    private fun caller(): String = Throwable().stackTrace.lastOrNull { it.className.startsWith("com.xemantic.typescript.compiler.") && it.className.substringAfterLast('.').substringBefore('$').endsWith("Test") }
        ?.let { "${it.className.substringAfterLast('.').substringBefore('$')}.${it.methodName}" } ?: "?"

    /**
     * The text tsgo compiles: [diagnose]'s text read as THIS module's harness reads it (`parseMultiFileSource`),
     * re-spelled in tsgo's harness format: this module's `// @useRealLibs` dropped, and a directive line that
     * this module accepts INDENTED (it matches `// @key:` / `//@key:` after `trim()`) de-indented, because
     * tsgo's `optionRegex` is anchored at the line start — left indented, tsgo would read the `@Filename`
     * as source and compile a different program. `// @ts-ignore:` / `// @ts-expect-error:` are comment
     * directives in both harnesses (source lines) and stay as written.
     */
    private fun tsgoText(text: String) = text.lines()
        .filterNot { USE_REAL_LIBS.containsMatchIn(it) }
        .map { line ->
            val t = line.trim()
            val content = when {
                t.startsWith("// @") -> t.removePrefix("// @")
                t.startsWith("//@") -> t.removePrefix("//@")
                else -> null
            }
            val key = content?.takeIf { ':' in it }?.substringBefore(':')?.trim()?.lowercase()
            if (key != null && key != "ts-ignore" && key != "ts-expect-error") line.trimStart() else line
        }
        .joinToString("\n")

    /** The dump's case key for a text: both engines record the same text under the same key. */
    private fun key(text: String, fileName: String) =
        MessageDigest.getInstance("SHA-256").digest((fileName + "\u0000" + tsgoText(text)).toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** A census run with the default engine: this module's answer, recorded beside the case, returned unchanged. */
    fun dumpCore(text: String, fileName: String): List<Diagnostic> {
        val result = TypeScriptCompiler().compile(text, fileName).diagnostics
        val h = key(text, fileName)
        val f = File(dump, "core/$h.json")
        f.parentFile.mkdirs()
        f.writeText(result.joinToString(",\n", "[\n", "\n]\n") { diagJson(it) })
        synchronized(this) { File(dump, "calls-core.tsv").appendText("${caller()}\t$h\t${File(fileName).name}\n") }
        return result
    }

    /** One mapped [Diagnostic] as JSON (the census compares the two engines' answers field by field). */
    fun diagJson(d: Diagnostic): String {
        fun q(s: String?): String = if (s == null) "null" else buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
        return "{\"code\":${d.code},\"category\":${q(d.category.name)},\"fileName\":${q(d.fileName)},\"line\":${d.line},\"character\":${d.character}," +
            "\"start\":${d.start},\"length\":${d.length},\"message\":${q(d.message)},\"messageChain\":[${d.messageChain.joinToString(",") { q(it) }}]," +
            "\"related\":[${d.relatedInformation.joinToString(",") { diagJson(it) }}]}"
    }

    fun diagnose(text: String, fileName: String): List<Diagnostic> {
        val caller = caller()
        try {
            return executor.submit(Callable { compile(text, fileName, caller) }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun init() {
        if (initialized) return
        com.xemantic.typescript.tsgo.parser.goInitPackage()
        File("build/goport/ts-submodule").takeIf { File(it, "tests/lib").isDirectory }?.let {
            com.xemantic.typescript.tsgo.harness.typeScriptSubmodule = it.absolutePath
        }
        initialized = true
    }

    private fun compile(text: String, fileName: String, caller: String): List<Diagnostic> {
        init()
        return compileTsgo(tsgoText(text), fileName, key(text, fileName), caller)
    }

    /** `// @useRealLibs: …` (this module's harness only; case-insensitive like every directive). */
    private val USE_REAL_LIBS = Regex("""^\s*//\s*@userealLibs\s*:""", RegexOption.IGNORE_CASE)

    private fun compileTsgo(text: String, fileName: String, h: String, caller: String): List<Diagnostic> {
        val goText = GoString.fromUtf16(text)
        val goName = GoString.fromUtf16(fileName)
        val sha = dump?.let { d ->
            val case = File(d, "compiler/$h/${File(fileName).name}")
            case.parentFile.mkdirs()
            case.writeBytes(text.toByteArray())
            synchronized(this) { File(d, "calls.tsv").appendText("$caller\t$h\t${File(fileName).name}\n") }
            h to case
        }
        try {
            val named = com.xemantic.typescript.tsgo.harness.caseConfigurations(goText).first()
            val run = com.xemantic.typescript.tsgo.harness.runConfiguration(goName, goText, named)
            val cd = GoString.toUtf16(run.prepared.currentDirectory).trimEnd('/') + "/"
            val check = run.check
            val result = (0 until check.diagnostics.len).map { toDiagnostic(check.diagnostics[it]!!, cd) }
            sha?.let { (_, case) ->
                File(case.path + ".port.mapped.json").writeText(result.joinToString(",\n", "[\n", "\n]\n") { diagJson(it) })
                File(case.path + ".port.jsonl").writeText((0 until check.diagnostics.len).joinToString("") { oracleLine(check.diagnostics[it]!!) + "\n" })
                File(case.path + ".port.variation").writeText(com.xemantic.typescript.tsgo.harness.variationName(named))
            }
            return result
        } catch (t: Throwable) {
            sha?.let { (_, case) -> File(case.path + ".port.error").writeText(t.toString()) }
            throw t
        } finally {
            com.xemantic.typescript.tsgo.harness.purgeSourceFileCache()
        }
    }

    private fun flatten(d: com.xemantic.typescript.tsgo.ast.Diagnostic): String = buildString {
        append(GoString.toUtf16(d.localize(com.xemantic.typescript.tsgo.locale.Locale())))
        fun chain(c: com.xemantic.typescript.tsgo.ast.Diagnostic, level: Int) {
            append("\n").append("  ".repeat(level)).append(GoString.toUtf16(c.localize(com.xemantic.typescript.tsgo.locale.Locale())))
            for (j in 0 until c.messageChain.len) chain(c.messageChain[j]!!, level + 1)
        }
        for (j in 0 until d.messageChain.len) chain(d.messageChain[j]!!, 1)
    }

    private fun toDiagnostic(d: com.xemantic.typescript.tsgo.ast.Diagnostic, currentDirectory: String): Diagnostic {
        val lines = flatten(d).split("\n")
        val category = when (GoString.toUtf16(d.category.name())) {
            "error" -> DiagnosticCategory.Error
            "warning" -> DiagnosticCategory.Warning
            "suggestion" -> DiagnosticCategory.Suggestion
            else -> DiagnosticCategory.Message
        }
        val file = d.file
        if (file == null) {
            return Diagnostic(lines[0], category, d.code, messageChain = lines.drop(1),
                relatedInformation = (0 until d.relatedInformation.len).map { toDiagnostic(d.relatedInformation[it]!!, currentDirectory) })
        }
        val path = GoString.toUtf16(file.fileName())
        val name = when {
            path.startsWith("bundled:///libs/") -> path.removePrefix("bundled:///libs/")
            path.startsWith(currentDirectory) -> path.removePrefix(currentDirectory)
            else -> path
        }
        val goText = file.text()
        val pos = d.loc.pos.value
        val end = d.loc.end.value
        val start16 = GoString.toUtf16(goText.substring(0, pos)).length
        val end16 = start16 + GoString.toUtf16(goText.substring(pos, end)).length
        val text16 = GoString.toUtf16(goText)
        val (line, character) = lineAndCharacterAt(text16, start16)
        return Diagnostic(
            message = lines[0], category = category, code = d.code, fileName = name,
            line = line, character = character, start = start16, length = end16 - start16,
            relatedInformation = (0 until d.relatedInformation.len).map { toDiagnostic(d.relatedInformation[it]!!, currentDirectory) },
            messageChain = lines.drop(1),
        )
    }

    /** The diag oracle's JSONL line (docs/goport-diag-oracle.md § 3) without `phase` (not compared here). */
    private fun oracleLine(d: com.xemantic.typescript.tsgo.ast.Diagnostic): String {
        fun q(s: String?): String = if (s == null) "null" else buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
        fun fields(x: com.xemantic.typescript.tsgo.ast.Diagnostic) =
            "\"file\":${q(x.file?.let { GoString.toUtf16(it.fileName()) })},\"start\":${x.loc.pos.value},\"length\":${x.loc.end.value - x.loc.pos.value}," +
                "\"code\":${x.code},\"category\":${q(GoString.toUtf16(x.category.name()))},\"text\":${q(flatten(x))}"
        val rel = (0 until d.relatedInformation.len).joinToString(",") { "{" + fields(d.relatedInformation[it]!!) + "}" }
        return "{" + fields(d) + ",\"related\":[$rel],\"skippedOnNoEmit\":${d.skippedOnNoEmit}}"
    }
}
