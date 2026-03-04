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
fun formatBaseline(
    fileName: String,
    cleanedSource: String,
    javascript: String,
): String {
    val baseName = fileName.substringAfterLast('/')
    val jsName = baseName.replace(".ts", ".js").replace(".tsx", ".jsx")

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

    // JS output with CRLF endings
    sb.append(toCRLF(javascript))

    // Trailing CRLF
    sb.append("\r\n")

    return sb.toString()
}

/**
 * Formats multi-file compiler output in the TypeScript baseline format.
 */
fun formatMultiFileBaseline(
    testFileName: String,
    sourceEchoes: List<Pair<String, String>>,
    jsOutputs: List<Pair<String, String>>,
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
    for ((jsName, javascript) in jsOutputs) {
        // Use just the filename (basename), not the full subdirectory path
        val baseJsName = jsName.substringAfterLast('/')
        sb.append("//// [")
        sb.append(baseJsName)
        sb.append("]\r\n")
        sb.append(toCRLF(javascript))
        sb.append("\r\n")
    }

    return sb.toString()
}

private fun toCRLF(text: String): String {
    // First normalize to LF, then convert to CRLF
    return text.replace("\r\n", "\n").replace("\n", "\r\n")
}
