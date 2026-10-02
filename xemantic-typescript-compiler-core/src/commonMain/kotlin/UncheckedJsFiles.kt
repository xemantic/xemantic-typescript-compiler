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
/**
 * (CHK.202) Which of a file's CHECKER rows TypeScript 7 reports at all — tsgo's
 * `Program.getBindAndCheckDiagnosticsWithChecker` / `canIncludeBindAndCheckDiagnostics`
 * (`internal/compiler/program.go`), ported as a filter over the finished diagnostics.
 *
 * Three answers, decided per FILE:
 *
 * - [Mode.SKIPPED]: a `// @ts-nocheck` leading pragma (any file kind, `.ts` included), or a
 *   JavaScript file under an EXPLICIT `checkJs: false`. tsgo returns NOTHING for it — no
 *   bind, no checker and no comment-directive row.
 * - [Mode.PLAIN]: a JavaScript file with no `// @ts-check` / `// @ts-nocheck` pragma while
 *   `checkJs` is UNSET (`ast.IsPlainJSFile`). tsgo keeps only the binder and grammar rows
 *   listed in [PLAIN_JS_CODES] and applies no `@ts-ignore` / `@ts-expect-error` directive
 *   (it returns before `getDiagnosticsWithPrecedingDirectives`).
 * - [Mode.CHECKED]: everything else — a TypeScript file, `checkJs: true`, or `// @ts-check`.
 *
 * This only ever REMOVES rows: `// @ts-check` without `checkJs` is not modelled as turning
 * this checker's `checkJs`-gated walkers on, so such a file reports what it reported before.
 *
 * Declaration diagnostics (TS4xxx / TS9xxx, emitted here by the checker's declaration-emit
 * walkers) survive both filters, because tsgo collects them through
 * `GetDeclarationDiagnostics`, which never consults `SkipTypeChecking` — the rule
 * [Checker]'s `noCheck` filter already states.
 */
internal object UncheckedJsFiles {

    enum class Mode { CHECKED, PLAIN, SKIPPED }

    /**
     * tsgo's `plainJSErrors` set (`internal/compiler/program.go`), as codes: the 91
     * binder errors, grammar errors and the one type error ("This condition will always
     * return …") that a plain JavaScript file still reports.
     */
    val PLAIN_JS_CODES: Set<Int> = hashSetOf(
        1005, 1009, 1013, 1014, 1029, 1030, 1031, 1042, 1044, 1048, 1049, 1053, 1054, 1089,
        1090, 1091, 1097, 1100, 1101, 1102, 1104, 1105, 1106, 1107, 1111, 1113, 1114, 1115,
        1116, 1123, 1155, 1156, 1162, 1171, 1172, 1174, 1182, 1184, 1186, 1188, 1189, 1190,
        1191, 1193, 1197, 1200, 1210, 1211, 1214, 1215, 1248, 1255, 1258, 1262, 1308, 1312,
        1325, 1341, 1344, 1358, 1359, 1368, 1450, 1451, 1473, 1474, 2451, 2462, 2480, 2492,
        2501, 2528, 2566, 2633, 2752, 2753, 2803, 2839, 2852, 5076, 17000, 17001, 17012, 18006,
        18007, 18012, 18013, 18016, 18036, 18038, 18041,
    )

    /**
     * Rows tsgo reports as SYNTACTIC diagnostics of a JavaScript file — so whatever [Mode]
     * says — that this compiler's CHECKER emits: the parser's `jsErrorAtRange` set
     * ("… can only be used in TypeScript files", `Decorators are not valid here`, TS1486)
     * and TS1003, the JSDoc `@typedef` parse error (`jsdocTypedefNoCrash`,
     * `misspelledJsDocTypedefTags`). Keyed by CODE, so a checker-grammar TS1003 tsgo would
     * drop in a plain file is kept too — a row no corpus case or library carries.
     */
    val SYNTACTIC_JS_CODES: Set<Int> = hashSetOf(
        1003, 1206, 1486, 8002, 8003, 8004, 8005, 8006, 8008, 8009, 8010, 8011, 8012, 8013,
        8016, 8017, 8037, 8038,
    )

