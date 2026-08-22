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
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.kir.api.KotlinApiModule
import com.xemantic.typescript.compiler.kir.api.TypeScriptApiExtractor
import com.xemantic.typescript.compiler.kir.api.compileMetadataKlib
import com.xemantic.typescript.compiler.kir.api.render
import com.xemantic.typescript.compiler.kir.front.CheckedFacts
import com.xemantic.typescript.compiler.kir.front.checkTypeScript
import com.xemantic.typescript.compiler.kir.front.checkTypeScriptProject
import java.nio.file.Path

/** What an API export produced. */
public class KotlinMetadataExport internal constructor(
    /** True only when a klib was written and the checker reported no errors. */
    public val successful: Boolean,
    /** The written metadata klib, or null when nothing was written. */
    public val klib: Path?,
    /** The exported surface, before it was rendered — for inspection and pins. */
    public val api: KotlinApiModule,
    /** The generated Kotlin source, which is the artifact's readable form. */
    public val source: String,
    /** Errors the TYPE CHECKER reported — nothing was exported. */
    public val typeErrors: List<Diagnostic>,
    /** Declarations left OFF the surface, each saying why. */
    public val refusals: List<KirDiagnostic>,
    /** What kotlinc reported about the generated source. */
    public val messages: List<String>,
) {

    override fun toString(): String = buildString {
        append(if (successful) "exported" else "export FAILED")
        append(' ').append(api.declarations.size).append(" declaration(s)")
        typeErrors.forEach {
            append("\n  TS${it.code} ${it.fileName}:${it.line}:${it.character} ${it.message}")
        }
        refusals.forEach { append("\n  not exported: $it") }
        if (!successful) messages.forEach { append("\n  $it") }
    }

}

/**
 * Exports one TypeScript file's public API as a Kotlin metadata klib.
 *
 * The artifact is what a Kotlin Multiplatform project's `commonMain` compiles
 * against: the library's exported declarations, typed by the TypeScript
 * checker's own answers and erased by `docs/kir-kotlin-metadata.md` §3.
 *
 * It refuses to export the API of a program the checker rejected, for the
 * reason the IR backend refuses to lower one: every type on the surface is an
 * answer about a program that type-checks, and a surface derived from an
 * ill-typed one describes a library that does not exist.
 *
 * @param outputKlib the `.klib` file to write; its directory is created.
 * @param packageName the Kotlin package the declarations land in.
 */
public fun exportTypeScriptApi(
    fileName: String,
    source: String,
    outputKlib: Path,
    packageName: String = "ts",
    moduleName: String = defaultModuleName(outputKlib),
    options: CompilerOptions = CompilerOptions(useRealLibs = true),
): KotlinMetadataExport {
    val checked = checkTypeScript(fileName, source, options)
    if (checked.errors.isNotEmpty()) {
        return failed(packageName, checked.errors)
    }
    return export(
        listOf(checked.sourceFile),
        checked.sourceFile,
        checked.facts,
        outputKlib,
        packageName,
        moduleName,
    )
}

/**
 * Exports a whole TypeScript PROJECT's public API — the form a real library
 * takes.
 *
 * [entryFileName] is the module whose exports ARE the public API: a package's
 * `index.ts` states what it offers, and the extractor follows its re-exports
 * (`export * from`, `export { x } from`) into the rest of the program rather
 * than taking the union of everything every file marks `export`.
 */
public fun exportTypeScriptProjectApi(
    projectPath: String,
    entryFileName: String,
    outputKlib: Path,
    packageName: String = "ts",
    moduleName: String = defaultModuleName(outputKlib),
): KotlinMetadataExport {
    val checked = checkTypeScriptProject(projectPath)
    if (checked.errors.isNotEmpty()) {
        return failed(packageName, checked.errors)
    }
    val entry = checked.files.firstOrNull { it.fileName.endsWith(entryFileName) }
        ?: return KotlinMetadataExport(
            successful = false,
            klib = null,
            api = KotlinApiModule(packageName, emptyList()),
            source = "",
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
            messages = emptyList(),
        )
    return export(
        checked.files,
        entry,
        checked.facts,
        outputKlib,
        packageName,
        moduleName,
    )
}

private fun export(
    files: List<SourceFile>,
    entry: SourceFile,
    facts: CheckedFacts,
    outputKlib: Path,
    packageName: String,
    moduleName: String,
): KotlinMetadataExport {
    val extracted = TypeScriptApiExtractor(files, facts, packageName).extract(entry)
    val source = extracted.module.render()
    val compiled = compileMetadataKlib(source, outputKlib, moduleName)
    return KotlinMetadataExport(
        successful = compiled.successful,
        klib = if (compiled.successful) outputKlib else null,
        api = extracted.module,
        source = source,
        typeErrors = emptyList(),
        refusals = extracted.refusals,
        messages = compiled.messages,
    )
}

private fun failed(
    packageName: String,
    errors: List<Diagnostic>,
): KotlinMetadataExport = KotlinMetadataExport(
    successful = false,
    klib = null,
    api = KotlinApiModule(packageName, emptyList()),
    source = "",
    typeErrors = errors,
    refusals = emptyList(),
    messages = emptyList(),
)

/**
 * The klib's own name, taken from the file it is written to.
 *
 * It reaches the artifact's manifest and is what a consumer's diagnostics name
 * the library by, so it defaults to something a human chose rather than to a
 * constant every exported library would share.
 */
private fun defaultModuleName(outputKlib: Path): String =
    outputKlib.fileName.toString().removeSuffix(".klib").ifEmpty { "ts" }
