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

/**
 * Formats compiler output in the TypeScript baseline format used by the official test suite.
 *
 * The baseline format uses mixed line endings:
 * - Section headers (`//// [...]`) use CRLF
 * - Echoed source preserves original LF endings
 * - JavaScript output uses CRLF
 */
/**
 * Formats a source-only baseline (no JS output) for @emitDeclarationOnly tests.
 */
fun formatSourceOnlyBaseline(
    fileName: String,
    cleanedSource: String,
): String {
    val baseName = fileName.substringAfterLast('/')
    val sb = StringBuilder()
    sb.append("//// [tests/cases/compiler/")
    sb.append(baseName)
    sb.append("] ////\r\n")
    sb.append("\r\n")
    sb.append("//// [")
    sb.append(baseName)
    sb.append("]\r\n")
    sb.append(cleanedSource.replace("\r", ""))
    sb.append("\r\n")
    return sb.toString()
}

fun formatBaseline(
    fileName: String,
    cleanedSource: String,
    javascript: String,
    sourceMap: Boolean = false,
    newLine: String? = null,
    jsx: String? = null,
    mapRoot: String? = null,
): String {
    val baseName = fileName.substringAfterLast('/')
    val tsxExtension = if (jsx?.lowercase() == "preserve") ".jsx" else ".js"
    val jsName = baseName.replace(".tsx", tsxExtension).replace(".mts", ".mjs").replace(".cts", ".cjs").replace(".ts", ".js")

    val sb = StringBuilder()

    // File path header
    sb.append("//// [tests/cases/compiler/")
    sb.append(baseName)
    sb.append("] ////\r\n")

    // Blank line
    sb.append("\r\n")

    // Source echo header
    sb.append("//// [")
    sb.append(baseName)
    sb.append("]\r\n")

    // Echoed source with LF-only endings (strip any \r from original)
    sb.append(cleanedSource.replace("\r", ""))

    // Separator between source and JS
    sb.append("\r\n")
    sb.append("\r\n")

    // JS output header
    sb.append("//// [")
    sb.append(jsName)
    sb.append("]\r\n")

    // JS output: use LF if @newline: LF is specified, otherwise CRLF
    val useLF = newLine?.lowercase() == "lf"
    sb.append(if (useLF) toLF(javascript) else toCRLF(javascript))

    // Trailing newline
    sb.append(if (useLF) "\n" else "\r\n")
    if (sourceMap) {
        val mapPrefix = if (mapRoot != null) "${mapRoot.trimEnd('/')}/" else ""
        sb.append("//# sourceMappingURL=$mapPrefix$jsName.map")
    }

    return sb.toString()
}

/**
 * Formats multi-file compiler output in the TypeScript baseline format.
 */
fun formatMultiFileBaseline(
    testFileName: String,
    sourceEchoes: List<Pair<String, String>>,
    jsOutputs: List<Pair<String, String>>,
    sourceMap: Boolean = false,
): String {
    val baseName = testFileName.substringAfterLast('/')

    val sb = StringBuilder()

    // File path header
    sb.append("//// [tests/cases/compiler/")
    sb.append(baseName)
    sb.append("] ////\r\n")

    // Blank line
    sb.append("\r\n")

    // Source echo sections
    for ((fileName, content) in sourceEchoes) {
        // Use just the filename (basename), not the full subdirectory path
        val baseName = fileName.substringAfterLast('/')
        sb.append("//// [")
        sb.append(baseName)
        sb.append("]\r\n")
        sb.append(content.replace("\r", ""))
        sb.append("\r\n")
    }

    // Blank line before JS output
    sb.append("\r\n")

    // JS output sections
    for ((index, entry) in jsOutputs.withIndex()) {
        val (jsName, javascript) = entry
        sb.append("//// [")
        sb.append(jsName)
        sb.append("]\r\n")
        sb.append(toCRLF(javascript))
        sb.append("\r\n")
        if (sourceMap) {
            sb.append("//# sourceMappingURL=${jsName.substringAfterLast('/')}.map")
            // Add CRLF separator between JS sections, but not after the last one
            if (index < jsOutputs.size - 1) {
                sb.append("\r\n")
            }
        }
    }

    return sb.toString()
}

private fun toCRLF(text: String): String {
    // First normalize to LF, then convert to CRLF
    return text.replace("\r\n", "\n").replace("\n", "\r\n")
}

private fun toLF(text: String): String {
    return text.replace("\r\n", "\n").replace("\r", "\n")
}