    /** Whether a [mode] file reports a row of [code]. */
    fun keeps(mode: Mode, code: Int, options: CompilerOptions): Boolean = when (mode) {
        Mode.CHECKED -> true
        Mode.PLAIN -> code in PLAIN_JS_CODES || code in SYNTACTIC_JS_CODES || isDeclarationDiagnostic(code, options)
        Mode.SKIPPED -> code in SYNTACTIC_JS_CODES || isDeclarationDiagnostic(code, options)
    }

    /** tsgo's `ScriptKindJS` / `ScriptKindJSX` by extension (`.js`, `.cjs`, `.mjs`, `.jsx`). */
    fun isJsFileName(fileName: String): Boolean =
        fileName.endsWith(".js") || fileName.endsWith(".jsx") ||
            fileName.endsWith(".mjs") || fileName.endsWith(".cjs")

    fun modeOf(fileName: String, text: String, options: CompilerOptions): Mode {
        val directive = checkJsDirective(text)
        if (directive == false) return Mode.SKIPPED
        if (!isJsFileName(fileName)) return Mode.CHECKED
        if (directive == true || options.checkJs) return Mode.CHECKED
        return if (options.checkJsExplicitlyFalse) Mode.SKIPPED else Mode.PLAIN
    }

    /** Declaration-emit rows, which tsgo reports whatever [Mode] says ([keptUnderNoCheck]'s range). */
    fun isDeclarationDiagnostic(code: Int, options: CompilerOptions): Boolean =
        (options.declaration || options.composite) && (code in 4000..4999 || code in 9000..9999)

    /**
     * The file's `CheckJsDirective`: `true` for `// @ts-check`, `false` for
     * `// @ts-nocheck`, `null` for neither — the LAST of the two among the file's
     * LEADING comments wins (tsgo `processPragmasIntoFields`). Only a `//` / `///`
     * comment carries it (`extractPragmas`): optional blanks, `@`, then a name of
     * `[A-Za-z-]+` compared case-insensitively, so `@ts-check: x` counts and
     * `@ts-checked` does not. A leading shebang line is skipped, as the scanner's
     * `GetLeadingCommentRanges(…, 0)` does.
     */
    fun checkJsDirective(text: String): Boolean? {
        var pos = 0
        val n = text.length
        if (n > 0 && text[0] == '﻿') pos = 1
        if (pos + 1 < n && text[pos] == '#' && text[pos + 1] == '!') {
            while (pos < n && text[pos] != '\n' && text[pos] != '\r') pos++
        }
        var result: Boolean? = null
        while (pos < n) {
            val c = text[pos]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' ||
                c == ' ' || c == '﻿' || c == ' ' || c == ' '
            ) { pos++; continue }
            if (c != '/' || pos + 1 >= n) break
            val next = text[pos + 1]
            if (next == '/') {
                var end = pos + 2
                while (end < n && text[end] != '\n' && text[end] != '\r' &&
                    text[end] != ' ' && text[end] != ' '
                ) end++
                singleLinePragma(text, pos, end)?.let { result = it }
                pos = end
            } else if (next == '*') {
                val close = text.indexOf("*/", pos + 2)
                if (close < 0) break
                pos = close + 2
            } else break
        }
        return result
    }

    private fun singleLinePragma(text: String, start: Int, end: Int): Boolean? {
        var p = start + 2
        if (p < end && text[p] == '/') p++
        while (p < end && (text[p] == ' ' || text[p] == '\t')) p++
        if (p >= end || text[p] != '@') return null
        p++
        val nameStart = p
        while (p < end && (text[p] in 'A'..'Z' || text[p] in 'a'..'z' || text[p] == '-')) p++
        return when (text.substring(nameStart, p).lowercase()) {
            "ts-check" -> true
            "ts-nocheck" -> false
            else -> null
        }
    }
}
