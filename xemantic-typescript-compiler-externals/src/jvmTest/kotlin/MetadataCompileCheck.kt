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

package com.xemantic.typescript.compiler.externals

import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.metadata.KotlinMetadataCompiler
import org.jetbrains.kotlin.config.Services
import java.nio.file.Files
import kotlin.io.path.deleteRecursively

/**
 * The in-test metadata compile behind every externals compile gate — extracted
 * from `KotlinExternalsCompileGateTest` when (EXT.6) added a second gate (the
 * mitt fixture). `metadataKlib = true` is load-bearing (the kir lesson: false
 * writes the LEGACY layout under the same name, silently), and the generated
 * surface names only Kotlin built-ins, so no classpath is needed.
 */
internal class CompileCheck(
    val successful: Boolean,
    val errors: List<String>,
)

private class RecordingMessageCollector : MessageCollector {

    private val recordedErrors = mutableListOf<String>()

    val errors: List<String> get() = recordedErrors.toList()

    override fun clear() {
        recordedErrors.clear()
    }

    override fun hasErrors(): Boolean = recordedErrors.isNotEmpty()

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        if (severity.isError) recordedErrors += buildString {
            if (location != null) {
                // (EXT.24) The FILE too: a multi-file gate over 51 generated
                // sources reports a line number that names nothing without it.
                append(location.path.substringAfterLast('/')).append(':')
                append(location.line).append(':').append(location.column)
                append(' ')
            }
            append(message)
        }
    }

}

/**
 * (EXT.21b) The MULTI-FILE metadata compile: one compilation over several
 * generated sources, each its own file with its own `package` line — what a
 * per-module generation set IS, and the only way a cross-module reference
 * (`node.net.Socket` named from the `dgram` generation) can be graded, since
 * it resolves against another file of the same compilation.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
internal fun compileCheckAll(sources: List<Pair<String, String>>): CompileCheck {
    val work = Files.createTempDirectory("xtsc-externals-gate-all")
    try {
        val files = sources.map { (name, source) ->
            val file = work.resolve("$name.kt")
            Files.writeString(file, source)
            file.toString()
        }
        val messages = RecordingMessageCollector()
        val arguments = K2MetadataCompilerArguments().apply {
            freeArgs = files
            destination = work.resolve("klib").toString()
            moduleName = "xtsc-externals-gate"
            metadataKlib = true
        }
        KotlinMetadataCompiler().exec(messages, Services.EMPTY, arguments)
        return CompileCheck(!messages.hasErrors(), messages.errors)
    } finally {
        work.deleteRecursively()
    }
}

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
internal fun compileCheck(source: String): CompileCheck {
    val work = Files.createTempDirectory("xtsc-externals-gate")
    try {
        val sourceFile = work.resolve("Externals.kt")
        Files.writeString(sourceFile, source)
        val messages = RecordingMessageCollector()
        val arguments = K2MetadataCompilerArguments().apply {
            freeArgs = listOf(sourceFile.toString())
            destination = work.resolve("klib").toString()
            moduleName = "xtsc-externals-gate"
            metadataKlib = true
        }
        KotlinMetadataCompiler().exec(messages, Services.EMPTY, arguments)
        return CompileCheck(!messages.hasErrors(), messages.errors)
    } finally {
        work.deleteRecursively()
    }
}
