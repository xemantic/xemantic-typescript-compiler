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

import com.xemantic.kotlin.test.assert
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.metadata.KotlinMetadataCompiler
import org.jetbrains.kotlin.config.Services
import java.nio.file.Files
import kotlin.io.path.deleteRecursively
import kotlin.test.Test

/**
 * THE COMPILE GATE: the generated Kotlin must COMPILE, checked in-test with
 * kotlinc's own metadata compiler (`KotlinMetadataCompiler`, the pattern of
 * kir's `KotlinMetadataKlib.kt` — `metadataKlib = true` is load-bearing there
 * and here, and the generated surface names only Kotlin built-ins, so no
 * classpath is needed).
 *
 * ## Which branch this gate took, and why
 *
 * Decided EMPIRICALLY (2026-09-01, Kotlin 2.4.10). Branch (i) — gating the
 * VERBATIM output including the `external` modifier — is refused by the
 * metadata compiler with three errors on the declaration:
 *
 *  - `error: only top-level functions can be external.`
 *  - `error: modifier 'external' is not applicable to 'class'.`
 *  - `error: modifier 'external' is not applicable to 'interface'.`
 *
 * `external interface` is a Kotlin/JS platform notion and a metadata
 * compilation has no platform. So this gate is branch (ii): it compiles
 * [KotlinExternals.compileCheckSource] — the SAME renderer invoked with the
 * flag that omits `external`, never a text strip — and therefore grades the
 * TYPE MAPPING; the `external` modifier itself is outside the gate, and
 * `external modifier is refused by the metadata compiler` below keeps the
 * decision honest: the day a Kotlin release starts accepting it, that pin
 * reddens and the gate should move to the verbatim output.
 */
class KotlinExternalsCompileGateTest {

    private class CompileCheck(
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
                    append(location.line).append(':').append(location.column)
                    append(' ')
                }
                append(message)
            }
        }

    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun compileCheck(source: String): CompileCheck {
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

    /** The MVP surface, end to end: every mapping, a fallback, backticks. */
    private val fixture = """
        export type Species = string;
        export interface Creature {
            name: string;
            limbCount: number;
            winged: boolean;
            nickname?: string;
            readonly kind: Species;
            tags: string | number;
            describe(prefix: string): string;
            touch(): void;
        }
        export interface Keys {
            object: string;
            val: number;
            in: boolean;
        }
    """.trimIndent()

    @Test
    fun `generated externals compile as kotlin metadata`() {
        val result = generateKotlinExternals("t.ts", fixture)
        val check = compileCheck(result.compileCheckSource)
        val compileErrors = check.errors
        assert(compileErrors.isEmpty())
        assert(check.successful)
    }

    @Test
    fun `negative control - a deliberately broken source fails the same gate`() {
        // Round 790's law: a verifier without its complement population reads
        // 0 both when the output is sound and when the instrument is dead.
        val check = compileCheck(
            """
            public interface Broken {
                public val p: NoSuchType
            }
            """.trimIndent()
        )
        val mentionsTheBrokenName = check.errors.any { "NoSuchType" in it }
        assert(!check.successful)
        assert(mentionsTheBrokenName)
    }

    @Test
    fun `external modifier is refused by the metadata compiler`() {
        // The living record of the branch decision in the class KDoc: while
        // this pin holds, the gate MUST grade the no-external variant; when it
        // reddens, the metadata compiler has started accepting `external` and
        // the gate should move to the verbatim output.
        val result = generateKotlinExternals("t.ts", fixture)
        val check = compileCheck(result.kotlin)
        val mentionsTheModifier = check.errors.any { "external" in it }
        assert(!check.successful)
        assert(mentionsTheModifier)
    }

}
