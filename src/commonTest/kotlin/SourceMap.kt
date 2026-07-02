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

package com.xemantic.typescript.compiler

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private data class MappingEntry(
    val outputLine: Int,
    val outputCol: Int,
    val sourceFileIndex: Int,
    val sourceLine: Int,
    val sourceCol: Int,
)

/**
 * Generates an inline source map data URL for appending to JS output.
 */
@OptIn(ExperimentalEncodingApi::class)
fun generateInlineSourceMapComment(
    sourceTexts: List<String>,
    sourceFileNames: List<String>,
    jsFileName: String,
    jsOutput: String,
    sourceRoot: String = "",
    includeSourcesContent: Boolean = false,
): String {
    val mappings = generateMappings(sourceTexts, jsOutput)
    val json = buildSourceMapJson(
        jsFileName = jsFileName,
        sourceRoot = sourceRoot,
        sourceFileNames = sourceFileNames,
        mappings = mappings,
        sourceTexts = if (includeSourcesContent) sourceTexts else null,
    )
    val base64 = Base64.encode(json.encodeToByteArray())
    return "//# sourceMappingURL=data:application/json;base64,$base64"
}

private fun buildSourceMapJson(
    jsFileName: String,
    sourceRoot: String,
    sourceFileNames: List<String>,
    mappings: String,
    sourceTexts: List<String>?,
): String {
    val sb = StringBuilder()
    sb.append("{\"version\":3")
    sb.append(",\"file\":\"")
    sb.append(jsFileName)
    sb.append("\"")
    sb.append(",\"sourceRoot\":\"")
    sb.append(sourceRoot)
    sb.append("\"")
    sb.append(",\"sources\":[")
    sourceFileNames.forEachIndexed { i, name ->
        if (i > 0) sb.append(",")
        sb.append("\"")
        sb.append(name)
        sb.append("\"")
    }
    sb.append("]")
    sb.append(",\"names\":[]")
    sb.append(",\"mappings\":\"")
    sb.append(mappings)
    sb.append("\"")
    if (sourceTexts != null) {
        sb.append(",\"sourcesContent\":[")
        sourceTexts.forEachIndexed { i, text ->
            if (i > 0) sb.append(",")
            sb.append("\"")
            sb.append(jsonEscapeString(text))
            sb.append("\"")
        }
        sb.append("]")
    }
    sb.append("}")
    return sb.toString()
}

private fun jsonEscapeString(s: String): String {
    val sb = StringBuilder(s.length + 10)
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (ch.code < 0x20) {
                sb.append("\\u")
                sb.append(ch.code.toString(16).padStart(4, '0'))
            } else {
                sb.append(ch)
            }
        }
    }
    return sb.toString()
}

/**
 * Generates VLQ-encoded mappings by matching output lines to source lines.
 */
internal fun generateMappings(
    sourceTexts: List<String>,
    jsOutput: String,
): String {
    val outputLines = jsOutput.split('\n')
    val entries = mutableListOf<MappingEntry>()

    // Build a lookup: source line text → (fileIndex, lineIndex, lineStartOffset)
    data class SourceLineInfo(
        val fileIndex: Int,
        val lineIndex: Int,
        val lineStartOffset: Int,
        val text: String,
    )

    val allSourceLines = mutableListOf<SourceLineInfo>()
    for ((fileIndex, sourceText) in sourceTexts.withIndex()) {
        val lines = sourceText.split('\n')
        var offset = 0
        for ((lineIndex, lineText) in lines.withIndex()) {
            allSourceLines.add(SourceLineInfo(fileIndex, lineIndex, offset, lineText))
            offset += lineText.length + 1 // +1 for \n
        }
    }

    // For each output line, find the corresponding source line
    // Track which source lines have been used to handle duplicates
    var nextSourceIdx = 0

    for ((outputLineIdx, outputLine) in outputLines.withIndex()) {
        val trimmedOutput = outputLine.trimEnd('\r')
        if (trimmedOutput.isEmpty()) continue // skip empty output lines
        if (trimmedOutput == "\"use strict\";") continue // skip use strict

        // Find matching source line starting from nextSourceIdx
        var matchIdx = -1
        for (i in nextSourceIdx until allSourceLines.size) {
            if (allSourceLines[i].text.trimEnd('\r') == trimmedOutput) {
                matchIdx = i
                break
            }
        }
        if (matchIdx < 0) continue // no match found

        val info = allSourceLines[matchIdx]
        nextSourceIdx = matchIdx + 1

        // Collect mapping positions for this source line
        val linePositions = collectLineMappingPositions(
            info.text, info.lineStartOffset, sourceTexts[info.fileIndex]
        )

        for ((srcOffset, srcCol) in linePositions) {
            entries.add(MappingEntry(
                outputLine = outputLineIdx,
                outputCol = srcCol,
                sourceFileIndex = info.fileIndex,
                sourceLine = info.lineIndex,
                sourceCol = srcCol,
            ))
        }
    }

    // Sort by output line, then output column
    entries.sortWith(compareBy({ it.outputLine }, { it.outputCol }))

    return encodeVLQMappings(entries)
}

