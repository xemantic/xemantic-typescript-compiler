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

import org.intellij.lang.annotations.Language

/**
 * Compiles an inline TypeScript [source] snippet and returns its diagnostics.
 *
 * The snippet is [String.trimIndent]-ed and prefixed with [directives]
 * (harness `// @option: value` lines); pass an empty string to compile
 * without any directives. Shared declarations reused across a test class
 * are interpolated or concatenated into [source] by the caller.
 */
/**
 * The directive header for a pin whose SUBJECT is a `target < ES2015` DOWNLEVEL gate —
 * TS1250 / TS1501 / TS1503 / TS2373 / TS2396 / TS2659 / TS2737 / TS18045 and their
 * siblings.
 *
 * Such a pin must name its target EXPLICITLY. Round 945 ((CHK.21)) made every one of
 * those gates read [CompilerOptions.defaultedTarget], so an UNSET target is the LATEST
 * standard (tsc's `getEmitScriptTarget`) and the gates are SHUT there — which is what
 * pristine does: across the whole baseline corpus every TS1250 / TS1501 / TS1503 /
 * TS2396 / TS2659 / TS2737 / TS18045 comes from a fixture with an explicit `@target`.
 * Twenty-one pins written before that round relied on [CompilerOptions.target]'s `ES3`
 * zero value to open the gate for them, i.e. on the false positive; they were re-pointed
 * here, which restores the exact population each was written to measure.
 *
 * `@ignoreDeprecations` keeps TS5107 ("Option 'target=ES5' is deprecated") out of the
 * result — every re-pointed pin counts one code, so it would not have mattered, but a
 * later exact-list assertion would trip over it.
 */
internal const val DOWNLEVEL_ES5: String =
    "// @strict: true\n// @target: es5\n// @ignoreDeprecations: 6.0"

internal fun diagnose(
    @Language("typescript") source: String,
    directives: String = "// @strict: true",
    fileName: String = "t.ts",
): List<Diagnostic> =
    TypeScriptCompiler().compile(
        (if (directives.isEmpty()) "" else directives + "\n") + source.trimIndent(),
        fileName,
    ).diagnostics

/**
 * Compiles [source] EXACTLY as given, bypassing the `// @directive` header format.
 *
 * The single reason to prefer this over [diagnose] is a test about LINE
 * TERMINATORS, and BOTH of that helper's layers destroy them: `trimIndent` splits
 * on `\r\n` / `\n` / `\r` alike and rejoins with `\n`, and `parseMultiFileSource`
 * — the header/`@Filename` splitter behind `TypeScriptCompiler.compile(String)` —
 * opens with `.replace("\r\n", "\n").replace("\r", "\n")`. So the string entry
 * point cannot carry a `\r` into the Parser at all, which is why a lone-`\r` defect
 * is unreachable from the whole generated corpus and not merely unrepresented in
 * it. The project/`Vfs` path performs no such normalisation, and this helper
 * reproduces it by handing the pipeline a [ParsedSource] directly.
 *
 * Anything not about line terminators belongs on [diagnose].
 */
internal fun diagnoseVerbatim(
    source: String,
    fileName: String = "t.ts",
    options: CompilerOptions = CompilerOptions(),
): List<Diagnostic> = TypeScriptCompiler().compileParsed(
    ParsedSource(options, listOf(SourceFileEntry(fileName, source))),
    options,
    fileName,
).diagnostics
