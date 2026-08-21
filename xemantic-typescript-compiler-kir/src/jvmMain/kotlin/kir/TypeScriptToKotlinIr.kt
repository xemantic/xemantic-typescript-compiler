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

package com.xemantic.typescript.compiler.kir

import com.xemantic.typescript.compiler.CompilerOptions
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.kir.emit.EmitResult
import com.xemantic.typescript.compiler.kir.emit.GeneratedProgramClasspath
import com.xemantic.typescript.compiler.kir.emit.KotlinIrEmitter
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.kir.front.CheckedFacts
import com.xemantic.typescript.compiler.kir.front.checkTypeScript
import com.xemantic.typescript.compiler.kir.front.checkTypeScriptProject
import com.xemantic.typescript.compiler.kir.lower.KirProgramLowering
import java.nio.file.Path

/** What [compileTypeScriptToJvm] produced. */
public class KirCompilation internal constructor(
    /** True only when class files were written and nothing was refused. */
    public val successful: Boolean,
    /** The fully qualified entry point, e.g. `program.MainKt`. */
    public val mainClass: String,
    /** Errors the TYPE CHECKER reported — the program was never lowered. */
    public val typeErrors: List<Diagnostic>,
    /** What the backend refused to lower, with file, position and construct. */
    public val refusals: List<KirDiagnostic>,
    /** What kotlinc reported about the IR it was handed. */
    public val emit: EmitResult?,
) {

    override fun toString(): String = buildString {
        append(if (successful) "compilation succeeded" else "compilation FAILED")
        typeErrors.forEach {
            append("\n  TS${it.code} ${it.fileName}:${it.line}:${it.character} ${it.message}")
        }
        refusals.forEach { append("\n  refused: $it") }
        emit?.takeIf { !it.successful }?.messages?.forEach { append("\n  $it") }
    }

}

/**
 * Compiles one TypeScript file to JVM `.class` files, through Kotlin IR.
 *
 * The pipeline in one call: parse, bind and CHECK (collecting the type facts
 * during the walk, because they are unobtainable afterwards), lower the checked
 * tree to Kotlin IR, then hand that IR to kotlinc's own JVM backend phases.
 *
 * It refuses to lower a program the checker rejected. That is not politeness:
 * every answer the lowering depends on — which overload a call selected, what a
 * union narrowed to, whether a `+` concatenates — is only meaningful for a
 * program that type-checks, and lowering an ill-typed one would produce
 * plausible bytecode for a program that does not exist.
 *
 * @param fileName the TypeScript file's name, as diagnostics should spell it.
 * @param source its contents.
 * @param outputDirectory where the `.class` files land.
 * @param packageName the generated package; the entry point is `<it>.MainKt`.
 */
public fun compileTypeScriptToJvm(
    fileName: String,
    source: String,
    outputDirectory: Path,
    packageName: String = "program",
    options: CompilerOptions = CompilerOptions(useRealLibs = true),
    classpath: List<Path> = GeneratedProgramClasspath.minimal(),
): KirCompilation {
    val mainClass = "$packageName.MainKt"
    val checked = checkTypeScript(fileName, source, options)
    if (checked.errors.isNotEmpty()) {
        return KirCompilation(false, mainClass, checked.errors, emptyList(), null)
    }
    return lowerAndEmit(
        mainClass,
        listOf(checked.sourceFile),
        checked.sourceFile,
        checked.facts,
        outputDirectory,
        packageName,
        classpath,
    )
}

/**
 * Compiles a whole TypeScript PROJECT — a directory with a `tsconfig.json` — to
 * JVM `.class` files.
 *
 * The multi-file form of [compileTypeScriptToJvm], and the one a library needs:
 * a module's exported function is called from another module, and nothing about
 * the `import` statement is consulted to make that work. The checker has
 * already turned each imported name into the DECLARATION it names, and the
 * declare pass records an IR symbol per declaration for the whole program, so a
 * cross-file call is an ordinary direct call.
 *
 * @param entryFileName the file whose top-level statements become `main`; the
 *   only file allowed to have any, since running the others would need module
 *   initialization order, which is a design question and not a default.
 */
public fun compileTypeScriptProjectToJvm(
    projectPath: String,
    entryFileName: String,
    outputDirectory: Path,
    packageName: String = "program",
    classpath: List<Path> = GeneratedProgramClasspath.minimal(),
): KirCompilation {
    val mainClass = "$packageName.MainKt"
    val checked = checkTypeScriptProject(projectPath)
    if (checked.errors.isNotEmpty()) {
        return KirCompilation(false, mainClass, checked.errors, emptyList(), null)
    }
    val entry = checked.files.firstOrNull { it.fileName.endsWith(entryFileName) }
        ?: return KirCompilation(
            successful = false,
            mainClass = mainClass,
            typeErrors = emptyList(),
            refusals = listOf(
                KirDiagnostic(
                    "no program file named '$entryFileName'; the program has " +
                        checked.files.joinToString { it.fileName },
                    entryFileName,
                    1,
                    1,
                )
            ),
            emit = null,
        )
    return lowerAndEmit(
        mainClass,
        checked.files,
        entry,
        checked.facts,
        outputDirectory,
        packageName,
        classpath,
        checked.importEdges,
    )
}

private fun lowerAndEmit(
    mainClass: String,
    files: List<SourceFile>,
    entry: SourceFile,
    facts: CheckedFacts,
    outputDirectory: Path,
    packageName: String,
    classpath: List<Path>,
    importEdges: List<Pair<String, String>> = emptyList(),
): KirCompilation {
    val refusals = mutableListOf<KirDiagnostic>()
    val emit = KotlinIrEmitter(outputDirectory, classpath).emit {
        try {
            KirProgramLowering(this, facts, files, entry, packageName, importEdges).lower()
        } catch (e: KirLoweringException) {
            refusals.add(e.diagnostic)
            throw e
        }
    }
    return KirCompilation(
        successful = emit.successful && refusals.isEmpty(),
        mainClass = mainClass,
        typeErrors = emptyList(),
        refusals = refusals,
        emit = emit,
    )
}