/**
 * Collects mapping positions for a single source line.
 * Returns list of (absolute offset, column within line).
 */
private fun collectLineMappingPositions(
    lineText: String,
    lineStartOffset: Int,
    fullSourceText: String,
): List<Pair<Int, Int>> {
    if (lineText.isBlank()) return emptyList()

    val positions = mutableListOf<Int>() // columns within the line
    val scanner = Scanner(lineText)
    var inVarDecl = false
    var state = 0 // 0=normal, 1=after var/let/const, 2=after var name

    while (true) {
        val token = scanner.scan()
        if (token == SyntaxKind.EndOfFile) break

        val start = scanner.getTokenPos()
        val end = scanner.getPos()
        val text = scanner.getTokenValue()

        when (token) {
            SyntaxKind.VarKeyword, SyntaxKind.LetKeyword, SyntaxKind.ConstKeyword -> {
                positions.add(start)
                state = 1; inVarDecl = true
            }
            SyntaxKind.Equals -> {
                if (state == 2) { state = 0 } // skip `=` in var init
                else positions.add(start)
            }
            SyntaxKind.Semicolon -> {
                positions.add(start)
                positions.add(end) // statement end
                state = 0; inVarDecl = false
            }
            SyntaxKind.OpenParen -> { positions.add(start) }
            SyntaxKind.CloseParen -> { positions.add(start) }
            SyntaxKind.Dot -> { positions.add(start) }
            SyntaxKind.Comma -> {
                positions.add(start)
                if (inVarDecl) state = 1 // next var name
            }
            SyntaxKind.OpenBrace -> {
                // Don't map open braces for class/function declarations
            }
            SyntaxKind.CloseBrace -> {
                // Map the end position of the closing brace (block end marker)
                positions.add(end)
            }
            else -> {
                if (state == 1 && (token == SyntaxKind.Identifier || token.ordinal >= SyntaxKind.BreakKeyword.ordinal)) {
                    positions.add(start)
                    positions.add(start + text.length) // name end
                    state = 2
                } else {
                    positions.add(start)
                    if (token == SyntaxKind.Identifier) {
                        positions.add(start + text.length)
                    }
                }
            }
        }
    }

    // Check if this line is a comment
    val trimmed = lineText.trim()
    if (trimmed.startsWith("//")) {
        // Single-line comment: map start and end
        val commentStart = lineText.indexOf("//")
        val commentEnd = lineText.length
        positions.clear()
        positions.add(commentStart)
        positions.add(commentEnd)
    }

    return positions.distinct().sorted().map { col -> (lineStartOffset + col) to col }
}

private fun encodeVLQMappings(entries: List<MappingEntry>): String {
    val sb = StringBuilder()
    var prevOutputCol = 0
    var prevSourceFileIndex = 0
    var prevSourceLine = 0
    var prevSourceCol = 0

    val maxLine = entries.maxOfOrNull { it.outputLine } ?: 0

    for (line in 0..maxLine) {
        if (line > 0) sb.append(';')
        val lineEntries = entries.filter { it.outputLine == line }
        prevOutputCol = 0

        for ((idx, entry) in lineEntries.withIndex()) {
            if (idx > 0) sb.append(',')
            sb.append(encodeVLQ(entry.outputCol - prevOutputCol))
            sb.append(encodeVLQ(entry.sourceFileIndex - prevSourceFileIndex))
            sb.append(encodeVLQ(entry.sourceLine - prevSourceLine))
            sb.append(encodeVLQ(entry.sourceCol - prevSourceCol))

            prevOutputCol = entry.outputCol
            prevSourceFileIndex = entry.sourceFileIndex
            prevSourceLine = entry.sourceLine
            prevSourceCol = entry.sourceCol
        }
    }

    return sb.toString()
}

private const val VLQ_BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal fun encodeVLQ(value: Int): String {
    var vlq = if (value < 0) ((-value) shl 1) or 1 else value shl 1
    val sb = StringBuilder()
    do {
        var digit = vlq and 0x1F
        vlq = vlq ushr 5
        if (vlq > 0) digit = digit or 0x20
        sb.append(VLQ_BASE64_CHARS[digit])
    } while (vlq > 0)
    return sb.toString()
}
