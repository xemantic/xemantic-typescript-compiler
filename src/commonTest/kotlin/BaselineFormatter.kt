/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
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
            options.sourceMap, options.newLine, options.jsx, options.mapRoot, options.outFile,
            options.inlineSourceMap, options.sourceRoot,
        )
    }
    return formatMultiFileBaseline(
        fileName, sourceEchoes, jsOutputs, options.sourceMap,
        options.inlineSourceMap, options.sourceRoot, options.outFile,
        options.inlineSources, options.mapRoot, options.outDir,
    )
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
    inlineSourceMap: Boolean = false,
    sourceRoot: String? = null,
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
    if (inlineSourceMap) {
        // Inline source map takes precedence over file-based source map
        val effectiveSourceRoot = sourceRoot?.let { "$it/" } ?: ""
        // When mapRoot is a relative path, source paths are adjusted relative to the map directory
        val isRelativeMapRoot = mapRoot != null && !mapRoot.contains("://")
        val sourceFileName = if (isRelativeMapRoot) "../$baseName" else baseName
        +generateInlineSourceMapComment(
            sourceTexts = listOf(cleanedSource),
            sourceFileNames = listOf(sourceFileName),
            jsFileName = jsName,
            jsOutput = javascript,
            sourceRoot = effectiveSourceRoot,
        )
    } else if (sourceMap) {
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
    inlineSourceMap: Boolean = false,
    sourceRoot: String? = null,
    outFile: String? = null,
    inlineSources: Boolean = false,
    mapRoot: String? = null,
    outDir: String? = null,
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
        if (inlineSourceMap && isJsOutput) {
            val effectiveSourceRoot = sourceRoot?.let { "$it/" } ?: ""
            val tsSourceEchoes = sourceEchoes.filter {
                val fn = it.first
                fn.endsWith(".ts") || fn.endsWith(".tsx") || fn.endsWith(".mts") || fn.endsWith(".cts")
            }
            +generateInlineSourceMapComment(
                sourceTexts = tsSourceEchoes.map { it.second },
                sourceFileNames = tsSourceEchoes.map { it.first.substringAfterLast('/') },
                jsFileName = jsName.substringAfterLast('/'),
                jsOutput = javascript,
                sourceRoot = effectiveSourceRoot,
                includeSourcesContent = inlineSources,
            )
            if (index < jsOutputs.size - 1) {
                +"\r\n"
            }
        } else if (sourceMap && isJsOutput) {
            val mapUrl = computeMapUrl(jsName, mapRoot, outDir)
            +"//# sourceMappingURL=${percentEncodeSourceMapUrl(mapUrl)}"
            if (index < jsOutputs.size - 1) {
                +"\r\n"
            }
        }
    }
}

/**
 * Computes the source map URL for a JS output file.
 *
 * When mapRoot is a relative path and outDir is set, TypeScript places map files under
 * mapRoot (relative to the tsconfig directory), and the URL in the JS file is relative
 * from the JS file's directory to the map file.
 */
