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

import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.config.Services
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * (EXT.17) The Kotlin/JS twin of [compileCheck]: compiles the REAL generated
 * output — `external` declarations, `@file:JsModule`, `@file:JsNonModule`,
 * `@JsName` — with `K2JSCompiler` from the embedded compiler, producing a
 * klib directory (no JavaScript is generated; the klib stage is where every
 * declaration-level Kotlin/JS diagnostic is reported, and the gate is about
 * legality, not emission).
 *
 * ## Why this is a LOCAL-only gate
 *
 * `K2JSCompiler` is in `kotlin-compiler-embeddable`, already on this module's
 * `jvmTest` classpath — but a Kotlin/JS compilation resolves every name,
 * including `kotlin.js.JsModule` and `kotlin.Int`, from the Kotlin/JS STDLIB
 * KLIB (`org.jetbrains.kotlin:kotlin-stdlib-js:<version>`, a `.klib`), which
 * NOTHING in this repo's build pulls: measured 2026-09-02, the Gradle cache
 * holds the JVM stdlib jars and the linuxX64 klibs of every dependency and
 * not one `kotlin-stdlib-js-*.klib` (the Kotlin/Native stdlib under
 * `~/.konan` is `builtins_platform=NATIVE` and is the wrong platform). Adding
 * that artifact is a build-file change, which is an owner decision — so the
 * klib is LOCATED rather than depended on ([JsStdlib.locate]): from
 * `XTSC_KOTLIN_STDLIB_JS` (an environment variable — Gradle does not forward
 * `-D` to the test JVM) or, failing that, from the Gradle module cache under
 * the running compiler's own version, which is where it WOULD land the day
 * the build declares it. When neither answers, every gate built on this
 * prints `SKIPPED: …` naming the paths it looked at and returns — the
 * `KotlinExternalsTypescriptGateTest` shape, and for the same reason: a gate
 * that reads a local artifact must be loud about not finding it, never
 * quietly green.
 *
 * The version is pinned to the COMPILER's: a klib carries `abi_version` and
 * the compiler refuses one newer than itself, and an older one is a
 * measurement of the wrong stdlib.
 */
internal class JsCompileCheck(
    val successful: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
)

internal object JsStdlib {

    private const val ENV = "XTSC_KOTLIN_STDLIB_JS"

    val version: String = KotlinCompilerVersion.VERSION

    /** Every path consulted, in order — for the skip line. */
    fun candidates(): List<Path> {
        val explicit = System.getenv(ENV)
        if (explicit != null) return listOf(Path.of(explicit))
        val gradleHome = System.getenv("GRADLE_USER_HOME")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".gradle")
        val versionDir = gradleHome.resolve("caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib-js/$version")
        val hashed =
            if (Files.isDirectory(versionDir)) versionDir.listDirectoryEntries().sorted().map { it.resolve("kotlin-stdlib-js-$version.klib") }
            else emptyList()
        return hashed.ifEmpty { listOf(versionDir.resolve("<hash>/kotlin-stdlib-js-$version.klib")) }
    }

    /**
     * The klib, or null with the `SKIPPED:` line already printed.
     *
     * An ABSENT [ENV] is a statement about the BOX — a developer running the
     * gate outside Gradle — and skips loudly. A [ENV] that is SET and names
     * nothing is a statement about the BUILD, which since (EXT.17) declares
     * the klib as a resolvable configuration and passes its single file here:
     * that cannot be true on a correct build, so it FAILS rather than skips.
     * Without the split the ablation of that build block reads `28 tests, 0
     * failures` having compiled nothing — a gate quietly green, which is the
     * one thing a gate over a located artifact may never be.
     */
    fun locate(): Path? {
        val candidates = candidates()
        val found = candidates.firstOrNull { it.isRegularFile() }
        if (found == null) {
            val explicit = System.getenv(ENV)
            check(explicit == null) {
                "$ENV names '$explicit', which is not a file. The build declares the " +
                    "Kotlin/JS stdlib klib (EXT.17, xemantic-typescript-compiler-externals/" +
                    "build.gradle.kts) and passes it here, so this is a build defect, not a " +
                    "missing local artifact — the gate refuses to pass without compiling."
            }
            println(
                "SKIPPED: Kotlin/JS stdlib klib for compiler $version not present at " +
                    candidates.joinToString(" or ") + " (set $ENV to a kotlin-stdlib-js-$version.klib)"
            )
        }
        return found
    }

}

