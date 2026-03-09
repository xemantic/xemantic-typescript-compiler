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
 * Formats a [CompilationResult] as a TypeScript baseline string for comparison
 * against official test suite reference files.
 */
fun CompilationResult.toBaseline(): String {
    if (jsOutputs.isEmpty()) {
        return if (!isMultiFile) {
            formatSourceOnlyBaseline(fileName, sourceEchoes.first().second)
        } else {
            formatMultiFileBaseline(fileName, sourceEchoes, emptyList())
        }
    }
    if (!isMultiFile) {
        return formatBaseline(
            fileName,
            sourceEchoes.first().second,
            jsOutputs.first().second,
            options.sourceMap, options.newLine, options.jsx, options.mapRoot, options.outFile
        )
    }
    return formatMultiFileBaseline(fileName, sourceEchoes, jsOutputs, options.sourceMap)
}

/**
 * Formats compiler output in the TypeScript baseline format used by the official test suite.
 *
 * The baseline format uses mixed line endings:
 * - Section headers (`//// [...]`) use CRLF
 * - Echoed source preserves original LF endings
 * - JavaScript output uses CRLF
 */
private fun TextBuilder.sourceEcho(fileName: String, cleanedSource: String) {
    val baseName = fileName.substringAfterLast('/')
    +"//// [tests/cases/compiler/"
    +baseName
    +"] ////\r\n"
    +"\r\n"
    +"//// ["
    +baseName
    +"]\r\n"
    +cleanedSource.replace("\r", "")
    +"\r\n"
}

/**
 * Formats a source-only baseline (no JS output) for @emitDeclarationOnly tests.
 */
fun formatSourceOnlyBaseline(
    fileName: String,
    cleanedSource: String,
): String = text {
    sourceEcho(fileName, cleanedSource)
}

fun formatBaseline(
    fileName: String,
    cleanedSource: String,
    javascript: String,
    sourceMap: Boolean = false,
    newLine: String? = null,
    jsx: String? = null,
    mapRoot: String? = null,
    outFile: String? = null,
): String = text {
    val baseName = fileName.substringAfterLast('/')
    val tsxExtension = if (jsx?.lowercase() == "preserve") ".jsx" else ".js"
    val jsName = outFile?.substringAfterLast('/')
        ?: baseName
            .replace(".tsx", tsxExtension)
            .replace(".mts", ".mjs")
            .replace(".cts", ".cjs")
            .replace(".ts", ".js")
    val useLF = newLine?.lowercase() == "lf"

    sourceEcho(fileName, cleanedSource)
    +"\r\n"
    +"//// ["
    +jsName
    +"]\r\n"
    +if (useLF) toLF(javascript) else toCRLF(javascript)
    +if (useLF) "\n" else "\r\n"
    if (sourceMap) {
        val mapPrefix = if (mapRoot != null) "${mapRoot.trimEnd('/')}/" else ""
        +"//# sourceMappingURL=$mapPrefix${percentEncodeSourceMapUrl(jsName)}.map"
    }
}

/**
 * Formats multi-file compiler output in the TypeScript baseline format.
 */
fun formatMultiFileBaseline(
    testFileName: String,
    sourceEchoes: List<Pair<String, String>>,
    jsOutputs: List<Pair<String, String>>,
    sourceMap: Boolean = false,
): String = text {
    val baseName = testFileName.substringAfterLast('/')

    +"//// [tests/cases/compiler/"
    +baseName
    +"] ////\r\n"
    +"\r\n"

    for ((fileName, content) in sourceEchoes) {
        val echoBaseName = fileName.substringAfterLast('/')
        +"//// ["
        +echoBaseName
        +"]\r\n"
        +content.replace("\r", "")
        +"\r\n"
    }

    +"\r\n"

    for ((index, entry) in jsOutputs.withIndex()) {
        val (jsName, javascript) = entry
        +"//// ["
        +jsName
        +"]\r\n"
        val converted = toCRLF(javascript)
        +converted
        if (converted.isNotEmpty()) {
            +"\r\n"
        }
        val isJsOutput = jsName.endsWith(".js") || jsName.endsWith(".jsx") ||
                jsName.endsWith(".mjs") || jsName.endsWith(".cjs")
        if (sourceMap && isJsOutput) {
            +"//# sourceMappingURL=${percentEncodeSourceMapUrl(jsName.substringAfterLast('/'))}.map"
            if (index < jsOutputs.size - 1) {
                +"\r\n"
            }
        }
    }
}

/**
 * Percent-encodes non-ASCII characters and spaces in a source map URL path,
 * matching TypeScript's URL encoding behavior for source map comments.
 */
private fun percentEncodeSourceMapUrl(path: String): String = text {
    for (ch in path) {
        if (ch.code > 127 || ch == ' ' || ch == '[' || ch == ']') {
            val bytes = ch.toString().encodeToByteArray()
            for (b in bytes) {
                +"%"
                +"${((b.toInt() and 0xFF) shr 4).digitToChar(16).uppercaseChar()}"
                +"${(b.toInt() and 0x0F).digitToChar(16).uppercaseChar()}"
            }
        } else {
            +"$ch"
        }
    }
}

private fun toCRLF(text: String): String {
    // Convert newlines to CRLF, but preserve LF inside template literals (backtick strings).
    // Must track comment context so backticks inside comments don't toggle template mode.
    val normalized = text.replace("\r\n", "\n")
    val sb = StringBuilder(normalized.length + normalized.length / 10)
    var inTemplate = false
    var inLineComment = false
    var inBlockComment = false
    var i = 0
    while (i < normalized.length) {
        when (val ch = normalized[i]) {
            // Track line comments (// ...)
            '/' if !inTemplate && !inBlockComment && !inLineComment &&
                    i + 1 < normalized.length && normalized[i + 1] == '/' -> {
                inLineComment = true
                sb.append("//")
                i += 2
                continue
            }
            // Track block comments (/* ... */)
            '/' if !inTemplate && !inBlockComment && !inLineComment &&
                    i + 1 < normalized.length && normalized[i + 1] == '*' -> {
                inBlockComment = true
                sb.append("/*")
                i += 2
                continue
            }
            // End block comment
            '*' if inBlockComment && i + 1 < normalized.length && normalized[i + 1] == '/' -> {
                inBlockComment = false
                sb.append("*/")
                i += 2
                continue
            }
            // Backtick toggles template only outside comments
            '`' if !inLineComment && !inBlockComment -> {
                inTemplate = !inTemplate
                sb.append(ch)
            }

            '\\' if inTemplate && i + 1 < normalized.length -> {
                // Skip escaped characters inside templates
                sb.append(ch)
                sb.append(normalized[i + 1])
                i++
            }

            '\n' -> {
                inLineComment = false
                if (inTemplate) {
                    sb.append(ch)
                } else {
                    sb.append("\r\n")
                }
            }

            else -> sb.append(ch)
        }
        i++
    }
    return sb.toString()
}

private fun toLF(text: String): String {
    return text.replace("\r\n", "\n").replace("\r", "\n")
}