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

import com.xemantic.typescript.compiler.Binder
import com.xemantic.typescript.compiler.Checker
import com.xemantic.typescript.compiler.CompilerOptions
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.typescript.compiler.Parser
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.computeParserFlags
import com.xemantic.typescript.compiler.runWithDeepStack
import com.xemantic.typescript.tsgo.compiler.getTypeChecker

/**
 * A checked TypeScript file, with every fact the backend will need already
 * extracted.
 *
 * The pairing is the whole point: a [SourceFile] alone is untyped syntax, and
 * the [facts] are only obtainable while the checker walks it. Holding them
 * together is what lets the lowering be an ordinary syntax-directed walk.
 */
public class CheckedTypeScript internal constructor(
    public val sourceFile: SourceFile,
    public val facts: CheckedFacts,
    /** Everything the parser, binder and checker reported, in that order. */
    public val diagnostics: List<Diagnostic>,
) {

    /** The diagnostics that are errors, i.e. the reason to refuse to emit. */
    public val errors: List<Diagnostic>
        get() = diagnostics.filter { it.category == DiagnosticCategory.Error }

}

/**
 * Which checker answers the KIR front end's questions.
 *
 * (TSGO.4-c) The PORTED tsgo checker by default; `XTSC_KIR_ENGINE=core` keeps
 * `-core`'s, for an A/B of the two front ends (the `-core` sunset report,
 * (TSGO.4-d)) — the lowering and everything after it are the same either way.
 */
internal val kirUsesCoreChecker: Boolean
    get() = System.getenv("XTSC_KIR_ENGINE") == "core"

/**
 * Parses and checks [source], collecting backend facts.
 *
 * (TSGO.4-c) The checker is the ported tsgo one: the file is checked as the
 * only file of a synthesized project (`files: [<it>]`, TypeScript 7's default
 * options and bundled libraries), and every fact is asked of tsgo's checker
 * after the check — see `TsgoFacts.kt`. `-core`'s parser still produces the
 * [CheckedTypeScript.sourceFile] the lowering walks.
 *
 * [options] is honoured for `strict` written false and for
 * `useDefineForClassFields`; the KIR has never been driven with anything else,
 * and the rest of `-core`'s option model has no single tsconfig spelling.
 */
public fun checkTypeScript(
    fileName: String,
    source: String,
    options: CompilerOptions = CompilerOptions(useRealLibs = true),
): CheckedTypeScript {
    if (kirUsesCoreChecker) return checkTypeScriptWithCore(fileName, source, options)
    val absolute = if (fileName.startsWith("/")) fileName else "$SYNTHETIC_ROOT/$fileName"
    val compilerOptions = buildList {
        if (options.strictExplicitlyFalse) add("\"strict\": false")
        options.useDefineForClassFields?.let { add("\"useDefineForClassFields\": $it") }
    }.joinToString(", ")
    val config = "$SYNTHETIC_ROOT/tsconfig.json"
    val fs = InMemoryFS(
        mapOf(
            absolute to source,
            config to "{ \"compilerOptions\": { $compilerOptions }, \"files\": [${jsonString(absolute)}] }",
        )
    )
    return TsgoPrograms.onDeepStack {
        val opened = TsgoPrograms.open(config, fs)
        val diagnostics = TsgoPrograms.diagnostics(opened) { if (it == absolute) fileName else it }
        val tsgoFile = opened.program.getSourceFile(com.xemantic.typescript.tsgo.runtime.GoString.fromUtf16(absolute))
            ?: error("tsgo did not load '$absolute'")
        val coreFile = parseForLowering(fileName, source)
        val facts = CheckedFacts()
        buildFacts(opened, listOf(FileNodeMap(coreFile, tsgoFile)), facts)
        CheckedTypeScript(coreFile, facts, diagnostics)
    }
}

/** The directory a single-file compile's synthesized project lives in. */
private const val SYNTHETIC_ROOT = "/xtsc-kir"

private fun jsonString(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * Asks tsgo's query checker every fact, after the check.
 *
 * The checker is the pool's QUERY checker (`XtscCheckerPool` index 2), the one
 * the diagnostics above ran on too, so every answer comes from the checker
 * that checked the program.
 */
internal fun buildFacts(
    opened: TsgoProgram,
    maps: List<FileNodeMap>,
    facts: CheckedFacts,
): List<Pair<String, String>> {
    val ctx = com.xemantic.typescript.tsgo.go.context.background()
    val (checker, done) = opened.program.getTypeChecker(ctx)
    try {
        val builder = TsgoFactsBuilder(checker!!, maps, facts)
        builder.build()
        return builder.importEdges()
    } finally {
        done?.invoke()
    }
}

/** `-core`'s own checker (`XTSC_KIR_ENGINE=core`), as the KIR front end was before (TSGO.4-c). */
internal fun checkTypeScriptWithCore(
    fileName: String,
    source: String,
    options: CompilerOptions,
): CheckedTypeScript {
    val flags = computeParserFlags(fileName, source, options)
    val parser = Parser(
        source,
        fileName,
        forceJsx = flags.forceJsx,
        topLevelAwait = flags.topLevelAwait,
        needsJsxFlag = flags.needsJsxFlag,
        noImplicitAny = flags.noImplicitAny,
    )
    val sourceFile = parser.parse()
    val parseDiagnostics = parser.getDiagnostics()
    val binderResult = Binder(options).bind(sourceFile)
    val facts = CheckedFacts()
    val checker = runWithDeepStack {
        Checker(
            options,
            listOf(binderResult),
            isMultiFileSource = true,
            checkedSink = facts,
        )
    }
    return CheckedTypeScript(
        sourceFile,
        facts,
        parseDiagnostics + checker.getDiagnostics(),
    )
}
