/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.sameAs
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Root of the sparse-cloned TypeScript repository (relative to the test working directory). */
internal const val typeScriptRepoDir = "typescript-repo"

/** Directory containing the official TypeScript compiler test cases. */
internal const val typeScriptCasesDir = "$typeScriptRepoDir/tests/cases/compiler"

/** Directory containing the TypeScript baseline reference files. */
internal const val typeScriptBaselineDir = "$typeScriptRepoDir/tests/baselines/reference"

/**
 * Reads the full text content of this [Path] using the system filesystem.
 * Detects UTF-16 BE/LE BOMs and decodes accordingly; otherwise reads as UTF-8.
 */
internal fun Path.readText(): String {
    val bytes = SystemFileSystem.source(this).buffered().readByteArray()
    return when {
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
            // UTF-16 BE BOM: high byte first
            buildString {
                var i = 2
                while (i + 1 < bytes.size) {
                    val hi = bytes[i].toInt() and 0xFF
                    val lo = bytes[i + 1].toInt() and 0xFF
                    val ch = (hi shl 8) or lo
                    if (ch != 0) append(ch.toChar())
                    i += 2
                }
            }
        }
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
            // UTF-16 LE BOM: low byte first
            buildString {
                var i = 2
                while (i + 1 < bytes.size) {
                    val lo = bytes[i].toInt() and 0xFF
                    val hi = bytes[i + 1].toInt() and 0xFF
                    val ch = (hi shl 8) or lo
                    if (ch != 0) append(ch.toChar())
                    i += 2
                }
            }
        }
        else -> bytes.decodeToString()
    }
}

/**
 * Asserts that this [Path]'s text content is the same as [expected] [Path]'s content.
 *
 * On failure, throws an [AssertionError] with a unified diff of the two file contents,
 * matching the output format of [com.xemantic.kotlin.test.sameAs].
 */
infix fun Path.sameAs(expected: Path) {
    readText() sameAs expected.readText()
}

/**
 * Asserts that this string is the same as the text content of the [expected] [Path],
 * after stripping any `.d.ts` / `.d.mts` / `.d.cts` sections from the expected baseline.
 * These declaration sections require a type checker to generate, which is out of scope.
 *
 * On failure, throws an [AssertionError] with a unified diff, matching the output format
 * of [com.xemantic.kotlin.test.sameAs]. Useful for comparing compiled output strings
 * directly against baseline files.
 */
infix fun String?.sameAs(expected: Path) {
    val stripped = stripDtsSection(expected.readText())
    // Normalize line endings to CRLF and trailing whitespace so dts stripping and
    // baseline files with mixed LF/CRLF don't cause spurious line-ending failures.
    fun String.normalizeCrlf() = replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n").trimEnd() + "\r\n"
    val normalizedExpected = stripped.normalizeCrlf()
    val normalizedActual = (this ?: "").normalizeCrlf()
    normalizedActual sameAs normalizedExpected
}

/**
 * Asserts that this [CompilationResult]'s error diagnostics match the `.errors.txt` baseline
 * at the given [baselinePath].
 *
 * - If no baseline file exists AND the compiler produced no diagnostics → test passes.
 * - If no baseline file exists BUT diagnostics were produced → test fails.
 * - If baseline file exists BUT no diagnostics were produced → test fails.
 * - If both exist → formatted output is compared against baseline content.
 */
fun CompilationResult.errorsMatchBaseline(baselinePath: Path) {
    val errorBaseline = toErrorBaseline()
    val baselineExists = SystemFileSystem.metadataOrNull(baselinePath) != null

    when {
        !baselineExists && errorBaseline == null -> {
            // Both agree: no errors — pass
        }
        !baselineExists && errorBaseline != null -> {
            throw AssertionError(
                "Unexpected diagnostics produced (no baseline file expected):\n$errorBaseline"
            )
        }
        baselineExists && errorBaseline == null -> {
            throw AssertionError(
                "Expected diagnostics from baseline ${baselinePath} but none produced"
            )
        }
        else -> {
            // Compare formatted output against baseline
            val expected = baselinePath.readText()
            fun String.normalize() =
                replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n")
                    .trimEnd() + "\r\n"
            errorBaseline!!.normalize() sameAs expected.normalize()
        }
    }
}

