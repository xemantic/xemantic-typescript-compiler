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
import com.xemantic.typescript.compiler.kir.front.checkTypeScript
import com.xemantic.typescript.compiler.kir.lower.KirFileLowering
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
    val refusals = mutableListOf<KirDiagnostic>()
    val emit = KotlinIrEmitter(outputDirectory, classpath).emit {
        try {
            KirFileLowering(this, checked, packageName, "Main.kt").lower()
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