private fun computeMapUrl(jsName: String, mapRoot: String?, outDir: String?): String {
    val jsBaseName = jsName.substringAfterLast('/')
    val mapFileName = "$jsBaseName.map"
    if (mapRoot == null) {
        return mapFileName
    }
    // Absolute mapRoot URL — prefix directly
    if (mapRoot.contains("://")) {
        return "${mapRoot.trimEnd('/')}/$mapFileName"
    }
    if (outDir == null) {
        return "${mapRoot.trimEnd('/')}/$mapFileName"
    }
    // Relative mapRoot: TypeScript resolves mapRoot relative to tsconfig dir.
    // The JS file is inside outDir (also resolved relative to tsconfig dir).
    // Strategy: find the tsconfig root by stripping the outDir suffix from the JS file's dir,
    // then compute the relative path from the JS dir back to tsconfig root + mapRoot + subpath.
    val jsDir = if (jsName.contains('/')) jsName.substringBeforeLast('/') else ""
    val outDirNorm = outDir.trimEnd('/')

    // Find the tsconfig root: jsDir should end with outDir (plus any subdir inside outDir)
    // Split jsDir by the outDir suffix to find the root and the subpath within outDir.
    val outDirSuffix = "/$outDirNorm"
    val outDirIdx = jsDir.indexOf(outDirSuffix)
    val subDirInOutDir = if (outDirIdx >= 0) {
        jsDir.substring(outDirIdx + outDirSuffix.length).trimStart('/') // e.g. "src" or ""
    } else {
        // Fallback: can't find outDir in jsDir, use simple basename
        return mapFileName
    }

    // How many levels up from jsDir to tsconfigRoot?
    // jsDir = tsconfigRoot/outDir/subDirInOutDir
    val outDirParts = outDirNorm.split('/').filter { it.isNotEmpty() }
    val subDirParts = subDirInOutDir.split('/').filter { it.isNotEmpty() }
    val upCount = outDirParts.size + subDirParts.size
    val prefix = "../".repeat(upCount)

    // Map file location: tsconfigRoot/mapRoot/subDirInOutDir/mapFileName
    val mapRootNorm = mapRoot.trimEnd('/')
    return if (subDirInOutDir.isEmpty()) {
        "${prefix}${mapRootNorm}/$mapFileName"
    } else {
        "${prefix}${mapRootNorm}/${subDirInOutDir}/$mapFileName"
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
    // Use allSourceFiles (includes tsconfig.json) if available, otherwise fall back to sourceEchoes
    val baseFiles = if (allSourceFiles.isNotEmpty()) allSourceFiles else sourceEchoes
    // Apply TypeScript test harness file ordering for error baselines.
    // Two paths mirror the harness logic:
    // 1. When tsconfig.json is present: tsconfig first, then files resolved by tsconfig
    //    (non-node_modules .ts/.d.ts under tsconfig directory, in original @filename order),
    //    then remaining files in original @filename order.
    // 2. Otherwise: if the last file has require()/reference path/noImplicitReferences,
    //    move it to front; else preserve @filename order.
    val orderedEchoes = if (isMultiFile && baseFiles.size > 1) {
        val tsconfigEntry = baseFiles.firstOrNull { it.first.endsWith("/tsconfig.json") || it.first == "tsconfig.json" }
        if (tsconfigEntry != null) {
            val tsconfigDir = tsconfigEntry.first.substringBeforeLast('/').let { if (it == tsconfigEntry.first) "" else "$it/" }
            val otherFiles = baseFiles.filter { it !== tsconfigEntry }
            // Root files: .ts/.d.ts (and .js/.jsx with allowJs) under the tsconfig directory,
            // not in node_modules — mirrors TypeScript's default include pattern
            val allowJs = options.allowJs
            val (rootFiles, remainingFiles) = otherFiles.partition { (name, _) ->
                val isIncludedExt = name.endsWith(".ts") || name.endsWith(".d.ts") ||
                    (allowJs && (name.endsWith(".js") || name.endsWith(".jsx")))
                isIncludedExt && name.startsWith(tsconfigDir) && "/node_modules/" !in name
            }
            listOf(tsconfigEntry) + rootFiles + remainingFiles
        } else {
            val lastContent = baseFiles.last().second
            val shouldReorder = options.noImplicitReferences
                || "require(" in lastContent
                || Regex("reference\\s+path").containsMatchIn(lastContent)
            if (shouldReorder) {
                listOf(baseFiles.last()) + baseFiles.dropLast(1)
            } else {
                baseFiles
            }
        }
    } else {
        baseFiles
    }
    return formatErrorBaseline(diagnostics, orderedEchoes, pretty = options.pretty)
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
    pretty: Boolean = false,
): String {
    val sorted = diagnostics.sortedWith(diagnosticComparator)
    // Build source line lookup for pretty formatting
    val sourceLinesByFile = if (pretty) {
        sourceFiles.associate { (name, content) -> name to content.split('\n') }
    } else emptyMap()

    return text {
        // Pretty header section (ANSI-colored diagnostics with source context)
        if (pretty) {
            for (diag in sorted) {
                if (diag.fileName != null && diag.line != null && diag.character != null) {
                    // Colored: [96mfile[0m:[93mline[0m:[93mcol[0m - [91merror[0m[90m TScode: [0mmessage
                    +"\u001b[96m${diag.fileName}\u001b[0m:\u001b[93m${diag.line}\u001b[0m:\u001b[93m${diag.character}\u001b[0m"
                    +" - \u001b[91m${diag.category.name.lowercase()}\u001b[0m\u001b[90m TS${diag.code}: \u001b[0m${diag.message}"
                    +"\r\n"
                    // Message chain continuation in pretty section
                    for (chain in diag.messageChain) {
                        +chain
                        +"\r\n"
                    }
                    +"\r\n"
                    // Source context line with squiggle
                    val lines = sourceLinesByFile[diag.fileName]
                    if (lines != null && diag.line!! >= 1 && diag.line!! <= lines.size) {
                        val lineIdx = diag.line!! - 1
                        val sourceLine = lines[lineIdx].trimEnd('\r')
                        // Pretty format: replace tabs with spaces for display
                        val displayLine = sourceLine.replace("\t", " ")
                        +"\u001b[7m${diag.line}\u001b[0m $displayLine\r\n"
                        // Squiggle line: gutter uses reverse video for spaces
                        val gutterSpaces = " ".repeat(diag.line.toString().length)
                        val squiggleStart = diag.character!! - 1
                        val squiggleLen = diag.length ?: 0
                        val indent = " ".repeat(squiggleStart)
                        val squiggle = "~".repeat(squiggleLen)
                        +"\u001b[7m$gutterSpaces\u001b[0m \u001b[91m$indent$squiggle\u001b[0m\r\n"
                    }
                    // Related info in pretty section — blank line before related info block
                    if (diag.relatedInformation.isNotEmpty()) {
                        +"\r\n"
                    }
                    for (related in diag.relatedInformation) {
                        if (related.fileName != null && related.line != null && related.character != null) {
                            +"  \u001b[96m${related.fileName}\u001b[0m:\u001b[93m${related.line}\u001b[0m:\u001b[93m${related.character}\u001b[0m\r\n"
                            val relLines = sourceLinesByFile[related.fileName]
                            if (relLines != null && related.line!! >= 1 && related.line!! <= relLines.size) {
                                val relLine = relLines[related.line!! - 1].trimEnd('\r')
                                +"    \u001b[7m${related.line}\u001b[0m $relLine\r\n"
                                val relGutter = " ".repeat(related.line.toString().length)
                                val relStart = related.character!! - 1
                                val relLen = maxOf(1, related.length ?: 1)
                                val relIndent = buildString {
                                    for (i in 0 until minOf(relStart, relLine.length)) {
                                        append(if (relLine[i] == '\t') '\t' else ' ')
                                    }
                                }
                                +"    \u001b[7m$relGutter\u001b[0m \u001b[96m${relIndent}${"~".repeat(relLen)}\u001b[0m\r\n"
                            }
                            +"    ${related.message}\r\n"
                        }
                    }
                }
            }
            +"\r\n"
        }

        // Part 1: Diagnostic summary (one line per diagnostic)
        // When pretty is on, the pretty header replaces this section
        if (!pretty) {
        for (diag in sorted) {
            val df = diag.fileName
            if (df != null && diag.line != null && diag.character != null) {
                // TypeScript strips leading "./" from filenames in the diagnostic summary.
                // Source-echo headers ("==== ./foo.ts ====") keep the prefix.
                +df.removePrefix("./")
                +"("
                +diag.line.toString()
                +","
                +diag.character.toString()
                +"): "
            } else if (df != null && diag.line == null) {
                val base = df.substringAfterLast('/').substringAfterLast('\\')
                if (base.startsWith("lib.") && base.endsWith(".d.ts")) {
                    +df
                    +"(--,--): "
                }
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
        }
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
            val skipLines = mutableSetOf<Int>() // line indices already emitted by multi-line spans
            for ((lineIdx, lineContent) in sourceLines.withIndex()) {
                if (lineIdx in skipLines) continue
                val lineNum = lineIdx + 1 // 1-based

                +"    "
                +lineContent
                +"\r\n"

                // Find diagnostics starting on this line, sorted by column, then length (shorter first), then code
                val lineDiags = fileDiags
                    .filter { it.line == lineNum }
                    .sortedWith(compareBy({ it.character ?: 0 }, { it.length ?: 0 }, { it.code }))

                for (diag in lineDiags) {
                    val col = ((diag.character ?: 1) - 1).coerceAtLeast(0) // convert to 0-based
                    val len = diag.length ?: 1
                    // Track continuation lines covered by this diag's multi-line squiggle so we
                    // can emit any additional diagnostics that start on those lines without
                    // re-emitting their source line (the multi-line continuation already did).
                    val continuationLineIndices = mutableListOf<Int>()

                    if (len == 0) {
                        // Zero-length span — empty squiggle line (just indentation, no ~)
                        +"    "
                        +lineContent.take(col).map { if (it == '\t') '\t' else ' ' }.joinToString("")
                        +"\r\n"
                    } else {
                        // First squiggle line
                        val charsOnFirstLine = (lineContent.length - col).coerceAtLeast(1)
                        val firstLineLen = len.coerceAtMost(charsOnFirstLine)
                        +"    "
                        +lineContent.take(col).map { if (it == '\t') '\t' else ' ' }.joinToString("")
                        +"~".repeat(firstLineLen)
                        +"\r\n"
                        // Multi-line span continuation
                        var remaining = len - firstLineLen
                        var nextLineIdx = lineIdx + 1
                        while (remaining > 0 && nextLineIdx < sourceLines.size) {
                            // Account for the newline character(s) between lines
                            remaining-- // consume the \n between lines
                            if (remaining <= 0) break
                            val nextLine = sourceLines[nextLineIdx]
                            skipLines.add(nextLineIdx)
                            continuationLineIndices.add(nextLineIdx)
                            // Emit source line
                            +"    "
                            +nextLine
                            +"\r\n"
                            // Emit squiggles (cover entire line or remaining).
                            val squiggleLen = remaining.coerceAtMost(nextLine.length)
                            if (squiggleLen > 0) {
                                +"    "
                                +"~".repeat(squiggleLen)
                                +"\r\n"
                            } else if (nextLine.isEmpty()) {
                                // Blank lines within a multi-line span get an empty squiggle line —
                                // TypeScript emits a blank indent line even with no tildes.
                                +"    "
                                +"\r\n"
                            }
                            remaining -= squiggleLen
                            nextLineIdx++
                        }
                    }

                    // Error annotation
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

                    // Related information
                    for (related in diag.relatedInformation) {
                        +"!!! related TS"
                        +related.code.toString()
                        val relFile = related.fileName
                        if (relFile != null) {
                            // Space separates the code from the file location. A related info
                            // with NO file (e.g. TS1369 regex group suggestion) renders the
                            // code directly followed by `: message` (no space before colon).
                            +" "
                            // Mirror summary-line convention: strip leading "./".
                            +relFile.removePrefix("./")
                            +":"
                            val base = relFile.substringAfterLast('/').substringAfterLast('\\')
                            val isLib = base.startsWith("lib.") && base.endsWith(".d.ts")
                            if (isLib && related.line == null) {
                                +"--:--"
                            } else {
                                +(related.line ?: 0).toString()
                                +":"
                                +(related.character ?: 0).toString()
                            }
                        }
                        +": "
                        +related.message
                        +"\r\n"
                        // Related-info message chain continuation lines (already carry their
                        // own leading indentation, NO `!!!` prefix — matches tsc's nested
                        // DiagnosticMessageChain rendering, e.g. iteratorExtraParameters'
                        // TS2322 related under TS2488). Additive: no diagnostic set a related
                        // messageChain before B438e, so this cannot shift existing baselines.
                        for (relChain in related.messageChain) {
                            +relChain
                            +"\r\n"
                        }
                    }

                    // Emit additional diagnostics that START on this diag's continuation lines
                    // (those source lines were already emitted by the multi-line continuation, so
                    // emit only the squiggle + !!! annotation, not the source line again).
                    // Only handle single-line additional diagnostics — multi-line additionals would
                    // re-emit overlapping source and are rare in practice.
                    for (contLineIdx in continuationLineIndices) {
                        val contLineNum = contLineIdx + 1
                        val contLineContent = sourceLines[contLineIdx]
                        val moreDiags = fileDiags
                            .filter { it.line == contLineNum && it !== diag }
                            .sortedWith(compareBy({ it.character ?: 0 }, { it.length ?: 0 }, { it.code }))
                        for (moreDiag in moreDiags) {
                            val mCol = ((moreDiag.character ?: 1) - 1).coerceAtLeast(0)
                            val mLen = moreDiag.length ?: 1
                            if (mLen == 0) {
                                +"    "
                                +contLineContent.take(mCol).map { if (it == '\t') '\t' else ' ' }.joinToString("")
                                +"\r\n"
                            } else {
                                val mCharsOnLine = (contLineContent.length - mCol).coerceAtLeast(1)
                                val mFirstLineLen = mLen.coerceAtMost(mCharsOnLine)
                                +"    "
                                +contLineContent.take(mCol).map { if (it == '\t') '\t' else ' ' }.joinToString("")
                                +"~".repeat(mFirstLineLen)
                                +"\r\n"
                            }
                            +"!!! "
                            +moreDiag.category.name.lowercase()
                            +" TS"
                            +moreDiag.code.toString()
                            +": "
                            +moreDiag.message
                            +"\r\n"
                            for (chain in moreDiag.messageChain) {
                                +"!!! "
                                +moreDiag.category.name.lowercase()
                                +" TS"
                                +moreDiag.code.toString()
                                +": "
                                +chain
                                +"\r\n"
                            }
                        }
                    }
                }
            }
        }
        // Pretty footer: "Found N error(s) in file:line"
        if (pretty) {
            val errorCount = sorted.size
            val firstDiag = sorted.firstOrNull { it.fileName != null }
            if (errorCount == 1 && firstDiag != null) {
                +"Found 1 error in ${firstDiag.fileName}\u001b[90m:${firstDiag.line}\u001b[0m\r\n"
            } else if (errorCount > 1) {
                val distinctFiles = sorted.mapNotNull { it.fileName }.toSet()
                if (distinctFiles.size == 1 && firstDiag != null) {
                    +"Found $errorCount errors in the same file, starting at: ${firstDiag.fileName}\u001b[90m:${firstDiag.line}\u001b[0m\r\n"
                } else {
                    +"Found $errorCount errors in ${distinctFiles.size} files.\r\n"
                    // Pretty per-file summary table (TypeScript appends this when errors
                    // span multiple files). Count column is right-aligned to width 6
                    // (matching the "Errors" header). Note: the table lines use LF only,
                    // while the summary lines above use CRLF — matches TS output exactly.
                    val byFile = LinkedHashMap<String, MutableList<Diagnostic>>()
                    for (d in sorted) {
                        val fn = d.fileName ?: continue
                        byFile.getOrPut(fn) { mutableListOf() }.add(d)
                    }
                    +"\r\n"
                    +"Errors  Files\n"
                    for ((fn, ds) in byFile) {
                        +"${ds.size.toString().padStart(6)}  $fn\u001b[90m:${ds.first().line}\u001b[0m\n"
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
            // tsconfig.json diagnostics (program-level) sort before source file diagnostics,
            // matching TypeScript's ordering: program/options diagnostics precede per-file diagnostics
            val aIsTsconfig = fileA.endsWith("tsconfig.json")
            val bIsTsconfig = fileB.endsWith("tsconfig.json")
            if (aIsTsconfig && !bIsTsconfig) return@Comparator -1
            if (!aIsTsconfig && bIsTsconfig) return@Comparator 1
            // Lib files (lib.*.d.ts) sort AFTER user files, matching TypeScript's baseline
            // ordering where user-file diagnostics precede lib-side ones.
            fun isLib(f: String): Boolean {
                val base = f.substringAfterLast('/').substringAfterLast('\\')
                return base.startsWith("lib.") && base.endsWith(".d.ts")
            }
            val aIsLib = isLib(fileA)
            val bIsLib = isLib(fileB)
            if (!aIsLib && bIsLib) return@Comparator -1
            if (aIsLib && !bIsLib) return@Comparator 1
            val c = fileA.compareTo(fileB)
            if (c != 0) return@Comparator c
        }
    }
    compareValues(a.start ?: 0, b.start ?: 0).let { if (it != 0) return@Comparator it }
    compareValues(a.length ?: 0, b.length ?: 0).let { if (it != 0) return@Comparator it }
    a.code.compareTo(b.code).let { if (it != 0) return@Comparator it }
    a.message.compareTo(b.message)
}