/**
 * Strips `.d.ts` / `.d.mts` / `.d.cts` sections and noCheck diff sections from a baseline string.
 * A dts section starts with a line like `//// [filename.d.ts]` or `//// [filename.d.mts]`
 * and extends to the end of the file (or to the next non-dts `//// [...]` section).
 * A noCheck diff section starts with `!!!! File ... differs from original emit in noCheck emit`
 * and extends to the end of the file.
 */
private fun stripDtsSection(baseline: String): String {
    val lines = baseline.split("\n")
    val result = mutableListOf<String>()
    var inDts = false
    var seenJsOutput = false
    // Track source file basenames seen so far to distinguish input from output sections
    val sourceBaseNamesSoFar = mutableSetOf<String>()
    for (line in lines) {
        val trimmed = line.trimEnd()
        // Strip noCheck diff section: everything from "!!!! File ... differs ..." onwards
        if (trimmed.startsWith("!!!!")) {
            break
        }
        if (trimmed.startsWith("//// [") && trimmed.endsWith("]")) {
            val fileName = trimmed.removePrefix("//// [").removeSuffix("]")
            val baseName = fileName.substringAfterLast('/')
            val isDtsFile = baseName.endsWith(".d.ts") || baseName.endsWith(".d.mts") || baseName.endsWith(".d.cts")
            // Track source files as we encounter them (.ts, .tsx, .mts, .cts, .js, .jsx, .mjs, .cjs)
            when {
                baseName.endsWith(".ts") && !baseName.endsWith(".d.ts") ->
                    sourceBaseNamesSoFar.add(baseName.removeSuffix(".ts"))
                baseName.endsWith(".tsx") -> sourceBaseNamesSoFar.add(baseName.removeSuffix(".tsx"))
                baseName.endsWith(".mts") && !baseName.endsWith(".d.mts") ->
                    sourceBaseNamesSoFar.add(baseName.removeSuffix(".mts"))
                baseName.endsWith(".cts") && !baseName.endsWith(".d.cts") ->
                    sourceBaseNamesSoFar.add(baseName.removeSuffix(".cts"))
                baseName.endsWith(".js") -> sourceBaseNamesSoFar.add(baseName.removeSuffix(".js"))
                baseName.endsWith(".jsx") -> sourceBaseNamesSoFar.add(baseName.removeSuffix(".jsx"))
                baseName.endsWith(".mjs") -> sourceBaseNamesSoFar.add(baseName.removeSuffix(".mjs"))
                baseName.endsWith(".cjs") -> sourceBaseNamesSoFar.add(baseName.removeSuffix(".cjs"))
            }
            // A .js file is output only if we've already seen a .ts file with the same basename
            val isOutputJs = (baseName.endsWith(".js") || baseName.endsWith(".jsx") ||
                    baseName.endsWith(".mjs") || baseName.endsWith(".cjs")) &&
                    baseName.substringBeforeLast('.') in sourceBaseNamesSoFar
            if (isOutputJs) seenJsOutput = true
            // Strip .d.ts sections that are compiler-generated output:
            // either after JS output, or matching a previously seen source file basename
            val dtsBaseName = when {
                baseName.endsWith(".d.ts") -> baseName.removeSuffix(".d.ts")
                baseName.endsWith(".d.mts") -> baseName.removeSuffix(".d.mts")
                baseName.endsWith(".d.cts") -> baseName.removeSuffix(".d.cts")
                else -> null
            }
            val isDtsOutput = isDtsFile && (seenJsOutput ||
                    (dtsBaseName != null && dtsBaseName in sourceBaseNamesSoFar))
            inDts = isDtsOutput
        }
        if (!inDts) {
            result.add(line)
        }
    }
    // Remove trailing blank lines that were between JS output and dts section,
    // but keep exactly the amount of trailing whitespace our emitter produces.
    // The standard baseline format has \r\n at the end. After stripping, there
    // may be extra blank lines. Remove all but one trailing blank line.
    while (result.size > 1 && result.last().trimEnd().isEmpty() && result[result.size - 2].trimEnd().isEmpty()) {
        result.removeLast()
    }
    return result.joinToString("\n")
}