private class RecordingJsMessageCollector : MessageCollector {

    private val recordedErrors = mutableListOf<String>()
    private val recordedWarnings = mutableListOf<String>()

    val errors: List<String> get() = recordedErrors.toList()
    val warnings: List<String> get() = recordedWarnings.toList()

    override fun clear() {
        recordedErrors.clear()
        recordedWarnings.clear()
    }

    override fun hasErrors(): Boolean = recordedErrors.isNotEmpty()

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        if (!severity.isError && !severity.isWarning) return
        val line = buildString {
            if (location != null) {
                // (EXT.24) The FILE too: a multi-file gate over 51 generated
                // sources reports a line number that names nothing without it.
                append(location.path.substringAfterLast('/')).append(':')
                append(location.line).append(':').append(location.column)
                append(' ')
            }
            append(message)
        }
        if (severity.isError) recordedErrors += line else recordedWarnings += line
    }

}

/**
 * (EXT.21b) The MULTI-FILE Kotlin/JS compile — the twin of [compileCheckAll],
 * and the one that grades the wiring as well as the types: each per-module
 * source carries its OWN `@file:JsModule` header, which is a per-FILE
 * annotation, so a set of them is exactly one compilation of several files.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
internal fun jsCompileCheckAll(
    sources: List<Pair<String, String>>,
    stdlib: Path,
    moduleKind: String = "commonjs",
): JsCompileCheck {
    val work = Files.createTempDirectory("xtsc-externals-js-gate-all")
    try {
        val files = sources.map { (name, source) ->
            val file = work.resolve("$name.kt")
            Files.writeString(file, source)
            file.toString()
        }
        val messages = RecordingJsMessageCollector()
        val arguments = K2JSCompilerArguments().apply {
            freeArgs = files
            libraries = stdlib.toString()
            outputDir = work.resolve("out").toString()
            moduleName = "xtsc-externals-js-gate"
            irProduceKlibDir = true
            this.moduleKind = moduleKind
        }
        K2JSCompiler().exec(messages, Services.EMPTY, arguments)
        return JsCompileCheck(!messages.hasErrors(), messages.errors, messages.warnings)
    } finally {
        work.deleteRecursively()
    }
}

/**
 * Compiles [source] as one Kotlin/JS file against [stdlib]. Errors are the
 * gate; warnings are recorded so a caller can print them, never gated on.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
internal fun jsCompileCheck(
    source: String,
    stdlib: Path,
    moduleKind: String = "commonjs",
    extensionFunctionsInExternals: Boolean = false,
): JsCompileCheck {
    val work = Files.createTempDirectory("xtsc-externals-js-gate")
    try {
        val sourceFile = work.resolve("Externals.kt")
        Files.writeString(sourceFile, source)
        val messages = RecordingJsMessageCollector()
        val arguments = K2JSCompilerArguments().apply {
            freeArgs = listOf(sourceFile.toString())
            libraries = stdlib.toString()
            outputDir = work.resolve("out").toString()
            moduleName = "xtsc-externals-js-gate"
            irProduceKlibDir = true
            this.moduleKind = moduleKind
            this.extensionFunctionsInExternals = extensionFunctionsInExternals
        }
        K2JSCompiler().exec(messages, Services.EMPTY, arguments)
        return JsCompileCheck(!messages.hasErrors(), messages.errors, messages.warnings)
    } finally {
        work.deleteRecursively()
    }
}
