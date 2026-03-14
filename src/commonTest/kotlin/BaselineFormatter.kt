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
        // Handle both Unix '/' and Windows '\' path separators
        val echoBaseName = fileName.substringAfterLast('/').substringAfterLast('\\')
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
    // Convert newlines to CRLF, but preserve LF inside string literals (single/double/backtick).
    // Must track comment context so string delimiters inside comments don't toggle string mode.
    val normalized = text.replace("\r\n", "\n")
    val sb = StringBuilder(normalized.length + normalized.length / 10)
    var inTemplate = false
    var inSingleQuote = false
    var inDoubleQuote = false
    var inLineComment = false
    var inBlockComment = false
    var i = 0
    while (i < normalized.length) {
        val inString = inTemplate || inSingleQuote || inDoubleQuote
        when (val ch = normalized[i]) {
            // Track line comments (// ...) — only outside strings
            '/' if !inString && !inBlockComment && !inLineComment &&
                    i + 1 < normalized.length && normalized[i + 1] == '/' -> {
                inLineComment = true
                sb.append("//")
                i += 2
                continue
            }
            // Track block comments (/* ... */) — only outside strings
            '/' if !inString && !inBlockComment && !inLineComment &&
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
            // Backtick toggles template only outside comments and other strings
            '`' if !inLineComment && !inBlockComment && !inSingleQuote && !inDoubleQuote -> {
                inTemplate = !inTemplate
                sb.append(ch)
            }
            // Single-quote string toggle — only outside comments and other strings
            '\'' if !inLineComment && !inBlockComment && !inTemplate && !inDoubleQuote -> {
                inSingleQuote = !inSingleQuote
                sb.append(ch)
            }
            // Double-quote string toggle — only outside comments and other strings
            '"' if !inLineComment && !inBlockComment && !inTemplate && !inSingleQuote -> {
                inDoubleQuote = !inDoubleQuote
                sb.append(ch)
            }

            '\\' if inString && i + 1 < normalized.length -> {
                // Skip escaped characters inside strings (so \" or \' doesn't close the string)
                sb.append(ch)
                sb.append(normalized[i + 1])
                i++
            }

            '\n' -> {
                inLineComment = false
                if (inTemplate) {
                    // Template literals can span lines — preserve LF as-is
                    sb.append(ch)
                } else {
                    // Single/double-quoted strings cannot span lines.
                    // A raw \n terminates them (syntax error). Reset string tracking.
                    inSingleQuote = false
                    inDoubleQuote = false
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

// --- Error Baseline Formatter ---

/**
 * Formats a [CompilationResult] as a `.errors.txt` baseline string.
 * Returns `null` if there are no diagnostics (matching TypeScript's behavior:
 * no baseline file is produced for error-free compilations).
 */
fun CompilationResult.toErrorBaseline(): String? {
    if (diagnostics.isEmpty()) return null
    // Apply TypeScript test harness file ordering for error baselines:
    // If the last file has require() or reference path, or noImplicitReferences is set,
    // move the last file to the front (it's the "root" file in the harness).
    val orderedEchoes = if (isMultiFile && sourceEchoes.size > 1) {
        val lastContent = sourceEchoes.last().second
        val shouldReorder = options.noImplicitReferences
            || "require(" in lastContent
            || Regex("reference\\s+path").containsMatchIn(lastContent)
        if (shouldReorder) {
            listOf(sourceEchoes.last()) + sourceEchoes.dropLast(1)
        } else {
            sourceEchoes
        }
    } else {
        sourceEchoes
    }
    return formatErrorBaseline(diagnostics, orderedEchoes)
}

/**
 * Formats diagnostics and source files into the TypeScript `.errors.txt` baseline format.
 *
 * The format has three parts:
 * 1. **Diagnostic summary** — one line per diagnostic
 * 2. **Global error markers** — `!!! category TScode: message` for diagnostics with no file
 * 3. **Per-file annotated source** — source lines with squiggles and error annotations
 */
fun formatErrorBaseline(
    diagnostics: List<Diagnostic>,
    sourceFiles: List<Pair<String, String>>,
): String {
    val sorted = diagnostics.sortedWith(diagnosticComparator)

    return text {
        // Part 1: Diagnostic summary (one line per diagnostic)
        for (diag in sorted) {
            if (diag.fileName != null && diag.line != null && diag.character != null) {
                +diag.fileName!!
                +"("
                +diag.line.toString()
                +","
                +diag.character.toString()
                +"): "
            }
            +diag.category.name.lowercase()
            +" TS"
            +diag.code.toString()
            +": "
            +diag.message
            +"\r\n"
            // Message chain continuation lines (e.g. migration URL for baseUrl)
            for (chain in diag.messageChain) {
                +chain
                +"\r\n"
            }
        }
        +"\r\n"
        +"\r\n"

        // Part 2: Global error markers (diagnostics with no file)
        val globalDiags = sorted.filter { it.fileName == null }
        for (diag in globalDiags) {
            +"!!! "
            +diag.category.name.lowercase()
            +" TS"
            +diag.code.toString()
            +": "
            +diag.message
            +"\r\n"
            // Message chain continuation lines
            for (chain in diag.messageChain) {
                +"!!! "
                +diag.category.name.lowercase()
                +" TS"
                +diag.code.toString()
                +": "
                +chain
                +"\r\n"
            }
        }

        // Part 3: Per-file annotated source
        for ((fileName, content) in sourceFiles) {
            val fileDiags = sorted.filter { it.fileName == fileName || it.fileName == fileName.substringAfterLast('/') }
            val errorCount = fileDiags.size

            +"==== "
            +fileName
            +" ("
            +errorCount.toString()
            +" errors) ===="
            +"\r\n"

            val sourceLines = content.replace("\r\n", "\n").replace("\r", "\n").split('\n')
            for ((lineIdx, lineContent) in sourceLines.withIndex()) {
                val lineNum = lineIdx + 1 // 1-based

                +"    "
                +lineContent
                +"\r\n"

                // Find diagnostics starting on this line, sorted by column then code
                val lineDiags = fileDiags
                    .filter { it.line == lineNum }
                    .sortedWith(compareBy({ it.character ?: 0 }, { it.code }))

                for (diag in lineDiags) {
                    val col = (diag.character ?: 1) - 1 // convert to 0-based
                    val len = diag.length ?: 1

                    // Squiggle line
                    +"    "
                    +" ".repeat(col)
                    +"~".repeat(len)
                    +"\r\n"

                    // Error annotation
                    +"!!! "
                    +diag.category.name.lowercase()
                    +" TS"
                    +diag.code.toString()
                    +": "
                    +diag.message
                    +"\r\n"

                    // Related information
                    for (related in diag.relatedInformation) {
                        +"!!! related TS"
                        +related.code.toString()
                        +" "
                        if (related.fileName != null) {
                            +related.fileName!!
                            +":"
                            +(related.line ?: 0).toString()
                            +":"
                            +(related.character ?: 0).toString()
                        }
                        +": "
                        +related.message
                        +"\r\n"
                    }
                }
            }
        }
    }
}

private val diagnosticComparator = Comparator<Diagnostic> { a, b ->
    // null fileName sorts before non-null (global diagnostics first)
    val fileA = a.fileName
    val fileB = b.fileName
    when {
        fileA == null && fileB != null -> return@Comparator -1
        fileA != null && fileB == null -> return@Comparator 1
        fileA != null && fileB != null -> {
            val c = fileA.compareTo(fileB)
            if (c != 0) return@Comparator c
        }
    }
    compareValues(a.start ?: 0, b.start ?: 0).let { if (it != 0) return@Comparator it }
    compareValues(a.length ?: 0, b.length ?: 0).let { if (it != 0) return@Comparator it }
    a.code.compareTo(b.code).let { if (it != 0) return@Comparator it }
    a.message.compareTo(b.message)
}