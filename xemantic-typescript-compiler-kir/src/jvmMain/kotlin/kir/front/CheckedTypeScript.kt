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
 * Parses, binds and checks [source], collecting backend facts as it goes.
 *
 * `useRealLibs` by default because a program that says `console.log` needs a
 * `console` to resolve to, and the embedded lib does not declare one — an
 * unknown name degrades to `any`, which is SILENT, so the failure would be a
 * wrong lowering rather than a diagnostic.
 *
 * [runWithDeepStack] is not optional: the checker recurses deeply enough on
 * ordinary input that the default JVM stack is not the budget it is written
 * against, and `Checker`'s own `init` block IS the check — so the sink has
 * already fired by the time the constructor returns.
 */
public fun checkTypeScript(
    fileName: String,
    source: String,
    options: CompilerOptions = CompilerOptions(useRealLibs = true),
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
