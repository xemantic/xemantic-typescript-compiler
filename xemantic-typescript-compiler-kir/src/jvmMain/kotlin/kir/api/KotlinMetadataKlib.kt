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

package com.xemantic.typescript.compiler.kir.api

import com.xemantic.typescript.compiler.kir.emit.RecordingMessageCollector
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.cli.metadata.KotlinMetadataCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isDirectory
import kotlin.io.path.walk

/** What [compileMetadataKlib] produced. */
internal class MetadataKlibResult(
    val successful: Boolean,
    val messages: List<String>,
    val errors: List<String>,
)

/**
 * Compiles [source] — one Kotlin file — to a metadata klib at [output].
 *
 * The metadata compiler is a THIRD kotlinc entry point, beside the JVM pipeline
 * `KotlinIrEmitter` drives and the `IrGenerationExtension` the native backend
 * rides in as. It is the one that produces the artifact a Kotlin Multiplatform
 * `commonMain` compiles against: declarations, no bodies, no platform.
 *
 * Three things about it that are not obvious and were each measured:
 *
 *  - **`metadataKlib` is load-bearing.** Left false, the compiler writes the
 *    LEGACY layout — a `.kotlin_module` file under `META-INF`, beside
 *    per-package `.kotlin_metadata` files — which is not a klib and which a
 *    multiplatform consumer does not resolve. There is no error either way.
 *    (The layout is spelled out in prose because a path glob inside a KDoc
 *    opens a NESTED block comment, which CLAUDE.md records as silently eating
 *    the rest of the file.)
 *  - **No classpath is needed.** The generated surface names only Kotlin's
 *    BUILT-IN types, which the compiler carries in its own resources, so the
 *    artifact has no dependency to declare — including on the standard library,
 *    whose common metadata is a separate artifact this project does not ship.
 *  - **The compiler writes a DIRECTORY klib**, and what a build publishes is a
 *    zipped one. Both are accepted on a consumer's classpath (verified in
 *    `KotlinMetadataKlibTest`); the zip is produced here because a single file
 *    is what a Maven artifact can be.
 */
internal fun compileMetadataKlib(
    source: String,
    output: Path,
    moduleName: String,
    /**
     * Metadata klibs the source is compiled AGAINST.
     *
     * Empty for an exported API, which names only built-in types. It is a
     * parameter because the round-trip test compiles a CONSUMER through this
     * same function — a consumer of a klib is the only thing that can say
     * whether the klib is usable — and because the runtime's own metadata
     * artifact, when there is one, arrives here.
     */
    classpath: List<Path> = emptyList(),
): MetadataKlibResult {
    val work = Files.createTempDirectory("xtsc-kir-api")
    val messages = RecordingMessageCollector()
    try {
        val sourceFile = work.resolve("Api.kt")
        Files.writeString(sourceFile, source)
        val exploded = work.resolve("klib")
        val arguments = K2MetadataCompilerArguments().apply {
            freeArgs = listOf(sourceFile.toString())
            destination = exploded.toString()
            this.moduleName = moduleName
            metadataKlib = true
            if (classpath.isNotEmpty()) {
                this.classpath = classpath.joinToString(File.pathSeparator)
            }
            // The generated bodies are `null as T` casts whose only purpose is
            // to type-check; the compiler is right that they can never succeed
            // and it must not be a reason to fail.
            suppressWarnings = true
        }
        KotlinMetadataCompiler().exec(messages, Services.EMPTY, arguments)
        if (messages.hasErrors()) {
            return MetadataKlibResult(false, messages.all, messages.errors)
        }
        output.parent?.let { Files.createDirectories(it) }
        zip(exploded, output)
        return MetadataKlibResult(true, messages.all, emptyList())
    } finally {
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        work.deleteRecursively()
    }
}

/** Zips a directory klib into the single file a build can publish. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun zip(directory: Path, output: Path) {
    Files.newOutputStream(output).use { stream ->
        ZipOutputStream(stream).use { zip ->
            directory.walk().sorted().forEach { path ->
                if (path.isDirectory()) return@forEach
                // Entry names are RELATIVE to the klib root and `/`-separated:
                // a klib is read by the same code on every platform, and a
                // Windows-shaped separator would be a name nothing matches.
                val name = directory.relativize(path).joinToString("/")
                zip.putNextEntry(ZipEntry(name))
                Files.copy(path, zip)
                zip.closeEntry()
            }
        }
    }
}
